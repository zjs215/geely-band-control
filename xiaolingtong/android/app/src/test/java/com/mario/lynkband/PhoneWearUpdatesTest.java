package com.mario.lynkband;

import org.json.JSONException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class PhoneWearUpdatesTest {
    private static final String REPO = "/chashaobao/geely-band-control/releases";
    private static final String TAG = "xiaolingtong-v0.3.35";
    private static final String SHA = "a".repeat(64);
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void cnbIndexRequiresAllFourSignedPackageEntriesWithChecksums() throws Exception {
        String index = """
                {"schema":1,"releases":[{"tag":"%s","assets":[
                  {"name":"lynk-companion-phone.apk","size":4000,"sha256":"%s"},
                  {"name":"lynk-watch.apk","size":4500,"sha256":"%s"},
                  {"name":"lynk-band-pill-0.2.27.rpk","size":2000,"sha256":"%s"},
                  {"name":"lynk-band-standard-0.2.27.rpk","size":3000,"sha256":"%s"}
                ]}]}
                """.formatted(TAG, SHA, SHA, SHA, SHA);
        var release = PhoneWearReleases.parseCnb(index).get(0);
        assertEquals(PhoneWearReleases.Source.CNB, release.source());
        assertEquals(TAG, release.tag());
        assertEquals(4, release.assets().size());
        assertEquals(SHA, release.assets().get(1).digest());
        assertEquals(4500, release.assets().get(1).size());
        assertEquals("https://cnb.cool/sidabakesi/xiaolingtong/-/git/raw/main/downloads/" + TAG + "/lynk-watch.apk",
                PhoneWearReleases.cnbDownloadUrl(TAG, "lynk-watch.apk").toString());
        assertThrows(JSONException.class, () -> PhoneWearReleases.parseCnb(index.replace(SHA, "invalid")));
        assertThrows(JSONException.class, () -> PhoneWearReleases.parseCnb(index.replace("4500", "4500.5")));
        assertThrows(JSONException.class, () -> PhoneWearReleases.parseCnb(
                index.replace("lynk-band-standard", "lynk-band-pill")));
        assertThrows(JSONException.class, () -> PhoneWearReleases.parseCnb(
                index.replace("lynk-band-standard", "other-app")));
        assertThrows(IllegalArgumentException.class,
                () -> PhoneWearReleases.cnbDownloadUrl(TAG, "../lynk-watch.apk"));
    }

    @Test public void releaseApiFiltersOtherProjectsAndSelectsMatchingAssets() throws Exception {
        String watch = uploaded(TAG, "lynk-watch.apk", "https://github.com", 4000);
        String pill = uploaded(TAG, "lynk-band-pill-0.2.19.rpk", "https://github.com", 3500);
        String standard = uploaded(TAG, "lynk-band-standard-0.2.19.rpk", "https://github.com", 6500);
        String phone = uploaded(TAG, "lynk-companion-phone.apk", "https://github.com", 4000);
        String offOrigin = uploaded(TAG, "lynk-band.rpk", "https://example.test", 4000);
        String oversized = uploaded(TAG, "lynk-band-pill-0.2.20.rpk", "https://github.com", 999999999);
        String current = release(TAG, false, String.join(",", watch, pill, standard, phone, offOrigin, oversized));
        String first = release("xiaolingtong-v0.3.9", false, uploaded("xiaolingtong-v0.3.9", "lynk-watch.apk", "https://github.com", 4100));
        String other = release("v9.0.0", false, uploaded("v9.0.0", "lynk-watch.apk", "https://github.com", 4100));
        String pre = release("xiaolingtong-v0.9.1", true, uploaded("xiaolingtong-v0.9.1", "lynk-watch.apk", "https://github.com", 4100));
        List<PhoneWearReleases.Release> releases = PhoneWearReleases.parseApi("[" + String.join(",", first, other, current, pre) + "]");
        assertEquals(2, releases.size());
        assertEquals(TAG, releases.get(0).tag());
        assertEquals(4, releases.get(0).assets().size());
        assertTrue(releases.get(0).assets().get(3).isPhone());
        assertEquals(SHA, releases.get(0).assets().get(0).digest());
        assertEquals("lynk-band-standard-0.2.19.rpk", releases.get(0).assets().get(2).name());
    }

    @Test public void htmlFallbackScopesExactTagsAndDownloadPaths() {
        String html = "<include-fragment src=\"" + REPO + "/expanded_assets/" + TAG + "\"></include-fragment>"
                + "<include-fragment src=\"" + REPO + "/expanded_assets/" + TAG + "-rc.1\"></include-fragment>"
                + "<include-fragment src=\"/somebody/else/releases/expanded_assets/xiaolingtong-v9.0.1\"></include-fragment>";
        assertEquals(TAG, PhoneWearReleases.parseWebReleases(html).get(0).tag());
        assertEquals(1, PhoneWearReleases.parseWebReleases(html).size());
        String assets = link(REPO, TAG, "lynk-band.rpk") + link(REPO, TAG, "SHA256SUMS.txt")
                + link(REPO, "xiaolingtong-v0.3.34", "lynk-watch.apk") + link(REPO, TAG, "lynk-companion-phone.apk")
                + link("/somebody/else/releases", TAG, "lynk-watch.apk");
        assertEquals(3, PhoneWearReleases.parseWebAssets(TAG, assets).size());
        assertTrue(PhoneWearReleases.parseWebAssets(TAG, assets).get(2).isPhone());
        assertEquals("lynk-band.rpk", PhoneWearReleases.parseWebAssets(TAG, assets).get(0).name());
    }

    @Test public void htmlFallbackAcceptsCurrentAbsoluteGitHubLinks() {
        String prefix = "<include-fragment src=\"" + "https://github.com";
        String html = prefix + REPO + "/expanded_assets/" + TAG + "\"></include-fragment>"
                + prefix + ".example.test" + REPO + "/expanded_assets/xiaolingtong-v1.2.3\"></include-fragment>";
        List<PhoneWearReleases.Release> releases = PhoneWearReleases.parseWebReleases(html);
        assertEquals(List.of(TAG), releases.stream().map(PhoneWearReleases.Release::tag).toList());
        List<PhoneWearReleases.Asset> assets = PhoneWearReleases.parseWebAssets(TAG,
                link("https://github.com" + REPO, TAG, PhoneWearReleases.PHONE_APK));
        assertEquals(1, assets.size());
        assertTrue(assets.get(0).isPhone());
    }

    @Test public void aPhoneOnlyReleaseCanBeCheckedAndVersionsSortNumerically() throws Exception {
        String phoneOnly = release(TAG, false, uploaded(TAG, PhoneWearReleases.PHONE_APK, "https://github.com", 4000));
        var versions = PhoneWearReleases.parseApi("[" + phoneOnly + "]");
        assertEquals(1, versions.size());
        assertTrue(versions.get(0).assets().get(0).isPhone());
        assertTrue(PhoneWearReleases.compareVersions("0.3.9", "0.3.42") < 0);
        assertEquals(0, PhoneWearReleases.compareVersions("0.3.42", "0.3.42"));
        assertTrue(PhoneWearReleases.compareVersions("1.0.0", "0.3.42") > 0);
    }

    @Test public void cdnAllowlistRejectsOtherHostsSchemesAndPorts() {
        String path = "/github-production-release-asset/123/a";
        assertTrue(PhoneWearReleases.allowedCdn(URI.create("https://release-assets.githubusercontent.com" + path + "?token=value")));
        assertFalse(PhoneWearReleases.allowedCdn(URI.create("http://release-assets.githubusercontent.com" + path)));
        assertFalse(PhoneWearReleases.allowedCdn(URI.create("https://release-assets.githubusercontent.com.example.test" + path)));
        assertFalse(PhoneWearReleases.allowedCdn(URI.create("https://release-assets.githubusercontent.com:8443" + path)));
        assertFalse(PhoneWearReleases.allowedCdn(URI.create("https://user@release-assets.githubusercontent.com" + path)));
        assertThrows(IllegalArgumentException.class, () -> PhoneWearReleases.downloadUrl(TAG, "../lynk-watch.apk"));
    }

    @Test public void checksumsAreExactAndStreamingIsBoundedAndCancellable() throws Exception {
        String document = SHA + "  lynk-band.rpk\r\n" + "b".repeat(64) + " *lynk-watch.apk\n";
        assertEquals(SHA, PhoneWearReleases.checksum(document, "lynk-band.rpk"));
        assertNull(PhoneWearReleases.checksum(document, "band.rpk"));
        assertThrows(IOException.class, () -> PhoneWearReleases.checksum(document + "c".repeat(64) + "  lynk-band.rpk", "lynk-band.rpk"));
        assertThrows(IOException.class, () -> copy(9, 8, -1, () -> {}));
        assertThrows(IOException.class, () -> copy(7, 8, 8, () -> {}));
        assertThrows(IOException.class, () -> copy(7, 8, -1, () -> { throw new IOException("cancelled"); }));
        assertEquals(8, copy(8, 8, 8, () -> {}));
    }

    @Test public void rpkIdentityAndVersionComeFromTheArchiveManifest() throws Exception {
        assertEquals("0.2.19", PhoneWearUpdates.rpkVersion(rpk("com.mario.lynkband.phone", "0.2.19", true)));
        assertThrows(IOException.class, () -> PhoneWearUpdates.rpkVersion(rpk("com.example.other", "0.2.19", true)));
        assertThrows(IOException.class, () -> PhoneWearUpdates.rpkVersion(rpk("com.mario.lynkband.phone", "../../bad", true)));
        assertThrows(IOException.class, () -> PhoneWearUpdates.rpkVersion(rpk("com.mario.lynkband.phone", "0.2.19", false)));
    }

    @Test public void velaExportUsesTheConnectedRpkProfileAndVersionCode() throws Exception {
        var installed = new WristApp.VelaVersion("paired", 26, "pill", 0L);
        var same = new PhoneWearUpdates.RpkInfo("0.2.24", 26, "pill", "same.rpk");
        var upgrade = new PhoneWearUpdates.RpkInfo("0.2.25", 27, "pill", "new.rpk");
        var downgrade = new PhoneWearUpdates.RpkInfo("0.2.23", 25, "pill", "old.rpk");
        var wrongScreen = new PhoneWearUpdates.RpkInfo("0.2.25", 27, "standard", "standard.rpk");
        PhoneWearUpdates.checkRpkTarget(same, installed);
        PhoneWearUpdates.checkRpkTarget(upgrade, installed);
        assertThrows(IOException.class, () -> PhoneWearUpdates.checkRpkTarget(downgrade, installed));
        assertThrows(IOException.class, () -> PhoneWearUpdates.checkRpkTarget(wrongScreen, installed));
        assertThrows(IOException.class, () -> PhoneWearUpdates.checkRpkTarget(same, null));
    }

    private File rpk(String pkg, String version, boolean signed) throws IOException {
        File path = temp.newFile();
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(path))) {
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write(("{\"package\":\"" + pkg + "\",\"versionName\":\"" + version + "\",\"versionCode\":19}")
                    .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            if (signed) {
                zip.putNextEntry(new ZipEntry("META-INF/CERT"));
                zip.write(1);
                zip.closeEntry();
            }
        }
        return path;
    }

    private static String release(String tag, boolean prerelease, String assets) {
        return "{\"tag_name\":\"" + tag + "\",\"prerelease\":" + prerelease + ",\"assets\":[" + assets + "]}";
    }

    private static String uploaded(String tag, String name, String origin, long size) {
        return "{\"state\":\"uploaded\",\"name\":\"" + name + "\",\"size\":" + size + ",\"digest\":\"sha256:" + SHA
                + "\",\"browser_download_url\":\"" + origin + REPO + "/download/" + tag + "/" + name + "\"}";
    }

    private static String link(String repo, String tag, String name) {
        return "<a href=\"" + repo + "/download/" + tag + "/" + name + "\">";
    }

    private static long copy(int bytes, long limit, long expected, PhoneWearReleases.Check check) throws IOException {
        return PhoneWearReleases.copy(new ByteArrayInputStream(new byte[bytes]), new ByteArrayOutputStream(), limit,
                expected, check, done -> {}, null);
    }
}
