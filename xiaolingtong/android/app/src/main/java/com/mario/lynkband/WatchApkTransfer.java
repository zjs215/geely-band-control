package com.mario.lynkband;

import android.annotation.SuppressLint;
import android.app.Activity;

import com.mario.lynkband.transport.WatchUpdateWire;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.SystemClock;
import android.util.Log;

import com.mario.lynkband.transport.PairedDevice;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import java.util.HashSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

public final class WatchApkTransfer {
    private static final AtomicReference<Pending> OFFER = new AtomicReference<>();
    private static final String WATCH_PACKAGE = "com.mario.lynkband.watch";
    private static final String BLUETOOTH_REQUIRED = "请开启手机蓝牙并允许附近设备权限。";
    private static final String WAIT_TIMED_OUT = "等待手表连接超时。请先在手机开始发送，再在手表打开设置 → 接收手机更新。"
            + "如果旧版手表应用没有此入口，请先通过 ADB 安装一次支持接收的手表 APK。";
    static final String TYPE = WatchUpdateWire.TYPE;
    static final int CHUNK_BYTES = WatchUpdateWire.MAX_CHUNK_BYTES;
    private static final int MAX_BYTES = WatchUpdateWire.MAX_APK_BYTES;
    private static final int BUFFER_BYTES = 16 * 1024;
    private static final long TIMEOUT_MS = 180_000L;

    private final WristApp app;

    public WatchApkTransfer(Activity activity) {
        app = (WristApp) activity.getApplication();
    }

    /** Runs on a worker. Both callbacks should return promptly. Progress counts only payload bytes. */
    @SuppressLint("MissingPermission")
    public void send(File apk, BooleanSupplier cancelled, LongConsumer bytesSent) throws IOException {
        String address = app.selectedWatchAddress();
        File snapshot = null;
        TransferGuard guard = new TransferGuard(cancelled, SystemClock::elapsedRealtime, TIMEOUT_MS);
        try {
            try {
                guard.check();
                if (address.isEmpty()) throw new IOException("请先在手机选择已配对的 Watch。");
                int length = checkedLength(apk.length());
                snapshot = File.createTempFile("watch-update-", ".apk", app.getCacheDir());
                byte[] sha256 = snapshot(apk, snapshot, length, guard::check);
                long version = verifyArchive(snapshot);
                guard.check();

                BluetoothAdapter adapter = PairedDevice.adapter(app);
                if (!PairedDevice.allowed(app) || adapter == null || !adapter.isEnabled()) {
                    throw new IOException(BLUETOOTH_REQUIRED);
                }
                if (adapter.getBondedDevices().stream().noneMatch(device -> isSelectedPeer(
                        address, device.getAddress(), device.getBondState() == BluetoothDevice.BOND_BONDED))) {
                    throw new IOException("已选择的 Watch 未与此手机配对，请重新配对后选择手表。");
                }
                if (!app.watch.listeningFor(address)) {
                    throw new IOException("请在手机选择 Android 和已配对的 Watch，并启动穿戴通信。");
                }
                awaitWatch(new Pending(address, snapshot, length, sha256, version, guard, bytesSent));
            } catch (SecurityException denied) {
                guard.check();
                throw new IOException(BLUETOOTH_REQUIRED, denied);
            } catch (IOException failed) {
                guard.check();
                throw failed;
            }
        } finally {
            if (snapshot != null) {
                try {
                    Files.deleteIfExists(snapshot.toPath());
                } catch (IOException failed) {
                    Log.w("WatchApkTransfer", "Could not remove APK snapshot", failed);
                }
            }
        }
    }

    private static void awaitWatch(Pending pending) throws IOException {
        if (!OFFER.compareAndSet(null, pending)) throw new IOException("已有手表更新正在发送。");
        try {
            pending.guard.waitingForWatch = true;
            while (!pending.complete.await(100L, TimeUnit.MILLISECONDS)) pending.guard.check();
            if (pending.failure != null) throw pending.failure;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("手表更新发送已中断。", interrupted);
        } finally {
            OFFER.compareAndSet(pending, null);
        }
    }

    /** Called on the existing authenticated WatchServer session. The reply uses the normal frame. */
    static JSONObject reply(String address, JSONObject request) throws JSONException {
        JSONObject response = new JSONObject().put("v", 1).put("type", TYPE)
                .put("id", request.optString("id"));
        Pending pending = OFFER.get();
        if (pending == null || !pending.address.equals(address)) {
            return response.put("error", "请先在所选手机上开始发送手表更新。");
        }
        return pending.reply(request, response);
    }

    static final class Pending {
        final String address;
        final File snapshot;
        final int length;
        final String sha256;
        final long version;
        final TransferGuard guard;
        final LongConsumer progress;
        final CountDownLatch complete = new CountDownLatch(1);
        volatile IOException failure;
        private String transfer;
        private int next;

        Pending(String address, File snapshot, int length, byte[] sha256, long version,
                TransferGuard guard, LongConsumer progress) {
            this.address = address;
            this.snapshot = snapshot;
            this.length = length;
            StringBuilder hex = new StringBuilder(64);
            for (byte b : sha256) hex.append(Character.forDigit((b & 255) >>> 4, 16))
                    .append(Character.forDigit(b & 15, 16));
            this.sha256 = hex.toString();
            this.version = version;
            this.guard = guard;
            this.progress = progress;
        }

        synchronized JSONObject reply(JSONObject request, JSONObject response) throws JSONException {
            int offset = request.optInt("offset", -1);
            long installed = request.optLong("installed", -1L);
            String token = request.optString("transfer", "");
            boolean staged = request.optBoolean("staged");
            if (request.optInt("v") != 1 || request.optString("id").isEmpty() || installed <= 0
                    || offset < 0 || offset > length || complete.getCount() == 0) {
                return response.put("error", "无效的手表更新请求。");
            }
            try {
                guard.check();
                if (!WatchUpdateWire.versionAllowed(installed, version)) {
                    return fail(response, new IOException("手表 APK 版本低于当前版本，不能降级。"));
                }
                if (token.isEmpty() && offset == 0 && !staged) {
                    transfer = UUID.randomUUID().toString();
                    next = 0;
                    guard.waitingForWatch = false;
                }
                if (transfer == null || (!token.equals(transfer) && !(token.isEmpty() && offset == 0 && !staged))
                        || offset != next || staged != (offset == length)) {
                    return response.put("error", "更新包进度失效，请重新开始接收。");
                }
                if (staged) {
                    complete.countDown();
                    return response.put("staged", true);
                }
                byte[] bytes = new byte[Math.min(CHUNK_BYTES, length - offset)];
                try (RandomAccessFile file = new RandomAccessFile(snapshot, "r")) {
                    file.seek(offset);
                    file.readFully(bytes);
                }
                JSONObject chunk = response.put("transfer", transfer).put("length", length).put("sha256", sha256)
                        .put("offset", offset).put("chunk", Base64.getEncoder().encodeToString(bytes));
                next += bytes.length;
                progress.accept(next);
                return chunk;
            } catch (IOException error) {
                return fail(response, error);
            }
        }

        private JSONObject fail(JSONObject response, IOException error) throws JSONException {
            failure = error;
            complete.countDown();
            return response.put("error", "无法继续接收更新，请查看手机上的提示。");
        }
    }

    private long verifyArchive(File snapshot) throws IOException {
        PackageManager manager = app.getPackageManager();
        int flags = PackageManager.GET_SIGNING_CERTIFICATES;
        PackageInfo archive = manager.getPackageArchiveInfo(snapshot.getAbsolutePath(), flags);
        if (archive == null || !WATCH_PACKAGE.equals(archive.packageName)) {
            throw new IOException("更新文件不是小领通 Watch APK，或 APK 已损坏。");
        }
        PackageInfo phone;
        try {
            phone = manager.getPackageInfo(app.getPackageName(), flags);
        } catch (PackageManager.NameNotFoundException missing) {
            throw new IOException("无法读取当前手机应用的签名。", missing);
        }
        Signature[] expected = currentSigners(phone);
        Signature[] actual = currentSigners(archive);
        if (expected == null || expected.length == 0 || actual == null || actual.length == 0
                || !new HashSet<>(Arrays.asList(expected)).equals(new HashSet<>(Arrays.asList(actual)))) {
            throw new IOException("手表 APK 的签名必须与当前手机应用的签名一致。");
        }
        if (archive.getLongVersionCode() <= 0) throw new IOException("手表 APK 的版本号无效。");
        return archive.getLongVersionCode();
    }

    private static Signature[] currentSigners(PackageInfo info) {
        return info.signingInfo == null ? null : info.signingInfo.getApkContentsSigners();
    }

    private static byte[] snapshot(File source, File target, int length, Check check) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IOException("无法计算更新文件的 SHA-256。", unavailable);
        }
        // Verify and send the same private snapshot even if the worker's source file changes.
        try (InputStream input = new FileInputStream(source); OutputStream output = new FileOutputStream(target)) {
            copyExactly(input, output, length, check, digest);
        }
        return digest.digest();
    }

    static boolean isSelectedPeer(String selected, String remote, boolean bonded) {
        return bonded && !selected.isEmpty() && selected.equals(remote);
    }

    static int checkedLength(long length) throws IOException {
        if (length <= 0 || length > MAX_BYTES) throw new IOException("手表 APK 大小必须在 1 字节至 16 MiB 之间。");
        return (int) length;
    }

    @FunctionalInterface
    interface Check { void run() throws IOException; }

    private static void copyExactly(InputStream input, OutputStream output, int length, Check check,
                                    MessageDigest digest) throws IOException {
        byte[] buffer = new byte[BUFFER_BYTES];
        int total = 0;
        while (total < length) {
            check.run();
            int count = input.read(buffer, 0, Math.min(buffer.length, length - total));
            if (count < 0) throw new IOException("手表 APK 不完整。");
            output.write(buffer, 0, count);
            digest.update(buffer, 0, count);
            total += count;
        }
        if (input.read() != -1) throw new IOException("手表 APK 大小在读取时发生变化。");
    }

    static final class TransferGuard {
        private final BooleanSupplier cancelled;
        private final LongSupplier time;
        private final long startedAt;
        private final long timeoutMillis;
        volatile boolean waitingForWatch;

        TransferGuard(BooleanSupplier cancelled, LongSupplier time, long timeoutMillis) {
            this.cancelled = cancelled;
            this.time = time;
            this.timeoutMillis = timeoutMillis;
            startedAt = time.getAsLong();
        }

        void check() throws IOException {
            if (cancelled.getAsBoolean()) throw new IOException("已取消向手表发送更新。");
            if (time.getAsLong() - startedAt >= timeoutMillis) {
                throw new IOException(waitingForWatch ? WAIT_TIMED_OUT : "手表更新超时，请重新开始发送和接收。");
            }
        }
    }
}
