package com.garage.watchcontrol;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class BluetoothPhoneClient {
    static final UUID SERVICE_UUID = UUID.fromString("9e7c3e5a-4b1d-4d6e-9f2a-8c3b1a7e5d01");

    interface Listener {
        void onConnected(String deviceName);
        void onMessage(byte[] data);
        void onDisconnected(String reason);
        void onError(String message);
    }

    record PairedDevice(String address, String name, boolean phoneLikely) {}

    private final Context context;
    private final Handler main;
    private final Listener listener;

    private volatile boolean active;
    private volatile int generation;
    private volatile BluetoothSocket activeSocket;
    private volatile ExecutorService writer;
    private volatile String preferredAddress = "";

    BluetoothPhoneClient(Context context, Handler main, Listener listener) {
        this.context = context;
        this.main = main;
        this.listener = listener;
    }

    boolean hasPermission() {
        return Build.VERSION.SDK_INT < 31
                || context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    void setPreferredAddress(String address) {
        preferredAddress = address == null ? "" : address;
    }

    String preferredAddress() {
        return preferredAddress;
    }

    @SuppressLint("MissingPermission")
    List<PairedDevice> bondedDevices() {
        List<PairedDevice> list = new ArrayList<>();
        if (!hasPermission()) return list;
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null) return list;
        Set<BluetoothDevice> bonded = adapter.getBondedDevices();
        if (bonded == null) return list;
        for (BluetoothDevice device : bonded) {
            BluetoothClass btClass = device.getBluetoothClass();
            boolean phone = btClass != null && btClass.getMajorDeviceClass() == BluetoothClass.Device.Major.PHONE;
            String name = device.getName();
            list.add(new PairedDevice(device.getAddress(), name == null || name.isEmpty() ? device.getAddress() : name, phone));
        }
        list.sort((a, b) -> {
            if (a.address().equals(preferredAddress)) return -1;
            if (b.address().equals(preferredAddress)) return 1;
            if (a.phoneLikely() != b.phoneLikely()) return a.phoneLikely() ? -1 : 1;
            return a.name().compareToIgnoreCase(b.name());
        });
        return list;
    }

    boolean isConnected() {
        BluetoothSocket socket = activeSocket;
        return active && writer != null && socket != null && socket.isConnected();
    }

    void connect() {
        if (active) return;
        if (!hasPermission()) {
            listener.onError("请在手表设置中允许附近设备权限");
            return;
        }
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            listener.onError("请先开启手表蓝牙");
            return;
        }
        active = true;
        int requestGeneration = ++generation;
        Thread connectThread = new Thread(() -> connectAndRead(adapter, requestGeneration), "WatchPhoneConnect");
        connectThread.setDaemon(true);
        connectThread.start();
    }

    void disconnect(String reason) {
        active = false;
        generation++;
        closeSession();
        if (reason != null && !reason.isEmpty()) {
            listener.onDisconnected(reason);
        }
    }

    void send(String text, Runnable onFail) {
        int requestGeneration = generation;
        ExecutorService currentWriter = writer;
        BluetoothSocket socket = activeSocket;
        if (!active || socket == null || currentWriter == null) {
            main.post(onFail);
            return;
        }
        try {
            currentWriter.execute(() -> {
                try {
                    OutputStream out = socket.getOutputStream();
                    WatchFraming.writeFrame(out, text);
                } catch (IOException | SecurityException failed) {
                    main.post(() -> {
                        if (active && generation == requestGeneration) {
                            disconnect("蓝牙连接已断开");
                            onFail.run();
                        }
                    });
                }
            });
        } catch (RuntimeException rejected) {
            main.post(onFail);
        }
    }

    @SuppressLint("MissingPermission")
    private void connectAndRead(BluetoothAdapter adapter, int requestGeneration) {
        List<PairedDevice> candidates;
        try {
            candidates = bondedDevices();
        } catch (SecurityException revoked) {
            candidates = List.of();
        }
        if (candidates.isEmpty()) {
            main.post(() -> {
                if (active && generation == requestGeneration) {
                    active = false;
                    listener.onError("请先与手机完成蓝牙配对");
                }
            });
            return;
        }
        BluetoothSocket connected = null;
        String connectedName = "";
        for (PairedDevice candidate : candidates) {
            if (!active || generation != requestGeneration) return;
            if (!preferredAddress.isEmpty() && !candidate.address().equals(preferredAddress)) continue;
            BluetoothSocket socket = null;
            try {
                BluetoothDevice device = adapter.getRemoteDevice(candidate.address());
                socket = device.createRfcommSocketToServiceRecord(SERVICE_UUID);
                synchronized (this) {
                    if (!active || generation != requestGeneration) {
                        closeQuietly(socket);
                        return;
                    }
                    activeSocket = socket;
                }
                socket.connect();
                connected = socket;
                connectedName = candidate.name();
                break;
            } catch (IOException | SecurityException failed) {
                closeQuietly(socket);
            }
        }
        if (connected == null) {
            reportDisconnected(requestGeneration, "请检查手机端「腕上银河」通信服务");
            return;
        }
        synchronized (this) {
            if (!active || generation != requestGeneration) {
                closeQuietly(connected);
                return;
            }
            writer = Executors.newSingleThreadExecutor();
        }
        String finalName = connectedName;
        main.post(() -> {
            if (active && generation == requestGeneration) {
                listener.onConnected(finalName);
            }
        });
        try {
            InputStream input = connected.getInputStream();
            while (active && generation == requestGeneration) {
                byte[] frame = WatchFraming.readFrame(input);
                if (frame == null) break;
                main.post(() -> {
                    if (active && generation == requestGeneration) {
                        listener.onMessage(frame);
                    }
                });
            }
        } catch (IOException | SecurityException ignored) {
        } finally {
            reportDisconnected(requestGeneration, "手机连接已断开");
        }
    }

    private void reportDisconnected(int requestGeneration, String reason) {
        main.post(() -> {
            if (active && generation == requestGeneration) disconnect(reason);
        });
    }

    private synchronized void closeSession() {
        closeQuietly(activeSocket);
        activeSocket = null;
        if (writer != null) {
            writer.shutdownNow();
            writer = null;
        }
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (IOException ignored) {
        }
    }
}
