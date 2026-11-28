package com.mario.lynkband;

import android.annotation.SuppressLint;
import android.bluetooth.*;
import android.content.Context;
import android.os.Handler;
import android.os.PowerManager;

import com.mario.lynkband.transport.PairedDevice;
import com.mario.lynkband.transport.WatchWire;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
import org.json.JSONException;
import java.io.IOException;
import java.util.concurrent.FutureTask;
import com.mario.lynkband.transport.WatchReplyWait;

final class WatchServer {
    interface Messages { String reply(String node, byte[] message); }
    private final Context context;
    private final Handler main;
    private final Messages messages;
    private final Runnable disconnect;
    private final Runnable notifyUi;
    private volatile Session session;
    String status = "";

    WatchServer(Context c, Handler h, Messages messages, Runnable disconnect, Runnable notifyUi) {
        this.context = c; this.main = h; this.messages = messages;
        this.disconnect = disconnect; this.notifyUi = notifyUi;
    }
    void start(String address) {
        stop();
        if (address.isEmpty()) return;
        Session next = new Session(address);
        session = next;
        status = "等待 Watch 连接";
        new Thread(next, "LynkWatchListener").start();
    }
    boolean listeningFor(String address) {
        Session current = session;
        return current != null && current.address.equals(address);
    }
    void stop() {
        Session old = session;
        session = null;
        if (old != null) old.shutdown();
        status = "";
    }
    private static void close(Closeable resource) {
        if (resource != null) try { resource.close(); } catch (IOException ignored) {}
    }

    private final class Session implements Runnable {
        final String address;
        private BluetoothServerSocket server;
        private volatile BluetoothSocket peer;
        Session(String address) { this.address = address; }
        synchronized void shutdown() { close(peer); close(server); }
        void update(String value, boolean disconnected) {
            main.post(() -> {
                if (session != this) return;
                status = value;
                if (disconnected) disconnect.run();
                notifyUi.run();
            });
        }
        @Override @SuppressLint("MissingPermission") public void run() {
            BluetoothAdapter adapter = PairedDevice.adapter(context);
            while (session == this) {
                try {
                    if (adapter == null || !PairedDevice.allowed(context) || !adapter.isEnabled()) {
                        update("请开启手机蓝牙并允许附近设备权限", false);
                    } else {
                        try (BluetoothServerSocket listener = adapter.listenUsingRfcommWithServiceRecord("WristLynk", WatchWire.SERVICE)) {
                            synchronized (this) { if (session != this) return; server = listener; }
                            update("等待 Watch 连接", false);
                            while (session == this) {
                                try (BluetoothSocket socket = listener.accept()) {
                                    BluetoothDevice device = socket.getRemoteDevice();
                                    if (!address.equals(device.getAddress()) || device.getBondState() != BluetoothDevice.BOND_BONDED) continue;
                                    synchronized (this) { if (session != this) return; peer = socket; }
                                    update("Watch 已连接", false);
                                    try { serve(socket); } catch (Exception lost) {
                                        android.util.Log.i("WristLynkHost", "Watch session ended", lost);
                                    }
                                    finally { peer = null; update("Watch 已断开", true); }
                                }
                            }
                        }
                    }
                } catch (IOException | SecurityException unavailable) {
                    update("等待手表蓝牙服务恢复", false);
                }
                if (session != this) return;
                try { Thread.sleep(1500); } catch (InterruptedException stop) { return; }
            }
        }
        private void serve(BluetoothSocket socket) throws Exception {
            BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
            PowerManager.WakeLock awake = context.getSystemService(PowerManager.class)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WristLynk:watchSession");
            awake.setReferenceCounted(false);
            try {
                awake.acquire(30_000L);
                boolean firstReply = true;
                while (session == this) {
                    byte[] frame = WatchWire.read(input);
                    // Polls arrive every four seconds. A silent peer can't keep the phone awake indefinitely.
                    awake.acquire(30_000L);
                    JSONObject request;
                    try { request = new JSONObject(new String(frame, StandardCharsets.UTF_8)); }
                    catch (JSONException invalid) { request = null; }
                    if (request != null && WatchApkTransfer.TYPE.equals(request.optString("type"))) {
                        WatchWire.writeReply(socket.getOutputStream(), WatchApkTransfer.reply(address, request).toString());
                        continue;
                    }
                    String progress = WatchReplyWait.progressFor(frame);
                    long queuedAt = android.os.SystemClock.elapsedRealtime();
                    FutureTask<String> result = new FutureTask<>(() -> {
                        if (session != this || peer != socket) return null;
                        if (android.os.SystemClock.elapsedRealtime() - queuedAt >= 6000) return WatchReplyWait.expired(frame);
                        return messages.reply("watch:" + address, frame);
                    });
                    main.post(result);
                    String response;
                    try {
                        response = WatchReplyWait.await(result, progress == null ? null :
                                () -> WatchWire.writeReply(socket.getOutputStream(), progress));
                    }
                    finally { result.cancel(false); }
                    if (response != null) {
                        WatchWire.writeReply(socket.getOutputStream(), response);
                        if (firstReply) {
                            firstReply = false;
                            android.util.Log.i("WristLynkHost", "Replied to paired watch, phone interactive="
                                    + context.getSystemService(PowerManager.class).isInteractive()
                                    + ", locked=" + context.getSystemService(android.app.KeyguardManager.class).isKeyguardLocked());
                        }
                    }
                }
            } finally {
                if (awake.isHeld()) awake.release();
            }
        }
    }
}
