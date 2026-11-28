package com.mario.lynkband.solar;

import android.graphics.BitmapFactory;
import android.util.AtomicFile;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.regex.Pattern;

public final class ThemeArt {
    public static final String CAPABILITY = "remoteThemes";
    public static final String OFFER = "remoteTheme";
    public static final String TRANSFER = "themeAsset";
    public static final int WEBP_LIMIT = 256 * 1024;
    public static final int PNG_LIMIT = 48 * 1024;
    public static final int WATCH_CHUNK = 12 * 1024;
    public static final int VELA_CHUNK = 1536;
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,31}");
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");

    private ThemeArt() {}

    public static boolean themeId(Object value) {
        return value instanceof String text && ID.matcher(text).matches();
    }

    public static boolean hash(Object value) {
        return value instanceof String text && HASH.matcher(text).matches();
    }

    public static String digest(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            char[] hex = "0123456789abcdef".toCharArray();
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest) result.append(hex[(value & 255) >>> 4]).append(hex[value & 15]);
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public static boolean title(Object value) {
        return value instanceof String text && !text.trim().isEmpty() && text.length() <= 32
                && text.chars().noneMatch(Character::isISOControl);
    }

    public static boolean valid(byte[] bytes, String sha, boolean png) {
        if (bytes == null || bytes.length == 0 || bytes.length > (png ? PNG_LIMIT : WEBP_LIMIT)
                || !digest(bytes).equals(sha)) return false;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        int edge = png ? 512 : 1600;
        return (png ? "image/png" : "image/webp").equals(bounds.outMimeType)
                && bounds.outWidth > 0 && bounds.outWidth <= edge && bounds.outHeight > 0 && bounds.outHeight <= edge;
    }

    public record Offer(String key, String title, String day, String night, String scene) {
        public JSONObject json() throws JSONException {
            return new JSONObject().put("key", key).put("title", title).put("day", day).put("night", night)
                    .put("scene", scene);
        }

        public static Offer parse(JSONObject json) throws JSONException {
            if (json == null || !hash(json.opt("key")) || !ThemeArt.title(json.opt("title"))
                    || !hash(json.opt("day")) || !hash(json.opt("night"))) throw new JSONException("Invalid theme");
            String title = json.getString("title");
            String scene = themeId(json.opt("scene")) ? json.getString("scene") : "generic";
            if ("generic".equals(scene)) scene = ThemeSync.themeIdForTitle(title);
            return new Offer(json.getString("key"), title, json.getString("day"),
                    json.getString("night"), scene);
        }
    }

    public record Stored(JSONObject info, byte[][] images) {}

    /** Both day and night are committed in one replacement. */
    public static final class Store {
        private final AtomicFile file;

        public Store(File path) { file = new AtomicFile(path); }

        public JSONObject info() throws IOException, JSONException {
            try (DataInputStream input = new DataInputStream(file.openRead())) {
                return new JSONObject(input.readUTF());
            }
        }

        public Stored read(int... limits) throws IOException, JSONException {
            try (DataInputStream input = new DataInputStream(file.openRead())) {
                JSONObject info = new JSONObject(input.readUTF());
                byte[][] images = new byte[limits.length][];
                for (int i = 0; i < limits.length; i++) {
                    int length = input.readInt();
                    if (length <= 0 || length > limits[i]) throw new IOException("Invalid theme length");
                    images[i] = new byte[length];
                    input.readFully(images[i]);
                }
                return new Stored(info, images);
            }
        }

        public void write(JSONObject info, byte[]... images) throws IOException {
            FileOutputStream stream = file.startWrite();
            try {
                DataOutputStream out = new DataOutputStream(stream);
                out.writeUTF(info.toString());
                for (byte[] bytes : images) {
                    out.writeInt(bytes.length);
                    out.write(bytes);
                }
                out.flush();
                file.finishWrite(stream);
            } catch (IOException error) {
                file.failWrite(stream);
                throw error;
            }
        }

        public void delete() { file.delete(); }
    }
}
