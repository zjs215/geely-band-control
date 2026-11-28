package com.mario.lynkband;

import com.mario.lynkband.solar.ThemeArt;
import com.mario.lynkband.solar.ThemeSync;
import com.mario.lynkband.transport.ControlCategory;
import com.mario.lynkband.transport.WatchIconCodec;
import com.mario.lynkband.transport.WatchPhotoWire;
import com.mario.lynkband.transport.WatchWire;

import android.app.Application;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class WristApp extends Application {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ActionTickets tickets = new ActionTickets();
    private final CardConfirmation followUp = new CardConfirmation();
    private final CardOperation operation = new CardOperation();
    private String operationNode = "";
    private final Runnable operationExpiry = () -> { observeOperation(); changed(); };
    private final java.util.Set<String> bandThemeSubscribers = new java.util.HashSet<>();
    record VelaVersion(String node, int code, String profile, long seenAt) {}
    private VelaVersion reportedVela;

    VelaVersion currentVelaVersion() {
        if (!service || !usesBand() || reportedVela == null
                || !reportedVela.node().equals(link.singleConnectedNode())
                || SystemClock.elapsedRealtime() - reportedVela.seenAt() > 30_000L) return null;
        return reportedVela;
    }

    private String receiptNode = "";
    private String cardReceipt = "";
    private final android.util.LruCache<String, String> watchIcons = new android.util.LruCache<>(16);
    private static final String VOICE = "local:mi-home";
    final VoiceScenes voice = new VoiceScenes();
    ThemeLibrary themes;
    LynkWidget card;
    XiaomiLink link;
    WatchServer watch;
    private SharedPreferences labels;
    private static final long LOCKED_REFRESH_INTERVAL_MS = 5 * 60_000L;
    private long nextLockedRefresh;
    private String lockedRefreshTime;
    boolean service;
    private boolean screen;
    private boolean recoveringTheme;
    private final Runnable retryLegacyTheme = this::restoreLegacyTheme;
    private String readyVehicle = "";
    private Runnable observer;
    String last = "";

    @Override public void onCreate() {
        super.onCreate();
        themes = new ThemeLibrary(this);
        if (themes.selected() != null) {
            SharedPreferences prefs = PhoneLandscape.preferences(this);
            PhoneLandscape compatible = PhoneLandscape.compatibleWith(themes.selected());
            if (compatible != PhoneLandscape.selected(prefs)) compatible.select(prefs);
        }
        labels = getSharedPreferences("buttonLabels", MODE_PRIVATE);
        card = new LynkWidget(this, main, this::cardChanged);
        if (!labels.contains("wearType")) {
            labels.edit().putString("wearType", hasWatch() || !card.bound() ? "apk" : "rpk").apply();
        }
        link = new XiaomiLink(this, main, this::receive, node -> {
            bandThemeSubscribers.remove("band:" + node);
            if (reportedVela != null && node.equals(reportedVela.node())) reportedVela = null;
            forgetDevice("band:" + node);
            changed();
        }, this::changed);
        watch = new WatchServer(this, main, this::reply,
                () -> forgetDevice(watchNode()), this::changed);
    }

    boolean syncThemeToWearable() {
        return PhoneLandscape.preferences(this).getBoolean("syncThemeToWearable", true);
    }

    void setThemeSyncToWearable(boolean enabled) {
        SharedPreferences prefs = PhoneLandscape.preferences(this);
        prefs.edit().putBoolean("syncThemeToWearable", enabled).apply();
        selectPhoneLandscape(PhoneLandscape.selected(prefs));
    }

    void selectPhoneLandscape(PhoneLandscape scene) {
        SharedPreferences prefs = PhoneLandscape.preferences(this);
        scene.select(prefs);
        if (!service || bandThemeSubscribers.isEmpty()) return;
        JSONObject state = wearableLandscape(prefs);
        try {
            String message = new JSONObject().put("v", 1).put("type", "bandThemeChanged")
                    .put("theme", state.getString("scene")).put("bandThemeRevision", state.getLong("revision")).toString();
            for (String node : bandThemeSubscribers) {
                if (permitsWearable(node)) sendBandTheme(node.substring(5), message);
            }
        } catch (JSONException invalid) { throw new IllegalStateException(invalid); }
    }

    private JSONObject wearableLandscape(SharedPreferences prefs) {
        JSONObject state = ThemeSync.snapshot(prefs, PhoneLandscape.KEY, true);
        try {
            if (!syncThemeToWearable()) state.put("scene", PhoneLandscape.SKY.name());
            return state;
        } catch (JSONException invalid) { throw new IllegalStateException(invalid); }
    }

    private void restoreLegacyTheme() {
        if ((!screen && !service) || recoveringTheme || themes.selected() != null) return;
        SharedPreferences prefs = PhoneLandscape.preferences(this);
        PhoneLandscape previous = PhoneLandscape.selected(prefs);
        if (previous == PhoneLandscape.SKY) return;
        main.removeCallbacks(retryLegacyTheme);
        recoveringTheme = true;
        new Thread(() -> {
            ThemeLibrary.Pack recovered = null;
            try { recovered = themes.recoverLegacyTheme(previous.themeId()); }
            catch (IOException | JSONException unavailable) { /* The default stays visible while offline. */ }
            ThemeLibrary.Pack next = recovered;
            main.post(() -> {
                recoveringTheme = false;
                if (themes.selected() != null || PhoneLandscape.selected(prefs) != previous) {
                    restoreLegacyTheme();
                    return;
                }
                if (next != null) {
                    try {
                        themes.select(next);
                        changed();
                        return;
                    } catch (IOException | JSONException unavailable) { /* Retry the private copy. */ }
                }
                if (screen || service) main.postDelayed(retryLegacyTheme, 30_000L);
            });
        }, "legacy-theme").start();
    }

    void sendBandTheme(String node, String message) {
        link.send(node, message, failure -> android.util.Log.i("WristLynkHost", "Band theme will sync on next status"));
    }

    void screen(boolean visible, Runnable listener) {
        screen = visible;
        observer = listener;
        if (visible) restoreLegacyTheme();
        card.listening(screen || service);
        changed();
    }

    void setService(boolean active) {
        if (active == service) return;
        service = active;
        if (active) { syncTransports(); restoreLegacyTheme(); }
        else { bandThemeSubscribers.clear(); watch.stop(); disarm(); link.stop(); }
        card.listening(screen || active);
        if (active && !card.bound() && card.autoBind() && !card.binding()) card.bind(null);
        changed();
    }

    boolean voiceEnabled() { return labels.getBoolean("voiceScenes", false); }
    boolean voiceConfirmation() { return labels.getBoolean("voiceConfirm", false); }
    void setVoiceConfirmation(boolean enabled) {
        resetVoice();
        labels.edit().putBoolean("voiceConfirm", enabled).apply();
        changed();
    }
    void setVoice(boolean enabled) {
        labels.edit().putBoolean("voiceScenes", enabled).apply();
        resetVoice();
        changed();
    }
    boolean voiceAccess() { return MiHomeScenesListener.access(this); }
    long elapsed() { return SystemClock.elapsedRealtime(); }

    void resetVoice() {
        voice.clear();
        tickets.forget(VOICE);
        followUp.forget(VOICE);
    }

    final VoiceScenes.Card voiceCard = new VoiceScenes.Card() {
        @Override public VoiceScenes.Choice prepare(String name) {
            updateReadyVehicle();
            if (!permits(VOICE)) return null;
            CardContent.Slot found = null;
            for (CardContent.Slot slot : slots()) {
                if (!name.equals(VoicePreset.canonical(label(slot)))) continue;
                if (found != null) return null;
                found = slot;
            }
            if (found == null) return null;
            String actual = label(found);
            try {
                JSONObject request = new JSONObject().put("slot", found.id()).put("key", found.signature()).put("label", actual);
                JSONObject out = new JSONObject();
                handleAction(VOICE, "prepare", request, out);
                return out.has("token") ? new VoiceScenes.Choice(found.id(), found.signature(), actual, out.getString("token")) : null;
            } catch (JSONException invalid) { return null; }
        }
        @Override public void cancel() { resetVoice(); }
        @Override public String execute(VoiceScenes.Choice choice) {
            updateReadyVehicle();
            try {
                JSONObject request = new JSONObject().put("slot", choice.slot()).put("key", choice.signature())
                        .put("label", choice.label()).put("token", choice.token());
                JSONObject out = new JSONObject();
                handleAction(VOICE, "execute", request, out);
                return out.optString("note", "动作已失效，请重新选择");
            } catch (JSONException invalid) { return "动作已失效，请重新选择"; }
        }
    };

    private void forgetDevice(String node) {
        tickets.forget(node);
        followUp.forget(node);
        if (node.equals(receiptNode)) cardReceipt = "";
    }

    private String watchNode() { return "watch:" + labels.getString("watchAddress", ""); }

    private boolean permits(String node) {
        if (!service || readyVehicle.isEmpty()) return false;
        return authorized(node);
    }

    // The provider may hide the ready vehicle panel while showing its confirmation.
    private boolean authorized(String node) {
        return VOICE.equals(node) ? voiceEnabled() && voiceAccess() : permitsWearable(node);
    }

    boolean permitsWearable(String node) {
        if (node.startsWith("watch:"))
            return androidWear() && hasWatch() && watchNode().equals(node);
        return usesBand() && node.startsWith("band:") && link.canReceive(node.substring(5));
    }

    String watchName() { return labels.getString("watchName", ""); }
    boolean hasWatch() { return !labels.getString("watchAddress", "").isEmpty(); }
    String selectedWatchAddress() { return labels.getString("watchAddress", ""); }
    boolean androidWear() { return "apk".equals(labels.getString("wearType", "apk")); }
    boolean usesBand() { return !androidWear(); }
    void selectType(boolean apk) {
        if (androidWear() == apk) return;
        labels.edit().putString("wearType", apk ? "apk" : "rpk").apply();
        disarm();
        if (service) syncTransports();
        changed();
    }
    private void syncTransports() {
        syncBand();
        watch.start(androidWear() ? labels.getString("watchAddress", "") : "");
    }

    private void syncBand() {
        if (!service || !usesBand()) reportedVela = null;
        if (service && usesBand()) link.start();
        else { bandThemeSubscribers.clear(); link.stop(); }
    }

    String connectionStatus() {
        if (!androidWear()) return link.status();
        if (!hasWatch()) return "请选择已配对的 Android 手表";
        return service ? watch.status : "Watch 通信未启动";
    }

    void selectWatch(String address, String name) {
        labels.edit().putString("watchAddress", address).putString("watchName", name).apply();
        disarm();
        if (service) {
            syncBand();
            watch.start(address);
        }
        changed();
    }

    void disarm() {
        operation.clear();
        main.removeCallbacks(operationExpiry);
        followUp.clear();
        cardReceipt = "";
        resetVoice();
        tickets.clear();
        changed();
    }

    private String vehicleKey() {
        return card.identity() + "/" + CardContent.text(card.content(), "widget_name");
    }

    private void updateReadyVehicle() {
        String key = CardContent.readyForControls(card.content()) ? vehicleKey() : "";
        if (key.equals(readyVehicle)) return;
        readyVehicle = key;
        // Changing panels invalidates preparation, not an already dispatched action.
        // inspectCardPrompt checks that continuation against the actual widget update.
        voice.clear();
        tickets.clear();
    }

    void cardChanged() {
        if (lockedRefreshTime != null) {
            String updated = CardContent.text(card.content(), "time");
            if (!updated.isEmpty() && !updated.equals(lockedRefreshTime)) {
                android.util.Log.i("WristLynkHost", "Provider timestamp advanced, phone interactive=" +
                        getSystemService(android.os.PowerManager.class).isInteractive());
                lockedRefreshTime = null;
            }
        }
        if (service && !card.bound() && card.autoBind() && !card.binding()) card.bind(null);
        inspectCardPrompt();
        observeOperation();
        updateReadyVehicle();
        changed();
    }

    private void observeOperation() {
        if (!operation.active()) return;
        long now = elapsed();
        String key = operation.bindingKey();
        String state = "unknown";
        for (CardContent.Slot slot : visibleSlots()) {
            if (key.equals(slot.bindingKey())) {
                state = control(slot).state();
                break;
            }
        }
        String note = operation.observe(key, state, now);
        if (!note.isEmpty()) {
            last = note;
            receiptNode = operationNode;
            cardReceipt = note;
        }
    }

    private void inspectCardPrompt() {
        long now = elapsed();
        if (!followUp.waiting(now)) return;
        View root = card.content();
        CardConfirmation.Action action = followUp.current(card.identity(), CardContent.text(root, "widget_name"), now);
        if (action == null) return;
        if (!service || !authorized(action.node()) || !card.bound()) {
            followUp.clear();
            return;
        }
        View prompt = CardContent.actionPrompt(root);
        String state = CardContent.text(root, "condition_txt");
        boolean driving = state.contains("行驶") || state.contains("行车中");
        if (prompt == null && CardContent.readyForControls(root)) {
            // Some cards temporarily replace the clicked icon with that slot's progress indicator.
            for (CardContent.Slot slot : visibleSlots()) {
                if (!action.bindingKey().equals(slot.bindingKey())) continue;
                if (slot.loading()) return;
                if (action.signature().equals(slot.signature()) && action.label().equals(label(slot))) return;
                break;
            }
        }
        followUp.clear(); // Consume before clicking, including when the provider rejects the click.
        View button = prompt == null || driving ? null : CardContent.confirmTarget(prompt, action.label());
        boolean clicked = button != null && button.performClick();
        receiptNode = action.node();
        cardReceipt = clicked ? "已点卡片确定 · " + action.label() + "，请核对车况" : "请在手机核对领克卡片 · " + action.label();
        last = cardReceipt;
    }

    private void refreshWhileLocked(String node) {
        long now = elapsed();
        if (now < nextLockedRefresh || !permitsWearable(node)
                || !card.bound() || operation.active() || followUp.waiting(now)
                || getSystemService(android.os.PowerManager.class).isInteractive()) return;
        View root = card.content();
        if (!CardContent.readable(root) || CardContent.awaitingOwner(root) || CardContent.find(root, "widget_hint") != null) return;
        nextLockedRefresh = now + LOCKED_REFRESH_INTERVAL_MS;
        lockedRefreshTime = CardContent.text(root, "time");
        boolean requested = CardContent.refresh(root);
        if (!requested) lockedRefreshTime = null;
        android.util.Log.i("WristLynkHost", "Screen-off widget refresh requested=" + requested);
    }

    String refreshCard() {
        if (!card.bound()) return "请先绑定领克卡片";
        return CardContent.refresh(card.content()) ? "已请求刷新，等候领克更新" : "当前卡片无可用刷新按钮";
    }

    void changed() { if (observer != null) observer.run(); }

    List<CardContent.Slot> slots() { return CardContent.slots(card.content(), card.identity()); }
    List<CardContent.Slot> visibleSlots() { return CardContent.visibleSlots(card.content(), card.identity()); }

    ControlCategory selectedItem(CardContent.Slot slot) {
        String key = "item:" + slot.bindingKey();
        if (labels.contains(key)) return ControlCategory.resolve(labels.getString(key, "OTHER"), "");
        // Carry over the old icon-specific selection when this vehicle and icon are seen again.
        ControlCategory previous = ControlCategory.resolve(labels.getString("category:" + slot.signature(), ""),
                labels.getString(slot.signature(), ""));
        if (previous != ControlCategory.OTHER) labels.edit().putString(key, previous.name()).apply();
        return previous;
    }

    private ControlItem control(CardContent.Slot slot) {
        return ControlItem.resolve(selectedItem(slot), slot);
    }

    String displayName(CardContent.Slot slot) { return control(slot).label(); }
    String label(CardContent.Slot slot) { return control(slot).action(); }

    boolean selectItem(CardContent.Slot selected, ControlCategory item) {
        for (CardContent.Slot current : visibleSlots()) {
            if (!current.bindingKey().equals(selected.bindingKey())) continue;
            labels.edit().putString("item:" + current.bindingKey(), item.name()).apply();
            disarm();
            return true;
        }
        return false;
    }

    private void receive(String node, byte[] bytes) {
        String response = reply("band:" + node, bytes);
        if (response != null) link.send(node, response, cause -> { last = "穿戴设备响应未送达"; changed(); });
    }

    String reply(String node, byte[] bytes) {
        if (!service || bytes == null || bytes.length > 4096) return null;
        try {
            JSONObject request = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            String id = request.optString("id");
            if (id.isEmpty() || id.length() > 80 || request.optInt("v") != 1) return null;
            JSONObject out = new JSONObject().put("v", 1).put("id", id);
            String type = request.optString("type");
            boolean band = node.startsWith("band:");
            if (ThemeArt.TRANSFER.equals(type)) {
                out.put("type", ThemeArt.TRANSFER);
                if (syncThemeToWearable() && (band || node.startsWith("watch:")) && permitsWearable(node)) {
                    themes.chunk(request, out, band);
                }
                return out.toString();
            }
            boolean compact = (node.startsWith("watch:") || (band && permitsWearable(node)))
                    && "execute".equals(type) && Boolean.TRUE.equals(request.opt("compactExecuteAck"));
            if (compact) out.put("accepted", false);
            boolean solarStatus = "status".equals(type) && permitsWearable(node)
                    && Boolean.TRUE.equals(request.opt(band ? "bandTheme" : "watchSolar"));
            if (solarStatus) {
                SharedPreferences prefs = PhoneLandscape.preferences(this);
                com.mario.lynkband.solar.SolarController.Snapshot solar =
                        com.mario.lynkband.solar.SolarController.current(this, prefs);
                out.put("themeNight", solar.night()).put("solarInfo", solar.info());
            }
            if (band && "status".equals(type)) {
                String idOfBand = node.substring(5);
                if (idOfBand.equals(link.singleConnectedNode())
                        && request.opt("bandVersionCode") instanceof Integer code && code > 0) {
                    String profile = request.optString("bandPackageProfile");
                    if ("standard".equals(profile) || "pill".equals(profile))
                        reportedVela = new VelaVersion(idOfBand, code, profile, SystemClock.elapsedRealtime());
                }
                if (solarStatus && Boolean.TRUE.equals(request.opt("bandThemePush"))) bandThemeSubscribers.add(node);
                else bandThemeSubscribers.remove(node);
            }
            if (solarStatus && band) {
                SharedPreferences prefs = PhoneLandscape.preferences(this);
                JSONObject theme = wearableLandscape(prefs);
                long revision = theme.getLong("revision");
                Object choice = syncThemeToWearable() && themes.selected() == null ? request.opt("bandThemeChoice") : null;
                for (PhoneLandscape scene : PhoneLandscape.values()) {
                    if (!scene.name().equals(choice) || scene.name().equals(theme.getString("scene"))) continue;
                    if (request.has("bandThemeBaseRevision") && request.optLong("bandThemeBaseRevision", -1) != revision) {
                        out.put("bandThemeConflict", true);
                    } else {
                        scene.select(prefs);
                        restoreLegacyTheme();
                        theme = wearableLandscape(prefs);
                    }
                    break;
                }
                out.put("theme", theme.getString("scene")).put("bandThemeRevision", theme.getLong("revision"));
            }
            // A paired watch can sync its landscape even when the vehicle widget is unavailable.
            if ("status".equals(type) && node.startsWith("watch:") && permitsWearable(node)
                    && request.opt("landscapeSync") instanceof JSONObject peer) {
                SharedPreferences prefs = PhoneLandscape.preferences(this);
                if (syncThemeToWearable() && themes.selected() == null
                        && ThemeSync.merge(prefs, PhoneLandscape.KEY, true, peer)) restoreLegacyTheme();
                out.put("landscapeSync", wearableLandscape(prefs));
            }
            if (solarStatus && Boolean.TRUE.equals(request.opt(ThemeArt.CAPABILITY))) {
                if (!syncThemeToWearable()) out.put(ThemeArt.OFFER, JSONObject.NULL);
                else if (themes.selected() != null || PhoneLandscape.selected(PhoneLandscape.preferences(this)) == PhoneLandscape.SKY)
                    out.put(ThemeArt.OFFER, themes.offer(band));
            }
            try {
                updateReadyVehicle();
                switch (type) {
                    case "status" -> {
                        refreshWhileLocked(node);
                        if (node.equals(receiptNode) && !cardReceipt.isEmpty()) {
                            out.put("note", cardReceipt);
                            cardReceipt = "";
                        }
                    }
                    case "refresh" -> out.put("note", refreshCard());
                    case "prepare", "execute" -> handleAction(node, type, request, out);
                    default -> { return null; }
                }
                if (compact) return out.put("type", "executeAck").toString();
                return snapshotReply(node, request, out);
            } catch (RuntimeException unavailable) {
                // A provider view can disappear or fail to draw during a card transition.
                android.util.Log.w("WristLynkHost", "Card unavailable for " + type, unavailable);
                if (!node.startsWith("watch:") && !band && !compact) throw unavailable;
                tickets.forget(node);
                out.remove("token");
                out.remove("label");
                if ("execute".equals(type) && !out.has("note")) {
                    followUp.forget(node);
                    out.put("note", "结果待核对，请查看官方车况");
                }
                if (compact) return out.put("type", "executeAck").toString();
                if ("refresh".equals(type) && !out.has("note")) out.put("note", "等待领克卡片数据");
                return out.put("status", "等待领克卡片数据").put("controlsAllowed", false)
                        .put("data", new JSONObject()).put("actions", new JSONArray()).toString();
            }
        } catch (JSONException | IllegalStateException ignored) {
            last = "未能读取卡片或穿戴端消息";
            changed();
            return null;
        }
    }

    private String snapshotReply(String node, JSONObject request, JSONObject out) throws JSONException {
        View view = card.content();
        JSONArray actions = new JSONArray();
        boolean permitted = permits(node);
        boolean visual = node.startsWith("watch:") && request.optBoolean("visualActions");
        boolean sendWatchIcons = visual && !Boolean.TRUE.equals(request.opt("localWatchIcons"));
        boolean listSelected = visual || (node.startsWith("band:") && request.optBoolean("selectedActions"));
        String bandIconKey = node.startsWith("band:") && "status".equals(request.opt("type"))
                && request.opt("bandIconKey") instanceof String key && !key.isEmpty()
                && !BandPhoto.requested(request) && permitsWearable(node) ? key : null;
        if (permitted || listSelected) {
            for (CardContent.Slot slot : listSelected ? CardContent.visibleSlots(view, card.identity()) : slots()) {
                ControlItem control = control(slot);
                if (control.action().isEmpty() && !listSelected) continue;
                JSONObject item = control.wireAction(slot, listSelected, permitted);
                boolean bandIcon = slot.signature().equals(bandIconKey);
                if ((sendWatchIcons && slot.icon() != null) || bandIcon) {
                    WatchIconCodec.Profile profile = bandIcon ? WatchIconCodec.Profile.BAND
                            : request.optBoolean(WatchIconCodec.HD_CAPABILITY) ? WatchIconCodec.Profile.HD
                            : WatchIconCodec.Profile.LEGACY;
                    String iconKey = slot.signature() + "/" + control.matchedDrawable() + "/" + profile.name();
                    try {
                        String icon = watchIcons.get(iconKey);
                        if (icon == null) {
                            icon = CardContent.controlIcon(slot.icon(), control.matchedDrawable(), profile);
                            watchIcons.put(iconKey, icon);
                        }
                        item.put(bandIcon ? "bandIconPng" : "icon", icon);
                    } catch (RuntimeException unavailable) {
                        if (bandIcon) item.put("bandIconPng", "");
                        android.util.Log.w("WristLynkHost", "Action icon unavailable", unavailable);
                    }
                }
                actions.put(item);
            }
        }
        String status = view == null ? "请先在手机绑定领克卡片"
                : CardContent.awaitingOwner(view) ? "请在手机领克卡片确认"
                : !CardContent.readable(view) ? "等待领克卡片数据"
                : permitted ? "已允许穿戴车控" : "车况只读";
        JSONObject data = CardContent.snapshot(view);
        out.put("data", data).put("status", status).put("controlsAllowed", permitted).put("actions", actions);
        if (node.startsWith("band:")) out.put("appearance", PhoneLandscape.night(this) ? "dark" : "light");
        if (node.startsWith("band:") && BandPhoto.requested(request) && permitsWearable(node)) {
            VehiclePhoto.Image photo = null;
            try {
                if (Boolean.TRUE.equals(request.opt(BandPhoto.HD_CAPABILITY)))
                    photo = card.vehiclePhoto(VehiclePhoto.Target.BAND_HD);
            } catch (RuntimeException unavailable) {
                android.util.Log.w("WristLynkHost", "HD band photo unavailable", unavailable);
            }
            if (photo == null) {
                try { photo = card.vehiclePhoto(VehiclePhoto.Target.BAND); }
                catch (RuntimeException unavailable) { android.util.Log.w("WristLynkHost", "Band photo unavailable", unavailable); }
            }
            BandPhoto.append(out, request, photo);
        } else if (node.startsWith("watch:") && permitsWearable(node) && request.opt("carPhotoId") instanceof String knownId) {
            try {
                boolean hd = Boolean.TRUE.equals(request.opt(WatchPhotoWire.HD_CAPABILITY));
                if (!hd || !appendWatchPhoto(out, data, knownId, VehiclePhoto.Target.WATCH_HD)) {
                    appendWatchPhoto(out, data, knownId, VehiclePhoto.Target.WATCH);
                }
            } catch (RuntimeException unavailable) {
                android.util.Log.w("WristLynkHost", "Vehicle photo unavailable", unavailable);
            }
        }
        return out.toString();
    }

    private boolean appendWatchPhoto(JSONObject out, JSONObject data, String knownId, VehiclePhoto.Target target)
            throws JSONException {
        VehiclePhoto.Image photo;
        try { photo = card.vehiclePhoto(target); }
        catch (RuntimeException unavailable) {
            android.util.Log.w("WristLynkHost", "Watch photo unavailable for " + target, unavailable);
            return false;
        }
        if (photo == null) return false;
        data.put("carPhotoId", photo.id());
        if (photo.id().equals(knownId)) return true;
        data.put("carPhoto", photo.base64());
        if (out.toString().getBytes(StandardCharsets.UTF_8).length <= WatchWire.REPLY_LIMIT) return true;
        data.remove("carPhoto");
        data.remove("carPhotoId");
        return false;
    }

    private void handleAction(String node, String type, JSONObject request, JSONObject out) throws JSONException {
        if (!permits(node)) { out.put("note", "请在手机检查穿戴车控设置和卡片状态"); return; }
        for (CardContent.Slot slot : slots()) {
            if (!slot.id().equals(request.optString("slot")) || !slot.signature().equals(request.optString("key"))) continue;
            ControlItem item = control(slot);
            String name = item.action();
            if (name.isEmpty() || !name.equals(request.optString("label"))) continue;
            String target = readyVehicle + "/" + slot.signature() + "/" + name;
            long now = elapsed();
            if (followUp.waiting(now)) {
                out.put("note", "正在等候上一次领克卡片操作");
                return;
            }
            if (type.equals("prepare")) {
                out.put("token", tickets.issue(node, target, now)).put("label", name);
            } else if (tickets.take(node, request.optString("token"), target, now)) {
                String identity = card.identity();
                String vehicle = CardContent.text(card.content(), "widget_name");
                if (out.has("accepted")) out.put("accepted", JSONObject.NULL);
                boolean sent = slot.target().performClick();
                if (out.has("accepted")) out.put("accepted", sent);
                last = sent ? "已转交领克卡片 · " + name : "领克卡片未接受操作";
                if (sent) {
                    operation.start(slot.bindingKey(), item.category().title, item.state(), now);
                    operationNode = node;
                    main.removeCallbacks(operationExpiry);
                    main.postDelayed(operationExpiry, CardOperation.TIMEOUT);
                    cardReceipt = "";
                    followUp.start(node, identity, vehicle, slot.bindingKey(), slot.signature(), name, now);
                    try {
                        inspectCardPrompt();
                    } catch (RuntimeException unavailable) {
                        // The primary click was dispatched. Never retry an ambiguous provider prompt.
                        followUp.forget(node);
                        last = "已转交领克卡片 · " + name + "，请在手机核对";
                        android.util.Log.w("WristLynkHost", "Card prompt unavailable after click", unavailable);
                    }
                }
                out.put("note", last);
                // Rendering the phone screen may draw provider icons. Keep it off the reply path.
                main.post(() -> {
                    try { changed(); }
                    catch (RuntimeException unavailable) {
                        android.util.Log.w("WristLynkHost", "Could not redraw card after action", unavailable);
                    }
                });
            } else out.put("note", "确认已失效，请重新选择");
            return;
        }
        tickets.forget(node);
        out.put("note", "卡片按钮已变化，请重新同步");
    }
}
