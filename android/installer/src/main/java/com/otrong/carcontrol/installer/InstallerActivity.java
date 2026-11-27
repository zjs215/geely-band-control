package com.otrong.carcontrol.installer;

import android.Manifest;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class InstallerActivity extends Activity {
    private static final int REQUEST_BLUETOOTH = 1;
    private static final int REQUEST_LOG_ZIP = 2;
    private static final int REQUEST_LOG_FOLDER = 3;
    private static final int REQUEST_INSTALL_PERMISSION = 4;
    private static final int REQUEST_EXPORT_RPK = 5;
    private static final int REQUEST_EXPORT_DIAGNOSTICS = 6;
    private static final String PHONE_STATUS_PERMISSION = "com.garage.bandcontrol.installer.PHONE_INSTALL_STATUS";
    private static final String LOG_SETTINGS = "mi-fitness-log";
    private static final String LOG_FOLDER_URI = "folder";
    private static final String LOG_BAND_ADDRESS = "lastBandAddress";
    private static final String DEVICE_SETTINGS = "installer-devices";
    private static final long BAND_IDLE_MS = 90_000;
    private static final long BAND_KEEP_ALIVE_MS = 800;
    private static final long UPDATE_INTERVAL_MS = 300_000;
    private static final int COLOR_BACKGROUND = 0xff111817;
    private static final int COLOR_SURFACE = 0xff202b29;
    private static final int COLOR_INSET = 0xff172321;
    private static final int COLOR_BORDER = 0xff3a514b;
    private static final int COLOR_TEXT = 0xfff1faf7;
    private static final int COLOR_SECONDARY = 0xffbcceca;
    private static final int COLOR_ACCENT = 0xff75dcc2;
    private static final String[] HEALTH_PACKAGES = {"com.mi.health", "com.xiaomi.wearable"};

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService versionWorker = Executors.newFixedThreadPool(2);
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable checkBandConnection = this::checkBandConnection;
    private BundledPackages packages;
    private InstallPackages installPackages;
    private BandDiagnostics bandDiagnostics;
    private ScrollView scroll;
    private LinearLayout content;
    private RadioGroup targets;
    private RadioButton phoneTarget;
    private RadioButton wearTarget;
    private WearableProfile selectedWearable;
    private InstallTarget pendingExport;
    private boolean bandHasUpdate;
    private boolean watchHasUpdate;
    private LinearLayout phonePanel;
    private LinearLayout wearPanel;
    private LinearLayout wearEmpty;
    private LinearLayout bandPanel;
    private LinearLayout watchPanel;
    private LinearLayout phoneFeedback;
    private LinearLayout wearFeedback;
    private LinearLayout bandFeedback;
    private LinearLayout watchFeedback;
    private LinearLayout feedback;
    private LinearLayout bandRecovery;
    private LinearLayout bandActions;
    private Button retryBand;
    private Button rescanBand;
    private Button repairBand;
    private LinearLayout bandSettings;
    private LinearLayout watchSettings;
    private EditText address;
    private EditText authKey;
    private TextView logStatus;
    private Button folderButton;
    private Uri logFolder;
    private WearableLog.Keys importedKeys;
    private boolean usingPickedZip;
    private EditText watchIp;
    private EditText watchPairPort;
    private EditText watchPairCode;
    private EditText watchConnectPort;
    private TextView status;
    private TextView phoneVersion;
    private TextView updateSource;
    private TextView phoneUpdate;
    private TextView bandUpdate;
    private TextView watchUpdate;
    private LinearLayout releaseNotesPanel;
    private TextView releaseNotesTitle;
    private TextView releaseNotesBody;
    private GitHubRelease.Release latestRelease;
    private boolean checkingRelease;
    private long lastReleaseCheck = -UPDATE_INTERVAL_MS;
    private String watchProbeEndpoint = "";
    private boolean watchReading;
    private boolean watchRead;
    private int watchProbeSequence;
    private long lastWatchProbe;
    private AppUpdate.Installed watchedVersion;
    private TextView bandDevice;
    private TextView bandTitle;
    private TextView bandProduct;
    private TextView bandOnboarding;
    private ProgressBar progress;
    private Button cancel;
    private Button shareDiagnosticsButton;
    private Runnable afterPermission;
    private NearbyBandPicker nearbyBandPicker;
    private BandSession bandSession;
    private Button connectBandButton;
    private Button installBandButton;
    private Button installPhoneButton;
    private Button openPhoneButton;
    private boolean resumeBandInstallAfterLog;
    private int phoneSession = -1;
    private String phoneSource = "";
    private long bandIdleDeadline;
    private boolean visible;
    private Operation current;
    private BluetoothDevice observedBand;
    private BandDiagnostics.Trace observedBandTrace = BandDiagnostics.Trace.OFF;
    private long observeBandUntil;
    private final BroadcastReceiver bandLinkEvents = new BroadcastReceiver() {
        @Override
        @SuppressWarnings("deprecation")
        public void onReceive(Context context, Intent intent) {
            if (observedBand == null || SystemClock.elapsedRealtime() > observeBandUntil) return;
            BluetoothDevice device = Build.VERSION.SDK_INT >= 33
                    ? intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class)
                    : intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (!observedBand.equals(device)) return;
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)) {
                observedBandTrace.event("system.bond_changed",
                        "old", intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1),
                        "new", intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1));
            } else {
                int transport = Build.VERSION.SDK_INT >= 33
                        ? intent.getIntExtra(BluetoothDevice.EXTRA_TRANSPORT, -1) : -1;
                observedBandTrace.event(BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)
                        ? "system.acl_connected" : "system.acl_disconnected", "transport", transport);
            }
        }
    };
    private final BroadcastReceiver phoneInstallResults = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (PhoneInstall.ACTION.equals(intent.getAction())
                    && phoneSession >= 0 && intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1) == phoneSession) {
                onPhoneInstallResult(intent);
            }
        }
    };

    @Override
    public void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        String appVersion = "";
        try {
            appVersion = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException ignored) {
            // The protocol trace is still available without version metadata.
        }
        bandDiagnostics = new BandDiagnostics(getNoBackupFilesDir(), appVersion, Build.VERSION.SDK_INT,
                Build.MANUFACTURER, Build.MODEL);
        if (savedState != null) {
            String export = savedState.getString("rpkExport");
            if (InstallTarget.BAND.name().equals(export)) pendingExport = InstallTarget.BAND;
            else if (InstallTarget.BAND_LITE.name().equals(export)) pendingExport = InstallTarget.BAND_LITE;
        }
        try {
            packages = new BundledPackages(getAssets());
            installPackages = new InstallPackages(getApplicationContext(), packages);
        } catch (IOException ignored) {
            // Keep the screen available to show the package error.
        }
        String savedFolder = getSharedPreferences(LOG_SETTINGS, MODE_PRIVATE).getString(LOG_FOLDER_URI, "");
        if (!savedFolder.isEmpty()) logFolder = Uri.parse(savedFolder);
        createScreen();
        registerPhoneResults();
        registerBandLinkEvents();
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerBandLinkEvents() {
        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_ACL_CONNECTED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(bandLinkEvents, filter, Context.RECEIVER_EXPORTED);
        else registerReceiver(bandLinkEvents, filter);
    }

    private void createScreen() {
        scroll = new ScrollView(this);
        scroll.setBackgroundColor(COLOR_BACKGROUND);
        scroll.setFillViewport(true);
        content = column();
        content.setPadding(dp(20), dp(22), dp(20), dp(24));
        content.setFocusableInTouchMode(true);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        applyInsets();
        setContentView(scroll);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_installer);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        header.addView(icon, new LinearLayout.LayoutParams(dp(42), dp(42)));
        LinearLayout heading = column();
        LinearLayout.LayoutParams headingParams = new LinearLayout.LayoutParams(0, -2, 1);
        headingParams.setMarginStart(dp(10));
        header.addView(heading, headingParams);
        TextView title = text(getString(R.string.app_name), 25, COLOR_TEXT);
        title.setTypeface(null, Typeface.BOLD);
        add(heading, title, 0);
        updateSource = text(getString(R.string.source_summary), 12, COLOR_SECONDARY);
        updateSource.setOnClickListener(view -> showReleaseNotesDialog());
        add(heading, updateSource, 5);
        add(content, header, 0);

        targets = new RadioGroup(this);
        targets.setOrientation(RadioGroup.HORIZONTAL);
        targets.setBackground(rounded(COLOR_SURFACE, 15));
        targets.setPadding(dp(4), dp(4), dp(4), dp(4));
        phoneTarget = radio(R.string.target_phone);
        wearTarget = radio(R.string.target_wearable);
        for (RadioButton target : new RadioButton[]{phoneTarget, wearTarget}) {
            targets.addView(target, new RadioGroup.LayoutParams(0, dp(44), 1));
        }
        add(content, targets, 20);

        releaseNotesPanel = inset();
        GradientDrawable notesBackground = rounded(COLOR_INSET, 14);
        notesBackground.setStroke(dp(1), COLOR_BORDER);
        releaseNotesPanel.setBackground(new RippleDrawable(ColorStateList.valueOf(0x2275dcc2), notesBackground, null));
        releaseNotesTitle = text("", 14, COLOR_ACCENT);
        releaseNotesTitle.setTypeface(null, Typeface.BOLD);
        add(releaseNotesPanel, releaseNotesTitle, 0);
        releaseNotesBody = text("", 13, COLOR_SECONDARY);
        releaseNotesBody.setMaxLines(6);
        releaseNotesBody.setEllipsize(TextUtils.TruncateAt.END);
        add(releaseNotesPanel, releaseNotesBody, 4);
        TextView releaseNotesMore = text(getString(R.string.release_notes_more), 12, COLOR_ACCENT);
        add(releaseNotesPanel, releaseNotesMore, 6);
        releaseNotesPanel.setOnClickListener(view -> showReleaseNotesDialog());
        releaseNotesPanel.setVisibility(View.GONE);
        add(content, releaseNotesPanel, 12);

        phonePanel = card();
        wearPanel = column();
        wearEmpty = card();
        bandPanel = card();
        watchPanel = card();
        add(content, phonePanel, 12);
        add(content, wearPanel, 12);
        add(wearPanel, wearEmpty, 0);
        add(wearPanel, bandPanel, 0);
        add(wearPanel, watchPanel, 0);
        createPhoneControls();
        createWearPicker();
        createBandControls();
        createWatchControls();

        feedback = inset();
        feedback.setVisibility(View.GONE);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setVisibility(View.GONE);
        add(feedback, progress, 0);
        status = text("", 15, COLOR_SECONDARY);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        status.setVisibility(View.GONE);
        add(feedback, status, 0);
        cancel = button(R.string.stop, false, () -> {
            if (current != null) current.stop(false);
        });
        cancel.setVisibility(View.GONE);
        add(feedback, cancel, 4);
        shareDiagnosticsButton = button(R.string.band_share_diagnostics, true, () -> {});
        shareDiagnosticsButton.setVisibility(View.GONE);
        add(feedback, shareDiagnosticsButton, 8);
        bandRecovery = column();
        LinearLayout recoveryActions = new LinearLayout(this);
        retryBand = button(R.string.band_retry, false, () -> {});
        rescanBand = button(R.string.band_nearby, false, () -> {});
        addAction(recoveryActions, retryBand);
        addAction(recoveryActions, rescanBand);
        add(bandRecovery, recoveryActions, 0);
        repairBand = button(R.string.band_repair, true, () -> {});
        repairBand.setVisibility(View.GONE);
        add(bandRecovery, repairBand, 6);
        add(bandRecovery, button(R.string.band_export_diagnostics, false, this::chooseDiagnosticsDestination), 4);
        bandRecovery.setVisibility(View.GONE);
        add(feedback, bandRecovery, 6);

        String previous = deviceSettings().getString("target", InstallTarget.PHONE.name());
        String wearChoice = deviceSettings().getString("wearTarget", previous);
        selectedWearable = WearableProfile.known(wearChoice);
        if (selectedWearable == null) {
            if (wearChoice.equals(InstallTarget.WATCH.name())) selectedWearable = WearableProfile.WATCH_5;
            else if (wearChoice.equals(InstallTarget.BAND.name()) || !value(address).isEmpty()) {
                selectedWearable = WearableProfile.BAND_10_PRO;
            } else if (!value(watchConnectPort).isEmpty()) selectedWearable = WearableProfile.WATCH_5;
        }
        if (selectedWearable != null && selectedWearable.target.isRpk()) address.setText(profileAddress(selectedWearable));
        targets.setOnCheckedChangeListener((group, id) -> showTarget());
        targets.check(previous.equals(InstallTarget.PHONE.name()) ? phoneTarget.getId() : wearTarget.getId());
        if (packages == null) setStatus(getString(R.string.asset_error));
        refreshUpdates();
        content.requestFocus();
    }

    private void showTarget() {
        boolean wearable = targets.getCheckedRadioButtonId() == wearTarget.getId();
        boolean band = wearable && selectedWearable != null && selectedWearable.target.isRpk();
        boolean watch = wearable && selectedWearable == WearableProfile.WATCH_5;
        if (!band) closeBandSession();
        phonePanel.setVisibility(wearable ? View.GONE : View.VISIBLE);
        wearPanel.setVisibility(wearable ? View.VISIBLE : View.GONE);
        wearEmpty.setVisibility(band || watch ? View.GONE : View.VISIBLE);
        bandPanel.setVisibility(band ? View.VISIBLE : View.GONE);
        watchPanel.setVisibility(watch ? View.VISIBLE : View.GONE);
        if (feedback.getParent() instanceof LinearLayout previous) previous.removeView(feedback);
        LinearLayout destination = band ? bandFeedback : watch ? watchFeedback : wearable ? wearFeedback : phoneFeedback;
        add(destination, feedback, 10);
        if (packages != null) setStatus("");
        refreshBandButtons();
        refreshUpdates();
        if (watch) probeWatch(false);
    }

    private void createWearPicker() {
        add(wearEmpty, heading(R.string.wearable_product), 0);
        add(wearEmpty, text(getString(R.string.wearable_guide), 14, COLOR_SECONDARY), 6);
        add(wearEmpty, button(R.string.band_nearby, true,
                () -> withBluetooth(true, () -> searchBand(false))), 14);
        wearFeedback = column();
        add(wearEmpty, wearFeedback, 0);
        add(wearEmpty, button(R.string.wearable_manual, false, this::chooseWearableType), 4);
    }

    private void chooseWearableType() {
        showWearableChoices(null, false);
    }

    private void showWearableChoices(String mac, boolean installAfter) {
        WearableProfile[] profiles = WearableProfile.values();
        List<String> entries = new ArrayList<>();
        if (mac == null) entries.add(getString(R.string.band_nearby));
        for (WearableProfile profile : profiles) {
            String pack = getString(packageLabel(profile.target));
            entries.add(getString(R.string.wearable_model_package, getString(profile.label), pack));
        }
        new AlertDialog.Builder(this).setTitle(mac == null ? R.string.wearable_choose : R.string.wearable_unknown)
                .setItems(entries.toArray(new String[0]), (dialog, index) -> {
            if (mac == null && index == 0) {
                withBluetooth(true, () -> searchBand(false));
            } else {
                WearableProfile profile = profiles[mac == null ? index - 1 : index];
                if (mac != null && profile.target.isRpk()) selectBand(profile, mac, installAfter);
                else changeWearable(profile, null);
                if (profile == WearableProfile.WATCH_5) watchSettings.setVisibility(View.VISIBLE);
            }
        }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private String wearableChoice(int title, boolean available) {
        return available ? getString(R.string.update_tab, getString(title)) : getString(title);
    }

    private void changeWearable(WearableProfile profile, String mac) {
        boolean changed = selectedWearable != profile;
        if (changed) closeBandSession();
        selectedWearable = profile;
        deviceSettings().edit().putString("wearTarget", profile.name()).apply();
        if (profile.target.isRpk()) {
            if (mac != null) address.setText(mac);
            else if (changed) address.setText(profileAddress(profile));
        }
        if (targets.getCheckedRadioButtonId() == wearTarget.getId()) showTarget();
        else targets.check(wearTarget.getId());
    }

    private String profileAddress(WearableProfile profile) {
        return deviceSettings().getString("bandAddress:" + profile.name(), profile == WearableProfile.BAND_10_PRO
                ? getSharedPreferences(LOG_SETTINGS, MODE_PRIVATE).getString(LOG_BAND_ADDRESS, "") : "");
    }

    private static int packageLabel(InstallTarget target) {
        return switch (target) {
            case BAND_LITE -> R.string.lite_rpk;
            case BAND -> R.string.full_rpk;
            default -> R.string.watch_apk;
        };
    }

    private void createPhoneControls() {
        add(phonePanel, heading(R.string.phone_product), 0);
        phoneVersion = text("", 14, COLOR_ACCENT);
        add(phonePanel, phoneVersion, 6);
        phoneUpdate = text("", 14, COLOR_SECONDARY);
        add(phonePanel, phoneUpdate, 4);
        installPhoneButton = button(R.string.install_or_update, true, this::installPhone);
        installPhoneButton.setEnabled(packages != null);
        add(phonePanel, installPhoneButton, 12);
        phoneFeedback = column();
        add(phonePanel, phoneFeedback, 0);
        add(phonePanel, text(getString(R.string.phone_guide), 14, COLOR_SECONDARY), 10);
        openPhoneButton = button(R.string.open_companion, false,
                () -> launch(new String[]{BundledPackages.APP_PACKAGE}, "请先安装口袋车控。"));
        add(phonePanel, openPhoneButton, 4);
    }

    private void createBandControls() {
        bandTitle = heading(R.string.band_10_pro);
        add(bandPanel, wearableHeading(bandTitle), 0);
        bandProduct = text("", 14, COLOR_SECONDARY);
        add(bandPanel, bandProduct, 5);
        bandDevice = text("", 14, COLOR_SECONDARY);
        add(bandPanel, bandDevice, 6);
        bandUpdate = text("", 14, COLOR_SECONDARY);
        add(bandPanel, bandUpdate, 4);
        installBandButton = button(R.string.install_or_update, true, this::startBandInstall);
        add(bandPanel, installBandButton, 14);
        bandFeedback = column();
        add(bandPanel, bandFeedback, 0);
        bandActions = new LinearLayout(this);
        addAction(bandActions, button(R.string.band_nearby, false,
                () -> withBluetooth(true, () -> searchBand(false))));
        connectBandButton = button(R.string.band_connect, false, () -> {
            if (bandSession != null) {
                closeBandSession();
                setStatus("已断开手环。");
            } else {
                prepareBand(false);
            }
        });
        addAction(bandActions, connectBandButton);
        add(bandPanel, bandActions, 5);
        bandOnboarding = text(getString(R.string.band_first_pair_guide), 14, COLOR_SECONDARY);
        bandOnboarding.setBackground(rounded(COLOR_INSET, 12));
        bandOnboarding.setPadding(dp(12), dp(10), dp(12), dp(10));
        add(bandPanel, bandOnboarding, 4);
        bandSettings = column();
        add(bandPanel, disclosure(R.string.band_setup, bandSettings), 4);
        add(bandPanel, bandSettings, 8);
        add(bandSettings, text(getString(R.string.band_guide), 15, COLOR_SECONDARY), 0);
        add(bandSettings, button(R.string.band_health_help, false, this::showMiFitnessGuide), 6);
        folderButton = button(logFolder == null ? R.string.band_select_log_folder : R.string.band_change_log_folder,
                false, () -> chooseLog(true));
        add(bandSettings, folderButton, 8);
        add(bandSettings, button(R.string.band_import_log, false, () -> chooseLog(false)), 2);
        add(bandSettings, button(R.string.band_export, false, this::chooseRpkDestination), 2);
        add(bandSettings, button(R.string.band_export_diagnostics, false, this::chooseDiagnosticsDestination), 2);
        add(bandSettings, button(R.string.band_device, false, () -> withBluetooth(this::pickBand)), 2);
        add(bandSettings, button(R.string.band_repair, false, () -> rePairBand(false)), 2);
        add(bandSettings, button(R.string.band_pair_settings, false, this::showBandPairSettings), 2);
        address = input(R.string.band_address, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        address.setText(getSharedPreferences(LOG_SETTINGS, MODE_PRIVATE).getString(LOG_BAND_ADDRESS, ""));
        add(bandSettings, address, 4);
        logStatus = text("", 14, COLOR_ACCENT);
        logStatus.setVisibility(View.GONE);
        logStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        add(bandSettings, logStatus, 4);
        address.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                if (bandSession != null && !bandSession.address.equals(WearableLog.address(text.toString()))) {
                    closeBandSession();
                    setStatus("手环已切换，请连接当前选定的设备。");
                }
                refreshLogStatus();
                refreshBandButtons();
                refreshUpdates();
            }
            @Override public void afterTextChanged(Editable text) {}
        });
        authKey = input(R.string.band_auth_key, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        authKey.setSaveEnabled(false);
        authKey.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        add(bandSettings, authKey, 4);
        bandSettings.setVisibility(View.GONE);
        refreshBandButtons();
    }

    private void createWatchControls() {
        add(watchPanel, wearableHeading(R.string.watch_5), 0);
        add(watchPanel, text(getString(R.string.watch_product), 14, COLOR_SECONDARY), 5);
        watchUpdate = text("", 14, COLOR_SECONDARY);
        add(watchPanel, watchUpdate, 4);
        Button install = button(R.string.install_or_update, true, this::installWatch);
        install.setEnabled(packages != null);
        add(watchPanel, install, 14);
        watchFeedback = column();
        add(watchPanel, watchFeedback, 0);
        watchSettings = column();
        add(watchPanel, disclosure(R.string.watch_setup, watchSettings), 4);
        add(watchPanel, watchSettings, 8);
        add(watchSettings, text(getString(R.string.watch_guide), 15, COLOR_SECONDARY), 0);
        int singleLine = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        watchIp = input(R.string.watch_host, singleLine);
        watchIp.setText(deviceSettings().getString("watchIp", ""));
        add(watchSettings, watchIp, 10);
        watchPairPort = input(R.string.watch_pair_port, InputType.TYPE_CLASS_NUMBER);
        watchPairCode = input(R.string.watch_pair_code, InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        watchPairCode.setSaveEnabled(false);
        watchPairCode.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        LinearLayout pairFields = new LinearLayout(this);
        pairFields.addView(watchPairPort, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams second = new LinearLayout.LayoutParams(0, -2, 1);
        second.setMarginStart(dp(10));
        pairFields.addView(watchPairCode, second);
        add(watchSettings, pairFields, 2);
        add(watchSettings, button(R.string.watch_pair, false, this::pairWatch), 4);
        watchConnectPort = input(R.string.watch_port, InputType.TYPE_CLASS_NUMBER);
        watchConnectPort.setText(deviceSettings().getString("watchPort", ""));
        add(watchSettings, watchConnectPort, 4);
        add(watchSettings, button(R.string.watch_read_version, false, () -> probeWatch(true)), 4);
        TextWatcher endpointListener = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                refreshUpdates();
            }
            @Override public void afterTextChanged(Editable text) {}
        };
        watchIp.addTextChangedListener(endpointListener);
        watchConnectPort.addTextChangedListener(endpointListener);
        watchSettings.setVisibility(value(watchConnectPort).isEmpty() ? View.VISIBLE : View.GONE);
    }

    private SharedPreferences deviceSettings() {
        return getSharedPreferences(DEVICE_SETTINGS, MODE_PRIVATE);
    }

    @SuppressWarnings("deprecation")
    private void chooseLog(boolean chooseFolder) {
        Intent picker = new Intent(chooseFolder ? Intent.ACTION_OPEN_DOCUMENT_TREE : Intent.ACTION_OPEN_DOCUMENT);
        picker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        picker.putExtra(DocumentsContract.EXTRA_INITIAL_URI, LogDocuments.INITIAL_FOLDER);
        if (chooseFolder) {
            picker.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        } else {
            picker.addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
            picker.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                    "application/zip", "application/x-zip-compressed", "application/octet-stream"});
        }
        try {
            startActivityForResult(picker, chooseFolder ? REQUEST_LOG_FOLDER : REQUEST_LOG_ZIP);
        } catch (ActivityNotFoundException unavailable) {
            setStatus("手机上没有可用的系统文件选择器。");
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == REQUEST_EXPORT_DIAGNOSTICS) {
            if (result == RESULT_OK && data != null && data.getData() != null) saveBandDiagnostics(data.getData());
            return;
        }
        if (request == REQUEST_EXPORT_RPK) {
            InstallTarget target = pendingExport;
            pendingExport = null;
            if (target != null && result == RESULT_OK && data != null && data.getData() != null) {
                saveRpk(target, data.getData());
            }
            return;
        }
        if (request == REQUEST_INSTALL_PERMISSION) {
            if (getPackageManager().canRequestPackageInstalls()) installPhone();
            else setStatus("允许助手安装应用后，再点「安装或更新」。");
            return;
        }
        if (request != REQUEST_LOG_ZIP && request != REQUEST_LOG_FOLDER) return;
        boolean continueInstall = resumeBandInstallAfterLog;
        resumeBandInstallAfterLog = false;
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        boolean folder = request == REQUEST_LOG_FOLDER;
        if (folder) {
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException | IllegalArgumentException denied) {
                setStatus("系统未授予持续访问权限。可以直接选择日志 ZIP 导入。");
                return;
            }
            Uri previous = logFolder;
            logFolder = uri;
            getSharedPreferences(LOG_SETTINGS, MODE_PRIVATE).edit().putString(LOG_FOLDER_URI, uri.toString()).apply();
            folderButton.setText(R.string.band_change_log_folder);
            if (previous != null && !previous.equals(uri)) {
                try {
                    getContentResolver().releasePersistableUriPermission(previous, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (SecurityException alreadyRevoked) {
                    // The previous grant can also be removed in system settings.
                }
            }
            usingPickedZip = false;
        }
        readLog(uri, folder, continueInstall ? () -> prepareBand(false, true) : null);
    }

    @SuppressWarnings("deprecation")
    private void chooseRpkDestination() {
        if (packages == null || current != null || selectedWearable == null || !selectedWearable.target.isRpk()) return;
        pendingExport = selectedWearable.target;
        Intent create = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/octet-stream").putExtra(Intent.EXTRA_TITLE, pendingExport.asset);
        try {
            startActivityForResult(create, REQUEST_EXPORT_RPK);
        } catch (ActivityNotFoundException unavailable) {
            pendingExport = null;
            setStatus("手机上没有可用的系统文件选择器。");
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        if (pendingExport != null) state.putString("rpkExport", pendingExport.name());
        super.onSaveInstanceState(state);
    }

    private void saveRpk(InstallTarget target, Uri destination) {
        if (packages == null) return;
        start("正在准备 RPK", "保存失败，请检查所选目录后重试。", 75, operation -> {
            InstallPackages.Selected selected = installPackages.resolve(target, operation::report);
            try (OutputStream output = getContentResolver().openOutputStream(destination, "wt")) {
                if (output == null) throw new InstallFailure("无法写入所选文件。");
                operation.attach(output);
                output.write(selected.bytes());
            }
            return "已保存 " + getString(packageLabel(target)) + " · " + selected.label()
                    + "\n可在 AstroBox 中选择这个文件安装。";
        });
    }

    @SuppressWarnings("deprecation")
    private void chooseDiagnosticsDestination() {
        if (current != null) return;
        if (!bandDiagnostics.hasEvents()) {
            setStatus(getString(R.string.band_diagnostics_empty));
            return;
        }
        if (Build.VERSION.SDK_INT >= 29) {
            saveBandDiagnostics(null);
            return;
        }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("text/plain").putExtra(Intent.EXTRA_TITLE, diagnosticsFilename());
        try {
            startActivityForResult(intent, REQUEST_EXPORT_DIAGNOSTICS);
        } catch (ActivityNotFoundException unavailable) {
            setStatus("手机上没有可用的系统文件选择器。");
        }
    }

    private static String diagnosticsFilename() {
        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)
                .withZone(ZoneId.systemDefault()).format(Instant.now());
        return "vela-diagnostics-" + stamp + ".txt";
    }

    private void saveBandDiagnostics(Uri destination) {
        runOperation("正在保存诊断日志", "诊断日志保存失败，请检查手机存储空间后重试。", 30, false, operation -> {
            byte[] log = bandDiagnostics.snapshot();
            if (destination == null && Build.VERSION.SDK_INT >= 29) {
                return new SavedDiagnostics(saveDiagnosticsToDownloads(log, operation), R.string.band_diagnostics_downloaded);
            }
            writeDiagnostics(destination, log, operation);
            return new SavedDiagnostics(destination, R.string.band_diagnostics_saved);
        }, this::showSavedDiagnostics, ignored -> {});
    }

    private record SavedDiagnostics(Uri file, int status) {}

    private void showSavedDiagnostics(SavedDiagnostics result) {
        setStatus(getString(result.status()));
        shareDiagnosticsButton.setOnClickListener(view -> shareDiagnostics(result.file()));
        shareDiagnosticsButton.setVisibility(View.VISIBLE);
        shareDiagnosticsButton.post(() -> {
            if (shareDiagnosticsButton.isShown()) {
                shareDiagnosticsButton.requestRectangleOnScreen(new Rect(0, 0,
                        shareDiagnosticsButton.getWidth(), shareDiagnosticsButton.getHeight()), false);
            }
        });
    }

    private void shareDiagnostics(Uri file) {
        Intent share = new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_STREAM, file)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        share.setClipData(ClipData.newRawUri("Vela diagnostics", file));
        try {
            startActivity(Intent.createChooser(share, getString(R.string.band_share_diagnostics)));
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            setStatus(getString(R.string.band_share_unavailable));
            shareDiagnosticsButton.setVisibility(View.VISIBLE);
        }
    }

    @TargetApi(29)
    private Uri saveDiagnosticsToDownloads(byte[] log, Operation operation) throws Exception {
        ContentValues metadata = new ContentValues();
        metadata.put(MediaStore.MediaColumns.DISPLAY_NAME, diagnosticsFilename());
        metadata.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
        metadata.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
        metadata.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri downloads = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        Uri file = getContentResolver().insert(downloads, metadata);
        if (file == null) throw new InstallFailure("无法在下载目录创建诊断文件。");
        boolean saved = false;
        try {
            writeDiagnostics(file, log, operation);
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            ContentValues finished = new ContentValues();
            finished.put(MediaStore.MediaColumns.IS_PENDING, 0);
            saved = getContentResolver().update(file, finished, null, null) == 1;
            if (!saved) throw new InstallFailure("诊断文件写入后无法发布到下载目录。");
            return file;
        } finally {
            if (!saved) {
                try {
                    getContentResolver().delete(file, null, null);
                } catch (RuntimeException ignored) {
                    // Leave the incomplete file hidden if the provider is unavailable.
                }
            }
        }
    }

    private void writeDiagnostics(Uri destination, byte[] log, Operation operation) throws Exception {
        try (OutputStream output = getContentResolver().openOutputStream(destination, "wt")) {
            if (output == null) throw new InstallFailure("无法写入诊断文件。");
            operation.attach(output);
            output.write(log);
        }
    }

    private void readLog(Uri source, boolean folder, Runnable next) {
        runOperation("正在读取小米运动健康日志", "无法读取日志，请重新选择运动健康导出的 ZIP。", 60,
                true, operation -> {
                    Uri file = folder ? LogDocuments.latestZip(getContentResolver(), source) : source;
                    try (InputStream stream = getContentResolver().openInputStream(file)) {
                        if (stream == null) throw new InstallFailure("无法打开日志 ZIP，请重新选择文件。");
                        operation.attach(stream);
                        return WearableLog.read(stream);
                    }
                },
                keys -> {
                    if (importedKeys != null) importedKeys.close();
                    importedKeys = keys;
                    usingPickedZip = !folder;
                    authKey.setText("");
                    if (value(address).trim().isEmpty()) {
                        String unique = keys.uniqueAddress();
                        if (!unique.isEmpty()) address.setText(unique);
                    }
                    refreshLogStatus();
                    if (next != null) next.run();
                    else setStatus(logStatus.getText().toString());
                }, WearableLog.Keys::close);
    }

    private void refreshLogStatus() {
        if (importedKeys == null) {
            logStatus.setVisibility(View.GONE);
            return;
        }
        String selected = WearableLog.address(value(address));
        logStatus.setText(selected.isEmpty() ? R.string.band_log_select_device
                : importedKeys.matches(selected) ? R.string.band_match_log : R.string.band_log_no_match);
        logStatus.setVisibility(View.VISIBLE);
    }

    private void withBluetooth(Runnable action) {
        withBluetooth(false, action);
    }

    private void withBluetooth(boolean scan, Runnable action) {
        List<String> missing = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
            if (scan && checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.BLUETOOTH_SCAN);
            }
        } else if (scan && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (!missing.isEmpty()) {
            afterPermission = action;
            requestPermissions(missing.toArray(new String[0]), REQUEST_BLUETOOTH);
            return;
        }
        action.run();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQUEST_BLUETOOTH) return;
        Runnable action = afterPermission;
        afterPermission = null;
        if (results.length > 0 && Arrays.stream(results).allMatch(grant -> grant == PackageManager.PERMISSION_GRANTED)
                && action != null) {
            action.run();
        } else {
            setStatus(Build.VERSION.SDK_INT >= 31 ? "请在系统设置中允许「附近设备」权限后重试。"
                    : "此安卓版本需要位置权限才能搜索蓝牙设备。");
        }
    }

    private void searchBand(boolean installAfter) {
        BluetoothAdapter adapter = bluetooth();
        if (adapter == null) return;
        if (Build.VERSION.SDK_INT <= 30) {
            LocationManager location = getSystemService(LocationManager.class);
            if (location != null && !location.isLocationEnabled()) {
                setStatus("请打开手机的位置开关，再搜索附近的手环。");
                return;
            }
        }
        closeBandSession();
        if (nearbyBandPicker != null) nearbyBandPicker.close();
        String selected = WearableLog.address(value(address));
        nearbyBandPicker = new NearbyBandPicker(this, adapter, selected, !selected.isEmpty(),
                entry -> selectWearable(entry.name(), entry.address(), installAfter));
        nearbyBandPicker.show();
    }

    private void selectWearable(String name, String mac, boolean installAfter) {
        WearableProfile model = WearableProfile.fromBluetoothName(name);
        if (model == WearableProfile.WATCH_5) {
            changeWearable(model, null);
            // Bluetooth names identify a model, while the user selects its wireless debugging endpoint.
            watchSettings.setVisibility(View.VISIBLE);
            setStatus("识别到小米 Watch 5。确认手表的无线调试地址后，点「安装或更新」。");
            return;
        }
        if (model == null) model = WearableProfile.known(deviceSettings().getString("bandProfile:" + mac, ""));
        if (model == null && mac.equalsIgnoreCase(value(address)) && selectedWearable != null
                && selectedWearable.target.isRpk() && mac.equalsIgnoreCase(profileAddress(selectedWearable))) {
            model = selectedWearable;
        }
        if (model != null && model.target.isRpk()) {
            selectBand(model, mac, installAfter);
            return;
        }
        showWearableChoices(mac, installAfter);
    }

    private void selectBand(WearableProfile profile, String mac, boolean installAfter) {
        deviceSettings().edit().putString("bandProfile:" + mac, profile.name()).apply();
        changeWearable(profile, mac);
        if (installAfter) prepareBand(true);
        else setStatus("已选定手环。点「安装或更新」开始。");
    }

    @SuppressLint("MissingPermission")
    private BluetoothAdapter bluetooth() {
        BluetoothManager manager = getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null) {
            setStatus("手机没有可用的蓝牙适配器。");
            return null;
        }
        if (!adapter.isEnabled()) {
            setStatus("请先在手机系统设置里开启蓝牙。");
            return null;
        }
        return adapter;
    }

    @SuppressLint("MissingPermission")
    private void pickBand() {
        BluetoothAdapter adapter = bluetooth();
        if (adapter == null) return;
        List<BluetoothDevice> paired = new ArrayList<>(adapter.getBondedDevices());
        if (paired.isEmpty()) {
            setStatus("已配对列表中没有手环。请在手环上打开「连接新手机」，再搜索附近设备。");
            return;
        }
        paired.sort(Comparator.comparing(InstallerActivity::deviceLabel));
        String[] names = paired.stream().map(InstallerActivity::deviceLabel).toArray(String[]::new);
        new AlertDialog.Builder(this).setTitle(R.string.band_device)
                .setItems(names, (dialog, index) -> {
                    BluetoothDevice device = paired.get(index);
                    selectWearable(device.getName(), device.getAddress(), false);
                })
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    @SuppressLint("MissingPermission")
    private static String deviceLabel(BluetoothDevice device) {
        String name = device.getName();
        return name == null || name.isEmpty() ? device.getAddress() : name + "\n" + device.getAddress();
    }

    private void startBandInstall() {
        if (current != null || packages == null) return;
        if (bandSession != null && bandSession.isOpen()) {
            BandSession session = bandSession;
            InstallTarget target = selectedWearable.target;
            bandIdleDeadline = SystemClock.elapsedRealtime() + BAND_IDLE_MS;
            runOperationOn(versionWorker, "正在准备穿戴安装包", 75, false,
                    operation -> installPackages.resolve(target, operation::report), selected -> {
                        if (bandSession == session && session.isOpen()) {
                            installBand(selected, false);
                        } else if (visible) {
                            boolean fallback = session.directRejected && !session.route.isDirect();
                            closeBandSession();
                            showBandRecoveryActions(fallback ? getString(R.string.band_repair_guide)
                                    : "准备安装包时手环连接已断开，请重试连接。", true, fallback);
                        }
                    }, ignored -> {}, failure -> setStatus("未能准备穿戴安装包，请重试。"));
            return;
        }
        closeBandSession();
        prepareBand(true);
    }

    private void rePairBand(boolean installAfter) {
        if (current != null || packages == null) return;
        closeBandSession();
        prepareBand(true, installAfter, true);
    }

    private void prepareBand(boolean installAfter) {
        prepareBand(true, installAfter, false);
    }

    private void prepareBand(boolean readLatest, boolean installAfter) {
        prepareBand(readLatest, installAfter, false);
    }

    private void prepareBand(boolean readLatest, boolean installAfter, boolean forceRepair) {
        if (current != null || packages == null) return;
        String mac = WearableLog.address(value(address));
        if (mac.isEmpty()) {
            withBluetooth(true, () -> searchBand(installAfter));
            return;
        }
        WearableProfile fromAddress = WearableProfile.known(deviceSettings().getString("bandProfile:" + mac, ""));
        if (fromAddress != null && fromAddress.target.isRpk() && fromAddress != selectedWearable) {
            changeWearable(fromAddress, mac);
        }
        if (readLatest && value(authKey).trim().isEmpty() && logFolder != null && !usingPickedZip) {
            readLog(logFolder, true, () -> prepareBand(false, installAfter, forceRepair));
            return;
        }
        withBluetooth(() -> connectBand(installAfter, forceRepair));
    }

    private void requestBandLog(boolean installAfter) {
        bandSettings.setVisibility(View.VISIBLE);
        new AlertDialog.Builder(this).setTitle(R.string.band_log_title).setMessage(R.string.band_log_guide)
                .setPositiveButton(R.string.band_select_log_folder, (dialog, which) -> {
                    resumeBandInstallAfterLog = installAfter;
                    chooseLog(true);
                })
                .setNeutralButton(R.string.band_import_zip, (dialog, which) -> {
                    resumeBandInstallAfterLog = installAfter;
                    chooseLog(false);
                })
                .setNegativeButton(R.string.band_go_export, (dialog, which) -> openMiFitness()).show();
    }

    private void showMiFitnessGuide() {
        new AlertDialog.Builder(this).setTitle(R.string.band_health_help).setMessage(R.string.band_log_guide)
                .setPositiveButton(R.string.open_health, (dialog, which) -> openMiFitness())
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    private void showBandPairSettings() {
        new AlertDialog.Builder(this).setTitle(R.string.band_pair_settings).setMessage(R.string.band_pair_guide)
                .setPositiveButton(R.string.band_open_bluetooth_settings, (dialog, which) -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
                    } catch (ActivityNotFoundException unavailable) {
                        setStatus("请在手机的系统设置中打开蓝牙设置。");
                    }
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void openMiFitness() {
        launch(HEALTH_PACKAGES, "手机上未找到小米运动健康。");
    }

    private void installBand(InstallPackages.Selected selected, boolean oneTap) {
        BandSession session = bandSession;
        if (current != null || packages == null) return;
        if (session == null || !session.isOpen()) {
            closeBandSession();
            setStatus("请先连接手环。");
            return;
        }
        session.keepAliveEnabled = false;
        bandSession = null;
        main.removeCallbacks(checkBandConnection);
        InstallTarget target = selectedWearable.target;
        runOperation("正在初始化穿戴安装", 180, false, operation -> {
            operation.bandTrace = session.trace;
            try (session) {
                session.trace.event("install.request", "target", target, "one_tap", oneTap);
                operation.attach(session.socket);
                session.trace.event("install.package", "version_code", selected.versionCode(), "bytes", selected.bytes().length,
                        "github", "GitHub".equals(selected.source()));
                String installedKey = "bandVersion:" + session.address;
                int lastConfirmed = deviceSettings().getInt(installedKey, 0);
                if (selected.versionCode() < lastConfirmed) {
                    throw new InstallFailure("这只手环已确认安装过更高版本，请联网获取新版后重试。");
                }
                BandProtocol.Result result = session.protocol.install(selected.bytes(), operation::report);
                session.trace.event("install.finished", "result", result);
                SharedPreferences.Editor settings = deviceSettings().edit().putString("target", InstallTarget.BAND.name());
                if (result == BandProtocol.Result.CONFIRMED) {
                    settings.putInt(installedKey, selected.versionCode())
                            .putString("bandVersionName:" + session.address, selected.version());
                }
                settings.apply();
                return getString(packageLabel(target)) + " · " + selected.label() + "\n"
                        + getString(result == BandProtocol.Result.CONFIRMED ? R.string.band_confirmed : R.string.band_sent);
            } catch (Exception failure) {
                session.trace.failure("install.operation_failed", failure);
                throw failure;
            } finally {
                if (session.protocol.transferCompleted()) {
                    deviceSettings().edit().putString("bandTransferRoute:" + session.address, session.route.name()).apply();
                    session.trace.event("rfcomm.preferred_after_transfer", "route", session.route);
                }
            }
        }, this::setStatus, ignored -> {}, failure -> {
            if (failure instanceof BandProtocol.InstallRejected) {
                showBandInstallRejected(failureMessage(failure, "手环拒绝安装。"));
            } else if (session.directRejected && !session.route.isDirect()) {
                showBandRecoveryActions(getString(R.string.band_repair_guide), true, true);
            } else {
                showBandRecoveryActions(failureMessage(failure, "认证后安装中断，请重连后再试。"), true, false);
            }
        });
    }

    @SuppressLint("MissingPermission")
    private void connectBand(boolean installAfter, boolean forceRepair) {
        if (packages == null || current != null || bandSession != null) return;
        BandDiagnostics.Trace trace = bandDiagnostics.begin();
        trace.event("connect.request", "one_tap", installAfter, "force_repair", forceRepair);
        String manual = value(authKey).trim();
        BluetoothAdapter adapter = bluetooth();
        if (adapter == null) {
            trace.event("connect.adapter_unavailable");
            return;
        }
        String mac = WearableLog.address(value(address));
        if (mac.isEmpty()) {
            trace.event("connect.address_needed");
            address.setError("请输入有效的手环蓝牙地址");
            return;
        }
        BluetoothDevice device = adapter.getRemoteDevice(mac);
        WearableProfile detected = WearableProfile.fromBluetoothName(device.getName());
        if (detected == WearableProfile.WATCH_5) {
            selectWearable(device.getName(), mac, installAfter);
            return;
        }
        if (detected != null && detected != selectedWearable) {
            deviceSettings().edit().putString("bandProfile:" + mac, detected.name()).apply();
            changeWearable(detected, mac);
        }
        byte[] key;
        try {
            key = manual.isEmpty() && importedKeys != null ? importedKeys.copyFor(mac) : BandProtocol.parseKey(manual);
        } catch (IllegalArgumentException invalid) {
            trace.event("connect.key_unavailable", "manual", !manual.isEmpty());
            if (manual.isEmpty()) requestBandLog(installAfter);
            else {
                bandSettings.setVisibility(View.VISIBLE);
                authKey.setError(invalid.getMessage());
            }
            return;
        }
        if (key == null) {
            trace.event("connect.key_unmatched");
            setStatus(getString(R.string.band_log_no_match));
            requestBandLog(installAfter);
            return;
        }
        WearableProfile profile = selectedWearable;
        InstallTarget target = profile.target;
        boolean knownBand = mac.equals(profileAddress(profile));
        int bondState = device.getBondState();
        boolean reconnect = knownBand || bondState == BluetoothDevice.BOND_BONDED || forceRepair;
        String fallbackRoute = profile == WearableProfile.BAND_10_PRO ? ""
                : deviceSettings().getString("bandRoute:" + mac, "");
        BandRfcomm.Route remembered = forceRepair ? null : BandRfcomm.Route.remembered(
                deviceSettings().getString("bandTransferRoute:" + mac, fallbackRoute));
        observedBand = device;
        observedBandTrace = trace;
        observeBandUntil = SystemClock.elapsedRealtime() + TimeUnit.MINUTES.toMillis(4);
        trace.event("connect.parameters", "profile", profile, "reconnect", reconnect, "bond", bondState,
                "remembered_route", remembered, "manual_key", !manual.isEmpty(), "force_repair", forceRepair);
        int timeoutSeconds = installAfter ? (forceRepair ? 210 : 175) : (forceRepair ? 150 : 115);
        runOperation(forceRepair ? "正在重置手环蓝牙配对" : reconnect ? "正在重连已知手环" : "正在连接手环",
                timeoutSeconds, false,
                operation -> {
                    operation.bandTrace = trace;
                    try {
                        InstallPackages.Selected selected = installAfter
                                ? installPackages.resolve(target, operation::report) : null;
                        if ((Build.VERSION.SDK_INT < 31 || checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                                == PackageManager.PERMISSION_GRANTED) && adapter.isDiscovering()) {
                            adapter.cancelDiscovery();
                        }
                        return new PreparedBand(openBand(device, mac, key, reconnect, remembered, forceRepair,
                                operation, trace), selected);
                    } catch (Exception failure) {
                        trace.failure("connect.operation_failed", failure);
                        throw failure;
                    } finally {
                        Arrays.fill(key, (byte) 0);
                    }
                }, prepared -> {
                    BandSession session = prepared.session();
                    connectedBand(profile, session);
                    if (installAfter && bandSession == session) installBand(prepared.selected(), true);
                }, prepared -> prepared.session().close(), failure -> {
                    if (failure instanceof BandConnectionFailure && visible) {
                        showBandConnectionRecovery(reconnect, installAfter);
                    } else {
                        showBandRecoveryActions(failureMessage(failure, "设备会话建立失败，请核对蓝牙状态和 AuthKey。"),
                                installAfter, forceRepair || reconnect);
                    }
                });
    }

    private record PreparedBand(BandSession session, InstallPackages.Selected selected) {}

    private void showBandConnectionRecovery(boolean reconnect, boolean installAfter) {
        setStatus(getString(reconnect ? R.string.band_reconnect_failed : R.string.band_connect_failed)
                + "\n" + getString(reconnect ? R.string.band_retry_guide : R.string.band_first_pair_guide));
        retryBand.setVisibility(reconnect ? View.VISIBLE : View.GONE);
        retryBand.setText(R.string.band_retry);
        rescanBand.setVisibility(View.VISIBLE);
        repairBand.setVisibility(reconnect ? View.VISIBLE : View.GONE);
        retryBand.setOnClickListener(view -> prepareBand(installAfter));
        rescanBand.setOnClickListener(view -> withBluetooth(true, () -> searchBand(installAfter)));
        repairBand.setOnClickListener(view -> rePairBand(installAfter));
        bandRecovery.setVisibility(View.VISIBLE);
        bandActions.setVisibility(View.GONE);
    }

    private void showBandRecoveryActions(String message, boolean installAfter) {
        showBandRecoveryActions(message, installAfter, false);
    }

    private void showBandRecoveryActions(String message, boolean installAfter, boolean offerRepair) {
        setStatus(message);
        retryBand.setText(R.string.band_retry);
        retryBand.setVisibility(View.VISIBLE);
        rescanBand.setVisibility(View.VISIBLE);
        repairBand.setVisibility(offerRepair ? View.VISIBLE : View.GONE);
        retryBand.setOnClickListener(view -> prepareBand(installAfter));
        rescanBand.setOnClickListener(view -> withBluetooth(true, () -> searchBand(installAfter)));
        repairBand.setOnClickListener(view -> rePairBand(installAfter));
        bandRecovery.setVisibility(View.VISIBLE);
        bandActions.setVisibility(View.GONE);
    }

    private void showBandInstallRejected(String message) {
        setStatus(message);
        retryBand.setText(R.string.band_export);
        retryBand.setVisibility(View.VISIBLE);
        retryBand.setOnClickListener(view -> chooseRpkDestination());
        rescanBand.setVisibility(View.GONE);
        repairBand.setVisibility(View.GONE);
        bandRecovery.setVisibility(View.VISIBLE);
        bandActions.setVisibility(View.GONE);
    }

    @SuppressLint("MissingPermission")
    private BandSession openBand(BluetoothDevice device, String mac, byte[] key, boolean reconnect,
                                 BandRfcomm.Route remembered, boolean forceRepair, Operation operation,
                                 BandDiagnostics.Trace trace)
            throws Exception {
        BluetoothSocket socket = null;
        BandSession candidate = null;
        boolean authenticated = false;
        try {
            try {
                if (forceRepair && device.getBondState() != BluetoothDevice.BOND_NONE) {
                    resetBond(device, operation, trace);
                }
                if (device.getBondState() != BluetoothDevice.BOND_BONDED) {
                    trace.event("pairing.begin", "force_repair", forceRepair);
                    awaitPairing(device, forceRepair, operation, trace);
                    trace.event("pairing.end", "bond", device.getBondState());
                }
                operation.report(reconnect ? "正在尝试手环兼容重连" : "正在连接手环", -1);
                BandRfcomm.Connected connected = BandRfcomm.open(device, reconnect, remembered, deadlines,
                        operation::attach, trace);
                socket = connected.socket();
                candidate = new BandSession(mac, socket, connected.route(), connected.directRejected(), trace);
            } catch (IOException failed) {
                throw new BandConnectionFailure(failed);
            }
            operation.report("正在认证手环", -1);
            candidate.protocol.authenticate(key, Build.VERSION.SDK_INT, Build.MODEL);
            authenticated = true;
            return candidate;
        } finally {
            if (!authenticated) {
                if (candidate != null) candidate.close();
                else if (socket != null) {
                    try { socket.close(); } catch (IOException ignored) {}
                }
            }
        }
    }

    private void connectedBand(WearableProfile profile, BandSession session) {
        if (!visible || !session.isOpen()) {
            session.trace.event("session.unavailable_after_auth", "foreground", visible, "open", session.isOpen(),
                    "ms_since_auth", session.protocol.timeSinceAuth());
            session.close();
            setStatus("连接已断开。请回到助手后重新连接手环。");
            return;
        }
        bandSession = session;
        session.keepAliveEnabled = profile == WearableProfile.BAND_10_PRO;
        bandIdleDeadline = SystemClock.elapsedRealtime() + BAND_IDLE_MS;
        deviceSettings().edit().putString("bandAddress:" + profile.name(), session.address)
                .putString("bandProfile:" + session.address, profile.name())
                .putString("bandRoute:" + session.address, session.route.name()).apply();
        authKey.setText("");
        refreshBandButtons();
        setStatus("穿戴设备已连接并完成认证，可以安装。");
        main.postDelayed(checkBandConnection, session.keepAliveEnabled ? BAND_KEEP_ALIVE_MS : 1000);
    }

    private void checkBandConnection() {
        BandSession session = bandSession;
        if (session == null) return;
        boolean open = session.isOpen();
        if (!open || SystemClock.elapsedRealtime() >= bandIdleDeadline) {
            endIdleBandSession(session, open);
            return;
        }
        if (session.keepAliveEnabled && !session.keepAlivePending) probeBandSession(session);
        main.postDelayed(checkBandConnection, session.keepAliveEnabled ? BAND_KEEP_ALIVE_MS : 1000);
    }

    private void probeBandSession(BandSession session) {
        session.keepAlivePending = true;
        worker.execute(() -> {
            boolean failed = false;
            try {
                if (session.keepAliveEnabled && session.isOpen()) session.protocol.keepSessionAlive();
            } catch (Exception failure) {
                failed = true;
            } finally {
                boolean lost = failed;
                main.post(() -> {
                    session.keepAlivePending = false;
                    if (lost && bandSession == session) endIdleBandSession(session, false);
                });
            }
        });
    }

    private void endIdleBandSession(BandSession session, boolean expired) {
        session.trace.event(expired ? "session.idle_expired" : "session.idle_link_lost",
                "ms_since_auth", session.protocol.timeSinceAuth(), "socket_connected", session.socket.isConnected(),
                "route", session.route, "direct_rejected", session.directRejected);
        boolean fallback = !expired && session.directRejected && !session.route.isDirect();
        closeBandSession();
        if (current != null) return;
        if (expired) setStatus("空闲连接已释放。安装时可重新连接手环。");
        else showBandRecoveryActions(fallback ? getString(R.string.band_repair_guide)
                : "手环连接已断开，请重新连接。", false, fallback);
    }

    private void closeBandSession() {
        main.removeCallbacks(checkBandConnection);
        BandSession connected = bandSession;
        bandSession = null;
        if (connected != null) connected.close();
        refreshBandButtons();
    }

    private void refreshBandButtons() {
        if (connectBandButton == null || installBandButton == null) return;
        connectBandButton.setText(bandSession == null ? R.string.band_connect : R.string.band_disconnect);
        connectBandButton.setEnabled(current == null && packages != null);
        installBandButton.setEnabled(current == null && packages != null);
        String mac = WearableLog.address(value(address));
        WearableProfile profile = selectedWearable != null && selectedWearable.target.isRpk()
                ? selectedWearable : WearableProfile.BAND_10_PRO;
        boolean known = !mac.isEmpty() && mac.equals(profileAddress(profile));
        bandTitle.setText(profile.label);
        bandProduct.setText(getString(R.string.band_product, getString(packageLabel(profile.target))));
        bandDevice.setText(bandSession != null ? getString(R.string.band_connected, mac)
                : known ? getString(R.string.band_remembered, mac)
                : !mac.isEmpty() ? getString(R.string.band_selected, mac) : getString(R.string.band_unselected));
        bandDevice.setTextColor(bandSession != null ? COLOR_ACCENT : COLOR_SECONDARY);
        bandOnboarding.setVisibility(mac.isEmpty() ? View.VISIBLE : View.GONE);
        installBandButton.setText(mac.isEmpty() ? R.string.band_find_install : R.string.install_or_update);
    }

    private static final class BandConnectionFailure extends IOException {
        BandConnectionFailure(IOException cause) {
            super("未建立手环蓝牙连接", cause);
        }
    }

    @SuppressLint("MissingPermission")
    private static void resetBond(BluetoothDevice device, Operation operation, BandDiagnostics.Trace trace)
            throws InterruptedException, InstallFailure {
        operation.report("正在断开旧连接并重置蓝牙配对", -1);
        trace.event("pairing.reset_begin", "bond", device.getBondState());
        if (!BandRfcomm.removeBond(device, trace)) {
            throw new InstallFailure("系统未允许自动取消旧配对。请在「连接设置 → 检查系统蓝牙配对」中取消手环配对后再试。");
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (device.getBondState() == BluetoothDevice.BOND_NONE) {
                Thread.sleep(300);
                trace.event("pairing.reset_done");
                return;
            }
            Thread.sleep(150);
        }
        trace.event("pairing.reset_timeout", "bond", device.getBondState());
        throw new InstallFailure("旧蓝牙配对尚未释放。请在「连接设置 → 检查系统蓝牙配对」中取消手环配对后再试。");
    }

    @SuppressLint("MissingPermission")
    private static void awaitPairing(BluetoothDevice device, boolean preferClassic, Operation operation,
                                     BandDiagnostics.Trace trace)
            throws InterruptedException, InstallFailure {
        int state = device.getBondState();
        if (state == BluetoothDevice.BOND_BONDED) return;
        boolean bonding = state == BluetoothDevice.BOND_BONDING;
        if (!bonding) {
            boolean started = preferClassic ? BandRfcomm.createClassicBond(device, trace) : device.createBond();
            if (!started) {
                if (preferClassic) throw new InstallFailure("未能发起系统蓝牙配对，请保持手环亮屏靠近手机后再试。");
                return; // The RFCOMM connect path can request pairing too.
            }
        }

        operation.report(preferClassic ? "请在手机配对弹窗和手环上点击确认配对。"
                : "等待系统蓝牙配对。请在手机和手环上按提示确认。", -1);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(preferClassic ? 45 : 40);
        while (System.nanoTime() < deadline) {
            state = device.getBondState();
            if (state == BluetoothDevice.BOND_BONDED) return;
            if (state == BluetoothDevice.BOND_NONE && bonding) break;
            bonding |= state == BluetoothDevice.BOND_BONDING;
            Thread.sleep(250);
        }
        throw new InstallFailure(preferClassic ? "系统蓝牙配对未完成。请确认手机配对弹窗和手环上的确认提示后重试。"
                : "系统蓝牙配对未完成。请打开手环的「连接新手机」页面后再试。");
    }

    private void pairWatch() {
        if (current != null) return;
        try {
            String ip = WatchAdb.localIp(value(watchIp));
            int port = WatchAdb.port(value(watchPairPort));
            String code = value(watchPairCode).trim();
            if (!code.matches("[0-9]{6}")) {
                watchPairCode.setError("请输入手表显示的六位配对码");
                return;
            }
            watchPairCode.setText("");
            start("正在与手表配对", "配对失败。请在手表上重新打开「使用配对码配对」并核对端口和配对码。", 40,
                    operation -> {
                        try (WatchAdb adb = new WatchAdb(getApplicationContext())) {
                            operation.attach(adb);
                            if (!adb.pair(ip, port, code)) {
                                throw new InstallFailure("手表拒绝配对，请重新获取配对码和配对端口。");
                            }
                            return getString(R.string.watch_paired);
                        }
                    });
        } catch (IOException | IllegalArgumentException invalid) {
            setStatus(invalid.getMessage());
        }
    }

    private void installWatch() {
        if (packages == null || current != null) return;
        try {
            String ip = WatchAdb.localIp(value(watchIp));
            int port = WatchAdb.port(value(watchConnectPort));
            String endpoint = watchEndpoint();
            watchProbeSequence++;
            watchReading = false;
            runOperation("正在准备手表 APK", 140, false,
                    operation -> {
                        InstallPackages.Selected selected = installPackages.resolve(InstallTarget.WATCH, operation::report);
                        try (WatchAdb adb = new WatchAdb(getApplicationContext())) {
                            operation.attach(adb);
                            WatchAdb.Result result = adb.install(ip, port, selected.bytes(), selected.versionCode(),
                                    operation::report);
                            deviceSettings().edit().putString("target", InstallTarget.WATCH.name())
                                    .putString("watchIp", ip).putString("watchPort", Integer.toString(port)).apply();
                            String model = result.model().isEmpty() || result.model().length() > 48
                                    ? getString(R.string.watch_5) : result.model();
                            AppUpdate.Installed version = result.versionChecked()
                                    ? new AppUpdate.Installed(selected.version(), selected.versionCode()) : null;
                            String message = selected.label() + "\n" + (result.versionChecked()
                                    ? getString(R.string.watch_confirmed, model) : getString(R.string.watch_accepted));
                            return new WatchInstallOutcome(version, message);
                        }
                    }, outcome -> {
                        acceptWatchVersion(endpoint, outcome.version() != null, outcome.version());
                        setStatus(outcome.message());
                    }, ignored -> {}, failure -> {
                        watchSettings.setVisibility(View.VISIBLE);
                        setStatus(failureMessage(failure,
                                "手表连接中断，请核对无线调试地址及连接端口，再查看手表安装状态。"));
                    });
        } catch (IOException | IllegalArgumentException invalid) {
            watchSettings.setVisibility(View.VISIBLE);
            setStatus(invalid.getMessage());
        }
    }

    private record WatchInstallOutcome(AppUpdate.Installed version, String message) {}

    private void installPhone() {
        if (current != null || packages == null || phoneSession >= 0) return;
        if (!getPackageManager().canRequestPackageInstalls()) {
            openInstallPermission();
            return;
        }
        runOperation("正在准备口袋车控", "未能准备口袋车控安装。请重试。", 65, false, operation -> {
            InstallPackages.Selected selected = installPackages.resolve(InstallTarget.PHONE, operation::report);
            AppUpdate.Installed installed = PhoneInstall.installedVersion(this);
            if (installed != null && installed.code() > selected.versionCode()) {
                throw new InstallFailure("手机上的口袋车控 " + installed.label() + " 已高于当前可用安装包。");
            }
            operation.report("正在提交给安卓安装程序", -1);
            return new PreparedPhone(PhoneInstall.prepare(this, selected.bytes()), selected.label());
        }, this::submitPhone, prepared -> PhoneInstall.abandon(this, prepared.id()));
    }

    private record PreparedPhone(int id, String source) {}

    @SuppressWarnings("deprecation")
    private void openInstallPermission() {
        setStatus("首次请在安卓设置中允许此助手安装应用。");
        Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.fromParts("package", getPackageName(), null));
        try {
            startActivityForResult(settings, REQUEST_INSTALL_PERMISSION);
        } catch (ActivityNotFoundException unavailable) {
            setStatus("请在系统设置中允许此助手安装未知应用，再回来安装。");
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerPhoneResults() {
        IntentFilter filter = new IntentFilter(PhoneInstall.ACTION);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(phoneInstallResults, filter, PHONE_STATUS_PERMISSION, null, Context.RECEIVER_NOT_EXPORTED);
        } else {
            // The app declares and holds a signature permission on versions before receiver flags.
            registerReceiver(phoneInstallResults, filter, PHONE_STATUS_PERMISSION, null);
        }
    }

    private void submitPhone(PreparedPhone prepared) {
        if (!visible) {
            PhoneInstall.abandon(this, prepared.id());
            setStatus("请回到助手，点「安装或更新」并确认系统提示。");
            return;
        }
        phoneSession = prepared.id();
        phoneSource = prepared.source();
        refreshPhoneVersion();
        try {
            PhoneInstall.commit(this, phoneSession);
            setStatus(phoneSource + "\n等待安卓系统确认安装。");
        } catch (IOException | SecurityException | IllegalStateException failed) {
            PhoneInstall.abandon(this, phoneSession);
            phoneSession = -1;
            refreshPhoneVersion();
            setStatus("安卓安装程序无法打开，请检查安装权限后重试。");
        }
    }

    @SuppressWarnings("deprecation")
    private void onPhoneInstallResult(Intent result) {
        int state = result.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (state == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = Build.VERSION.SDK_INT >= 33 ? result.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class)
                    : result.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null && visible) {
                try {
                    startActivity(confirm);
                    return;
                } catch (ActivityNotFoundException | SecurityException unavailable) {
                    // Release the session if confirmation could not be shown.
                }
            }
            PhoneInstall.abandon(this, phoneSession);
        }
        phoneSession = -1;
        refreshPhoneVersion();
        if (state == PackageInstaller.STATUS_SUCCESS) {
            deviceSettings().edit().putString("target", InstallTarget.PHONE.name()).apply();
            setStatus(phoneSource + "\n口袋车控已安装，可直接打开。");
        } else if (state == PackageInstaller.STATUS_FAILURE_ABORTED) {
            setStatus("已取消口袋车控安装。");
        } else if (state == PackageInstaller.STATUS_FAILURE_CONFLICT) {
            setStatus("安卓系统报告应用冲突，请核对已装口袋车控的安装来源。");
        } else {
            setStatus("安卓系统未完成安装。请核对安装权限和系统提示后重试。");
        }
    }

    private void refreshPhoneVersion() {
        refreshUpdates();
        if (installPhoneButton != null) {
            installPhoneButton.setEnabled(current == null && packages != null && phoneSession < 0);
        }
    }

    private void checkForUpdates() {
        long now = SystemClock.elapsedRealtime();
        long interval = latestRelease == null ? 30_000 : UPDATE_INTERVAL_MS;
        if (packages == null || checkingRelease || now - lastReleaseCheck < interval) return;
        checkingRelease = true;
        lastReleaseCheck = now;
        refreshUpdates();
        versionWorker.execute(() -> {
            GitHubRelease.Release release;
            try {
                release = GitHubRelease.latestRelease();
            } catch (IOException unavailable) {
                release = null;
            }
            GitHubRelease.Release result = release;
            main.post(() -> {
                if (isDestroyed()) return;
                latestRelease = result;
                checkingRelease = false;
                refreshUpdates();
            });
        });
    }

    private String watchEndpoint() {
        return value(watchIp).trim() + "|" + value(watchConnectPort).trim();
    }

    private void probeWatch(boolean manual) {
        if (!visible || current != null || watchReading) return;
        String endpoint = watchEndpoint();
        if (!manual) {
            String lastIp = deviceSettings().getString("watchIp", "");
            String lastPort = deviceSettings().getString("watchPort", "");
            if (lastIp.isEmpty() || lastPort.isEmpty() || !endpoint.equals(lastIp + "|" + lastPort)) return;
            if (endpoint.equals(watchProbeEndpoint) && SystemClock.elapsedRealtime() - lastWatchProbe < 30_000) return;
        }
        final String ip;
        final int port;
        try {
            ip = WatchAdb.localIp(value(watchIp));
            port = WatchAdb.port(value(watchConnectPort));
        } catch (IOException | IllegalArgumentException invalid) {
            if (manual) setStatus(invalid.getMessage());
            return;
        }

        int sequence = ++watchProbeSequence;
        watchProbeEndpoint = endpoint;
        watchReading = true;
        watchRead = false;
        watchedVersion = null;
        refreshUpdates();
        versionWorker.execute(() -> {
            AppUpdate.Installed installed = null;
            boolean read = false;
            try (WatchAdb adb = new WatchAdb(getApplicationContext())) {
                ScheduledFuture<?> timeout = deadlines.schedule(() -> {
                    try {
                        adb.close();
                    } catch (IOException ignored) {
                        // The version request may already have disconnected.
                    }
                }, 10, TimeUnit.SECONDS);
                try {
                    installed = adb.readInstalled(ip, port);
                    read = true;
                } finally {
                    timeout.cancel(false);
                }
            } catch (Exception unavailable) {
                // An unavailable watch leaves the installed version unknown.
            }
            AppUpdate.Installed result = installed;
            boolean verified = read;
            main.post(() -> {
                if (!isDestroyed() && sequence == watchProbeSequence) acceptWatchVersion(endpoint, verified, result);
            });
        });
    }

    private void acceptWatchVersion(String endpoint, boolean read, AppUpdate.Installed version) {
        watchProbeEndpoint = endpoint;
        lastWatchProbe = SystemClock.elapsedRealtime();
        watchReading = false;
        watchRead = read;
        watchedVersion = version;
        refreshUpdates();
    }

    private void refreshUpdates() {
        AppUpdate.Installed phone = PhoneInstall.installedVersion(this);
        phoneVersion.setText(phone == null ? getString(R.string.phone_not_installed)
                : getString(R.string.phone_installed, phone.label()));
        openPhoneButton.setVisibility(phone == null ? View.GONE : View.VISIBLE);
        if (packages == null) return;
        if (checkingRelease) {
            updateSource.setText(R.string.update_checking);
            updateSource.setTextColor(COLOR_SECONDARY);
        } else if (latestRelease != null) {
            boolean hasNotes = !latestRelease.body().isEmpty();
            updateSource.setText(getString(hasNotes ? R.string.update_checked_notes : R.string.update_checked,
                    latestRelease.tag()));
            updateSource.setTextColor(hasNotes ? COLOR_ACCENT : COLOR_SECONDARY);
        } else if (lastReleaseCheck >= 0) {
            updateSource.setText(R.string.update_unavailable);
            updateSource.setTextColor(COLOR_SECONDARY);
        }

        String mac = WearableLog.address(value(address));
        int code = deviceSettings().getInt("bandVersion:" + mac, 0);
        String bandVersion = deviceSettings().getString("bandVersionName:" + mac, "");
        if (bandVersion.isEmpty() && code == packages.band.versionCode()) bandVersion = packages.band.version();
        AppUpdate.Installed band = code > 0 ? new AppUpdate.Installed(bandVersion, code) : null;
        boolean matchesWatch = watchEndpoint().equals(watchProbeEndpoint);
        AppUpdate.Installed watch = matchesWatch && watchRead ? watchedVersion : null;

        AppUpdate phoneResult = showUpdate(InstallTarget.PHONE, phone, phoneUpdate, "");
        boolean phoneHasUpdate = phoneResult.comparison() == AppUpdate.Comparison.UPDATE;
        InstallTarget rpk = selectedWearable != null && selectedWearable.target.isRpk()
                ? selectedWearable.target : InstallTarget.BAND;
        AppUpdate bandResult = showUpdate(rpk, band, bandUpdate,
                band == null ? getString(R.string.band_version_unknown) : getString(R.string.band_version_known, band.label()));
        bandHasUpdate = bandResult.comparison() == AppUpdate.Comparison.UPDATE;
        String watchStatus;
        if (matchesWatch && watchReading) watchStatus = getString(R.string.watch_version_reading);
        else if (watch != null) watchStatus = getString(R.string.watch_version_known, watch.label());
        else if (matchesWatch && watchRead) watchStatus = getString(R.string.watch_version_missing);
        else watchStatus = getString(R.string.watch_version_unknown);
        AppUpdate watchResult = showUpdate(InstallTarget.WATCH, watch, watchUpdate, watchStatus);
        watchHasUpdate = watchResult.comparison() == AppUpdate.Comparison.UPDATE;
        phoneTarget.setText(wearableChoice(R.string.target_phone, phoneHasUpdate));
        wearTarget.setText(wearableChoice(R.string.target_wearable, bandHasUpdate || watchHasUpdate));
        refreshReleaseNotes(phoneResult, bandResult, watchResult);
    }

    private void refreshReleaseNotes(AppUpdate phoneResult, AppUpdate bandResult, AppUpdate watchResult) {
        if (releaseNotesPanel == null) return;
        if (latestRelease == null || latestRelease.body().isEmpty()) {
            releaseNotesPanel.setVisibility(View.GONE);
            return;
        }
        boolean wearable = targets != null && targets.getCheckedRadioButtonId() == wearTarget.getId();
        boolean showInline;
        if (!wearable) {
            showInline = hasOnlineUpdate(phoneResult);
        } else if (selectedWearable == WearableProfile.WATCH_5) {
            showInline = hasOnlineUpdate(watchResult);
        } else if (selectedWearable != null && selectedWearable.target.isRpk()) {
            showInline = hasOnlineUpdate(bandResult);
        } else {
            showInline = hasOnlineUpdate(bandResult) || hasOnlineUpdate(watchResult);
        }
        if (!showInline) {
            releaseNotesPanel.setVisibility(View.GONE);
            return;
        }
        releaseNotesTitle.setText(releaseNotesHeading(latestRelease));
        releaseNotesBody.setText(latestRelease.body());
        releaseNotesPanel.setVisibility(View.VISIBLE);
    }

    private static boolean hasOnlineUpdate(AppUpdate update) {
        return update.online() && update.comparison() != AppUpdate.Comparison.CURRENT;
    }

    private String releaseNotesHeading(GitHubRelease.Release release) {
        if (!release.name().isEmpty() && !release.name().equalsIgnoreCase(release.tag())) {
            return getString(R.string.release_notes_title, release.tag()) + " · " + release.name();
        }
        return getString(R.string.release_notes_title, release.tag());
    }

    private void showReleaseNotesDialog() {
        if (latestRelease == null || latestRelease.body().isEmpty()) return;
        ScrollView bodyScroll = new ScrollView(this);
        TextView detail = text(latestRelease.body(), 14, COLOR_SECONDARY);
        detail.setTextIsSelectable(true);
        detail.setPadding(dp(22), dp(12), dp(22), dp(8));
        bodyScroll.addView(detail);
        new AlertDialog.Builder(this)
                .setTitle(releaseNotesHeading(latestRelease))
                .setView(bodyScroll)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private AppUpdate showUpdate(InstallTarget target, AppUpdate.Installed installed, TextView label, String installedLabel) {
        AppUpdate update = AppUpdate.compare(target.bundled(packages), latestRelease, target, installed);
        String available = getString(update.online() ? R.string.update_github_version : R.string.update_local_version,
                update.available());
        boolean newer = update.comparison() == AppUpdate.Comparison.UPDATE;
        String detail = switch (update.comparison()) {
            case UPDATE -> getString(R.string.update_available, available);
            case CURRENT -> getString(R.string.update_current, available);
            case UNKNOWN -> getString(R.string.update_package, available);
        };
        label.setTextColor(newer ? COLOR_ACCENT : COLOR_SECONDARY);
        label.setText(installedLabel.isEmpty() ? detail : installedLabel + "\n" + detail);
        return update;
    }

    private void launch(String[] names, String missing) {
        for (String name : names) {
            Intent activity = getPackageManager().getLaunchIntentForPackage(name);
            if (activity != null) {
                startActivity(activity);
                return;
            }
        }
        setStatus(missing);
    }

    private interface Work<T> {
        T run(Operation operation) throws Exception;
    }

    private void start(String initial, String fallback, int seconds, Work<String> work) {
        runOperation(initial, fallback, seconds, false, work, this::setStatus, ignored -> {});
    }

    private <T> void runOperation(String initial, String fallback, int seconds, boolean readingLog,
                                  Work<T> work, Consumer<T> onSuccess, Consumer<T> onDiscard) {
        runOperation(initial, seconds, readingLog, work, onSuccess, onDiscard,
                failure -> setStatus(failureMessage(failure, fallback)));
    }

    private static String failureMessage(Exception failure, String fallback) {
        return failure instanceof InstallFailure ? failure.getMessage() : fallback;
    }

    private <T> void runOperation(String initial, int seconds, boolean readingLog,
                                  Work<T> work, Consumer<T> onSuccess, Consumer<T> onDiscard,
                                  Consumer<Exception> onFailure) {
        runOperationOn(worker, initial, seconds, readingLog, work, onSuccess, onDiscard, onFailure);
    }

    private <T> void runOperationOn(ExecutorService executor, String initial, int seconds, boolean readingLog,
                                    Work<T> work, Consumer<T> onSuccess, Consumer<T> onDiscard,
                                    Consumer<Exception> onFailure) {
        if (current != null) return;
        getSystemService(InputMethodManager.class).hideSoftInputFromWindow(content.getWindowToken(), 0);
        Operation operation = new Operation(readingLog);
        current = operation;
        setBusy(true);
        setStatus(initial);
        operation.timer = deadlines.schedule(() -> operation.stop(true), seconds, TimeUnit.SECONDS);
        executor.execute(() -> {
            operation.thread = Thread.currentThread();
            T value = null;
            Exception error = null;
            try {
                value = work.run(operation);
            } catch (Exception failure) {
                error = failure;
            } finally {
                operation.timer.cancel(false);
                operation.connection = null;
                operation.thread = null;
            }
            T result = value;
            Exception failure = error;
            runOnUiThread(() -> {
                if (current != operation || isDestroyed()) {
                    if (result != null) onDiscard.accept(result);
                    return;
                }
                current = null;
                setBusy(false);
                if (operation.stopped) {
                    if (result != null) onDiscard.accept(result);
                    if (readingLog) setStatus(operation.timedOut ? "读取日志超时，请重新选择 ZIP。" : "已停止读取日志。");
                    else if (operation.timedOut) setStatus("操作超时。请先在穿戴设备上查看安装状态。");
                    else setStatus("已停止等待。若安装包已发送，请在穿戴设备上查看安装状态。");
                } else if (failure != null) {
                    onFailure.accept(failure);
                } else {
                    onSuccess.accept(result);
                }
            });
        });
    }

    private final class Operation {
        private final boolean readingLog;
        private volatile Thread thread;
        private volatile Closeable connection;
        private volatile boolean stopped;
        private volatile boolean timedOut;
        private volatile BandDiagnostics.Trace bandTrace = BandDiagnostics.Trace.OFF;
        private ScheduledFuture<?> timer;

        Operation(boolean readingLog) {
            this.readingLog = readingLog;
        }

        void attach(Closeable channel) throws InterruptedException {
            connection = channel;
            if (stopped) throw new InterruptedException();
        }

        void report(String stage, int percent) {
            runOnUiThread(() -> {
                if (current != this || stopped) return;
                setStatus(stage);
                progress.setIndeterminate(percent < 0);
                if (percent >= 0) progress.setProgress(percent);
            });
        }

        synchronized void stop(boolean timeout) {
            if (stopped) return;
            stopped = true;
            timedOut = timeout;
            bandTrace.event("operation.stop", "timeout", timeout);
            runOnUiThread(() -> {
                if (current == this && !isDestroyed()) {
                    cancel.setEnabled(false);
                    setStatus(readingLog ? "正在停止读取日志。"
                            : timeout ? "连接超时，正在结束操作。" : "正在断开连接。");
                }
            });
            Thread running = thread;
            if (running != null) running.interrupt();
            Closeable channel = connection;
            if (channel != null) {
                deadlines.execute(() -> {
                    try {
                        channel.close();
                    } catch (IOException ignored) {
                        // The worker is also responsible for closing the connection.
                    }
                });
            }
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        visible = true;
        refreshPhoneVersion();
        checkForUpdates();
        probeWatch(false);
    }

    @Override
    protected void onStop() {
        visible = false;
        if (bandSession != null) bandSession.trace.event("session.activity_stopped");
        closeBandSession();
        if (nearbyBandPicker != null) {
            nearbyBandPicker.close();
            nearbyBandPicker = null;
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(phoneInstallResults);
        unregisterReceiver(bandLinkEvents);
        closeBandSession();
        if (current != null) current.stop(false);
        if (importedKeys != null) importedKeys.close();
        worker.shutdownNow();
        versionWorker.shutdownNow();
        deadlines.shutdown();
        super.onDestroy();
    }

    private void setBusy(boolean busy) {
        setEnabled(targets, !busy);
        setEnabled(phonePanel, !busy);
        setEnabled(wearPanel, !busy);
        refreshBandButtons();
        refreshPhoneVersion();
        progress.setIndeterminate(true);
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        cancel.setEnabled(busy);
        cancel.setVisibility(busy ? View.VISIBLE : View.GONE);
        if (busy) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private static void setEnabled(View view, boolean enabled) {
        view.setEnabled(enabled);
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) setEnabled(group.getChildAt(i), enabled);
        }
    }

    private void setStatus(String message) {
        status.setText(message);
        status.setVisibility(message.isEmpty() ? View.GONE : View.VISIBLE);
        bandRecovery.setVisibility(View.GONE);
        shareDiagnosticsButton.setVisibility(View.GONE);
        bandActions.setVisibility(View.VISIBLE);
        feedback.setVisibility(message.isEmpty() && current == null ? View.GONE : View.VISIBLE);
    }

    @SuppressWarnings("deprecation")
    private void applyInsets() {
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets safe = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            } else {
                view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
    }

    private LinearLayout column() {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        return column;
    }

    private LinearLayout card() {
        LinearLayout card = column();
        GradientDrawable background = rounded(COLOR_SURFACE, 20);
        background.setStroke(dp(1), COLOR_BORDER);
        card.setBackground(background);
        card.setPadding(dp(18), dp(18), dp(18), dp(14));
        return card;
    }

    private LinearLayout inset() {
        LinearLayout view = column();
        view.setPadding(dp(12), dp(10), dp(12), dp(10));
        view.setBackground(rounded(COLOR_INSET, 12));
        return view;
    }

    private void addAction(LinearLayout row, Button action) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
        if (row.getChildCount() != 0) params.setMarginStart(dp(6));
        GradientDrawable outline = rounded(COLOR_INSET, 12);
        outline.setStroke(dp(1), COLOR_BORDER);
        action.setBackground(new RippleDrawable(ColorStateList.valueOf(0x2275dcc2), outline, null));
        row.addView(action, params);
    }

    private Button disclosure(int label, View details) {
        Button toggle = button(label, false,
                () -> details.setVisibility(details.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        toggle.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        toggle.setPadding(dp(12), 0, dp(12), 0);
        toggle.setText(getString(R.string.disclosure, getString(label)));
        return toggle;
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(radius));
        return background;
    }

    private void add(LinearLayout parent, View view, int margin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(margin);
        parent.addView(view, params);
    }

    private RadioButton radio(int label) {
        RadioButton target = new RadioButton(this);
        target.setId(View.generateViewId());
        target.setText(label);
        target.setTextSize(15);
        int[][] states = {{android.R.attr.state_checked}, {}};
        target.setTextColor(new ColorStateList(states, new int[]{COLOR_BACKGROUND, COLOR_SECONDARY}));
        android.graphics.drawable.StateListDrawable background = new android.graphics.drawable.StateListDrawable();
        background.addState(states[0], rounded(COLOR_ACCENT, 11));
        background.addState(states[1], rounded(Color.TRANSPARENT, 11));
        target.setBackground(background);
        target.setButtonDrawable(null);
        target.setGravity(Gravity.CENTER);
        return target;
    }

    private TextView heading(int label) {
        TextView view = text(getString(label), 23, COLOR_TEXT);
        view.setTypeface(null, Typeface.BOLD);
        view.setAccessibilityHeading(true);
        return view;
    }

    private LinearLayout wearableHeading(int label) {
        return wearableHeading(heading(label));
    }

    private LinearLayout wearableHeading(TextView title) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Button change = button(R.string.wearable_change, false, this::chooseWearableType);
        change.setContentDescription(getString(R.string.wearable_choose));
        change.setPadding(dp(8), 0, dp(8), 0);
        change.setMinWidth(0);
        change.setMinimumWidth(0);
        row.addView(change, new LinearLayout.LayoutParams(-2, -2));
        return row;
    }

    private TextView text(String label, int size, int color) {
        TextView view = new TextView(this);
        view.setText(label);
        view.setTextColor(color);
        view.setTextSize(size);
        return view;
    }

    private EditText input(int hint, int inputType) {
        EditText view = new EditText(this);
        view.setId(View.generateViewId());
        view.setSingleLine(true);
        view.setTextSize(16);
        view.setHint(hint);
        view.setInputType(inputType);
        return view;
    }

    private Button button(int label, boolean primary, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        button.setElevation(0);
        button.setStateListAnimator(null);
        if (primary) {
            int[][] states = {{-android.R.attr.state_enabled}, {}};
            android.graphics.drawable.StateListDrawable fill = new android.graphics.drawable.StateListDrawable();
            fill.addState(states[0], rounded(0xff354b46, 14));
            fill.addState(states[1], rounded(COLOR_ACCENT, 14));
            button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x26000000), fill, null));
            button.setTextColor(new ColorStateList(states, new int[]{COLOR_SECONDARY, COLOR_BACKGROUND}));
            button.setTypeface(null, Typeface.BOLD);
        } else {
            button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x2275dcc2), null,
                    rounded(Color.WHITE, 12)));
            button.setTextColor(COLOR_TEXT);
        }
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private int dp(int size) {
        return Math.round(size * getResources().getDisplayMetrics().density);
    }

    private static String value(EditText input) {
        return input.getText().toString();
    }
}
