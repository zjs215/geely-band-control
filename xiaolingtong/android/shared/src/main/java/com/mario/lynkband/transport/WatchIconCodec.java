package com.mario.lynkband.transport;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.Base64;

import java.io.ByteArrayOutputStream;

public final class WatchIconCodec {
    // Four HD icons take at most 15,360 base64 bytes of a 32 KiB reply, leaving
    // space for the vehicle photo (8 KiB before base64) and action/status metadata.
    private static final int HD_PNG = 2880;
    public static final int SOURCE_LIMIT = 512;
    public static final String HD_CAPABILITY = "hdActionIcons";
    private static final int FLAGS = Base64.URL_SAFE | Base64.NO_WRAP;

    public enum Profile {
        LEGACY(960, FLAGS, 1, 48, 36, 24, 16, 12),
        HD(HD_PNG, FLAGS, 1, 96, 80, 64, 48, 36, 24, 16, 12),
        // Vela data URIs use standard base64. 1536 bytes encode to 2048 characters.
        BAND(1536, Base64.NO_WRAP, 32, 72, 64, 56, 48, 40, 36, 32);

        private final int bytes, flags, minEdge;
        private final int[] sizes;

        Profile(int bytes, int flags, int minEdge, int... sizes) {
            this.bytes = bytes;
            this.flags = flags;
            this.minEdge = minEdge;
            this.sizes = sizes;
        }
    }

    private WatchIconCodec() {}

    /** Load the matched packaged bitmap at native resolution, independent of phone DPI. */
    public static String encodeResource(Resources res, int drawableId, boolean hd) {
        return encodeResource(res, drawableId, hd ? Profile.HD : Profile.LEGACY);
    }

    public static String encodeResource(Resources res, int drawableId, Profile profile) {
        if (drawableId == 0) return "";
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inScaled = false;
        options.inJustDecodeBounds = true;
        try {
            BitmapFactory.decodeResource(res, drawableId, options);
            if (options.outWidth <= 0 || options.outHeight <= 0) return "";
            int largest = Math.max(options.outWidth, options.outHeight);
            options.inSampleSize = 1;
            while ((long) options.inSampleSize * SOURCE_LIMIT < largest) options.inSampleSize *= 2;
            options.inJustDecodeBounds = false;
            Bitmap bitmap = BitmapFactory.decodeResource(res, drawableId, options);
            if (bitmap == null) return "";
            try { return encode(bitmap, profile); }
            finally { bitmap.recycle(); }
        } catch (Resources.NotFoundException unavailable) { return ""; }
    }

    public static String encode(Bitmap source) { return encode(source, false); }

    /** Crop at source resolution before fitting the glyph to the negotiated pixel/byte budget. */
    public static String encode(Bitmap source, boolean hd) {
        return encode(source, hd ? Profile.HD : Profile.LEGACY);
    }

    public static String encode(Bitmap source, Profile profile) {
        if (source == null || source.isRecycled()) return "";
        int largest = Math.max(source.getWidth(), source.getHeight());
        if (largest > SOURCE_LIMIT) {
            float scale = (float) SOURCE_LIMIT / largest;
            Bitmap bounded = Bitmap.createScaledBitmap(source, Math.max(1, Math.round(source.getWidth() * scale)),
                    Math.max(1, Math.round(source.getHeight() * scale)), true);
            try { return encode(bounded, profile); }
            finally { bounded.recycle(); }
        }
        if (source.getConfig() == Bitmap.Config.HARDWARE) {
            Bitmap readable = source.copy(Bitmap.Config.ARGB_8888, false);
            if (readable == null) return "";
            try { return encode(readable, profile); }
            finally { readable.recycle(); }
        }
        Rect crop = visibleBounds(source);
        if (crop.isEmpty()) return "";
        int longest = Math.max(crop.width(), crop.height());
        if (longest < profile.minEdge) return "";
        int previous = 0;
        Paint filter = new Paint(Paint.FILTER_BITMAP_FLAG);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int size : profile.sizes) {
            int edge = Math.min(longest, size);
            if (edge == previous) continue;
            previous = edge;
            int width = Math.max(1, Math.round((float) edge * crop.width() / longest));
            int height = Math.max(1, Math.round((float) edge * crop.height() / longest));
            Bitmap sample = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            try {
                new Canvas(sample).drawBitmap(source, crop, new Rect(0, 0, width, height), filter);
                bytes.reset();
                if (sample.compress(Bitmap.CompressFormat.PNG, 100, bytes) && bytes.size() <= profile.bytes)
                    return Base64.encodeToString(bytes.toByteArray(), profile.flags);
            } finally { sample.recycle(); }
        }
        return "";
    }

    private static Rect visibleBounds(Bitmap source) {
        int w = source.getWidth(), h = source.getHeight();
        if (!source.hasAlpha()) return new Rect(0, 0, w, h);
        int left = w, top = h, right = 0, bottom = 0;
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            source.getPixels(row, 0, w, 0, y, w, 1);
            for (int x = 0; x < w; x++) {
                if ((row[x] >>> 24) == 0) continue;
                left = Math.min(left, x);
                right = Math.max(right, x + 1);
                top = Math.min(top, y);
                bottom = y + 1;
            }
        }
        return new Rect(left, top, right, bottom);
    }

    public static Bitmap decode(String encoded) {
        if (encoded == null || encoded.isEmpty() || encoded.length() > 4 * ((HD_PNG + 2) / 3)) return null;
        try {
            byte[] bytes = Base64.decode(encoded, FLAGS);
            if (bytes.length > HD_PNG) return null;
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            if (!"image/png".equals(bounds.outMimeType) || bounds.outWidth <= 0 || bounds.outHeight <= 0
                    || Math.max(bounds.outWidth, bounds.outHeight) > Profile.HD.sizes[0]) return null;
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }
}
