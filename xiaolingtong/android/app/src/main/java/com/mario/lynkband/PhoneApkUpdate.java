package com.mario.lynkband;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.mario.lynkband.transport.WatchUpdateWire;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;

final class PhoneApkUpdate {
    private static final String APK_TYPE = "application/vnd.android.package-archive";
    private static final String SAVED_FILE = "phoneUpdateFile";
    private static final String SAVED_VERSION = "phoneUpdateVersion";
    private static final int SOURCES = 274;
    private static final int INSTALL = 275;
    private static final long KEEP_MS = 24 * 60 * 60 * 1000L;

    record Package(File file, String version) {}

    private final Activity activity;
    private Package pending;
    private boolean continueAfterSettings;

    PhoneApkUpdate(Activity activity, Bundle state) {
        this.activity = activity;
        File directory = directory();
        if (state != null) {
            String name = state.getString(SAVED_FILE, "");
            String version = state.getString(SAVED_VERSION, "");
            File file = new File(directory, name);
            if (name.matches("phone-update-[0-9]+\\.apk") && file.isFile()
                    && !expired(file) && version.matches("[0-9]+\\.[0-9]+\\.[0-9]+")) pending = new Package(file, version);
        }
        File[] expiredFiles = directory.listFiles(file -> file.getName().matches("phone-update-[0-9]+\\.apk") && expired(file));
        if (expiredFiles != null) for (File file : expiredFiles) file.delete();
    }

    private static boolean expired(File file) {
        return System.currentTimeMillis() - file.lastModified() >= KEEP_MS;
    }

    private File directory() { return new File(activity.getCacheDir(), "phone-update"); }

    File createPackageFile() throws IOException {
        File directory = directory();
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法创建手机更新缓存。");
        return File.createTempFile("phone-update-", ".apk", directory);
    }

    void verify(File file, String version) throws IOException {
        PackageManager manager = activity.getPackageManager();
        int flags = PackageManager.GET_SIGNING_CERTIFICATES;
        String ownPackage = activity.getPackageName();
        PackageInfo candidate = manager.getPackageArchiveInfo(file.getPath(), flags);
        if (candidate == null || !ownPackage.equals(candidate.packageName) || !version.equals(candidate.versionName))
            throw new IOException("发布附件不是此版本的小领通手机 APK。");
        try {
            PackageInfo installed = manager.getPackageInfo(ownPackage, flags);
            Signature[] current = signers(installed);
            Signature[] incoming = signers(candidate);
            if (current == null || current.length == 0 || incoming == null
                    || !new HashSet<>(Arrays.asList(current)).equals(new HashSet<>(Arrays.asList(incoming)))) {
                throw new IOException("手机更新包签名与已安装应用不一致。");
            }
            if (!WatchUpdateWire.versionAllowed(installed.getLongVersionCode(), candidate.getLongVersionCode()))
                throw new IOException("手机 APK 版本低于当前版本，不能降级。");
        } catch (PackageManager.NameNotFoundException unavailable) {
            throw new IOException("无法确认当前手机应用的签名。", unavailable);
        }
    }

    private static Signature[] signers(PackageInfo app) {
        return app.signingInfo == null ? null : app.signingInfo.getApkContentsSigners();
    }

    void save(Bundle state) {
        if (pending != null) {
            state.putString(SAVED_FILE, pending.file().getName());
            state.putString(SAVED_VERSION, pending.version());
        }
    }

    boolean hasPending() { return pending != null && pending.file().isFile(); }

    void prepare(Package apk) {
        discard();
        pending = apk;
        showInstall();
    }

    void showInstall() {
        if (!hasPending()) return;
        new AlertDialog.Builder(activity).setTitle("手机更新包已校验")
                .setMessage("版本 " + pending.version() + "。请在 Android 系统界面确认安装。")
                .setNeutralButton("删除更新包", (dialog, button) -> discard())
                .setNegativeButton("稍后", null)
                .setPositiveButton("安装", (dialog, button) -> install()).show();
    }

    void resume() {
        if (!continueAfterSettings) return;
        continueAfterSettings = false;
        if (activity.getPackageManager().canRequestPackageInstalls()) showInstall();
        else toast("更新包已保留。允许此来源安装后可以继续安装。");
    }

    @SuppressWarnings("deprecation")
    private void install() {
        if (!hasPending()) return;
        PackageManager manager = activity.getPackageManager();
        if (!manager.canRequestPackageInstalls()) {
            new AlertDialog.Builder(activity).setTitle("允许安装手机更新")
                    .setMessage("首次更新需要在系统设置中允许小领通安装此 APK。")
                    .setNegativeButton("稍后", null).setPositiveButton("打开设置", (dialog, button) -> {
                        Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.fromParts("package", activity.getPackageName(), null));
                        try { activity.startActivityForResult(settings, SOURCES); }
                        catch (RuntimeException unavailable) { toast("无法打开系统安装权限设置。"); }
                    }).show();
            return;
        }
        Uri uri = null;
        try {
            verify(pending.file(), pending.version());
            uri = shareUri();
            Intent intent = new Intent(Intent.ACTION_INSTALL_PACKAGE).setDataAndType(uri, APK_TYPE)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).putExtra(Intent.EXTRA_RETURN_RESULT, true);
            intent.setClipData(ClipData.newRawUri("小领通手机更新", uri));
            for (ResolveInfo info : manager.queryIntentActivities(intent,
                    PackageManager.MATCH_SYSTEM_ONLY | PackageManager.MATCH_DEFAULT_ONLY)) {
                if (info.activityInfo == null || !info.activityInfo.exported) continue;
                intent.setClassName(info.activityInfo.packageName, info.activityInfo.name);
                activity.startActivityForResult(intent, INSTALL);
                return;
            }
        } catch (IOException invalid) {
            discard();
            toast(invalid.getMessage());
            return;
        } catch (RuntimeException unavailable) {
            if (uri != null) activity.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
        toast("无法打开系统安装界面，更新包已保留。");
    }

    private Uri shareUri() {
        return FileProvider.getUriForFile(activity, activity.getPackageName() + ".phone-updates", pending.file());
    }

    boolean activityResult(int request, int result) {
        if (request == SOURCES) {
            continueAfterSettings = true;
            return true;
        }
        if (request != INSTALL) return false;
        if (hasPending()) activity.revokeUriPermission(shareUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (result == Activity.RESULT_OK) discard();
        return true;
    }

    void discard() {
        if (pending != null) pending.file().delete();
        pending = null;
    }

    private void toast(String text) { Toast.makeText(activity, text, Toast.LENGTH_LONG).show(); }
}
