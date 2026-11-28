package com.mario.lynkband;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.mario.lynkband.transport.WatchWire;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class WatchCompanionTest {
    @Test public void watchRequestsUsePhoneStatusAndRequireActiveAuthorization() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        WristApp phone = (WristApp) instrumentation.getTargetContext().getApplicationContext();
        // Exercise the routing against the actual phone application on its UI thread.
        // The test sends only status and prepare. No execute or widget click is sent.
        java.util.concurrent.FutureTask<Void> check = new java.util.concurrent.FutureTask<>(() -> {
            boolean original = phone.service;
            try {
                phone.service = false;
                assertNull(phone.reply("watch:test", frame("status")));
                phone.service = true;
                JSONObject status = exchange(phone, "status");
                assertEquals("w-test", status.getString("id"));
                assertEquals(1, status.getInt("v"));
                assertNotNull(status.getJSONObject("data"));
                assertNotNull(status.getJSONArray("actions"));
                JSONObject prepare = exchange(phone, "prepare");
                assertFalse(prepare.has("token"));
                assertTrue(prepare.getString("note").length() > 0);
            } finally { phone.service = original; }
            return null;
        });
        instrumentation.runOnMainSync(check); check.get();
    }
    @Test public void themesConvergeOnStatusInBothDirections() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        WristApp phone = (WristApp) instrumentation.getTargetContext().getApplicationContext();
        var routes = phone.getSharedPreferences("buttonLabels", 0);
        var prefs = PhoneLandscape.preferences(phone);
        var watch = phone.getSharedPreferences("mock-watch-theme", 0);
        var task = new java.util.concurrent.FutureTask<Void>(() -> {
            boolean service = phone.service;
            String oldAddress = routes.getString("watchAddress", null), oldType = routes.getString("wearType", null);
            String old = prefs.getString("landscape", null);
            long rev = prefs.getLong("landscape.revision", -1);
            boolean hasAuthor = prefs.contains("landscape.fromPhone"), author = prefs.getBoolean("landscape.fromPhone", true);
            try {
                routes.edit().putString("wearType", "apk").putString("watchAddress", "test").apply();
                phone.service = true;
                watch.edit().clear().apply();
                PhoneLandscape.COAST.select(prefs);
                JSONObject request = new JSONObject().put("v",1).put("type","status").put("id","theme")
                        .put("landscapeSync", com.mario.lynkband.solar.ThemeSync.snapshot(watch,"scene",false));
                JSONObject reply = new JSONObject(phone.reply("watch:test", request.toString().getBytes(StandardCharsets.UTF_8)));
                var first = reply.getJSONObject("landscapeSync");
                assertTrue(com.mario.lynkband.solar.ThemeSync.merge(watch,"scene",false,first));
                assertEquals("COAST", watch.getString("scene", null));
                com.mario.lynkband.solar.ThemeSync.select(watch,"scene","ALPINE",false);
                request.put("landscapeSync",com.mario.lynkband.solar.ThemeSync.snapshot(watch,"scene",false));
                assertFalse(new JSONObject(phone.reply("watch:other",request.toString().getBytes(StandardCharsets.UTF_8))).has("landscapeSync"));
                assertEquals(PhoneLandscape.COAST, PhoneLandscape.selected(prefs));
                reply = new JSONObject(phone.reply("watch:test",request.toString().getBytes(StandardCharsets.UTF_8)));
                assertEquals("ALPINE", reply.getJSONObject("landscapeSync").getString("scene"));
                assertEquals(PhoneLandscape.ALPINE, PhoneLandscape.selected(prefs));
                assertFalse(com.mario.lynkband.solar.ThemeSync.merge(watch,"scene",false,first));
                assertFalse(com.mario.lynkband.solar.ThemeSync.merge(watch,"scene",false,reply.getJSONObject("landscapeSync")));
            } finally {
                phone.service = service;
                routes.edit().putString("watchAddress",oldAddress).putString("wearType",oldType).apply();
                var restore = prefs.edit().putString("landscape",old);
                if (rev == -1) restore.remove("landscape.revision"); else restore.putLong("landscape.revision", rev);
                if (hasAuthor) restore.putBoolean("landscape.fromPhone",author); else restore.remove("landscape.fromPhone");
                restore.apply(); watch.edit().clear().apply();
            }
            return null;
        });
        instrumentation.runOnMainSync(task); task.get();
    }

    private JSONObject exchange(WristApp phone, String type) throws Exception {
        String reply = phone.reply("watch:test", frame(type));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        WatchWire.write(bytes,reply);
        return new JSONObject(new String(WatchWire.read(new ByteArrayInputStream(bytes.toByteArray())), StandardCharsets.UTF_8));
    }
    private byte[] frame(String type) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        WatchWire.write(bytes,new JSONObject().put("v",1).put("id","w-test").put("type",type).toString());
        return WatchWire.read(new ByteArrayInputStream(bytes.toByteArray()));
    }
}
