package com.garage.bandcontrol;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PaintFlagsDrawFilter;
import android.graphics.drawable.BitmapDrawable;
import android.view.View;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

final class CarImage {
    static final int CHUNK_CHARS = 1400;
    static final int MAX_CHUNKS = 160;
    private static final int TARGET_CHUNKS = 96;
    private static final int[][] TIERS = {
            {420, 180},
            {360, 154},
            {300, 128},
            {240, 102},
            {180, 76}
    };

    record Packaged(String hash, List<String> chunks) {}

    private CarImage() {}

    static Packaged extract(View view) {
        if (view == null || view.getWidth() <= 0 || view.getHeight() <= 0) return null;
        Bitmap source = captureSource(view);
        if (source == null) return null;
        try {
            Bitmap cropped = trimTransparent(source, 24, 12);
            Bitmap subject = cropped != null ? cropped : source;
            try {
                if (!hasVisibleDetail(subject)) return null;
                Packaged best = null;
                for (int[] tier : TIERS) {
                    Bitmap scaled = scaleDown(subject, tier[0], tier[1]);
                    if (scaled == null) continue;
                    byte[] png;
                    try {
                        png = toPng(scaled);
                    } finally {
                        if (scaled != subject) scaled.recycle();
                    }
                    if (png == null) continue;
                    Packaged candidate = packagePng(png);
                    if (candidate == null) continue;
                    best = candidate;
                    if (candidate.chunks().size() <= TARGET_CHUNKS) break;
                }
                return best;
            } finally {
                if (cropped != null) cropped.recycle();
            }
        } finally {
            source.recycle();
        }
    }

    static Packaged packagePng(byte[] pngBytes) {
        return packagePng(pngBytes, MAX_CHUNKS);
    }

    static Packaged packagePng(byte[] pngBytes, int maxChunks) {
        if (pngBytes == null || pngBytes.length == 0) return null;
        String base64 = Base64.getEncoder().encodeToString(pngBytes);
        if (base64.isEmpty()) return null;
        List<String> chunks = new ArrayList<>();
        for (int i = 0; i < base64.length(); i += CHUNK_CHARS) {
            chunks.add(base64.substring(i, Math.min(base64.length(), i + CHUNK_CHARS)));
        }
        if (chunks.size() > maxChunks) return null;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(pngBytes);
            String hash = Base64.getUrlEncoder().withoutPadding().encodeToString(digest).substring(0, 12);
            return new Packaged(hash, List.copyOf(chunks));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    static Bitmap captureSource(View view) {
        if (view instanceof ImageView imageView && imageView.getBackground() == null
                && imageView.getImageTintList() == null && imageView.getColorFilter() == null
                && imageView.getDrawable() instanceof BitmapDrawable drawable) {
            Bitmap raw = drawable.getBitmap();
            ImageView.ScaleType scaleType = imageView.getScaleType();
            boolean fullImageFit = scaleType == ImageView.ScaleType.FIT_CENTER
                    || scaleType == ImageView.ScaleType.CENTER_INSIDE
                    || scaleType == ImageView.ScaleType.FIT_XY
                    || scaleType == ImageView.ScaleType.FIT_START
                    || scaleType == ImageView.ScaleType.FIT_END;
            if (raw != null && !raw.isRecycled() && fullImageFit
                    && raw.getWidth() >= view.getWidth() && raw.getHeight() >= view.getHeight()) {
                return raw.copy(Bitmap.Config.ARGB_8888, false);
            }
        }
        int srcW = view.getWidth();
        int srcH = view.getHeight();
        Bitmap bitmap = Bitmap.createBitmap(srcW, srcH, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.setDrawFilter(new PaintFlagsDrawFilter(0, Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG));
        view.draw(canvas);
        return bitmap;
    }

    static Bitmap trimTransparent(Bitmap source, int minWidth, int minHeight) {
        int w = source.getWidth();
        int h = source.getHeight();
        int[] row = new int[w];
        int minX = w;
        int minY = h;
        int maxX = -1;
        int maxY = -1;
        for (int y = 0; y < h; y++) {
            source.getPixels(row, 0, w, 0, y, w, 1);
            for (int x = 0; x < w; x++) {
                if (Color.alpha(row[x]) > 4) {
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
        }
        if (maxX < minX || maxY < minY) return null;
        int left = Math.max(0, minX - 2);
        int top = Math.max(0, minY - 2);
        int right = Math.min(w, maxX + 3);
        int bottom = Math.min(h, maxY + 3);
        int cropW = right - left;
        int cropH = bottom - top;
        if (cropW < minWidth || cropH < minHeight || (cropW == w && cropH == h)) return null;
        return Bitmap.createBitmap(source, left, top, cropW, cropH);
    }

    static Bitmap scaleDown(Bitmap source, int maxWidth, int maxHeight) {
        int srcW = source.getWidth();
        int srcH = source.getHeight();
        float scale = Math.min((float) maxWidth / srcW, (float) maxHeight / srcH);
        if (scale > 1f) scale = 1f;
        int dstW = Math.max(1, Math.round(srcW * scale));
        int dstH = Math.max(1, Math.round(srcH * scale));
        return Bitmap.createScaledBitmap(source, dstW, dstH, true);
    }

    private static boolean hasVisibleDetail(Bitmap bitmap) {
        int stepX = Math.max(1, bitmap.getWidth() / 16);
        int stepY = Math.max(1, bitmap.getHeight() / 12);
        int firstColor = 0;
        boolean seenOpaque = false;
        for (int y = 0; y < bitmap.getHeight(); y += stepY) {
            for (int x = 0; x < bitmap.getWidth(); x += stepX) {
                int pixel = bitmap.getPixel(x, y);
                if (Color.alpha(pixel) <= 16) continue;
                if (!seenOpaque) {
                    firstColor = pixel;
                    seenOpaque = true;
                } else if (pixel != firstColor) {
                    return true;
                }
            }
        }
        return false;
    }

    static byte[] toPng(Bitmap bitmap) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(16384);
        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)) return null;
        return out.toByteArray();
    }
}
