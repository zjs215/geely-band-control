package com.otrong.carcontrol.phone;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.util.Log;

import com.xiaomi.xms.wearable.Wearable;
import com.xiaomi.xms.wearable.auth.AuthApi;
import com.xiaomi.xms.wearable.auth.Permission;
import com.xiaomi.xms.wearable.message.MessageApi;
import com.xiaomi.xms.wearable.node.Node;
import com.xiaomi.xms.wearable.node.NodeApi;
import com.xiaomi.xms.wearable.service.OnServiceConnectionListener;
import com.xiaomi.xms.wearable.service.ServiceApi;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

final class XiaomiLink {
    interface Receiver {
        void onMessage(String node, byte[] data);
    }

    interface DiagnosticSink {
        void log(String title, String detail, boolean warning);
    }

    private static final String TAG = "XiaomiLink";
    private static final String SERVICE_ACTION = "com.xiaomi.wearable.XMS_WEARABLE_SERVICE";
    private static final String MI_HEALTH_PACKAGE = "com.mi.health";
    private static final String LEGACY_WEARABLE_PACKAGE = "com.xiaomi.wearable";
    private static final long AUTHORIZE_TIMEOUT_MS = 15_000;

    private final Context context;
    private final Handler main;
    private final Receiver receiver;
    private final Consumer<String> onDisconnect;
    private final Runnable onChange;
    private final DiagnosticSink diagnostics;
    private final Set<String> connected = new HashSet<>();
    private final Set<String> listening = new HashSet<>();
    private final Set<String> permissionNeeded = new HashSet<>();
    private final Set<String> registering = new HashSet<>();
    private final Set<String> checkedWearApp = new HashSet<>();
    private final Runnable poll = this::poll;
    private final Runnable authorizeTimeout = this::onAuthorizeTimeout;
    private final OnServiceConnectionListener serviceListener = new OnServiceConnectionListener() {
        @Override public void onServiceConnected() {
            main.post(() -> {
                if (!active) return;
                diagnostics.log("小米穿戴服务已绑定", environmentSummary, false);
            });
        }

        @Override public void onServiceDisconnected() {
            main.post(() -> {
                if (!active) return;
                lastPollResult = pollSequence;
                connectedDevices = List.of();
                setStatus("等待小米运动健康连接");
                diagnostics.log("小米穿戴服务连接断开", "小米运动健康服务可能被系统回收或重启", true);
            });
        }
    };

    private MessageApi messages;
    private NodeApi nodes;
    private AuthApi auth;
    private ServiceApi serviceApi;
    private boolean serviceListenerRegistered;
    private boolean active;
    private boolean authorizing;
    private int generation;
    private int authorizeGeneration;
    private int pollSequence;
    private int lastPollResult;
    private String status = "通信服务未启动";
    private String environmentSummary = "未检查";
    private String lastPollError = "";
    private String lastCheckError = "";
    private String lastNodeSignature = "";
    private List<String> connectedDevices = List.of();
    private boolean emptyNodesLogged;
    private boolean preferBluetoothWatch;

    XiaomiLink(Context context, Handler main, Receiver receiver, Consumer<String> onDisconnect,
               Runnable onChange, DiagnosticSink diagnostics) {
        this.context = context;
        this.main = main;
        this.receiver = receiver;
        this.onDisconnect = onDisconnect;
        this.onChange = onChange;
        this.diagnostics = diagnostics == null ? (title, detail, warning) -> {} : diagnostics;
    }

    void setPreferBluetoothWatch(boolean prefer) {
        this.preferBluetoothWatch = prefer;
    }

    String status() {
        return status;
    }

    List<String> connectedDevices() {
        return connectedDevices;
    }

    String environmentSummary() {
        return environmentSummary;
    }

    boolean isAuthorizing() {
        return active && authorizing;
    }

    boolean canAuthorize() {
        return active && !permissionNeeded.isEmpty() && !authorizing;
    }

    void authorize() {
        if (!canAuthorize()) return;
        String node = permissionNeeded.iterator().next();
        int requestGeneration = generation;
        authorizing = true;
        authorizeGeneration = requestGeneration;
        main.removeCallbacks(authorizeTimeout);
        main.postDelayed(authorizeTimeout, AUTHORIZE_TIMEOUT_MS);
        setStatus("请在小米运动健康中确认设备授权");
        diagnostics.log("发起小米设备授权", "目标节点 " + WearDiagnostics.formatNodeLabel(node, ""), false);
        try {
            auth.requestPermission(node, Permission.DEVICE_MANAGER)
                    .addOnSuccessListener(granted -> main.post(() -> {
                        if (!active || generation != requestGeneration) return;
                        cancelAuthorizationWait();
                        List<String> grantedNames = new ArrayList<>();
                        boolean hasDeviceManager = false;
                        if (granted != null) {
                            for (Permission item : granted) {
                                if (item == null || item.getName() == null) continue;
                                grantedNames.add(item.getName());
                                if (Permission.DEVICE_MANAGER.getName().equals(item.getName())) {
                                    hasDeviceManager = true;
                                }
                            }
                        }
                        if (hasDeviceManager && connected.contains(node)) {
                            permissionNeeded.remove(node);
                            diagnostics.log("设备授权已通过", String.join(", ", grantedNames), false);
                            register(node, requestGeneration);
                            return;
                        }
                        if (grantedNames.isEmpty() && !connected.contains(node)) {
                            permissionNeeded.remove(node);
                        }
                        diagnostics.log("设备授权未授予",
                                grantedNames.isEmpty() ? "未返回已授予权限" : "返回权限: " + String.join(", ", grantedNames),
                                true);
                        setStatus("小米设备通信权限未授予");
                    }))
                    .addOnFailureListener(error -> main.post(() -> authorizationFailed(requestGeneration, node, error)));
        } catch (RuntimeException unavailable) {
            authorizationFailed(requestGeneration, node, unavailable);
        }
    }

    private void onAuthorizeTimeout() {
        if (!active || !authorizing || generation != authorizeGeneration) return;
        authorizing = false;
        diagnostics.log(
                "设备授权超时",
                "15 秒内未收到小米运动健康回调。请检查「小米运动健康」的后台弹出界面权限，并确认手表是否运行 Vela 系统且已安装腕上车控 RPK",
                true);
        setStatus("设备授权超时，请检查小米运动健康后重试");
    }

    private void cancelAuthorizationWait() {
        authorizing = false;
        main.removeCallbacks(authorizeTimeout);
    }

    private void authorizationFailed(int requestGeneration, String node, Throwable error) {
        if (!active || generation != requestGeneration) return;
        cancelAuthorizationWait();
        if (WearDiagnostics.isMissingWearAppError(error)) {
            permissionNeeded.remove(node);
        }
        String detail = WearDiagnostics.describeSdkError(error);
        Log.w(TAG, "Authorization failed: " + detail, error);
        diagnostics.log("设备授权失败", detail, true);
        setStatus("设备授权未完成：" + detail);
    }

    void start() {
        if (active) return;
        active = true;
        generation++;
        lastPollError = "";
        lastCheckError = "";
        lastNodeSignature = "";
        emptyNodesLogged = false;
        environmentSummary = inspectWearableService();
        setStatus("正在连接小米运动健康");
        try {
            messages = Wearable.getMessageApi(context);
            nodes = Wearable.getNodeApi(context);
            auth = Wearable.getAuthApi(context);
            serviceApi = Wearable.getServiceApi(context);
            serviceApi.registerServiceConnectionListener(serviceListener);
            serviceListenerRegistered = true;
            poll();
        } catch (RuntimeException unavailable) {
            active = false;
            String detail = WearDiagnostics.describeSdkError(unavailable);
            Log.w(TAG, "Wearable service initialization failed", unavailable);
            diagnostics.log("小米通信 SDK 启动失败", detail + " | " + environmentSummary, true);
            setStatus("无法启动小米通信 SDK");
        }
    }

    void stop() {
        if (!active) return;
        active = false;
        generation++;
        main.removeCallbacks(poll);
        cancelAuthorizationWait();
        if (serviceListenerRegistered && serviceApi != null) {
            try {
                serviceApi.unregisterServiceConnectionListener(serviceListener);
            } catch (RuntimeException ignored) {
            }
            serviceListenerRegistered = false;
        }
        for (String id : listening) messages.removeListener(id);
        listening.clear();
        connected.clear();
        permissionNeeded.clear();
        registering.clear();
        checkedWearApp.clear();
        connectedDevices = List.of();
        setStatus("通信服务未启动");
    }

    void send(String node, String text, Consumer<String> onFailure) {
        if (!active || !listening.contains(node)) {
            onFailure.accept("not_connected");
            return;
        }
        try {
            messages.sendMessage(node, text.getBytes(StandardCharsets.UTF_8))
                    .addOnFailureListener(error -> main.post(() -> {
                        String detail = WearDiagnostics.describeSdkError(error);
                        Log.w(TAG, "sendMessage failed: " + detail, error);
                        onFailure.accept(detail);
                    }));
        } catch (RuntimeException unavailable) {
            onFailure.accept(WearDiagnostics.describeSdkError(unavailable));
        }
    }

    private String inspectWearableService() {
        PackageManager pm = context.getPackageManager();
        String miHealthVersion = packageVersion(pm, MI_HEALTH_PACKAGE);
        String resolvedPackage = resolveWearableServicePackage(pm);
        if (resolvedPackage.isEmpty()) {
            diagnostics.log("未检测到小米通信服务",
                    "未解析到 " + SERVICE_ACTION + "（小米运动健康版本: " + miHealthVersion + "）", true);
            return "未解析到穿戴服务 (小米运动健康: " + miHealthVersion + ")";
        }
        String summary = "服务包 " + resolvedPackage + " · 小米运动健康 " + miHealthVersion;
        diagnostics.log("检测到小米通信环境", summary, false);
        return summary;
    }

    private static String packageVersion(PackageManager pm, String packageName) {
        try {
            PackageInfo info = pm.getPackageInfo(packageName, 0);
            return info.versionName == null ? "已安装" : info.versionName;
        } catch (PackageManager.NameNotFoundException missing) {
            return "未安装";
        } catch (RuntimeException unavailable) {
            return "读取失败";
        }
    }

    private static String resolveWearableServicePackage(PackageManager pm) {
        for (String pkg : new String[]{MI_HEALTH_PACKAGE, LEGACY_WEARABLE_PACKAGE}) {
            try {
                ResolveInfo info = pm.resolveService(new Intent(SERVICE_ACTION).setPackage(pkg), 0);
                if (info != null) return pkg;
            } catch (RuntimeException ignored) {
            }
        }
        return "";
    }

    private void poll() {
        if (!active) return;
        int requestGeneration = generation;
        int sequence = ++pollSequence;
        try {
            nodes.getConnectedNodes()
                    .addOnSuccessListener(result -> main.post(() -> {
                        if (!acceptPollResult(requestGeneration, sequence)) return;
                        updateNodes(result, requestGeneration);
                    }))
                    .addOnFailureListener(error -> main.post(() -> {
                        if (acceptPollResult(requestGeneration, sequence)) pollFailed(error);
                    }));
        } catch (RuntimeException unavailable) {
            if (acceptPollResult(requestGeneration, sequence)) pollFailed(unavailable);
        }
        main.postDelayed(poll, 5_000);
    }

    private boolean acceptPollResult(int requestGeneration, int sequence) {
        if (!active || generation != requestGeneration || sequence <= lastPollResult) return false;
        lastPollResult = sequence;
        return true;
    }

    private void pollFailed(Throwable error) {
        connectedDevices = List.of();
        String detail = WearDiagnostics.describeSdkError(error);
        if (!detail.equals(lastPollError)) {
            lastPollError = detail;
            Log.w(TAG, "getConnectedNodes failed: " + detail, error);
            diagnostics.log("获取小米穿戴设备失败", detail, true);
        }
        setStatus("等待小米运动健康连接");
    }

    private void updateNodes(Iterable<Node> result, int requestGeneration) {
        lastPollError = "";
        Set<String> current = new HashSet<>();
        List<String> labels = new ArrayList<>();
        List<String> devices = new ArrayList<>();
        for (Node node : result) {
            if (node != null && node.id != null && !node.id.isEmpty() && current.add(node.id)) {
                String label = WearDiagnostics.formatNodeLabel(node.id, node.name);
                labels.add(label);
                String name = node.name == null ? "" : node.name.trim();
                devices.add((name.isEmpty() ? "小米穿戴设备 (" + label + ")" : name) + " · 小米运动健康");
            }
        }
        for (String previous : connected) {
            if (!current.contains(previous)) onDisconnect.accept(previous);
        }
        connected.clear();
        connected.addAll(current);
        for (String previous : new HashSet<>(listening)) {
            if (!current.contains(previous)) {
                messages.removeListener(previous);
                listening.remove(previous);
            }
        }
        permissionNeeded.retainAll(current);
        registering.retainAll(current);
        checkedWearApp.retainAll(current);
        devices.sort(String::compareTo);
        updateConnectedDevices(devices);
        if (current.isEmpty()) {
            lastNodeSignature = "";
            if (!emptyNodesLogged) {
                emptyNodesLogged = true;
                diagnostics.log("小米运动健康暂未返回已连接设备", "请确认手表在小米运动健康内显示已连接", false);
            }
            setStatus("等待手环连接");
            return;
        }
        emptyNodesLogged = false;
        labels.sort(String::compareTo);
        String signature = String.join(", ", labels);
        if (!signature.equals(lastNodeSignature)) {
            lastNodeSignature = signature;
            diagnostics.log("发现已连接小米设备", signature, false);
        }
        for (String id : current) {
            if (listening.contains(id) || (authorizing && permissionNeeded.contains(id)) || !registering.add(id)) continue;
            auth.checkPermission(id, Permission.DEVICE_MANAGER).addOnSuccessListener(granted -> main.post(() -> {
                registering.remove(id);
                if (!active || generation != requestGeneration || !connected.contains(id)) return;
                lastCheckError = "";
                if (Boolean.TRUE.equals(granted)) {
                    permissionNeeded.remove(id);
                    register(id, requestGeneration);
                } else {
                    permissionNeeded.add(id);
                    setStatus("手环已连接，请授权设备通信");
                }
            })).addOnFailureListener(error -> main.post(() -> {
                registering.remove(id);
                if (!active || generation != requestGeneration) return;
                String detail = WearDiagnostics.describeSdkError(error);
                if (WearDiagnostics.isMissingWearAppError(error)) {
                    permissionNeeded.remove(id);
                    if (!preferBluetoothWatch && !detail.equals(lastCheckError)) {
                        lastCheckError = detail;
                        Log.w(TAG, "checkPermission failed (missing/mismatched RPK): " + detail, error);
                        diagnostics.log("穿戴端未检测到可用 RPK", detail, true);
                    }
                    setStatus(detail);
                    return;
                }
                permissionNeeded.add(id);
                if (!detail.equals(lastCheckError)) {
                    lastCheckError = detail;
                    Log.w(TAG, "checkPermission failed: " + detail, error);
                    diagnostics.log("读取设备授权状态失败", detail, true);
                }
                setStatus("无法读取小米授权状态：" + detail);
            }));
        }
    }

    private void register(String id, int requestGeneration) {
        if (!listening.add(id)) return;
        checkWearAppInstalled(id, requestGeneration);
        messages.addListener(id, (device, data) -> main.post(() -> {
            if (!active || generation != requestGeneration || !connected.contains(id)
                    || !listening.contains(id) || !id.equals(device)) return;
            setStatus("已收到手环消息");
            receiver.onMessage(id, data);
        })).addOnSuccessListener(value -> main.post(() -> {
            if (active && generation == requestGeneration && !status.equals("已收到手环消息")) {
                setStatus("手环已连接，等待打开腕上车控");
            }
        })).addOnFailureListener(error -> main.post(() -> {
            if (!active || generation != requestGeneration) return;
            listening.remove(id);
            String detail = WearDiagnostics.describeSdkError(error);
            Log.w(TAG, "addListener failed: " + detail, error);
            diagnostics.log("消息监听注册失败", detail, true);
            setStatus("手环已连接，消息监听失败");
        }));
    }

    private void checkWearAppInstalled(String id, int requestGeneration) {
        if (!checkedWearApp.add(id)) return;
        try {
            nodes.isWearAppInstalled(id)
                    .addOnSuccessListener(installed -> main.post(() -> {
                        if (!active || generation != requestGeneration || !connected.contains(id)) return;
                        if (Boolean.TRUE.equals(installed)) {
                            diagnostics.log("穿戴端应用已就绪", "手表/手环已安装腕上车控 RPK", false);
                        } else {
                            diagnostics.log("穿戴端未检测到腕上车控",
                                    "小米运动健康报告该设备未安装包名 " + BuildConfig.APPLICATION_ID + " 的 RPK，或不支持 Vela 应用",
                                    true);
                        }
                    }))
                    .addOnFailureListener(error -> main.post(() -> {
                        if (!active || generation != requestGeneration) return;
                        String detail = WearDiagnostics.describeSdkError(error);
                        Log.w(TAG, "isWearAppInstalled failed: " + detail, error);
                        diagnostics.log("穿戴端应用安装状态未知", detail, true);
                    }));
        } catch (RuntimeException unavailable) {
            diagnostics.log("穿戴端应用检查异常", WearDiagnostics.describeSdkError(unavailable), true);
        }
    }

    private void setStatus(String value) {
        status = value;
        onChange.run();
    }

    private void updateConnectedDevices(List<String> devices) {
        if (connectedDevices.equals(devices)) return;
        connectedDevices = List.copyOf(devices);
        onChange.run();
    }
}
