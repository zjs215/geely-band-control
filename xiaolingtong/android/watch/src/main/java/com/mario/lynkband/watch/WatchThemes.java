package com.mario.lynkband.watch;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.SystemClock;
import android.util.Base64;

import com.mario.lynkband.solar.ThemeArt;
import com.mario.lynkband.solar.ThemeSync;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;

final class WatchThemes {
    private final ThemeArt.Store store;
    private final SharedPreferences settings;
    private String legacyDisplay;
    private ThemeArt.Offer active;
    private ThemeArt.Offer offered;
    private byte[][] images;
    private Bitmap preview;
    private boolean previewNight;
    private final ByteArrayOutputStream received = new ByteArrayOutputStream();
    private byte[] day;
    private int length;
    private long retryAfter;

    WatchThemes(Context context) {
        settings = context.getSharedPreferences("watchTheme", Context.MODE_PRIVATE);
        legacyDisplay = settings.getString("legacyDisplayTheme", null);
        store = new ThemeArt.Store(new File(context.getFilesDir(), "visual-theme.dat"));
        try {
            ThemeArt.Stored saved = store.read(ThemeArt.WEBP_LIMIT, ThemeArt.WEBP_LIMIT);
            ThemeArt.Offer info = ThemeArt.Offer.parse(saved.info());
            if (ThemeArt.valid(saved.images()[0], info.day(), false)
                    && ThemeArt.valid(saved.images()[1], info.night(), false)) {
                images = saved.images();
                active = info;
            }
        } catch (IOException | JSONException ignored) { /* Show the built-in theme. */ }
    }

    private boolean showActive() {
        return active != null && (legacyDisplay == null || legacyDisplay.equals(active.scene()));
    }

    private void legacyDisplay(String id) {
        if (java.util.Objects.equals(legacyDisplay, id)) return;
        legacyDisplay = id;
        settings.edit().putString("legacyDisplayTheme", id).apply();
    }

    String key() { return showActive() ? active.key() : ""; }
    String scene() { return showActive() ? active.scene() : "sky"; }
    String title() { return showActive() ? active.title() : "蓝天与星空"; }
    boolean waitingForLegacyArt() { return legacyDisplay != null && !"sky".equals(legacyDisplay) && !showActive(); }

    Bitmap preview(boolean night) {
        if (!showActive()) return null;
        if (preview == null || previewNight != night) {
            byte[] bytes = images[night ? 1 : 0];
            preview = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            previewNight = night;
        }
        return preview;
    }

    void acceptOffer(JSONObject response) {
        if (!response.has(ThemeArt.OFFER)) {
            disconnected();
            JSONObject legacy = response.optJSONObject("landscapeSync");
            String id = legacy == null ? null : ThemeSync.legacyThemeId(legacy.opt("scene"));
            if (id != null) legacyDisplay(id);
            return;
        }
        if (response.isNull(ThemeArt.OFFER)) {
            disconnected();
            legacyDisplay(null);
            if (active != null) {
                store.delete();
                active = null;
                images = null;
                preview = null;
            }
            return;
        }
        try {
            ThemeArt.Offer next = ThemeArt.Offer.parse(response.optJSONObject(ThemeArt.OFFER));
            if (next.equals(active)) legacyDisplay(null);
            if (!next.equals(offered)) {
                reset();
                offered = next;
                retryAfter = 0;
            }
        } catch (JSONException invalid) {
            disconnected();
        }
    }

    private void reset() {
        received.reset();
        length = 0;
        day = null;
    }

    void disconnected() {
        offered = null;
        reset();
    }

    boolean ready() {
        return offered != null && !offered.equals(active) && SystemClock.elapsedRealtime() >= retryAfter;
    }

    void request(JSONObject frame) throws JSONException {
        frame.put("key", offered.key()).put("part", day == null ? "day" : "night").put("offset", received.size());
    }

    void acceptChunk(JSONObject response) {
        if (!ready()) return;
        try {
            String part = day == null ? "day" : "night";
            int total = response.getInt("length");
            int offset = received.size();
            String chunk = response.getString("chunk");
            if (!ThemeArt.TRANSFER.equals(response.opt("type")) || !offered.key().equals(response.opt("key"))
                    || !part.equals(response.opt("part")) || response.getInt("offset") != offset
                    || total <= offset || total > ThemeArt.WEBP_LIMIT || (offset > 0 && total != length)
                    || chunk.length() > ThemeArt.WATCH_CHUNK / 3 * 4) throw new IOException("Invalid chunk");
            byte[] bytes = Base64.decode(chunk, Base64.NO_WRAP);
            if (bytes.length != Math.min(ThemeArt.WATCH_CHUNK, total - offset)) throw new IOException("Invalid chunk size");
            length = total;
            received.write(bytes, 0, bytes.length);
            if (received.size() != length) return;
            byte[] image = received.toByteArray();
            if (!ThemeArt.valid(image, day == null ? offered.day() : offered.night(), false))
                throw new IOException("Invalid theme image");
            if (day == null) {
                day = image;
                received.reset();
                length = 0;
            } else {
                store.write(offered.json(), day, image);
                images = new byte[][]{day, image};
                active = offered;
                legacyDisplay(null);
                preview = null;
                reset();
            }
        } catch (IOException | JSONException | IllegalArgumentException invalid) {
            reset();
            retryAfter = SystemClock.elapsedRealtime() + 30_000L;
        }
    }
}
