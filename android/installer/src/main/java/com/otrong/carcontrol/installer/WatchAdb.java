package com.otrong.carcontrol.installer;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
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
    private static final String LIST_APP = "pm list packages --show-versioncode " + BundledPackages.APP_PACKAGE;
    private static final Pattern APP_LINE = Pattern.compile("^package:"
            + Pattern.quote(BundledPackages.APP_PACKAGE) + "\\s+versionCode:(\\d+)\\s*$", Pattern.MULTILINE);
    private final WatchIdentity identity;

    record Result(String model, boolean versionChecked) {}

    WatchAdb(Context context) throws Exception {
        identity = WatchIdentity.load(context);
        setApi(35);
        setTimeout(20, TimeUnit.SECONDS);
        setThrowOnUnauthorised(true);
    }

    @Override protected PrivateKey getPrivateKey() { return identity.key(); }
    @Override protected Certificate getCertificate() { return identity.certificate(); }
    @Override protected String getDeviceName() { return "Galaxy Wear Installer"; }

    Result install(String ip, int port, byte[] apk, int versionCode, BandProtocol.Progress progress)
            throws Exception {
        progress.report("正在连接手表的无线调试端口", -1);
        String model = connectWatch(ip, port);

        progress.report("正在发送手表 APK", 0);
        String response;
        try (AdbStream stream = openStream("exec:cmd package install -r -S " + apk.length)) {
            OutputStream output = stream.openOutputStream();
            for (int offset = 0; offset < apk.length;) {
                int length = Math.min(CHUNK_SIZE, apk.length - offset);
                output.write(apk, offset, length);
                offset += length;
                progress.report("正在发送手表 APK", offset * 100 / apk.length);
            }
            output.flush();
            progress.report("等待手表确认安装", -1);
            response = readReply(stream);
        }
        if (!Arrays.asList(response.replace("\r", "").split("\n")).contains("Success")) {
            throw new InstallFailure(describeInstallFailure(response));
        }
        try {
            return new Result(model, installedCode(command(LIST_APP)) == versionCode);
        } catch (IOException readbackUnavailable) {
            return new Result(model, false);
        }
    }

    AppUpdate.Installed readInstalled(String ip, int port) throws Exception {
        setTimeout(5, TimeUnit.SECONDS);
        connectWatch(ip, port);
        long code = installedCode(command(LIST_APP));
        if (code == 0) return null;
        String name = command("dumpsys package " + BundledPackages.APP_PACKAGE
                + " | grep -m 1 '^[[:space:]]*versionName='").trim();
        String version = name.startsWith("versionName=") ? name.substring("versionName=".length()).trim() : "";
        if (!version.matches("[0-9a-zA-Z.+_-]{1,64}")) version = "";
        return new AppUpdate.Installed(version, code);
    }

    private String connectWatch(String ip, int port) throws Exception {
        try {
            if (!connect(ip, port)) throw new InstallFailure("连接手表超时，请核对无线调试连接端口");
        } catch (AdbPairingRequiredException pairing) {
            throw new InstallFailure("请先在手表上完成无线调试配对", pairing);
        }
        String model = command("getprop ro.product.model").trim();
        String features = command("pm list features");
        if (!isWatch(model, features)) throw new InstallFailure("连接的设备未被识别为 Android 手表");
        return model;
    }

    static long installedCode(String packages) throws IOException {
        Matcher app = APP_LINE.matcher(packages);
        if (app.find()) {
            try {
                return Long.parseLong(app.group(1));
            } catch (NumberFormatException malformed) {
                throw new IOException("手表上的应用版本号无法识别", malformed);
            }
        }
        if (Arrays.stream(packages.split("\\r?\\n")).anyMatch(line -> !line.trim().isEmpty()
                && (!line.startsWith("package:") || line.startsWith("package:" + BundledPackages.APP_PACKAGE + " ")))) {
            throw new IOException("手表未返回可识别的应用列表");
        }
        return 0;
    }

    private String command(String command) throws Exception {
        try (AdbStream stream = openStream("shell:" + command)) {
            return readReply(stream);
        }
    }

    static boolean isWatch(String model, String featureList) {
        return model.equalsIgnoreCase("M2501W1") || model.equalsIgnoreCase("Xiaomi Watch 5")
                || Arrays.asList(featureList.replace("\r", "").split("\n")).contains(WATCH_FEATURE);
    }

    private static String readReply(AdbStream stream) throws IOException {
        ByteArrayOutputStream reply = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int length;
        while ((length = stream.read(buffer, 0, buffer.length)) != -1) {
            if (length > MAX_REPLY_SIZE - reply.size()) throw new InstallFailure("手表回复异常");
            reply.write(buffer, 0, length);
        }
        return new String(reply.toByteArray(), StandardCharsets.UTF_8);
    }

    static String describeInstallFailure(String response) {
        if (response.contains("UPDATE_INCOMPATIBLE")) return "安装失败，手表上已有不同签名的腕上车控";
        if (response.contains("VERSION_DOWNGRADE")) return "安装失败，手表上的腕上车控版本更高";
        if (response.contains("INSUFFICIENT_STORAGE")) return "安装失败，手表存储空间不足";
        if (response.contains("USER_RESTRICTED")) return "安装失败，请查看手表上的系统安装限制";
        return "手表未确认安装成功，请在手表上检查腕上车控的安装状态";
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
            throw new InstallFailure("请输入手表无线调试页面显示的有效 IP 地址", invalid);
        }
        byte[] bytes = address.getAddress();
        boolean uniqueLocalV6 = bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
        if (!address.isSiteLocalAddress() && !address.isLinkLocalAddress() && !uniqueLocalV6) {
            throw new InstallFailure("请使用同一可信 Wi-Fi 下手表的局域网 IP");
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
