package com.mario.lynkband;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.Base64;
import android.view.View;
import android.widget.ImageView;

import com.mario.lynkband.transport.WatchPhotoWire;

import java.io.ByteArrayOutputStream;
import java.lang.ref.WeakReference;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Reads the current provider bitmap on the widget host's main thread. */
final class VehiclePhoto {
    record Image(String id, String base64) {}

    enum Target {
        WATCH(256, 128, 8192, false, false),
        BAND(160, 80, BandPhoto.MAX_CHARS / 4 * 3, true, false),
        WATCH_HD(WatchPhotoWire.HD_WIDTH, WatchPhotoWire.HD_HEIGHT, WatchPhotoWire.HD_BYTES, false, true),
        BAND_HD(288, 144, BandPhoto.HD_MAX_CHARS / 4 * 3, true, true);

        final int width, height, budget;
        final boolean png, hd;

        Target(int width, int height, int budget, boolean png, boolean hd) {
            this.width = width;
            this.height = height;
            this.budget = budget;
            this.png = png;
            this.hd = hd;
        }
    }

    private final Target target;
    private WeakReference<Bitmap> previous = new WeakReference<>(null);
    private int generation;
    private String previousVehicle;
    private Image cached;

    VehiclePhoto(Target target) { this.target = target; }

    Image read(View root, String binding) {
        View panel = CardContent.find(root, "rl_widget_cal_state");
        String name = CardContent.text(panel, "widget_name");
        if (panel == null || CardContent.awaitingOwner(root) || CardContent.find(root, "ll_loading_auth") != null
                || CardContent.find(root, "widget_hint") != null || name.isEmpty())
            return null;
        // com.lynkco.customer 4.3.1 (403101550), layout_widget / res/rcW.xml:331.
        // car_img's XML src is mipmap/car_side_line until the provider supplies the vehicle.
        View slot = CardContent.find(panel, "car_img");
        if (!(slot instanceof ImageView image) || image.getImageAlpha() == 0 || !visibleInCard(slot, root)
                || !(image.getDrawable() instanceof BitmapDrawable drawable)) return null;
        Bitmap source = drawable.getBitmap();
        if (source == null || source.isRecycled()) return null;
        String vehicle = binding + "/" + name;
        if (previous.get() == source && generation == source.getGenerationId()) {
            // A partial RemoteViews update may change the vehicle before replacing its bitmap.
            return !target.png || vehicle.equals(previousVehicle) ? cached : null;
        }
        previousVehicle = vehicle;
        previous = new WeakReference<>(source);
        generation = source.getGenerationId();
        cached = null;
        try {
            Resources res = slot.getResources();
            if (!LynkWidget.OWNER.equals(res.getResourcePackageName(slot.getId()))) return null;
            int placeholderId = res.getIdentifier("car_side_line", "mipmap", LynkWidget.OWNER);
            if (placeholderId == 0) return null;
            Drawable initial = res.getDrawable(placeholderId, null);
            if (!(initial instanceof BitmapDrawable silhouette) || source.sameAs(silhouette.getBitmap())) return null;
            cached = encode(source);
        } catch (RuntimeException unavailable) {
            // A stale provider resource or an unreadable bitmap must not affect card status and controls.
        }
        return cached;
    }

    private static boolean visibleInCard(View view, View root) {
        for (View current = view; current != null; current = current.getParent() instanceof View p ? p : null) {
            if (current.getVisibility() != View.VISIBLE || current.getAlpha() <= 0f) return false;
            // The host also renders offscreen when the companion activity is closed.
            if (current == root) return true;
        }
        return false;
    }

    Image encode(Bitmap source) {
        Bitmap artwork = target.hd ? trimTransparentPadding(source) : source;
        try {
            return encodeThumbnail(artwork);
        } finally {
            if (artwork != source) artwork.recycle();
        }
    }

    @SuppressWarnings("deprecation") // API 28/29 spell lossy WebP as WEBP.
    private Image encodeThumbnail(Bitmap source) {
        float scale = Math.min(1f, Math.min((float) target.width / source.getWidth(), (float) target.height / source.getHeight()));
        int width = Math.max(1, Math.round(source.getWidth() * scale));
        int height = Math.max(1, Math.round(source.getHeight() * scale));
        Bitmap.CompressFormat format = target.png ? Bitmap.CompressFormat.PNG
                : Build.VERSION.SDK_INT >= 30 ? Bitmap.CompressFormat.WEBP_LOSSY : Bitmap.CompressFormat.WEBP;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(target.budget);
        int attempts = target.hd ? 6 : 4;
        for (int attempt = 0; attempt < attempts; attempt++) {
            Bitmap scaled = Bitmap.createScaledBitmap(source, width, height, true);
            Bitmap readable = scaled;
            try {
                if (scaled.getConfig() == Bitmap.Config.HARDWARE) readable = scaled.copy(Bitmap.Config.ARGB_8888, false);
                if (readable == null || !hasVisiblePixel(readable)) return null;
                bytes.reset();
                if (!readable.compress(format, target.hd ? 89 : 85, bytes)) return null;
                if (bytes.size() > 0 && bytes.size() <= target.budget) {
                    byte[] encoded = bytes.toByteArray();
                    return new Image(digest(encoded), Base64.encodeToString(encoded, Base64.NO_WRAP));
                }
            } finally {
                if (readable != null && readable != scaled) readable.recycle();
                if (scaled != source) scaled.recycle();
            }
            width = Math.max(1, target.hd ? width * 5 / 6 : width * 2 / 3);
            height = Math.max(1, target.hd ? height * 5 / 6 : height * 2 / 3);
        }
        return null;
    }

    private static Bitmap trimTransparentPadding(Bitmap source) {
        if (!source.hasAlpha() || source.getConfig() == Bitmap.Config.HARDWARE) return source;
        int w = Math.min(source.getWidth(), 160), h = Math.min(source.getHeight(), 72);
        Bitmap sample = Bitmap.createScaledBitmap(source, w, h, true);
        Rect bounds = new Rect(w, h, 0, 0);
        try {
            int[] row = new int[w];
            for (int y = 0; y < h; y++) {
                sample.getPixels(row, 0, w, 0, y, w, 1);
                for (int x = 0; x < w; x++) {
                    if ((row[x] >>> 24) < 16) continue;
                    bounds.left = Math.min(bounds.left, x);
                    bounds.top = Math.min(bounds.top, y);
                    bounds.right = Math.max(bounds.right, x + 1);
                    bounds.bottom = Math.max(bounds.bottom, y + 1);
                }
            }
        } finally {
            if (sample != source) sample.recycle();
        }
        if (bounds.isEmpty()) return source;
        // Leave two sample pixels at each edge for soft antialiasing and fine details.
        bounds.inset(-2, -2);
        bounds.intersect(0, 0, w, h);
        int left = bounds.left * source.getWidth() / w;
        int top = bounds.top * source.getHeight() / h;
        int right = (int) Math.ceil(bounds.right * (double) source.getWidth() / w);
        int bottom = (int) Math.ceil(bounds.bottom * (double) source.getHeight() / h);
        return Bitmap.createBitmap(source, left, top, right - left, bottom - top);
    }

    private static boolean hasVisiblePixel(Bitmap bitmap) {
        if (!bitmap.hasAlpha()) return true;
        int[] row = new int[bitmap.getWidth()];
        for (int y = 0; y < bitmap.getHeight(); y++) {
            bitmap.getPixels(row, 0, row.length, 0, y, row.length, 1);
            for (int pixel : row) if ((pixel >>> 24) != 0) return true;
        }
        return false;
    }

    private static String digest(byte[] bytes) {
        try {
            StringBuilder hex = new StringBuilder(64);
            for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) {
                hex.append(Character.forDigit((b & 255) >>> 4, 16)).append(Character.forDigit(b & 15, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
