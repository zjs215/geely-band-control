package com.garage.bandcontrol;

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
    private static final String DISPATCHED = "已发送命令";
    private static final String UNMAPPED = "请在手机核对银河小组件配置";
    private static final String VOICE_ENABLED = "miHomeVoiceEnabled";
    private static final String SHORTCUT_TOKEN = "voiceShortcutToken";
    private static final String VEHICLE_NAME_KEY = "vehicleName";
    private static final int MAX_VEHICLE_NAME_LENGTH = 24;
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

    private record Dispatch(int cardId, CarAction action, String slot, String targetKey) {}
    private record DispatchResult(String state, String note) {}
    private record RequestKey(String node, String id) {}
    private record Receipt(BandMessage request, String state, String note) {}

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Confirmation confirmations = new Confirmation();
    private final MiHomeVoice voiceNotifications = new MiHomeVoice();
    private final Map<RequestKey, Receipt> receipts = new LinkedHashMap<>();
    private final Runnable controlTimeout = this::finishControlObservation;
    GalaxyWidgets widgets;
    WearLink link;
    private SharedPreferences prefs;
    private Runnable observer;
    private boolean screenVisible;
    private boolean serviceRunning;
    private boolean voiceEnabled;
    private boolean voiceListenerConnected;
    private String shortcutToken = "";
    private String vehicleName = DEFAULT_VEHICLE_NAME;
    private int armedCardId = AppWidgetManager.INVALID_APPWIDGET_ID;
    private Dispatch dispatch;
    private boolean confirmationVisible;
    private int handledConfirmation = AppWidgetManager.INVALID_APPWIDGET_ID;
    record UiEvent(long time, String title, String detail, boolean warning) {}
    private final List<UiEvent> uiEvents = new ArrayList<>();
    private String observedLink = "";
    private String observedBlock = "";
    private boolean loggedMissingCarImage;
    private String lastResult = "";
    private String voiceTestResult = "";

    @Override public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("garage", MODE_PRIVATE);
        voiceEnabled = prefs.getBoolean(VOICE_ENABLED, false);
        shortcutToken = prefs.getString(SHORTCUT_TOKEN, "");
        if (shortcutToken.isEmpty()) {
            shortcutToken = VoiceShortcuts.generateToken();
            prefs.edit().putString(SHORTCUT_TOKEN, shortcutToken).apply();
        }
        vehicleName = normalizeVehicleName(prefs.getString(VEHICLE_NAME_KEY, DEFAULT_VEHICLE_NAME));
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
        widgets = new GalaxyWidgets(this, prefs, main, this::onWidgetChange);
        link = new WearLink(this, main, this::onBandMessage, confirmations::disconnect, this::changed, this::note);
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
    String shortcutToken() { return shortcutToken; }
    String vehicleName() { return vehicleName; }

    static String normalizeVehicleName(String raw) {
        if (raw == null) return DEFAULT_VEHICLE_NAME;
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return DEFAULT_VEHICLE_NAME;
        return trimmed.length() > MAX_VEHICLE_NAME_LENGTH
                ? trimmed.substring(0, MAX_VEHICLE_NAME_LENGTH)
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
        voiceEnabled = enabled;
        prefs.edit().putBoolean(VOICE_ENABLED, enabled).apply();
        appendEvent(enabled ? "语音直控已开启" : "语音直控已关闭", "通知 / 快捷指令", false);
        updateWidgetListening();
        VoiceShortcuts.syncDynamicShortcuts(this, widgets.controls().keySet(), shortcutToken);
        changed();
    }

    void setVoiceListenerConnected(boolean connected) {
        if (voiceListenerConnected == connected) return;
        voiceListenerConnected = connected;
        appendEvent(connected ? "通知监听已连接" : "通知监听已断开", "语音直控", !connected);
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
            appendEvent("车控授权已开启", "当前银河中号卡片", false);
            updateWidgetListening();
            changed();
        } else {
            setResult("请先核对银河卡片按钮和车况");
        }
    }

    void disarm() {
        confirmations.clear();
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
            GalaxyWidgets.Control lock = widgets.controls().get(CarAction.LOCK);
            String block = isArmed() && lock != null ? lock.blockedReason() : "";
            if (!block.equals(observedBlock)) {
                observedBlock = block;
                if (!block.isEmpty()) appendEvent("车锁暂不可用", block + " · 卡片状态「"
                        + widgets.vehicle().motion() + "」", true);
            }
        }
        if (observer != null) observer.run();
    }

    private void onWidgetChange(int id) {
        if (armedCardId != AppWidgetManager.INVALID_APPWIDGET_ID && !isArmed()) {
            armedCardId = AppWidgetManager.INVALID_APPWIDGET_ID;
            confirmations.clear();
            updateWidgetListening();
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
        VoiceShortcuts.syncDynamicShortcuts(this, widgets.controls().keySet(), shortcutToken);
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

    void onVoiceNotification(MiHomeVoice.Source source, String key, long postedAt, MiHomeVoice.Match match) {
        if (!voiceEnabled) return;
        MiHomeVoice.Command command = match.command();
        String id = Long.toString(postedAt, 36) + "-" + Integer.toUnsignedString(key.hashCode(), 36);
        MiHomeVoice.Admission admission = voiceNotifications.receive(key, postedAt,
                System.currentTimeMillis(), SystemClock.elapsedRealtime(), command);
        if (admission != MiHomeVoice.Admission.ACCEPTED) {
            Log.i(TAG, "event=voice_notification_ignored id=" + id + " source=" + source.packageName
                    + " reason=" + admission);
            return;
        }
        Log.i(TAG, "event=voice_notification_received id=" + id + " source=" + source.packageName
                + " command=" + command + " field=" + match.field());
        appendEvent(source.label + "通知已接收", "已匹配「" + command.label + "」 · " + match.field(), false);
        if (command == MiHomeVoice.Command.TEST) {
            voiceTestResult = "已收到" + source.label + "测试通知（" + match.field() + "）";
            appendEvent("通知测试通过", "已收到" + source.label + "测试通知 · " + match.field(), false);
            changed();
            return;
        }
        DispatchResult result = dispatchVoiceCommand(command,
                "id=" + id + " type=voice source=" + source.packageName);
        setResult("语音" + command.label + " · " + result.note());
    }

    void onUnmatchedVoiceNotification(MiHomeVoice.Source source, String hint) {
        if (!voiceEnabled) return;
        Log.i(TAG, "event=voice_notification_unmatched source=" + source.packageName + " hint=" + hint);
        appendEvent(source.label + "通知未匹配", hint, true);
        changed();
    }

    String onVoiceShortcut(MiHomeVoice.Command command) {
        if (!voiceEnabled) {
            appendEvent("快捷指令未执行", "请先开启「语音直控」", true);
            changed();
            return "请先在智在银河开启「语音直控」";
        }
        if (voiceNotifications.receiveShortcut(SystemClock.elapsedRealtime(), command) != MiHomeVoice.Admission.ACCEPTED) {
            return command.label + " · 指令刚刚已触发，请稍后再试";
        }
        appendEvent("语音快捷方式已触发", "已匹配「" + command.label + "」", false);
        DispatchResult result = dispatchVoiceCommand(command, "type=shortcut");
        setResult("快捷" + command.label + " · " + result.note());
        return command.label + " · " + result.note().replace("已发送命令", "指令已发送");
    }

    private DispatchResult dispatchVoiceCommand(MiHomeVoice.Command command, String logContext) {
        CarAction action = command.action;
        GalaxyWidgets.Control control = control(action);
        String blocked = voiceBlockedReason(control);
        if (blocked.isEmpty()) blocked = command.directionBlock(control.target().direction());
        DispatchResult result = blocked.isEmpty() ? dispatchControl(action, control.target())
                : new DispatchResult("blocked", blocked);
        Log.i(TAG, "event=dispatch_result " + logContext + " action=" + action.id
                + " command=" + command + " result=" + result.state());
        return result;
    }

    private void onBandMessage(String node, byte[] data) {
        try {
            BandMessage request = BandMessage.decode(data);
            if (request == null) return;
            Log.i(TAG, "event=request_received" + requestFields(request));
            switch (request.type()) {
                case "hello" -> send(node, request.id(),
                        () -> BandMessage.helloReply(request.id(), BuildConfig.VERSION_NAME));
                case "status" -> reply(node, request, "ready", "已读取手机车况");
                case "refresh" -> refresh((state, note) -> reply(node, request, state, note));
                case "prepare" -> prepare(node, request);
                case "execute" -> execute(node, request);
                case "cancel" -> confirmations.cancel(node, request.token());
                case "car_image" -> sendCarImageChunk(node, request);
            }
        } catch (JSONException invalid) {
            Log.w(TAG, "Invalid band message");
        }
    }

    private void prepare(String node, BandMessage request) {
        if (replyIfControlPending(node, request)) return;
        GalaxyWidgets.Control control = control(request.action());
        String blocked = wearBlockedReason(control);
        if (!blocked.isEmpty()) {
            if (request.action() == CarAction.LOCK) {
                appendEvent("车锁准备被拦截", blocked + " · 卡片状态「" + widgets.vehicle().motion() + "」", true);
            }
            reply(node, request, "blocked", blocked);
            return;
        }
        GalaxyWidgets.Target target = control.target();
        if (request.direction() == null && target.direction() == ControlDirection.CLOSE) {
            reply(node, request, "blocked", "请更新手环端后使用「" + target.title() + "」");
            return;
        }
        if (request.direction() != null && request.direction() != target.direction()) {
            reply(node, request, "blocked", "按钮状态已变化，请重新选择");
            return;
        }
        String token = confirmations.prepare(node, request.action(), target.key(), SystemClock.elapsedRealtime());
        send(node, request.id(), () -> BandMessage.reply(request.id(), "confirm", target.title())
                .put("token", token).put("action", request.action().id));
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
        DispatchResult result = dispatchControl(request.action(), target);
        receipt(node, request, result.state(), result.note());
    }

    private DispatchResult dispatchControl(CarAction action, GalaxyWidgets.Target target) {
        if (action == CarAction.LOCK) {
            appendEvent("准备" + target.title(), "卡片状态「" + widgets.vehicle().motion()
                    + "」 · 锁态「" + target.lockState() + "」", false);
        }
        try {
            if (!target.button().view().performClick()) {
                return new DispatchResult("blocked", "官方按钮当前不可点击");
            }
        } catch (RuntimeException unavailable) {
            return new DispatchResult("unknown", "转交结果未知，请到银河 App 查看");
        }
        dispatch = new Dispatch(target.cardId(), action, target.button().slot(), target.key());
        confirmations.clear();
        main.postDelayed(controlTimeout, CONTROL_WAIT_MS);
        return new DispatchResult("dispatched", DISPATCHED);
    }

    private boolean replyIfControlPending(String node, BandMessage request) {
        ControlPause pause = controlPause();
        if (pause == ControlPause.NONE) return false;
        if ("execute".equals(request.type())) dispatchReply(node, request, pause.status, pause.message);
        else reply(node, request, pause.status, pause.message);
        return true;
    }

    private void finishControlObservation() {
        if (dispatch == null) return;
        Dispatch finished = dispatch;
        dispatch = null;
        main.removeCallbacks(controlTimeout);
        if (widgets.hasOfficialConfirmation(finished.cardId())) {
            setResult(ControlPause.PHONE_CONFIRM.message);
        } else {
            changed();
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
            setResult(clicked ? "已点击银河的" + (accept ? "确认" : "取消") : "官方确认按钮当前不可点击");
        } catch (RuntimeException unavailable) {
            setResult("操作结果未知，请打开银河 App 查看");
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
        manager.createNotificationChannel(new NotificationChannel(CONFIRM_CHANNEL, "银河官方确认",
                NotificationManager.IMPORTANCE_DEFAULT));
        Intent open = new Intent(this, MainActivity.class).setAction(MainActivity.SHOW_CONFIRMATION)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openNotification = PendingIntent.getActivity(this, CONFIRM_NOTICE, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        manager.notify(CONFIRM_NOTICE, new Notification.Builder(this, CONFIRM_CHANNEL)
                .setSmallIcon(R.drawable.ic_garage)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(ControlPause.PHONE_CONFIRM.message)
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
                pause == ControlPause.NONE ? "" : pause.message,
                requireWearService,
                serviceRunning,
                isArmed());
    }

    private GalaxyWidgets.Control control(CarAction action) {
        return widgets.controls().get(action);
    }

    private String wearBlockedReason(GalaxyWidgets.Control control) {
        if (control == null) return UNMAPPED;
        String notice = liveControlNotice(controlPause(), true);
        return notice.isEmpty() ? control.blockedReason() : notice;
    }

    private String voiceBlockedReason(GalaxyWidgets.Control control) {
        if (control == null) return UNMAPPED;
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
        send(node, request.id(), () -> BandMessage.reply(request.id(), state, note));
    }

    private void sendCarImageChunk(String node, BandMessage request) {
        CarImage.Packaged image = widgets.carImage();
        String hash = request.imageHash();
        int index = request.imageIndex();
        if (index == 0 && image != null && image.hash().equals(hash)) {
            appendEvent("同步车辆图片", "向穿戴端发送官方卡片车图（" + image.chunks().size() + " 片）", false);
        }
        String text = BandMessage.encodeReply(request.id(), () -> {
            if (image == null || !image.hash().equals(hash) || index < 0 || index >= image.chunks().size()) {
                return BandMessage.carImageReply(request.id(), hash, index, 0, "");
            }
            return BandMessage.carImageReply(request.id(), hash, index,
                    image.chunks().size(), image.chunks().get(index));
        }, error -> Log.e(TAG, "event=car_image_reply_failed id=" + request.id(), error));
        link.send(node, text, reason -> Log.i(TAG, "event=car_image_send_failed id=" + request.id() + " reason=" + reason));
    }

    private void send(String node, String id, BandMessage.ReplyBuilder builder) {
        String text = BandMessage.encodeReply(id, () -> withState(builder.build()),
                error -> Log.e(TAG, "event=reply_state_failed id=" + id, error));
        link.send(node, text,
                reason -> {
                    Log.i(TAG, "event=reply_send_failed id=" + id + " reason=" + reason);
                    note("手环回复发送失败", "请求 " + id + " · " + reason, true);
                });
    }

    private JSONObject withState(JSONObject response) throws JSONException {
        JSONArray available = new JSONArray();
        JSONArray mapped = new JSONArray();
        response.put("mode", "live")
                .put("vehicleName", vehicleName)
                .put("available", available).put("mapped", mapped).put("vehicle", widgets.vehicle().json());

        ControlPause pause = controlPause();
        String notice = liveControlNotice(pause, true);
        JSONObject blocked = new JSONObject();
        JSONObject directions = new JSONObject();
        for (Map.Entry<CarAction, GalaxyWidgets.Control> entry : widgets.controls().entrySet()) {
            String id = entry.getKey().id;
            GalaxyWidgets.Control control = entry.getValue();
            mapped.put(id);
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
