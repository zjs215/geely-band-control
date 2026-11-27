package com.garage.bandcontrol.installer;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
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
    private static final String LOG_SETTINGS = "mi-fitness-log";
    private static final String LOG_FOLDER_URI = "folder";
    private static final String LOG_BAND_ADDRESS = "lastBandAddress";
    private static final int COLOR_BACKGROUND = 0xff111817;
    private static final int COLOR_SURFACE = 0xff202b29;
    private static final int COLOR_TEXT = 0xfff1faf7;
    private static final int COLOR_SECONDARY = 0xffbcceca;
    private static final int COLOR_ACCENT = 0xff75dcc2;
    private static final UUID SERIAL_PORT = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb");
    private static final String[] HEALTH_PACKAGES = {"com.mi.health", "com.xiaomi.wearable"};

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor();
    private BundledPackages packages;
    private ScrollView scroll;
    private LinearLayout content;
    private RadioGroup targets;
    private RadioButton bandTarget;
    private LinearLayout bandPanel;
    private LinearLayout watchPanel;
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
    private ProgressBar progress;
    private Button cancel;
    private Runnable afterPermission;
    private NearbyBandPicker nearbyBandPicker;
    private Operation current;

    @Override
    public void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        try {
            packages = new BundledPackages(getAssets());
        } catch (IOException ignored) {
            // Keep the screen available to show the package error.
        }
        String savedFolder = getSharedPreferences(LOG_SETTINGS, MODE_PRIVATE).getString(LOG_FOLDER_URI, "");
        if (!savedFolder.isEmpty()) logFolder = Uri.parse(savedFolder);
        createScreen();
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

        TextView title = text(getString(R.string.app_name), 28, COLOR_TEXT);
        title.setTypeface(null, Typeface.BOLD);
        add(content, title, 0);

        targets = new RadioGroup(this);
        targets.setOrientation(RadioGroup.HORIZONTAL);
        bandTarget = radio(R.string.band_10_pro);
        targets.addView(bandTarget, new RadioGroup.LayoutParams(0, dp(52), 1));
        targets.addView(radio(R.string.watch_5), new RadioGroup.LayoutParams(0, dp(52), 1));
        add(content, targets, 12);

        bandPanel = card();
        watchPanel = card();
        add(content, bandPanel, 10);
        add(content, watchPanel, 10);
        createBandControls();
        createWatchControls();

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setVisibility(View.GONE);
        add(content, progress, 14);
        status = text("", 15, COLOR_SECONDARY);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        status.setVisibility(View.GONE);
        add(content, status, 8);
        cancel = button(R.string.stop, false, () -> {
            if (current != null) current.stop(false);
        });
        cancel.setVisibility(View.GONE);
        add(content, cancel, 8);

        targets.setOnCheckedChangeListener((group, id) -> {
            bandPanel.setVisibility(id == bandTarget.getId() ? View.VISIBLE : View.GONE);
            watchPanel.setVisibility(id == bandTarget.getId() ? View.GONE : View.VISIBLE);
            if (packages != null) setStatus("");
        });
        targets.check(bandTarget.getId());
        if (packages == null) setStatus(getString(R.string.asset_error));
        content.requestFocus();
    }

    private void createBandControls() {
        if (packages != null) add(bandPanel, packageText(R.string.band_package, packages.band), 0);
        add(bandPanel, text(getString(R.string.band_guide), 15, COLOR_SECONDARY), 8);
        add(bandPanel, button(R.string.band_import_log, false, () -> chooseLog(false)), 10);
        folderButton = button(logFolder == null ? R.string.band_select_log_folder : R.string.band_change_log_folder,
                false, () -> chooseLog(true));
        add(bandPanel, folderButton, 4);
        add(bandPanel, button(R.string.band_nearby, false, () -> withBluetooth(true, this::searchBand)), 4);
        add(bandPanel, button(R.string.band_device, false, () -> withBluetooth(this::pickBand)), 4);
        address = input(R.string.band_address, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        if (logFolder != null) {
            address.setText(getSharedPreferences(LOG_SETTINGS, MODE_PRIVATE).getString(LOG_BAND_ADDRESS, ""));
        }
        add(bandPanel, address, 4);
        logStatus = text("", 14, COLOR_ACCENT);
        logStatus.setVisibility(View.GONE);
        logStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        add(bandPanel, logStatus, 4);
        address.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                refreshLogStatus();
            }
            @Override public void afterTextChanged(Editable text) {}
        });
        authKey = input(R.string.band_auth_key, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        authKey.setSaveEnabled(false);
        authKey.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        add(bandPanel, authKey, 4);
        Button install = button(R.string.band_install, true, () -> withBluetooth(this::installBand));
        install.setEnabled(packages != null);
        add(bandPanel, install, 10);
        add(bandPanel, button(R.string.open_health, false,
                () -> launch(HEALTH_PACKAGES, "手机上未找到小米运动健康")), 4);
    }

    private void createWatchControls() {
        if (packages != null) add(watchPanel, packageText(R.string.watch_package, packages.watch), 0);
        add(watchPanel, text(getString(R.string.watch_guide), 15, COLOR_SECONDARY), 8);
        int singleLine = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        watchIp = input(R.string.watch_host, singleLine);
        add(watchPanel, watchIp, 10);
        watchPairPort = input(R.string.watch_pair_port, InputType.TYPE_CLASS_NUMBER);
        watchPairCode = input(R.string.watch_pair_code, InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        watchPairCode.setSaveEnabled(false);
        watchPairCode.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        LinearLayout pairFields = new LinearLayout(this);
        pairFields.addView(watchPairPort, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams second = new LinearLayout.LayoutParams(0, -2, 1);
        second.setMarginStart(dp(10));
        pairFields.addView(watchPairCode, second);
        add(watchPanel, pairFields, 2);
        add(watchPanel, button(R.string.watch_pair, false, this::pairWatch), 4);
        watchConnectPort = input(R.string.watch_port, InputType.TYPE_CLASS_NUMBER);
        add(watchPanel, watchConnectPort, 4);
        Button install = button(R.string.watch_install, true, this::installWatch);
        install.setEnabled(packages != null);
        add(watchPanel, install, 10);
        add(watchPanel, button(R.string.open_companion, false,
                () -> launch(new String[]{BundledPackages.APP_PACKAGE}, "手机上未找到智在银河")), 4);
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
        if ((request != REQUEST_LOG_ZIP && request != REQUEST_LOG_FOLDER)
                || result != RESULT_OK || data == null || data.getData() == null) return;
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
        readLog(uri, folder, false);
    }

    private void readLog(Uri source, boolean folder, boolean installAfter) {
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
                    String selectedBefore = WearableLog.address(value(address));
                    if (value(address).trim().isEmpty()) {
                        String unique = keys.uniqueAddress();
                        if (!unique.isEmpty()) address.setText(unique);
                    }
                    refreshLogStatus();
                    if (installAfter && keys.matches(selectedBefore)) {
                        installBand(false);
                    } else {
                        setStatus(logStatus.getText().toString());
                    }
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

    private void searchBand() {
        BluetoothAdapter adapter = bluetooth();
        if (adapter == null) return;
        if (Build.VERSION.SDK_INT <= 30) {
            LocationManager location = getSystemService(LocationManager.class);
            if (location != null && !location.isLocationEnabled()) {
                setStatus("请打开手机的位置开关，再搜索附近的手环。");
                return;
            }
        }
        if (nearbyBandPicker != null) nearbyBandPicker.close();
        nearbyBandPicker = new NearbyBandPicker(this, adapter, WearableLog.address(value(address)), mac -> {
            address.setText(mac);
            if (importedKeys != null) {
                setStatus(logStatus.getText().toString());
            } else {
                setStatus("已选定手环。可点「安装到手环」继续。");
            }
        });
        nearbyBandPicker.show();
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
            setStatus("系统未列出已配对的设备。请在小米运动健康连接手环，或填写本人手环的蓝牙地址。");
            return;
        }
        paired.sort(Comparator.comparing(InstallerActivity::deviceLabel));
        String[] names = paired.stream().map(InstallerActivity::deviceLabel).toArray(String[]::new);
        new AlertDialog.Builder(this).setTitle(R.string.band_device)
                .setItems(names, (dialog, index) -> {
                    address.setText(paired.get(index).getAddress());
                    setStatus("");
                })
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    @SuppressLint("MissingPermission")
    private static String deviceLabel(BluetoothDevice device) {
        String name = device.getName();
        return name == null || name.isEmpty() ? device.getAddress() : name + "\n" + device.getAddress();
    }

    @SuppressLint("MissingPermission")
    private void installBand() {
        installBand(true);
    }

    @SuppressLint("MissingPermission")
    private void installBand(boolean readLatest) {
        if (packages == null || current != null) return;
        String manual = value(authKey).trim();
        if (readLatest && manual.isEmpty() && logFolder != null && !usingPickedZip) {
            readLog(logFolder, true, true);
            return;
        }
        BluetoothAdapter adapter = bluetooth();
        if (adapter == null) return;
        String mac = WearableLog.address(value(address));
        if (mac.isEmpty()) {
            address.setError("请输入有效的手环蓝牙地址");
            return;
        }
        byte[] key;
        try {
            key = manual.isEmpty() && importedKeys != null ? importedKeys.copyFor(mac) : BandProtocol.parseKey(manual);
        } catch (IllegalArgumentException invalid) {
            authKey.setError(manual.isEmpty() ? "请导入运动健康日志或填写 AuthKey" : invalid.getMessage());
            return;
        }
        if (key == null) {
            setStatus(getString(R.string.band_log_no_match));
            return;
        }
        if (manual.isEmpty() && logFolder != null && !usingPickedZip) {
            getSharedPreferences(LOG_SETTINGS, MODE_PRIVATE).edit().putString(LOG_BAND_ADDRESS, mac).apply();
        }
        authKey.setText("");
        start("正在连接手环", "蓝牙安装失败。请核对手环地址、AuthKey 和蓝牙连接状态。", 160,
                operation -> {
                    try {
                        byte[] rpk = packages.verifiedBytes(packages.band);
                        BluetoothDevice device = adapter.getRemoteDevice(mac);
                        if ((Build.VERSION.SDK_INT < 31 || checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                                == PackageManager.PERMISSION_GRANTED) && adapter.isDiscovering()) {
                            adapter.cancelDiscovery();
                        }
                        awaitPairing(device, operation);
                        operation.report("正在连接手环", -1);
                        try (BluetoothSocket socket = device.createRfcommSocketToServiceRecord(SERIAL_PORT)) {
                            operation.attach(socket);
                            try {
                                socket.connect();
                            } catch (IOException connecting) {
                                throw new InstallFailure("未能连接手环。请让手环停在「连接新手机」页面，在助手内重新搜索并选择它。", connecting);
                            }
                            try (BandWire wire = new BandWire(socket.getInputStream(), socket.getOutputStream())) {
                                BandProtocol.Result result = new BandProtocol(wire).install(key,
                                        Build.VERSION.SDK_INT, Build.MODEL, rpk, operation::report);
                                return getString(result == BandProtocol.Result.CONFIRMED
                                        ? R.string.band_confirmed : R.string.band_sent);
                            }
                        }
                    } finally {
                        Arrays.fill(key, (byte) 0);
                    }
                });
    }

    @SuppressLint("MissingPermission")
    private static void awaitPairing(BluetoothDevice device, Operation operation)
            throws InterruptedException, InstallFailure {
        int state = device.getBondState();
        if (state == BluetoothDevice.BOND_BONDED) return;
        boolean bonding = state == BluetoothDevice.BOND_BONDING;
        if (!bonding && !device.createBond()) return; // The RFCOMM connect path can request pairing too.

        operation.report("等待系统蓝牙配对。请在手机和手环上按提示确认。", -1);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40);
        while (System.nanoTime() < deadline) {
            state = device.getBondState();
            if (state == BluetoothDevice.BOND_BONDED) return;
            if (state == BluetoothDevice.BOND_NONE && bonding) break;
            bonding |= state == BluetoothDevice.BOND_BONDING;
            Thread.sleep(250);
        }
        throw new InstallFailure("系统蓝牙配对未完成。请打开手环的「连接新手机」页面后再试。");
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
            start("正在读取手表 APK", "手表连接中断，请核对无线调试地址及连接端口，再查看手表安装状态。", 100,
                    operation -> {
                        byte[] apk = packages.verifiedBytes(packages.watch);
                        try (WatchAdb adb = new WatchAdb(getApplicationContext())) {
                            operation.attach(adb);
                            WatchAdb.Result result = adb.install(ip, port, apk, packages.watch.versionCode(),
                                    operation::report);
                            String model = result.model().isEmpty() || result.model().length() > 48
                                    ? getString(R.string.watch_5) : result.model();
                            return result.versionChecked()
                                    ? getString(R.string.watch_confirmed, model) : getString(R.string.watch_accepted);
                        }
                    });
        } catch (IOException | IllegalArgumentException invalid) {
            setStatus(invalid.getMessage());
        }
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
        if (current != null) return;
        getSystemService(InputMethodManager.class).hideSoftInputFromWindow(content.getWindowToken(), 0);
        Operation operation = new Operation(readingLog);
        current = operation;
        setBusy(true);
        setStatus(initial);
        operation.timer = deadlines.schedule(() -> operation.stop(true), seconds, TimeUnit.SECONDS);
        worker.execute(() -> {
            operation.thread = Thread.currentThread();
            T value = null;
            String error = null;
            try {
                value = work.run(operation);
            } catch (Exception failure) {
                error = failure instanceof InstallFailure ? failure.getMessage() : fallback;
            } finally {
                operation.timer.cancel(false);
                operation.connection = null;
                operation.thread = null;
            }
            T result = value;
            String message = error;
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
                } else if (message != null) {
                    setStatus(message);
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
    protected void onStop() {
        if (nearbyBandPicker != null) {
            nearbyBandPicker.close();
            nearbyBandPicker = null;
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (current != null) current.stop(false);
        if (importedKeys != null) importedKeys.close();
        worker.shutdownNow();
        deadlines.shutdown();
        super.onDestroy();
    }

    private void setBusy(boolean busy) {
        setEnabled(targets, !busy);
        setEnabled(bandPanel, !busy);
        setEnabled(watchPanel, !busy);
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
        if (!message.isEmpty()) scroll.post(() -> scroll.smoothScrollTo(0, content.getHeight()));
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
        GradientDrawable background = new GradientDrawable();
        background.setColor(COLOR_SURFACE);
        background.setCornerRadius(dp(16));
        card.setBackground(background);
        card.setPadding(dp(16), dp(16), dp(16), dp(12));
        return card;
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
        target.setTextColor(COLOR_TEXT);
        target.setGravity(Gravity.CENTER_VERTICAL);
        return target;
    }

    private TextView text(String label, int size, int color) {
        TextView view = new TextView(this);
        view.setText(label);
        view.setTextColor(color);
        view.setTextSize(size);
        return view;
    }

    private TextView packageText(int format, BundledPackages.Package packaged) {
        TextView label = text(getString(format, packaged.version(), packaged.size() / 1024), 18, COLOR_ACCENT);
        label.setTypeface(null, Typeface.BOLD);
        return label;
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
        if (primary) {
            button.setBackgroundTintList(ColorStateList.valueOf(COLOR_ACCENT));
            button.setTextColor(Color.BLACK);
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
