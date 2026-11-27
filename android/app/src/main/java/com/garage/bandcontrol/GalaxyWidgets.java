package com.garage.bandcontrol;

import android.app.Activity;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.Handler;
import android.os.Process;
import android.view.View;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;

class GalaxyWidgets {
    static final int BIND_REQUEST = 71;
    static final int CONFIGURE_REQUEST = 72;
    private static final long REFRESH_TIMEOUT_MS = 9_000;
    private static final Control WAITING_FOR_WIDGET = new Control(null, "等待官方卡片更新");

    record Binding(String slot, String fingerprint, String action, String lockState, LynkMark mark) {
        Binding(String slot, String fingerprint, String action, String lockState) { this(slot, fingerprint, action, lockState, null); }
        boolean matches(WidgetTree.Button button) {
            return slot.equals(button.slot()) && fingerprint.equals(button.fingerprint())
                    && (mark == null || mark.matches(button.slot(), button.fingerprint(), button.label()));
        }
    }

    record Target(int cardId, WidgetTree.Button button, String title, String lockState, ControlDirection direction) {
        String key() {
            return cardId + ":" + button.slot() + ":" + button.fingerprint() + ":" + direction + ":" + title;
        }

        boolean isLynkWindow() {
            if (!lockState.isEmpty()) return "车窗微开".equals(lockState) || "车窗全开".equals(lockState);
            return button.lynkIcon() != null && "车窗".equals(button.lynkIcon().category);
        }

        boolean isLynkLock() {
            if (!lockState.isEmpty()) return "锁".equals(lockState);
            return button.lynkIcon() != null && "车锁".equals(button.lynkIcon().category);
        }

        boolean isLynkTrunk() {
            if (!lockState.isEmpty()) return "后备箱".equals(lockState);
            return button.lynkIcon() == LynkWidgetIcons.Kind.TRUNK;
        }

        String lynkShortLabel() {
            String category = lockState;
            LynkWidgetIcons.Kind icon = button.lynkIcon();
            if (category.isEmpty() && icon != null) {
                category = switch (icon) {
                    case WINDOW_VENT -> "车窗微开";
                    case WINDOW_OPEN -> "车窗全开";
                    default -> icon.category;
                };
            }
            return switch (category) {
                case "锁", "车锁" -> direction == ControlDirection.LOCK ? "上锁"
                        : direction == ControlDirection.UNLOCK ? "解锁" : "车锁";
                case "车窗微开" -> direction == ControlDirection.CLOSE ? "关窗" : "窗微开";
                case "车窗全开" -> direction == ControlDirection.CLOSE ? "关窗" : "窗全开";
                case "快速降温" -> "快降温";
                case "快速升温" -> "快升温";
                case "车窗", "发动机", "天窗", "后备箱", "寻车", "充电口" -> category;
                default -> "";
            };
        }
    }

    record Control(Target target, String blockedReason, String slotState, String slotLabel) {
        Control(Target target, String blockedReason) {
            this(target, blockedReason, "", "");
        }

        boolean ready() {
            return blockedReason.isEmpty();
        }
    }

    interface RefreshResult {
        void accept(String state, String note);
    }

    static final class Card {
        final int id;
        final HostedView view;
        final List<Binding> bindings;
        private Map<String, CarAction> lastMapped = Map.of();
        private final Map<String, String> lynkSlotLabels = new LinkedHashMap<>();
        private VehicleStatus latest = VehicleStatus.EMPTY;
        private boolean validReading;
        private long carImageRevision = -1;
        private CarImage.Packaged carImage;
        private long controlIconsRevision = -1;
        private Boolean controlIconsDay;
        private Map<String, CarImage.Packaged> controlIcons = Map.of();
        private final Map<String, CarImage.Packaged> controlIconCache = new LinkedHashMap<>();

        Card(int id, HostedView view, List<Binding> bindings) {
            this.id = id;
            this.view = view;
            this.bindings = bindings;
        }

        Binding binding(WidgetTree.Button button) {
            WidgetIcons.Match match = button.match();
            if (match != null) return new Binding(button.slot(), button.fingerprint(),
                    match.action().id, match.lockState());
            for (Binding binding : bindings) {
                if (binding.matches(button)) return binding;
            }
            return null;
        }
    }

    private static final class Refresh {
        final Card card;
        final long revision;
        final long previousTime;
        final List<RefreshResult> waiters = new ArrayList<>();
        boolean receivedSameTime;

        Refresh(Card card, RefreshResult result) {
            this.card = card;
            this.revision = card.view.revision;
            this.previousTime = card.latest.updatedAt();
            waiters.add(result);
        }
    }

    private final Context context;
    private final SharedPreferences prefs;
    private final Handler main;
    private final IntConsumer onChange;
    private final AppWidgetManager manager;
    private final AppWidgetHost host;
    final BrandConfig config;
    private final WidgetTree tree;
    private final WidgetIcons icons;
    private final String packageName, cardsKey;
    private final ComponentName providerName;
    private final int heightDp;
    private final Map<Integer, Card> cards = new LinkedHashMap<>();
    private final Runnable refreshTimeout = this::finishTimedOutRefresh;
    private boolean listening;
    private Refresh refresh;

    GalaxyWidgets(Context context, SharedPreferences prefs, Handler main, IntConsumer onChange, BrandConfig config) {
        this.config = config;
        tree = new WidgetTree(config);
        icons = new WidgetIcons(config);
        packageName = config.packageName;
        providerName = new ComponentName(packageName, config.provider);
        cardsKey = config.cardsKey;
        heightDp = config.heightDp;
        this.context = context;
        this.prefs = prefs;
        this.main = main;
        this.onChange = onChange;
        manager = AppWidgetManager.getInstance(context);
        host = new AppWidgetHost(context, config.hostId) {
            @Override protected AppWidgetHostView onCreateView(Context viewContext, int id,
                                                              AppWidgetProviderInfo info) {
                return new HostedView(viewContext);
            }

            @Override public void onAppWidgetRemoved(int id) {
                super.onAppWidgetRemoved(id);
                forget(id);
            }
        };
    }

    final class HostedView extends AppWidgetHostView {
        private String layout = "";
        private long revision;

        HostedView(Context context) {
            super(context);
        }

        @Override public void updateAppWidget(RemoteViews views) {
            super.updateAppWidget(views);
            layout = layoutName(views);
            revision++;
            Card card = cards.get(getAppWidgetId());
            if (card != null && card.view == this) {
                recordReading(card);
                // The visible host may need a layout pass before its new buttons have bounds.
                main.post(() -> onChange.accept(card.id));
            }
        }
    }

    void setListening(boolean active) {
        if (active == listening) return;
        if (active) {
            restore();
            host.startListening();
        } else {
            host.stopListening();
        }
        listening = active;
    }

    boolean isProviderInstalled() {
        return provider() != null;
    }

    Collection<Card> cards() {
        return Collections.unmodifiableCollection(cards.values());
    }

    int allocate(Activity activity) {
        AppWidgetProviderInfo info = provider();
        if (info == null || primary() != null) return AppWidgetManager.INVALID_APPWIDGET_ID;
        int id = host.allocateAppWidgetId();
        Bundle options = widgetOptions();
        if (manager.bindAppWidgetIdIfAllowed(id, info.provider, options)) {
            finishBind(id);
            return AppWidgetManager.INVALID_APPWIDGET_ID;
        }
        Intent intent = new Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_OPTIONS, options);
        try {
            activity.startActivityForResult(intent, BIND_REQUEST);
            return id;
        } catch (RuntimeException unavailable) {
            host.deleteAppWidgetId(id);
            throw unavailable;
        }
    }

    boolean finishBind(int id) {
        AppWidgetProviderInfo info = manager.getAppWidgetInfo(id);
        if (info == null || !providerName.equals(info.provider)) {
            host.deleteAppWidgetId(id);
            return false;
        }
        if (cards.containsKey(id)) return false;
        if (primary() != null) {
            host.deleteAppWidgetId(id);
            return false;
        }
        addCard(id, info, new ArrayList<>());
        persist();
        onChange.accept(id);
        return true;
    }

    void cancelBind(int id) {
        if (id != AppWidgetManager.INVALID_APPWIDGET_ID && !cards.containsKey(id)) {
            host.deleteAppWidgetId(id);
        }
    }

    void remove(int id) {
        if (!forget(id)) return;
        host.deleteAppWidgetId(id);
    }

    private boolean forget(int id) {
        Card removed = cards.remove(id);
        if (removed == null) return false;
        if (refresh != null && refresh.card == removed) finishRefresh("blocked", "车况卡片已移除");
        persist();
        onChange.accept(id);
        return true;
    }

    boolean configure(Activity activity, Card card) {
        if (card != primary()) return false;
        AppWidgetProviderInfo info = manager.getAppWidgetInfo(card.id);
        if (info == null || !providerName.equals(info.provider)) return false;
        if (info.configure != null) {
            host.startAppWidgetConfigureActivityForResult(activity, card.id, 0, CONFIGURE_REQUEST, null);
        } else {
            WidgetTree.Button settings = uniqueRole(card, config.settings);
            if (settings == null || !settings.view().performClick()) return false;
        }
        recheckAfterConfiguration();
        return true;
    }

    void recheckAfterConfiguration() {
        Card card = primary();
        if (card != null) {
            card.latest = VehicleStatus.EMPTY;
            card.validReading = false;
            card.lastMapped = Map.of();
            card.lynkSlotLabels.clear();
        }
        cancelRefresh("卡片配置正在重新核对");
        onChange.accept(AppWidgetManager.INVALID_APPWIDGET_ID);
    }

    boolean showsSetup(Card card) {
        return cards.get(card.id) == card && (card.view.layout.equals(config.setupLayout)
                || hasNormalLayout(card) && tree.showsZeekrGuest(card.view));
    }

    boolean showsUnavailable(Card card) {
        return cards.get(card.id) == card && card.view.layout.equals(config.unavailableLayout);
    }

    List<WidgetTree.Button> buttons(Card card) {
        if (!hasNormalLayout(card)) return Collections.emptyList();
        String version = providerVersion();
        if (version == null) return Collections.emptyList();
        layoutForScan(card);
        List<WidgetTree.Button> current = tree.buttons(card.view, version, card.validReading, icons);
        if (config.slotMode == BrandConfig.SlotMode.CONFIGURABLE && !tree.loading(card.view)
                && !tree.confirmationVisible(card.view)) {
            boolean removed = card.bindings.removeIf(binding -> binding.mark() != null
                    && current.stream().anyMatch(button -> button.slot().equals(binding.slot()) && !binding.matches(button)));
            if (removed) persist();
        }
        return current;
    }

    boolean bindButton(Card card, WidgetTree.Button original, String action, String lockState) {
        if (card != primary()) return false;
        WidgetTree.Button current = null;
        for (WidgetTree.Button button : buttons(card)) {
            if (button.slot().equals(original.slot()) && button.fingerprint().equals(original.fingerprint())) {
                current = button;
                break;
            }
        }
        if (current == null || !current.role().isEmpty() || current.match() != null || config.recognizedIconsOnly()) return false;
        String fingerprint = current.fingerprint();
        card.bindings.removeIf(binding -> binding.slot.equals(original.slot())
                && (action.isEmpty() || binding.fingerprint.equals(fingerprint) || !binding.action.equals(action)));
        if (!action.isEmpty()) {
            if (config.slotMode == BrandConfig.SlotMode.CONFIGURABLE) {
                String[] evidence = lockState.split(":", -1);
                if (evidence.length != 2 || !LynkMark.CATEGORIES.contains(evidence[0])) return false;
                Binding neutral = LynkWidgets.binding(current);
                if (neutral == null) return false;
                card.bindings.add(new Binding(current.slot(), fingerprint, neutral.action(), "",
                        new LynkMark(current.slot(), fingerprint, current.label(), evidence[0], evidence[1])));
            } else card.bindings.add(new Binding(original.slot(), fingerprint, action, lockState));
        }
        persist();
        onChange.accept(card.id);
        return true;
    }

    VehicleStatus vehicle() {
        Card card = primary();
        return card == null ? VehicleStatus.EMPTY : card.latest;
    }

    CarImage.Packaged carImage() {
        Card card = primary();
        if (card == null || !hasNormalLayout(card) || tree.loading(card.view)
                || config == BrandConfig.ZEEKR && !card.validReading) return null;
        if (card.carImageRevision != card.view.revision) {
            layoutForScan(card);
            var view = tree.vehicleImage(card.view);
            CarImage.Packaged extracted = CarImage.extract(view);
            if (extracted != null) {
                card.carImage = extracted;
                card.carImageRevision = card.view.revision;
            }
        }
        return card.carImage;
    }

    Map<String, CarImage.Packaged> controlIcons(Map<CarAction, Control> controls, Boolean themeDay) {
        Card card = primary();
        if (card == null || !hasNormalLayout(card) || tree.loading(card.view) || controls == null || controls.isEmpty()) {
            return Map.of();
        }
        if (card.controlIconsRevision == card.view.revision
                && java.util.Objects.equals(card.controlIconsDay, themeDay)) {
            return card.controlIcons;
        }
        layoutForScan(card);
        Map<String, CarImage.Packaged> extracted = new LinkedHashMap<>();
        for (Map.Entry<CarAction, Control> entry : controls.entrySet()) {
            Control control = entry.getValue();
            Target target = control == null ? null : control.target();
            View iconView = target == null ? null : target.button().iconView();
            if (iconView == null) continue;
            CarImage.Packaged icon = ControlIcons.extract(iconView, themeDay);
            if (icon == null) continue;
            extracted.put(entry.getKey().id, icon);
            card.controlIconCache.remove(icon.hash());
            card.controlIconCache.put(icon.hash(), icon);
            while (card.controlIconCache.size() > 32) {
                String oldest = card.controlIconCache.keySet().iterator().next();
                card.controlIconCache.remove(oldest);
            }
        }
        card.controlIcons = extracted.isEmpty() ? Map.of() : Collections.unmodifiableMap(extracted);
        card.controlIconsRevision = card.view.revision;
        card.controlIconsDay = themeDay;
        return card.controlIcons;
    }

    CarImage.Packaged controlIcon(String hash) {
        if (hash == null || hash.isEmpty()) return null;
        Card card = primary();
        return card == null ? null : card.controlIconCache.get(hash);
    }

    boolean showsLastReading() {
        Card card = primary();
        return card != null && !card.validReading && card.latest.updatedAt() != 0;
    }

    boolean hasValidReading(int cardId) {
        Card card = primary();
        return card != null && card.id == cardId && card.validReading;
    }

    Card primary() {
        return cards.isEmpty() ? null : cards.values().iterator().next();
    }

    private boolean hasNormalLayout(Card card) {
        return cards.get(card.id) == card && config.normalLayout.equals(card.view.layout);
    }

    private void recordReading(Card card) {
        if (showsUnavailable(card) || config.recognizedIconsOnly() && showsSetup(card)) {
            card.latest = VehicleStatus.EMPTY;
            card.validReading = false;
            card.lastMapped = Map.of();
            card.lynkSlotLabels.clear();
            card.carImage = null;
            card.carImageRevision = -1;
            card.controlIcons = Map.of();
            card.controlIconsRevision = -1;
            card.controlIconsDay = null;
            if (refresh != null && refresh.card == card) finishRefresh("blocked", "当前卡片没有可读取的车辆");
            return;
        }
        VehicleStatus update = hasNormalLayout(card)
                ? tree.read(card.view, System.currentTimeMillis(), ZoneId.systemDefault()) : VehicleStatus.EMPTY;
        card.validReading = update.updatedAt() != 0 && update.updatedAt() >= card.latest.updatedAt();
        if (!card.validReading) return;
        card.latest = update;
        if (refresh == null || refresh.card != card || card.view.revision <= refresh.revision) return;
        if (update.updatedAt() > refresh.previousTime) {
            finishRefresh("refreshed", "已收到更新的车况");
        } else {
            refresh.receivedSameTime = true;
        }
    }

    Map<CarAction, Control> controls() {
        Card card = primary();
        if (card == null || !hasNormalLayout(card)) return Collections.emptyMap();
        if (config == BrandConfig.LYNKCO) return lynkControls(card);
        Map<CarAction, Control> result = new LinkedHashMap<>();
        boolean loading = tree.loading(card.view);
        if (loading || tree.confirmationVisible(card.view)) {
            Control paused = loading ? new Control(null, "加载中", "loading", "") : WAITING_FOR_WIDGET;
            for (CarAction action : card.lastMapped.values()) result.put(action, paused);
            return result;
        }

        Map<CarAction, Control> live = new LinkedHashMap<>();
        Map<String, CarAction> liveSlots = new LinkedHashMap<>();
        List<WidgetTree.Button> buttons = buttons(card);
        for (WidgetTree.Button button : buttons) {
            if (!button.role().isEmpty()) continue;
            Binding binding = card.binding(button);
            CarAction action = binding == null ? null : CarAction.fromId(binding.action());
            if (action == null || live.containsKey(action)) continue;

            String title = action.label;
            String lockState = "";
            String blockedReason = "";
            ControlDirection direction = button.match() == null ? null : button.match().direction();
            if (action == CarAction.LOCK) {
                lockState = binding.lockState();
                blockedReason = LockControl.blockedReason(card.validReading, card.latest.motion(), lockState)
                        .replace("银河", config.name);
                direction = ControlDirection.forLockState(lockState);
                if (direction == ControlDirection.UNLOCK) title = "解锁车门";
                if (direction == ControlDirection.LOCK) title = "上锁车门";
            } else if (action.isWindow()) {
                WindowControl.Decision decision = WindowControl.resolve(direction);
                direction = decision.direction();
                blockedReason = decision.blockedReason();
                if (direction == ControlDirection.CLOSE) title = "关闭车窗";
            } else if (action.isSwitch()) {
                if (!card.validReading) blockedReason = "请刷新" + config.name + "卡片，核对" + action.label + "开关状态";
                else if (direction == null) blockedReason = "请在手机核对" + action.label + "开关状态";
                else title = (direction == ControlDirection.OPEN ? "开启" : "关闭") + action.label;
            }
            Target target = new Target(card.id, button, title, lockState, direction);
            boolean unknown = !blockedReason.isEmpty()
                    && (action == CarAction.LOCK || action.isWindow() || action.isSwitch())
                    && (!card.validReading || direction == null);
            live.put(action, new Control(target, blockedReason, unknown ? "unknown" : "", ""));
            liveSlots.put(button.slot(), action);
        }

        Map<String, CarAction> mapped = new LinkedHashMap<>();
        for (String slot : config.slots) {
            CarAction action = liveSlots.get(slot);
            Control control;
            if (action != null) {
                control = live.get(action);
            } else {
                action = card.lastMapped.get(slot);
                if (action == null || live.containsKey(action) || !tree.slotVisible(card.view, slot)) continue;
                control = tree.slotLoading(card.view, slot)
                        ? new Control(null, "加载中", "loading", "")
                        : new Control(null, "状态未知，请在官方 App 核对", "unknown", "");
            }
            mapped.put(slot, action);
            result.put(action, control);
        }
        card.lastMapped = mapped;
        return result;
    }

    private Map<CarAction, Control> lynkControls(Card card) {
        Map<CarAction, Control> result = new LinkedHashMap<>();
        Map<String, CarAction> mapped = new LinkedHashMap<>();
        Map<String, WidgetTree.Button> current = new LinkedHashMap<>();
        for (WidgetTree.Button button : buttons(card)) {
            if (button.role().isEmpty()) current.put(button.slot(), button);
        }
        boolean paused = tree.loading(card.view) || tree.confirmationVisible(card.view);
        for (String slot : config.slots) {
            if (!tree.slotVisible(card.view, slot) && !(paused && card.lastMapped.containsKey(slot))) {
                card.lynkSlotLabels.remove(slot);
                continue;
            }
            CarAction action = LynkWidgets.actionForSlot(slot);
            mapped.put(slot, action);
            WidgetTree.Button button = current.get(slot);
            Binding binding = button == null ? null : card.binding(button);
            LynkMark mark = binding == null ? null : binding.mark();
            LynkWidgets.SlotState state = LynkWidgets.slotState(tree, card.view, slot, button, mark != null);
            if (state != LynkWidgets.SlotState.READY) {
                String reason = state == LynkWidgets.SlotState.UNKNOWN ? "状态未知，请在领克 App 核对"
                        : state == LynkWidgets.SlotState.LOADING ? "加载中" : WAITING_FOR_WIDGET.blockedReason();
                result.put(action, new Control(null, reason, state.wireValue,
                        card.lynkSlotLabels.getOrDefault(slot, "")));
                continue;
            }
            String category = mark == null ? "" : mark.category();
            ControlDirection direction = mark == null
                    ? (button.match() == null ? null : button.match().direction())
                    : ControlDirection.fromId(mark.direction());
            String title = mark != null
                    ? action.label + " · 车主标记：" + category + (direction == null ? " · 请核对方向" : " · " + direction.id)
                    : button.lynkIcon() == null ? action.label : button.lynkIcon().label() + " · 请核对方向";
            Target target = new Target(card.id, button, title, category, direction);
            String label = target.lynkShortLabel();
            if (label.isEmpty()) card.lynkSlotLabels.remove(slot);
            else card.lynkSlotLabels.put(slot, label);
            result.put(action, new Control(target, "", "", label));
        }
        card.lastMapped = mapped;
        return result;
    }

    void refresh(RefreshResult result) {
        Card card = primary();
        if (card == null) {
            result.accept("blocked", "请先添加" + config.name + "小组件");
            return;
        }
        if (refresh != null && refresh.card == card) {
            refresh.waiters.add(result);
            return;
        }
        if (hasOfficialConfirmation()) {
            result.accept("phone_confirm", "请先在手机处理" + config.name + "官方确认");
            return;
        }
        WidgetTree.Button button = uniqueRole(card, config.refresh);
        if (button == null) {
            result.accept("cached", tree.loading(card.view)
                    ? config.name + "卡片正在获取车况" : "当前卡片没有可用的刷新按钮");
            return;
        }
        refresh = new Refresh(card, result);
        main.postDelayed(refreshTimeout, REFRESH_TIMEOUT_MS);
        try {
            if (!button.view().performClick()) finishRefresh("cached", config.name + "卡片刷新按钮暂不可用");
        } catch (RuntimeException unavailable) {
            finishRefresh("cached", config.name + "卡片刷新暂不可用");
        }
    }

    void cancelRefresh(String note) {
        finishRefresh("blocked", note);
    }

    private void finishTimedOutRefresh() {
        if (refresh == null) return;
        String note = "刷新超时，尚未取得真实车况";
        if (refresh.receivedSameTime) {
            note = "卡片已回传，车况时间仍为 " + refresh.card.latest.updatedLabel();
        } else if (refresh.card.latest.updatedAt() != 0) {
            note = "刷新超时，仍显示上次车况";
        }
        finishRefresh("cached", note);
    }

    private void finishRefresh(String status, String note) {
        if (refresh == null) return;
        Refresh finished = refresh;
        refresh = null;
        main.removeCallbacks(refreshTimeout);
        for (RefreshResult result : finished.waiters) result.accept(status, note);
    }

    private WidgetTree.Button uniqueRole(Card card, String role) {
        if (!hasNormalLayout(card)) return null;
        layoutForScan(card);
        for (WidgetTree.Button button : tree.buttons(card.view, "", false, icons)) {
            if (role.equals(button.role())) return button;
        }
        return null;
    }

    boolean hasOfficialConfirmation() {
        Card card = primary();
        return card != null && hasOfficialConfirmation(card.id);
    }

    boolean hasOfficialConfirmation(int id) {
        Card card = cards.get(id);
        return card != null && hasNormalLayout(card) && tree.confirmationVisible(card.view);
    }

    Card confirmationCard(int handledId) {
        Card card = primary();
        return card != null && card.id != handledId && officialConfirmation(card) != null ? card : null;
    }

    WidgetTree.OfficialConfirmation officialConfirmation(Card card) {
        if (card == null || card != primary() || !hasNormalLayout(card)) return null;
        layoutForScan(card);
        return tree.confirmation(card.view);
    }

    long revision(Card card) {
        return hasNormalLayout(card) ? card.view.revision : -1;
    }

    private AppWidgetProviderInfo provider() {
        for (AppWidgetProviderInfo info : manager.getInstalledProvidersForPackage(packageName, Process.myUserHandle())) {
            if (providerName.equals(info.provider)) return info;
        }
        return null;
    }

    private String providerVersion() {
        try {
            var info = context.getPackageManager().getPackageInfo(packageName, 0);
            return info.getLongVersionCode() + ":" + info.lastUpdateTime + ":"
                    + (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK);
        } catch (PackageManager.NameNotFoundException uninstalled) {
            return null;
        }
    }

    private String layoutName(RemoteViews views) {
        if (views == null || !packageName.equals(views.getPackage())) return "";
        try {
            return context.getPackageManager().getResourcesForApplication(packageName)
                    .getResourceEntryName(views.getLayoutId());
        } catch (PackageManager.NameNotFoundException | Resources.NotFoundException unavailable) {
            return "";
        }
    }

    int screenPaddingDp() {
        return Math.min(20, Math.max(4, (context.getResources().getConfiguration().screenWidthDp - 330) / 2));
    }

    private int widthDp() {
        return Math.max(1, context.getResources().getConfiguration().screenWidthDp - 2 * screenPaddingDp());
    }

    private Bundle widgetOptions() {
        int width = widthDp();
        Bundle options = new Bundle();
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, width);
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, width);
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, heightDp);
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, heightDp);
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY, AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN);
        return options;
    }

    private void addCard(int id, AppWidgetProviderInfo info, List<Binding> bindings) {
        HostedView view = (HostedView) host.createView(context, id, info);
        view.setPadding(0, 0, 0, 0);
        Card card = new Card(id, view, bindings);
        cards.put(id, card);
        layoutForScan(card);
        recordReading(card);
    }

    private void layoutForScan(Card card) {
        if (card.view.getWidth() > 0 && card.view.getHeight() > 0 && !card.view.isLayoutRequested()) return;
        // A widget update can replace the controls before the next layout pass.
        int width = card.view.getWidth() > 0 ? card.view.getWidth() : dp(widthDp());
        int height = card.view.getHeight() > 0 ? card.view.getHeight() : dp(heightDp);
        int left = card.view.getLeft();
        int top = card.view.getTop();
        card.view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        card.view.layout(left, top, left + width, top + height);
    }

    private int dp(int size) {
        return Math.round(context.getResources().getDisplayMetrics().density * size);
    }

    private void restore() {
        if (!cards.isEmpty()) return;
        try {
            JSONArray saved = new JSONArray(prefs.getString(cardsKey, "[]"));
            for (int i = 0; i < saved.length(); i++) {
                JSONObject entry = saved.getJSONObject(i);
                int id = entry.getInt("id");
                AppWidgetProviderInfo info = manager.getAppWidgetInfo(id);
                if (info == null || !providerName.equals(info.provider)) continue;
                JSONArray entries = entry.getJSONArray("bindings");
                List<Binding> bindings = new ArrayList<>();
                for (int j = 0; j < entries.length(); j++) {
                    JSONObject item = entries.getJSONObject(j);
                    String slot = item.optString("slot");
                    if (!slot.isEmpty()) bindings.add(new Binding(slot, item.getString("fingerprint"),
                            item.getString("action"), item.optString("lockState"),
                            item.has("category") ? new LynkMark(slot, item.getString("fingerprint"), item.optString("description"),
                            item.getString("category"), item.optString("direction")) : null));
                }
                addCard(id, info, bindings);
            }
        } catch (JSONException corrupt) {
            android.util.Log.w("GalaxyWidgets", "Could not read local widget bindings", corrupt);
        }
    }

    private void persist() {
        try {
            JSONArray data = new JSONArray();
            for (Card card : cards.values()) {
                JSONArray entries = new JSONArray();
                for (Binding binding : card.bindings) {
                    JSONObject item = new JSONObject().put("slot", binding.slot).put("fingerprint", binding.fingerprint)
                            .put("action", binding.action).put("lockState", binding.lockState);
                    if (binding.mark() != null) item.put("category", binding.mark().category())
                            .put("description", binding.mark().description()).put("direction", binding.mark().direction());
                    entries.put(item);
                }
                data.put(new JSONObject().put("id", card.id).put("bindings", entries));
            }
            prefs.edit().putString(cardsKey, data.toString()).apply();
        } catch (JSONException invalid) {
            throw new IllegalStateException(invalid);
        }
    }
}
