package com.garage.bandcontrol.installer;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.IOException;
import java.util.Locale;

final class LogDocuments {
    static final Uri INITIAL_FOLDER = DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents", "primary:Download/wearablelog");

    private LogDocuments() {}

    static Uri latestZip(ContentResolver resolver, Uri folder) throws IOException {
        String[] columns = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                DocumentsContract.Document.COLUMN_MIME_TYPE
        };
        String id = null;
        String name = "";
        long modified = Long.MIN_VALUE;
        try {
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(folder,
                    DocumentsContract.getTreeDocumentId(folder));
            try (Cursor cursor = resolver.query(children, columns, null, null, null)) {
                if (cursor == null) throw new InstallFailure("无法读取日志目录，请重新授权。");
                while (cursor.moveToNext()) {
                    String candidate = cursor.getString(1);
                    if (candidate == null || !candidate.toLowerCase(Locale.ROOT).endsWith(".zip")
                            || DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(3))) continue;
                    long timestamp = cursor.isNull(2) ? 0 : cursor.getLong(2);
                    if (timestamp > modified || timestamp == modified && candidate.compareTo(name) > 0) {
                        id = cursor.getString(0);
                        name = candidate;
                        modified = timestamp;
                    }
                }
            }
            if (id == null) {
                throw new InstallFailure("日志目录里没有 ZIP。请先在小米运动健康导出日志。");
            }
            return DocumentsContract.buildDocumentUriUsingTree(folder, id);
        } catch (SecurityException | IllegalArgumentException unavailable) {
            throw new InstallFailure("无法访问日志目录，请在助手里重新授权。", unavailable);
        }
    }
}
