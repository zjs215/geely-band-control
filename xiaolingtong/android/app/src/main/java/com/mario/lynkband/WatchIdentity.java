// Adapted for 小领通 from zjs215/geely-band-control (Apache-2.0).
package com.mario.lynkband;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;
import java.util.Date;
import java.util.concurrent.TimeUnit;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Wireless ADB's pairing library needs an encodable RSA key. Keep it encrypted
 * in private app storage with a separate non-exportable Android Keystore key.
 */
record WatchIdentity(PrivateKey key, X509Certificate certificate) {
    private static final String ALIAS = "xiaolingtong-watch-pairing";
    private static final String PREFS = "watch-pairing";
    private static final String IDENTITY = "identity";
    private static final String CIPHER = "AES/GCM/NoPadding";
    private static final int IV_SIZE = 12;

    static synchronized WatchIdentity load(Context context) throws Exception {
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String stored = preferences.getString(IDENTITY, null);
        KeyStore keystore = KeyStore.getInstance("AndroidKeyStore");
        keystore.load(null);
        SecretKey wrappingKey = (SecretKey) keystore.getKey(ALIAS, null);
        if (stored != null && wrappingKey != null) {
            try {
                return decode(wrappingKey, Base64.decode(stored, Base64.NO_WRAP));
            } catch (Exception expiredIdentity) {
                // This app's key may have been invalidated by the phone OS.
                // Generate a new identity so the user can pair the watch again.
            }
        }

        if (wrappingKey == null) {
            KeyGenerator generator = KeyGenerator.getInstance("AES", "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build());
            wrappingKey = generator.generateKey();
        }
        WatchIdentity identity = create();
        String encrypted = Base64.encodeToString(identity.encode(wrappingKey), Base64.NO_WRAP);
        if (!preferences.edit().putString(IDENTITY, encrypted).commit()) {
            throw new IOException("无法保存手表配对凭据");
        }
        return identity;
    }

    private static WatchIdentity create() throws Exception {
        SecureRandom random = new SecureRandom();
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048, random);
        KeyPair pair = generator.generateKeyPair();
        X500Name subject = new X500Name("CN=Xiaolingtong Watch Installer");
        long now = System.currentTimeMillis();
        JcaX509v3CertificateBuilder certificate = new JcaX509v3CertificateBuilder(subject,
                new BigInteger(127, random).setBit(126), new Date(now - TimeUnit.DAYS.toMillis(1)),
                new Date(now + TimeUnit.DAYS.toMillis(3650)), subject, pair.getPublic());
        return new WatchIdentity(pair.getPrivate(), new JcaX509CertificateConverter()
                .getCertificate(certificate.build(new JcaContentSignerBuilder("SHA256withRSA")
                        .build(pair.getPrivate()))));
    }

    private byte[] encode(SecretKey wrappingKey) throws Exception {
        byte[] keyBytes = key.getEncoded();
        byte[] certBytes = certificate.getEncoded();
        byte[] plain = ByteBuffer.allocate(4 + keyBytes.length + certBytes.length)
                .putInt(keyBytes.length).put(keyBytes).put(certBytes).array();
        try {
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.ENCRYPT_MODE, wrappingKey);
            byte[] encrypted = cipher.doFinal(plain);
            return ByteBuffer.allocate(IV_SIZE + encrypted.length).put(cipher.getIV()).put(encrypted).array();
        } finally {
            Arrays.fill(plain, (byte) 0);
            Arrays.fill(keyBytes, (byte) 0);
        }
    }

    private static WatchIdentity decode(SecretKey wrappingKey, byte[] record) throws Exception {
        Cipher cipher = Cipher.getInstance(CIPHER);
        cipher.init(Cipher.DECRYPT_MODE, wrappingKey, new GCMParameterSpec(128, record, 0, IV_SIZE));
        byte[] plain = cipher.doFinal(record, IV_SIZE, record.length - IV_SIZE);
        byte[] keyBytes = null;
        try {
            ByteBuffer input = ByteBuffer.wrap(plain);
            int keyLength = input.getInt();
            if (keyLength < 1 || keyLength >= input.remaining()) throw new IOException("Invalid pairing identity");
            keyBytes = new byte[keyLength];
            input.get(keyBytes);
            PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
            X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(plain, input.position(), input.remaining()));
            return new WatchIdentity(key, cert);
        } finally {
            Arrays.fill(plain, (byte) 0);
            if (keyBytes != null) Arrays.fill(keyBytes, (byte) 0);
        }
    }
}
