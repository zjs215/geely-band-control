package com.mario.lynkband.watch;

import com.mario.lynkband.transport.WatchUpdateWire;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;

import java.io.File;
import java.util.Arrays;
import java.util.HashSet;

final class WatchUpdateApk {
    private static final String PACKAGE = "com.mario.lynkband.watch";

    private WatchUpdateApk() {}

    static PackageInfo installed(Context context) throws PackageManager.NameNotFoundException {
        return context.getPackageManager().getPackageInfo(PACKAGE, PackageManager.GET_SIGNING_CERTIFICATES);
    }

    static void verify(Context context, PackageInfo installed, File apk) throws WatchUpdateWire.InvalidUpdate {
        PackageInfo candidate = context.getPackageManager()
                .getPackageArchiveInfo(apk.getAbsolutePath(), PackageManager.GET_SIGNING_CERTIFICATES);
        if (candidate == null || !PACKAGE.equals(candidate.packageName))
            throw new WatchUpdateWire.InvalidUpdate("更新包不是有效的手表应用");
        Signature[] current = installed.signingInfo == null ? null : installed.signingInfo.getApkContentsSigners();
        Signature[] incoming = candidate.signingInfo == null ? null : candidate.signingInfo.getApkContentsSigners();
        if (!sameSigners(current, incoming))
            throw new WatchUpdateWire.InvalidUpdate("更新包签名与当前手表应用不一致");
        if (!WatchUpdateWire.versionAllowed(installed.getLongVersionCode(), candidate.getLongVersionCode()))
            throw new WatchUpdateWire.InvalidUpdate("更新包版本低于当前手表应用，不能降级");
    }

    static boolean sameSigners(Signature[] a, Signature[] b) {
        return a != null && a.length != 0 && b != null
                && new HashSet<>(Arrays.asList(a)).equals(new HashSet<>(Arrays.asList(b)));
    }
}
