package com.mario.lynkband.watch;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.res.Configuration;
import android.view.ViewGroup;
import java.util.HashMap;
import java.util.Map;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.*;

import com.mario.lynkband.solar.ThemeSync;
import com.mario.lynkband.transport.ControlCategory;
import com.mario.lynkband.ui.AmbientMotion;

import org.json.JSONObject;


/** Native touch targets on a scrollable surface, sized to the watch window. */
final class WatchFace extends FrameLayout {
    interface Controls {
        void refresh();

        void prepare(JSONObject item);

        void confirm();

        void cancel();

        void pair();

        void reconnect();

        default void receiveUpdate() {}
        default void solarSettingsChanged() {}
    }

    enum Screen {
        STATUS,
        PHONE,
        THEME, SOLAR, CONTROLS
    }

    private final Controls events;
    private final FrameLayout dial;
    private final AmbientMotion motion;
    private final Path roundViewport = new Path();
    private int windowWidth, windowHeight;
    private boolean round, narrow, singleColumn, scrollingHome;
    private FrameLayout page;
    private ScrollView homeScroll;
    private int homeOffset;
    private Screen settingsScrollPage;
    private int settingsOffset;
    private WatchState model;
    private Screen screen = Screen.STATUS;
    private static final String SCENE_KEY = "scene";
    private static final String CONTROL_LIST_KEY = "controlListLayout";
    private final android.content.SharedPreferences themeSettings;
    private final WatchThemes themes;
    private boolean localSolarNight, solarNight;
    private String localSolarInfo = "日出 06:00 · 日落 18:00";
    private Context palette;
    private boolean online, paired;
    private boolean controlList;
    private boolean wasConfirming;
    private long confirmShownAt;
    private float scale;
    private String rendered = "";
    private Map<String, ActionTile> tiles = new HashMap<>();

    void clearTiles() {
        for (ActionTile tile : tiles.values()) tile.stopMotion();
        tiles.clear();
        rendered = "";
    }

    @Override protected void onDetachedFromWindow() {
        clearTiles();
        super.onDetachedFromWindow();
    }

    WatchFace(Context context, Controls events) {
        super(context);
        this.events = events;
        themeSettings = context.getSharedPreferences("watchTheme", Context.MODE_PRIVATE);
        controlList = themeSettings.getBoolean(CONTROL_LIST_KEY, false);
        themes = new WatchThemes(context);
        motion = new AmbientMotion(context);
        motion.setCenterCrop(true);
        updatePalette();
        setBackgroundColor(0xff000000);
        dial = new FrameLayout(context);
        dial.setBackgroundColor(color(R.color.bg));
        addView(dial, new LayoutParams(-1, -1, Gravity.CENTER));
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec), height = MeasureSpec.getSize(heightSpec);
        boolean roundScreen = getResources().getConfiguration().isScreenRound();
        if (width > 0 && height > 0 && (width != windowWidth || height != windowHeight || round != roundScreen)) {
            windowWidth = width;
            windowHeight = height;
            round = roundScreen;
            narrow = height >= width * 1.45f;
            singleColumn = width < 280 || (!round && width != height) || narrow;
            scale = width / (narrow ? 320f : 480f);
            roundViewport.reset();
            if (round) {
                float radius = Math.min(width, height) / 2f;
                roundViewport.addRoundRect(0, 0, width, height, radius, radius, Path.Direction.CW);
            }
            clearTiles(); // Child sizes and text autosizing use the dial scale at construction.
            drawPage();
        }
        super.onMeasure(widthSpec, heightSpec);
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        if (!round) {
            super.dispatchDraw(canvas);
            return;
        }
        int save = canvas.save();
        canvas.clipPath(roundViewport);
        super.dispatchDraw(canvas);
        canvas.restoreToCount(save);
    }

    private boolean nightScene() { return solarNight; }

    private void updatePalette() {
        Configuration config = new Configuration(getResources().getConfiguration());
        config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                | (nightScene() ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
        palette = getContext().createConfigurationContext(config);
    }

    void setLocalSolar(boolean night, String info) {
        localSolarNight = night;
        localSolarInfo = info;
        updateSolarScene();
        drawPage();
    }

    private void updateSolarScene() {
        WatchState.PhoneSolar phone = followedSolar();
        boolean night = phone == null ? localSolarNight : phone.night();
        if (night == solarNight) return;
        solarNight = night;
        updatePalette();
        clearTiles();
    }

    private WatchState.PhoneSolar followedSolar() {
        return online && model != null && !manualSolar() ? model.phoneSolar() : null;
    }

    void show(WatchState state, boolean connected, boolean hasPhone) {
        model = state;
        if (state.confirming() && !wasConfirming) confirmShownAt=android.os.SystemClock.elapsedRealtime();
        wasConfirming = state.confirming();
        online = connected;
        paired = hasPhone;
        updateSolarScene();
        drawPage();
    }

    private void go(Screen where) {
        screen = where;
        drawPage();
    }

    boolean scrollWithCrown(float steps) {
        if (homeScroll == null || steps == 0) return false;
        homeScroll.scrollBy(0, Math.round(-steps * px(52)));
        return true;
    }

    boolean back() {
        if (model != null && model.confirming()) {
            events.cancel();
            return true;
        }
        if (screen == Screen.STATUS) return false;
        go(screen == Screen.SOLAR ? Screen.THEME
                : screen == Screen.THEME || screen == Screen.CONTROLS ? Screen.PHONE : Screen.STATUS);
        return true;
    }

    boolean controlsVisible() {
        return online && screen == Screen.STATUS && !model.confirming();
    }

    private void drawPage() {
        if (scale <= 0 || model == null) return;
        String key =
                screen
                        + "|" + themes.key() + "|" + themes.scene() + "|" + themes.waitingForLegacyArt() + "|"
                        + solarNight + "|"
                        + online
                        + paired
                        + model.confirming()
                        + model.title()
                        + model.confirmCategory() + model.confirmState()
                        + model.status
                        + "|"
                        + model.note
                        + "|"
                        + (model.carPhoto != null)
                        + model.photoResolved
                        + model.data
                        + "|"
                        + model.actions
                        + "|"
                        + model.controlPending() + "|" + model.loadingAction() + "|" + controlList
                        + (screen == Screen.SOLAR ? "|" + manualSolar() + "|" + solarSummary() : "");
        if (key.equals(rendered)) return;
        if (!controlsVisible()) clearTiles();
        rendered = key;
        if (homeScroll != null) {
            if (scrollingHome) homeOffset = homeScroll.getScrollY();
            else if (settingsScrollPage == screen) settingsOffset = homeScroll.getScrollY();
            homeScroll = null;
        }
        dial.removeAllViews();
        scrollingHome = !model.confirming() && screen == Screen.STATUS && (online || paired);
        boolean photoHome = scrollingHome;
        boolean night = nightScene();
        dial.setBackgroundColor(scrollingHome ? (night ? 0xff11151a : 0xff4c5a68) : color(R.color.bg));
        if (scrollingHome) {
            android.widget.ImageView landscape = new android.widget.ImageView(getContext());
            landscape.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            android.graphics.Bitmap preview = themes.preview(night);
            if (preview == null) landscape.setImageResource(night ? R.drawable.watch_stars : R.drawable.watch_landscape);
            else landscape.setImageBitmap(preview);
            if (!night) {
                int[] shade = photoHome
                        ? new int[]{0x900a1323, 0x640a1323, 0x300a1323, 0x000a1323}
                        : new int[]{0x80ffffff, 0x8cffffff, 0x52ffffff, 0x14ffffff, 0x08ffffff};
                landscape.setForeground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, shade));
            }
            landscape.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            dial.addView(landscape, new FrameLayout.LayoutParams(-1, -1));
            motion.setScene(themes.scene(), night);
            dial.addView(motion, new FrameLayout.LayoutParams(-1, -1));
        }
        createScrollPage();
        if (model.confirming()) {
            confirmPage();
            return;
        }
        if (screen == Screen.THEME) { themePage(); return; }
        if (screen == Screen.SOLAR) { solarPage(); return; }
        if (screen == Screen.CONTROLS) { controlLayoutPage(); return; }
        if (screen == Screen.PHONE || (!online && !paired)) {
            phonePage();
            return;
        }
        String car = value("vehicle", getContext().getString(R.string.app_name));
        String status = model.status;
        for (int i = 0; i < model.actions.length(); i++) {
            JSONObject item = model.actions.optJSONObject(i);
            if (item != null && WatchState.ControlState.of(item) == WatchState.ControlState.LOADING) {
                status = WatchState.tileCaption(item);
                break;
            }
        }
        if (!model.preparingTitle().isEmpty()) status = "正在核对 · " + model.preparingTitle();
        int gridTop;
        if (photoHome) {
            gridTop = photoHeader(car, status);
        } else {
            label(car, 30, color(R.color.ink), true, 110, 34, 260, 38);
            String connectionStatus = online && (status.contains("卡片") || status.equals("车况只读"))
                    ? "已连接 · " + status : status;
            label(connectionStatus, 20, color(R.color.link), false, 76, 73, 328, 30);
            String km = value("range", "—");
            LinearLayout distance = row();
            TextView caption = text("续航", 21, color(R.color.muted), false);
            caption.setPadding(0, px(17), px(8), 0);
            distance.addView(caption);
            TextView number = text(km, 68, color(R.color.ink), true);
            number.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
            distance.addView(number);
            TextView unit = text(value("unit", "km"), 22, color(R.color.muted), false);
            unit.setPadding(px(5), px(18), 0, 0);
            distance.addView(unit);
            put(distance, 123, 104, 234, 74);
            FrameLayout energy = new FrameLayout(getContext());
            String fuel = fuelReading();
            boolean hasFuel = !fuel.isEmpty();
            boolean stacked = singleColumn && hasFuel;
            put(energy, 86, 179, 308, stacked ? 78 : 39);
            metric(energy, singleColumn || !hasFuel ? 77 : 0, 0, "电量", value("battery", "—"), "battery", color(R.color.link));
            if (hasFuel) metric(energy, stacked ? 77 : 154, stacked ? 39 : 0, "油量", fuel, "fuel", color(R.color.warm));
            if (hasFuel && !stacked) {
                View divider = new View(getContext());
                divider.setBackgroundColor(color(R.color.line));
                place(energy, divider, 153, 10, 1, 19);
            }
            gridTop = stacked ? 273 : 234;
        }
        int muted = photoHome ? 0xffd3dfed : color(R.color.muted);
        boolean list = singleColumn || controlList;
        int rowHeight = list ? 118 : 148;
        int rows = actionTiles(gridTop, muted, list, rowHeight);
        String time = value("time", "待更新");
        LinearLayout refresh = row();
        int refreshInk = color(R.color.ink);
        refresh.addView(new Symbol("refresh", refreshInk), px(22), px(22));
        TextView updated = text("  刷新车况 · " + time, 21, refreshInk, true);
        updated.setMaxWidth(px(254));
        refresh.addView(updated);
        activate(
                refresh,
                "刷新车况，更新时间 " + time,
                this::refreshDashboard,
                color(R.color.control),
                36);
        int footerY = gridTop + (rows == 0 ? 91 : 6 + rows * rowHeight);
        put(refresh, 88, footerY, 304, 72);
        chip("设置", "", false, 128, footerY + 83, 224, 56, () -> go(Screen.PHONE));
        page.setMinimumHeight(px(footerY + 189));
    }

    private int photoHeader(String car, String status) {
        int white = 0xfff3f6fc, soft = 0xffd7e3ef;
        label(car, 31, white, false, 96, 44, 288, 41);
        LinearLayout range = row();
        TextView number = text(value("range", "—"), 73, white, true);
        number.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        range.addView(number);
        TextView unit = text(value("unit", "km"), 23, soft, false);
        unit.setPadding(px(6), px(20), 0, 0);
        range.addView(unit);
        range.setContentDescription("续航 " + number.getText() + " " + unit.getText());
        put(range, 80, 82, 320, 83);

        LinearLayout energy = row();
        photoMetric(energy, "battery", "电量", value("battery", "—"), soft);
        String fuel = fuelReading();
        if (!fuel.isEmpty()) photoMetric(energy, "fuel", "油量", fuel, soft);
        put(energy, 74, 165, 332, 32);
        String connection = "已允许穿戴车控".equals(status) ? "已连接" : status;
        LinearLayout connectionRow = row();
        View dot = new View(getContext());
        dot.setBackground(shape(0xff68e5b2, 5));
        connectionRow.addView(dot, px(7), px(7));
        TextView connectionText = text(connection, 19, soft, false);
        connectionText.setPadding(px(9), 0, 0, 0);
        connectionRow.addView(connectionText);
        put(connectionRow, 76, 199, 328, 28);

        if (model.carPhoto == null && !model.photoResolved) return 246;
        ImageView photo = new ImageView(getContext());
        photo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        if (model.carPhoto != null) {
            photo.setImageBitmap(model.carPhoto);
            photo.setContentDescription("车模图");
        } else {
            photo.setImageResource(R.drawable.default_vehicle);
            photo.setContentDescription("默认领克车图");
        }
        put(photo, 60, 229, 360, 164);
        put(new Symbol("chevron", soft), 230, 425, 20, 14);
        return 480;
    }

    private void photoMetric(LinearLayout energy, String symbol, String title, String reading, int color) {
        LinearLayout metric = row();
        metric.setPadding(px(12), 0, px(12), 0);
        metric.setContentDescription(title + " " + reading);
        metric.addView(new Symbol(symbol, color), px(23), px(23));
        TextView value = text(reading, 23, color, false);
        value.setPadding(px(6), 0, 0, 0);
        metric.addView(value);
        energy.addView(metric);
    }

    private void metric(
            FrameLayout host, int x, int y, String title, String val, String symbol, int color) {
        LinearLayout metric = row();
        metric.addView(new Symbol(symbol, color), px(27), px(27));
        metric.addView(text(" " + val, 25, color(R.color.ink), true));
        metric.setContentDescription(title + " " + val);
        place(host, metric, x, y, 154, 39);
    }

    private void refreshDashboard() {
        if (online) events.refresh();
        else events.reconnect();
    }

    private void createScrollPage() {
        homeScroll = scrollingHome
                ? new com.mario.lynkband.ui.PullRefreshScrollView(getContext(), this::refreshDashboard)
                : new ScrollView(getContext());
        homeScroll.setContentDescription(scrollingHome ? "车况与车控" : "操作与设置");
        homeScroll.setVerticalScrollBarEnabled(false);
        homeScroll.setVerticalFadingEdgeEnabled(true);
        homeScroll.setFadingEdgeLength(px(28));
        homeScroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        dial.addView(homeScroll, new LayoutParams(-1, -1));
        page = new FrameLayout(getContext());
        homeScroll.addView(page, new ScrollView.LayoutParams(-1, -2));
        if (!scrollingHome) page.setMinimumHeight(px(narrow ? 530 : 480));
        if (settingsScrollPage != screen || scrollingHome) settingsOffset = 0;
        settingsScrollPage = scrollingHome ? null : screen;
        int restore = scrollingHome ? homeOffset : settingsOffset;
        if (restore > 0) {
            homeScroll.addOnLayoutChangeListener(
                    new OnLayoutChangeListener() {
                        @Override
                        public void onLayoutChange(
                                View v,
                                int l,
                                int t,
                                int r,
                                int b,
                                int oldL,
                                int oldT,
                                int oldR,
                                int oldB) {
                            v.removeOnLayoutChangeListener(this);
                            v.scrollTo(0, restore);
                        }
                    });
        }
    }

    private java.util.List<JSONObject> executingLayout;

    private int actionTiles(int gridTop, int muted, boolean list, int rowHeight) {
        Map<String, ActionTile> previous = tiles;
        tiles = new HashMap<>();
        JSONObject pending = model.loadingAction();
        if (pending == null) executingLayout = null;
        else if (executingLayout == null) {
            // Keep the tapped control in place even if a provider overlay hides the entire panel.
            executingLayout = new java.util.ArrayList<>();
            boolean found = false;
            for (int i = 0; i < model.actions.length(); i++) {
                JSONObject item = model.actions.optJSONObject(i);
                if (model.showsLoading(item)) {
                    executingLayout.add(pending);
                    found = true;
                } else if (WatchState.actionable(item)) executingLayout.add(item);
            }
            if (!found) executingLayout.add(pending);
        }
        java.util.List<JSONObject> visible = new java.util.ArrayList<>();
        if (online && pending != null) visible.addAll(executingLayout);
        else if (online) {
            for (int i = 0; i < model.actions.length(); i++) {
                JSONObject item = model.actions.optJSONObject(i);
                if (model.canControl(item)) visible.add(item);
            }
        }
        int count = visible.size();
        for (int i = 0; i < count; i++) {
            JSONObject item = visible.get(i);
            boolean executing = pending != null && item == pending;
            String slot = item.optString("slot");
            ActionTile tile = previous.remove(slot);
            if (tile == null) tile = new ActionTile(list);
            else ((ViewGroup) tile.getParent()).removeView(tile);
            tiles.put(slot, tile);
            tile.bind(item, executing);
            if (list) put(tile, narrow || round ? 110 : 20, gridTop + i * rowHeight, narrow || round ? 260 : 440, 106);
            else {
                int width = round ? 158 : 216;
                boolean lone = i == count - 1 && i % 2 == 0;
                int x = lone ? (480 - width) / 2 : round ? 74 + i % 2 * 174 : 20 + i % 2 * 224;
                put(tile, x, gridTop + i / 2 * rowHeight, width, 122);
            }
        }
        for (ActionTile removed : previous.values()) removed.stopMotion();
        if (count == 0 && online) label("当前没有可用车控", 23, muted, false, 82, gridTop + 3, 316, 66);
        return list ? count : (count + 1) / 2;
    }

    private final class ActionTile extends LinearLayout {
        private final TextView caption;
        private final FrameLayout emblem;
        private View glyph;
        private int iconResource;
        private JSONObject bound;
        private WatchState.ControlState state;
        private int tint;
        private ValueAnimator transition, pulse;

        ActionTile(boolean list) {
            super(WatchFace.this.getContext());
            boolean horizontal = list && !singleColumn;
            setOrientation(horizontal ? HORIZONTAL : VERTICAL);
            setGravity(Gravity.CENTER);
            boolean night = nightScene();
            setBackground(new RippleDrawable(ColorStateList.valueOf(0x447aabcb),
                    new FrostedTile(night, px(24), px(1)), shape(Color.WHITE, 24)));
            emblem = new FrameLayout(getContext());
            int iconSize = px(horizontal ? 62 : 70);
            addView(emblem, iconSize, iconSize);
            caption = text("", 20, color(R.color.ink), true);
            caption.setGravity(Gravity.CENTER);
            caption.setIncludeFontPadding(false);
            caption.setSingleLine(true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(horizontal ? -2 : -1, px(29));
            if (horizontal) {
                lp.leftMargin = px(8);
                caption.setMaxWidth(px(round ? 162 : 320));
            } else lp.topMargin = px(4);
            addView(caption, lp);
        }

        void bind(JSONObject item, boolean executing) {
            String action = item.optString("label");
            String name = executing ? WatchState.controlName(item) + "·执行中" : WatchState.tileCaption(item);
            ControlCategory category = ControlCategory.resolve(item.optString("category"), action);
            WatchState.ControlState next = executing ? WatchState.ControlState.LOADING : WatchState.ControlState.of(item);
            int drawable = executing ? 0 : controlDrawable(category, next);
            boolean newGlyph = glyph == null || iconResource != drawable;
            if (newGlyph) {
                iconResource = drawable;
                emblem.removeAllViews();
                if (executing) {
                    Symbol spinner = new Symbol("executing", stateTint(next));
                    glyph = spinner;
                    emblem.addView(spinner, new FrameLayout.LayoutParams(px(46), px(46), Gravity.CENTER));
                } else {
                    glyph = actionIcon(drawable);
                    emblem.addView(glyph, -1, -1);
                }
            }
            bound = item;
            boolean enabled = !executing && online && model.canControl(item);
            String description = name + (enabled || executing ? "" : "，只读");
            caption.setText(name);
            caption.setTextColor(executing ? stateTint(next) : color(enabled ? R.color.ink : R.color.muted));
            setContentDescription(description);
            setOnClickListener(enabled ? v -> events.prepare(bound) : null);
            setEnabled(enabled);
            setClickable(enabled);
            setFocusable(enabled);

            int target = stateTint(next);
            if (state != next) {
                stopMotion();
                if (state == null) paintTint(target);
                else {
                    paintTint(tint);
                    transition = ValueAnimator.ofArgb(tint, target);
                    transition.setDuration(240);
                    transition.addUpdateListener(a -> paintTint((int) a.getAnimatedValue()));
                    transition.start();
                }
                if (!executing && next == WatchState.ControlState.LOADING) {
                    pulse = ObjectAnimator.ofFloat(emblem, View.ALPHA, 1f, .45f);
                    pulse.setDuration(600);
                    pulse.setRepeatMode(ValueAnimator.REVERSE);
                    pulse.setRepeatCount(ValueAnimator.INFINITE);
                    pulse.start();
                }
                state = next;
            } else if (newGlyph) paintTint(transition != null && transition.isRunning() ? tint : target);
        }

        private void paintTint(int color) {
            tint = color;
            if (glyph instanceof ImageView image) image.setImageTintList(ColorStateList.valueOf(color));
            else if (glyph instanceof Symbol symbol) {
                symbol.pen.setColor(color);
                symbol.invalidate();
            }
        }

        void stopMotion() {
            if (transition != null) transition.cancel();
            if (pulse != null) pulse.cancel();
            emblem.setAlpha(1f);
        }
    }

    private void confirmPage() {
        label("确认车控", 22, color(R.color.muted), false, 110, 32, 260, 34);
        FrameLayout emblem = new FrameLayout(getContext());
        WatchState.ControlState state = model.confirmState();
        ImageView icon = actionIcon(controlDrawable(model.confirmCategory(), state));
        icon.setImageTintList(ColorStateList.valueOf(stateTint(state)));
        place(emblem, icon, 14, 14, 60, 60);
        put(emblem, 196, 62, 88, 88);
        label(model.title(), 36, color(R.color.ink), true, 65, 146, 350, 54);
        label(value("vehicle", "领克官方卡片"), 20, color(R.color.muted), false, 85, 201, 310, 32);
        actionButton("确认操作", "check", true, 246, () -> {
            if (android.os.SystemClock.elapsedRealtime() - confirmShownAt >= 500) events.confirm();
        });
        secondaryButton("取消", "", 348, events::cancel);
    }

    private void actionButton(String name, String icon, boolean accent, int y, Runnable click) {
        chip(name, icon, accent, 60, y, 360, 88, 34, click);
    }

    private void secondaryButton(String name, String icon, int y, Runnable click) {
        chip(name, icon, false, 99, y, 282, 78, 32, click);
    }

    private void phonePage() {
        label("手机连接", 30, color(R.color.ink), true, 105, 38, 270, 45);
        put(new Symbol("link", color(R.color.link)), 218, 88, 44, 44);
        multiline(model.status, 24, color(R.color.ink), 62, 134, 356, 52);
        int y = 198;
        if (!model.note.isEmpty()) {
            multiline(model.note, 18, color(R.color.warm), 67, 186, 346, 46);
            y = 242;
        }
        if (paired && !online) {
            actionButton("重新连接", "refresh", true, y, events::reconnect);
            actionButton("选择手机", "", false, y + 102, events::pair);
            y += 204;
        } else {
            actionButton("选择手机", "link", true, y, events::pair);
            y += 102;
        }
        actionButton("接收手机更新", "", false, y, events::receiveUpdate);
        y += 102;
        actionButton("主题和昼夜", "", false, y, () -> go(Screen.THEME));
        y += 102;
        if (canChooseControlLayout()) {
            actionButton("车控布局 · " + (controlList ? "列表" : "网格"), "", false, y, () -> go(Screen.CONTROLS));
            y += 102;
        }
        if (online) {
            secondaryButton("返回车况", "back", y, () -> go(Screen.STATUS));
            y += 92;
        }
        label(BuildConfig.VERSION_NAME, 17, color(R.color.muted), false, 165, y, 150, 24);
        page.setMinimumHeight(px(y + 100));
    }

    private boolean canChooseControlLayout() {
        if (!online || singleColumn || windowWidth == 0) return false;
        int count = 0;
        for (int i = 0; i < model.actions.length(); i++) {
            if (model.canControl(model.actions.optJSONObject(i)) && ++count == 2) return true;
        }
        return false;
    }

    private void controlLayoutPage() {
        label("车控布局", 30, color(R.color.ink), true, 100, 40, 280, 46);
        int y = 112;
        if (canChooseControlLayout()) {
            actionButton(controlList ? "网格" : "网格 · 已选", "", !controlList, y, () -> setControlList(false));
            actionButton(controlList ? "列表 · 已选" : "列表", "", controlList, y + 102, () -> setControlList(true));
            y += 204;
        }
        secondaryButton("返回设置", "back", y, () -> go(Screen.PHONE));
        page.setMinimumHeight(px(y + 140));
    }

    private void setControlList(boolean value) {
        if (!canChooseControlLayout() || controlList == value) return;
        controlList = value;
        themeSettings.edit().putBoolean(CONTROL_LIST_KEY, value).apply();
        homeOffset = 0;
        clearTiles();
        drawPage();
    }

    private void themePage() {
        label("主题", 30, color(R.color.ink), true, 100, 40, 280, 46);
        label(themes.title(), 21, color(R.color.ink), true, 66, 102, 348, 40);
        label(themes.waitingForLegacyArt() ? "旧主题待同步" : "在手机上选择主题",
                18, color(R.color.muted), false, 66, 147, 348, 34);
        actionButton("日出日落", "", false, 195, () -> go(Screen.SOLAR));
        secondaryButton("返回", "back", 297, () -> go(Screen.PHONE));
        page.setMinimumHeight(px(447));
    }

    WatchThemes themes() { return themes; }

    JSONObject landscapeSnapshot() {
        return ThemeSync.snapshot(themeSettings, SCENE_KEY, false);
    }

    void mergeLandscape(JSONObject peer) {
        ThemeSync.merge(themeSettings, SCENE_KEY, false, peer);
    }

    private boolean manualSolar() {
        return ThemeSync.flag(themeSettings, "manual", false);
    }

    private String solarSummary() {
        WatchState.PhoneSolar phone = followedSolar();
        if (phone == null) return "手机未同步 · " + localSolarInfo;
        return phone.info().isEmpty() ? "已同步手机" : phone.info();
    }

    private void solarPage() {
        label("日出日落", 30, color(R.color.ink), true, 100, 53, 280, 46);
        boolean manual = manualSolar();
        actionButton("跟随手机", manual ? "" : "check", !manual, 111, () -> setManual(false));
        actionButton("用户设定", manual ? "check" : "", manual, 213, () -> setManual(true));
        int backY;
        if (manual) {
            timeButton(true, 315);
            timeButton(false, 417);
            backY = 519;
        } else {
            multiline(solarSummary(), 18, color(R.color.muted), 48, 315, 384, 48);
            backY = 373;
        }
        secondaryButton("返回", "back", backY, () -> go(Screen.THEME));
        page.setMinimumHeight(px(backY + 150));
    }

    private void setManual(boolean manual) {
        themeSettings.edit().putBoolean("manual", manual).apply();
        rendered = "";
        events.solarSettingsChanged();
        updateSolarScene();
        drawPage();
    }

    private void timeButton(boolean sunrise, int y) {
        String key = sunrise ? "riseMinute" : "setMinute";
        int minute = ThemeSync.minute(themeSettings, key, sunrise ? 360 : 1080);
        String title = sunrise ? "日出" : "日落";
        String caption = String.format(java.util.Locale.ROOT, "%s  %02d:%02d", title, minute / 60, minute % 60);
        actionButton(caption, "", false, y, () -> {
            android.app.TimePickerDialog dialog = new android.app.TimePickerDialog(getContext(),
                    nightScene() ? android.R.style.Theme_Material_Dialog_Alert : android.R.style.Theme_Material_Light_Dialog_Alert,
                    (picker, h, m) -> {
                        int selected = h * 60 + m;
                        if (selected == ThemeSync.minute(themeSettings, sunrise ? "setMinute" : "riseMinute", sunrise ? 1080 : 360)) {
                            Toast.makeText(getContext(), "请选择不同的日出和日落时间", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        themeSettings.edit().putInt(key, selected).apply();
                        setManual(true);
                    }, minute / 60, minute % 60, true);
            dialog.setTitle(title);
            dialog.show();
        });
    }

    private String fuelReading() {
        String reading = model.data.optString("fuel", "").trim();
        for (int i = 0; i < reading.length(); i++) {
            if (Character.isDigit(reading.charAt(i))) return reading;
        }
        return "";
    }

    private String value(String k, String fallback) {
        String s = model.data.optString(k);
        return s.isEmpty() ? fallback : s;
    }

    private int stateTint(WatchState.ControlState state) {
        boolean dark = nightScene();
        return switch (state) {
            case OPEN -> dark ? 0xff63d998 : 0xff16703e;
            case LOADING -> dark ? 0xffffd358 : 0xff886500;
            case UNKNOWN -> dark ? 0xffa5a9ae : 0xff666b70;
            default -> color(R.color.ink);
        };
    }

    // The PNGs include their optical padding. The watch palette supplies day/night contrast.
    private ImageView actionIcon(int drawable) {
        ImageView image = new ImageView(getContext());
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setImageResource(drawable);
        return image;
    }

    private static int controlDrawable(ControlCategory category, WatchState.ControlState state) {
        boolean open = state == WatchState.ControlState.OPEN;
        return switch (category) {
            case LOCK -> open ? R.drawable.watch_control_lock_open : R.drawable.watch_control_lock;
            case WINDOW -> open ? R.drawable.watch_control_window_open : R.drawable.watch_control_window;
            case ENGINE -> open ? R.drawable.watch_control_engine_open : R.drawable.watch_control_engine;
            case TRUNK -> open ? R.drawable.watch_control_trunk_open : R.drawable.watch_control_trunk;
            case SUNROOF -> open ? R.drawable.watch_control_sunroof_open : R.drawable.watch_control_sunroof;
            case CLIMATE -> open ? R.drawable.watch_control_climate_open : R.drawable.watch_control_climate;
            case FIND -> R.drawable.watch_control_find;
            case OTHER -> R.drawable.watch_control_other;
        };
    }

    private void label(String s, int size, int color, boolean bold, int x, int y, int w, int h) {
        TextView t = text(s, size, color, bold);
        put(t, x, y, w, h);
        int minSize = px(Math.min(17, size));
        int maxSize = px(size);
        if (minSize < maxSize) {
            t.setAutoSizeTextTypeUniformWithConfiguration(minSize, maxSize, 1, TypedValue.COMPLEX_UNIT_PX);
        }
    }

    private void multiline(String s, int size, int color, int x, int y, int w, int h) {
        TextView t = text(s, size, color, false);
        t.setSingleLine(false);
        t.setMaxLines(2);
        put(t, x, y, w, h);
    }

    private TextView text(String s, int size, int color, boolean bold) {
        TextView t = new TextView(getContext());
        t.setIncludeFontPadding(false);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setGravity(Gravity.CENTER);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(size));
        t.setText(s);
        t.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        return t;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(getContext());
        row.setGravity(Gravity.CENTER);
        return row;
    }

    private void chip(
            String name,
            String symbol,
            boolean accent,
            int x,
            int y,
            int w,
            int h,
            Runnable click) {
        chip(name, symbol, accent, x, y, w, h, 28, click);
    }

    private void chip(String name, String symbol, boolean accent, int x, int y, int w, int h,
            int textSize, Runnable click) {
        LinearLayout chip = row();
        int color = accent ? color(R.color.on_warm) : color(R.color.ink);
        if (!symbol.isEmpty()) chip.addView(new Symbol(symbol, color), px(textSize - 2), px(textSize - 2));
        if (w > 70) chip.addView(text((symbol.isEmpty() ? "" : " ") + name, textSize, color, true));
        activate(chip, name, click, accent ? color(R.color.warm) : color(R.color.control), h / 2);
        put(chip, x, y, w, h);
    }

    private void activate(View v, String desc, Runnable click, int color, int radius) {
        v.setBackground(
                new RippleDrawable(ColorStateList.valueOf(0x447aabcb), shape(color, radius), null));
        v.setContentDescription(desc);
        v.setFocusable(true);
        v.setOnClickListener(ignored -> click.run());
    }

    private GradientDrawable shape(int color, int r) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(px(r));
        return d;
    }

    private int color(int id) { return palette.getColor(id); }

    private int px(int n) {
        return Math.max(n == 0 ? 0 : 1, Math.round(n * scale));
    }

    private void put(View v, int x, int y, int w, int h) {
        if (narrow) {
            // Recenter the 480-unit reference layout on the 320-unit grid, keeping a side gutter.
            int gridWidth = 320, gutter = 6;
            int center = x + w / 2 - (480 - gridWidth) / 2;
            w = Math.min(w, gridWidth - 2 * gutter);
            x = Math.max(gutter, Math.min(gridWidth - gutter - w, center - w / 2));
        }
        place(page, v, x, y, w, h);
    }

    private void place(FrameLayout parent, View v, int x, int y, int w, int h) {
        LayoutParams lp = new LayoutParams(px(w), px(h));
        lp.leftMargin = px(x);
        lp.topMargin = px(y);
        parent.addView(v, lp);
    }

    private final class Symbol extends View {
        final String id;
        final Paint pen = new Paint(Paint.ANTI_ALIAS_FLAG);

        Symbol(String id, int color) {
            super(WatchFace.this.getContext());
            this.id = id;
            pen.setColor(color);
            pen.setStyle(Paint.Style.STROKE);
            pen.setStrokeWidth(1.8f);
            pen.setStrokeJoin(Paint.Join.ROUND);
            pen.setStrokeCap(Paint.Cap.ROUND);
        }

        private void line(Canvas c, float... xy) {
            Path p = new Path();
            p.moveTo(xy[0], xy[1]);
            for (int i = 2; i < xy.length; i += 2) p.lineTo(xy[i], xy[i + 1]);
            c.drawPath(p, pen);
        }

        @Override
        protected void onDraw(Canvas c) {
            c.save();
            c.scale(getWidth() / 24f, getHeight() / 24f);
            switch (id) {
                case "executing" -> {
                    pen.setStrokeWidth(1.65f);
                    pen.setAlpha(55);
                    c.drawCircle(12, 12, 9, pen);
                    pen.setAlpha(255);
                    boolean animate = ValueAnimator.areAnimatorsEnabled();
                    float angle = animate ? android.os.SystemClock.uptimeMillis() % 1200 * .3f : 0f;
                    c.drawArc(3, 3, 21, 21, angle - 90f, 105f, false, pen);
                    if (animate && isShown() && getWindowVisibility() == VISIBLE) postInvalidateDelayed(65);
                }
                case "battery" -> {
                    c.drawRoundRect(2, 7, 19, 17, 2, 2, pen);
                    line(c, 22, 10, 22, 14);
                    line(c, 6, 10, 6, 14, 10, 14, 10, 10);
                }
                case "fuel" -> {
                    c.drawRoundRect(4, 4, 14, 20, 1, 1, pen);
                    line(c, 6, 10, 12, 10);
                    line(c, 14, 11, 17, 11, 17, 17, 19, 19, 21, 17, 21, 9, 18, 6);
                }
                case "link" -> {
                    line(c, 7, 6, 16, 18, 12, 21, 12, 3, 17, 7, 7, 17);
                }
                case "refresh" -> {
                    c.drawArc(4, 4, 20, 20, -43, 290, false, pen);
                    line(c, 20, 4, 20, 10, 14, 10);
                }
                case "chevron" -> line(c, 3, 7, 12, 17, 21, 7);
                case "back" -> {
                    line(c, 13, 5, 6, 12, 13, 19);
                    line(c, 7, 12, 21, 12);
                }
                case "check" -> line(c, 4, 12, 10, 18, 21, 6);
            }
            c.restore();
        }
    }
}
