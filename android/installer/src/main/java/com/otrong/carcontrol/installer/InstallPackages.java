package com.otrong.carcontrol.installer;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.FeatureInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class InstallPackages {
    record Selected(byte[] bytes, String version, int versionCode, String source) {
        String label() { return source + " · v" + version; }
    }

    private record Version(String name, int code) {}

    private final Context context;
    private final BundledPackages bundled;

    InstallPackages(Context context, BundledPackages bundled) {
        this.context = context;
        this.bundled = bundled;
    }

    Selected resolve(InstallTarget target, BandProtocol.Progress progress) throws IOException, InterruptedException {
        BundledPackages.Package fallback = target.bundled(bundled);
        String reason;
        progress.report("正在检查在线稳定版", -1);
        GitHubRelease.Asset asset;
        try {
            asset = GitHubRelease.latest(target);
        } catch (IOException unavailable) {
            asset = null;
        }
        if (asset != null) {
            int comparison = GitHubRelease.compareVersions(asset.tag().substring(1), fallback.version());
            if (comparison >= 0) {
                try {
                    progress.report("正在下载并校验 " + asset.tag(), -1);
                    byte[] bytes = GitHubRelease.download(asset);
                    Version version = target.isRpk() ? validateRpk(bytes, target == InstallTarget.BAND)
                            : validateApk(target, bytes, bundled.verifiedBytes(fallback));
                    if (!asset.tag().equals("v" + version.name)) throw new IOException("发布标签与包内版本不一致");
                    if (version.code >= fallback.versionCode()) {
                        return new Selected(bytes, version.name, version.code, "线上");
                    }
                } catch (IOException downloadFailed) {
                    if (comparison > 0) {
                        throw new InstallFailure("检测到新版 " + asset.tag() + "，但下载或校验失败："
                                + downloadFailed.getMessage(), downloadFailed);
                    }
                }
            }
            reason = "线上版本较早";
        } else {
            reason = "在线包暂不可用";
        }
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        progress.report(reason + "，使用内置 v" + fallback.version(), -1);
        return new Selected(bundled.verifiedBytes(fallback), fallback.version(), fallback.versionCode(), "内置");
    }

    private Version validateRpk(byte[] bytes, boolean full) throws IOException {
        File file = writeTemporary(bytes, ".rpk");
        try (ZipFile zip = new ZipFile(file)) {
            ZipEntry manifest = zip.getEntry("manifest.json");
            ZipEntry cert = zip.getEntry("META-INF/CERT");
            boolean hasThemes = zip.stream().anyMatch(item ->
                    item.getName().startsWith("common/themes/") && item.getName().endsWith(".jpg"));
            if (manifest == null || cert == null || cert.getSize() <= 0 || hasThemes != full) {
                throw new IOException("穿戴安装包签名结构或 RPK 类型不匹配");
            }
            try (InputStream input = zip.getInputStream(manifest)) {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                byte[] data = GitHubRelease.readBounded(input, 64 * 1024, deadline);
                JSONObject info = new JSONObject(new String(data, StandardCharsets.UTF_8));
                JSONArray devices = info.getJSONArray("deviceTypeList");
                boolean wearable = false;
                for (int i = 0; i < devices.length(); i++) wearable |= "watch".equals(devices.optString(i));
                if (!wearable || !BundledPackages.APP_PACKAGE.equals(info.getString("package"))) {
                    throw new IOException("RPK 包名或目标设备无效");
                }
                return version(info.getString("versionName"), info.getLong("versionCode"));
            } catch (JSONException invalid) {
                throw new IOException("RPK 清单无效", invalid);
            }
        } finally {
            deleteTemporary(file);
        }
    }

    private Version validateApk(InstallTarget target, byte[] bytes, byte[] reference) throws IOException {
        PackageInfo apk = readApk(bytes);
        PackageInfo bundledApk = readApk(reference);
        boolean isWatch = apk.reqFeatures != null && Arrays.stream(apk.reqFeatures)
                .anyMatch(feature -> "android.hardware.type.watch".equals(feature.name)
                        && (feature.flags & FeatureInfo.FLAG_REQUIRED) != 0);
        List<String> activities = target == InstallTarget.PHONE
                ? Arrays.asList("com.otrong.carcontrol.phone.MainActivity", "com.garage.bandcontrol.MainActivity")
                : Arrays.asList("com.otrong.carcontrol.watch.WatchActivity", "com.garage.watchcontrol.WatchActivity");
        if (!BundledPackages.APP_PACKAGE.equals(apk.packageName) || isWatch != (target == InstallTarget.WATCH)
                || apk.activities == null || Arrays.stream(apk.activities).map((ActivityInfo entry) -> entry.name)
                        .noneMatch(activities::contains)
                || !signers(apk).equals(signers(bundledApk))) {
            throw new IOException("APK 目标设备、应用标识或签名与内置包不符");
        }
        return version(apk.versionName, apk.getLongVersionCode());
    }

    private PackageInfo readApk(byte[] bytes) throws IOException {
        File file = writeTemporary(bytes, ".apk");
        try {
            PackageInfo info = context.getPackageManager().getPackageArchiveInfo(file.getAbsolutePath(),
                    PackageManager.GET_SIGNING_CERTIFICATES | PackageManager.GET_CONFIGURATIONS
                            | PackageManager.GET_ACTIVITIES);
            if (info == null) throw new IOException("无法读取 APK 清单");
            return info;
        } finally {
            deleteTemporary(file);
        }
    }

    private static Set<Signature> signers(PackageInfo info) throws IOException {
        Signature[] signatures = info.signingInfo == null ? null : info.signingInfo.getApkContentsSigners();
        if (signatures == null || signatures.length == 0) throw new IOException("APK 缺少签名");
        return new HashSet<>(Arrays.asList(signatures));
    }

    private File writeTemporary(byte[] bytes, String suffix) throws IOException {
        File file = File.createTempFile("release-", suffix, context.getCacheDir());
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(bytes);
        } catch (IOException failure) {
            deleteTemporary(file);
            throw failure;
        }
        return file;
    }

    private static void deleteTemporary(File file) {
        if (!file.delete()) android.util.Log.w("InstallPackages", "Could not remove temporary package");
    }

    private static Version version(String name, long code) throws IOException {
        if (name == null || name.isEmpty() || code <= 0 || code > Integer.MAX_VALUE) {
            throw new IOException("应用版本无效");
        }
        return new Version(name, (int) code);
    }
}
