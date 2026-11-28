package com.mario.lynkband;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.util.Base64;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.mario.lynkband.solar.ThemeArt;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@RunWith(AndroidJUnit4.class)
public class LocalThemePackTest {
    private static final String[] ROLES = {"phone-day", "watch-day", "watch-night", "vela-day", "vela-night"};
    private final Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
    @Rule public final TemporaryFolder files = new TemporaryFolder(target.getCacheDir());

    private Context localContext() {
        return new ContextWrapper(target) {
            @Override public File getFilesDir() { return files.getRoot(); }
        };
    }

    @Test public void importedPackRemainsSelectableOfflineAndUsesExistingWearablePayloads() throws Exception {
        Map<String, byte[]> source = packageFiles("本地主题");
        ThemeLibrary.Pack imported = read(source);
        assertEquals(1, imported.entry.version());
        ThemeLibrary library = new ThemeLibrary(localContext());
        library.saveLocal(imported);
        library.select(imported);
        assertTrue(library.isSelected(imported.entry));

        library = new ThemeLibrary(localContext());
        assertEquals("本地主题", library.selected().title());
        assertEquals(Color.CYAN, library.preview(false).getPixel(0, 0));
        library.select(null);
        assertNull(library.selected());

        library = new ThemeLibrary(localContext());
        assertEquals(1, library.savedCatalog().size());
        library.select(library.savedTheme(library.savedCatalog().get(0)));
        assertTrue(library.isSelected(imported.entry));
        for (boolean vela : new boolean[]{false, true}) {
            JSONObject offer = (JSONObject) library.offer(vela);
            JSONObject chunk = new JSONObject();
            library.chunk(new JSONObject().put("key", offer.getString("key")).put("part", "night").put("offset", 0),
                    chunk, vela);
            assertArrayEquals(source.get(vela ? "vela-night.png" : "watch-night.webp"),
                    Base64.decode(chunk.getString("chunk"), Base64.NO_WRAP));
        }

        ThemeLibrary.Pack replacement = read(packageFiles("本地主题新版"));
        library.saveLocal(replacement);
        assertEquals(1, library.savedCatalog().size());
        assertEquals("本地主题新版", library.savedCatalog().get(0).entry().title());
        assertEquals("本地主题", library.selected().title());
    }

    @Test public void rejectsWrongDigestMissingFilesAndUnknownEntries() throws Exception {
        Map<String, byte[]> wrong = packageFiles("本地主题");
        wrong.put("watch-day.webp", wrong.get("watch-night.webp"));
        reject(wrong);

        Map<String, byte[]> incomplete = packageFiles("本地主题");
        incomplete.remove("vela-night.png");
        reject(incomplete);

        Map<String, byte[]> extra = packageFiles("本地主题");
        extra.put("extra.png", extra.get("vela-day.png"));
        reject(extra);
    }

    @Test public void rejectsOversizedAndUnsupportedImageEvenWithMatchingDigest() throws Exception {
        Map<String, byte[]> oversized = packageFiles("本地主题");
        oversized.put("vela-night.png", new byte[ThemeArt.PNG_LIMIT + 1]);
        manifest(oversized, "本地主题");
        reject(oversized);

        Map<String, byte[]> wrongFormat = packageFiles("本地主题");
        wrongFormat.put("watch-night.webp", wrongFormat.get("vela-night.png"));
        manifest(wrongFormat, "本地主题");
        reject(wrongFormat);

        Map<String, byte[]> trailing = packageFiles("本地主题");
        byte[] png = trailing.get("vela-night.png");
        trailing.put("vela-night.png", Arrays.copyOf(png, png.length + 1));
        manifest(trailing, "本地主题");
        reject(trailing);
    }

    @Test public void downloadedThemesSurviveRestartAndOnlyReuseMatchingVersions() throws Exception {
        ThemeLibrary.Pack first = read(withVersion(packageFiles("下载主题"), 1));
        ThemeLibrary.Pack newer = read(withVersion(packageFiles("下载主题"), 2));
        ThemeLibrary.Pack edited = read(withVersion(packageFiles("自定义主题"), 1));
        ThemeLibrary library = new ThemeLibrary(localContext());
        library.saveLocal(edited);
        library.cacheDownload(first);
        library.select(first);
        library.select(null);

        library = new ThemeLibrary(localContext());
        assertNull(library.selected());
        assertEquals(2, library.savedCatalog().size());
        assertEquals("自定义主题", library.savedCatalog().get(0).entry().title());
        assertTrue(library.savedCatalog().get(1).downloaded());
        assertEquals(1, library.onlineTheme(first.entry).entry.version()); // Never hits the network for this fixture.
        assertNull(library.cachedDownload(newer.entry));
        library.cacheDownload(newer);
        library.select(library.savedTheme(library.savedCatalog().get(1)));
        assertEquals(2, library.selected().version());
        assertEquals(2, library.downloadedEntry(newer.entry.id()).version());
        assertEquals("自定义主题", library.savedCatalog().get(0).entry().title());
    }

    @Test public void previousActiveDownloadRemainsAvailableAfterSwitchingAway() throws Exception {
        ThemeLibrary.Pack old = read(packageFiles("上次下载"));
        JSONObject legacy = old.entry.json();
        legacy.remove("version");
        new ThemeArt.Store(new File(files.getRoot(), "visual-theme.dat")).write(legacy, old.images);

        ThemeLibrary library = new ThemeLibrary(localContext());
        assertEquals(1, library.selected().version());
        library.select(null);
        assertNull(library.selected());
        ThemeLibrary.SavedTheme restored = library.savedCatalog().get(0);
        assertTrue(restored.downloaded());
        assertEquals(1, library.savedTheme(restored).entry.version());
    }

    @Test public void onlineChoicesHideCurrentAndOlderVersionsAndShowActualUpdates() throws Exception {
        ThemeLibrary.Pack first = read(packageFiles("新主题"));
        ThemeLibrary.Pack changed = read(packageFiles("新版名称"));
        ThemeLibrary.Pack newer = read(withVersion(packageFiles("新主题"), 2));
        ThemeLibrary library = new ThemeLibrary(localContext());
        assertEquals(List.of(new ThemeLibrary.OnlineTheme(first.entry, false)),
                library.availableOnline(List.of(first.entry)));

        library.cacheDownload(first);
        assertTrue(library.availableOnline(List.of(first.entry)).isEmpty());
        assertEquals(List.of(new ThemeLibrary.OnlineTheme(newer.entry, true)),
                library.availableOnline(List.of(newer.entry)));
        assertEquals(List.of(new ThemeLibrary.OnlineTheme(changed.entry, true)),
                library.availableOnline(List.of(changed.entry)));

        library.saveLocal(newer);
        assertTrue(library.availableOnline(List.of(first.entry, newer.entry)).isEmpty());
    }

    @Test public void deletingAnActiveThemeClearsItsCopiesAndMakesItDownloadableAgain() throws Exception {
        ThemeLibrary.Pack first = read(packageFiles("已下载主题"));
        ThemeLibrary library = new ThemeLibrary(localContext());
        library.cacheDownload(first);
        library.saveLocal(first);
        library.select(first);
        assertEquals(1, library.savedCatalog().size());
        library.deleteSaved(library.savedCatalog().get(0));

        library = new ThemeLibrary(localContext());
        assertNull(library.selected());
        assertTrue(library.savedCatalog().isEmpty());
        assertEquals(List.of(new ThemeLibrary.OnlineTheme(first.entry, false)),
                library.availableOnline(List.of(first.entry)));
    }

    @Test public void rejectsInvalidThemeVersion() throws Exception {
        reject(withVersion(packageFiles("版本无效"), 0));
    }

    private static Map<String, byte[]> withVersion(Map<String, byte[]> files, int version) throws JSONException {
        JSONObject manifest = new JSONObject(new String(files.get("theme.json"), StandardCharsets.UTF_8));
        files.put("theme.json", manifest.put("version", version).toString().getBytes(StandardCharsets.UTF_8));
        return files;
    }

    private static Map<String, byte[]> packageFiles(String title) throws Exception {
        Map<String, byte[]> result = new LinkedHashMap<>();
        for (String role : ROLES) {
            boolean png = role.startsWith("vela-");
            Bitmap source = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888);
            source.eraseColor(role.endsWith("day") ? Color.CYAN : Color.BLUE);
            try (ByteArrayOutputStream encoded = new ByteArrayOutputStream()) {
                assertTrue(source.compress(png ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.WEBP, 100, encoded));
                result.put(role + (png ? ".png" : ".webp"), encoded.toByteArray());
            } finally { source.recycle(); }
        }
        manifest(result, title);
        return result;
    }

    private static void manifest(Map<String, byte[]> files, String title) throws JSONException {
        JSONObject digests = new JSONObject();
        for (String role : ROLES) {
            String name = role + (role.startsWith("vela-") ? ".png" : ".webp");
            digests.put(role, ThemeArt.digest(files.get(name)));
        }
        files.put("theme.json", new JSONObject().put("schema", 1).put("id", "local-test").put("title", title)
                .put("assets", digests).toString().getBytes(StandardCharsets.UTF_8));
    }

    private static ThemeLibrary.Pack read(Map<String, byte[]> files) throws IOException, JSONException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(file.getKey()));
                zip.write(file.getValue());
                zip.closeEntry();
            }
        }
        try (ByteArrayInputStream input = new ByteArrayInputStream(out.toByteArray())) {
            return ThemeLibrary.readZip(input);
        }
    }

    private static void reject(Map<String, byte[]> files) throws Exception {
        try {
            read(files);
            fail("Invalid package was accepted");
        } catch (IOException | JSONException expected) {
            // All invalid package input is rejected before persisting the library.
        }
    }
}
