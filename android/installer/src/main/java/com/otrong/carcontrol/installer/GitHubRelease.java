package com.otrong.carcontrol.installer;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HttpsURLConnection;

final class GitHubRelease {
    private static final String REPO = "/zjs215/geely-band-control";
    private static final String MIRROR_BASE = "https://car.otrong.com";
    private static final String MIRROR_LATEST = MIRROR_BASE + "/latest.json";
    private static final String LATEST = "https://api.github.com/repos" + REPO + "/releases/latest";
    private static final String DOWNLOAD = "https://github.com" + REPO + "/releases/download/";
    private static final int MAX_ASSET_BYTES = 24 * 1024 * 1024;
    private static final int METADATA_TIMEOUT_SECONDS = 15;
    private static final int DOWNLOAD_TIMEOUT_SECONDS = 90;

    private static final int MAX_NOTES_CHARS = 4000;
    static final String INSTALLER_ASSET = "wrist-car-control-installer.apk";

    record Asset(String tag, String name, String url, int size, String digest) {}

    static Asset latest(InstallTarget target) throws IOException {
        return latestRelease().asset(target);
    }

    static Release latestRelease() throws IOException {
        try {
            return parse(new String(get(MIRROR_LATEST, 512 * 1024, METADATA_TIMEOUT_SECONDS), StandardCharsets.UTF_8));
        } catch (IOException mirrorUnavailable) {
            return parse(new String(get(LATEST, 512 * 1024, METADATA_TIMEOUT_SECONDS), StandardCharsets.UTF_8));
        }
    }

    static Asset select(String json, InstallTarget target) throws IOException {
        return parse(json).asset(target);
    }

    static Release parse(String json) throws IOException {
        try {
            JSONObject release = new JSONObject(json);
            String tag = release.getString("tag_name");
            if (release.optBoolean("draft") || release.optBoolean("prerelease")
                    || !tag.matches("v[0-9]{1,6}\\.[0-9]{1,6}\\.[0-9]{1,6}")) {
                throw new IOException("GitHub 暂无可用的稳定版");
            }
            String name = release.optString("name", "").trim();
            String body = formatNotes(release.optString("body", ""));
            return new Release(tag, name, body, release.getJSONArray("assets"));
        } catch (JSONException invalid) {
            throw new IOException("无法读取 GitHub 发布信息", invalid);
        }
    }

    static String formatNotes(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        String text = raw.replace("\r\n", "\n").replace('\r', '\n').trim();
        if (text.isEmpty()) return "";
        StringBuilder formatted = new StringBuilder(Math.min(text.length(), MAX_NOTES_CHARS));
        int blankLines = 0;
        for (String rawLine : text.split("\n", -1)) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                if (formatted.length() > 0 && blankLines == 0) {
                    formatted.append('\n');
                    blankLines = 1;
                }
                continue;
            }
            blankLines = 0;
            line = line.replaceAll("^#{1,6}\\s+", "");
            if (line.startsWith("- ") || line.startsWith("* ")) {
                line = "• " + line.substring(2).trim();
            }
            line = line.replaceAll("\\[([^\\]]+)\\]\\(([^)]+)\\)", "$1")
                    .replace("**", "")
                    .replace("__", "")
                    .replace("`", "");
            if (formatted.length() > 0) formatted.append('\n');
            if (formatted.length() + line.length() > MAX_NOTES_CHARS) {
                int remaining = Math.max(0, MAX_NOTES_CHARS - formatted.length() - 1);
                if (remaining > 0) formatted.append(line, 0, Math.min(line.length(), remaining));
                formatted.append('…');
                break;
            }
            formatted.append(line);
        }
        return formatted.toString().trim();
    }

    record Release(String tag, String name, String body, JSONArray assets) {
        Release(String tag, JSONArray assets) {
            this(tag, "", "", assets);
        }

        Asset asset(InstallTarget target) throws IOException {
            return assetByNames(target.asset, target.previousAsset);
        }

        Asset installerAsset() throws IOException {
            return assetByNames(INSTALLER_ASSET);
        }

        private Asset assetByNames(String... names) throws IOException {
            for (String name : names) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject item = assets.optJSONObject(i);
                    if (item == null || !name.equals(item.optString("name"))) continue;
                    String githubUrl = DOWNLOAD + tag + "/" + name;
                    String mirrorUrl = MIRROR_BASE + "/" + tag + "/" + name;
                    String actualUrl = item.optString("browser_download_url");
                    long size = item.optLong("size");
                    String digest = item.optString("digest").toLowerCase(Locale.ROOT);
                    if (size <= 0 || size > MAX_ASSET_BYTES || !"uploaded".equals(item.optString("state"))
                            || !(githubUrl.equals(actualUrl) || mirrorUrl.equals(actualUrl))
                            || !digest.matches("sha256:[0-9a-f]{64}")) {
                        throw new IOException("GitHub 发布包缺少有效的校验信息");
                    }
                    return new Asset(tag, name, actualUrl, (int) size, digest.substring(7));
                }
            }
            throw new IOException("GitHub 稳定版未提供此设备的安装包");
        }
    }

    static byte[] download(Asset asset) throws IOException {
        byte[] data = get(asset.url, asset.size, DOWNLOAD_TIMEOUT_SECONDS);
        if (data.length != asset.size || !BundledPackages.sha256(data).equals(asset.digest)) {
            throw new IOException("GitHub 安装包 SHA-256 或大小校验失败");
        }
        return data;
    }

    static int compareVersions(String first, String second) {
        String[] left = first.split("\\.");
        String[] right = second.split("\\.");
        if (left.length != 3 || right.length != 3) return 0;
        try {
            for (int i = 0; i < left.length; i++) {
                int difference = Integer.compare(Integer.parseInt(left[i]), Integer.parseInt(right[i]));
                if (difference != 0) return difference;
            }
        } catch (NumberFormatException malformed) {
            return 0;
        }
        return 0;
    }

    private static byte[] get(String source, int maxSize, int timeoutSeconds) throws IOException {
        URL url = new URL(source);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        for (int redirect = 0; redirect < 4; redirect++) {
            String host = url.getHost();
            if (!"https".equals(url.getProtocol()) || url.getUserInfo() != null
                    || (url.getPort() != -1 && url.getPort() != 443)
                    || !(host.equals("car.otrong.com")
                        || host.equals("api.github.com") || host.equals("github.com")
                        || host.equals("release-assets.githubusercontent.com")
                        || host.equals("objects.githubusercontent.com"))) {
                throw new IOException("GitHub 下载地址无效");
            }
            HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(20_000);
            connection.setRequestProperty("User-Agent", "Wrist-Car-Control-Installer");
            if (host.equals("api.github.com")) {
                connection.setRequestProperty("Accept", "application/vnd.github+json");
            }
            try {
                checkTime(deadline);
                int status = connection.getResponseCode();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new IOException("GitHub 下载地址缺失");
                    url = new URL(url, location);
                    continue;
                }
                if (status != 200) throw new IOException("GitHub 返回 HTTP " + status);
                try (InputStream input = connection.getInputStream()) {
                    return readBounded(input, maxSize, deadline);
                }
            } finally {
                connection.disconnect();
            }
        }
        throw new IOException("GitHub 下载跳转次数过多");
    }

    static byte[] readBounded(InputStream input, int maxSize, long deadline) throws IOException {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (int count; (count = input.read(buffer)) != -1;) {
            checkTime(deadline);
            if (count > maxSize - data.size()) throw new IOException("安装文件超过预期大小");
            data.write(buffer, 0, count);
        }
        return data.toByteArray();
    }

    private static void checkTime(long deadline) throws IOException {
        if (Thread.currentThread().isInterrupted() || System.nanoTime() - deadline >= 0) {
            throw new IOException("读取已停止或超时");
        }
    }
}
