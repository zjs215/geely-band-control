package com.mario.lynkband;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.RemoteViews;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

// Exercises the installed official layout with local fixture values. No provider callbacks are sent.
@RunWith(AndroidJUnit4.class)
public final class OfficialCardTest {
    private Resources resources;
    private View root;
    @Before public void inflateOfficialMediumLayout() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        try { resources = context.getPackageManager().getResourcesForApplication(LynkWidget.OWNER); }
        catch (PackageManager.NameNotFoundException missing) { assumeTrue("Install the China Lynk 4.3.1 APK", false); }
        int layout = resources.getIdentifier("layout_widget", "layout", LynkWidget.OWNER);
        assertNotEquals(0, layout);
        root = new RemoteViews(LynkWidget.OWNER, layout).apply(context, new FrameLayout(context));
    }
    private View view(String name) {
        View result = root.findViewById(resources.getIdentifier(name, "id", LynkWidget.OWNER));
        assertNotNull(name, result);
        return result;
    }
    private void text(String name, String value) { ((TextView) view(name)).setText(value); }
    private void live() {
        view("rl_widget_state").setVisibility(View.GONE);
        view("rl_widget_cal_state").setVisibility(View.VISIBLE);
        view("ll_loading_auth").setVisibility(View.GONE);
        text("time", "11-25 14:30"); text("widget_name", "测试车辆"); text("condition_txt", "已驻车");
    }
    private ImageView setButton() {
        ImageView icon = (ImageView) view("unlock");
        view("fr_unlock").setVisibility(View.VISIBLE);
        view("pb_unlock").setVisibility(View.GONE);
        icon.setImageDrawable(new ColorDrawable(Color.RED));
        icon.setOnClickListener(v -> {});
        return icon;
    }

    @Test public void loggedOutLayoutOffersNoControlsOrStatus() throws Exception {
        assertEquals(0, CardContent.snapshot(root).length());
        assertTrue(CardContent.slots(root, "id").isEmpty());
    }
    @Test public void readsExactFuelBatteryRangeAndProviderTime() throws Exception {
        live();
        text("tv_mileage", "372"); text("tv_electricity", "48%"); text("txt_bar", "20L");
        var data = CardContent.snapshot(root);
        assertEquals("372", data.getString("range"));
        assertEquals("48%", data.getString("battery"));
        assertEquals("20L", data.getString("fuel"));
        assertEquals("11-25 14:30", data.getString("time"));
        view("ll_electricity").setVisibility(View.GONE);
        assertEquals("", CardContent.snapshot(root).getString("battery"));
    }
    @Test public void actionDescriptionTracksDirectionButIgnoresGenericOrConflictingLabels() {
        live();
        ImageView icon = setButton();
        CardContent.Slot slot = CardContent.slots(root, "id").get(0);
        View frame = (View) icon.getParent();
        frame.setContentDescription(null);
        icon.setContentDescription("开启空调");
        assertEquals("开空调", CardContent.describedAction(slot));
        icon.setContentDescription("关闭空调");
        assertEquals("关空调", CardContent.describedAction(slot));
        frame.setContentDescription("打开空调");
        assertEquals("", CardContent.describedAction(slot));
        frame.setContentDescription(null);
        icon.setContentDescription("已上锁");
        assertEquals("", CardContent.describedAction(slot));
    }

    @Test public void windowKeepsItsBindingAcrossAllFourOfficialStates() {
        live();
        ImageView icon = setButton();
        var category = com.mario.lynkband.transport.ControlCategory.WINDOW;
        String binding = CardContent.visibleSlots(root, "official").get(0).bindingKey();
        for (String suffix : new String[]{"on", "off", "on2", "off2"}) {
            int id = resources.getIdentifier("ic_ck_cc_" + suffix, "mipmap", LynkWidget.OWNER);
            assertNotEquals(0, id);
            icon.setImageDrawable(resources.getDrawable(id, null));
            var slot = CardContent.visibleSlots(root, "official").get(0);
            ControlItem item = ControlItem.resolve(category, slot);
            assertEquals(binding, slot.bindingKey());
            assertEquals(suffix.startsWith("on") ? "车窗.开" : "车窗.关", item.label());
            assertEquals(suffix.startsWith("on") ? "关车窗" : "开车窗", item.action());
        }
        icon.setVisibility(View.GONE);
        view("pb_unlock").setVisibility(View.VISIBLE);
        var busy = CardContent.visibleSlots(root, "official").get(0);
        assertEquals(binding, busy.bindingKey());
        assertNull(busy.target());
        assertEquals("车窗.加载", ControlItem.resolve(category, busy).label());
        view("pb_unlock").setVisibility(View.GONE);
        var missing = CardContent.visibleSlots(root, "official").get(0);
        assertEquals("车窗.未知", ControlItem.resolve(category, missing).label());
    }

    private void glyph(ImageView icon, String name) {
        int id = resources.getIdentifier("ic_ck_" + name, "mipmap", LynkWidget.OWNER);
        assertNotEquals(name, 0, id);
        icon.setImageDrawable(resources.getDrawable(id, null));
    }

    @Test public void lockShowsAllSixProviderStatesIndependentlyOfOptionalText() {
        live();
        ImageView icon = setButton();
        var lock = com.mario.lynkband.transport.ControlCategory.LOCK;
        for (String condition : new String[]{"已驻车", ""}) {
            for (String time : new String[]{"11-25 14:30", "--"}) {
                text("condition_txt", condition);
                text("time", time);
                boolean operable = !time.equals("--");
                for (String suffix : new String[]{"off", "on", "off2", "on2", "offs", "ons"}) {
                    glyph(icon, "js_" + suffix);
                    var slot = CardContent.visibleSlots(root, "official").get(0);
                    var item = ControlItem.resolve(lock, slot);
                    boolean open = suffix.startsWith("on");
                    assertEquals(suffix, open ? "车锁.开" : "车锁.关", item.label());
                    assertEquals(operable, slot.target() != null);
                    assertEquals(operable ? (open ? "上锁" : "解锁") : "", item.action());
                    assertEquals(!operable, CardContent.slots(root, "official").isEmpty());
                }
            }
        }
    }

    @Test public void lockRequiresEvidenceAndHonorsLoadingAndOverlays() {
        live();
        ImageView icon = setButton();
        var lock = com.mario.lynkband.transport.ControlCategory.LOCK;
        glyph(icon, "cc_off2"); // A real but different control in the same slot.
        assertEquals("车锁.未知", ControlItem.resolve(lock, CardContent.visibleSlots(root, "id").get(0)).label());
        glyph(icon, "js_off2");
        view("pb_unlock").setVisibility(View.VISIBLE);
        assertEquals("车锁.加载", ControlItem.resolve(lock, CardContent.visibleSlots(root, "id").get(0)).label());
        view("pb_unlock").setVisibility(View.GONE);
        for (String overlay : new String[]{"verify", "widget_hint"}) {
            View panel = view(overlay);
            panel.setVisibility(View.VISIBLE);
            for (android.view.ViewParent parent = panel.getParent(); parent instanceof View ancestor; parent = ancestor.getParent())
                ancestor.setVisibility(View.VISIBLE);
            var slot = CardContent.visibleSlots(root, "id").get(0);
            assertNull(slot.target());
            assertEquals("车锁.未知", ControlItem.resolve(lock, slot).label());
            panel.setVisibility(View.GONE);
        }
    }

    @Test public void iconOrVehicleChangeInvalidatesButtonIdentity() {
        live();
        ImageView icon = setButton();
        String first = CardContent.slots(root, "id").get(0).signature();
        icon.setImageDrawable(new ColorDrawable(Color.BLUE));
        String changed = CardContent.slots(root, "id").get(0).signature();
        assertNotEquals(first, changed);
        text("widget_name", "另一辆车");
        assertNotEquals(changed, CardContent.slots(root, "id").get(0).signature());
    }
    @Test public void exportedIconKeepsOfficialPixelsWithinBluetoothFrameBudget() {
        live();
        ImageView icon = setButton();
        byte[] encoded = android.util.Base64.decode(CardContent.watchIcon(icon), android.util.Base64.URL_SAFE);
        assertTrue(encoded.length <= 960);
        android.graphics.Bitmap decoded = android.graphics.BitmapFactory.decodeByteArray(encoded,0,encoded.length);
        assertEquals(Color.RED,decoded.getPixel(decoded.getWidth()/2,decoded.getHeight()/2));
        icon.setImageDrawable(new ColorDrawable(Color.BLUE));
        assertFalse(java.util.Arrays.equals(encoded,
                android.util.Base64.decode(CardContent.watchIcon(icon),android.util.Base64.URL_SAFE)));
    }
    @Test public void loadingDrivingAndOfficialConfirmationSuppressActions() {
        live(); setButton();
        assertFalse(CardContent.slots(root, "id").isEmpty());
        view("pb_unlock").setVisibility(View.VISIBLE);
        assertTrue(CardContent.slots(root, "id").isEmpty());
        view("pb_unlock").setVisibility(View.GONE);
        text("condition_txt", "行驶中");
        assertTrue(CardContent.slots(root, "id").isEmpty());
        text("condition_txt", "已驻车");
        view("verify").setVisibility(View.VISIBLE);
        assertTrue(CardContent.awaitingOwner(root));
        assertTrue(CardContent.slots(root, "id").isEmpty());
        assertFalse(CardContent.refresh(root));
    }
    private View showWindowPrompt() {
        view("rl_widget_state").setVisibility(View.GONE);
        view("rl_widget_cal_state").setVisibility(View.GONE);
        view("verify").setVisibility(View.VISIBLE);
        text("ty_confirm_title", "确认打开车窗吗？");
        assertEquals("确定", ((TextView) view("tv_verify")).getText().toString());
        assertEquals("取消", ((TextView) view("tv_cancel")).getText().toString());
        return view("verify");
    }

    @Test public void verifyOnlyScreenshotReturnsOnlyTheLocalConfirmButton() {
        View verify = showWindowPrompt();
        int[] confirmed = {0};
        View yes = view("tv_verify");
        yes.setOnClickListener(v -> confirmed[0]++);
        view("tv_cancel").setOnClickListener(v -> fail("Clicked cancel"));
        verify.setOnClickListener(v -> fail("Clicked overlay"));
        assertFalse(CardContent.readable(root));
        assertSame(verify, CardContent.actionPrompt(root));
        assertSame(yes, CardContent.confirmTarget(verify, "打开车窗"));
        View target = CardContent.confirmTarget(verify, "开窗");
        assertSame(yes, target);
        assertEquals(0, confirmed[0]);
        assertTrue(target.performClick());
        assertEquals(1, confirmed[0]);
        live(); // A visible vehicle panel beneath the overlay must still be read-only.
        assertFalse(CardContent.readyForControls(root));
        verify.setVisibility(View.GONE);
        assertNull(CardContent.actionPrompt(root));
        assertNull(CardContent.confirmTarget(verify, "打开车窗"));
        assertTrue(CardContent.readyForControls(root));
    }

    @Test public void doorLockPromptMatchesLockButNotUnlock() {
        View verify = showWindowPrompt();
        text("ty_confirm_title", "确认上锁车门吗？");
        View yes = view("tv_verify");
        yes.setOnClickListener(v -> fail("Lookup dispatched a click"));
        assertSame(yes, CardContent.confirmTarget(verify, "上锁"));
        assertNull(CardContent.confirmTarget(verify, "解锁"));
    }

    @Test public void rejectsOppositePartialPlaceholderAndVerificationText() {
        View verify = showWindowPrompt();
        view("tv_verify").setOnClickListener(v -> fail("Lookup dispatched a click"));
        for (String label : new String[]{"关闭车窗", "打开天窗", "车窗", "打开", "待标记", "--", "", null})
            assertNull(label, CardContent.confirmTarget(verify, label));
        text("ty_confirm_title", "确认关闭车窗吗?");
        assertNull(CardContent.confirmTarget(verify, "打开车窗"));
        assertSame(view("tv_verify"), CardContent.confirmTarget(verify, "关窗"));
        for (String title : new String[]{"确认打开车窗吗？请输入验证码", "确认打开车窗验证码吗？", "确认待标记吗？"}) {
            text("ty_confirm_title", title);
            assertNull(CardContent.actionPrompt(root));
            assertNull(CardContent.confirmTarget(verify, "打开车窗"));
            assertNull(CardContent.confirmTarget(verify, "待标记"));
        }
    }

    @Test public void requiresOneVisibleEnabledButtonWithItsOwnListener() {
        View verify = showWindowPrompt();
        View yes = view("tv_verify");
        verify.setOnClickListener(v -> fail("Overlay is not an affirmative button"));
        yes.setClickable(true);
        assertSame(verify, CardContent.actionPrompt(root));
        assertNull(CardContent.confirmTarget(verify, "开窗"));
        yes.setOnClickListener(v -> fail("Lookup dispatched a click"));
        yes.setEnabled(false);
        assertNull(CardContent.confirmTarget(verify, "开窗"));
        yes.setEnabled(true);
        yes.setClickable(false);
        assertNull(CardContent.confirmTarget(verify, "开窗"));
        yes.setClickable(true);
        yes.setVisibility(View.INVISIBLE);
        assertNull(CardContent.confirmTarget(verify, "开窗"));
        yes.setVisibility(View.VISIBLE);
        TextView duplicate = new TextView(yes.getContext());
        duplicate.setId(yes.getId());
        duplicate.setText("确定");
        duplicate.setOnClickListener(v -> fail("Clicked duplicate"));
        ((ViewGroup) view("ly_confirm")).addView(duplicate);
        assertNull(CardContent.confirmTarget(verify, "开窗"));
        duplicate.setVisibility(View.GONE);
        assertSame(yes, CardContent.confirmTarget(verify, "开窗"));
    }

    private void onMain(Runnable test) throws Exception {
        var task = new java.util.concurrent.FutureTask<Void>(test, null);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(task);
        task.get();
    }

    // Use the same receive -> voiceCard -> handleAction -> cardChanged path as the listener.
    // This separate Application never starts transports or attaches a provider widget.
    private final class VoicePhone extends WristApp {
        long now = 100_000;
        int firstClicks, confirmClicks, photoReads, failedRedraws;
        boolean cardUnavailable, photoUnavailable, failNextRedraw;
        boolean access = true;
        boolean bandPaired;
        @Override boolean permitsWearable(String node) {
            return (bandPaired && usesBand() && "band:fixture".equals(node)) || super.permitsWearable(node);
        }
        String identity = "fixture/1";
        final ImageView icon;

        VoicePhone(boolean requireConfirmation) {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            attachBaseContext(context);
            onCreate();
            card = new LynkWidget(this, new android.os.Handler(getMainLooper()), this::cardChanged) {
                @Override View content() {
                    if (cardUnavailable) throw new IllegalStateException("card temporarily unavailable");
                    return root;
                }
                @Override VehiclePhoto.Image vehiclePhoto(VehiclePhoto.Target target) {
                    photoReads++;
                    if (photoUnavailable) throw new IllegalStateException("photo temporarily unavailable");
                    return super.vehiclePhoto(target);
                }
                @Override String identity() { return identity; }
                @Override boolean bound() { return true; }
            };
            live();
            view("verify").setVisibility(View.GONE);
            icon = setButton();
            glyph(icon, "cc_off");
            icon.setOnClickListener(v -> firstClicks++);
            view("tv_verify").setOnClickListener(v -> {
                confirmClicks++;
                cardChanged(); // A synchronous duplicate update must not re-enter this click.
            });
            view("tv_cancel").setOnClickListener(v -> fail("Unexpected cancel click"));
            service = true;
            setVoice(true);
            setVoiceConfirmation(requireConfirmation);
            assertTrue(selectItem(slots().get(0), com.mario.lynkband.transport.ControlCategory.WINDOW));
        }
        @Override public android.content.SharedPreferences getSharedPreferences(String name, int mode) {
            return super.getSharedPreferences("voice-card-test-" + name, mode);
        }
        @Override long elapsed() { return now; }
        @Override boolean voiceAccess() { return access; }
        @Override void changed() {
            if (failNextRedraw) {
                failNextRedraw = false;
                failedRedraws++;
                throw new IllegalStateException("screen temporarily unavailable");
            }
            super.changed();
        }

        org.json.JSONObject watchReply(org.json.JSONObject request) throws Exception {
            String response = reply("watch:fixture", request.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertNotNull(response);
            return new org.json.JSONObject(response);
        }

        String post(String code) {
            now++;
            return voice.receive(VoiceScenes.command(code), "fixture", now, now, now, voiceConfirmation(), voiceCard);
        }
        void prompt() { showWindowPrompt(); cardChanged(); }
    }

    @Test public void directVoiceConfirmsOneMatchingCardOnlyOnce() throws Exception {
        onMain(() -> {
            VoicePhone phone = new VoicePhone(false);
            try {
                String code = "LYNK:动作:打开车窗";
                assertTrue(phone.post(code).contains("已转交"));
                assertEquals("", phone.voice.receive(code, "fixture", phone.now, phone.now, phone.now, false, phone.voiceCard));
                phone.post(code);
                phone.cardChanged(); // Ordinary updates must neither consume nor renew the continuation.
                assertEquals(1, phone.firstClicks);
                assertEquals(0, phone.confirmClicks);
                phone.prompt();
                phone.post(VoiceScenes.CONFIRM);
                phone.cardChanged();
                assertEquals(1, phone.firstClicks);
                assertEquals(1, phone.confirmClicks);
            } finally { phone.disarm(); }
        });
    }

    @Test public void voiceWaitsThroughItsOwnSpinnerBeforeWindowPrompt() throws Exception {
        onMain(() -> {
            VoicePhone phone = new VoicePhone(false);
            try {
                assertTrue(phone.post("LYNK:动作:打开车窗").contains("已转交"));
                phone.icon.setVisibility(View.GONE);
                view("pb_unlock").setVisibility(View.VISIBLE);
                phone.cardChanged();
                phone.cardChanged();
                assertEquals(0, phone.confirmClicks);
                phone.prompt();
                phone.cardChanged();
                assertEquals(1, phone.firstClicks);
                assertEquals(1, phone.confirmClicks);
            } finally { phone.disarm(); }
        });
    }

    @Test public void confirmedVoiceNeedsFreshConfirmationThenClicksMatchingPromptOnce() throws Exception {
        onMain(() -> {
            VoicePhone phone = new VoicePhone(true);
            try {
                assertTrue(phone.post("LYNK:动作:开窗").contains("待执行"));
                assertEquals(0, phone.firstClicks);
                // Also exercise a provider that switches to verify during the first click.
                phone.icon.setOnClickListener(v -> { phone.firstClicks++; showWindowPrompt(); });
                assertTrue(phone.post(VoiceScenes.CONFIRM).contains("已点卡片确定"));
                phone.post(VoiceScenes.CONFIRM);
                phone.cardChanged();
                assertEquals(1, phone.firstClicks);
                assertEquals(1, phone.confirmClicks);
            } finally { phone.disarm(); }
        });
    }

    @Test public void explicitCancelRevokesPreparedAndAlreadyDispatchedVoice() throws Exception {
        onMain(() -> {
            VoicePhone phone = new VoicePhone(true);
            try {
                phone.post("LYNK:动作:开窗");
                phone.post(VoiceScenes.CANCEL);
                phone.post(VoiceScenes.CONFIRM);
                assertEquals(0, phone.firstClicks);
                phone.post("LYNK:动作:开窗");
                phone.post(VoiceScenes.CONFIRM);
                phone.post(VoiceScenes.CANCEL);
                phone.prompt();
                assertEquals(1, phone.firstClicks);
                assertEquals(0, phone.confirmClicks);
            } finally { phone.disarm(); }
        });
    }

    @Test public void lateOrRevokedOrChangedCardNeverConfirmsEvenIfOriginalReturns() throws Exception {
        onMain(() -> {
            for (String reason : new String[]{"expired", "disabled", "access", "disconnect", "service", "mode", "vehicle",
                    "binding", "direction", "driving", "mismatch"}) {
                VoicePhone phone = new VoicePhone(false);
                try {
                    assertTrue(reason, phone.post("LYNK:动作:开窗").contains("已转交"));
                    switch (reason) {
                        case "expired" -> phone.now += CardConfirmation.TIMEOUT;
                        case "disabled" -> { phone.setVoice(false); phone.setVoice(true); }
                        case "access" -> { phone.access = false; phone.cardChanged(); phone.access = true; }
                        case "disconnect" -> phone.resetVoice(); // Listener disconnected.
                        case "service" -> { phone.setService(false); phone.service = true; }
                        case "mode" -> phone.setVoiceConfirmation(true);
                        case "vehicle" -> { text("widget_name", "另一辆车"); phone.cardChanged(); text("widget_name", "测试车辆"); }
                        case "binding" -> { phone.identity = "fixture/2"; phone.cardChanged(); phone.identity = "fixture/1"; }
                        case "direction" -> { glyph(phone.icon, "cc_on"); phone.cardChanged(); glyph(phone.icon, "cc_off"); }
                        case "driving" -> { text("condition_txt", "行驶中"); phone.cardChanged(); text("condition_txt", "已驻车"); }
                        case "mismatch" -> { showWindowPrompt(); text("ty_confirm_title", "确认关闭车窗吗？"); phone.cardChanged(); }
                    }
                    phone.prompt();
                    assertEquals(reason, 1, phone.firstClicks);
                    assertEquals(reason, 0, phone.confirmClicks);
                } finally { phone.disarm(); }
            }
        });
    }

    @Test public void bandStatusReadsPhotoOnlyForPairedOptInAndClearsHiddenImage() throws Exception {
        var check = new java.util.concurrent.FutureTask<Void>(() -> {
            VoicePhone phone = new VoicePhone(false);
            phone.getSharedPreferences("buttonLabels", Context.MODE_PRIVATE).edit().putString("wearType", "rpk").apply();
            var request = new org.json.JSONObject().put("v", 1).put("id", "poll").put("type", "status")
                    .put("bandPhoto", true).put("carPhotoId", "").put("photoOffset", 0).put("photoTransferId", "");
            String node = "band:fixture";
            try {
                var bitmap = android.graphics.Bitmap.createBitmap(72, 30, android.graphics.Bitmap.Config.ARGB_8888);
                bitmap.eraseColor(Color.BLUE);
                ((ImageView) view("car_img")).setImageBitmap(bitmap);
                byte[] poll = request.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                var denied = new org.json.JSONObject(phone.reply(node, poll)).getJSONObject("data");
                assertFalse(denied.has("carPhotoId"));
                phone.bandPaired = true;
                request.remove("bandPhoto");
                var legacy = new org.json.JSONObject(phone.reply(node, request.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                assertFalse(legacy.getJSONObject("data").has("carPhotoChunk"));
                assertEquals(0, phone.photoReads);
                var allowed = new org.json.JSONObject(phone.reply(node, poll)).getJSONObject("data");
                assertEquals("测试车辆", allowed.getString("vehicle"));
                assertEquals("image/png", allowed.getString("carPhotoMime"));
                assertTrue(allowed.getString("carPhotoChunk").startsWith("iVBORw0KGgo"));
                assertFalse(allowed.has("carPhoto"));
                view("car_img").setVisibility(View.GONE);
                var hidden = new org.json.JSONObject(phone.reply(node, poll)).getJSONObject("data");
                assertEquals("", hidden.getString("carPhotoId"));
                assertFalse(hidden.has("carPhotoChunk"));
                assertEquals(0, phone.firstClicks);
                assertEquals(0, phone.confirmClicks);
            } finally { phone.disarm(); }
            return null;
        });
        InstrumentationRegistry.getInstrumentation().runOnMainSync(check);
        check.get();
    }

    @Test public void compactExecuteReportsDispatchBeforeRedrawAndRecoversOnTheNextPoll() throws Exception {
        var check = new java.util.concurrent.FutureTask<VoicePhone>(() -> {
            VoicePhone phone = new VoicePhone(false);
            phone.getSharedPreferences("buttonLabels", Context.MODE_PRIVATE).edit()
                    .putString("wearType", "apk").putString("watchAddress", "fixture").apply();
            var status = new org.json.JSONObject().put("v", 1).put("id", "status").put("type", "status")
                    .put("compactExecuteAck", true).put("visualActions", true).put("carPhotoId", "");
            var item = phone.watchReply(status).getJSONArray("actions").getJSONObject(0);
            var request = new org.json.JSONObject(status.toString()).put("id", "prepare").put("type", "prepare");
            for (String field : new String[]{"slot", "key", "label"}) request.put(field, item.getString(field));
            var prepared = phone.watchReply(request);
            assertFalse(prepared.has("type"));
            request.put("type", "execute").put("id", "execute").put("token", prepared.getString("token"));
            phone.icon.setOnClickListener(v -> {
                phone.firstClicks++;
                phone.cardUnavailable = true; // Post-click inspection cannot verify the provider's prompt.
                phone.failNextRedraw = true;
            });
            int previousPhotoReads = phone.photoReads;
            var ack = phone.watchReply(request);
            assertEquals(5, ack.length());
            assertEquals(1, ack.getInt("v"));
            assertEquals("execute", ack.getString("id"));
            assertEquals("executeAck", ack.getString("type"));
            assertEquals(Boolean.TRUE, ack.get("accepted"));
            assertTrue(ack.getString("note").contains("已转交"));
            assertEquals(previousPhotoReads, phone.photoReads);
            assertEquals(0, phone.failedRedraws);
            var unavailable = phone.watchReply(status);
            assertFalse(unavailable.getBoolean("controlsAllowed"));
            assertEquals(0, unavailable.getJSONArray("actions").length());
            phone.cardUnavailable = false;
            phone.photoUnavailable = true;
            var recovered = phone.watchReply(status);
            assertTrue(recovered.getBoolean("controlsAllowed"));
            assertEquals("测试车辆", recovered.getJSONObject("data").getString("vehicle"));
            assertFalse(recovered.getJSONObject("data").has("carPhoto"));
            var replay = phone.watchReply(request);
            assertEquals(Boolean.FALSE, replay.get("accepted"));
            assertTrue(replay.getString("note").contains("失效"));
            request.put("compactExecuteAck", "true"); // Older clients only understand full snapshots.
            assertTrue(phone.watchReply(request).has("actions"));
            assertEquals(1, phone.firstClicks);
            return phone;
        });
        InstrumentationRegistry.getInstrumentation().runOnMainSync(check);
        VoicePhone phone = check.get();
        onMain(() -> {
            try { assertEquals(1, phone.failedRedraws); }
            finally { phone.disarm(); }
        });
    }

    @Test public void legacyAndHdRequestsCropBeforeResizingAndUseSeparateCacheEntries() throws Exception {
        var check = new java.util.concurrent.FutureTask<Void>(() -> {
            VoicePhone phone = new VoicePhone(false);
            android.graphics.Bitmap padded = android.graphics.Bitmap.createBitmap(220, 160, android.graphics.Bitmap.Config.ARGB_8888);
            try {
                android.graphics.Paint paint = new android.graphics.Paint();
                paint.setColor(Color.WHITE);
                new android.graphics.Canvas(padded).drawRect(63, 48, 183, 108, paint);
                phone.icon.setImageBitmap(padded);
                var request = new org.json.JSONObject().put("v", 1).put("id", "icons").put("type", "status").put("visualActions", true);
                for (boolean hd : new boolean[] {false, true, false}) {
                    if (hd) request.put("hdActionIcons", true); else request.remove("hdActionIcons");
                    String response = phone.reply("watch:test", request.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    assertNotNull(response);
                    var item = new org.json.JSONObject(response).getJSONArray("actions").getJSONObject(0);
                    String png = item.getString("icon");
                    assertTrue(android.util.Base64.decode(png, android.util.Base64.URL_SAFE).length <= (hd ? 2880 : 960));
                    android.graphics.Bitmap decoded = com.mario.lynkband.transport.WatchIconCodec.decode(png);
                    assertNotNull(decoded);
                    try {
                        assertEquals(hd ? 96 : 48, decoded.getWidth());
                        assertEquals(decoded.getWidth() / 2, decoded.getHeight());
                        assertEquals(Color.WHITE, decoded.getPixel(0, 0));
                    } finally { decoded.recycle(); }
                }
            } finally { phone.disarm(); phone.icon.setImageDrawable(null); padded.recycle(); }
            return null;
        });
        InstrumentationRegistry.getInstrumentation().runOnMainSync(check);
        check.get();
    }

    private org.json.JSONArray bandActions(VoicePhone phone, org.json.JSONObject request) throws Exception {
        byte[] bytes = request.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return new org.json.JSONObject(phone.reply("band:fixture", bytes)).getJSONArray("actions");
    }

    private void assertNoBandIcon(org.json.JSONArray actions) throws Exception {
        for (int i = 0; i < actions.length(); i++) assertFalse(actions.getJSONObject(i).has("bandIconPng"));
    }

    @Test public void pairedBandRequestsOneOfficialPngByCurrentSignature() throws Exception {
        var check = new java.util.concurrent.FutureTask<Void>(() -> {
            VoicePhone phone = new VoicePhone(false);
            phone.getSharedPreferences("buttonLabels", Context.MODE_PRIVATE).edit().putString("wearType", "rpk").apply();
            var request = new org.json.JSONObject().put("v", 1).put("id", "icon").put("type", "status")
                    .put("selectedActions", true);
            try {
                var legacy = bandActions(phone, request);
                String key = legacy.getJSONObject(0).getString("key");
                assertNoBandIcon(legacy);
                request.put("bandIconKey", key);
                assertNoBandIcon(bandActions(phone, request)); // Unpaired node, still allowed to see metadata.
                phone.bandPaired = true;
                var actions = bandActions(phone, request);
                var first = actions.getJSONObject(0);
                assertEquals(key, first.getString("key"));
                String png = first.getString("bandIconPng");
                assertTrue(png.startsWith("iVBORw0KGgo") && png.length() <= 2048);
                var profile = com.mario.lynkband.transport.WatchIconCodec.Profile.BAND;
                int drawable = resources.getIdentifier("ic_ck_cc_off", "mipmap", LynkWidget.OWNER);
                String official = com.mario.lynkband.transport.WatchIconCodec.encodeResource(resources, drawable, profile);
                assertEquals(official, png);
                byte[] decodedPng = java.util.Base64.getDecoder().decode(png);
                assertTrue(decodedPng.length <= 1536);
                var decoded = android.graphics.BitmapFactory.decodeByteArray(decodedPng, 0, decodedPng.length);
                assertNotNull(decoded);
                int edge = Math.max(decoded.getWidth(), decoded.getHeight());
                decoded.recycle();
                assertTrue(edge >= 32 && edge <= 72);
                for (int i = 1; i < actions.length(); i++) assertFalse(actions.getJSONObject(i).has("bandIconPng"));
                assertFalse(first.has("icon"));

                request.put("bandPhoto", true).put("carPhotoId", "").put("photoOffset", 0).put("photoTransferId", "");
                assertNoBandIcon(bandActions(phone, request));
                request.remove("bandPhoto");
                glyph(phone.icon, "cc_on");
                assertNoBandIcon(bandActions(phone, request)); // The changed artwork has a new key.
                request.put("bandIconKey", bandActions(phone, request).getJSONObject(0).getString("key"));
                assertNotEquals(png, bandActions(phone, request).getJSONObject(0).getString("bandIconPng"));

                phone.icon.setVisibility(View.GONE);
                request.put("bandIconKey", bandActions(phone, request).getJSONObject(0).getString("key"));
                assertEquals("", bandActions(phone, request).getJSONObject(0).getString("bandIconPng"));
                assertEquals(0, phone.firstClicks);
                assertEquals(0, phone.confirmClicks);
            } finally { phone.disarm(); }
            return null;
        });
        InstrumentationRegistry.getInstrumentation().runOnMainSync(check);
        check.get();
    }

    @Test public void wideWidgetTargetKeepsItsSquareGlyphOnTheWatch() {
        live();
        ImageView icon = setButton();
        android.graphics.Bitmap mark = android.graphics.Bitmap.createBitmap(64,64,android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Paint ink = new android.graphics.Paint();
        ink.setColor(Color.WHITE);
        new android.graphics.Canvas(mark).drawRect(12,12,52,52,ink);
        icon.setImageBitmap(mark);
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        icon.layout(0,0,180,52);
        android.graphics.Bitmap exported = com.mario.lynkband.transport.WatchIconCodec.decode(CardContent.watchIcon(icon));
        assertNotNull(exported);
        int row=0,column=0;
        for (int i=0;i<exported.getWidth();i++) {
            if(Color.alpha(exported.getPixel(i,exported.getHeight()/2))>127) row++;
            if(Color.alpha(exported.getPixel(exported.getWidth()/2,i))>127) column++;
        }
        assertTrue(row>10);
        assertTrue(Math.abs(row-column)<=1);
        view("pb_unlock").setVisibility(View.VISIBLE);
        assertTrue(CardContent.slots(root,"id").isEmpty());
        assertNull(CardContent.visibleSlots(root,"id").get(0).target());
    }
}
