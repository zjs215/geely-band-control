package com.otrong.carcontrol.installer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;

public class GitHubReleaseTest {
    private static final String DOWNLOAD =
            "https://github.com/zjs215/geely-band-control/releases/download/v0.3.2/";

    @Test
    public void matchesEachDeviceToItsPackageAndRecognizesPublishedLegacyNames() throws Exception {
        String release = release(asset("galaxy-phone.apk"), asset("wrist-car-control-lite.rpk"),
                asset("galaxy-vela-band.rpk"), asset("wrist-car-control-watch.apk"));
        assertEquals("galaxy-phone.apk", GitHubRelease.select(release, InstallTarget.PHONE).name());
        assertEquals("galaxy-vela-band.rpk", GitHubRelease.select(release, InstallTarget.BAND).name());
        assertEquals("wrist-car-control-lite.rpk", GitHubRelease.select(release, InstallTarget.BAND_LITE).name());
        assertEquals("wrist-car-control-watch.apk", GitHubRelease.select(release, InstallTarget.WATCH).name());
    }

    @Test
    public void parsesAndFormatsReleaseNotesForDisplay() throws Exception {
        String json = new JSONObject(release(asset("pocket-car-control.apk")))
                .put("name", "v0.3.2 更新")
                .put("body", "### 本次更新\r\n- 修复 **连接** 与 `RPK` 传包\r\n\r\n* 新增 [说明](https://example.org)\r\n")
                .toString();
        GitHubRelease.Release parsed = GitHubRelease.parse(json);
        assertEquals("v0.3.2 更新", parsed.name());
        assertEquals("本次更新\n• 修复 连接 与 RPK 传包\n\n• 新增 说明", parsed.body());
    }

    @Test
    public void rejectsLiteAndArtifactsWithoutAnOfficialUrlAndDigest() throws Exception {
        assertThrows(IOException.class, () -> GitHubRelease.select(release(
                asset("wrist-car-control-lite.rpk"), asset("pocket-car-control.apk")), InstallTarget.BAND));
        JSONObject badUrl = asset("wrist-car-control.rpk").put("browser_download_url", "https://example.org/test");
        assertThrows(IOException.class, () -> GitHubRelease.select(release(badUrl), InstallTarget.BAND));
        JSONObject missingHash = asset("wrist-car-control.rpk").put("digest", JSONObject.NULL);
        assertThrows(IOException.class, () -> GitHubRelease.select(release(missingHash), InstallTarget.BAND));
        String preview = new JSONObject(release(asset("wrist-car-control.rpk")))
                .put("prerelease", true).toString();
        assertThrows(IOException.class, () -> GitHubRelease.select(preview, InstallTarget.BAND));
    }

    @Test
    public void recognizesSupportedModelsWithoutConfusingOtherXiaomiDevices() {
        assertEquals(InstallTarget.BAND, WearableProfile.fromBluetoothName("小米手环 10 Pro").target);
        assertEquals(InstallTarget.BAND, WearableProfile.fromBluetoothName("Xiaomi Smart Band 10 Pro-9ABC").target);
        assertEquals(InstallTarget.BAND_LITE, WearableProfile.fromBluetoothName("小米手环 8 Pro").target);
        assertEquals(InstallTarget.BAND_LITE, WearableProfile.fromBluetoothName("Xiaomi Smart Band 9 Pro C692").target);
        assertEquals(InstallTarget.BAND_LITE, WearableProfile.fromBluetoothName("小米手环 10").target);
        assertEquals(InstallTarget.BAND, WearableProfile.VELA_WATCH.target);
        assertEquals(InstallTarget.WATCH, WearableProfile.fromBluetoothName("Xiaomi Watch 5").target);
        assertEquals(InstallTarget.WATCH, WearableProfile.fromBluetoothName("M2501W1").target);
        assertNull(WearableProfile.fromBluetoothName("REDMI Watch 5"));
        assertNull(WearableProfile.fromBluetoothName("小米 Watch S5"));
    }

    @Test
    public void comparesVersionsNumericallyAndBoundsPayloadReads() throws Exception {
        assertTrue(GitHubRelease.compareVersions("0.10.0", "0.9.25") > 0);
        assertTrue(GitHubRelease.compareVersions("0.2.12", "0.3.0") < 0);
        assertEquals(0, GitHubRelease.compareVersions("0.3.0", "0.3.0"));
        assertEquals(6, GitHubRelease.readBounded(new ByteArrayInputStream(new byte[6]), 6, Long.MAX_VALUE).length);
        assertThrows(IOException.class, () -> GitHubRelease.readBounded(
                new ByteArrayInputStream(new byte[7]), 6, Long.MAX_VALUE));
    }

    private static String release(JSONObject... items) throws Exception {
        JSONArray assets = new JSONArray();
        for (JSONObject item : items) assets.put(item);
        return new JSONObject().put("tag_name", "v0.3.2").put("assets", assets).toString();
    }

    private static JSONObject asset(String name) throws Exception {
        return new JSONObject().put("name", name).put("state", "uploaded")
                .put("size", 1024).put("digest", "sha256:" + "ab".repeat(32))
                .put("browser_download_url", DOWNLOAD + name);
    }
}
