package com.otrong.carcontrol.phone;

import com.xiaomi.xms.wearable.exception.AppNotInstalledException;
import com.xiaomi.xms.wearable.exception.DeviceDisconnectedException;
import com.xiaomi.xms.wearable.exception.PermissionDeniedException;
import com.xiaomi.xms.wearable.exception.SignatureVerifyFailedException;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class WearDiagnosticsTest {
    @Test public void describesKnownXiaomiSdkExceptionsClearly() {
        assertTrue(WearDiagnostics.describeSdkError(new AppNotInstalledException("app not installed"))
                .contains("AppNotInstalled"));
        assertTrue(WearDiagnostics.isMissingWearAppError(new AppNotInstalledException("app not installed")));
        assertTrue(WearDiagnostics.describeSdkError(new SignatureVerifyFailedException("fingerprint verify failed"))
                .contains("SignatureVerifyFailed"));
        assertTrue(WearDiagnostics.isMissingWearAppError(new SignatureVerifyFailedException("fingerprint verify failed")));
        assertFalse(WearDiagnostics.isMissingWearAppError(new PermissionDeniedException("permission denied")));
        assertFalse(WearDiagnostics.isMissingWearAppError(new DeviceDisconnectedException("device disconnected")));
        assertTrue(WearDiagnostics.describeSdkError(new PermissionDeniedException("permission denied"))
                .contains("PermissionDenied"));
        assertTrue(WearDiagnostics.describeSdkError(new DeviceDisconnectedException("device disconnected"))
                .contains("DeviceDisconnected"));
        assertEquals("IllegalStateException: service dead",
                WearDiagnostics.describeSdkError(new RuntimeException(new IllegalStateException("service dead"))));
    }

    @Test public void compactsConnectedStatesWithoutHidingAuthOrErrors() {
        assertEquals("通信未启动", WearDiagnostics.compactLinkStatus("请在小米运动健康中确认设备授权", false));
        assertEquals("应用已连接", WearDiagnostics.compactLinkStatus("已收到手环消息", true));
        assertEquals("应用已连接", WearDiagnostics.compactLinkStatus("已收到穿戴设备消息", true));
        assertEquals("手表已连接", WearDiagnostics.compactLinkStatus("手表已连接（蓝牙直连）", true));
        assertEquals("设备已连接，等待打开腕上车控",
                WearDiagnostics.compactLinkStatus("手环已连接，等待打开腕上车控", true));
        assertEquals("请在小米运动健康中确认设备授权",
                WearDiagnostics.compactLinkStatus("请在小米运动健康中确认设备授权", true));
        assertEquals("设备授权超时，请检查小米运动健康后重试",
                WearDiagnostics.compactLinkStatus("设备授权超时，请检查小米运动健康后重试", true));
        assertTrue(WearDiagnostics.isWarningStatus("设备授权超时，请检查小米运动健康后重试"));
        assertFalse(WearDiagnostics.isWarningStatus("请在小米运动健康中确认设备授权"));
    }

    @Test public void formatsNodeLabelsAndDiagnosticExports() {
        assertEquals("Xiaomi Watch 6 (1234…cdef)",
                WearDiagnostics.formatNodeLabel("1234567890abcdef", "Xiaomi Watch 6"));
        assertEquals("node1", WearDiagnostics.formatNodeLabel("node1", ""));

        String report = WearDiagnostics.formatDiagnosticExport(
                "0.1.23",
                35,
                "Xiaomi 15",
                "服务包 com.mi.health · 小米运动健康 3.35.0",
                true,
                "设备授权超时，请检查小米运动健康后重试",
                true,
                false,
                List.of(new GarageApp.UiEvent(1_700_000_000_000L, "设备授权超时", "详情", true)));
        assertTrue(report.contains("口袋车控诊断信息"));
        assertTrue(report.contains("伴侣版本: 0.1.23"));
        assertTrue(report.contains("Android API 35 / Xiaomi 15"));
        assertTrue(report.contains("服务包 com.mi.health"));
        assertTrue(report.contains("[!] 设备授权超时 — 详情"));
    }

    @Test public void activeBluetoothWatchStatusIsNotHiddenByMissingRpkError() {
        assertEquals("手表已连接（蓝牙直连）", WearLink.combineStatus(
                "手表端未安装腕上车控 RPK 或包名不一致 (AppNotInstalled)",
                "手表已连接（蓝牙直连）",
                true));
        assertEquals("已收到手表消息", WearLink.combineStatus(
                "无法读取小米授权状态：DeviceDisconnected",
                "已收到手表消息",
                true));
        assertEquals("已收到穿戴设备消息", WearLink.combineStatus(
                "已收到手环消息",
                "手表已连接（蓝牙直连）",
                true));
        assertEquals("手环已连接，等待打开腕上车控", WearLink.combineStatus(
                "手环已连接，等待打开腕上车控",
                "手表已连接（蓝牙直连）",
                true));
        assertEquals("等待手表重连（蓝牙直连）", WearLink.combineStatus(
                "未检测到腕上车控 RPK（若用手表 APK 请忽略，AppNotInstalled）",
                "",
                false,
                true));
        assertEquals("等待手表重连", WearDiagnostics.compactLinkStatus("等待手表重连（蓝牙直连）", true));
        assertFalse(WearDiagnostics.isWarningStatus("等待手表重连（蓝牙直连）"));
    }

    @Test public void normalizesCustomAndDefaultVehicleNames() {
        assertEquals(GarageApp.DEFAULT_VEHICLE_NAME, GarageApp.normalizeVehicleName(null));
        assertEquals(GarageApp.DEFAULT_VEHICLE_NAME, GarageApp.normalizeVehicleName("   "));
        assertEquals("星耀 8", GarageApp.normalizeVehicleName("  星耀 8  "));
        assertEquals("1234567890", GarageApp.normalizeVehicleName("123456789012345678901234567890"));
        assertEquals("一二三四五六七八九🚗", GarageApp.normalizeVehicleName("一二三四五六七八九🚗十"));
    }
}
