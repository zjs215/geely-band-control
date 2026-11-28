package com.mario.lynkband.watch;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import com.mario.lynkband.transport.WatchUpdateWire;

import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.util.Log;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;

/** Receive only from an explicit tap, and send the verified archive through the system installer. */
final class WatchUpdate {
    private static final int INSTALL_PERMISSION = 74;
    private static final int INSTALL = 75;
    private static final String STATE_URI = "watchUpdateInstallUri";
    private static final String STATE_SETTINGS = "watchUpdateInstallSettings";
    private static final String TAG = "WristLynkUpdate";
    private static final String INSTALL_FILE = "installFile";
    private static final String INSTALL_TIME = "installedAt";

    private final Activity activity;
    private final Handler main;
    private final PhoneLink link;
    private final Runnable phoneLinkChanged;
    private WatchUpdateTransfer transfer;
    private AlertDialog dialog;
    private Uri installing;
    private boolean visible;
    private boolean holdsPhoneLink;
    private boolean waitingForSettings;
    private Object verification;

    WatchUpdate(Activity activity, Handler main, PhoneLink link, Bundle saved, Runnable phoneLinkChanged) {
        this.activity = activity;
        this.main = main;
        this.link = link;
        this.phoneLinkChanged = phoneLinkChanged;
        if (saved != null) {
            String restored = saved.getString(STATE_URI);
            if (restored != null) installing = Uri.parse(restored);
            waitingForSettings = saved.getBoolean(STATE_SETTINGS);
            holdsPhoneLink = installing != null || waitingForSettings;
        }
    }

    void save(Bundle state) {
        if (installing != null) state.putString(STATE_URI, installing.toString());
        if (waitingForSettings) state.putBoolean(STATE_SETTINGS, true);
    }

    boolean holdsPhoneLink() { return holdsPhoneLink; }

    private void holdPhoneLink(boolean hold) {
        if (holdsPhoneLink == hold) return;
        holdsPhoneLink = hold;
        phoneLinkChanged.run();
    }

    void start() {
        visible = true;
        discardAppliedUpdate();
        WatchUpdateFiles.staged(activity);
    }

    private SharedPreferences installAttempt() {
        return activity.getSharedPreferences("watchUpdate", Context.MODE_PRIVATE);
    }

    private void discardAppliedUpdate() {
        SharedPreferences attempt = installAttempt();
        long previous = attempt.getLong(INSTALL_TIME, 0);
        if (previous == 0) return;
        try {
            if (WatchUpdateApk.installed(activity).lastUpdateTime == previous) return;
        } catch (PackageManager.NameNotFoundException unavailable) {
            return;
        }
        File apk = WatchUpdateFiles.staged(activity);
        if (apk != null && apk.getName().equals(attempt.getString(INSTALL_FILE, ""))) discard(apk);
        attempt.edit().clear().apply();
        installing = null;
        holdPhoneLink(false);
    }

    void stop() {
        visible = false;
        cancelTransfer();
        verification = null;
        dismiss();
        if (!waitingForSettings && installing == null) holdPhoneLink(false);
    }

    void receive() {
        if (!visible || holdsPhoneLink) return;
        File staged = WatchUpdateFiles.staged(activity);
        if (staged != null) {
            showStaged(staged);
            return;
        }
        if (!link.ready()) {
            toast("请先与已配对的手机连接");
            return;
        }
        holdPhoneLink(true);
        transfer = new WatchUpdateTransfer(activity, main, link, this::received);
        dismiss();
        dialog = new AlertDialog.Builder(activity).setTitle("正在接收手机更新")
                .setMessage("请保持手机和手表的更新页面打开。")
                .setNegativeButton("取消", (ignored, button) -> cancelReceive())
                .setOnCancelListener(ignored -> cancelReceive()).show();
        Window window = dialog.getWindow();
        if (window != null) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        transfer.start();
    }

    void linkIdle() { if (transfer != null) transfer.linkIdle(); }
    void linkLost() { if (transfer != null) transfer.linkLost(); }
    void reply(JSONObject response) { if (transfer != null) transfer.reply(response); }

    private void cancelTransfer() {
        if (transfer != null) transfer.cancel();
        transfer = null;
    }

    private void cancelReceive() {
        cancelTransfer();
        holdPhoneLink(false);
    }

    private void received(File apk, String error) {
        if (!visible || transfer == null) return;
        transfer = null;
        dismiss();
        if (apk != null) showStaged(apk);
        else {
            toast(error);
            holdPhoneLink(false);
        }
    }

    private void showStaged(File apk) {
        holdPhoneLink(true);
        dismiss();
        dialog = new AlertDialog.Builder(activity).setTitle("手表更新已就绪")
                .setMessage("更新包已校验。选择安装后，请在系统界面确认。")
                .setPositiveButton("安装", (ignored, button) -> requestInstall(apk))
                .setNeutralButton("删除更新包", (ignored, button) -> {
                    discard(apk);
                    holdPhoneLink(false);
                })
                .setNegativeButton("稍后", (ignored, button) -> holdPhoneLink(false))
                .setOnCancelListener(ignored -> holdPhoneLink(false)).show();
    }

    private void requestInstall(File apk) {
        if (!activity.getPackageManager().canRequestPackageInstalls()) {
            dismiss();
            dialog = new AlertDialog.Builder(activity).setTitle("需要安装权限")
                    .setMessage("请在系统设置允许从此应用安装更新，返回后可再次选择安装。")
                    .setPositiveButton("打开设置", (ignored, button) -> openInstallSettings())
                    .setNegativeButton("稍后", (ignored, button) -> holdPhoneLink(false))
                    .setOnCancelListener(ignored -> holdPhoneLink(false)).show();
            return;
        }

        Object attempt = new Object();
        verification = attempt;
        new Thread(() -> {
            String error = null;
            try {
                WatchUpdateApk.verify(activity, WatchUpdateApk.installed(activity), apk);
            } catch (WatchUpdateWire.InvalidUpdate invalid) {
                error = invalid.getMessage();
            } catch (PackageManager.NameNotFoundException | RuntimeException failure) {
                Log.w(TAG, "Cannot verify staged watch update", failure);
                error = "无法验证已接收的更新，请重新接收";
            }
            String message = error;
            main.post(() -> {
                if (!visible || verification != attempt) return;
                verification = null;
                if (message == null) launchInstaller(apk);
                else {
                    discard(apk);
                    toast(message);
                    holdPhoneLink(false);
                }
            });
        }, "watch-apk-verifier").start();
    }

    private void openInstallSettings() {
        Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.fromParts("package", activity.getPackageName(), null));
        waitingForSettings = true;
        try {
            activity.startActivityForResult(settings, INSTALL_PERMISSION);
        } catch (RuntimeException unavailable) {
            waitingForSettings = false;
            Log.w(TAG, "Cannot open install permission settings", unavailable);
            toast("无法打开安装权限设置，更新包已保留");
            holdPhoneLink(false);
        }
    }

    @SuppressWarnings("deprecation") // The system installer supplies the final user confirmation.
    private void launchInstaller(File apk) {
        Uri uri = WatchUpdateFiles.uri(activity, apk);
        Intent intent = new Intent(Intent.ACTION_INSTALL_PACKAGE)
                .setDataAndType(uri, WatchUpdateFiles.APK_TYPE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .putExtra(Intent.EXTRA_RETURN_RESULT, true);
        intent.setClipData(ClipData.newRawUri("LynkBand Watch update", uri));
        try {
            long before = WatchUpdateApk.installed(activity).lastUpdateTime;
            // Resolve only a system handler before granting access to the staged file.
            for (ResolveInfo info : activity.getPackageManager().queryIntentActivities(intent,
                    PackageManager.MATCH_SYSTEM_ONLY | PackageManager.MATCH_DEFAULT_ONLY)) {
                if (info.activityInfo == null || !info.activityInfo.exported) continue;
                intent.setClassName(info.activityInfo.packageName, info.activityInfo.name);
                // Self-replacement may terminate the caller before it receives the installer result.
                if (!installAttempt().edit().putString(INSTALL_FILE, apk.getName()).putLong(INSTALL_TIME, before).commit())
                    throw new IllegalStateException("Unable to record install attempt");
                installing = uri;
                activity.startActivityForResult(intent, INSTALL);
                return;
            }
        } catch (PackageManager.NameNotFoundException | RuntimeException unavailable) {
            installAttempt().edit().clear().apply();
            installing = null;
            activity.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Log.w(TAG, "Cannot open system installer", unavailable);
        }
        toast("无法打开系统安装界面，更新包已保留");
        holdPhoneLink(false);
    }

    void activityResult(int request, int result) {
        if (request == INSTALL_PERMISSION) {
            waitingForSettings = false;
            File apk = WatchUpdateFiles.staged(activity);
            if (apk != null && visible && activity.getPackageManager().canRequestPackageInstalls()) showStaged(apk);
            else {
                if (apk != null && visible) toast("安装权限未开启，更新包已保留");
                holdPhoneLink(false);
            }
        } else if (request == INSTALL) {
            installAttempt().edit().clear().apply();
            Uri uri = installing;
            installing = null;
            if (uri != null) activity.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            File apk = WatchUpdateFiles.staged(activity);
            if (uri != null && apk != null && uri.equals(WatchUpdateFiles.uri(activity, apk))) {
                if (result == Activity.RESULT_OK) discard(apk);
                else if (visible) toast("安装未完成，可在接收手机更新中重试");
            }
            holdPhoneLink(false);
        }
    }

    private void discard(File apk) {
        if (!apk.delete() && apk.isFile()) toast("无法删除更新包");
    }

    private void dismiss() {
        if (dialog != null) dialog.dismiss();
        dialog = null;
    }

    private void toast(String message) {
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
    }
}
