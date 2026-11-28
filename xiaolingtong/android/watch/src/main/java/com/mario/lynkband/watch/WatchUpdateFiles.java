package com.mario.lynkband.watch;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.regex.Pattern;

/** Gives the system installer read access to a single verified APK per grant. */
public final class WatchUpdateFiles extends ContentProvider {
    static final String APK_TYPE = "application/vnd.android.package-archive";
    private static final long KEEP_MS = 24 * 60 * 60 * 1000L;
    private static final Pattern APK_NAME = Pattern.compile("[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}\\.apk");

    static File directory(Context context) {
        return new File(context.getCacheDir(), "watch-update");
    }

    static File staged(Context context) {
        File[] files = directory(context).listFiles();
        if (files == null) return null;
        File staged = null;
        for (File file : files) {
            boolean apk = APK_NAME.matcher(file.getName()).matches();
            if (apk && fresh(file)) {
                staged = file;
            } else if (apk || (file.getName().startsWith("incoming-") && expired(file))) {
                file.delete();
            }
        }
        return staged;
    }

    static Uri uri(Context context, File file) {
        return apkUri(context, file.getName());
    }

    private static Uri apkUri(Context context, String name) {
        return new Uri.Builder().scheme("content").authority(context.getPackageName() + ".updates")
                .appendPath(name).build();
    }

    private static boolean expired(File file) {
        return System.currentTimeMillis() - file.lastModified() >= KEEP_MS;
    }

    private static boolean fresh(File file) {
        return file.isFile() && !expired(file);
    }

    private File resolve(Uri uri) throws FileNotFoundException {
        Context context = getContext();
        if (context == null || uri.getPathSegments().size() != 1) throw new FileNotFoundException();
        String name = uri.getLastPathSegment();
        if (name == null || !APK_NAME.matcher(name).matches()
                || !apkUri(context, name).equals(uri)) throw new FileNotFoundException();
        File file = new File(directory(context), name);
        if (!fresh(file)) throw new FileNotFoundException();
        return file;
    }

    @Override public boolean onCreate() { return true; }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException();
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public String getType(Uri uri) { return APK_TYPE; }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        File file;
        try {
            file = resolve(uri);
        } catch (FileNotFoundException absent) {
            return null;
        }
        String[] columns = projection == null
                ? new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
        Object[] row = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) row[i] = "LynkBand-Watch.apk";
            if (OpenableColumns.SIZE.equals(columns[i])) row[i] = file.length();
        }
        MatrixCursor result = new MatrixCursor(columns, 1);
        result.addRow(row);
        return result;
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
