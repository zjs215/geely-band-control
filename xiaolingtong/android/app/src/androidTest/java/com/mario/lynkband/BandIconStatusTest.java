package com.mario.lynkband;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.FutureTask;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class BandIconStatusTest {
    @Test public void onlyPairedStatusWithMatchingKeyGetsArtwork() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        var work = new FutureTask<Void>(() -> {
            Context resources = instrumentation.getContext();
            FrameLayout live = named(new FrameLayout(resources), "rl_widget_cal_state");
            var vehicle = named(new TextView(resources), "widget_name");
            vehicle.setText("Test vehicle");
            live.addView(vehicle);
            var time = named(new TextView(resources), "time");
            time.setText("11-26 10:10");
            live.addView(time);
            var condition = named(new TextView(resources), "condition_txt");
            condition.setText("已驻车");
            live.addView(condition);
            ImageView lock = addGlyph(live, "fr_unlock", "unlock", Color.GREEN);
            addGlyph(live, "fr_window", "window", Color.BLUE);
            var phone = new FixturePhone(instrumentation.getTargetContext(), live);
            try {
                var request = new JSONObject().put("v", 1).put("id", "icon-status").put("type", "status")
                        .put("selectedActions", true);
                JSONArray legacy = phone.status(request);
                assertEquals(2, legacy.length());
                assertNoIcons(legacy);
                String key = legacy.getJSONObject(0).getString("key");
                request.put("bandIconKey", key);
                assertNoIcons(phone.status(request));
                phone.paired = true;
                JSONArray withIcon = phone.status(request);
                String png = withIcon.getJSONObject(0).getString("bandIconPng");
                assertTrue(png.startsWith("iVBORw0KGgo") && png.length() <= 2048);
                assertFalse(withIcon.getJSONObject(1).has("bandIconPng"));
                assertFalse(withIcon.getJSONObject(0).has("icon"));
                assertEquals(png, phone.status(request).getJSONObject(0).getString("bandIconPng"));
                request.put("bandPhoto", true).put("carPhotoId", "").put("photoOffset", 0).put("photoTransferId", "");
                assertNoIcons(phone.status(request));
                request.remove("bandPhoto");
                request.put("type", "prepare");
                assertNoIcons(phone.status(request));
                request.put("type", "status").put("bandIconKey", 123);
                assertNoIcons(phone.status(request));
                request.put("bandIconKey", key);
                lock.setImageDrawable(new ColorDrawable(Color.RED));
                JSONArray changed = phone.status(request);
                assertNoIcons(changed);
                request.put("bandIconKey", changed.getJSONObject(0).getString("key"));
                assertNotEquals(png, phone.status(request).getJSONObject(0).getString("bandIconPng"));
                lock.setVisibility(View.GONE);
                JSONArray missing = phone.status(request);
                request.put("bandIconKey", missing.getJSONObject(0).getString("key"));
                assertEquals("", phone.status(request).getJSONObject(0).getString("bandIconPng"));
            } finally { phone.disarm(); }
            return null;
        });
        instrumentation.runOnMainSync(work);
        work.get();
    }

    @Test public void localWatchIconsSkipEncodingForEverySnapshotAndKeepVisualMetadata() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        var work = new FutureTask<Void>(() -> {
            Context context = instrumentation.getContext();
            FrameLayout live = named(new FrameLayout(context), "rl_widget_cal_state");
            var vehicle = named(new TextView(context), "widget_name");
            vehicle.setText("Read-only vehicle");
            live.addView(vehicle);
            var glyph = addGlyph(live, "fr_window", "window", Color.BLUE);
            var icon = new ColorDrawable(Color.BLUE) {
                int artworkDraws;
                @Override public void draw(android.graphics.Canvas canvas) {
                    // Slot identity and state matching draw at 48px. The encoder draws this icon at 96px.
                    if (canvas.getWidth() == 96) artworkDraws++;
                    super.draw(canvas);
                }
            };
            glyph.setImageDrawable(icon);
            var phone = new FixturePhone(instrumentation.getTargetContext(), live);
            phone.paired = true;
            try {
                var request = new JSONObject().put("v", 1).put("id", "watch-icons").put("visualActions", true)
                        .put("hdActionIcons", true).put("localWatchIcons", true);
                for (String type : new String[]{"status", "refresh", "prepare", "execute"}) {
                    var actions = phone.status("watch:fixture", request.put("type", type)).getJSONArray("actions");
                    assertEquals(1, actions.length());
                    var item = actions.getJSONObject(0);
                    for (String field : new String[]{"slot", "key", "label", "category", "displayLabel", "controlName", "controlState"})
                        assertTrue(field, item.has(field));
                    assertFalse(item.getBoolean("enabled"));
                    assertFalse(item.has("icon"));
                    assertFalse(item.has("bandIconPng"));
                }
                assertEquals(0, icon.artworkDraws);

                request.put("type", "status");
                for (Object legacyFlag : new Object[]{null, false, "true"}) {
                    request.put("localWatchIcons", legacyFlag);
                    var item = phone.status("watch:fixture", request).getJSONArray("actions").getJSONObject(0);
                    assertTrue(item.getString("icon").startsWith("iVBORw0KGgo"));
                }
                assertTrue(icon.artworkDraws > 0);
                var warm = phone.status("watch:fixture", request.put("localWatchIcons", true)).getJSONArray("actions").getJSONObject(0);
                assertFalse(warm.has("icon"));
                request.put("selectedActions", true).put("bandIconKey", warm.getString("key"));
                assertTrue(phone.status(request).getJSONObject(0).getString("bandIconPng").startsWith("iVBORw0KGgo"));
            } finally { phone.disarm(); }
            return null;
        });
        instrumentation.runOnMainSync(work);
        work.get();
    }

    @Test public void themeAndPhoneSolarRelayBetweenPairedWatchAndBand() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        var task = new FutureTask<Void>(() -> {
            Context context = instrumentation.getTargetContext();
            var prefs = PhoneLandscape.preferences(context);
            java.util.Map<String, ?> previous = prefs.getAll();
            try {
                prefs.edit().clear().putBoolean("manual", true).putInt("riseMinute", 389).putInt("setMinute", 1031).apply();
                var phone = new FixturePhone(context, null);
                phone.paired = true;
                var watch = new JSONObject().put("v", 1).put("id", "watch-theme").put("type", "status")
                        .put("watchSolar", true).put("landscapeSync", new JSONObject().put("scene", "RIDGE")
                                .put("revision", 10L).put("fromPhone", false));
                var watchReply = phone.status("watch:fixture", watch);
                assertEquals("RIDGE", watchReply.getJSONObject("landscapeSync").getString("scene"));
                assertEquals("日出 06:29 · 日落 17:11", watchReply.getString("solarInfo"));
                var band = new JSONObject().put("v", 1).put("id", "band-theme").put("type", "status").put("bandTheme", true);
                var bandReply = phone.status("band:fixture", band);
                assertEquals("RIDGE", bandReply.getString("theme"));
                assertEquals(watchReply.getString("solarInfo"), bandReply.getString("solarInfo"));
                assertEquals(watchReply.getBoolean("themeNight"), bandReply.getBoolean("themeNight"));
                band.put("bandThemeChoice", "ALPINE");
                assertEquals("ALPINE", phone.status("band:fixture", band).getString("theme"));
                assertEquals("ALPINE", phone.status("watch:fixture", watch).getJSONObject("landscapeSync").getString("scene"));
                band.remove("bandThemeChoice");
                long base = phone.status("band:fixture", band.put("bandThemePush", true)).getLong("bandThemeRevision");
                phone.selectPhoneLandscape(PhoneLandscape.COAST);
                JSONObject pushed = new JSONObject(phone.themeMessage);
                assertEquals("bandThemeChanged", pushed.getString("type"));
                assertEquals("COAST", pushed.getString("theme"));
                assertTrue(pushed.getLong("bandThemeRevision") > base);
                var conflict = phone.status("band:fixture", band.put("bandThemeChoice", "SKY").put("bandThemeBaseRevision", base));
                assertTrue(conflict.getBoolean("bandThemeConflict"));
                assertEquals("COAST", conflict.getString("theme"));
                phone.paired = false;
                assertFalse(phone.status("band:fixture", band.put("bandThemeChoice", "CITY")).has("theme"));
                assertEquals("COAST", PhoneLandscape.selected(prefs).name());
            } finally {
                var restore = prefs.edit().clear();
                previous.forEach((key, value) -> {
                    if (value instanceof String text) restore.putString(key, text);
                    else if (value instanceof Boolean flag) restore.putBoolean(key, flag);
                    else if (value instanceof Integer number) restore.putInt(key, number);
                    else if (value instanceof Long number) restore.putLong(key, number);
                });
                restore.apply();
            }
            return null;
        });
        instrumentation.runOnMainSync(task);
        task.get();
    }

    private static void assertNoIcons(JSONArray list) throws Exception {
        for (int i = 0; i < list.length(); i++) assertFalse(list.getJSONObject(i).has("bandIconPng"));
    }

    private static <V extends View> V named(V view, String name) {
        int id = view.getResources().getIdentifier(name, "id", view.getContext().getPackageName());
        assertNotEquals(name, 0, id);
        view.setId(id);
        return view;
    }

    private static ImageView addGlyph(FrameLayout root, String frameName, String glyphName, int color) {
        Context context = root.getContext();
        var frame = named(new FrameLayout(context), frameName);
        var glyph = named(new ImageView(context), glyphName);
        glyph.setImageDrawable(new ColorDrawable(color));
        glyph.setOnClickListener(view -> fail("Status must never click a widget button"));
        frame.addView(glyph);
        root.addView(frame);
        return glyph;
    }

    private static final class FixturePhone extends WristApp {
        boolean paired;
        boolean unavailableCard;
        String themeMessage;
        @Override void sendBandTheme(String node, String message) {
            assertEquals("fixture", node);
            themeMessage = message;
        }

        FixturePhone(Context context, View root) {
            attachBaseContext(context);
            onCreate();
            card = new LynkWidget(this, new Handler(getMainLooper()), this::cardChanged) {
                @Override View content() {
                    if (unavailableCard) throw new IllegalStateException("provider replaced its view");
                    return root;
                }
                @Override String identity() { return "fixture"; }
                @Override boolean bound() { return true; }
                @Override VehiclePhoto.Image vehiclePhoto(VehiclePhoto.Target target) { return null; }
            };
            service = true;
        }

        @Override boolean permitsWearable(String node) {
            return paired && ("band:fixture".equals(node) || "watch:fixture".equals(node));
        }

        JSONArray status(JSONObject request) throws Exception {
            return status("band:fixture", request).getJSONArray("actions");
        }

        JSONObject status(String node, JSONObject request) throws Exception {
            String response = reply(node, request.toString().getBytes(StandardCharsets.UTF_8));
            assertNotNull(response);
            assertTrue(response.getBytes(StandardCharsets.UTF_8).length <= 4096);
            return new JSONObject(response);
        }
    }

    @Test public void velaKeepsThemeAndReadOnlyStatusWhileProviderReplacesItsView() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        var task = new FutureTask<Void>(() -> {
            var phone = new FixturePhone(instrumentation.getTargetContext(), null);
            phone.paired = true;
            phone.unavailableCard = true;
            try {
                var req = new JSONObject().put("v",1).put("id","read").put("type","status").put("bandTheme",true);
                var status = phone.status("band:fixture", req);
                assertTrue(status.has("theme"));
                assertFalse(status.getBoolean("controlsAllowed"));
                assertEquals(0, status.getJSONArray("actions").length());
                var refresh = phone.status("band:fixture", req.put("type","refresh"));
                assertTrue(refresh.getString("note").contains("卡片"));
            } finally { phone.disarm(); }
            return null;
        });
        instrumentation.runOnMainSync(task);
        task.get();
    }
}
