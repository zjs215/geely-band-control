package com.mario.lynkband.vela;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.*;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.ParcelUuid;
import android.os.SystemClock;

import java.io.*;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;


public final class VelaInstaller {
    private static final UUID STANDARD = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb");
    public interface Progress {
        void report(String stage, int percent);
        default void authenticated() {}
    }

    public enum Mode {
        AUTO("自动识别"), SPP_V1("经典蓝牙 SPP V1"), SPP_V2("经典蓝牙 SPP V2"), BLE("低功耗蓝牙 BLE V2");
        private final String label;
        Mode(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    // Only link setup can change the transport. Never repeat an authentication or install request.
    private static class LinkFailure extends IOException {
        LinkFailure(IOException cause) { super(cause.getMessage(), cause); }
    }

    public static byte[] key(String input) { return BandProtocol.parseKey(input); }

    private static final class UnidentifiedSerial extends LinkFailure {
        UnidentifiedSerial(IOException cause) { super(cause); }
    }

    @SuppressLint("MissingPermission")
    public static boolean send(Context context, BluetoothDevice device, Mode mode, byte[] key, File rpk, int versionCode,
                               BooleanSupplier cancelled, Progress progress) throws IOException {
        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
        try {
            check(cancelled);
            byte[] data = Files.readAllBytes(rpk.toPath());
            LinkFailure sppFailure = null;
            if (mode != Mode.BLE && (mode != Mode.AUTO || device.getType() != BluetoothDevice.DEVICE_TYPE_LE)) {
                try {
                    return sendClassic(context, device, mode, key, data, versionCode, cancelled, timer, progress);
                } catch (LinkFailure e) {
                    if (mode != Mode.AUTO) throw e;
                    sppFailure = e;
                    check(cancelled);
                }
            }
            progress.report("正在连接小米 BLE 服务", -1);
            try (VelaBle ble = new VelaBle(cancelled)) {
                try {
                    ble.connect(context, device);
                } catch (IOException e) {
                    check(cancelled);
                    throw new LinkFailure(e);
                }
                return sendV2(ble.input, ble.output, ble.payloadSize(), key, data, versionCode, cancelled, progress);
            } catch (LinkFailure bleFailure) {
                check(cancelled);
                if (sppFailure instanceof UnidentifiedSerial) {
                    progress.report("未识别到 V2，正在重新连接 SPP V1", -1);
                    return sendClassic(context, device, Mode.SPP_V1, key, data, versionCode, cancelled, timer, progress);
                }
                if (sppFailure == null) throw bleFailure;
                throw new IOException("经典蓝牙：" + sppFailure.getMessage() + " BLE：" + bleFailure.getMessage(), bleFailure);
            }
        } catch (IOException failure) { throw failure; }
        catch (SecurityException denied) { throw new IOException("请在系统设置中允许小领通连接附近设备。", denied); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException("操作已中断。", interrupted); }
        catch (Exception failure) { throw new IOException("Vela 安装未完成，请核对设备授权和连接状态。", failure); }
        finally {
            Arrays.fill(key, (byte) 0);
            timer.shutdownNow();
        }
    }

    private static boolean sendClassic(Context context, BluetoothDevice device, Mode mode, byte[] key, byte[] data,
                                       int version, BooleanSupplier cancelled, ScheduledExecutorService timer,
                                       Progress progress) throws Exception {
        BluetoothSocket connected;
        try { connected = connect(context, device, cancelled, timer, progress); }
        catch (IOException e) { throw new LinkFailure(e); }
        try (BluetoothSocket socket = connected) {
            var stop = timer.scheduleWithFixedDelay(() -> { if (cancelled.getAsBoolean()) closeSocket(socket); },
                    0, 200, TimeUnit.MILLISECONDS);
            var deadline = timer.schedule(() -> closeSocket(socket), 5, TimeUnit.MINUTES);
            try {
                BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
                OutputStream out = socket.getOutputStream();
                SppGreeting.Version reported = SppGreeting.Version.UNKNOWN;
                if (mode == Mode.AUTO) {
                    progress.report("正在识别 SPP 协议", -1);
                    reported = SppGreeting.probe(in, out, cancelled, 1800);
                }
                if (mode == Mode.SPP_V1 || reported == SppGreeting.Version.V1) {
                    try (LegacyInstall session = new LegacyInstall(in, out, cancelled)) {
                        if (reported == SppGreeting.Version.V1) session.greetingReceived();
                        return session.install(key, Build.VERSION.SDK_INT, Build.MODEL, data, version, progress);
                    }
                }
                if (mode == Mode.SPP_V2) { out.write(SppGreeting.HELLO); out.flush(); }
                try { return sendV2(in, out, 0, key, data, version, cancelled, progress); }
                catch (LinkFailure e) {
                    if (mode == Mode.AUTO && reported == SppGreeting.Version.UNKNOWN) throw new UnidentifiedSerial(e);
                    throw e;
                }
            } finally {
                stop.cancel(false);
                deadline.cancel(false);
            }
        }
    }

    private static boolean sendV2(InputStream in, OutputStream out, int blePayload, byte[] key, byte[] data,
                                  int version, BooleanSupplier cancelled, Progress progress) throws Exception {
        try (BandWire wire = new BandWire(in, out); BandProtocol protocol = new BandProtocol(wire)) {
            String link = blePayload > 0 ? "BLE" : "SPP";
            progress.report("正在建立 " + link + " V2 会话", -1);
            try { protocol.openSession(blePayload); }
            catch (IOException e) { throw new LinkFailure(e); }
            check(cancelled);
            progress.report("正在认证 " + link + " V2 设备", -1);
            protocol.authenticateSession(key, Build.VERSION.SDK_INT, Build.MODEL);
            check(cancelled);
            progress.authenticated();
            return protocol.install(data, version, progress::report) == BandProtocol.Result.CONFIRMED;
        }
    }

    static UUID serialService(UUID uuid) {
        return uuid != null && uuid.toString().startsWith("00001101") ? uuid : null;
    }

    @SuppressLint("MissingPermission")
    private static UUID discover(BluetoothDevice device, BooleanSupplier cancelled) throws IOException {
        boolean started = device.fetchUuidsWithSdp();
        for (int i = 0; i < 20; i++) {
            check(cancelled);
            ParcelUuid[] services = device.getUuids();
            if (services != null) for (ParcelUuid service : services) {
                if (service != null && serialService(service.getUuid()) != null) return service.getUuid();
            }
            if (!started) break;
            SystemClock.sleep(100);
        }
        return null;
    }

    @SuppressLint("MissingPermission")
    private static BluetoothSocket connect(Context context, BluetoothDevice device, BooleanSupplier cancelled,
                                          ScheduledExecutorService timer, Progress progress) throws IOException {
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) throw new IOException("请先开启手机蓝牙。");
        if (device.getBondState() != BluetoothDevice.BOND_BONDED) throw new IOException("请先在系统蓝牙设置中配对所选设备。");
        if ((Build.VERSION.SDK_INT < 31 || context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                == PackageManager.PERMISSION_GRANTED) && adapter.isDiscovering()) adapter.cancelDiscovery();
        progress.report("正在查询设备公布的串口服务", -1);
        UUID service = discover(device, cancelled);
        if (service != null) {
            BluetoothSocket socket = serviceSocket(device, service, cancelled, timer, progress);
            if (socket != null) return socket;
        }
        for (int channel : new int[]{5, 1}) {
            for (boolean secure : new boolean[]{false,true}) {
                check(cancelled);
                progress.report("正在连接通道 " + channel + (secure ? "（安全）" : "（兼容）"), -1);
                BluetoothSocket socket;
                try {
                    String method = secure ? "createRfcommSocket" : "createInsecureRfcommSocket";
                    socket = (BluetoothSocket) BluetoothDevice.class.getMethod(method,int.class).invoke(device,channel);
                } catch (ReflectiveOperationException | SecurityException unavailable) { continue; }
                if (attempt(socket, channel == 5 ? 8000 : 6000, cancelled, timer)) return socket;
            }
        }
        if (service == null || !STANDARD.equals(service)) {
            BluetoothSocket socket = serviceSocket(device, STANDARD, cancelled, timer, progress);
            if (socket != null) return socket;
        }
        check(cancelled);
        throw new IOException((service == null ? "未发现 SPP 服务。" : "设备已公布 SPP 服务，连接未成功。")
                + "通道 5 和 1 均未连通。请让手表保持亮屏并检查其他应用是否占用连接。");
    }

    @SuppressLint("MissingPermission")
    private static BluetoothSocket serviceSocket(BluetoothDevice device, UUID uuid, BooleanSupplier cancelled,
                                                ScheduledExecutorService timer, Progress progress) throws IOException {
        for (boolean secure : new boolean[]{false,true}) {
            check(cancelled);
            progress.report(secure ? "正在连接安全串口服务" : "正在连接兼容串口服务", -1);
            BluetoothSocket socket;
            try {
                socket = secure ? device.createRfcommSocketToServiceRecord(uuid) : device.createInsecureRfcommSocketToServiceRecord(uuid);
            } catch (IOException unavailable) { continue; }
            if (attempt(socket,6000,cancelled,timer)) return socket;
        }
        return null;
    }

    @SuppressLint("MissingPermission")
    private static boolean attempt(BluetoothSocket socket, int timeout, BooleanSupplier cancelled,
                                   ScheduledExecutorService timer) {
        if (socket == null) return false;
        AtomicBoolean finished = new AtomicBoolean();
        long until = SystemClock.elapsedRealtime() + timeout;
        var watchdog = timer.scheduleWithFixedDelay(() -> {
            if ((cancelled.getAsBoolean() || SystemClock.elapsedRealtime() >= until) && finished.compareAndSet(false,true)) closeSocket(socket);
        }, 0, 100, TimeUnit.MILLISECONDS);
        boolean connected = false;
        try {
            socket.connect();
            connected = finished.compareAndSet(false,true) && !cancelled.getAsBoolean();
            return connected;
        } catch (IOException failed) { return false; }
        finally {
            watchdog.cancel(false);
            if (!connected) closeSocket(socket);
        }
    }

    private static void check(BooleanSupplier cancelled) throws IOException {
        if (cancelled.getAsBoolean()) throw new IOException("操作已取消。");
    }
    private static void closeSocket(BluetoothSocket socket) {
        try { socket.close(); } catch (IOException ignored) { }
    }
}
