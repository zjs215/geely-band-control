package com.otrong.carcontrol.phone;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Pure-Java diagnostic formatting helpers for wearable connectivity troubleshooting.
 */
final class WearDiagnostics {
    private WearDiagnostics() {}

    private static Throwable unwrap(Throwable error) {
        Throwable root = error;
        while (root != null && root.getCause() != null && root.getCause() != root
                && root.getClass() == RuntimeException.class) {
            root = root.getCause();
        }
        return root;
    }

    static boolean isMissingWearAppError(Throwable error) {
        Throwable root = unwrap(error);
        if (root == null) return false;
        String type = root.getClass().getSimpleName();
        return "AppNotInstalledException".equals(type) || "SignatureVerifyFailedException".equals(type);
    }

    static String describeSdkError(Throwable error) {
        Throwable root = unwrap(error);
        if (root == null) return "未知错误";
        String type = root.getClass().getSimpleName();
        String message = root.getMessage();
        return switch (type) {
            case "AppNotInstalledException" -> "未检测到腕上车控 RPK（若用手表 APK 请忽略，AppNotInstalled）";
            case "SignatureVerifyFailedException" -> "手机 APK 与手表 RPK 签名证书不一致 (SignatureVerifyFailed)";
            case "PermissionDeniedException" -> "小米运动健康拒绝了设备通信授权 (PermissionDenied)";
            case "DeviceDisconnectedException" -> "穿戴设备未连接或已断开 (DeviceDisconnected)";
            default -> {
                if (message == null || message.trim().isEmpty()) {
                    yield type.isEmpty() ? "未知异常" : type;
                }
                yield type + ": " + message.trim();
            }
        };
    }

    static String formatNodeLabel(String id, String name) {
        String shortId = shortenId(id);
        if (name == null || name.trim().isEmpty()) return shortId;
        if (shortId.isEmpty()) return name.trim();
        return name.trim() + " (" + shortId + ")";
    }

    private static String shortenId(String id) {
        if (id == null) return "";
        String trimmed = id.trim();
        if (trimmed.length() <= 10) return trimmed;
        return trimmed.substring(0, 4) + "…" + trimmed.substring(trimmed.length() - 4);
    }

    static String compactLinkStatus(String linkStatus, boolean serviceRunning) {
        if (!serviceRunning) return "通信未启动";
        if (linkStatus == null || linkStatus.trim().isEmpty()) return "正在等待连接";
        if (linkStatus.equals("已收到手环消息") || linkStatus.equals("已收到手表消息")
                || linkStatus.equals("已收到穿戴设备消息")) {
            return "应用已连接";
        }
        if (linkStatus.startsWith("等待手表重连")) return "等待手表重连";
        if (linkStatus.startsWith("手表已连接")) return "手表已连接";
        if (linkStatus.equals("手环已连接，等待打开腕上车控")) return "设备已连接，等待打开腕上车控";
        if (linkStatus.equals("通信服务未启动") || linkStatus.equals("正在连接小米运动健康")) {
            return "正在等待连接";
        }
        return linkStatus;
    }

    static boolean isWarningStatus(String status) {
        if (status == null) return false;
        return status.contains("失败") || status.contains("无法") || status.contains("超时")
                || status.contains("未授予") || status.contains("未检测") || status.contains("未安装")
                || status.contains("不一致");
    }

    static String formatDiagnosticExport(
            String appVersion,
            int sdkInt,
            String deviceModel,
            String environmentSummary,
            boolean serviceRunning,
            String linkStatus,
            boolean canAuthorize,
            boolean authorizing,
            List<GarageApp.UiEvent> events) {
        StringBuilder out = new StringBuilder();
        out.append("口袋车控诊断信息\n");
        out.append("伴侣版本: ").append(appVersion).append('\n');
        out.append("系统: Android API ").append(sdkInt).append(" / ").append(deviceModel).append('\n');
        if (environmentSummary != null && !environmentSummary.isEmpty()) {
            out.append("通道环境: ").append(environmentSummary).append('\n');
        }
        out.append("通信服务: ").append(serviceRunning ? "运行中" : "已停止")
                .append(" | 状态: ").append(linkStatus)
                .append(" | 可授权: ").append(canAuthorize)
                .append(" | 授权等待中: ").append(authorizing).append('\n');
        out.append("--- 最近运行日志 ---\n");
        if (events == null || events.isEmpty()) {
            out.append("暂无记录\n");
            return out.toString();
        }
        SimpleDateFormat time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
        for (int i = events.size() - 1; i >= 0; i--) {
            GarageApp.UiEvent event = events.get(i);
            out.append('[').append(time.format(new Date(event.time()))).append("] ");
            if (event.warning()) out.append("[!] ");
            out.append(event.title());
            if (event.detail() != null && !event.detail().isEmpty()) {
                out.append(" — ").append(event.detail());
            }
            out.append('\n');
        }
        return out.toString();
    }
}
