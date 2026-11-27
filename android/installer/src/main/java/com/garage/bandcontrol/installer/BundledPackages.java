package com.garage.bandcontrol.installer;

import android.content.res.AssetManager;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Properties;

final class BundledPackages {
    static final String APP_PACKAGE = "com.garage.bandcontrol";
    private static final String ROOT = "wearable/";

    record Package(String fileName, int size, String version, int versionCode, String sha256) {}

    private final AssetManager assets;
    final Package band;
    final Package watch;

    BundledPackages(AssetManager assets) throws IOException {
        this.assets = assets;
        Properties metadata = new Properties();
        try (InputStream input = assets.open(ROOT + "packages.properties")) {
            metadata.load(input);
        }
        band = readMetadata(metadata, "band", "galaxy-vela-band.rpk");
        watch = readMetadata(metadata, "watch", "galaxy-watch.apk");
    }

    private static Package readMetadata(Properties metadata, String key, String expected) throws IOException {
        String name = metadata.getProperty(key + ".name", "");
        String hash = metadata.getProperty(key + ".sha256", "");
        String version = metadata.getProperty(key + ".version", "");
        try {
            int size = Integer.parseInt(metadata.getProperty(key + ".size", ""));
            int versionCode = Integer.parseInt(metadata.getProperty(key + ".versionCode", ""));
            if (name.equals(expected) && hash.matches("[0-9a-f]{64}") && !version.isEmpty()
                    && size > 0 && versionCode > 0) {
                return new Package(name, size, version, versionCode, hash);
            }
        } catch (NumberFormatException invalid) {
            throw new IOException("内置安装包的版本或大小无效", invalid);
        }
        throw new IOException("内置安装包清单无效");
    }

    byte[] verifiedBytes(Package source) throws IOException {
        byte[] data = new byte[source.size()];
        try (DataInputStream input = new DataInputStream(assets.open(ROOT + source.fileName()))) {
            input.readFully(data);
            if (input.read() != -1) throw new IOException("内置安装包大小校验失败");
        }
        if (!sha256(data).equals(source.sha256())) {
            throw new IOException("内置安装包 SHA-256 校验失败，请重新安装助手");
        }
        return data;
    }

    static String sha256(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder hex = new StringBuilder(64);
            for (byte value : digest) hex.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
