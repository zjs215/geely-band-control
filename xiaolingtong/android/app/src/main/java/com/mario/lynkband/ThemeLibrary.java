package com.mario.lynkband;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.util.Base64;

import com.mario.lynkband.solar.ThemeArt;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.net.ssl.HttpsURLConnection;

final class ThemeLibrary {
    private static final String CNB_ROOT = PhoneWearReleases.CNB_RAW + "themes/";
    private static final String GITHUB_ROOT =
            "https://raw.githubusercontent.com/chashaobao/geely-band-control/contrib/xiaolingtong/xiaolingtong/themes/";
    private static final String MANIFEST = "theme.json";
    private static final int MANIFEST_LIMIT = 8 * 1024;
    private static final int ARCHIVE_LIMIT = 1024 * 1024;
    private static final String[] ROLES = {"phone-day", "watch-day", "watch-night", "vela-day", "vela-night"};
    private static final int[] LIMITS = {ThemeArt.WEBP_LIMIT, ThemeArt.WEBP_LIMIT, ThemeArt.WEBP_LIMIT,
            ThemeArt.PNG_LIMIT, ThemeArt.PNG_LIMIT};

    record Entry(String id, String title, int version, String[] hashes) {
        static Entry parse(JSONObject json) throws JSONException {
            String id = json.getString("id");
            if (!ThemeArt.themeId(json.opt("id")) || "sky".equals(id) || !ThemeArt.title(json.opt("title")))
                throw new JSONException("Invalid theme name");
            JSONObject entries = json.getJSONObject("assets");
            String[] hashes = new String[ROLES.length];
            for (int i = 0; i < hashes.length; i++) {
                if (!ThemeArt.hash(entries.opt(ROLES[i]))) throw new JSONException("Invalid theme checksum");
                hashes[i] = entries.getString(ROLES[i]);
            }
            Object rawVersion = json.opt("version");
            if (json.has("version") && (!(rawVersion instanceof Integer number) || number < 1))
                throw new JSONException("Invalid theme version");
            return new Entry(id, json.getString("title"), rawVersion == null ? 1 : (int) rawVersion, hashes);
        }

        boolean matches(Entry other) {
            return other != null && id.equals(other.id) && title.equals(other.title) && version == other.version
                    && Arrays.equals(hashes, other.hashes);
        }

        String versionLabel() { return "v" + version; }

        JSONObject json() throws JSONException {
            JSONObject assets = new JSONObject();
            for (int i = 0; i < ROLES.length; i++) assets.put(ROLES[i], hashes[i]);
            return new JSONObject().put("id", id).put("title", title).put("version", version).put("assets", assets);
        }
    }

    record SavedTheme(Entry entry, boolean downloaded) {
        String readFailure() {
            return downloaded ? "缓存读取失败，请打开在线主题重新下载" : "本地主题读取失败，请重新导入原主题包";
        }
    }

    record OnlineTheme(Entry entry, boolean update) {}

    static final class Pack {
        final Entry entry;
        final byte[][] images;
        final ThemeArt.Offer watch;
        final ThemeArt.Offer vela;
        final long velaDayCrc;
        final long velaNightCrc;

        Pack(Entry entry, byte[][] images) {
            this.entry = entry;
            this.images = images;
            String key = ThemeArt.digest((entry.id + ":" + entry.title + ":" + String.join(":", entry.hashes))
                    .getBytes(StandardCharsets.UTF_8));
            watch = new ThemeArt.Offer(key, entry.title, entry.hashes[1], entry.hashes[2], entry.id);
            vela = new ThemeArt.Offer(key, entry.title, entry.hashes[3], entry.hashes[4], entry.id);
            velaDayCrc = crc32(images[3]);
            velaNightCrc = crc32(images[4]);
        }

        private static long crc32(byte[] image) {
            java.util.zip.CRC32 crc = new java.util.zip.CRC32();
            crc.update(image);
            return crc.getValue();
        }
    }

    private final File activeFile;
    private final ThemeArt.Store store;
    private final File localDirectory;
    private final File downloadDirectory;
    private Pack active;
    private Bitmap preview;
    private boolean previewNight;

    ThemeLibrary(Context context) {
        activeFile = new File(context.getFilesDir(), "visual-theme.dat");
        store = new ThemeArt.Store(activeFile);
        localDirectory = new File(context.getFilesDir(), "local-themes");
        downloadDirectory = new File(context.getFilesDir(), "downloaded-themes");
        try {
            active = readStored(store);
            keepPreviousSelection();
        } catch (IOException | JSONException ignored) {
            // The default theme is available offline.
        }
    }

    private static Pack readStored(ThemeArt.Store store) throws IOException, JSONException {
        ThemeArt.Stored saved = store.read(LIMITS);
        Entry entry = Entry.parse(saved.info());
        validate(entry, saved.images());
        return new Pack(entry, saved.images());
    }

    private void keepPreviousSelection() throws IOException, JSONException {
        // Before 0.3.47 a downloaded theme only had the active copy.
        Entry entry = active.entry;
        if (!entry.matches(storedEntry(localDirectory, entry.id)) && storedEntry(downloadDirectory, entry.id) == null)
            cacheDownload(active);
    }

    private static ThemeArt.Store storeIn(File directory, String id) {
        return new ThemeArt.Store(new File(directory, id + ".dat"));
    }

    private static Entry storedEntry(File directory, String id) {
        try {
            Entry entry = Entry.parse(storeIn(directory, id).info());
            return id.equals(entry.id()) ? entry : null;
        } catch (IOException | JSONException missing) {
            return null;
        }
    }

    private static List<Entry> entriesIn(File directory) {
        List<Entry> entries = new ArrayList<>();
        String[] files = directory.list((folder, name) -> name.endsWith(".dat"));
        if (files == null) return entries;
        for (String name : files) {
            String id = name.substring(0, name.length() - 4);
            if (!ThemeArt.themeId(id)) continue;
            Entry entry = storedEntry(directory, id);
            if (entry != null) entries.add(entry);
        }
        entries.sort(Comparator.comparing(Entry::title).thenComparing(Entry::id));
        return entries;
    }

    synchronized List<SavedTheme> savedCatalog() {
        List<SavedTheme> choices = new ArrayList<>();
        List<Entry> imports = entriesIn(localDirectory);
        for (Entry entry : imports) choices.add(new SavedTheme(entry, false));
        for (Entry entry : entriesIn(downloadDirectory)) {
            if (imports.stream().noneMatch(entry::matches)) choices.add(new SavedTheme(entry, true));
        }
        return choices;
    }

    synchronized Entry downloadedEntry(String id) {
        return storedEntry(downloadDirectory, id);
    }

    synchronized List<OnlineTheme> availableOnline(List<Entry> catalog) {
        List<OnlineTheme> choices = new ArrayList<>();
        for (Entry entry : catalog) {
            Entry downloaded = validSavedEntry(downloadDirectory, entry.id());
            Entry imported = validSavedEntry(localDirectory, entry.id());
            if (isCurrentOrNewer(downloaded, entry) || isCurrentOrNewer(imported, entry)) continue;
            choices.add(new OnlineTheme(entry, downloaded != null || imported != null));
        }
        return choices;
    }

    private static Entry validSavedEntry(File directory, String id) {
        try {
            Entry entry = readStored(storeIn(directory, id)).entry;
            return id.equals(entry.id()) ? entry : null;
        } catch (IOException | JSONException missing) {
            return null;
        }
    }

    private static boolean isCurrentOrNewer(Entry local, Entry online) {
        return local != null && (local.version() > online.version() || online.matches(local));
    }

    synchronized Pack savedTheme(SavedTheme choice) throws IOException, JSONException {
        File directory = choice.downloaded() ? downloadDirectory : localDirectory;
        Pack pack = readStored(storeIn(directory, choice.entry().id()));
        if (!choice.entry().id().equals(pack.entry.id())) throw new IOException("Wrong saved theme ID");
        return pack;
    }

    synchronized void deleteSaved(SavedTheme choice) throws IOException, JSONException {
        Entry entry = choice.entry();
        File directory = choice.downloaded() ? downloadDirectory : localDirectory;
        File other = choice.downloaded() ? localDirectory : downloadDirectory;
        if (!entry.matches(storedEntry(directory, entry.id())))
            throw new IOException("Saved theme changed");
        if (isSelected(entry)) select(null);
        // Both stores can contain the exact same theme, which is shown as a single row.
        if (entry.matches(storedEntry(other, entry.id())))
            deleteStore(new File(other, entry.id() + ".dat"));
        deleteStore(new File(directory, entry.id() + ".dat"));
    }

    private static void deleteStore(File file) throws IOException {
        new ThemeArt.Store(file).delete();
        if (file.exists()) throw new IOException("Cannot delete saved theme");
    }

    synchronized void saveLocal(Pack pack) throws IOException, JSONException {
        saveTo(localDirectory, pack);
    }

    synchronized void cacheDownload(Pack pack) throws IOException, JSONException {
        saveTo(downloadDirectory, pack);
    }

    private static void saveTo(File directory, Pack pack) throws IOException, JSONException {
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot save themes");
        storeIn(directory, pack.entry.id()).write(pack.entry.json(), pack.images);
    }

    synchronized Pack cachedDownload(Entry entry) {
        if (!entry.matches(downloadedEntry(entry.id()))) return null;
        try {
            Pack pack = readStored(storeIn(downloadDirectory, entry.id()));
            return entry.matches(pack.entry) ? pack : null;
        } catch (IOException | JSONException missing) {
            return null;
        }
    }

    Pack onlineTheme(Entry entry) throws IOException, JSONException {
        Pack cached = cachedDownload(entry);
        if (cached != null) return cached;
        Pack fresh;
        try {
            fresh = download(CNB_ROOT, entry);
        } catch (IOException primary) {
            if (Thread.currentThread().isInterrupted()) throw primary;
            fresh = download(GITHUB_ROOT, entry);
        }
        if (Thread.currentThread().isInterrupted()) throw new IOException("Theme download interrupted");
        cacheDownload(fresh);
        return fresh;
    }

    Pack recoverLegacyTheme(String id) throws IOException, JSONException {
        Entry existing = downloadedEntry(id);
        if (existing != null) {
            Pack cached = cachedDownload(existing);
            if (cached != null) return cached;
        }
        for (Entry entry : catalog()) {
            if (entry.id().equals(id)) return onlineTheme(entry);
        }
        throw new IOException("Legacy theme missing from online catalog");
    }

    Entry selected() { return active == null ? null : active.entry; }

    boolean isSelected(Entry entry) { return entry.matches(selected()); }

    void select(Pack next) throws IOException, JSONException {
        if (next == null) deleteStore(activeFile);
        else store.write(next.entry.json(), next.images);
        active = next;
        preview = null;
    }

    Bitmap preview(boolean night) {
        if (active == null) return null;
        if (preview == null || previewNight != night) {
            byte[] image = active.images[night ? 2 : 0];
            preview = BitmapFactory.decodeByteArray(image, 0, image.length);
            previewNight = night;
        }
        return preview;
    }

    Object offer(boolean vela) throws JSONException {
        if (active == null) return JSONObject.NULL;
        if (!vela) return active.watch.json();
        return new JSONObject().put("key", active.vela.key()).put("title", active.vela.title())
                .put("scene", active.vela.scene()).put("dayCrc", active.velaDayCrc).put("nightCrc", active.velaNightCrc);
    }

    void chunk(JSONObject request, JSONObject out, boolean vela) throws JSONException {
        if (active == null) return;
        ThemeArt.Offer offer = vela ? active.vela : active.watch;
        Object part = request.opt("part");
        if (!offer.key().equals(request.opt("key")) || (!"day".equals(part) && !"night".equals(part))
                || !(request.opt("offset") instanceof Integer offset)) return;
        byte[] image = active.images[(vela ? 3 : 1) + ("night".equals(part) ? 1 : 0)];
        int size = vela ? ThemeArt.VELA_CHUNK : ThemeArt.WATCH_CHUNK;
        if (offset < 0 || offset >= image.length || offset % size != 0) return;
        int end = Math.min(offset + size, image.length);
        out.put("key", offer.key()).put("part", part).put("offset", offset).put("length", image.length)
                .put("chunk", Base64.encodeToString(Arrays.copyOfRange(image, offset, end), Base64.NO_WRAP));
    }

    static List<Entry> catalog() throws IOException, JSONException {
        try {
            List<Entry> primary = catalog(CNB_ROOT);
            if (!primary.isEmpty()) return primary;
        } catch (IOException | JSONException primary) {
            if (Thread.currentThread().isInterrupted()) throw primary;
        }
        return catalog(GITHUB_ROOT);
    }

    private static List<Entry> catalog(String root) throws IOException, JSONException {
        JSONObject catalog = new JSONObject(new String(fetch(root, "catalog.json", 64 * 1024), StandardCharsets.UTF_8));
        JSONArray entries = catalog.getJSONArray("themes");
        if (catalog.optInt("schema") != 1 || entries.length() > 64) throw new JSONException("Invalid theme catalog");
        List<Entry> results = new ArrayList<>();
        HashSet<String> ids = new HashSet<>();
        for (int i = 0; i < entries.length(); i++) {
            Entry entry = Entry.parse(entries.getJSONObject(i));
            if (!ids.add(entry.id)) throw new JSONException("Duplicate theme");
            results.add(entry);
        }
        return results;
    }

    private static Pack download(String root, Entry entry) throws IOException {
        byte[][] images = new byte[ROLES.length][];
        for (int i = 0; i < ROLES.length; i++) images[i] = fetch(root, entry.id + "/" + assetName(i), LIMITS[i]);
        validate(entry, images);
        return new Pack(entry, images);
    }

    static Pack readZip(InputStream input) throws IOException, JSONException {
        byte[] archive = readBytes(input, ARCHIVE_LIMIT);
        Map<String, byte[]> entries = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                String name = entry.getName();
                int limit = MANIFEST.equals(name) ? MANIFEST_LIMIT : 0;
                for (int i = 0; i < ROLES.length; i++) {
                    if (assetName(i).equals(name)) limit = LIMITS[i];
                }
                if (limit == 0 || entry.isDirectory() || entry.getSize() > limit || entries.containsKey(name))
                    throw new IOException("Invalid theme ZIP entry");
                entries.put(name, readBytes(zip, limit));
            }
        } catch (IllegalArgumentException malformed) {
            throw new IOException("Invalid theme ZIP", malformed);
        }
        if (entries.size() != ROLES.length + 1) throw new IOException("Incomplete theme ZIP");
        JSONObject manifest = new JSONObject(new String(entries.get(MANIFEST), StandardCharsets.UTF_8));
        if (manifest.optInt("schema") != 1) throw new JSONException("Unsupported theme ZIP version");
        Entry entry = Entry.parse(manifest);
        byte[][] images = new byte[ROLES.length][];
        for (int i = 0; i < ROLES.length; i++) images[i] = entries.get(assetName(i));
        validate(entry, images);
        for (int i = 0; i < images.length; i++) {
            byte[] image = images[i];
            if (i >= 3) {
                if (image.length < 45) throw new IOException("Invalid Vela PNG");
                ByteBuffer end = ByteBuffer.wrap(image, image.length - 12, 12);
                if (end.getInt() != 0 || end.getInt() != 0x49454e44 || end.getInt() != 0xae426082)
                    throw new IOException("Invalid Vela PNG ending");
            }
            // Reject a damaged payload even if its dimensions and declared checksum are correct.
            Bitmap decoded = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(image)),
                    (decoder, info, imageSource) -> decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE));
            decoded.recycle();
        }
        return new Pack(entry, images);
    }

    private static String assetName(int index) {
        return ROLES[index] + (index >= 3 ? ".png" : ".webp");
    }

    private static void validate(Entry entry, byte[][] images) throws IOException {
        for (int i = 0; i < ROLES.length; i++) {
            if (!ThemeArt.valid(images[i], entry.hashes[i], i >= 3)) throw new IOException("Invalid theme image");
        }
    }

    private static byte[] readBytes(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (int count; (count = input.read(buffer)) >= 0;) {
            if (Thread.currentThread().isInterrupted() || output.size() + count > limit)
                throw new IOException("Theme read interrupted or too large");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static byte[] fetch(String root, String path, int limit) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("Theme download interrupted");
        HttpsURLConnection connection = (HttpsURLConnection) new URL(root + path).openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(8000);
        try {
            if (connection.getResponseCode() != 200 || connection.getContentLength() > limit)
                throw new IOException("Theme download unavailable");
            try (InputStream input = connection.getInputStream()) {
                return readBytes(input, limit);
            }
        } finally {
            connection.disconnect();
        }
    }
}
