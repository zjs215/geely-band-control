package com.otrong.carcontrol.phone;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

final class WearLink {
    private static final String PREF_PREFER_BLUETOOTH_WATCH = "prefer_bluetooth_watch";

    private final SharedPreferences prefs;
    private final XiaomiLink xiaomi;
    private final BluetoothWatchLink bluetoothWatch;
    private final Runnable onChange;
    private boolean preferBluetoothWatch;
    private String lastStatus = "通信服务未启动";
    private List<String> connectedDevices = List.of();
    private boolean lastCanAuthorize;
    private boolean lastAuthorizing;

    WearLink(Context context, Handler main, XiaomiLink.Receiver receiver,
             Consumer<String> onDisconnect, Runnable onChange, XiaomiLink.DiagnosticSink diagnostics) {
        this.prefs = context.getSharedPreferences("garage", Context.MODE_PRIVATE);
        this.preferBluetoothWatch = prefs.getBoolean(PREF_PREFER_BLUETOOTH_WATCH, false);
        this.onChange = onChange;
        this.xiaomi = new XiaomiLink(context, main, receiver, onDisconnect, this::refreshStatus, diagnostics);
        this.xiaomi.setPreferBluetoothWatch(this.preferBluetoothWatch);
        this.bluetoothWatch = new BluetoothWatchLink(context, main, receiver, onDisconnect, this::refreshStatus);
    }

    String status() {
        return lastStatus;
    }

    List<String> connectedDevices() {
        return connectedDevices;
    }

    String diagnosticSummary() {
        return xiaomi.environmentSummary();
    }

    boolean isAuthorizing() {
        return xiaomi.isAuthorizing();
    }

    boolean canAuthorize() {
        return xiaomi.canAuthorize();
    }

    void authorize() {
        xiaomi.authorize();
    }

    void start() {
        xiaomi.setPreferBluetoothWatch(preferBluetoothWatch);
        xiaomi.start();
        bluetoothWatch.start();
        refreshStatus();
    }

    void stop() {
        bluetoothWatch.stop();
        xiaomi.stop();
        refreshStatus();
    }

    void send(String node, String text, Consumer<String> onFailure) {
        if (BluetoothWatchLink.handlesNode(node)) {
            bluetoothWatch.send(node, text, onFailure);
        } else {
            xiaomi.send(node, text, onFailure);
        }
    }

    private void refreshStatus() {
        String x = xiaomi.status();
        boolean btConnected = bluetoothWatch.isConnected();
        boolean nextPrefer = preferBluetoothWatch;
        if (btConnected) {
            nextPrefer = true;
        } else if (x.equals("已收到手环消息") || x.startsWith("手环已连接，等待打开")) {
            nextPrefer = false;
        }
        if (nextPrefer != preferBluetoothWatch) {
            preferBluetoothWatch = nextPrefer;
            prefs.edit().putBoolean(PREF_PREFER_BLUETOOTH_WATCH, nextPrefer).apply();
        }
        xiaomi.setPreferBluetoothWatch(preferBluetoothWatch);
        String combined = combineStatus(x, bluetoothWatch.status(), btConnected, preferBluetoothWatch);
        List<String> devices = new ArrayList<>(xiaomi.connectedDevices());
        devices.addAll(bluetoothWatch.connectedDevices());
        boolean canAuthorize = canAuthorize();
        boolean authorizing = isAuthorizing();
        if (combined.equals(lastStatus) && devices.equals(connectedDevices)
                && canAuthorize == lastCanAuthorize && authorizing == lastAuthorizing) return;
        lastStatus = combined;
        connectedDevices = List.copyOf(devices);
        lastCanAuthorize = canAuthorize;
        lastAuthorizing = authorizing;
        onChange.run();
    }

    static String combineStatus(String xiaomiStatus, String bluetoothStatus, boolean bluetoothConnected) {
        return combineStatus(xiaomiStatus, bluetoothStatus, bluetoothConnected, false);
    }

    static String combineStatus(String xiaomiStatus, String bluetoothStatus,
                                boolean bluetoothConnected, boolean preferBluetoothWatch) {
        String x = xiaomiStatus == null ? "" : xiaomiStatus;
        String b = bluetoothStatus == null ? "" : bluetoothStatus;
        if (bluetoothConnected && !b.isEmpty()) {
            if (x.equals("已收到手环消息")) return "已收到穿戴设备消息";
            if (!x.startsWith("手环已连接，等待打开")) return b;
        }
        if (preferBluetoothWatch && !x.equals("已收到手环消息") && !x.startsWith("手环已连接，等待打开")) {
            return x.equals("通信服务未启动") ? x : "等待手表重连（蓝牙直连）";
        }
        if (!b.isEmpty() && (x.equals("通信服务未启动") || x.equals("正在连接小米运动健康")
                || x.equals("等待小米运动健康连接") || x.equals("等待手环连接") || x.equals("无法启动小米通信 SDK"))) {
            return b;
        }
        return x;
    }
}
