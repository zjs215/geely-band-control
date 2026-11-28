package com.mario.lynkband.vela;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Encrypted per-device storage for user-supplied Vela AuthKeys. */
public final class VelaAuthKeys {
    static final String PREFS = "vela.auth.keys";
    private static final String KEY_ALIAS = "com.mario.lynkband.VelaAuthKeys.v1";
    private static final String VALUE_PREFIX = "v1:";
    private static final Pattern ADDRESS = Pattern.compile("[0-9A-F]{2}(?::[0-9A-F]{2}){5}");
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_BYTES = 12;
    private static final Object LOCK = new Object();

    private VelaAuthKeys() {}

    /** Returns a canonical Bluetooth address, or null for an invalid address. */
    public static String normalizeAddress(String value) {
        if (value == null) return null;
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return ADDRESS.matcher(normalized).matches() ? normalized : null;
    }

    /** Formats a raw 16-byte AuthKey for the password field without retaining it. */
    public static String format(byte[] key) {
        if (key == null || key.length != 16) throw new IllegalArgumentException("AuthKey must be 16 bytes");
        StringBuilder result = new StringBuilder(32);
        for (byte item : key) result.append(String.format(Locale.ROOT, "%02x", item & 0xff));
        return result.toString();
    }

    public static void save(Context context, String deviceAddress, byte[] key) throws GeneralSecurityException {
        String address = requireAddress(deviceAddress);
        if (key == null || key.length != 16) throw new IllegalArgumentException("AuthKey must be 16 bytes");
        byte[] encoded = null;
        try {
            synchronized (LOCK) {
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.ENCRYPT_MODE, secretKey());
                byte[] iv = cipher.getIV();
                byte[] ciphertext = cipher.doFinal(key);
                encoded = new byte[iv.length + ciphertext.length];
                System.arraycopy(iv, 0, encoded, 0, iv.length);
                System.arraycopy(ciphertext, 0, encoded, iv.length, ciphertext.length);
                String value = VALUE_PREFIX + Base64.encodeToString(encoded, Base64.NO_WRAP);
                context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .edit().putString(address, value).apply();
            }
        } finally {
            if (encoded != null) Arrays.fill(encoded, (byte) 0);
        }
    }

    /** Returns a new raw AuthKey, or null when no valid key is stored for this device. */
    public static byte[] load(Context context, String deviceAddress) {
        String address = normalizeAddress(deviceAddress);
        if (address == null) return null;
        String value = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(address, null);
        if (value == null || !value.startsWith(VALUE_PREFIX)) return null;
        byte[] encoded = null;
        try {
            encoded = Base64.decode(value.substring(VALUE_PREFIX.length()), Base64.DEFAULT);
            if (encoded.length <= IV_BYTES) throw new GeneralSecurityException("Invalid AuthKey record");
            byte[] iv = Arrays.copyOf(encoded, IV_BYTES);
            byte[] cipherText = Arrays.copyOfRange(encoded, IV_BYTES, encoded.length);
            try {
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, secretKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
                byte[] key = cipher.doFinal(cipherText);
                if (key.length != 16) {
                    Arrays.fill(key, (byte) 0);
                    throw new GeneralSecurityException("Invalid AuthKey length");
                }
                return key;
            } finally {
                Arrays.fill(iv, (byte) 0);
                Arrays.fill(cipherText, (byte) 0);
            }
        } catch (GeneralSecurityException | IllegalArgumentException unavailable) {
            // A reset/replaced keystore invalidates old records. Remove only this device's record.
            clear(context, address);
            return null;
        } finally {
            if (encoded != null) Arrays.fill(encoded, (byte) 0);
        }
    }

    public static void clear(Context context, String deviceAddress) {
        String address = normalizeAddress(deviceAddress);
        if (address == null) return;
        context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(address).apply();
    }

    public static boolean has(Context context, String deviceAddress) {
        byte[] key = load(context, deviceAddress);
        if (key == null) return false;
        Arrays.fill(key, (byte) 0);
        return true;
    }

    private static String requireAddress(String value) {
        String normalized = normalizeAddress(value);
        if (normalized == null) throw new IllegalArgumentException("Invalid Bluetooth address");
        return normalized;
    }

    private static SecretKey secretKey() throws GeneralSecurityException {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        try {
            store.load(null);
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException | java.security.cert.CertificateException failure) {
            throw new GeneralSecurityException("Unable to load Android Keystore", failure);
        }
        if (!store.containsAlias(KEY_ALIAS)) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build());
            generator.generateKey();
        }
        return ((KeyStore.SecretKeyEntry) store.getEntry(KEY_ALIAS, null)).getSecretKey();
    }
}
