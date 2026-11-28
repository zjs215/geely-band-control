package com.mario.lynkband.watch;

import com.mario.lynkband.transport.WatchUpdateWire;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/** One bounded update request sequence on the open PhoneLink. */
final class WatchUpdateTransfer {
    private static final long TIMEOUT_MS = 180_000L;
    private static final String TAG = "WristLynkUpdate";

    interface Result { void complete(File apk, String error); }

    private final Context context;
    private final Handler main;
    private final PhoneLink link;
    private final Result result;
    private final MessageDigest digest = sha256();
    private final Runnable deadline = this::timeout;
    private PackageInfo installed;
    private boolean active;
    private boolean requested;
    private int offset;
    private int length;
    private String transfer;
    private String sha256;
    private File incoming;
    private File staged;
    private BufferedOutputStream output;

    WatchUpdateTransfer(Context context, Handler main, PhoneLink link, Result result) {
        this.context = context.getApplicationContext();
        this.main = main;
        this.link = link;
        this.result = result;
    }

    void start() {
        active = true;
        main.postDelayed(deadline, TIMEOUT_MS);
        try {
            installed = WatchUpdateApk.installed(context);
            linkIdle();
        } catch (PackageManager.NameNotFoundException | RuntimeException failure) {
            Log.w(TAG, "Cannot read installed Watch version", failure);
            finish("无法读取当前手表版本");
        }
    }

    void linkIdle() {
        if (!active || requested || !link.idleForUpdate()) return;
        requested = true;
        request(false);
    }

    private void request(boolean complete) {
        try {
            if (!link.sendUpdate(WatchUpdateWire.request(installed.getLongVersionCode(), offset, transfer, complete)))
                linkLost();
        } catch (JSONException invalid) {
            finish("无法生成手机更新请求");
        }
    }

    void reply(JSONObject reply) {
        if (!active) return;
        try {
            if (staged != null) {
                WatchUpdateWire.acknowledged(reply);
                finish(null);
                return;
            }
            WatchUpdateWire.Chunk chunk = WatchUpdateWire.chunk(reply, offset, transfer, length, sha256);
            if (transfer == null) {
                transfer = chunk.transfer();
                length = chunk.length();
                sha256 = chunk.sha256();
                File directory = WatchUpdateFiles.directory(context);
                if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create update cache");
                incoming = File.createTempFile("incoming-", ".apk", directory);
                output = new BufferedOutputStream(new FileOutputStream(incoming));
            }
            output.write(chunk.bytes());
            digest.update(chunk.bytes());
            offset += chunk.bytes().length;
            if (offset < length) {
                request(false);
                return;
            }
            output.close();
            output = null;
            if (!MessageDigest.isEqual(digest.digest(), decodeDigest(sha256))) {
                finish("更新包校验失败");
                return;
            }
            verifyArchive(incoming);
        } catch (WatchUpdateWire.InvalidUpdate invalid) {
            finish(invalid.getMessage());
        } catch (IOException | RuntimeException failure) {
            Log.w(TAG, "Cannot receive update chunk", failure);
            finish("无法保存更新包，请重新接收");
        }
    }

    private void verifyArchive(File file) {
        new Thread(() -> {
            String error = null;
            try {
                WatchUpdateApk.verify(context, installed, file);
            } catch (WatchUpdateWire.InvalidUpdate invalid) {
                error = invalid.getMessage();
            } catch (RuntimeException failure) {
                Log.w(TAG, "Cannot verify update archive", failure);
                error = "无法验证更新包，请重新接收";
            }
            String message = error;
            main.post(() -> {
                if (!active) return;
                if (message != null) {
                    finish(message);
                    return;
                }
                File ready = new File(file.getParentFile(), UUID.randomUUID() + ".apk");
                if (!file.renameTo(ready)) {
                    finish("无法保存已验证的更新包");
                    return;
                }
                incoming = null;
                staged = ready;
                request(true);
            });
        }, "watch-apk-receiver").start();
    }

    void linkLost() { if (active) finish("手机连接已断开，请重新接收更新"); }

    private void timeout() { finish("接收超时，请在手机上重新开始发送"); }

    private void finish(String error) {
        if (!active) return;
        cancel();
        result.complete(staged, error);
    }

    void cancel() {
        active = false;
        main.removeCallbacks(deadline);
        link.cancelUpdate();
        if (output != null) {
            try { output.close(); } catch (IOException ignored) {}
            output = null;
        }
        if (incoming != null) {
            incoming.delete();
            incoming = null;
        }
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private static byte[] decodeDigest(String hex) {
        byte[] bytes = new byte[32];
        for (int i = 0; i < bytes.length; i++) {
            int first = Character.digit(hex.charAt(2 * i), 16);
            int second = Character.digit(hex.charAt(2 * i + 1), 16);
            bytes[i] = (byte) ((first << 4) | second);
        }
        return bytes;
    }
}
