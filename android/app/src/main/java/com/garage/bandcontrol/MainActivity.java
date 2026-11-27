package com.garage.bandcontrol;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.appwidget.AppWidgetManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.ViewOutlineProvider;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Switch;
import com.garage.appearance.ThemeArtwork;
import com.garage.appearance.ThemeClock;
import com.garage.appearance.ThemeConfig;
import com.garage.appearance.ThemeDialogs;
import com.garage.appearance.ThemeEditor;
import com.garage.appearance.ThemeStyles;

import java.text.SimpleDateFormat;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.Locale;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public final class MainActivity extends Activity {
    static final String SHOW_CONFIRMATION = "com.garage.bandcontrol.SHOW_CONFIRMATION";
    private static final int BG = Color.rgb(16, 22, 27);
    private static final int PANEL = Color.rgb(25, 35, 43);
    private static final int CONTROL = Color.rgb(35, 49, 59);
    private static final int PRIMARY_CONTROL = Color.rgb(32, 61, 56);
    private static final int INK = Color.rgb(239, 246, 247);
    private static final int MUTED = Color.rgb(164, 183, 189);
    private static final int MINT = Color.rgb(154, 232, 206);
    private static final int BLUETOOTH_REQUEST = 73;
    private static final String BIND_ID = "pendingWidgetId";
    private static final int NO_WIDGET = AppWidgetManager.INVALID_APPWIDGET_ID;
    private static final int LOG_VIEW_HEIGHT_DP = 220;
    private static final int[][] BUTTON_STATES = {{-android.R.attr.state_enabled}, {android.R.attr.state_activated}, {}};

    private GarageApp app;
    private int pendingBind = NO_WIDGET;
    private WidgetPreview preview;
    private FrameLayout widgetContainer;
    private LinearLayout setupPanel;
    private TextView vehicleNameText;
    private TextView setupStatus;
    private TextView controlStatus;
    private TextView linkStatus;
    private TextView connectedDevices;
    private ScrollView logScroll;
    private LinearLayout logRows;
    private String renderedLogs = "";
    private LinearLayout controlPanel;
    private TextView mappingStatus;
    private TextView lynkIconStatus;
    private TextView batteryStatus;
    private TextView voiceStatus;
    private Button connection;
    private Button authorize;
    private Button addWidget;
    private Button liveControl;
    private Button phoneConfirmation;
    private Switch voiceToggle;
    private AlertDialog officialDialog;
    private ThemeClock themeClock;
    private final ThemeStyles themeStyles = new ThemeStyles();
    private final ThemeDialogs dialogs = new ThemeDialogs(this, themeStyles);
    private FrameLayout homeRoot;
    private ImageView homeBackdrop;
    private View homeFade;
    private View navigationShade;
    private FrameLayout themePreview;
    private ImageView themeImage;
    private View themePreviewShade;
    private TextView themeSummary;
    private Button bandDisplayButton;
    private Switch hapticsToggle;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        app = (GarageApp) getApplication();
        if (state != null) pendingBind = state.getInt(BIND_ID, NO_WIDGET);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            getWindow().setStatusBarContrastEnforced(false);
            getWindow().setNavigationBarContrastEnforced(false);
        }
        setContentView(buildScreen());
        themeClock = new ThemeClock(this, List.of(app::phoneTheme, app::wearableTheme), this::renderThemes);
        if (app.needsBrandSelection()) chooseBrand();
    }

    private void chooseBrand() {
        String[] names = BrandConfig.OPTIONS.stream().map(config -> config.name).toArray(String[]::new);
        dialogs.builder().setTitle("选择车辆品牌")
                .setSingleChoiceItems(names, BrandConfig.OPTIONS.indexOf(app.brand),
                        (dialog, index) -> {
                            if (pendingBind != NO_WIDGET) app.widgets.cancelBind(pendingBind);
                            pendingBind = NO_WIDGET;
                            app.selectBrand(BrandConfig.OPTIONS.get(index));
                            dialog.dismiss();
                            recreate();
                        }).setCancelable(false).setPositiveButton("使用当前品牌", (dialog, which) -> {
                            app.selectBrand(app.brand);
                        }).show();
    }

    @Override protected void onStart() {
        super.onStart();
        themeClock.start();
        app.setScreen(true, this::updateScreen);
        updateScreen();
        handleConfirmationIntent(getIntent());
    }

    @Override protected void onResume() {
        super.onResume();
        PowerManager power = getSystemService(PowerManager.class);
        boolean exempt = power.isIgnoringBatteryOptimizations(getPackageName());
        batteryStatus.setText(exempt ? "Android 电池优化已豁免" : "Android 电池优化未豁免");
        updateVoiceControls();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleConfirmationIntent(intent);
    }

    @Override protected void onStop() {
        themeClock.stop();
        app.setScreen(false, null);
        super.onStop();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt(BIND_ID, pendingBind);
        super.onSaveInstanceState(state);
    }

    private View buildScreen() {
        homeRoot = new FrameLayout(this);
        homeBackdrop = new ImageView(this);
        homeBackdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        homeBackdrop.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        homeRoot.addView(homeBackdrop, new FrameLayout.LayoutParams(-1, -1));
        homeFade = new View(this);
        homeFade.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        homeRoot.addView(homeFade, new FrameLayout.LayoutParams(-1, dp(248), Gravity.TOP));
        LinearLayout layout = column();
        homeRoot.addView(layout, new FrameLayout.LayoutParams(-1, -1));
        navigationShade = new View(this);
        navigationShade.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        homeRoot.addView(navigationShade, new FrameLayout.LayoutParams(-1, 0, Gravity.BOTTOM));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        layout.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout screen = column();
        int horizontal = dp(app.widgets.screenPaddingDp());
        screen.setPadding(horizontal, dp(14), horizontal, dp(24));
        if (Build.VERSION.SDK_INT >= 30) {
            layout.setOnApplyWindowInsetsListener((view, insets) -> {
                Insets bounds = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                layout.setPadding(bounds.left, bounds.top, bounds.right, 0);
                screen.setPadding(horizontal, dp(14), horizontal, dp(24) + bounds.bottom);
                ViewGroup.LayoutParams shade = navigationShade.getLayoutParams();
                if (shade.height != bounds.bottom) {
                    shade.height = bounds.bottom;
                    navigationShade.setLayoutParams(shade);
                }
                return insets;
            });
        }
        scroll.addView(screen, new ScrollView.LayoutParams(-1, -2));
        LinearLayout vehicleHeader = row();
        LinearLayout vehicle = column();
        vehicleNameText = photoText(app.vehicleName(), 19, INK, true);
        vehicleNameText.setSingleLine(true);
        vehicleNameText.setEllipsize(TextUtils.TruncateAt.END);
        vehicle.addView(vehicleNameText);
        TextView editVehicleName = photoText("修改名称", 12, MINT, false);
        add(vehicle, editVehicleName, 2);
        vehicle.setOnClickListener(view -> editVehicleName());
        vehicleHeader.addView(vehicle, new LinearLayout.LayoutParams(0, -2, 1));
        Button brand = button(app.brand.name + " ▾", this::chooseBrand, true);
        brand.setContentDescription("车辆品牌：" + app.brand.name + "，切换");
        LinearLayout.LayoutParams brandBounds = new LinearLayout.LayoutParams(-2, -2);
        brandBounds.setMarginStart(dp(6));
        vehicleHeader.addView(brand, brandBounds);

        ImageButton themeButton = new ImageButton(this);
        themeButton.setContentDescription("手机主题");
        themeButton.setTooltipText("手机主题");
        themeButton.setImageResource(R.drawable.ic_theme_palette);
        themeButton.setScaleType(ImageView.ScaleType.CENTER);
        themeButton.setPadding(0, 0, 0, 0);
        themeButton.setStateListAnimator(null);
        themeButton.setElevation(0f);
        themeStyles.bind(themeButton, (view, palette) -> {
            ((ImageButton) view).setImageTintList(ColorStateList.valueOf(palette == null ? INK : 0xfff3f8fc));
            applyButtonBackground(view, palette, true);
        });
        themeButton.setOnClickListener(view -> openPhoneTheme());
        LinearLayout.LayoutParams themeBounds = new LinearLayout.LayoutParams(dp(48), dp(48));
        themeBounds.setMarginStart(dp(6));
        vehicleHeader.addView(themeButton, themeBounds);
        add(screen, vehicleHeader, 0);

        widgetContainer = new FrameLayout(this);
        widgetContainer.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(20));
            }
        });
        widgetContainer.setClipToOutline(true);
        LinearLayout.LayoutParams widgetBounds = new LinearLayout.LayoutParams(-1, dp(app.brand.heightDp));
        widgetBounds.topMargin = dp(10);
        screen.addView(widgetContainer, widgetBounds);

        setupPanel = panel();
        add(setupPanel, text("银河官方小组件".replace("银河", app.brand.name), 19, INK, true), 0);
        setupStatus = text("", 14, MUTED, false);
        add(setupPanel, setupStatus, 5);
        String addWidgetLabel = app.brand == BrandConfig.ZEEKR ? "添加极氪大号小组件"
                : "添加" + app.brand.name + "中号小组件";
        addWidget = button(addWidgetLabel, this::bindWidget);
        addWidget.setActivated(true);
        add(setupPanel, addWidget, 12);
        add(screen, setupPanel, 18);

        controlPanel = panel();
        LinearLayout controlHeader = row();
        controlHeader.addView(text("车控授权", 18, INK, true), new LinearLayout.LayoutParams(0, -2, 1));
        mappingStatus = text("", 13, MINT, true);
        controlHeader.addView(mappingStatus);
        add(controlPanel, controlHeader, 0);

        lynkIconStatus = text("", 13, MUTED, false);
        lynkIconStatus.setVisibility(View.GONE);
        add(controlPanel, lynkIconStatus, 4);

        controlStatus = text("", 14, MUTED, false);
        add(controlPanel, controlStatus, 4);
        liveControl = button("允许车控", this::toggleControl);
        add(controlPanel, liveControl, 12);
        phoneConfirmation = button("处理银河官方确认".replace("银河", app.brand.name), this::showOfficialConfirmation);
        phoneConfirmation.setActivated(true);
        add(controlPanel, phoneConfirmation, 8);
        add(screen, controlPanel, 14);

        LinearLayout voicePanel = panel();
        LinearLayout voiceHeader = row();
        voiceHeader.addView(text("米家语音", 18, INK, true), new LinearLayout.LayoutParams(0, -2, 1));
        voiceToggle = styledSwitch("米家语音直控");
        voiceToggle.setOnCheckedChangeListener((button, checked) -> {
            if (checked != app.voiceEnabled()) app.setVoiceEnabled(checked);
        });
        voiceHeader.addView(voiceToggle);
        add(voicePanel, voiceHeader, 0);
        voiceStatus = text("", 13, MUTED, false);
        add(voicePanel, voiceStatus, 4);
        LinearLayout voiceActions = row();
        voiceActions.addView(button("通知权限", this::openNotificationAccess), new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams accessBounds = new LinearLayout.LayoutParams(0, -2, 1);
        accessBounds.setMarginStart(dp(8));
        voiceActions.addView(button("指令码", this::showVoiceCommands), accessBounds);
        add(voicePanel, voiceActions, 10);
        add(voicePanel, text(app.brand == BrandConfig.ZEEKR ? "极氪目前提供米家通知测试。"
                : "无需手环或手表，开启「允许车控」与通知权限后，通过小爱触发米家场景即可控车。",
                13, MUTED, false), 8);
        add(screen, voicePanel, 12);

        LinearLayout backgroundPanel = panel();
        LinearLayout backgroundHeader = row();
        backgroundHeader.addView(text("后台运行设置", 17, INK, true), new LinearLayout.LayoutParams(0, -2, 1));
        backgroundHeader.addView(text("›", 24, MUTED, false));
        add(backgroundPanel, backgroundHeader, 0);
        batteryStatus = text("电池与应用锁定", 13, MUTED, false);
        add(backgroundPanel, batteryStatus, 4);
        backgroundPanel.setContentDescription("后台运行设置，电池与应用锁定");
        backgroundPanel.setOnClickListener(view -> dialogs.builder()
                .setTitle("后台运行设置")
                .setItems(new String[]{"省电策略设置", "应用锁定引导"}, (dialog, which) -> {
                    if (which == 0) showBatteryGuide(); else showLockGuide();
                }).setNegativeButton("关闭", null).show());
        add(screen, backgroundPanel, 12);

        LinearLayout bandPanel = panel();
        add(bandPanel, text("穿戴设备", 18, INK, true), 0);
        linkStatus = text("", 14, MUTED, false);
        add(bandPanel, linkStatus, 4);
        connectedDevices = text("", 14, INK, false);
        connectedDevices.setLineSpacing(dp(3), 1f);
        add(bandPanel, connectedDevices, 4);
        connection = button("启动通信", this::toggleService);
        add(bandPanel, connection, 12);
        authorize = button("授权小米设备通信", () -> app.link.authorize());
        add(bandPanel, authorize, 8);
        add(bandPanel, buildWearableThemePreview(), 8);
        bandDisplayButton = button("手环布局", this::chooseBandDisplay);
        add(bandPanel, bandDisplayButton, 8);
        LinearLayout hapticsRow = row();
        hapticsRow.setPadding(dp(13), dp(10), dp(12), dp(10));
        bindSurface(hapticsRow, CONTROL, 12);
        LinearLayout hapticsCopy = column();
        hapticsCopy.addView(text("车控震动", 14, INK, false));
        add(hapticsCopy, text("轻量版手环确认、执行与异常提示时短震", 12, MUTED, false), 2);
        hapticsRow.addView(hapticsCopy, new LinearLayout.LayoutParams(0, -2, 1));
        hapticsToggle = styledSwitch("手环车控震动");
        hapticsToggle.setOnCheckedChangeListener((button, checked) -> {
            if (checked != app.wearableHaptics()) app.setWearableHaptics(checked);
        });
        hapticsRow.addView(hapticsToggle);
        hapticsRow.setOnClickListener(view -> hapticsToggle.toggle());
        add(bandPanel, hapticsRow, 8);
        add(bandPanel, text("仅在搭配小米手环或手表端「腕上银河」时需要启动通信。", 13, MUTED, false), 8);
        add(screen, bandPanel, 12);

        LinearLayout logs = panel();
        LinearLayout logHeader = row();
        logHeader.addView(text("运行日志", 17, INK, true), new LinearLayout.LayoutParams(0, -2, 1));
        TextView copyLogs = text("复制", 13, MINT, false);
        copyLogs.setPadding(dp(12), dp(10), dp(12), dp(10));
        copyLogs.setOnClickListener(view -> copyDiagnostics());
        logHeader.addView(copyLogs);
        TextView clear = text("清空", 13, MINT, false);
        clear.setPadding(dp(12), dp(10), 0, dp(10));
        clear.setOnClickListener(view -> app.clearUiEvents());
        logHeader.addView(clear);
        add(logs, logHeader, 0);
        logScroll = new ScrollView(this) {
            @Override public boolean onInterceptTouchEvent(MotionEvent ev) {
                if (canScrollVertically(1) || canScrollVertically(-1)) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                return super.onInterceptTouchEvent(ev);
            }

            @Override public boolean onTouchEvent(MotionEvent ev) {
                if (canScrollVertically(1) || canScrollVertically(-1)) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                int action = ev.getActionMasked();
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    getParent().requestDisallowInterceptTouchEvent(false);
                }
                return super.onTouchEvent(ev);
            }
        };
        logScroll.setFillViewport(true);
        logScroll.setVerticalScrollBarEnabled(true);
        logScroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        bindSurface(logScroll, BG, 12);
        logScroll.setClipToOutline(true);
        logRows = column();
        logRows.setPadding(dp(8), dp(8), dp(8), dp(8));
        logRows.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        logScroll.addView(logRows, new ScrollView.LayoutParams(-1, -2));
        LinearLayout.LayoutParams logBounds = new LinearLayout.LayoutParams(-1, dp(LOG_VIEW_HEIGHT_DP));
        logBounds.topMargin = dp(6);
        logs.addView(logScroll, logBounds);
        add(screen, logs, 12);

        return homeRoot;
    }

    private View buildWearableThemePreview() {
        themePreview = new FrameLayout(this);
        bindSurface(themePreview, CONTROL, 14);
        themePreview.setClipToOutline(true);
        themeImage = new ImageView(this);
        themeImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
        themeImage.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        themePreview.addView(themeImage, new FrameLayout.LayoutParams(-1, dp(92)));
        themePreviewShade = new View(this);
        themePreviewShade.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        themePreview.addView(themePreviewShade, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout copy = column();
        copy.setGravity(Gravity.CENTER_VERTICAL);
        copy.setPadding(dp(14), dp(12), dp(14), dp(12));
        copy.addView(photoText("穿戴主题", 16, INK, true));
        themeSummary = photoText("经典", 13, INK, false);
        add(copy, themeSummary, 3);
        themePreview.addView(copy, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL));
        themePreview.setMinimumHeight(dp(92));
        themePreview.setOnClickListener(view -> openWearableTheme());
        return themePreview;
    }

    private void openPhoneTheme() {
        ThemeEditor.showOnPhone(this, themeStyles, app.phoneTheme(), "手机主题", false, theme -> {
            app.setPhoneTheme(theme);
            themeClock.refresh();
        });
    }

    private void openWearableTheme() {
        ThemeEditor.showOnPhone(this, themeStyles, app.wearableTheme(), "穿戴主题", true, theme -> {
            app.setWearableTheme(theme);
            themeClock.refresh();
        });
    }

    private void chooseBandDisplay() {
        int selected = app.bandDisplay().twoPage() ? 1 : 0;
        dialogs.builder().setTitle("手环布局")
                .setSingleChoiceItems(new String[]{"单屏 · 车况与车控上下滚动", "双屏 · 车况与车控分开展示"}, selected,
                        (dialog, which) -> {
                            app.selectBandDisplay(which == 1);
                            dialog.dismiss();
                        }).setNegativeButton("取消", null).show();
    }

    private void renderThemes(ZonedDateTime now) {
        ThemeConfig.Variant system = ThemeStyles.systemVariant(this);
        renderPhoneTheme(now, system);
        renderWearableThemePreview(now, system);
    }

    private void renderPhoneTheme(ZonedDateTime now, ThemeConfig.Variant system) {
        ThemeConfig config = app.phoneTheme();
        ThemeConfig.Variant active = config.activeVariant(now, system);
        String asset = config.asset(active);
        boolean day = active == ThemeConfig.Variant.DAY;
        boolean themed = ThemeArtwork.show(homeBackdrop, asset, day ? 0x00000000 : 0x180d1626, 1);
        ThemeStyles.Palette palette = themed ? ThemeStyles.forVariant(active) : null;
        themeStyles.apply(palette);
        homeRoot.setBackgroundColor(themed ? palette.background() : BG);
        if (themed) {
            homeFade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{0x990d1724, 0x730d1724, 0x000d1724}));
            navigationShade.setBackgroundColor(0xb00d1724);
        } else {
            homeFade.setBackground(null);
            navigationShade.setBackgroundColor(BG);
        }
        ThemeStyles.applySystemBars(getWindow(), Build.VERSION.SDK_INT >= 30 ? Color.TRANSPARENT : BG, false);
    }

    private void renderWearableThemePreview(ZonedDateTime now, ThemeConfig.Variant system) {
        ThemeConfig config = app.wearableTheme();
        ThemeConfig.Variant active = config.activeVariant(now, system);
        String asset = config.wearableAsset(active);
        boolean previewHasArtwork = ThemeArtwork.show(themeImage, asset, 0);
        themePreviewShade.setBackground(previewHasArtwork
                ? new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xc40d1724, 0x880d1724, 0x1a0d1724})
                : null);
        String summary = config.summary(active, ThemeConfig.PHONE_SYSTEM_LABEL);
        themeSummary.setText(summary);
        themePreview.setContentDescription("穿戴主题，" + summary);
    }

    private void updateScreen() {
        vehicleNameText.setText(app.vehicleName());
        bandDisplayButton.setText("手环布局 · " + (app.bandDisplay().twoPage() ? "双屏" : "单屏"));
        hapticsToggle.setChecked(app.wearableHaptics());
        GalaxyWidgets.Card card = app.widgets.primary();
        showWidget(card);
        String link = app.link.status();
        String compact = WearDiagnostics.compactLinkStatus(link, app.hasService());
        linkStatus.setText(compact);
        List<String> devices = app.link.connectedDevices();
        connectedDevices.setText(String.join("\n", devices));
        connectedDevices.setVisibility(devices.isEmpty() ? View.GONE : View.VISIBLE);
        connection.setText(app.hasService() ? "停止通信" : "启动通信");
        connection.setActivated(!app.hasService());
        boolean showAuthorize = app.link.canAuthorize() || app.link.isAuthorizing();
        authorize.setVisibility(showAuthorize ? View.VISIBLE : View.GONE);
        authorize.setEnabled(app.link.canAuthorize());
        authorize.setText(app.link.isAuthorizing() ? "正在等待小米运动健康授权…" : "授权小米设备通信");
        updateLogs();
        updateVoiceControls();
        controlPanel.setVisibility(card == null ? View.GONE : View.VISIBLE);
        if (card == null) {
            phoneConfirmation.setVisibility(View.GONE);
            boolean hasProvider = app.widgets.isProviderInstalled();
            setupStatus.setText(hasProvider ? "授权添加后，同步卡片车况和四个车控按钮。"
                    : "请先安装并登录" + app.brand.appName + " App。");
            addWidget.setEnabled(hasProvider && pendingBind == NO_WIDGET);
            return;
        }
        updateBandControls(app.widgets.controls());
        if (app.widgets.showsUnavailable(card)) {
            mappingStatus.setText("当前车辆不支持远程控制");
        } else if (app.brand.recognizedIconsOnly() && app.widgets.showsSetup(card)) {
            mappingStatus.setText("请在" + app.brand.appName + " App 绑定车辆并配置小组件");
        }
        preview.updateAccessibility();
    }

    private void updateVoiceControls() {
        boolean enabled = app.voiceEnabled();
        voiceToggle.setChecked(enabled);
        String permission = !MiHomeNotificationListener.hasAccess(this) ? "待授权通知权限"
                : app.voiceListenerConnected() ? "通知已就绪" : "正在连接通知服务";
        voiceStatus.setText((enabled ? "已开启" : "已关闭") + " · " + permission);

    }

    private void updateLogs() {
        List<GarageApp.UiEvent> events = app.uiEvents();
        String signature = events.toString();
        if (signature.equals(renderedLogs)) return;
        renderedLogs = signature;
        logRows.removeAllViews();
        logScroll.scrollTo(0, 0);
        if (events.isEmpty()) {
            add(logRows, text("暂无记录", 14, MUTED, false), 8);
            return;
        }
        SimpleDateFormat time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
        for (int i = events.size() - 1; i >= 0; i--) {
            GarageApp.UiEvent event = events.get(i);
            boolean latest = i == events.size() - 1;
            int color = event.warning() ? 0xffffbd69 : latest ? MINT : INK;
            LinearLayout item = column();
            item.setPadding(dp(10), dp(9), dp(10), dp(9));
            if (latest) bindSurface(item, event.warning() ? 0xff3a3028 : PRIMARY_CONTROL, 10);
            LinearLayout heading = row();
            TextView title = text("•  " + event.title(), 14, color, latest);
            heading.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
            TextView stamp = text(time.format(new Date(event.time())), 11, MUTED, false);
            stamp.setPadding(dp(8), 0, 0, 0);
            heading.addView(stamp);
            add(item, heading, 0);
            if (!event.detail().isEmpty()) add(item, text(event.detail(), 12, MUTED, false), 3);
            add(logRows, item, latest ? 0 : 3);
        }
    }

    private void editVehicleName() {
        EditText input = new EditText(dialogs.context());
        dialogs.styleInput(input);
        input.setSingleLine(true);
        input.setHint("输入车辆名称");
        input.setText(app.vehicleName());
        input.setSelection(input.getText().length());
        LinearLayout container = column();
        container.setPadding(dp(24), dp(8), dp(24), dp(4));
        container.addView(input);
        dialogs.builder()
                .setTitle("自定义车辆名称")
                .setView(container)
                .setNeutralButton("恢复默认", (dialog, which) -> app.setVehicleName(""))
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", (dialog, which) -> app.setVehicleName(input.getText().toString()))
                .show();
    }

    private void copyDiagnostics() {
        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        if (clipboard == null) {
            toast("无法访问剪贴板");
            return;
        }
        clipboard.setPrimaryClip(ClipData.newPlainText("智在银河诊断信息".replace("银河", app.brand.name), app.exportDiagnostics()));
        toast("已复制诊断信息与日志");
    }

    private void openNotificationAccess() {
        try {
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        } catch (ActivityNotFoundException unavailable) {
            toast("请在系统设置中搜索通知使用权");
        }
    }

    private void showVoiceCommands() {
        String namespace = app.brand.namespace;
        MiHomeVoice.Command[] commands = MiHomeVoice.commands(namespace);
        String[] choices = new String[commands.length];
        String[] codes = new String[commands.length];
        for (int index = 0; index < commands.length; index++) {
            MiHomeVoice.Command command = commands[index];
            String label = "LYNKCO_V1".equals(namespace) ? switch (command) {
                case TEST -> command.label;
                case LOCK_TOGGLE -> "切换车锁（免标记，可上锁或解锁）";
                case WINDOW_TOGGLE -> "切换车窗（免标记，可开窗或关窗）";
                case TRUNK_TOGGLE -> "切换后备箱（免标记，可打开或关闭）";
                default -> command.label;
            } : command.label;
            codes[index] = namespace + ":" + command.name();
            choices[index] = label + "\n" + codes[index];
        }
        dialogs.builder()
                .setTitle("点按复制通知指令码")
                .setItems(choices, (dialog, index) -> getSystemService(ClipboardManager.class)
                        .setPrimaryClip(ClipData.newPlainText("米家指令码", codes[index])))
                .setNegativeButton("关闭", null)
                .show();
    }

    private void updateBandControls(Map<CarAction, GalaxyWidgets.Control> controls) {
        boolean armed = app.isArmed();
        boolean hasReady = controls.values().stream().anyMatch(GalaxyWidgets.Control::ready);
        mappingStatus.setText(app.brand == BrandConfig.LYNKCO
                ? "已同步 " + controls.size() + " 个功能"
                : "功能已同步 · " + controls.size() + "/" + app.brand.slots.size());
        List<String> recognized = new ArrayList<>();
        if (app.brand == BrandConfig.LYNKCO) {
            for (GalaxyWidgets.Control control : controls.values()) {
                if (control.target() == null) continue;
                LynkWidgetIcons.Kind icon = control.target().button().lynkIcon();
                if (icon != null && !recognized.contains(icon.label())) recognized.add(icon.label());
            }
        }
        lynkIconStatus.setVisibility(recognized.isEmpty() ? View.GONE : View.VISIBLE);
        if (!recognized.isEmpty()) lynkIconStatus.setText("自动识别 · " + String.join("、", recognized));
        String setupHint = app.brand.recognizedIconsOnly() ? "请刷新车况或在" + app.brand.appName + " App 核对小组件按钮"
                : "请先刷新车况或点按上方预览卡片补标按钮";
        String armedHint = app.brand == BrandConfig.ZEEKR ? "车控已授权 · 可用穿戴端控车"
                : "车控已授权 · 可用米家语音或穿戴端控车";
        controlStatus.setText(armed ? armedHint
                : hasReady ? "车控未授权 · 点击下方允许后即可控车" : setupHint);
        liveControl.setText(armed ? "关闭车控" : "允许车控");
        liveControl.setActivated(!armed);
        liveControl.setEnabled(armed || hasReady);
        phoneConfirmation.setVisibility(app.phoneConfirmationCard() != null ? View.VISIBLE : View.GONE);
    }

    private void handleConfirmationIntent(Intent intent) {
        if (intent == null || !SHOW_CONFIRMATION.equals(intent.getAction())) return;
        intent.setAction(null);
        showOfficialConfirmation();
    }

    private void showOfficialConfirmation() {
        if (officialDialog != null && officialDialog.isShowing()) return;
        GalaxyWidgets.Card card = app.phoneConfirmationCard();
        WidgetTree.OfficialConfirmation prompt = app.widgets.officialConfirmation(card);
        if (prompt == null) {
            toast("请打开" + app.brand.appName + "查看当前操作");
            return;
        }
        long revision = app.widgets.revision(card);
        officialDialog = dialogs.builder()
                .setTitle(prompt.title())
                .setMessage("由银河官方小组件请求确认。".replace("银河", app.brand.name))
                .setNeutralButton("返回", null)
                .setNegativeButton("取消", (dialog, which) -> app.respondOnPhone(card, prompt, revision, false))
                .setPositiveButton("确认", (dialog, which) -> app.respondOnPhone(card, prompt, revision, true))
                .show();
    }

    private void showBatteryGuide() {
        dialogs.builder()
                .setTitle("省电策略设置")
                .setMessage("在「智在银河」的应用信息中，打开「电池用量」或「省电策略」，选择「无限制」。".replace("银河", app.brand.name)
                        + "如果系统提供的是「允许后台活动」，请开启它。"
                        + "\n\n小米手机也可从「省电与电池 → 应用智能省电」为智在银河选择「无限制」。".replace("银河", app.brand.name)
                        + "\n\n使用 Android 电池优化列表时，选中智在银河并设为「不优化」。".replace("银河", app.brand.name))
                .setNegativeButton("返回", null)
                .setNeutralButton("电池优化列表", (dialog, which) -> openBatteryOptimizationSettings())
                .setPositiveButton("打开应用信息", (dialog, which) -> openApplicationSettings())
                .show();
    }

    private void showLockGuide() {
        dialogs.builder()
                .setTitle("应用锁定引导")
                .setMessage("打开最近任务，找到「智在银河」。长按卡片或打开卡片菜单，选择「锁定」或锁形图标。".replace("银河", app.brand.name)
                        + "\n\n部分小米手机可在「手机管家 → 设置 → 优化加速 → 锁定任务」中开启。"
                        + "锁定后，一键清理时会保留这个任务。入口随手机系统而异。")
                .setPositiveButton("知道了", null)
                .show();
    }

    private void openBatteryOptimizationSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        } catch (ActivityNotFoundException unavailable) {
            openApplicationSettings();
        }
    }

    private void openApplicationSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", getPackageName(), null)));
        } catch (ActivityNotFoundException unavailable) {
            toast("请在系统设置中搜索智在银河".replace("银河", app.brand.name));
        }
    }

    private void toggleService() {
        if (app.hasService()) {
            stopService(new Intent(this, BandService.class));
            return;
        }
        List<String> permissions = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && !getPreferences(MODE_PRIVATE).getBoolean("askedNotifications", false)) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("askedNotifications", true).apply();
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (permissions.isEmpty()) startBandService();
        else requestPermissions(permissions.toArray(new String[0]), BLUETOOTH_REQUEST);
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        if (request != BLUETOOTH_REQUEST) return;
        if (Build.VERSION.SDK_INT >= 31
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            toast("请在系统设置里允许连接附近设备");
            return;
        }
        startBandService();
    }

    private void startBandService() {
        try {
            startForegroundService(new Intent(this, BandService.class));
        } catch (RuntimeException unavailable) {
            toast("系统未能启动手环通信");
        }
    }

    private void toggleControl() {
        if (app.isArmed()) {
            app.disarm();
            return;
        }
        dialogs.builder()
                .setTitle("允许车控")
                .setMessage("请确认当前卡片选中目标车辆，且映射按钮与" + app.brand.name + "小组件配置一致。米家语音指令或穿戴端确认将触发对应的官方按钮。"
                        + (app.brand == BrandConfig.LYNKCO ? "领克语音无需穿戴设备。切换车锁、车窗或后备箱会点击对应的官方按钮，由车主判断当前状态。与本次动作匹配的官方弹窗可由伴侣提交。" : "")
                        + "\n\n安全与免责提示：本工具为独立开源项目，与吉利汽车及小米无官方关联。远程控车存在通信与刷新延迟，车辆实际状态以" + app.brand.appName + " App 为准；"
                        + "米家语音收到通知后将直接执行，请谨慎设置触发口令；如搭配穿戴设备使用，请开启锁屏密码，离车或非本人佩戴时及时关闭车控授权。")
                .setNegativeButton("取消", null)
                .setPositiveButton("已知晓并允许", (dialog, which) -> app.arm())
                .show();
    }

    private void bindWidget() {
        try {
            app.disarm();
            pendingBind = app.widgets.allocate(this);
            updateScreen();
        } catch (RuntimeException unavailable) {
            toast("当前系统未能打开小组件授权页面");
        }
    }

    @Override protected void onActivityResult(int request, int resultCode, Intent data) {
        super.onActivityResult(request, resultCode, data);
        if (request == GalaxyWidgets.BIND_REQUEST && pendingBind != NO_WIDGET) {
            int id = pendingBind;
            pendingBind = NO_WIDGET;
            if (resultCode == RESULT_OK) {
                if (!app.widgets.finishBind(id)) toast("小组件绑定未完成");
            } else {
                app.widgets.cancelBind(id);
            }
            updateScreen();
        }
    }

    private void showWidget(GalaxyWidgets.Card card) {
        widgetContainer.setVisibility(card == null ? View.GONE : View.VISIBLE);
        setupPanel.setVisibility(card == null ? View.VISIBLE : View.GONE);
        if (card == (preview == null ? null : preview.card)) return;
        widgetContainer.removeAllViews();
        preview = null;
        if (card == null) return;
        if (card.view.getParent() instanceof ViewGroup parent) parent.removeView(card.view);
        preview = new WidgetPreview(card);
        widgetContainer.addView(preview, new FrameLayout.LayoutParams(-1, -1));
    }

    private void configure(GalaxyWidgets.Card card) {
        if (app.brand == BrandConfig.LYNKCO) {
            Intent launch = getPackageManager().getLaunchIntentForPackage(app.brand.packageName);
            if (launch != null) startActivity(launch);
            else toast("请安装中国版领克 App");
            return;
        }
        app.disarm();
        try {
            if (app.widgets.configure(this, card)) return;
        } catch (RuntimeException unavailable) {
            android.util.Log.w("GarageActivity", "Widget configuration did not open");
        }
        dialogs.builder().setTitle("在" + app.brand.appName + "里配置")
                .setMessage("打开官方 App 配置小组件，选择车辆和需要的按钮，保存后返回这里查看卡片。")
                .setNegativeButton("取消", null)
                .setPositiveButton("打开" + app.brand.appName, (dialog, which) -> {
                    Intent launch = getPackageManager().getLaunchIntentForPackage(app.brand.packageName);
                    if (launch != null) {
                        app.widgets.recheckAfterConfiguration();
                        startActivity(launch);
                    }
                }).show();
    }

    private void labelButton(GalaxyWidgets.Card card, WidgetTree.Button target) {
        if (app.brand == BrandConfig.LYNKCO && target.role().isEmpty()) {
            String[] choices = java.util.stream.Stream.concat(LynkMark.CATEGORIES.stream(), java.util.stream.Stream.of("清除标记")).toArray(String[]::new);
            String detected = target.lynkIcon() == null ? "" : "自动识别：" + target.lynkIcon().label() + "\n";
            dialogs.builder().setTitle(detected + "车主明确标记：" + target.slot() + "\n描述：" + target.label() + "\n签名：" + target.fingerprint())
                    .setItems(choices, (dialog, index) -> {
                        if (index == LynkMark.CATEGORIES.size()) { saveBinding(card, target, "", ""); return; }
                        String category = choices[index];
                        if ("寻车".equals(category) || "充电口".equals(category)) {
                            saveBinding(card, target, LynkWidgets.binding(target).action(), category + ":");
                            return;
                        }
                        String[] directions = category.equals("锁") ? new String[]{"当前已解锁 → 上锁", "当前已上锁 → 解锁", "未知"}
                                : new String[]{"当前关闭 → 打开（须已验证点按）", "当前开启 → 关闭（须已验证点按）", "未知"};
                        dialogs.builder().setTitle("核对卡片当前状态及真机点按方向")
                                .setItems(directions, (d, i) -> saveBinding(card, target, LynkWidgets.binding(target).action(),
                                        category + ":" + (i == 2 ? "" : category.equals("锁") ? (i == 0 ? "lock" : "unlock") : (i == 0 ? "open" : "close"))))
                                .setNegativeButton("取消", null).show();
                    }).setNegativeButton("取消", null).show();
            return;
        }
        if (target.role().equals(app.brand.settings)) {
            configure(card);
            return;
        }
        if (target.role().equals(app.brand.refresh)) {
            app.refreshOnPhone();
            return;
        }
        if (target.match() != null) {
            dialogs.builder().setTitle(buttonLabel(card, target))
                    .setMessage("修改功能请到这张卡片的银河官方配置页面。".replace("银河", app.brand.name))
                    .setPositiveButton("知道了", null).show();
            return;
        }
        if (app.brand.recognizedIconsOnly()) {
            toast("此按钮尚未识别，请在" + app.brand.appName + " App 核对");
            return;
        }
        CarAction[] actions = Arrays.stream(CarAction.values()).filter(action -> !action.id.startsWith("lynk_")).toArray(CarAction[]::new);
        String[] choices = Arrays.copyOf(Arrays.stream(actions).map(action -> action.label).toArray(String[]::new),
                actions.length + 1);
        choices[actions.length] = "清除标记";
        dialogs.builder().setTitle("补标卡片按钮")
                .setItems(choices, (dialog, index) -> {
                    if (index == actions.length) {
                        saveBinding(card, target, "", "");
                    } else {
                        CarAction action = actions[index];
                        if (action == CarAction.LOCK) chooseLockState(card, target);
                        else saveBinding(card, target, action.id, "");
                    }
                }).setNegativeButton("取消", null).show();
    }

    private void chooseLockState(GalaxyWidgets.Card card, WidgetTree.Button target) {
        dialogs.builder().setTitle("此时的车门锁态")
                .setMessage("请先对照" + app.brand.appName + " App 的当前锁态。手环确认时会显示切换后的目标状态。")
                .setNeutralButton("取消", null)
                .setNegativeButton("当前已解锁", (dialog, which) ->
                        saveBinding(card, target, CarAction.LOCK.id, WidgetTree.UNLOCKED))
                .setPositiveButton("当前已上锁", (dialog, which) ->
                        saveBinding(card, target, CarAction.LOCK.id, WidgetTree.LOCKED)).show();
    }

    private void saveBinding(GalaxyWidgets.Card card, WidgetTree.Button target, String action, String lockState) {
        app.disarm();
        if (app.widgets.bindButton(card, target, action, lockState)) toast("已更新按钮标记");
        else toast("官方卡片刚刚更新，请重新选择按钮");
    }

    private String buttonLabel(GalaxyWidgets.Card card, WidgetTree.Button target) {
        if (!target.role().isEmpty()) return target.label();
        GalaxyWidgets.Binding binding = card.binding(target);
        if (binding == null && app.brand == BrandConfig.LYNKCO) binding = LynkWidgets.binding(target);
        CarAction action = binding == null ? null : CarAction.fromId(binding.action());
        if (action == null) return target.label().isEmpty() ? "待标记" : target.label() + "（待标记）";
        if (binding.mark() != null) return action.label + " · 车主标记：" + binding.mark().category() + " · " + binding.mark().direction();
        if (target.lynkIcon() != null) return target.lynkIcon().label() + " · 请核对方向";
        String source = target.match() != null ? "自动识别" : "手动标记";
        String state = switch (binding.lockState()) {
            case WidgetTree.LOCKED -> "，已上锁";
            case WidgetTree.UNLOCKED -> "，已解锁";
            default -> "";
        };
        return action.label + "（" + source + state + "）";
    }

    private int dp(int size) { return Math.round(getResources().getDisplayMetrics().density * size); }
    private void toast(String value) { app.note(value, "", false); }

    private LinearLayout column() {
        LinearLayout value = new LinearLayout(this);
        value.setOrientation(LinearLayout.VERTICAL);
        return value;
    }

    private LinearLayout row() {
        LinearLayout value = new LinearLayout(this);
        value.setGravity(Gravity.CENTER_VERTICAL);
        return value;
    }

    private LinearLayout panel() {
        LinearLayout value = column();
        value.setPadding(dp(16), dp(14), dp(16), dp(16));
        bindSurface(value, PANEL, 18);
        return value;
    }

    private void add(LinearLayout parent, View child, int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(top);
        parent.addView(child, params);
    }

    private TextView text(String value, int size, int color, boolean strong) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        themeStyles.bind(view, (target, palette) -> ((TextView) target).setTextColor(foreground(color, palette)));
        if (strong) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private TextView photoText(String value, int size, int color, boolean strong) {
        TextView view = text(value, size, color, strong);
        themeStyles.bind(view, (target, palette) -> ((TextView) target).setTextColor(
                palette == null ? color : color == MINT ? 0xffb5f1df : 0xfff3f8fc));
        return view;
    }

    private Button button(String title, Runnable clicked) {
        return button(title, clicked, false);
    }

    private Button button(String title, Runnable clicked, boolean quiet) {
        Button button = new Button(this);
        button.setText(title);
        button.setAllCaps(false);
        button.setTextSize(quiet ? 13 : 14);
        button.setMinHeight(dp(46));
        button.setMinimumHeight(dp(46));
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        int horizontal = dp(quiet ? 8 : 13);
        button.setPadding(horizontal, dp(6), horizontal, dp(6));
        button.setStateListAnimator(null);
        button.setElevation(0f);
        themeStyles.bind(button, (target, palette) -> {
            if (quiet) ((Button) target).setTextColor(palette == null ? MUTED : 0xffdce6ed);
            else ((Button) target).setTextColor(new ColorStateList(BUTTON_STATES, new int[]{
                    foreground(0xff81939a, palette), foreground(MINT, palette), foreground(INK, palette)}));
            applyButtonBackground(target, palette, quiet);
        });
        button.setOnClickListener(view -> clicked.run());
        return button;
    }

    private void applyButtonBackground(View view, ThemeStyles.Palette palette, boolean quiet) {
        int ripple = quiet ? 0x25ffffff : (foreground(MINT, palette) & 0x00ffffff) | 0x29000000;
        if (quiet) {
            view.setBackground(new RippleDrawable(ColorStateList.valueOf(ripple), null, rounded(Color.WHITE, 12)));
            return;
        }
        int control = surfaceColor(CONTROL, palette);
        GradientDrawable surface = rounded(control, 12);
        surface.setColor(new ColorStateList(BUTTON_STATES, new int[]{control, surfaceColor(PRIMARY_CONTROL, palette), control}));
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(ripple), surface, null));
    }

    private void bindSurface(View view, int color, int radius) {
        themeStyles.bind(view, (target, palette) -> target.setBackground(rounded(surfaceColor(color, palette), radius)));
    }

    private Switch styledSwitch(String description) {
        Switch toggle = new Switch(this);
        toggle.setContentDescription(description);
        themeStyles.bind(toggle, (view, palette) -> ((Switch) view).setThumbTintList(new ColorStateList(
                new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{foreground(MINT, palette), foreground(MUTED, palette)})));
        return toggle;
    }

    private static int foreground(int color, ThemeStyles.Palette palette) {
        if (palette == null) return color;
        if (color == MINT) return palette.accent();
        if (color == MUTED || color == 0xff81939a) return palette.muted();
        if (color == 0xffffbd69) return palette == ThemeStyles.DAY ? 0xff8d4b13 : color;
        return palette.ink();
    }

    private static int surfaceColor(int color, ThemeStyles.Palette palette) {
        if (palette == null) return color;
        if (color == BG) return palette.background();
        if (color == PANEL) return palette == ThemeStyles.DAY ? 0xe6f5f9fc : 0xe6132132;
        if (color == PRIMARY_CONTROL) return palette.primary();
        if (color == 0xff3a3028) return palette == ThemeStyles.DAY ? 0xfff6e7d6 : color;
        return palette.control();
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(radius));
        return background;
    }

    private final class WidgetPreview extends FrameLayout {
        private final GalaxyWidgets.Card card;

        WidgetPreview(GalaxyWidgets.Card card) {
            super(MainActivity.this);
            this.card = card;
            addView(card.view, new FrameLayout.LayoutParams(-1, -1));
            updateAccessibility();
        }

        void updateAccessibility() {
            boolean setup = app.widgets.showsSetup(card);
            setImportantForAccessibility(setup ? View.IMPORTANT_FOR_ACCESSIBILITY_NO : View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            card.view.setImportantForAccessibility(setup
                    ? View.IMPORTANT_FOR_ACCESSIBILITY_AUTO : View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        }

        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            if (app.widgets.showsSetup(card)) return;
            VehicleStatus reading = app.widgets.vehicle();
            StringBuilder label = new StringBuilder("银河官方小组件".replace("银河", app.brand.name));
            if (reading.totalKm() != null) label.append("，总续航 ").append(reading.totalKm()).append(" 公里");
            if (reading.electricKm() != null) label.append("，纯电 ").append(reading.electricKm()).append(" 公里");
            if (reading.fuelKm() != null) label.append("，燃油 ").append(reading.fuelKm()).append(" 公里");
            if (!reading.motion().isEmpty()) label.append("，").append(reading.motion());
            info.setContentDescription(label);
            info.setClickable(true);
            info.addAction(new AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, "小组件操作"));
        }

        @Override public boolean performAccessibilityAction(int action, Bundle arguments) {
            if (action != AccessibilityNodeInfo.ACTION_CLICK || app.widgets.showsSetup(card)) {
                return super.performAccessibilityAction(action, arguments);
            }
            List<WidgetTree.Button> choices = app.widgets.buttons(card);
            if (choices.isEmpty()) return false;
            String[] names = choices.stream().map(target -> buttonLabel(card, target)).toArray(String[]::new);
            dialogs.builder().setTitle("小组件操作")
                    .setItems(names, (dialog, index) -> labelButton(card, choices.get(index))).show();
            return true;
        }

        @Override public boolean onInterceptTouchEvent(MotionEvent event) {
            return !app.widgets.showsSetup(card);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (app.widgets.showsSetup(card)) return super.onTouchEvent(event);
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                WidgetTree.Button selected = null;
                long smallest = Long.MAX_VALUE;
                int x = (int) event.getX();
                int y = (int) event.getY();
                for (WidgetTree.Button target : app.widgets.buttons(card)) {
                    Rect box = target.bounds();
                    long area = (long) box.width() * box.height();
                    if (box.contains(x, y) && area < smallest) {
                        selected = target;
                        smallest = area;
                    }
                }
                if (selected != null) {
                    performClick();
                    labelButton(card, selected);
                }
            }
            return true;
        }

        @Override public boolean performClick() { return super.performClick(); }
    }
}
