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
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Switch;
import java.text.SimpleDateFormat;
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

    private GarageApp app;
    private int pendingBind = NO_WIDGET;
    private WidgetPreview preview;
    private FrameLayout widgetContainer;
    private LinearLayout setupPanel;
    private TextView vehicleNameText;
    private TextView setupStatus;
    private TextView controlStatus;
    private TextView linkStatus;
    private LinearLayout logRows;
    private String renderedLogs = "";
    private LinearLayout controlPanel;
    private TextView mappingStatus;
    private TextView batteryStatus;
    private TextView voiceStatus;
    private Button connection;
    private Button authorize;
    private Button addWidget;
    private Button liveControl;
    private Button phoneConfirmation;
    private Switch voiceToggle;
    private AlertDialog officialDialog;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        app = (GarageApp) getApplication();
        if (state != null) pendingBind = state.getInt(BIND_ID, NO_WIDGET);
        setContentView(buildScreen());
    }

    @Override protected void onStart() {
        super.onStart();
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
        app.setScreen(false, null);
        super.onStop();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt(BIND_ID, pendingBind);
        super.onSaveInstanceState(state);
    }

    private View buildScreen() {
        LinearLayout layout = column();
        layout.setBackgroundColor(BG);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        layout.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout screen = column();
        int horizontal = dp(app.widgets.screenPaddingDp());
        screen.setPadding(horizontal, dp(18), horizontal, dp(24));
        if (Build.VERSION.SDK_INT >= 30) {
            layout.setOnApplyWindowInsetsListener((view, insets) -> {
                Insets bounds = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                layout.setPadding(bounds.left, 0, bounds.right, bounds.bottom);
                screen.setPadding(horizontal, dp(18) + bounds.top, horizontal, dp(24));
                return insets;
            });
        }
        scroll.addView(screen, new ScrollView.LayoutParams(-1, -2));
        add(screen, text(getString(R.string.app_name), 28, INK, true), 0);
        LinearLayout vehicleHeader = row();
        vehicleNameText = text(app.vehicleName(), 14, MUTED, false);
        vehicleHeader.addView(vehicleNameText);
        TextView editVehicleName = text("修改名称", 13, MINT, false);
        editVehicleName.setPadding(dp(10), dp(4), dp(6), dp(4));
        vehicleHeader.addView(editVehicleName);
        vehicleHeader.setOnClickListener(view -> editVehicleName());
        add(screen, vehicleHeader, 2);

        widgetContainer = new FrameLayout(this);
        widgetContainer.setBackground(rounded(PANEL, 20));
        widgetContainer.setClipToOutline(true);
        LinearLayout.LayoutParams widgetBounds = new LinearLayout.LayoutParams(-1, dp(GalaxyWidgets.HEIGHT_DP));
        widgetBounds.topMargin = dp(18);
        screen.addView(widgetContainer, widgetBounds);

        setupPanel = panel();
        add(setupPanel, text("银河官方小组件", 19, INK, true), 0);
        setupStatus = text("", 14, MUTED, false);
        add(setupPanel, setupStatus, 5);
        addWidget = button("添加银河中号小组件", this::bindWidget);
        addWidget.setActivated(true);
        add(setupPanel, addWidget, 12);
        add(screen, setupPanel, 18);

        controlPanel = panel();
        LinearLayout controlHeader = row();
        controlHeader.addView(text("车控授权", 18, INK, true), new LinearLayout.LayoutParams(0, -2, 1));
        mappingStatus = text("", 13, MINT, true);
        controlHeader.addView(mappingStatus);
        add(controlPanel, controlHeader, 0);

        controlStatus = text("", 14, MUTED, false);
        add(controlPanel, controlStatus, 4);
        liveControl = button("允许车控", this::toggleControl);
        add(controlPanel, liveControl, 12);
        phoneConfirmation = button("处理银河官方确认", this::showOfficialConfirmation);
        phoneConfirmation.setActivated(true);
        add(controlPanel, phoneConfirmation, 8);
        add(screen, controlPanel, 14);

        LinearLayout voicePanel = panel();
        LinearLayout voiceHeader = row();
        voiceHeader.addView(text("语音直控", 18, INK, true), new LinearLayout.LayoutParams(0, -2, 1));
        voiceToggle = new Switch(this);
        voiceToggle.setContentDescription("语音直控");
        voiceToggle.setThumbTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{MINT, MUTED}));
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
        add(voicePanel, text("支持小米「米家」与 OPPO/一加「小布指令（Breeno）」。无需手环或手表，开启「允许车控」与通知权限后，触发米家「手机通知」或小布指令「发送提醒」即可控车。"
                + "\nOPPO/一加侧载安装若无法开启通知权限，请先到「应用信息 → 右上角菜单」开启「允许受限制的设置」。",
                13, MUTED, false), 8);
        add(screen, voicePanel, 12);

        LinearLayout bandPanel = panel();
        add(bandPanel, text("穿戴设备（可选）", 18, INK, true), 0);
        linkStatus = text("", 14, MUTED, false);
        add(bandPanel, linkStatus, 4);
        connection = button("启动通信", this::toggleService);
        add(bandPanel, connection, 12);
        authorize = button("授权小米设备通信", () -> app.link.authorize());
        add(bandPanel, authorize, 8);
        add(bandPanel, text("仅在搭配小米手环或手表端「腕上银河」时需要启动通信。", 13, MUTED, false), 8);
        add(screen, bandPanel, 12);

        LinearLayout backgroundPanel = panel();
        LinearLayout backgroundHeader = row();
        backgroundHeader.addView(text("后台运行设置", 17, INK, true), new LinearLayout.LayoutParams(0, -2, 1));
        backgroundHeader.addView(text("›", 24, MUTED, false));
        add(backgroundPanel, backgroundHeader, 0);
        batteryStatus = text("电池与应用锁定", 13, MUTED, false);
        add(backgroundPanel, batteryStatus, 4);
        backgroundPanel.setContentDescription("后台运行设置，电池与应用锁定");
        backgroundPanel.setOnClickListener(view -> new AlertDialog.Builder(this)
                .setTitle("后台运行设置")
                .setItems(new String[]{"省电策略设置", "应用锁定引导"}, (dialog, which) -> {
                    if (which == 0) showBatteryGuide(); else showLockGuide();
                }).setNegativeButton("关闭", null).show());
        add(screen, backgroundPanel, 12);

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
        logRows = column();
        logRows.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        add(logs, logRows, 4);
        add(screen, logs, 12);

        return layout;
    }

    private void updateScreen() {
        vehicleNameText.setText(app.vehicleName());
        GalaxyWidgets.Card card = app.widgets.primary();
        showWidget(card);
        String link = app.link.status();
        String compact = WearDiagnostics.compactLinkStatus(link, app.hasService());
        linkStatus.setText(compact);
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
                    : "请先安装并登录吉利银河 App。");
            addWidget.setEnabled(hasProvider && pendingBind == NO_WIDGET);
            return;
        }
        updateBandControls(app.widgets.controls());
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
            if (latest) item.setBackground(rounded(event.warning() ? 0xff3a3028 : PRIMARY_CONTROL, 10));
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
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("例如：银河星耀 8");
        input.setText(app.vehicleName());
        input.setSelection(input.getText().length());
        LinearLayout container = column();
        container.setPadding(dp(20), dp(8), dp(20), 0);
        container.addView(input);
        new AlertDialog.Builder(this)
                .setTitle("自定义车辆名称")
                .setMessage("手环与手表首屏将显示此名称，留空则恢复为「" + GarageApp.DEFAULT_VEHICLE_NAME + "」。")
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
        clipboard.setPrimaryClip(ClipData.newPlainText("智在银河诊断信息", app.exportDiagnostics()));
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
        MiHomeVoice.Command[] commands = MiHomeVoice.Command.values();
        String[] choices = new String[commands.length];
        for (int index = 0; index < commands.length; index++) {
            MiHomeVoice.Command command = commands[index];
            choices[index] = command.label + "\n" + command.code;
        }
        new AlertDialog.Builder(this)
                .setTitle("点按复制语音指令码")
                .setItems(choices, (dialog, index) -> getSystemService(ClipboardManager.class)
                        .setPrimaryClip(ClipData.newPlainText("语音指令码", commands[index].code)))
                .setNegativeButton("关闭", null)
                .show();
    }

    private void updateBandControls(Map<CarAction, GalaxyWidgets.Control> controls) {
        boolean armed = app.isArmed();
        boolean hasReady = controls.values().stream().anyMatch(GalaxyWidgets.Control::ready);
        mappingStatus.setText("功能已同步 · " + controls.size() + "/" + WidgetTree.SLOTS.size());
        controlStatus.setText(armed ? "车控已授权 · 可用语音直控或穿戴端控车"
                : hasReady ? "车控未授权 · 点击下方允许后即可控车" : "请先刷新车况或点按上方预览卡片补标按钮");
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
            toast("请打开吉利银河查看当前操作");
            return;
        }
        long revision = app.widgets.revision(card);
        officialDialog = new AlertDialog.Builder(this)
                .setTitle(prompt.title())
                .setMessage("由银河官方小组件请求确认。")
                .setNeutralButton("返回", null)
                .setNegativeButton("取消", (dialog, which) -> app.respondOnPhone(card, prompt, revision, false))
                .setPositiveButton("确认", (dialog, which) -> app.respondOnPhone(card, prompt, revision, true))
                .show();
    }

    private void showBatteryGuide() {
        new AlertDialog.Builder(this)
                .setTitle("省电策略设置")
                .setMessage("在「智在银河」的应用信息中，打开「电池用量」「耗电管理」或「省电策略」，选择「无限制」或开启「允许完全后台行为 / 允许后台活动」。"
                        + "\n\n小米手机也可从「省电与电池 → 应用智能省电」为智在银河选择「无限制」；OPPO / 一加（ColorOS）可在「耗电管理」开启「允许完全后台行为」和「允许自启动」。"
                        + "\n\n使用 Android 电池优化列表时，选中智在银河并设为「不优化」。")
                .setNegativeButton("返回", null)
                .setNeutralButton("电池优化列表", (dialog, which) -> openBatteryOptimizationSettings())
                .setPositiveButton("打开应用信息", (dialog, which) -> openApplicationSettings())
                .show();
    }

    private void showLockGuide() {
        new AlertDialog.Builder(this)
                .setTitle("应用锁定引导")
                .setMessage("打开最近任务，找到「智在银河」。长按卡片或打开卡片右上角菜单，选择「锁定」或锁形图标。"
                        + "\n\n部分小米手机可在「手机管家 → 设置 → 优化加速 → 锁定任务」中开启；OPPO / 一加（ColorOS）可在最近任务卡片菜单直接锁定。"
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
            toast("请在系统设置中搜索智在银河");
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
        new AlertDialog.Builder(this)
                .setTitle("允许车控")
                .setMessage("请确认当前卡片选中目标车辆，且映射按钮与银河小组件配置一致。语音指令（米家 / 小布指令）或穿戴端确认将直接触发对应的银河官方按钮。"
                        + "\n\n安全与免责提示：本工具为独立开源项目，与吉利汽车、小米及 OPPO 无官方关联。远程控车存在通信与刷新延迟，车辆实际状态以吉利银河 App 为准；"
                        + "语音直控收到来自米家或小布指令的匹配通知后将直接执行，请谨慎设置触发口令；如搭配穿戴设备使用，请开启锁屏密码，离车或非本人佩戴时及时关闭车控授权。")
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
        app.disarm();
        try {
            if (app.widgets.configure(this, card)) return;
        } catch (RuntimeException unavailable) {
            android.util.Log.w("GarageActivity", "Widget configuration did not open");
        }
        new AlertDialog.Builder(this).setTitle("在吉利银河里配置")
                .setMessage("打开吉利银河的「设置小组件」，选择这辆车和需要的按钮，保存后返回这里查看卡片。")
                .setNegativeButton("取消", null)
                .setPositiveButton("打开吉利银河", (dialog, which) -> {
                    Intent launch = getPackageManager().getLaunchIntentForPackage(GalaxyWidgets.PACKAGE);
                    if (launch != null) {
                        app.widgets.recheckAfterConfiguration();
                        startActivity(launch);
                    }
                }).show();
    }

    private void labelButton(GalaxyWidgets.Card card, WidgetTree.Button target) {
        if (target.role().equals(WidgetTree.SETTINGS)) {
            configure(card);
            return;
        }
        if (target.role().equals(WidgetTree.REFRESH)) {
            app.refreshOnPhone();
            return;
        }
        if (target.match() != null) {
            new AlertDialog.Builder(this).setTitle(buttonLabel(card, target))
                    .setMessage("修改功能请到这张卡片的银河官方配置页面。")
                    .setPositiveButton("知道了", null).show();
            return;
        }
        CarAction[] actions = CarAction.values();
        String[] choices = Arrays.copyOf(Arrays.stream(actions).map(action -> action.label).toArray(String[]::new),
                actions.length + 1);
        choices[actions.length] = "清除标记";
        new AlertDialog.Builder(this).setTitle("补标卡片按钮")
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
        new AlertDialog.Builder(this).setTitle("此时的车门锁态")
                .setMessage("请先对照吉利银河 App 的当前锁态。手环确认时会显示切换后的目标状态。")
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
        CarAction action = binding == null ? null : CarAction.fromId(binding.action());
        if (action == null) return target.label().isEmpty() ? "待标记" : target.label() + "（待标记）";
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
        value.setBackground(rounded(PANEL, 18));
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
        view.setTextColor(color);
        if (strong) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private Button button(String title, Runnable clicked) {
        Button button = new Button(this);
        button.setText(title);
        button.setAllCaps(false);
        button.setTextColor(new ColorStateList(new int[][]{{-android.R.attr.state_enabled},
                {android.R.attr.state_activated}, {}}, new int[]{0xff81939a, MINT, INK}));
        button.setTextSize(14);
        button.setMinHeight(dp(46));
        button.setMinimumHeight(dp(46));
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(13), dp(6), dp(13), dp(6));
        GradientDrawable surface = rounded(CONTROL, 12);
        surface.setColor(new ColorStateList(new int[][]{{-android.R.attr.state_enabled},
                {android.R.attr.state_activated}, {}}, new int[]{CONTROL, PRIMARY_CONTROL, CONTROL}));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x299ae8ce), surface, null));
        button.setOnClickListener(view -> clicked.run());
        return button;
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
            StringBuilder label = new StringBuilder("银河官方小组件");
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
            new AlertDialog.Builder(MainActivity.this).setTitle("小组件操作")
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
