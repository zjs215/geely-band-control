package com.otrong.carcontrol.phone;

import android.app.Application;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import com.otrong.carcontrol.appearance.ThemeConfig;
import com.otrong.carcontrol.appearance.ThemeStyles;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

public final class GarageApp extends Application {
    static final String DEFAULT_VEHICLE_NAME = "我的座驾";
    private static final String TAG = "GarageApp";
    private static final int CONFIRM_NOTICE = 2;
    private static final String CONFIRM_CHANNEL = "galaxy_confirmation";
    private static final int VOICE_CONFIRM_NOTICE = 3;
    private static final String VOICE_CONFIRM_CHANNEL = "voice_control_confirmation";
    private static final long VOICE_CONFIRM_TIMEOUT_MS = 30_000;
    private static final String DISPATCHED = "已发送命令";
    private static final String UNMAPPED = "请在手机核对银河小组件配置";
    private static final String VOICE_ENABLED = "miHomeVoiceEnabled";
    private static final String VEHICLE_NAME_KEY = "vehicleName";
    private static final String PHONE_THEME_KEY = "phoneTheme";
    private static final String WEARABLE_THEME_KEY = "wearableTheme";
    private static final String BAND_DISPLAY_REV = "bandDisplayRevision";
    private static final String BAND_DISPLAY_TWO_PAGE = "bandDisplayTwoPage";
    private static final String HAPTICS_ENABLED_KEY = "wearableHapticsEnabled";
    static final int MAX_VEHICLE_NAME_LENGTH = 10;
    private static final long CONTROL_WAIT_MS = 5_000;

    private enum ControlPause {
        NONE("", ""),
        DISPATCH("blocked", "正在同步银河车况"),
        PHONE_CONFIRM("phone_confirm", "请在手机确认银河操作"),
        WIDGET_UPDATE("blocked", "已处理，等待银河更新");

        final String status;
        final String message;

        ControlPause(String status, String message) {
            this.status = status;
            this.message = message;
        }
    }

    private record LynkApproval(LynkConfirmation expected, long revision, long expiresAt,
                                long authorization, boolean fromVoice) {}
    private record Dispatch(int cardId, CarAction action, String slot, String targetKey,
                            LynkApproval lynkApproval, String lynkFallback) {}
    private record DispatchResult(String state, String note) {}
    private record RequestKey(String node, String id) {}
    private record Receipt(BandMessage request, String state, String note) {}
    record VoiceConfirmation(String id, CarAction action, String targetKey, String title,
                             LynkConfirmation toggle, long expiresAt) {}
    record ConfirmationSettings(boolean wearable, boolean voice) {}

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Confirmation confirmations = new Confirmation();
    private final MiHomeVoice voiceNotifications = new MiHomeVoice();
    private final Map<RequestKey, Receipt> receipts = new LinkedHashMap<>();
    private final Runnable controlTimeout = this::finishControlObservation;
    private final Runnable voiceConfirmTimeout = this::expireVoiceConfirmation;
    private int widgetGeneration;
    BrandConfig brand = BrandConfig.GALAXY;
    GalaxyWidgets widgets;
    WearLink link;
    private SharedPreferences prefs;
    private Runnable observer;
    private boolean screenVisible;
    private boolean serviceRunning;
    private boolean voiceEnabled;
    private boolean voiceListenerConnected;
    private String vehicleName = DEFAULT_VEHICLE_NAME;
    private ThemeConfig phoneTheme = ThemeConfig.CLASSIC;
    private ThemeConfig wearableTheme = ThemeConfig.CLASSIC;
    private BandDisplay bandDisplay = BandDisplay.INITIAL;
    private boolean wearableHaptics = true;
    private int armedCardId = AppWidgetManager.INVALID_APPWIDGET_ID;
    private long lynkAuthorization;
    private Dispatch dispatch;
    private VoiceConfirmation voiceConfirmation;
    private boolean confirmationVisible;
    private int handledConfirmation = AppWidgetManager.INVALID_APPWIDGET_ID;
    record UiEvent(long time, String title, String detail, boolean warning) {}
    private final List<UiEvent> uiEvents = new ArrayList<>();
    private String observedLink = "";
    private String observedBlock = "";
    private String observedWindowBlock = "";
    private boolean loggedMissingCarImage;
    private final Map<String, Boolean> nodeThemeDay = new java.util.LinkedHashMap<>();
    private String lastResult = "";
    private String voiceTestResult = "";

    @Override public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("garage", MODE_PRIVATE);
        voiceEnabled = prefs.getBoolean(VOICE_ENABLED, false);
        vehicleName = normalizeVehicleName(prefs.getString(VEHICLE_NAME_KEY, DEFAULT_VEHICLE_NAME));
        ThemeConfig savedWearableTheme = ThemeConfig.parse(prefs.getString(WEARABLE_THEME_KEY, ""));
        if (savedWearableTheme != null) wearableTheme = savedWearableTheme;
        ThemeConfig savedPhoneTheme = ThemeConfig.parse(prefs.getString(PHONE_THEME_KEY, ""));
        if (savedPhoneTheme != null) {
            phoneTheme = savedPhoneTheme;
        } else if (!prefs.contains(PHONE_THEME_KEY)) {
            // The old shared choice was saved under the wearable key. Copy it once on upgrade.
            phoneTheme = wearableTheme;
            prefs.edit().putString(PHONE_THEME_KEY, phoneTheme.encode()).apply();
        }
        bandDisplay = new BandDisplay(prefs.getBoolean(BAND_DISPLAY_TWO_PAGE, false), prefs.getLong(BAND_DISPLAY_REV, 0));
        wearableHaptics = prefs.getBoolean(HAPTICS_ENABLED_KEY, true);
        try {
            JSONArray saved = new JSONArray(prefs.getString("uiEvents", "[]"));
            for (int i = Math.max(0, saved.length() - 40); i < saved.length(); i++) {
                JSONObject entry = saved.getJSONObject(i);
                uiEvents.add(new UiEvent(entry.getLong("time"), entry.getString("title"),
                        entry.optString("detail"), entry.optBoolean("warning")));
            }
        } catch (JSONException invalid) {
            uiEvents.clear();
        }
        brand = BrandConfig.fromId(prefs.getString("brand", "GALAXY"));
        if (!prefs.contains("brand") && prefs.contains("widgetCards")) prefs.edit().putString("brand", "GALAXY").apply();
        widgets = createWidgets();
        link = new WearLink(this, main, this::onBandMessage, confirmations::disconnect, this::changed, this::note);
    }

    private GalaxyWidgets createWidgets() {
        int generation = ++widgetGeneration;
        java.util.function.IntConsumer changed = id -> {
            if (generation == widgetGeneration) onWidgetChange(id);
        };
        return brand == BrandConfig.LYNKCO ? new LynkWidgets(this, prefs, main, changed)
                : new GalaxyWidgets(this, prefs, main, changed, brand);
    }

    boolean needsBrandSelection() { return !prefs.contains("brand"); }

    void selectBrand(BrandConfig selected) {
        prefs.edit().putString("brand", selected.brand.name()).apply();
        if (brand == selected) return;
        disarm();
        voiceEnabled = false;
        voiceTestResult = "";
        prefs.edit().putBoolean(VOICE_ENABLED, false).apply();
        widgets.cancelRefresh("品牌已切换，请重新读取车况");
        widgets.setListening(false);
        main.removeCallbacks(controlTimeout);
        dispatch = null;
        receipts.clear();
        handledConfirmation = AppWidgetManager.INVALID_APPWIDGET_ID;
        brand = selected;
        widgets = createWidgets();
        loggedMissingCarImage = false;
        lastResult = "品牌已切换，请核对或重新绑定" + brand.name + "小组件并重新授权";
        updateWidgetListening();
        updateConfirmationNotice();
        changed();
    }

    boolean isArmed() {
        GalaxyWidgets.Card card = widgets.primary();
        return card != null && armedCardId == card.id;
    }
    boolean hasService() { return serviceRunning; }
    String lastResult() { return lastResult; }
    boolean voiceEnabled() { return voiceEnabled; }
    boolean voiceListenerConnected() { return voiceListenerConnected; }
    String voiceTestResult() { return voiceTestResult; }
    String vehicleName() { return vehicleName; }

    private String confirmationKey(CarAction action, GalaxyWidgets.Target target) {
        String key = "confirm." + brand.brand.name() + "." + action.id;
        if (brand != BrandConfig.LYNKCO) return key;
        var button = target.button();
        String function = target.lockState();
        if (target.isLynkLock()) function = "车锁";
        else if (target.isLynkWindow()) function = "车窗";
        else if (target.isLynkTrunk()) function = "后备箱";
        else if (function.isEmpty()) {
            function = button.lynkIcon() == null ? button.fingerprint() : button.lynkIcon().category;
        }
        return key + "." + button.slot() + "." + function;
    }

    ConfirmationSettings confirmationSettings(CarAction action, GalaxyWidgets.Target target) {
        String key = confirmationKey(action, target);
        return new ConfirmationSettings(prefs.getBoolean(key + ".wearable", true), prefs.getBoolean(key + ".voice", false));
    }

    void setConfirmationSettings(CarAction action, GalaxyWidgets.Target shown, ConfirmationSettings settings) {
        GalaxyWidgets.Control current = control(action);
        if (current == null || current.target() == null || !current.target().key().equals(shown.key())) {
            setResult("车控按钮已变化，请重新打开确认设置");
            return;
        }
        String key = confirmationKey(action, shown);
        if (confirmationSettings(action, shown).equals(settings)) return;
        prefs.edit().putBoolean(key + ".wearable", settings.wearable())
                .putBoolean(key + ".voice", settings.voice()).apply();
        confirmations.clear();
        clearVoiceConfirmation();
        note("车控确认设置已保存", shown.title(), false);
    }

    ThemeConfig phoneTheme() { return phoneTheme; }

    void setPhoneTheme(ThemeConfig theme) {
        if (phoneTheme.equals(theme)) return;
        phoneTheme = theme;
        prefs.edit().putString(PHONE_THEME_KEY, theme.encode()).apply();
    }

    ThemeConfig wearableTheme() { return wearableTheme; }

    void setWearableTheme(ThemeConfig theme) {
        if (wearableTheme.equals(theme)) return;
        wearableTheme = theme;
        prefs.edit().putString(WEARABLE_THEME_KEY, theme.encode()).apply();
    }

    BandDisplay bandDisplay() { return bandDisplay; }

    void selectBandDisplay(boolean twoPage) {
        saveBandDisplay(bandDisplay.select(twoPage));
    }

    private void saveBandDisplay(BandDisplay next) {
        if (next.equals(bandDisplay)) return;
        bandDisplay = next;
        prefs.edit().putBoolean(BAND_DISPLAY_TWO_PAGE, next.twoPage()).putLong(BAND_DISPLAY_REV, next.revision()).apply();
        changed();
    }

    boolean wearableHaptics() { return wearableHaptics; }

    void setWearableHaptics(boolean enabled) {
        if (wearableHaptics == enabled) return;
        wearableHaptics = enabled;
        prefs.edit().putBoolean(HAPTICS_ENABLED_KEY, enabled).apply();
        changed();
    }

    static String normalizeVehicleName(String raw) {
        if (raw == null) return DEFAULT_VEHICLE_NAME;
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return DEFAULT_VEHICLE_NAME;
        return trimmed.codePointCount(0, trimmed.length()) > MAX_VEHICLE_NAME_LENGTH
                ? trimmed.substring(0, trimmed.offsetByCodePoints(0, MAX_VEHICLE_NAME_LENGTH)).trim()
                : trimmed;
    }

    void setVehicleName(String raw) {
        String next = normalizeVehicleName(raw);
        if (next.equals(vehicleName)) return;
        vehicleName = next;
        prefs.edit().putString(VEHICLE_NAME_KEY, next).apply();
        appendEvent("车辆名称已更新", next, false);
        changed();
    }

    List<UiEvent> uiEvents() { return List.copyOf(uiEvents); }

    String exportDiagnostics() {
        String model = (Build.MANUFACTURER + " " + Build.MODEL).trim();
        return WearDiagnostics.formatDiagnosticExport(
                BuildConfig.VERSION_NAME,
                Build.VERSION.SDK_INT,
                model,
                link.diagnosticSummary(),
                serviceRunning,
                link.status(),
                link.canAuthorize(),
                link.isAuthorizing(),
                uiEvents);
    }

    void clearUiEvents() {
        uiEvents.clear();
        saveUiEvents();
        changed();
    }

    void note(String title, String detail, boolean warning) {
        appendEvent(title, detail, warning);
        changed();
    }

    private void appendEvent(String title, String detail, boolean warning) {
        uiEvents.add(new UiEvent(System.currentTimeMillis(), title, detail, warning));
        if (uiEvents.size() > 40) uiEvents.remove(0);
        saveUiEvents();
    }

    private void saveUiEvents() {
        JSONArray saved = new JSONArray();
        try {
            for (UiEvent entry : uiEvents) saved.put(new JSONObject().put("time", entry.time())
                    .put("title", entry.title()).put("detail", entry.detail()).put("warning", entry.warning()));
            prefs.edit().putString("uiEvents", saved.toString()).apply();
        } catch (JSONException invalid) {
            Log.w(TAG, "Unable to save UI events", invalid);
        }
    }

    void setVoiceEnabled(boolean enabled) {
        if (!enabled) {
            lynkAuthorization++;
            clearVoiceConfirmation();
        }
        voiceEnabled = enabled;
        prefs.edit().putBoolean(VOICE_ENABLED, enabled).apply();
        appendEvent(enabled ? "语音直控已开启" : "语音直控已关闭", "米家语音", false);
        updateWidgetListening();
        changed();
    }

    void setVoiceListenerConnected(boolean connected) {
        if (voiceListenerConnected == connected) return;
        voiceListenerConnected = connected;
        if (!connected) clearVoiceConfirmation();
        appendEvent(connected ? "通知监听已连接" : "通知监听已断开", "米家语音", !connected);
        updateWidgetListening();
        changed();
    }

    void setScreen(boolean visible, Runnable observer) {
        screenVisible = visible;
        this.observer = observer;
        updateWidgetListening();
    }

    void setService(boolean running) {
        serviceRunning = running;
        if (running) {
            link.start();
        } else {
            widgets.cancelRefresh("穿戴通信已停止");
            finishControlObservation();
            link.stop();
        }
        updateWidgetListening();
        changed();
    }

    private void updateWidgetListening() {
        widgets.setListening(ControlPolicy.shouldListenWidgets(
                screenVisible, serviceRunning, voiceEnabled, isArmed()));
    }

    void arm() {
        GalaxyWidgets.Card card = widgets.primary();
        boolean hasReady = card != null && widgets.controls().values().stream()
                .anyMatch(GalaxyWidgets.Control::ready);
        if (ControlPolicy.canArm(card != null, hasReady)) {
            armedCardId = card.id;
            appendEvent("车控授权已开启", "当前" + brand.name + "中号卡片", false);
            updateWidgetListening();
            changed();
        } else {
            setResult("请先核对" + brand.name + "卡片按钮和车况");
        }
    }

    void disarm() {
        confirmations.clear();
        clearVoiceConfirmation();
        lynkAuthorization++;
        if (armedCardId == AppWidgetManager.INVALID_APPWIDGET_ID) return;
        armedCardId = AppWidgetManager.INVALID_APPWIDGET_ID;
        appendEvent("车控授权已关闭", "车控与语音直控", false);
        updateWidgetListening();
        changed();
    }

    void refreshOnPhone() {
        ControlPause pause = controlPause();
        if (pause == ControlPause.DISPATCH || pause == ControlPause.WIDGET_UPDATE) return;
        refresh((state, note) -> setResult(note));
    }

    private void changed() {
        if (link != null && !observedLink.equals(link.status())) {
            observedLink = link.status();
            appendEvent("手环通信", observedLink, WearDiagnostics.isWarningStatus(observedLink));
        }
        if (widgets != null) {
            Map<CarAction, GalaxyWidgets.Control> current = widgets.controls();
            GalaxyWidgets.Control lock = current.get(CarAction.LOCK);
            String block = isArmed() && lock != null && !isTransientBlock(lock) ? lock.blockedReason() : "";
            observedBlock = recordObservedBlock("车锁暂不可用", block, observedBlock);

            String windowBlock = "";
            if (isArmed()) {
                for (Map.Entry<CarAction, GalaxyWidgets.Control> entry : current.entrySet()) {
                    GalaxyWidgets.Control item = entry.getValue();
                    if (entry.getKey().isWindow() && !item.blockedReason().isEmpty() && !isTransientBlock(item)) {
                        windowBlock = entry.getKey().label + ": " + item.blockedReason();
                        break;
                    }
                }
            }
            observedWindowBlock = recordObservedBlock("车窗暂不可用", windowBlock, observedWindowBlock);
        }
        if (observer != null) observer.run();
    }

    private static boolean isTransientBlock(GalaxyWidgets.Control control) {
        return ControlPolicy.isTransientWidgetBlock(control.slotState(), control.blockedReason());
    }

    private String recordObservedBlock(String title, String block, String previous) {
        if (!block.equals(previous) && !block.isEmpty()) {
            appendEvent(title, block + " · 车辆状态「" + widgets.vehicle().motion() + "」", true);
        }
        return block;
    }

    private void onWidgetChange(int id) {
        if (brand == BrandConfig.LYNKCO) confirmations.clear();
        GalaxyWidgets.Card card = widgets.primary();
        if (brand.recognizedIconsOnly() && card != null && id == card.id
                && (widgets.showsSetup(card) || widgets.showsUnavailable(card))) disarm();
        if (armedCardId != AppWidgetManager.INVALID_APPWIDGET_ID && !isArmed()) {
            armedCardId = AppWidgetManager.INVALID_APPWIDGET_ID;
            confirmations.clear();
            lynkAuthorization++;
            updateWidgetListening();
        }
        if (voiceConfirmation != null && !voiceTargetMatches(voiceConfirmation, control(voiceConfirmation.action()))) {
            clearVoiceConfirmation();
            appendEvent("米家语音确认已取消", "车控按钮已变化", true);
        }
        if (handledConfirmation == id && !widgets.hasOfficialConfirmation(id)) {
            handledConfirmation = AppWidgetManager.INVALID_APPWIDGET_ID;
        }
        if (dispatch != null && dispatch.cardId() == id) {
            GalaxyWidgets.Control updated = control(dispatch.action());
            if (widgets.hasOfficialConfirmation(id) || targetChanged(updated, dispatch)) {
                finishControlObservation();
            }
        }
        updateConfirmationNotice();
        changed();
    }

    private void setResult(String value) {
        lastResult = value;
        boolean warning = value.contains("失败") || value.contains("未知") || value.contains("超时")
                || value.contains("请") || value.contains("不可") || value.contains("失效");
        String message = value.replace("已发送命令", "指令已发送");
        int separator = message.indexOf(" · ");
        if (separator > 0) appendEvent(message.substring(separator + 3), message.substring(0, separator), warning);
        else appendEvent(message, "", warning);
        changed();
    }

    void onVoiceNotification(String key, long postedAt, MiHomeVoice.Match match) {
        if (!voiceEnabled) return;
        MiHomeVoice.Command command = match.command();
        if (!MiHomeVoice.enabled(brand.namespace, command)) return;
        String id = Long.toString(postedAt, 36) + "-" + Integer.toUnsignedString(key.hashCode(), 36);
        MiHomeVoice.Admission admission = voiceNotifications.receive(key, postedAt,
                System.currentTimeMillis(), SystemClock.elapsedRealtime(), command);
        if (admission != MiHomeVoice.Admission.ACCEPTED) {
            Log.i(TAG, "event=voice_notification_ignored id=" + id + " reason=" + admission);
            return;
        }
        Log.i(TAG, "event=voice_notification_received id=" + id + " command=" + command
                + " field=" + match.field());
        appendEvent("米家通知已接收", "已匹配「" + command.label + "」 · " + match.field(), false);
        if (command == MiHomeVoice.Command.TEST) {
            voiceTestResult = "已收到测试通知（" + match.field() + "）";
            appendEvent("通知测试通过", "已收到测试通知 · " + match.field(), false);
            changed();
            return;
        }
        LynkConfirmation toggle = voiceToggle(command);
        CarAction action;
        GalaxyWidgets.Control control;
        if (toggle != null) {
            String function = toggle.label.substring("切换".length());
            Map.Entry<CarAction, GalaxyWidgets.Control> selected = null;
            for (var entry : widgets.controls().entrySet()) {
                GalaxyWidgets.Target target = entry.getValue().target();
                if (target == null || !matchesLynkToggle(target, toggle)) continue;
                if (selected != null) {
                    setResult("语音" + toggle.label + " · 官方卡片有多个" + function + "按钮，请在穿戴端选择");
                    return;
                }
                selected = entry;
            }
            if (selected == null) {
                String notice = liveControlNotice(controlPause(), false);
                setResult("语音" + toggle.label + " · " + (notice.isEmpty() ? "未识别到可用的领克" + function + "按钮" : notice));
                return;
            }
            action = selected.getKey();
            control = selected.getValue();
        } else {
            action = MiHomeVoice.action(brand.namespace, command);
            control = control(action);
        }
        String blocked = voiceBlockedReason(control);
        if (blocked.isEmpty()) {
            if (toggle != null && !widgets.hasValidReading(control.target().cardId())) {
                blocked = "当前卡片状态不可读，请先刷新领克车况";
            } else if (toggle == null) {
                blocked = command.directionBlock(control.target().direction());
            }
        }
        if (voiceConfirmation != null && pendingVoiceConfirmation() == null) clearVoiceConfirmation();
        DispatchResult result;
        if (!blocked.isEmpty()) {
            result = new DispatchResult("blocked", blocked);
        } else if (voiceConfirmation != null) {
            result = new DispatchResult("blocked", "已有语音指令等待手机确认");
        } else if (confirmationSettings(action, control.target()).voice()) {
            requestVoiceConfirmation(id, action, control.target(), command.label, toggle);
            result = new DispatchResult("confirm", "请在手机确认米家语音操作");
        } else {
            result = dispatchControl(action, control.target(), true, toggle);
        }
        Log.i(TAG, "event=dispatch_result id=" + id + " type=voice action=" + action.id
                + " command=" + command + " result=" + result.state());
        setResult("语音" + command.label + " · " + result.note());
    }

    private LynkConfirmation voiceToggle(MiHomeVoice.Command command) {
        if (brand != BrandConfig.LYNKCO) return null;
        return switch (command) {
            case WINDOW_TOGGLE -> LynkConfirmation.WINDOW_TOGGLE;
            case LOCK_TOGGLE -> LynkConfirmation.LOCK_TOGGLE;
            case TRUNK_TOGGLE -> LynkConfirmation.TRUNK_TOGGLE;
            default -> null;
        };
    }

    boolean hasVoiceCommand(CarAction action, GalaxyWidgets.Target target) {
        for (MiHomeVoice.Command command : MiHomeVoice.commands(brand.namespace)) {
            if (command == MiHomeVoice.Command.TEST) continue;
            LynkConfirmation toggle = voiceToggle(command);
            if (toggle == null ? action == MiHomeVoice.action(brand.namespace, command) : matchesLynkToggle(target, toggle)) {
                return true;
            }
        }
        return false;
    }

    VoiceConfirmation pendingVoiceConfirmation() {
        return voiceConfirmation != null && SystemClock.elapsedRealtime() < voiceConfirmation.expiresAt()
                ? voiceConfirmation : null;
    }

    private void requestVoiceConfirmation(String id, CarAction action, GalaxyWidgets.Target target, String title,
                                          LynkConfirmation toggle) {
        voiceConfirmation = new VoiceConfirmation(id, action, target.key(), title, toggle,
                SystemClock.elapsedRealtime() + VOICE_CONFIRM_TIMEOUT_MS);
        main.postDelayed(voiceConfirmTimeout, VOICE_CONFIRM_TIMEOUT_MS);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (!manager.areNotificationsEnabled()) return;
        manager.createNotificationChannel(new NotificationChannel(VOICE_CONFIRM_CHANNEL, "米家语音车控确认",
                NotificationManager.IMPORTANCE_HIGH));
        Intent open = new Intent(this, MainActivity.class).setAction(MainActivity.SHOW_VOICE_CONFIRMATION)
                .setData(android.net.Uri.fromParts("carcontrol-voice", id, null))
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent intent = PendingIntent.getActivity(this, VOICE_CONFIRM_NOTICE, open, PendingIntent.FLAG_IMMUTABLE);
        manager.notify(VOICE_CONFIRM_NOTICE, new Notification.Builder(this, VOICE_CONFIRM_CHANNEL)
                .setSmallIcon(R.drawable.ic_garage).setContentTitle("确认米家语音操作")
                .setContentText(brand.name + " · " + title).setContentIntent(intent)
                .setVisibility(Notification.VISIBILITY_PRIVATE).setAutoCancel(true)
                .setTimeoutAfter(VOICE_CONFIRM_TIMEOUT_MS).build());
    }

    private void clearVoiceConfirmation() {
        if (voiceConfirmation == null) return;
        voiceConfirmation = null;
        main.removeCallbacks(voiceConfirmTimeout);
        getSystemService(NotificationManager.class).cancel(VOICE_CONFIRM_NOTICE);
    }

    private void expireVoiceConfirmation() {
        clearVoiceConfirmation();
        setResult("米家语音确认已超时");
    }

    private static boolean voiceTargetMatches(VoiceConfirmation pending, GalaxyWidgets.Control control) {
        return control != null && control.target() != null && pending.targetKey().equals(control.target().key());
    }

    void respondToVoiceConfirmation(VoiceConfirmation pending, boolean accept) {
        if (!pending.equals(pendingVoiceConfirmation())) {
            setResult("米家语音确认已失效，请重新触发");
            return;
        }
        clearVoiceConfirmation();
        if (!accept) {
            setResult("已取消米家语音操作");
            return;
        }
        GalaxyWidgets.Control control = control(pending.action());
        String blocked = voiceBlockedReason(control);
        if (!voiceEnabled || !voiceTargetMatches(pending, control)) blocked = "车控授权或按钮已变化";
        else if (pending.toggle() != null && !widgets.hasValidReading(control.target().cardId())) {
            blocked = "当前卡片状态不可读，请先刷新领克车况";
        }
        DispatchResult result = blocked.isEmpty() ? dispatchControl(pending.action(), control.target(), true, pending.toggle())
                : new DispatchResult("blocked", blocked);
        Log.i(TAG, "event=dispatch_result id=" + pending.id() + " type=voice_confirmation action="
                + pending.action().id + " result=" + result.state());
        setResult("语音" + pending.title() + " · " + result.note());
    }

    private static boolean matchesLynkToggle(GalaxyWidgets.Target target, LynkConfirmation toggle) {
        return switch (toggle) {
            case WINDOW_TOGGLE -> target.isLynkWindow();
            case LOCK_TOGGLE -> target.isLynkLock();
            case TRUNK_TOGGLE -> target.isLynkTrunk();
            default -> false;
        };
    }

    private void onBandMessage(String node, byte[] data) {
        try {
            BandMessage request = BandMessage.decode(data);
            if (request == null) return;
            Log.i(TAG, "event=request_received" + requestFields(request));
            switch (request.type()) {
                case "hello" -> send(node, request.id(), () -> {
                    JSONObject hello = BandMessage.helloReply(request.id(), BuildConfig.VERSION_NAME)
                            .put("brand", brand.brand.name())
                            .put("haptics", wearableHaptics);
                    hello.getJSONArray("features").put(BandMessage.COMPACT_EXECUTE_ACK);
                    return hello;
                });
                case "status" -> {
                    if (request.themeDay() != null) nodeThemeDay.put(node, request.themeDay());
                    if (!BluetoothWatchLink.handlesNode(node)) saveBandDisplay(bandDisplay.accept(request.bandDisplay()));
                    reply(node, request, "ready", "已读取手机车况");
                }
                case "refresh" -> refresh((state, note) -> reply(node, request, state, note));
                case "prepare" -> prepare(node, request);
                case "execute" -> execute(node, request);
                case "cancel" -> confirmations.cancel(node, request.token());
                case "car_image" -> sendCarImageChunk(node, request);
                case "control_icon" -> sendControlIconChunk(node, request);
            }
        } catch (JSONException invalid) {
            Log.w(TAG, "Invalid band message");
        }
    }

    private void prepare(String node, BandMessage request) {
        if (request.brand().isEmpty() && brand.recognizedIconsOnly()) {
            reply(node, request, "blocked", "请更新穿戴端后使用" + brand.name + "车控");
            return;
        }
        if (!request.brand().isEmpty() && !brand.brand.name().equals(request.brand())) {
            reply(node, request, "blocked", "车辆品牌已切换，请核对车况后重新选择");
            return;
        }
        if (replyIfControlPending(node, request)) return;
        GalaxyWidgets.Control control = control(request.action());
        String blocked = wearBlockedReason(control);
        if (!blocked.isEmpty()) {
            if (request.action() == CarAction.LOCK) {
                appendEvent("车锁准备被拦截", blocked + " · 车辆状态「" + widgets.vehicle().motion() + "」", true);
            }
            reply(node, request, "blocked", blocked);
            return;
        }
        GalaxyWidgets.Target target = control.target();
        if (brand != BrandConfig.LYNKCO && request.direction() == null && target.direction() == ControlDirection.CLOSE) {
            reply(node, request, "blocked", "请更新手环端后使用「" + target.title() + "」");
            return;
        }
        if (request.direction() != null && request.direction() != target.direction()) {
            reply(node, request, "blocked", "按钮状态已变化，请重新选择");
            return;
        }
        String token = confirmations.prepare(node, request.action(), target.key(), SystemClock.elapsedRealtime());
        LynkConfirmation known = lynkConfirmation(target);
        String title = known == null ? target.title() : "领克 · " + known.label;
        boolean confirmationRequired = confirmationSettings(request.action(), target).wearable();
        send(node, request.id(), () -> BandMessage.reply(request.id(), "confirm", title)
                .put("token", token).put("action", request.action().id)
                .put("confirmationRequired", confirmationRequired));
    }

    private void execute(String node, BandMessage request) {
        Receipt previous = receipts.get(new RequestKey(node, request.id()));
        if (previous != null) {
            if (previous.request().equals(request)) {
                Log.i(TAG, "event=receipt_replayed" + requestFields(request) + " result=" + previous.state());
                reply(node, request, previous.state(), previous.note());
            } else {
                dispatchReply(node, request, "blocked", "请求已失效，请重新选择");
            }
            return;
        }
        if (replyIfControlPending(node, request)) return;
        GalaxyWidgets.Control control = control(request.action());
        String blocked = wearBlockedReason(control);
        GalaxyWidgets.Target target = blocked.isEmpty() ? control.target() : null;
        String key = target == null ? "" : target.key();
        boolean confirmed = confirmations.consume(node, request.action(), request.token(), key, SystemClock.elapsedRealtime());
        if (!blocked.isEmpty()) {
            dispatchReply(node, request, "blocked", blocked);
            return;
        }
        if (!confirmed) {
            dispatchReply(node, request, "blocked", "确认已失效或按钮已变化，请重新选择");
            return;
        }
        DispatchResult result = dispatchControl(request.action(), target, false, lynkConfirmation(target));
        receipt(node, request, result.state(), result.note());
    }

    private DispatchResult dispatchControl(CarAction action, GalaxyWidgets.Target target, boolean fromVoice,
                                           LynkConfirmation expected) {
        if (action == CarAction.LOCK) {
            appendEvent("准备" + target.title(), "车辆状态「" + widgets.vehicle().motion()
                    + "」 · 锁态「" + target.lockState() + "」", false);
        }
        LynkApproval approval = null;
        String lynkFallback = "";
        if (brand == BrandConfig.LYNKCO) {
            long revision = widgets.revision(widgets.primary());
            if (widgets.hasOfficialConfirmation(target.cardId()) || revision < 0) {
                lynkFallback = "派发前卡片已经在确认中或不可读";
            } else if (!widgets.hasValidReading(target.cardId())) {
                lynkFallback = "本次派发前没有读到有效的领克车况";
            } else if (expected == null) {
                lynkFallback = "当前槽位未识别为可切换的车锁、车窗或后备箱，也没有已核对的动作方向";
            } else {
                approval = new LynkApproval(expected, revision, SystemClock.elapsedRealtime() + CONTROL_WAIT_MS,
                        lynkAuthorization, fromVoice);
            }
        }
        try {
            if (!target.button().view().performClick()) {
                return new DispatchResult("blocked", "官方按钮当前不可点击");
            }
        } catch (RuntimeException unavailable) {
            return new DispatchResult("unknown", "转交结果未知，请到" + brand.appName + " App 查看");
        }
        dispatch = new Dispatch(target.cardId(), action, target.button().slot(), target.key(), approval, lynkFallback);
        confirmations.clear();
        clearVoiceConfirmation();
        main.postDelayed(controlTimeout, CONTROL_WAIT_MS);
        return new DispatchResult("dispatched", DISPATCHED);
    }

    private LynkConfirmation lynkConfirmation(GalaxyWidgets.Target target) {
        if (brand != BrandConfig.LYNKCO || !widgets.hasValidReading(target.cardId())) return null;
        LynkConfirmation marked = LynkConfirmation.forMark(target.lockState(), target.direction());
        if (marked == null) {
            if (target.isLynkWindow()) return LynkConfirmation.WINDOW_TOGGLE;
            if (target.isLynkLock()) return LynkConfirmation.LOCK_TOGGLE;
            if (target.isLynkTrunk()) return LynkConfirmation.TRUNK_TOGGLE;
        }
        return marked;
    }

    private boolean replyIfControlPending(String node, BandMessage request) {
        ControlPause pause = controlPause();
        if (pause == ControlPause.NONE) return false;
        String note = pause.message.replace("银河", brand.name);
        if ("execute".equals(request.type())) dispatchReply(node, request, pause.status, note);
        else reply(node, request, pause.status, note);
        return true;
    }

    private void finishControlObservation() {
        if (dispatch == null) return;
        Dispatch finished = dispatch;
        dispatch = null;
        main.removeCallbacks(controlTimeout);
        if (widgets.hasOfficialConfirmation(finished.cardId())) {
            if (brand == BrandConfig.LYNKCO) {
                String fallback = confirmLynkDispatch(finished);
                if (fallback.isEmpty()) {
                    setResult("已提交领克官方确认，等待车况回传");
                    return;
                }
                appendEvent("领克仍需手机确认", fallback, true);
            }
            setResult(ControlPause.PHONE_CONFIRM.message.replace("银河", brand.name));
        } else {
            changed();
        }
    }

    /** An empty result means the matching official confirmation was submitted. */
    private String confirmLynkDispatch(Dispatch finished) {
        LynkApproval approval = finished.lynkApproval();
        if (approval == null) {
            GalaxyWidgets.Card card = widgets.primary();
            WidgetTree.OfficialConfirmation prompt = card != null && card.id == finished.cardId()
                    ? widgets.officialConfirmation(card) : null;
            return prompt == null ? finished.lynkFallback()
                    : finished.lynkFallback() + "，官方弹窗为「" + prompt.title() + "」";
        }
        if (!isArmed() || approval.authorization() != lynkAuthorization
                || (approval.fromVoice() ? !voiceEnabled : !serviceRunning)) return "车控授权或连接已变化";
        if (SystemClock.elapsedRealtime() >= approval.expiresAt()) return "官方确认超过 5 秒";
        GalaxyWidgets.Card card = widgets.primary();
        if (card == null || card.id != finished.cardId() || card.id == handledConfirmation
                || widgets.revision(card) <= approval.revision()) return "卡片已切换，或弹窗不是本次派发后出现";
        WidgetTree.OfficialConfirmation prompt = widgets.officialConfirmation(card);
        if (prompt == null) return "未读到可点击的官方确认按钮";
        if (!approval.expected().matches(prompt.title())) {
            return "已核对「" + approval.expected().label + "」，官方弹窗为「" + prompt.title() + "」";
        }
        try {
            if (!prompt.accept().performClick()) return "官方确认按钮当前不可点击";
            handledConfirmation = card.id;
            updateConfirmationNotice();
            return "";
        } catch (RuntimeException unavailable) {
            Log.w(TAG, "Unable to submit matched Lynk confirmation", unavailable);
            return "提交官方确认时出现异常";
        }
    }

    private static boolean targetChanged(GalaxyWidgets.Control updated, Dispatch dispatched) {
        if (updated == null || !updated.ready()) return false;
        GalaxyWidgets.Target target = updated.target();
        return target.cardId() == dispatched.cardId() && target.button().slot().equals(dispatched.slot())
                && !target.key().equals(dispatched.targetKey());
    }

    private void receipt(String node, BandMessage request, String state, String note) {
        receipts.put(new RequestKey(node, request.id()), new Receipt(request, state, note));
        if (receipts.size() > 24) receipts.remove(receipts.keySet().iterator().next());
        setResult(note);
        dispatchReply(node, request, state, note);
    }

    private void dispatchReply(String node, BandMessage request, String state, String note) {
        String result = "phone_confirm".equals(state) ? "blocked" : state;
        Log.i(TAG, "event=dispatch_result" + requestFields(request) + " result=" + result);
        reply(node, request, state, note);
    }

    private static String requestFields(BandMessage request) {
        String action = request.action() == null ? "-" : request.action().id;
        return " id=" + request.id() + " type=" + request.type() + " action=" + action;
    }

    void respondOnPhone(GalaxyWidgets.Card card, WidgetTree.OfficialConfirmation shown, long revision, boolean accept) {
        WidgetTree.OfficialConfirmation current = widgets.officialConfirmation(card);
        if (current == null || widgets.revision(card) != revision || !current.equals(shown)) {
            setResult("官方确认已变化，请重新打开卡片核对");
            return;
        }
        if (handledConfirmation == card.id) {
            setResult("这次官方确认已处理");
            return;
        }
        try {
            boolean clicked = (accept ? current.accept() : current.cancel()).performClick();
            if (clicked) handledConfirmation = card.id;
            updateConfirmationNotice();
            setResult(clicked ? "已点击" + brand.name + "的" + (accept ? "确认" : "取消") : "官方确认按钮当前不可点击");
        } catch (RuntimeException unavailable) {
            setResult("操作结果未知，请打开" + brand.appName + " App 查看");
        }
    }

    private void updateConfirmationNotice() {
        boolean visible = controlPause() == ControlPause.PHONE_CONFIRM;
        if (visible == confirmationVisible) return;
        confirmationVisible = visible;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (!visible) {
            manager.cancel(CONFIRM_NOTICE);
            return;
        }
        if (!manager.areNotificationsEnabled()) return;
        manager.createNotificationChannel(new NotificationChannel(CONFIRM_CHANNEL, "车控官方确认",
                NotificationManager.IMPORTANCE_DEFAULT));
        Intent open = new Intent(this, MainActivity.class).setAction(MainActivity.SHOW_CONFIRMATION)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openNotification = PendingIntent.getActivity(this, CONFIRM_NOTICE, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        manager.notify(CONFIRM_NOTICE, new Notification.Builder(this, CONFIRM_CHANNEL)
                .setSmallIcon(R.drawable.ic_garage)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(ControlPause.PHONE_CONFIRM.message.replace("银河", brand.name))
                .setContentIntent(openNotification)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setAutoCancel(true)
                .build());
    }

    GalaxyWidgets.Card phoneConfirmationCard() {
        return widgets.confirmationCard(handledConfirmation);
    }

    private ControlPause controlPause() {
        GalaxyWidgets.Card card = widgets.primary();
        if (card != null && widgets.hasOfficialConfirmation(card.id)) {
            return card.id == handledConfirmation ? ControlPause.WIDGET_UPDATE : ControlPause.PHONE_CONFIRM;
        }
        return dispatch == null ? ControlPause.NONE : ControlPause.DISPATCH;
    }

    private String liveControlNotice(ControlPause pause, boolean requireWearService) {
        return ControlPolicy.liveControlNotice(
                pause == ControlPause.NONE ? "" : pause.message.replace("银河", brand.name),
                requireWearService,
                serviceRunning,
                isArmed());
    }

    private GalaxyWidgets.Control control(CarAction action) {
        return widgets.controls().get(action);
    }

    private String wearBlockedReason(GalaxyWidgets.Control control) {
        if (control == null) return UNMAPPED.replace("银河", brand.name);
        String notice = liveControlNotice(controlPause(), true);
        return notice.isEmpty() ? control.blockedReason() : notice;
    }

    private String voiceBlockedReason(GalaxyWidgets.Control control) {
        if (control == null) return UNMAPPED.replace("银河", brand.name);
        String notice = liveControlNotice(controlPause(), false);
        return notice.isEmpty() ? control.blockedReason() : notice;
    }

    private void refresh(GalaxyWidgets.RefreshResult result) {
        if (dispatch != null) {
            result.accept("cached", ControlPause.DISPATCH.message);
            return;
        }
        widgets.refresh(result);
    }

    private void reply(String node, BandMessage request, String state, String note) {
        if ("prepare".equals(request.type()) && !"confirm".equals(state)) {
            note("操作暂不可用", request.action().label + " · " + note, true);
        }
        if ("refresh".equals(request.type())) {
            note(note, "手环刷新", !"refreshed".equals(state));
        }
        if ("execute".equals(request.type()) && request.compactAck()) {
            sendReply(node, request.id(), () -> BandMessage.executeAck(request.id(), state, note));
        } else {
            send(node, request.id(), () -> BandMessage.reply(request.id(), state, note));
        }
    }

    private void sendCarImageChunk(String node, BandMessage request) {
        CarImage.Packaged image = widgets.carImage();
        String hash = request.imageHash();
        int index = request.imageIndex();
        if (index == 0 && image != null && image.hash().equals(hash)) {
            appendEvent("同步车辆图片", "向穿戴端发送官方卡片车图（" + image.chunks().size() + " 片）", false);
        }
        sendPackagedChunk(node, request, image, false);
    }

    private void sendControlIconChunk(String node, BandMessage request) {
        sendPackagedChunk(node, request, widgets.controlIcon(request.imageHash()), true);
    }

    private void sendPackagedChunk(String node, BandMessage request, CarImage.Packaged packaged, boolean controlIcon) {
        String hash = request.imageHash();
        int index = request.imageIndex();
        String label = controlIcon ? "control_icon" : "car_image";
        String text = BandMessage.encodeReply(request.id(), () -> {
            if (packaged == null || !packaged.hash().equals(hash) || index < 0 || index >= packaged.chunks().size()) {
                return controlIcon ? BandMessage.controlIconReply(request.id(), hash, index, 0, "")
                        : BandMessage.carImageReply(request.id(), hash, index, 0, "");
            }
            return controlIcon
                    ? BandMessage.controlIconReply(request.id(), hash, index, packaged.chunks().size(), packaged.chunks().get(index))
                    : BandMessage.carImageReply(request.id(), hash, index, packaged.chunks().size(), packaged.chunks().get(index));
        }, error -> Log.e(TAG, "event=" + label + "_reply_failed id=" + request.id(), error));
        link.send(node, text, reason -> Log.i(TAG, "event=" + label + "_send_failed id=" + request.id() + " reason=" + reason));
    }

    private void send(String node, String id, BandMessage.ReplyBuilder builder) {
        Boolean themeDay = nodeThemeDay.get(node);
        sendReply(node, id, () -> {
            JSONObject response = withState(builder.build(), themeDay);
            if (!BluetoothWatchLink.handlesNode(node)) response.put("bandDisplay", bandDisplay.json());
            return response;
        });
    }

    private void sendReply(String node, String id, BandMessage.ReplyBuilder builder) {
        String text = BandMessage.encodeReply(id, builder,
                error -> Log.e(TAG, "event=reply_state_failed id=" + id, error));
        link.send(node, text,
                reason -> {
                    Log.i(TAG, "event=reply_send_failed id=" + id + " reason=" + reason);
                    note("手环回复发送失败", "请求 " + id + " · " + reason, true);
                });
    }

    private JSONObject withState(JSONObject response, Boolean themeDay) throws JSONException {
        JSONArray available = new JSONArray();
        JSONArray mapped = new JSONArray();
        response.put("mode", "live")
                .put("vehicleName", vehicleName)
                .put("haptics", wearableHaptics)
                .put("theme", wearableTheme.forWearableSync(ThemeStyles.systemVariant(this)).json())
                .put("brand", brand.brand.name()).put("available", available).put("mapped", mapped).put("vehicle", widgets.vehicle().json());

        ControlPause pause = controlPause();
        String notice = liveControlNotice(pause, true);
        JSONObject blocked = new JSONObject();
        JSONObject directions = new JSONObject();
        JSONObject labels = new JSONObject();
        JSONObject states = new JSONObject();
        Map<CarAction, GalaxyWidgets.Control> controls = widgets.controls();
        for (Map.Entry<CarAction, GalaxyWidgets.Control> entry : controls.entrySet()) {
            String id = entry.getKey().id;
            GalaxyWidgets.Control control = entry.getValue();
            mapped.put(id);
            if (!control.slotLabel().isEmpty()) labels.put(id, control.slotLabel());
            if (!control.slotState().isEmpty()) states.put(id, control.slotState());
            if (control.ready()) {
                GalaxyWidgets.Target target = control.target();
                if (entry.getKey() == CarAction.LOCK) response.put("lockState", target.lockState());
                if (target.direction() != null) directions.put(id, target.direction().id);
            }
            if (!notice.isEmpty()) continue;
            if (control.ready()) available.put(id);
            else blocked.put(id, control.blockedReason());
        }
        if (!notice.isEmpty()) response.put("controlNotice", notice);
        if (blocked.length() > 0) response.put("blocked", blocked);
        if (brand == BrandConfig.LYNKCO) response.put("controlLabels", labels);
        if (states.length() > 0) response.put("controlStates", states);
        Map<String, CarImage.Packaged> icons = widgets.controlIcons(controls, themeDay);
        if (!icons.isEmpty()) {
            JSONObject actionIcons = new JSONObject();
            for (Map.Entry<String, CarImage.Packaged> icon : icons.entrySet()) {
                CarImage.Packaged packaged = icon.getValue();
                actionIcons.put(icon.getKey(), new JSONArray().put(packaged.hash()).put(packaged.chunks().size()));
            }
            response.put("actionIcons", actionIcons);
        }
        CarImage.Packaged carImage = widgets.carImage();
        if (carImage != null) {
            loggedMissingCarImage = false;
            response.put("carImageHash", carImage.hash()).put("carImageChunks", carImage.chunks().size());
        } else if (!loggedMissingCarImage && widgets.vehicle().updatedAt() > 0) {
            loggedMissingCarImage = true;
            appendEvent("未提取到官方车辆图", "本次车况将使用穿戴端默认车辆图", true);
        }
        return response.put("controlDirections", directions)
                .put("vehicleCached", widgets.showsLastReading())
                .put("phoneConfirmation", pause == ControlPause.PHONE_CONFIRM)
                .put("controlPending", pause != ControlPause.NONE);
    }
}
