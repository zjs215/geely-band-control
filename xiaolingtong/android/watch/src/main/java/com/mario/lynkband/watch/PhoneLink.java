package com.mario.lynkband.watch;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.graphics.Bitmap;
import org.json.JSONException;
import org.json.JSONObject;
import android.os.Handler;
import android.os.SystemClock;
import com.mario.lynkband.transport.WatchReplyWait;
import com.mario.lynkband.transport.WatchWire;
import com.mario.lynkband.transport.WatchUpdateWire;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class PhoneLink {
    interface Listener {
        void connected();
        void received(JSONObject message, Bitmap photo);
        void updateReceived(JSONObject message);
        void lost(String reason);
    }
    private final Handler main;
    private final Listener listener;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService reader = Executors.newSingleThreadExecutor();
    private BluetoothSocket socket;
    private boolean ready;
    private String pendingType = "";
    private final Runnable timeout = () -> {
        android.util.Log.w("WristLynkLink", ready ? "Reply timed out: " + pendingType : "Connect timed out");
        fail("手机未响应");
    };
    private String pendingId;
    private long replyDeadline;

    PhoneLink(Handler main, Listener listener) { this.main = main; this.listener = listener; }

    @SuppressLint("MissingPermission")
    void connect(BluetoothAdapter adapter, String address) {
        close();
        try {
            BluetoothDevice phone = adapter.getRemoteDevice(address);
            if (phone.getBondState() != BluetoothDevice.BOND_BONDED) { listener.lost("请重新配对并选择手机"); return; }
            BluetoothSocket current = phone.createRfcommSocketToServiceRecord(WatchWire.SERVICE);
            socket = current;
            main.postDelayed(timeout, 12000);
            worker.execute(() -> {
                try {
                    current.connect();
                    BufferedInputStream stream = new BufferedInputStream(current.getInputStream());
                    main.post(() -> {
                        if (socket != current) return;
                        main.removeCallbacks(timeout);
                        ready = true;
                        reader.execute(() -> readReplies(current, stream));
                        listener.connected();
                    });
                } catch (IOException | SecurityException error) {
                    android.util.Log.i("WristLynkLink", "Bluetooth connect failed", error);
                    failed(current, error);
                }
            });
        } catch (IllegalArgumentException | IOException | SecurityException error) { fail("无法打开手机蓝牙连接"); }
    }

    void send(JSONObject request) {
        BluetoothSocket current = socket;
        if (!ready || current == null) { fail("手机未连接"); return; }
        pendingType = request.optString("type");
        pendingId = request.optString("id");
        replyDeadline = SystemClock.uptimeMillis() + WatchReplyWait.DEADLINE_MS + 2000;
        String frame = request.toString();
        armReplyTimeout();
        worker.execute(() -> {
            try {
                WatchWire.write(current.getOutputStream(), frame);
            } catch (IOException | SecurityException error) { failed(current, error); }
        });
    }

    boolean idleForUpdate() { return ready && pendingId == null; }

    boolean sendUpdate(JSONObject request) {
        if (!idleForUpdate()) return false;
        send(request);
        return true;
    }

    void cancelUpdate() {
        if (!WatchUpdateWire.TYPE.equals(pendingType)) return;
        clearReply();
    }

    private void clearReply() {
        main.removeCallbacks(timeout);
        pendingId = null;
        pendingType = "idle";
    }

    private void armReplyTimeout() {
        main.removeCallbacks(timeout);
        main.postAtTime(timeout, Math.min(replyDeadline, SystemClock.uptimeMillis() + WatchReplyWait.SILENCE_MS));
    }

    private void readReplies(BluetoothSocket current, BufferedInputStream stream) {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                JSONObject response = new JSONObject(new String(WatchWire.readReply(stream), StandardCharsets.UTF_8));
                boolean update = WatchUpdateWire.TYPE.equals(response.opt("type"));
                Bitmap photo = update ? null : WatchPhoto.decode(response.optJSONObject("data"));
                main.post(() -> {
                    if (current != socket || pendingId == null || !pendingId.equals(response.opt("id"))) return;
                    if (response.has("working")) {
                        if (WatchReplyWait.matches(response, pendingId)) armReplyTimeout();
                        return;
                    }
                    if (update != WatchUpdateWire.TYPE.equals(pendingType)) {
                        fail("手机更新协议不兼容，请更新手机伴侣");
                        return;
                    }
                    clearReply();
                    if (update) listener.updateReceived(response);
                    else listener.received(response, photo);
                });
            }
        } catch (JSONException invalid) {
            main.post(() -> { if (socket == current) fail("手机响应不兼容，请更新两端应用"); });
        } catch (IOException | SecurityException error) { failed(current, error); }
    }
    private void failed(BluetoothSocket previous, Exception error) {
        main.post(() -> {
            if (socket != previous) return;
            android.util.Log.i("WristLynkLink", "Bluetooth I/O ended during " + pendingType, error);
            fail("请检查手机上的穿戴通信和手表选择");
        });
    }
    private void fail(String message) { close(); listener.lost(message); }
    boolean ready() { return ready; }
    void close() {
        clearReply();
        BluetoothSocket previous = socket;
        socket = null; ready = false;
        if (previous != null) try { previous.close(); } catch (IOException ignored) {}
    }
    void destroy() { close(); worker.shutdownNow(); reader.shutdownNow(); }
}
