// Adapted for 小领通 from zjs215/geely-band-control (Apache-2.0).
package com.mario.lynkband;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.muntashirakon.adb.AbsAdbConnectionManager;
import io.github.muntashirakon.adb.AdbPairingRequiredException;
import io.github.muntashirakon.adb.AdbStream;

final class WatchAdb extends AbsAdbConnectionManager {
    private static final String WATCH_FEATURE = "feature:android.hardware.type.watch";
    private static final int CHUNK_SIZE = 32 * 1024;
    private static final int MAX_REPLY_SIZE = 64 * 1024;
    private static final String LIST_APP = "pm list packages --show-versioncode " + PhoneWearUpdates.WATCH_PACKAGE;
    private static final Pattern APP_LINE = Pattern.compile("^package:"
            + Pattern.quote(PhoneWearUpdates.WATCH_PACKAGE) + "\\s+versionCode:(\\d+)\\s*$", Pattern.MULTILINE);
    private final WatchIdentity identity;

    record Result(boolean versionChecked) {}

    WatchAdb(Context context) throws Exception {
        identity = WatchIdentity.load(context);
        setApi(35);
        setTimeout(20, TimeUnit.SECONDS);
        setThrowOnUnauthorised(true);
    }

    @Override protected PrivateKey getPrivateKey() { return identity.key(); }
    @Override protected Certificate getCertificate() { return identity.certificate(); }
    @Override protected String getDeviceName() { return "Xiaolingtong Watch Installer"; }

    interface Progress { void report(String stage, int percent); }

    Result installFirst(File apk, long versionCode, Progress progress) throws Exception {
        if (currentVersion() != 0) throw new IOException("手表已安装小领通，请使用蓝牙更新。");
        progress.report("正在首次安装 Watch APK", 0);
        String response;
        try (AdbStream stream = openStream("exec:cmd package install -R -S " + apk.length());
             FileInputStream input = new FileInputStream(apk)) {
            OutputStream output = stream.openOutputStream();
            byte[] buffer = new byte[CHUNK_SIZE];
            long sent = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
                sent += count;
                progress.report("正在发送 Watch APK", (int) (sent * 100 / apk.length()));
            }
            output.flush();
            progress.report("正在等待系统安装结果", -1);
            response = readReply(stream);
        }
        if (!Arrays.asList(response.replace("\r", "").split("\n")).contains("Success")) {
            throw new IOException(describeInstallFailure(response));
        }
        try {
            return new Result(currentVersion() == versionCode);
        } catch (Exception readbackUnavailable) {
            return new Result(false);
        }
    }

    long currentVersion() throws Exception {
        return versionFromQuery(command(LIST_APP + " && echo XLT_QUERY_OK"));
    }

    static long versionFromQuery(String reply) throws IOException {
        String output = reply.replace("\r", "").trim();
        int marker = output.lastIndexOf("XLT_QUERY_OK");
        if (marker < 0 || !output.endsWith("XLT_QUERY_OK") || (marker > 0 && output.charAt(marker - 1) != '\n'))
            throw new IOException("无法确认手表已安装的应用，请重新连接后再试。");
        return installedCode(output.substring(0, marker));
    }

    void connectWatch(String ip, int port) throws Exception {
        try {
            if (!connect(ip, port)) throw new IOException("连接手表超时，请核对无线调试连接端口");
        } catch (AdbPairingRequiredException pairing) {
            throw new IOException("请先在手表上完成无线调试配对", pairing);
        }
        String features = command("pm list features");
        if (!isWatch(command("getprop ro.build.characteristics").trim(), features)) throw new IOException("连接的设备未被识别为 Android 手表");
    }

    static long installedCode(String packages) throws IOException {
        for (String line : packages.split("\\r?\\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            Matcher app = APP_LINE.matcher(line);
            if (app.matches()) {
                try {
                    long version = Long.parseLong(app.group(1));
                    if (version > 0) return version;
                } catch (NumberFormatException malformed) {
                    throw new IOException("手表上的应用版本号无法识别", malformed);
                }
                throw new IOException("手表上的应用版本号无法识别");
            }
            if (!line.matches("package:[a-zA-Z0-9_.]+\\s+versionCode:[0-9]+"))
                throw new IOException("手表未返回可识别的应用列表");
        }
        return 0;
    }

    private String command(String command) throws Exception {
        try (AdbStream stream = openStream("shell:" + command)) {
            return readReply(stream);
        }
    }

    static boolean isWatch(String characteristics, String featureList) {
        return Arrays.asList(characteristics.split(",")).contains("watch")
                || Arrays.asList(featureList.replace("\r", "").split("\n")).contains(WATCH_FEATURE);
    }

    private static String readReply(AdbStream stream) throws IOException {
        ByteArrayOutputStream reply = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int length;
        while ((length = stream.read(buffer, 0, buffer.length)) != -1) {
            if (length > MAX_REPLY_SIZE - reply.size()) throw new IOException("手表回复异常");
            reply.write(buffer, 0, length);
        }
        return new String(reply.toByteArray(), StandardCharsets.UTF_8);
    }

    static String describeInstallFailure(String response) {
        if (response.contains("ALREADY_EXISTS")) return "手表已安装小领通，请使用蓝牙更新。";
        if (response.contains("UPDATE_INCOMPATIBLE")) return "安装失败，手表上已有不同签名的小领通";
        if (response.contains("VERSION_DOWNGRADE")) return "安装失败，手表上的小领通版本更高";
        if (response.contains("INSUFFICIENT_STORAGE")) return "安装失败，手表存储空间不足";
        if (response.contains("USER_RESTRICTED")) return "安装失败，请查看手表上的系统安装限制";
        return "手表未确认安装成功，请在手表上检查小领通的安装状态";
    }

    static String localIp(String text) throws IOException {
        String ip = text.trim();
        if (ip.startsWith("[") && ip.endsWith("]")) ip = ip.substring(1, ip.length() - 1);
        InetAddress address;
        try {
            if (ip.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) {
                byte[] ipv4 = new byte[4];
                String[] octets = ip.split("\\.");
                for (int index = 0; index < octets.length; index++) {
                    int value = Integer.parseInt(octets[index]);
                    if (value > 255) throw new UnknownHostException();
                    ipv4[index] = (byte) value;
                }
                address = InetAddress.getByAddress(ipv4);
            } else if (ip.contains(":") && ip.matches("[0-9a-fA-F:.]+")) {
                address = InetAddress.getByName(ip);
            } else {
                throw new UnknownHostException();
            }
        } catch (UnknownHostException invalid) {
            throw new IOException("请输入手表无线调试页面显示的有效 IP 地址", invalid);
        }
        byte[] bytes = address.getAddress();
        boolean uniqueLocalV6 = bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
        if (!address.isSiteLocalAddress() && !address.isLinkLocalAddress() && !uniqueLocalV6) {
            throw new IOException("请使用同一可信 Wi-Fi 下手表的局域网 IP");
        }
        return address.getHostAddress();
    }

    static int port(String text) {
        String port = text.trim();
        if (port.matches("[0-9]{1,5}")) {
            int number = Integer.parseInt(port);
            if (number > 0 && number <= 65535) return number;
        }
        throw new IllegalArgumentException("请输入 1 到 65535 之间的手表端口");
    }
}
