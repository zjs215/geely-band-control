package com.mario.lynkband;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;

final class PhoneWearReleases {
    static final long MAX_WATCH_APK = com.mario.lynkband.transport.WatchUpdateWire.MAX_APK_BYTES;
    static final long MAX_RPK = 32L * 1024 * 1024;
    static final String WATCH_APK = "lynk-watch.apk";
    static final String PHONE_APK = "lynk-companion-phone.apk";
    static final String CNB_RAW = "https://cnb.cool/sidabakesi/xiaolingtong/-/git/raw/main/";
    private static final String CNB_DOWNLOADS = CNB_RAW + "downloads/";
    private static final String REPO = "/chashaobao/geely-band-control/releases";
    private static final String WEB = "https://github.com" + REPO;
    private static final URI API = URI.create("https://api.github.com/repos" + REPO + "?per_page=30");
    private static final String TAG_EXPRESSION = "xiaolingtong-v(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})";
    private static final Pattern TAG = Pattern.compile(TAG_EXPRESSION);
    private static final Pattern EXPANDED = Pattern.compile("\"(?:https://github[.]com)?" + REPO + "/expanded_assets/(" + TAG_EXPRESSION + ")\"");
    private static final Pattern ASSET_LINK = Pattern.compile("href=\"(?:https://github[.]com)?" + REPO + "/download/(" + TAG_EXPRESSION + ")/([A-Za-z0-9._-]+)\"");
    private static final Pattern PROFILE_RPK = Pattern.compile("lynk-band-(pill|standard)-[0-9]+\\.[0-9]+\\.[0-9]+\\.rpk");
    private static final Pattern SHA = Pattern.compile("[0-9a-fA-F]{64}");
    private static final Pattern SUM_LINE = Pattern.compile("([0-9a-fA-F]{64})[ \\t]+\\*?([A-Za-z0-9._-]+)");
    private static final String SUMS = "SHA256SUMS.txt";
    private static final long MAX_METADATA = 2L * 1024 * 1024;
    private static final long MAX_SUMS = 64L * 1024;

    record Release(String tag, List<Asset> assets, Source source) {
        String version() { return tag.substring("xiaolingtong-v".length()); }
    }

    enum Source { CNB, GitHub }

    record Asset(String name, long size, String digest) {
        boolean isWatch() { return WATCH_APK.equals(name); }
        boolean isPhone() { return PHONE_APK.equals(name); }
        boolean isRpk() { return "lynk-band.rpk".equals(name) || PROFILE_RPK.matcher(name).matches(); }
    }

    interface Check { void run() throws IOException; }

    static List<Release> list(Check check) throws IOException {
        try {
            List<Release> releases = parseCnb(text(URI.create(CNB_DOWNLOADS + "releases.json"),
                    MAX_METADATA, false, 20_000, check));
            if (!releases.isEmpty()) return releases;
        } catch (IOException | JSONException ignored) {
            check.run();
        }
        return listGithub(check);
    }

    private static List<Release> listGithub(Check check) throws IOException {
        try {
            List<Release> releases = parseApi(text(API, MAX_METADATA, false, 20_000, check));
            if (!releases.isEmpty()) return releases;
        } catch (IOException | JSONException ignored) {
            // Public API rate limits can be shared by many users behind one IP.
            check.run();
        }
        return parseWebReleases(text(URI.create(WEB), MAX_METADATA, false, 20_000, check));
    }

    static List<Asset> assets(Release release, Check check) throws IOException {
        if (release.assets() != null) return release.assets();
        return parseWebAssets(release.tag(), text(URI.create(WEB + "/expanded_assets/" + release.tag()),
                MAX_METADATA, false, 20_000, check));
    }

    static void download(Release release, Asset asset, List<Asset> assets, File file,
                         Check check, LongConsumer progress) throws IOException {
        String expected = asset.digest();
        if (expected == null && assets.stream().anyMatch(item -> SUMS.equals(item.name()))) {
            expected = checksum(text(downloadUrl(release.tag(), SUMS), MAX_SUMS, true, 20_000, check), asset.name());
            if (expected == null) throw new IOException("发布校验文件缺少此安装包的 SHA-256。");
        }
        if (expected == null) throw new IOException("此发布未提供安装包的 SHA-256，无法验证下载。");
        if (release.source() == Source.CNB) {
            try {
                downloadFile(cnbDownloadUrl(release.tag(), asset.name()), asset, expected, file, false, check, progress);
                return;
            } catch (IOException cnbFailure) {
                check.run();
                progress.accept(0);
                // Try the same release and verify it against the same checksum.
                try {
                    downloadFile(downloadUrl(release.tag(), asset.name()), asset, expected, file, true, check, progress);
                    return;
                } catch (IOException githubFailure) {
                    githubFailure.addSuppressed(cnbFailure);
                    throw githubFailure;
                }
            }
        }
        downloadFile(downloadUrl(release.tag(), asset.name()), asset, expected, file, true, check, progress);
    }

    private static void downloadFile(URI url, Asset asset, String expected, File file, boolean allowCdn,
                                     Check check, LongConsumer progress) throws IOException {
        long limit = sizeLimit(asset.name());
        MessageDigest sha256 = sha256();
        try (OutputStream out = new FileOutputStream(file)) {
            long size = fetch(url, out, limit, asset.size(), allowCdn, 120_000, check, progress, sha256);
            if (size == 0 || !expected.equalsIgnoreCase(hex(sha256.digest()))) {
                throw new IOException("下载文件为空或 SHA-256 校验不一致。");
            }
        }
    }

    static List<Release> parseCnb(String source) throws JSONException {
        JSONObject index = new JSONObject(source);
        JSONArray entries = index.getJSONArray("releases");
        if (!Integer.valueOf(1).equals(index.opt("schema")) || entries.length() > 100)
            throw new JSONException("Invalid CNB release index");
        Map<String, Release> releases = new LinkedHashMap<>();
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.getJSONObject(i);
            String tag = entry.getString("tag");
            JSONArray values = entry.getJSONArray("assets");
            if (!TAG.matcher(tag).matches() || values.length() != 4 || releases.containsKey(tag))
                throw new JSONException("Invalid CNB release");
            Map<String, Asset> assets = new LinkedHashMap<>();
            for (int j = 0; j < values.length(); j++) {
                JSONObject value = values.getJSONObject(j);
                String name = value.getString("name");
                String digest = value.getString("sha256");
                long size = value.opt("size") instanceof Integer number ? number : -1;
                if ((!PHONE_APK.equals(name) && !WATCH_APK.equals(name) && !PROFILE_RPK.matcher(name).matches())
                        || size <= 0 || size > sizeLimit(name) || !SHA.matcher(digest).matches()
                        || assets.putIfAbsent(name, new Asset(name, size, digest)) != null)
                    throw new JSONException("Invalid CNB asset");
            }
            if (!assets.containsKey(PHONE_APK) || !assets.containsKey(WATCH_APK)
                    || assets.keySet().stream().noneMatch(name -> name.startsWith("lynk-band-pill-"))
                    || assets.keySet().stream().noneMatch(name -> name.startsWith("lynk-band-standard-")))
                throw new JSONException("Missing CNB installation packages");
            releases.put(tag, new Release(tag, new ArrayList<>(assets.values()), Source.CNB));
        }
        return sorted(new ArrayList<>(releases.values()));
    }

    static List<Release> parseApi(String source) throws JSONException {
        JSONArray entries = new JSONArray(source);
        List<Release> releases = new ArrayList<>();
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.optJSONObject(i);
            if (entry == null || entry.optBoolean("draft") || entry.optBoolean("prerelease")) continue;
            String tag = entry.optString("tag_name");
            if (!TAG.matcher(tag).matches()) continue;
            JSONArray values = entry.optJSONArray("assets");
            if (values == null) continue;
            Map<String, Asset> assets = new LinkedHashMap<>();
            for (int j = 0; j < values.length(); j++) {
                JSONObject value = values.optJSONObject(j);
                if (value == null || !"uploaded".equals(value.optString("state"))) continue;
                String name = value.optString("name");
                if (!allowedAsset(name) || !downloadUrl(tag, name).toString().equals(value.optString("browser_download_url"))) continue;
                long size = value.optLong("size", -1);
                if (size <= 0 || size > sizeLimit(name)) continue;
                String digest = value.optString("digest");
                String sha = digest.startsWith("sha256:") && SHA.matcher(digest.substring(7)).matches()
                        ? digest.substring(7) : null;
                assets.putIfAbsent(name, new Asset(name, size, sha));
            }
            if (assets.values().stream().anyMatch(asset -> asset.isPhone() || asset.isWatch() || asset.isRpk())) {
                releases.add(new Release(tag, new ArrayList<>(assets.values()), Source.GitHub));
            }
        }
        return sorted(releases);
    }

    static List<Release> parseWebReleases(String source) {
        Map<String, Release> releases = new LinkedHashMap<>();
        Matcher matches = EXPANDED.matcher(source);
        while (matches.find()) {
            String tag = matches.group(1);
            releases.putIfAbsent(tag, new Release(tag, null, Source.GitHub));
        }
        return sorted(new ArrayList<>(releases.values()));
    }

    static List<Asset> parseWebAssets(String tag, String source) {
        Map<String, Asset> assets = new LinkedHashMap<>();
        Matcher matches = ASSET_LINK.matcher(source);
        while (matches.find()) {
            String name = matches.group(5);
            if (tag.equals(matches.group(1)) && allowedAsset(name)) assets.putIfAbsent(name, new Asset(name, -1, null));
        }
        return new ArrayList<>(assets.values());
    }

    private static List<Release> sorted(List<Release> releases) {
        releases.sort((a, b) -> compareVersions(b.version(), a.version()));
        return releases;
    }

    static int compareVersions(String first, String second) {
        String[] left = first.split("\\.");
        String[] right = second.split("\\.");
        for (int i = 0; i < 3; i++) {
            int compared = Integer.compare(Integer.parseInt(left[i]), Integer.parseInt(right[i]));
            if (compared != 0) return compared;
        }
        return 0;
    }

    static String checksum(String document, String name) throws IOException {
        String match = null;
        for (String line : document.split("\\R")) {
            Matcher entry = SUM_LINE.matcher(line.trim());
            if (!entry.matches() || !name.equals(entry.group(2))) continue;
            String found = entry.group(1);
            if (match != null && !match.equalsIgnoreCase(found)) throw new IOException("发布校验文件包含冲突的 SHA-256。");
            match = found;
        }
        return match;
    }

    static URI downloadUrl(String tag, String name) {
        if (!TAG.matcher(tag).matches() || !allowedAsset(name)) throw new IllegalArgumentException("Invalid release asset");
        return URI.create(WEB + "/download/" + tag + "/" + name);
    }

    static URI cnbDownloadUrl(String tag, String name) {
        if (!TAG.matcher(tag).matches() || !allowedAsset(name)) throw new IllegalArgumentException("Invalid release asset");
        return URI.create(CNB_DOWNLOADS + tag + "/" + name);
    }

    private static boolean allowedAsset(String name) {
        Asset asset = new Asset(name, -1, null);
        return asset.isPhone() || asset.isWatch() || asset.isRpk() || SUMS.equals(name);
    }

    private static long sizeLimit(String name) {
        if (SUMS.equals(name)) return MAX_SUMS;
        return WATCH_APK.equals(name) ? MAX_WATCH_APK : MAX_RPK;
    }

    static boolean allowedCdn(URI uri) {
        return "https".equals(uri.getScheme()) && "release-assets.githubusercontent.com".equals(uri.getHost())
                && uri.getPort() == -1 && uri.getRawUserInfo() == null && uri.getRawFragment() == null
                && uri.getRawPath() != null && uri.getRawPath().startsWith("/github-production-release-asset/");
    }

    private static String text(URI url, long limit, boolean redirect, int timeout, Check check) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        fetch(url, buffer, limit, -1, redirect, timeout, check, done -> {}, null);
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    private static long fetch(URI url, OutputStream out, long limit, long expected, boolean allowCdn,
                              int timeout, Check check, LongConsumer progress, MessageDigest hash) throws IOException {
        long deadline = System.nanoTime() + timeout * 1_000_000L;
        Check timed = () -> {
            check.run();
            if (System.nanoTime() - deadline >= 0) throw new IOException("连接更新服务器超时，请重试。");
        };
        for (int redirects = 0; redirects <= 2; redirects++) {
            timed.run();
            HttpsURLConnection connection = (HttpsURLConnection) url.toURL().openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(8_000);
            connection.setReadTimeout(8_000);
            connection.setRequestProperty("User-Agent", "Lynk-Wear-Updates");
            connection.setRequestProperty("Accept-Encoding", "identity");
            if ("api.github.com".equals(url.getHost())) connection.setRequestProperty("Accept", "application/vnd.github+json");
            try {
                int status = connection.getResponseCode();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String location = connection.getHeaderField("Location");
                    URI redirect;
                    try { redirect = location == null ? null : url.resolve(new URI(location)); }
                    catch (IllegalArgumentException | URISyntaxException invalid) { throw new IOException("发布下载地址无效。"); }
                    if (!allowCdn || redirect == null || !allowedCdn(redirect)) throw new IOException("发布下载地址不受信任。");
                    url = redirect;
                    continue;
                }
                if (status != 200) throw new IOException("更新服务器返回 HTTP " + status + "。");
                long length = connection.getContentLengthLong();
                if (length > limit || (length >= 0 && expected >= 0 && length != expected)) {
                    throw new IOException("下载文件大小与发布信息不符或超过限制。");
                }
                try (InputStream in = connection.getInputStream()) {
                    return copy(in, out, limit, expected >= 0 ? expected : length, timed, progress, hash);
                }
            } finally {
                connection.disconnect();
            }
        }
        throw new IOException("发布下载重定向次数过多。");
    }

    static long copy(InputStream in, OutputStream out, long limit, long expected, Check check,
                     LongConsumer progress, MessageDigest hash) throws IOException {
        byte[] buffer = new byte[32 * 1024];
        long total = 0;
        while (true) {
            check.run();
            int bytes = in.read(buffer);
            if (bytes < 0) break;
            total += bytes;
            if (total > limit || (expected >= 0 && total > expected)) throw new IOException("文件大小超过限制。");
            if (hash != null) hash.update(buffer, 0, bytes);
            out.write(buffer, 0, bytes);
            progress.accept(total);
        }
        check.run();
        if (expected >= 0 && total != expected) throw new IOException("文件传输未完成。");
        return total;
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException(unavailable); }
    }

    private static String hex(byte[] bytes) {
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            hex.append(Character.forDigit((value & 0xff) >>> 4, 16));
            hex.append(Character.forDigit(value & 0xf, 16));
        }
        return hex.toString();
    }

    private PhoneWearReleases() {}
}
