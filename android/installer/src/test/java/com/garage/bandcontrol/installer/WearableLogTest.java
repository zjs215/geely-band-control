package com.garage.bandcontrol.installer;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class WearableLogTest {
    private static final String FIRST = "A2:10:20:30:40:" + "01";
    private static final String SECOND = "A2:10:20:30:40:" + "02";
    private static final String KEY_A = "00112233445566778899aabbccddeeff";
    private static final String KEY_B = "ffeeddccbbaa99887766554433221100";

    @Test
    public void readsNestedDeviceDetailFromExportedLog() throws Exception {
        String record = "11-27 09:05 XiaomiFit: {\"deviceInfo\":{\"device\":"
                + binding(FIRST.toLowerCase(Locale.ROOT), KEY_A) + "}}";
        try (WearableLog.Keys keys = WearableLog.read(zip("logs/XiaomiFit.main.log", record))) {
            assertEquals(FIRST, keys.uniqueAddress());
            assertArrayEquals(BandProtocol.parseKey(KEY_A), keys.copyFor(FIRST));
            assertNull(keys.copyFor(SECOND));
        }
    }

    @Test
    public void keepsKeysScopedToDevicesInOneLogRecord() throws Exception {
        String record = "{\"devices\":[" + binding(FIRST, KEY_A) + "," + binding(SECOND, KEY_B) + "]}";
        try (WearableLog.Keys keys = WearableLog.read(zip("XiaomiFit.main.log", record))) {
            assertEquals("", keys.uniqueAddress());
            assertArrayEquals(BandProtocol.parseKey(KEY_A), keys.copyFor(FIRST));
            assertArrayEquals(BandProtocol.parseKey(KEY_B), keys.copyFor(SECOND));
            assertNull(keys.copyFor("A2:10:20:30:40:" + "03"));
        }
    }

    @Test
    public void usesLastBindingForSameDeviceInPrimaryLog() throws Exception {
        try (WearableLog.Keys keys = WearableLog.read(zip("XiaomiFit.main.log",
                binding(FIRST, KEY_A) + "\r\n" + binding(FIRST, KEY_B),
                "XiaomiFit.device.log", binding(FIRST, KEY_A)))) {
            assertArrayEquals(BandProtocol.parseKey(KEY_B), keys.copyFor(FIRST));
        }
    }

    @Test
    public void readsDetailWhenLogSerializesItAsString() throws Exception {
        String record = "{\"mac\":\"" + FIRST + "\",\"detail\":\"{\\\"encryptKey\\\":\\\"" + KEY_A + "\\\"}\"}";
        try (WearableLog.Keys keys = WearableLog.read(zip("subdir/Transfer.device.log", record))) {
            assertEquals(FIRST, keys.uniqueAddress());
            assertArrayEquals(BandProtocol.parseKey(KEY_A), keys.copyFor(FIRST));
        }
    }

    @Test
    public void unboundKeyRequiresAnExplicitDeviceSelection() throws Exception {
        try (WearableLog.Keys keys = WearableLog.read(zip("XiaomiFit.device.log",
                "DeviceDetail(encryptKey=" + KEY_A + ", authKey=null)"))) {
            assertEquals("", keys.uniqueAddress());
            assertNull(keys.copyFor(""));
            assertArrayEquals(BandProtocol.parseKey(KEY_A), keys.copyFor(FIRST));
        }
    }

    @Test
    public void refusesUnboundKeysWhenMoreThanOneCouldApply() {
        InstallFailure error = assertThrows(InstallFailure.class, () -> WearableLog.read(zip("XiaomiFit.device.log",
                "encryptKey=" + KEY_A + "\nencryptKey=" + KEY_B)));
        assertTrue(error.getMessage().contains("多把"));
    }

    @Test
    public void skipsGenericTokensDummyKeysAndNonLogEntries() {
        InstallFailure error = assertThrows(InstallFailure.class, () -> WearableLog.read(zip("XiaomiFit.main.log",
                "{\"token\":\"" + KEY_A + "\",\"encryptKey\":\"" + "0".repeat(32) + "\"}",
                "telemetry.bin", binding(FIRST, KEY_B))));
        assertTrue(error.getMessage().contains("未找到"));
    }

    @Test
    public void reportsNonZipSelection() {
        InstallFailure error = assertThrows(InstallFailure.class, () ->
                WearableLog.read(new ByteArrayInputStream("not a ZIP".getBytes(StandardCharsets.UTF_8))));
        assertTrue(error.getMessage().contains("ZIP"));
    }

    private static String binding(String mac, String key) {
        return "{\"macAddress\":\"" + mac + "\",\"detail\":{\"authKey\":null,\"encryptKey\":\"" + key + "\"}}";
    }

    private static ByteArrayInputStream zip(String... files) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream archive = new ZipOutputStream(bytes)) {
            for (int i = 0; i < files.length; i += 2) {
                archive.putNextEntry(new ZipEntry(files[i]));
                archive.write(files[i + 1].getBytes(StandardCharsets.UTF_8));
                archive.closeEntry();
            }
        }
        return new ByteArrayInputStream(bytes.toByteArray());
    }
}
