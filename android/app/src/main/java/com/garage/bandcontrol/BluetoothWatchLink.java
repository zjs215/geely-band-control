package com.garage.bandcontrol;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

final class BluetoothWatchLink {
    static final String NODE_PREFIX = "bt:";
    static final String SERVICE_NAME = "GalaxyWatchControl";
    static final UUID SERVICE_UUID = UUID.fromString("9e7c3e5a-4b1d-4d6e-9f2a-8c3b1a7e5d01");
    private static final String TAG = "BluetoothWatchLink";

    private final Context context;
    private final Handler main;
    private final XiaomiLink.Receiver receiver;
    private final Consumer<String> onDisconnect;
    private final Runnable onChange;
    private final Map<String, ClientSession> sessions = new HashMap<>();

    private volatile boolean active;
    private volatile int generation;
    private BluetoothServerSocket serverSocket;
    private Thread acceptThread;
    private String status = "";

    BluetoothWatchLink(Context context, Handler main, XiaomiLink.Receiver receiver,
                       Consumer<String> onDisconnect, Runnable onChange) {
        this.context = context;
        this.main = main;
        this.receiver = receiver;
        this.onDisconnect = onDisconnect;
        this.onChange = onChange;
    }

    static boolean handlesNode(String node) {
        return node != null && node.startsWith(NODE_PREFIX);
    }

    String status() {
        return status;
    }

    boolean isConnected() {
        return !sessions.isEmpty();
    }

    List<String> connectedDevices() {
        List<String> devices = new ArrayList<>(sessions.size());
        for (ClientSession session : sessions.values()) devices.add(session.deviceLabel);
        devices.sort(String::compareTo);
        return devices;
    }

    void start() {
        if (active) return;
        active = true;
        int currentGeneration = ++generation;
        if (!hasBluetoothPermission()) {
            setStatus("");
            return;
        }
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            setStatus("");
            return;
        }
        acceptThread = new Thread(() -> acceptLoop(adapter, currentGeneration), "GalaxyWatchAccept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    void stop() {
        if (!active) return;
        active = false;
        generation++;
        closeQuietly(serverSocket);
        serverSocket = null;
        if (acceptThread != null) {
            acceptThread.interrupt();
            acceptThread = null;
        }
        for (ClientSession session : sessions.values()) {
            session.close();
        }
        sessions.clear();
        setStatus("");
    }

    void send(String node, String text, Consumer<String> onFailure) {
        ClientSession session = sessions.get(node);
        if (!active || session == null) {
            onFailure.accept("not_connected");
            return;
        }
        session.send(text, onFailure);
    }

    private boolean hasBluetoothPermission() {
        return Build.VERSION.SDK_INT < 31
                || context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressLint("MissingPermission")
    private void acceptLoop(BluetoothAdapter adapter, int requestGeneration) {
        while (active && generation == requestGeneration) {
            if (!hasBluetoothPermission() || !adapter.isEnabled()) {
                sleepQuietly(2_000);
                continue;
            }
            try {
                BluetoothServerSocket listener = adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SERVICE_UUID);
                serverSocket = listener;
                while (active && generation == requestGeneration) {
                    BluetoothSocket socket = listener.accept();
                    handleAcceptedSocket(socket, requestGeneration);
                }
            } catch (SecurityException denied) {
                Log.w(TAG, "Bluetooth permission denied while listening for watch", denied);
                return;
            } catch (IOException io) {
                if (active && generation == requestGeneration) {
                    sleepQuietly(1_500);
                }
            } finally {
                closeQuietly(serverSocket);
                serverSocket = null;
            }
        }
    }

    @SuppressLint("MissingPermission")
    private void handleAcceptedSocket(BluetoothSocket socket, int requestGeneration) {
        if (!active || generation != requestGeneration) {
            closeQuietly(socket);
            return;
        }
        BluetoothDevice remote = socket.getRemoteDevice();
        if (remote == null || remote.getBondState() != BluetoothDevice.BOND_BONDED) {
            Log.w(TAG, "Rejected unbonded Bluetooth watch connection");
            closeQuietly(socket);
            return;
        }
        String address = remote.getAddress();
        String node = NODE_PREFIX + address;
        String name = "";
        try {
            name = remote.getName();
        } catch (SecurityException ignored) {
            // The Bluetooth name is optional metadata for an accepted connection.
        }
        String label = (name == null || name.trim().isEmpty()
                ? "手表 (" + address.substring(Math.max(0, address.length() - 5)) + ")" : name.trim()) + " · 蓝牙直连";
        main.post(() -> {
            if (!active || generation != requestGeneration) {
                closeQuietly(socket);
                return;
            }
            ClientSession session = new ClientSession(node, label, socket, requestGeneration);
            ClientSession previous = sessions.put(node, session);
            if (previous != null) previous.close();
            setStatus("手表已连接（蓝牙直连）");
            session.start();
        });
    }

    private void removeSession(String node, ClientSession session, int requestGeneration) {
        main.post(() -> {
            if (generation != requestGeneration || !sessions.remove(node, session)) return;
            onDisconnect.accept(node);
            setStatus(sessions.isEmpty() ? "" : "手表已连接（蓝牙直连）");
        });
    }

    private void setStatus(String value) {
        status = value;
        onChange.run();
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (IOException ignored) {
        }
    }

    private final class ClientSession {
        private final String node;
        private final String deviceLabel;
        private final BluetoothSocket socket;
        private final int requestGeneration;
        private final ExecutorService writer = Executors.newSingleThreadExecutor();
        private volatile boolean closed;

        ClientSession(String node, String deviceLabel, BluetoothSocket socket, int requestGeneration) {
            this.node = node;
            this.deviceLabel = deviceLabel;
            this.socket = socket;
            this.requestGeneration = requestGeneration;
        }

        void start() {
            Thread reader = new Thread(this::readLoop, "GalaxyWatchRead-" + node);
            reader.setDaemon(true);
            reader.start();
        }

        void send(String text, Consumer<String> onFailure) {
            if (closed) {
                onFailure.accept("not_connected");
                return;
            }
            try {
                writer.execute(() -> {
                    if (closed) {
                        main.post(() -> onFailure.accept("not_connected"));
                        return;
                    }
                    try {
                        OutputStream output = socket.getOutputStream();
                        WatchFraming.writeFrame(output, text);
                    } catch (IOException failed) {
                        close();
                        removeSession(node, this, requestGeneration);
                        main.post(() -> onFailure.accept("bt_write_failed"));
                    }
                });
            } catch (RuntimeException rejected) {
                onFailure.accept("bt_queue_closed");
            }
        }

        private void readLoop() {
            try {
                InputStream input = socket.getInputStream();
                while (!closed && active && generation == requestGeneration) {
                    byte[] frame = WatchFraming.readFrame(input);
                    if (frame == null) break;
                    main.post(() -> {
                        if (!active || generation != requestGeneration || closed || sessions.get(node) != this) return;
                        setStatus("已收到手表消息");
                        receiver.onMessage(node, frame);
                    });
                }
            } catch (IOException ignored) {
            } finally {
                close();
                removeSession(node, this, requestGeneration);
            }
        }

        void close() {
            if (closed) return;
            closed = true;
            closeQuietly(socket);
            writer.shutdownNow();
        }
    }
}
