package com.otrong.carcontrol.installer;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Build;

import java.io.IOException;
import java.io.OutputStream;

final class PhoneInstall {
    static final String ACTION = "com.garage.bandcontrol.installer.PHONE_INSTALL_RESULT";

    static int prepare(Context context, byte[] bytes) throws IOException, InterruptedException {
        return preparePackage(context, BundledPackages.APP_PACKAGE, bytes);
    }

    static int prepareSelf(Context context, byte[] bytes) throws IOException, InterruptedException {
        return preparePackage(context, context.getPackageName(), bytes);
    }

    private static int preparePackage(Context context, String packageName, byte[] bytes)
            throws IOException, InterruptedException {
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(packageName);
        params.setSize(bytes.length);
        if (Build.VERSION.SDK_INT >= 31) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED);
        }
        int id = installer.createSession(params);
        boolean ready = false;
        try (PackageInstaller.Session session = installer.openSession(id)) {
            try (OutputStream output = session.openWrite("base.apk", 0, bytes.length)) {
                for (int offset = 0; offset < bytes.length;) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    int count = Math.min(32 * 1024, bytes.length - offset);
                    output.write(bytes, offset, count);
                    offset += count;
                }
                session.fsync(output);
            }
            ready = true;
            return id;
        } finally {
            if (!ready) abandon(context, id);
        }
    }

    static void commit(Context context, int id) throws IOException {
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        Intent result = new Intent(ACTION).setPackage(context.getPackageName());
        int flags = PendingIntent.FLAG_CANCEL_CURRENT;
        if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
        PendingIntent callback = PendingIntent.getBroadcast(context, id, result, flags);
        try (PackageInstaller.Session session = installer.openSession(id)) {
            session.commit(callback.getIntentSender());
        }
    }

    static void abandon(Context context, int id) {
        try {
            context.getPackageManager().getPackageInstaller().abandonSession(id);
        } catch (SecurityException | IllegalStateException ignored) {
            // Android may already have finalized the session.
        }
    }

    static AppUpdate.Installed installedVersion(Context context) {
        return installedVersion(context, BundledPackages.APP_PACKAGE);
    }

    static AppUpdate.Installed selfVersion(Context context) {
        return installedVersion(context, context.getPackageName());
    }

    private static AppUpdate.Installed installedVersion(Context context, String packageName) {
        try {
            PackageInfo app = context.getPackageManager().getPackageInfo(packageName, 0);
            return new AppUpdate.Installed(app.versionName == null ? "" : app.versionName, app.getLongVersionCode());
        } catch (PackageManager.NameNotFoundException missing) {
            return null;
        }
    }
}
