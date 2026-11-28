package com.mario.lynkband.watch;

import android.graphics.Bitmap;
import android.graphics.ImageDecoder;

import com.mario.lynkband.transport.WatchPhotoWire;

import org.json.JSONObject;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/** Validates and decodes an optional photo on the Bluetooth reader thread. */
final class WatchPhoto {
    private static final int MAX_BYTES = WatchPhotoWire.HD_BYTES;
    private WatchPhoto() {}

    static Bitmap decode(JSONObject data) {
        if (data == null || !(data.opt("carPhoto") instanceof String encoded)
                || !(data.opt("carPhotoId") instanceof String id) || id.length() != 64
                || encoded.isEmpty() || encoded.length() > 4 * ((MAX_BYTES + 2) / 3)) return null;
        try {
            byte[] bytes = Base64.getDecoder().decode(encoded);
            if (bytes.length > MAX_BYTES || !matchesDigest(id, bytes)) return null;
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes)), (decoder, info, source) -> {
                String mime = info.getMimeType();
                if (!("image/png".equals(mime) || "image/webp".equals(mime))
                        || info.getSize().getWidth() > WatchPhotoWire.HD_WIDTH
                        || info.getSize().getHeight() > WatchPhotoWire.HD_HEIGHT) {
                    throw new IllegalArgumentException("Unsupported watch thumbnail");
                }
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            });
        } catch (IllegalArgumentException | IOException malformed) {
            return null;
        }
    }

    private static boolean matchesDigest(String id, byte[] bytes) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
            for (int i = 0; i < hash.length; i++) {
                if (Character.forDigit((hash[i] & 255) >>> 4, 16) != id.charAt(2 * i)
                        || Character.forDigit(hash[i] & 15, 16) != id.charAt(2 * i + 1)) return false;
            }
            return true;
        } catch (NoSuchAlgorithmException unavailable) {
            throw new AssertionError(unavailable); // SHA-256 is required by Android.
        }
    }
}
