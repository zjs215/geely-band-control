package com.otrong.carcontrol.installer;

import java.io.IOException;

record AppUpdate(String available, boolean online, Comparison comparison) {
    record Installed(String version, long code) {
        String label() { return version.isEmpty() ? "#" + code : "v" + version; }
    }

    enum Comparison { UPDATE, CURRENT, UNKNOWN }

    static AppUpdate compare(BundledPackages.Package bundled, GitHubRelease.Release release,
                             InstallTarget target, Installed installed) {
        String remote = "";
        if (release != null) {
            try {
                remote = release.asset(target).tag().substring(1);
            } catch (IOException unavailable) {
                // A release can omit one device's asset and still offer updates for the others.
            }
        }
        boolean newerOnline = !remote.isEmpty() && GitHubRelease.compareVersions(remote, bundled.version()) > 0;
        String available = newerOnline ? remote : bundled.version();
        if (installed == null) return new AppUpdate(available, newerOnline, Comparison.UNKNOWN);

        if (!newerOnline) {
            return new AppUpdate(available, false, installed.code() < bundled.versionCode()
                    ? Comparison.UPDATE : Comparison.CURRENT);
        }
        if (installed.code() < bundled.versionCode()) {
            return new AppUpdate(available, true, Comparison.UPDATE);
        }
        String installedName = installed.version();
        if (installedName.isEmpty() && installed.code() == bundled.versionCode()) {
            installedName = bundled.version();
        }
        if (!installedName.matches("[0-9]{1,6}\\.[0-9]{1,6}\\.[0-9]{1,6}")) {
            return new AppUpdate(available, true, Comparison.UNKNOWN);
        }
        return new AppUpdate(available, true, GitHubRelease.compareVersions(available, installedName) > 0
                ? Comparison.UPDATE : Comparison.CURRENT);
    }

    static String availableInstallerVersion(GitHubRelease.Release release, String currentVersion) {
        if (release == null || currentVersion == null
                || !currentVersion.matches("[0-9]{1,6}\\.[0-9]{1,6}\\.[0-9]{1,6}")) {
            return null;
        }
        try {
            String remote = release.installerAsset().tag().substring(1);
            return GitHubRelease.compareVersions(remote, currentVersion) > 0 ? remote : null;
        } catch (IOException unavailable) {
            return null;
        }
    }
}
