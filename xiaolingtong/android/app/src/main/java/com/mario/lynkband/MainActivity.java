package com.mario.lynkband;

import com.mario.lynkband.solar.ThemeSync;
import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.app.TimePickerDialog;
import com.mario.lynkband.solar.SolarController;
import com.mario.lynkband.ui.AmbientMotion;
import android.annotation.SuppressLint;
import com.mario.lynkband.transport.PairedDevice;
import com.mario.lynkband.transport.ControlCategory;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.appwidget.AppWidgetHostView;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import java.io.IOException;
import java.io.InputStream;
import org.json.JSONException;

public final class MainActivity extends Activity {
    private WristApp app;
    private PhoneWearUpdates wearUpdates;
    private LinearLayout page, group, rows;
    private Button apkType, rpkType;
    private FrameLayout hostFrame;
    private TextView state, last;
    private Button bind, authorize, watch;
    private Switch voiceSwitch, voiceConfirm;
    private Button voiceAccess;
    private boolean triedAutoBinding, triedAutoCommunication;
    private TextView noSlots;
    private AlertDialog itemPicker;
    private String pickerVehicle, pickerSlot;
    private SharedPreferences themePrefs;
    private ThemeLibrary.Entry renderedTheme;
    private final ExecutorService themeWorker = Executors.newSingleThreadExecutor();
    private AlertDialog themeProgress;
    private final SharedPreferences.OnSharedPreferenceChangeListener landscapeListener = (prefs, key) -> {
        if (PhoneLandscape.KEY.equals(key)) refreshThemeControls();
    };
    private SolarController solar;
    private boolean night, changingScene;
    private LinearLayout landscapeBackground;
    private View landscapeFade;
    private ImageView landscapeImage;
    private AmbientMotion landscapeMotion;
    private TextView themeCaption, solarInfo;
    private Button landscapeButton, timingButton, riseButton, setButton, locationButton;
    private static final int REQUEST_COMMUNICATION = 74;
    private static final int PICK_THEME_ZIP = 75;
    private static final String HIDE_FROM_RECENTS = "hideFromRecents";

    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        night = PhoneLandscape.night(base);
        Configuration appearance = new Configuration();
        appearance.uiMode = (base.getResources().getConfiguration().uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                | (night ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
        applyOverrideConfiguration(appearance);
    }

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        clearLegacyLoginCookies();
        app = (WristApp) getApplication();
        wearUpdates = new PhoneWearUpdates(this, saved);
        triedAutoBinding = saved != null && saved.getBoolean("triedAutoBinding");
        triedAutoCommunication = saved != null && saved.getBoolean("triedAutoCommunication");
        getWindow().setStatusBarColor(getColor(R.color.bg));
        getWindow().setNavigationBarColor(getColor(R.color.bg));
        themePrefs = PhoneLandscape.preferences(this);
        solar = new SolarController(this, themePrefs, this::sceneChanged);
        if (!night) getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        ScrollView scroll = new com.mario.lynkband.ui.PullRefreshScrollView(this, () -> toast(app.refreshCard()));
        scroll.setId(R.id.phone_scroll);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 35) {
            scroll.setOnApplyWindowInsetsListener((v, insets) -> {
                android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars()
                        | android.view.WindowInsets.Type.displayCutout());
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return insets;
            });
        }
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(12), dp(26), dp(12), dp(24));
        scroll.addView(page);
        FrameLayout root = new FrameLayout(this);
        addFixedLandscape(root);
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);

        addPageHeading();
        section("卡片", false);
        hostFrame = new FrameLayout(this);
        group.addView(hostFrame, new LinearLayout.LayoutParams(-1, dp(175)));
        bind = button("", v -> binding());
        section("设备", true);
        LinearLayout types = new LinearLayout(this);
        types.setBackground(glass(getColor(R.color.bg), 0x49, 0x27));
        types.setPadding(dp(3), dp(3), dp(3), dp(3));
        group.addView(types);
        apkType=typeButton(types, "android", true);
        rpkType=typeButton(types, "Vela", false);
        state = line("", 17, getColor(R.color.link));
        state.setPadding(dp(4),dp(15),dp(4),dp(6));
        watch = button("选择已配对的手表", v -> selectWatch());
        if ("Xiaomi".equalsIgnoreCase(Build.MANUFACTURER)) {
            button("锁屏连接设置", v -> new AlertDialog.Builder(this)
                    .setTitle("允许锁屏后连接手表")
                    .setMessage("在小领通的应用信息中，点「省电策略 → 无限制」。返回后保留后台通信开启，锁屏时手表就可以重新请求车况。")
                    .setNegativeButton("返回", null)
                    .setPositiveButton("打开应用信息", (dialog, which) -> startActivity(new Intent(
                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.fromParts("package", getPackageName(), null)))).show());
        }
        authorize = button("授权 Vela 手环通信", v -> app.link.authorize());
        button("穿戴端更新", v -> wearUpdates.show(app.androidWear()));
        section("车控", true);
        line("对照官方卡片，点「编辑」选择按钮类型。", 14, getColor(R.color.muted));
        noSlots = line("", 14, getColor(R.color.muted));
        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        group.addView(rows);
        last = line("", 14, getColor(R.color.muted));
        section("米家", true);
        voiceSwitch = new Switch(this);
        voiceSwitch.setText("允许小爱语音车控");
        voiceSwitch.setTextColor(getColor(R.color.ink));
        voiceSwitch.setMinHeight(dp(52));
        voiceSwitch.setOnClickListener(v -> app.setVoice(voiceSwitch.isChecked()));
        group.addView(voiceSwitch);
        voiceConfirm = new Switch(this);
        voiceConfirm.setMinHeight(dp(52));
        voiceConfirm.setText("语音执行前再次确认");
        voiceConfirm.setTextColor(getColor(R.color.ink));
        voiceConfirm.setOnClickListener(v -> app.setVoiceConfirmation(voiceConfirm.isChecked()));
        group.addView(voiceConfirm);
        voiceAccess = button("", v -> startActivity(new Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        button("配置米家场景", v -> new VoiceSceneSetup(this, app).show());
        section("外观", true);
        landscapeButton = button("", v -> chooseLandscape());
        Switch syncTheme = new Switch(this);
        syncTheme.setText("同步主题到穿戴设备");
        syncTheme.setTextColor(getColor(R.color.ink));
        syncTheme.setMinHeight(dp(52));
        syncTheme.setChecked(app.syncThemeToWearable());
        syncTheme.setOnCheckedChangeListener((button, enabled) -> app.setThemeSyncToWearable(enabled));
        group.addView(syncTheme);
        timingButton = button("", v -> chooseTiming());
        riseButton = button("", v -> chooseTime(true));
        setButton = button("", v -> chooseTime(false));
        locationButton = button("使用大致位置计算日出日落", v -> solar.locate());
        solarInfo = line("", 14, getColor(R.color.muted));
        solarInfo.setPadding(dp(4), dp(10), dp(4), 0);
        section("应用", true);
        SharedPreferences appSettings = getPreferences(MODE_PRIVATE);
        Switch hideFromRecents = new Switch(this);
        hideFromRecents.setText("在最近任务中隐藏");
        hideFromRecents.setTextColor(getColor(R.color.ink));
        hideFromRecents.setMinHeight(dp(52));
        hideFromRecents.setChecked(appSettings.getBoolean(HIDE_FROM_RECENTS, false));
        hideFromRecents.setOnCheckedChangeListener((button, hidden) -> {
            appSettings.edit().putBoolean(HIDE_FROM_RECENTS, hidden).apply();
            applyRecentTasksVisibility(hidden);
        });
        group.addView(hideFromRecents);
        button("检查手机应用更新", v -> wearUpdates.showPhone());
        TextView version = text(BuildConfig.VERSION_NAME, 13, getColor(R.color.muted));
        version.setGravity(Gravity.CENTER);
        version.setPadding(0, dp(16), 0, 0);
        page.addView(version);
        solar.initialize();
        render();
    }

    @Override protected void onStart() {
        super.onStart();
        applyRecentTasksVisibility(getPreferences(MODE_PRIVATE).getBoolean(HIDE_FROM_RECENTS, false));
        themePrefs.registerOnSharedPreferenceChangeListener(landscapeListener);
        refreshThemeControls(); // Catch changes received while the screen was stopped.
        app.screen(true, this::render);
        solar.start();
    }

    private void applyRecentTasksVisibility(boolean hidden) {
        ActivityManager manager = getSystemService(ActivityManager.class);
        for (ActivityManager.AppTask task : manager.getAppTasks()) {
            try {
                task.setExcludeFromRecents(hidden);
            } catch (IllegalArgumentException taskRemoved) {
                // Android may remove a task after returning the task list.
            }
        }
    }

    @Override protected void onPostResume() {
        super.onPostResume();
        wearUpdates.resume();
        if (!triedAutoBinding && app.card.view() == null && app.card.autoBind()) {
            triedAutoBinding = true;
            app.card.bind(this);
            if (app.card.binding()) return;
        }
        if (!triedAutoCommunication && !app.service) {
            triedAutoCommunication = true;
            startCommunication();
        }
    }

    @Override protected void onSaveInstanceState(Bundle saved) {
        super.onSaveInstanceState(saved);
        saved.putBoolean("triedAutoBinding", triedAutoBinding);
        saved.putBoolean("triedAutoCommunication", triedAutoCommunication);
        wearUpdates.save(saved);
    }
    @Override protected void onStop() {
        wearUpdates.stop();
        themePrefs.unregisterOnSharedPreferenceChangeListener(landscapeListener);
        solar.stop();
        app.screen(false, null);
        hostFrame.removeAllViews();
        super.onStop();
    }

    private void addFixedLandscape(FrameLayout root) {
        landscapeBackground = new LinearLayout(this);
        landscapeBackground.setOrientation(LinearLayout.VERTICAL);
        landscapeBackground.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        root.addView(landscapeBackground, new FrameLayout.LayoutParams(-1, -1));
        // Fit the square artwork to the phone width. Shallow windows trim sky and foreground.
        FrameLayout scenery = new FrameLayout(this);
        LinearLayout.LayoutParams artwork = new LinearLayout.LayoutParams(-1, 0);
        landscapeBackground.addView(scenery, artwork);
        landscapeBackground.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int height = Math.round((b - t) * 2f / 3f);
            if (artwork.height != height) {
                artwork.height = height;
                scenery.setLayoutParams(artwork);
            }
        });
        landscapeImage = new ImageView(this);
        landscapeImage.setId(R.id.phone_landscape_image);
        landscapeImage.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        landscapeImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
        scenery.addView(landscapeImage, new FrameLayout.LayoutParams(-1, -1));
        View shade = new View(this);
        shade.setBackgroundColor(night ? 0x3009131d : android.graphics.Color.TRANSPARENT);
        shade.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        scenery.addView(shade, new FrameLayout.LayoutParams(-1, -1));
        landscapeFade = new View(this);
        scenery.addView(landscapeFade, new FrameLayout.LayoutParams(-1, -1));
        landscapeMotion = new AmbientMotion(this);
        landscapeMotion.setCenterCrop(true);
        scenery.addView(landscapeMotion, new FrameLayout.LayoutParams(-1, -1));
    }

    private void addPageHeading() {
        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.VERTICAL);
        heading.setPadding(dp(4), dp(12), dp(4), dp(16));
        TextView title = text(getString(R.string.app_name), 29, night ? 0xffffffff : 0xff10222c);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setShadowLayer(dp(3), 0, dp(1), night ? 0x99000000 : 0xafffffff);
        heading.addView(title);
        themeCaption = text("", 15, night ? 0xfff3f7fb : 0xff14232e);
        themeCaption.setShadowLayer(dp(2), 0, dp(1), night ? 0x66000000 : 0xaaffffff);
        heading.addView(themeCaption);
        heading.setOnClickListener(v -> chooseLandscape());
        page.addView(heading);
    }

    private void refreshThemeControls() {
        PhoneLandscape scene = PhoneLandscape.SKY;
        ThemeLibrary.Entry selected = app.themes.selected();
        renderedTheme = selected;
        String title = selected == null ? scene.title : selected.title() + " · " + selected.versionLabel();
        int base = scene.baseColor(night);
        int rgb = base & 0x00ffffff;
        landscapeBackground.setBackgroundColor(base);
        landscapeFade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{rgb, rgb, rgb, rgb, rgb | 0x20000000, rgb | 0x85000000,
                        rgb | 0xdc000000, base}));
        android.graphics.Bitmap preview = app.themes.preview(night);
        if (preview == null) landscapeImage.setImageResource(scene.image(night));
        else landscapeImage.setImageBitmap(preview);
        landscapeMotion.setScene(selected == null ? scene.name() : selected.id().toUpperCase(java.util.Locale.ROOT), night);
        String caption = title + (night ? " · 夜间" : " · 日间");
        themeCaption.setText(caption);
        ((View) themeCaption.getParent()).setContentDescription(getString(R.string.app_name) + "，" + caption + "，选择主题");
        landscapeButton.setText("主题 · " + title);
        boolean manual = ThemeSync.flag(themePrefs, "manual", false);
        timingButton.setText(manual ? "日夜切换 · 手动时间" : "日夜切换 · 自动日出日落");
        riseButton.setText("日出 · " + timeLabel(ThemeSync.minute(themePrefs, "riseMinute", 360)));
        setButton.setText("日落 · " + timeLabel(ThemeSync.minute(themePrefs, "setMinute", 1080)));
        riseButton.setVisibility(manual ? View.VISIBLE : View.GONE);
        setButton.setVisibility(manual ? View.VISIBLE : View.GONE);
        locationButton.setVisibility(manual ? View.GONE : View.VISIBLE);
        solarInfo.setVisibility(manual ? View.GONE : View.VISIBLE);
    }

    private void sceneChanged(boolean nextNight, String info) {
        if (nextNight != night) {
            if (!changingScene && !isFinishing()) {
                changingScene = true;
                recreate(); // Apply the same resource palette to controls, icons and native dialogs.
            }
        } else {
            solarInfo.setText(info);
        }
    }

    private void chooseLandscape() {
        if (themeProgress != null) return;
        List<ThemeLibrary.SavedTheme> entries = new ArrayList<>();
        entries.add(null);
        entries.addAll(app.themes.savedCatalog());
        ArrayAdapter<ThemeLibrary.SavedTheme> adapter = new ArrayAdapter<>(this, 0, entries) {
            @Override public View getView(int position, View recycled, ViewGroup parent) {
                ThemeListRow row = recycled instanceof ThemeListRow available ? available : new ThemeListRow();
                ThemeLibrary.SavedTheme choice = getItem(position);
                if (choice == null) {
                    row.bind(PhoneLandscape.SKY.title, "", app.themes.selected() == null ? "使用中" : "", null);
                } else {
                    ThemeLibrary.Entry entry = choice.entry();
                    row.bind(entry.title(), entry.versionLabel(), app.themes.isSelected(entry) ? "使用中" : "",
                            () -> confirmThemeDeletion(choice, () -> {
                                entries.subList(1, entries.size()).clear();
                                entries.addAll(app.themes.savedCatalog());
                                notifyDataSetChanged();
                            }));
                }
                return row;
            }
        };
        new AlertDialog.Builder(this).setTitle("选择主题")
                .setAdapter(adapter, (dialog, which) -> {
                    ThemeLibrary.SavedTheme choice = entries.get(which);
                    if (choice == null) {
                        applyTheme(null);
                        return;
                    }
                    if (!app.themes.isSelected(choice.entry())) {
                        loadTheme("正在读取主题", () -> app.themes.savedTheme(choice), pack -> {
                            if (pack != null) applyTheme(pack);
                            else toast(choice.readFailure());
                        });
                    }
                }).setNeutralButton("导入主题包", (dialog, which) -> pickThemeZip())
                .setPositiveButton("在线主题", (dialog, which) -> browseOnlineThemes())
                .setNegativeButton("取消", null).show();
    }

    private void confirmThemeDeletion(ThemeLibrary.SavedTheme choice, Runnable refresh) {
        String message = "删除本地保存的「" + choice.entry().title() + "」？";
        if (app.themes.isSelected(choice.entry())) message += "\n删除后将切回默认主题。";
        new AlertDialog.Builder(this).setTitle("删除主题").setMessage(message)
                .setNegativeButton("取消", null).setPositiveButton("删除", (dialog, which) -> {
                    boolean wasSelected = app.themes.isSelected(choice.entry());
                    try {
                        app.themes.deleteSaved(choice);
                    } catch (IOException | JSONException failure) {
                        toast("无法删除主题，请重试");
                    }
                    if (wasSelected && app.themes.selected() == null) {
                        app.selectPhoneLandscape(PhoneLandscape.SKY);
                        refreshThemeControls();
                    }
                    refresh.run();
                }).show();
    }

    private void browseOnlineThemes() {
        loadTheme("正在获取在线主题", () -> app.themes.availableOnline(ThemeLibrary.catalog()), choices -> {
            if (choices == null || choices.isEmpty()) {
                toast(choices == null ? "暂时无法访问在线主题" : "暂无可下载或更新的主题");
                return;
            }
            ArrayAdapter<ThemeLibrary.OnlineTheme> adapter = new ArrayAdapter<>(this, 0, choices) {
                @Override public View getView(int position, View recycled, ViewGroup parent) {
                    ThemeListRow row = recycled instanceof ThemeListRow available ? available : new ThemeListRow();
                    ThemeLibrary.OnlineTheme choice = choices.get(position);
                    row.bind(choice.entry().title(), choice.entry().versionLabel(), choice.update() ? "更新" : "", null);
                    return row;
                }
            };
            new AlertDialog.Builder(this).setTitle("在线主题")
                    .setAdapter(adapter, (dialog, which) -> {
                        ThemeLibrary.OnlineTheme choice = choices.get(which);
                        ThemeLibrary.Entry entry = choice.entry();
                        loadTheme("正在获取" + entry.title(), () -> app.themes.onlineTheme(entry), pack -> {
                            if (pack == null) {
                                toast("主题下载、校验或保存失败，继续使用当前主题");
                            } else if (applyTheme(pack)) {
                                String action = choice.update() ? "已更新" : "已下载";
                                toast(action + " · " + pack.entry.title() + " " + pack.entry.versionLabel());
                            }
                        });
                    }).setNegativeButton("取消", null).show();
        });
    }

    private final class ThemeListRow extends LinearLayout {
        private final TextView title;
        private final TextView status;
        private final TextView version;
        private final Button delete;

        ThemeListRow() {
            super(MainActivity.this);
            setGravity(Gravity.CENTER_VERTICAL);
            setMinimumHeight(dp(56));
            setPaddingRelative(dp(24), dp(8), dp(24), dp(8));
            title = text("", 16, getColor(R.color.ink));
            addView(title, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
            status = addTag(R.color.link, R.color.tile);
            version = addTag(R.color.ink, R.color.control);
            delete = new Button(MainActivity.this, null, android.R.attr.borderlessButtonStyle);
            delete.setText("删除");
            delete.setTextSize(13);
            delete.setTextColor(getColor(R.color.muted));
            delete.setPadding(0, 0, 0, 0);
            delete.setFocusable(false);
            LayoutParams size = new LayoutParams(dp(48), dp(48));
            size.setMarginStart(dp(4));
            addView(delete, size);
        }

        private TextView addTag(int foreground, int fill) {
            TextView tag = text("", 12, getColor(foreground));
            tag.setSingleLine(true);
            tag.setPaddingRelative(dp(8), dp(4), dp(8), dp(4));
            GradientDrawable background = new GradientDrawable();
            background.setColor(getColor(fill));
            background.setCornerRadius(dp(12));
            tag.setBackground(background);
            LayoutParams size = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            size.setMarginStart(dp(6));
            addView(tag, size);
            return tag;
        }

        void bind(String name, String revision, String label, Runnable remove) {
            title.setText(name);
            status.setText(label);
            status.setVisibility(label.isEmpty() ? GONE : VISIBLE);
            version.setText(revision);
            version.setVisibility(revision.isEmpty() ? GONE : VISIBLE);
            delete.setVisibility(remove == null ? GONE : VISIBLE);
            delete.setOnClickListener(remove == null ? null : view -> remove.run());
            delete.setContentDescription(remove == null ? null : "删除" + name);
        }
    }

    private void pickThemeZip() {
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*").putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                        "application/zip", "application/x-zip", "application/x-zip-compressed", "application/octet-stream"
                });
        try {
            startActivityForResult(picker, PICK_THEME_ZIP);
        } catch (ActivityNotFoundException unavailable) {
            toast("系统文件选择器不可用");
        }
    }

    private void importTheme(Uri file) {
        loadTheme("正在校验主题包", () -> {
            try (InputStream input = getContentResolver().openInputStream(file)) {
                if (input == null) throw new IOException("Cannot open theme ZIP");
                return ThemeLibrary.readZip(input);
            }
        }, pack -> {
            if (pack == null) {
                toast("主题包无效，请选择小领通主题 ZIP");
                return;
            }
            try {
                app.themes.saveLocal(pack);
            } catch (IOException | JSONException failure) {
                toast("无法保存本地主题");
                return;
            }
            if (applyTheme(pack)) toast("已导入并应用 · " + pack.entry.title() + " " + pack.entry.versionLabel());
        });
    }

    private interface ThemeTask<T> {
        T run() throws IOException, JSONException;
    }

    private <T> void loadTheme(String message, ThemeTask<T> task, java.util.function.Consumer<T> finish) {
        AlertDialog progress = new AlertDialog.Builder(this).setMessage(message).setNegativeButton("取消", null).show();
        themeProgress = progress;
        java.util.concurrent.Future<?> job = themeWorker.submit(() -> {
            T result = null;
            try { result = task.run(); }
            catch (IOException | JSONException | SecurityException unavailable) { /* Keep the current theme. */ }
            T value = result;
            runOnUiThread(() -> {
                if (themeProgress != progress || !progress.isShowing() || isFinishing() || isDestroyed()) return;
                progress.dismiss();
                themeProgress = null;
                finish.accept(value);
            });
        });
        progress.setOnDismissListener(dialog -> {
            if (themeProgress == progress) {
                job.cancel(true);
                themeProgress = null;
            }
        });
    }

    private boolean applyTheme(ThemeLibrary.Pack pack) {
        try {
            app.themes.select(pack);
            app.selectPhoneLandscape(PhoneLandscape.compatibleWith(pack == null ? null : pack.entry));
            refreshThemeControls();
            return true;
        } catch (IOException | JSONException failure) {
            toast("应用主题失败，继续使用当前主题");
            return false;
        }
    }

    private void chooseTiming() {
        boolean manual = ThemeSync.flag(themePrefs, "manual", false);
        new AlertDialog.Builder(this).setTitle("日夜切换")
                .setSingleChoiceItems(new String[]{"自动日出日落", "手动设置时间"}, manual ? 1 : 0, (dialog, which) -> {
                    dialog.dismiss();
                    themePrefs.edit().putBoolean("manual", which == 1).apply();
                    refreshThemeControls();
                    solar.settingsChanged();
                }).setNegativeButton("取消", null).show();
    }

    private void chooseTime(boolean sunrise) {
        String key = sunrise ? "riseMinute" : "setMinute";
        int minute = ThemeSync.minute(themePrefs, key, sunrise ? 360 : 1080);
        TimePickerDialog picker = new TimePickerDialog(this, (view, hour, min) -> {
            int selected = hour * 60 + min;
            if (selected == ThemeSync.minute(themePrefs, sunrise ? "setMinute" : "riseMinute", sunrise ? 1080 : 360)) {
                toast("日出和日落时间不能相同");
                return;
            }
            themePrefs.edit().putInt(key, selected).apply();
            refreshThemeControls();
            solar.settingsChanged();
        }, minute / 60, minute % 60, true);
        picker.setTitle(sunrise ? "日出时间" : "日落时间");
        picker.show();
    }

    private static String timeLabel(int minute) {
        return String.format(java.util.Locale.ROOT, "%02d:%02d", minute / 60, minute % 60);
    }

    private void binding() {
        if (app.card.view() == null) toast(app.card.bind(this));
        else new AlertDialog.Builder(this).setTitle("解除卡片绑定？").setMessage("手表和手环将暂停接收这张卡片的车况和操作。")
                .setNegativeButton("取消", null).setPositiveButton("解除", (dialog, which) -> {
                    app.disarm();
                    app.card.unbind();
                    render();
                }).show();
    }

    private void clearLegacyLoginCookies() {
        SharedPreferences migrations = getSharedPreferences("migrations", MODE_PRIVATE);
        if (migrations.getBoolean("legacyLoginCookiesCleared", false)) return;

        // The 0.3.61 login screen was the only user of this app's WebView profile.
        if (!new java.io.File(getDataDir(), "app_webview").isDirectory()) {
            migrations.edit().putBoolean("legacyLoginCookiesCleared", true).apply();
            return;
        }
        try {
            android.webkit.CookieManager cookies = android.webkit.CookieManager.getInstance();
            cookies.removeAllCookies(removed -> {
                cookies.flush();
                migrations.edit().putBoolean("legacyLoginCookiesCleared", true).apply();
            });
        } catch (RuntimeException unavailable) {
            // A disabled WebView provider can become available on a later launch.
        }
    }

    @Override protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code == PICK_THEME_ZIP) {
            if (result == RESULT_OK && data != null && data.getData() != null) importTheme(data.getData());
            return;
        }
        if (wearUpdates.activityResult(code, result, data)) return;
        if (code == LynkWidget.REQUEST) { toast(app.card.finish(result == RESULT_OK)); render(); }
    }

    @Override protected void onDestroy() {
        if (themeProgress != null) themeProgress.dismiss();
        themeWorker.shutdownNow();
        wearUpdates.destroy();
        super.onDestroy();
    }

    private void startCommunication() {
        Intent service = new Intent(this, BandService.class);
        List<String> permissions = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!permissions.isEmpty()) requestPermissions(permissions.toArray(new String[0]), REQUEST_COMMUNICATION);
        else startForegroundService(service);
    }

    @SuppressLint("MissingPermission")
    private void selectWatch() {
        if (!PairedDevice.allowed(this) && Build.VERSION.SDK_INT >= 31) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 73);
            return;
        }
        PairedDevice.choose(this, "选择已配对的Watch", device -> {
            String name = device.getName();
            app.selectWatch(device.getAddress(), name == null ? "Watch" : name);
        });
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (wearUpdates.velaPermission(code)) return;
        if (code == 73) {
            if (PairedDevice.allowed(this)) selectWatch();
            else toast("选择手表需要附近设备权限");
            return;
        }
        if (code == SolarController.REQUEST_LOCATION) {
            solar.permissionResult();
            return;
        }
        if (code != REQUEST_COMMUNICATION) return;
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) toast("连接穿戴设备需要附近设备权限");
        else startForegroundService(new Intent(this, BandService.class));
    }

    private void render() {
        if (renderedTheme != app.themes.selected()) refreshThemeControls();
        AppWidgetHostView card = app.card.view();
        if (card != null && card.getParent() != hostFrame) {
            if (card.getParent() instanceof ViewGroup old) old.removeView(card);
            hostFrame.removeAllViews();
            hostFrame.addView(card, new FrameLayout.LayoutParams(-1, -1));
            card.post(this::render);
        }
        if (card == null) hostFrame.removeAllViews();
        hostFrame.setVisibility(card == null ? View.GONE : View.VISIBLE);
        bind.setText(card == null ? "绑定领克中号卡片" : "解除卡片绑定");
        state.setText(app.connectionStatus());
        voiceSwitch.setChecked(app.voiceEnabled());
        voiceConfirm.setChecked(app.voiceConfirmation());
        voiceConfirm.setVisibility(app.voiceEnabled() ? View.VISIBLE : View.GONE);
        voiceAccess.setText(MiHomeScenesListener.access(this) ? "已允许读取米家通知" : "授权读取米家通知");
        boolean apk = app.androidWear();
        styleType(apkType,apk);
        styleType(rpkType,!apk);
        watch.setVisibility(apk ? View.VISIBLE : View.GONE);
        watch.setText(app.watchName().isEmpty() ? "选择 Watch" : "已配对 · " + app.watchName());
        boolean showBandAuth = !apk && app.usesBand() && app.link.canAuthorize();
        authorize.setVisibility(showBandAuth ? View.VISIBLE : View.GONE);
        last.setText(app.last);
        last.setVisibility(app.last.isEmpty() ? View.GONE : View.VISIBLE);
        List<CardContent.Slot> slots = app.visibleSlots();
        if (itemPicker != null && (!pickerVehicle.equals(vehicleKey())
                || slots.stream().noneMatch(slot -> slot.id().equals(pickerSlot)))) itemPicker.dismiss();
        noSlots.setVisibility(slots.isEmpty() ? View.VISIBLE : View.GONE);
        if (slots.isEmpty()) noSlots.setText(card == null ? "绑定卡片后可设置。" : "等候官方卡片显示按钮。");
        while (rows.getChildCount() > slots.size()) rows.removeViewAt(rows.getChildCount() - 1);
        for (int i = 0; i < slots.size(); i++) {
            SlotRow row = (SlotRow) rows.getChildAt(i);
            if (row == null) {
                row = new SlotRow(this);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                lp.topMargin = dp(6);
                rows.addView(row, lp);
            }
            row.bindSlot(slots.get(i));
        }
    }

    private final class SlotRow extends LinearLayout {
        private final ImageView preview;
        private final TextView title;
        private CardContent.Slot slot;
        private Drawable source;

        SlotRow(Context context) {
            super(context);
            setGravity(Gravity.CENTER_VERTICAL);
            setBackground(frostedButton(getColor(R.color.control), false));
            setMinimumHeight(dp(52));
            setPadding(dp(12), dp(6), dp(12), dp(6));
            preview = new ImageView(context);
            preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
            preview.setImageTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.ink)));
            addView(preview, new LayoutParams(dp(32), dp(32)));
            title = text("", 16, getColor(R.color.ink));
            title.setPadding(dp(8), 0, dp(8), 0);
            addView(title, new LayoutParams(0, -2, 1));
            addView(text("编辑", 14, getColor(R.color.link)));
            setOnClickListener(v -> chooseItem(slot));
        }

        void bindSlot(CardContent.Slot next) {
            Drawable drawable = next.icon() == null ? null : next.icon().getDrawable();
            if (slot == null || !slot.signature().equals(next.signature()) || source != drawable) {
                if (drawable == null) preview.setImageDrawable(null);
                else if (drawable.getConstantState() != null) {
                    // Keep preview state and tint separate from the official widget.
                    Drawable copy = drawable.getConstantState().newDrawable().mutate();
                    copy.setState(drawable.getState());
                    preview.setImageDrawable(copy);
                } else {
                    preview.setImageBitmap(com.mario.lynkband.transport.WatchIconCodec.decode(CardContent.watchIcon(next.icon())));
                }
                source = drawable;
            }
            slot = next;
            int index = switch (slot.id()) { case "fr_unlock" -> 1; case "fr_window" -> 2; case "fr_kjck" -> 3; default -> 4; };
            String name = app.displayName(slot);
            String label = index + " · " + (name.isEmpty() ? "未选择控制项目" : name);
            if (!label.contentEquals(title.getText())) title.setText(label);
        }
    }

    private String vehicleKey() {
        return app.card.identity() + "/" + CardContent.text(app.card.view(), "widget_name");
    }

    private void chooseItem(CardContent.Slot slot) {
        ControlCategory[] items = ControlCategory.values();
        String[] names = new String[items.length];
        for (int i = 0; i < items.length; i++) {
            names[i] = items[i] == ControlCategory.OTHER ? "不选择（清除）" : items[i].title;
        }
        String vehicle = vehicleKey();
        pickerVehicle = vehicle;
        pickerSlot = slot.id();
        itemPicker = new AlertDialog.Builder(this).setTitle("选择按钮类型")
                .setSingleChoiceItems(names, app.selectedItem(slot).ordinal(), (dialog, i) -> {
                    dialog.dismiss();
                    boolean saved = vehicle.equals(vehicleKey()) && app.selectItem(slot, items[i]);
                    if (!saved) toast("卡片已变化，请重新选择");
                    render();
                }).setNegativeButton("取消", null)
                .setOnDismissListener(dialog -> { if (itemPicker == dialog) itemPicker = null; })
                .show();
    }
    private void section(String title, boolean inset) {
        group=page;
        addTitle(title,20);
        group=new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        if(inset) {
            group.setBackground(glass(getColor(R.color.surface), night ? 0xaa : 0xb4, night ? 0x7c : 0x8d));
            group.setPadding(dp(13),dp(13),dp(13),dp(13));
        }
        page.addView(group);
    }

    private Button typeButton(LinearLayout host,String title,boolean apk) {
        Button button=new Button(this);
        button.setText(title);
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setStateListAnimator(null);
        host.addView(button,new LinearLayout.LayoutParams(0,dp(48),1));
        button.setOnClickListener(v -> app.selectType(apk));
        return button;
    }

    private void styleType(Button button,boolean active) {
        if(button.getTag() != null && button.isSelected()==active) return;
        button.setTag(Boolean.TRUE);
        button.setSelected(active);
        button.setTextColor(active?getColor(R.color.on_link):getColor(R.color.muted));
        button.setBackground(frostedButton(getColor(active ? R.color.link : R.color.bg), active));
    }

    private void addTitle(String title, int size) {
        TextView t = line(title, size, night ? 0xffffffff : 0xff14232e);
        t.setShadowLayer(dp(3), 0, dp(1), night ? 0xb3000000 : 0xbbffffff);
        t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        t.setPadding(dp(3), dp(16), 0, dp(10));
    }
    private TextView text(String value, int size, int color) {
        TextView t = new TextView(this);
        t.setText(value); t.setTextSize(size); t.setTextColor(color); t.setGravity(Gravity.CENTER_VERTICAL);
        return t;
    }
    private TextView line(String value, int size, int color) {
        TextView t = text(value, size, color); group.addView(t); return t;
    }
    private Button button(String value, View.OnClickListener click) {
        Button b = new Button(this);
        b.setAllCaps(false); b.setText(value); b.setTextColor(getColor(R.color.ink)); b.setTextSize(15); b.setStateListAnimator(null);
        b.setBackground(frostedButton(getColor(R.color.control), false));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(48)); lp.topMargin = dp(7);
        group.addView(b, lp); b.setOnClickListener(click);
        return b;
    }
    private Drawable frostedButton(int color, boolean selected) {
        GradientDrawable surface = glass(color, selected ? 0xe1 : 0x79, selected ? 0xca : 0x4e);
        return new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x407ebeea), surface, null);
    }

    private GradientDrawable glass(int color, int topAlpha, int bottomAlpha) {
        int rgb = color & 0x00ffffff;
        GradientDrawable surface = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{(topAlpha << 24) | rgb, (bottomAlpha << 24) | rgb});
        surface.setCornerRadius(dp(14));
        surface.setStroke(dp(1), night ? 0x55d8eafb : 0x99ffffff);
        return surface;
    }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
}
