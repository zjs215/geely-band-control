package com.otrong.carcontrol.installer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class AppUpdateTest {
    private static final BundledPackages.Package BUNDLED =
            new BundledPackages.Package("pocket-car-control.apk", 500, "0.3.0", 50, "");

    @Test
    public void checksKnownVersionsUsingReleaseOrBundledPackage() throws Exception {
        AppUpdate installedOld = AppUpdate.compare(BUNDLED, null, InstallTarget.PHONE, installed("0.2.12", 49));
        assertEquals(AppUpdate.Comparison.UPDATE, installedOld.comparison());
        assertEquals("0.3.0", installedOld.available());
        assertFalse(installedOld.online());

        AppUpdate online = AppUpdate.compare(BUNDLED, release("0.3.2", InstallTarget.PHONE), InstallTarget.PHONE,
                installed("0.3.0", 50));
        assertEquals(AppUpdate.Comparison.UPDATE, online.comparison());
        assertEquals("0.3.2", online.available());
        assertTrue(online.online());
        assertEquals(AppUpdate.Comparison.CURRENT,
                AppUpdate.compare(BUNDLED, release("0.3.2", InstallTarget.PHONE), InstallTarget.PHONE,
                        installed("0.3.3", 53)).comparison());
    }

    @Test
    public void checksAssetsPerTargetAndShowsOnlyTheAvailablePackageForUnknownVersions() throws Exception {
        GitHubRelease.Release release = release("0.3.2", InstallTarget.PHONE);
        assertEquals(AppUpdate.Comparison.CURRENT, AppUpdate.compare(BUNDLED, release,
                InstallTarget.BAND, installed("0.3.0", 50)).comparison());
        assertEquals(AppUpdate.Comparison.UNKNOWN, AppUpdate.compare(BUNDLED, release,
                InstallTarget.PHONE, null).comparison());
        assertEquals(AppUpdate.Comparison.UNKNOWN, AppUpdate.compare(BUNDLED, release,
                InstallTarget.PHONE, installed("", 51)).comparison());
        assertEquals(AppUpdate.Comparison.UPDATE, AppUpdate.compare(BUNDLED, release,
                InstallTarget.PHONE, installed("", 50)).comparison());
    }

    @Test
    public void detectsNewerInstallerAssetInLatestRelease() throws Exception {
        String tag = "v0.3.4";
        JSONObject asset = new JSONObject().put("name", GitHubRelease.INSTALLER_ASSET)
                .put("state", "uploaded").put("size", 2048)
                .put("digest", "sha256:" + "ab".repeat(32))
                .put("browser_download_url", "https://github.com/zjs215/geely-band-control/releases/download/"
                        + tag + "/" + GitHubRelease.INSTALLER_ASSET);
        GitHubRelease.Release release = new GitHubRelease.Release(tag, new JSONArray().put(asset));

        assertEquals("0.3.4", AppUpdate.availableInstallerVersion(release, "0.3.3"));
        assertNull(AppUpdate.availableInstallerVersion(release, "0.3.4"));
        assertNull(AppUpdate.availableInstallerVersion(release, "0.3.5"));
        assertNull(AppUpdate.availableInstallerVersion(null, "0.3.3"));
    }

    private static AppUpdate.Installed installed(String version, long code) {
        return new AppUpdate.Installed(version, code);
    }

    private static GitHubRelease.Release release(String version, InstallTarget target) throws Exception {
        String tag = "v" + version;
        JSONObject asset = new JSONObject().put("name", target.asset).put("state", "uploaded").put("size", 1024)
                .put("digest", "sha256:" + "ab".repeat(32))
                .put("browser_download_url", "https://github.com/zjs215/geely-band-control/releases/download/"
                        + tag + "/" + target.asset);
        return new GitHubRelease.Release(tag, new JSONArray().put(asset));
    }
}
