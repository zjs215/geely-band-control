package com.otrong.carcontrol.watch;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.TextUtils;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import com.otrong.carcontrol.appearance.ThemeArtwork;
import com.otrong.carcontrol.appearance.LauncherIcons;
import com.otrong.carcontrol.appearance.ThemeClock;
import com.otrong.carcontrol.appearance.ThemeConfig;
import com.otrong.carcontrol.appearance.ThemeDialogs;
import com.otrong.carcontrol.appearance.ThemeEditor;
import com.otrong.carcontrol.appearance.ThemeStyles;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class WatchActivity extends Activity implements BluetoothPhoneClient.Listener {
    private static final int BG = Color.rgb(11, 18, 30);
    private static final int PANEL = Color.rgb(20, 31, 48);
    private static final int META = Color.rgb(21, 34, 54);
    private static final int ORB_ACTIVE = Color.rgb(24, 98, 232);
    private static final int ORB_ACTIVE_BORDER = Color.rgb(79, 146, 255);
    private static final int ORB_READY = Color.rgb(29, 42, 61);
    private static final int ORB_READY_BORDER = Color.rgb(50, 73, 105);
    private static final int ORB_PAUSED = Color.rgb(20, 29, 43);
    private static final int ORB_PAUSED_BORDER = Color.rgb(33, 48, 68);
    private static final int ORB_UNASSIGNED = Color.rgb(18, 26, 38);
    private static final int ORB_UNASSIGNED_BORDER = Color.rgb(30, 42, 59);
    private static final int INK = Color.rgb(246, 249, 255);
    private static final int SUB_INK = Color.rgb(200, 215, 235);
    private static final int MUTED = Color.rgb(142, 166, 197);
    private static final int MINT = Color.rgb(158, 233, 206);
    private static final int ELECTRIC = Color.rgb(111, 231, 165);
    private static final int FUEL = Color.rgb(116, 183, 255);
    private static final String DEFAULT_VEHICLE_NAME = "我的座驾";

    private static final long STATUS_INTERVAL_MS = 12_000;
    private static final long CONTROL_STATUS_INTERVAL_MS = 1_500;
    private static final long PHONE_CONFIRM_INTERVAL_MS = 2_500;
    private static final long TOAST_DURATION_MS = 2_800;
    private static final long HELLO_TIMEOUT_MS = 2_000;
    private static final long CONFIRM_TIMEOUT_MS = 13_000;
    private static final long FAILURE_PULSE_GAP_MS = 200;
    private static final int BT_PERMISSION_REQUEST = 41;
    private static final String PREFS_NAME = "watch_garage";
    private static final String SNAPSHOT_KEY = "vehicle-snapshot";
    private static final String CAR_IMAGE_HASH_KEY = "car-image-hash";
    private static final String CAR_IMAGE_FILE_KEY = "car-image-file";
    private static final String CONTROL_ICON_CACHE_KEY = "control-icon-cache";
    private static final int MAX_CONTROL_ICON_FILES = 24;
    private record IconSyncTarget(String hash, int total) {}
    private static final String EVENTS_KEY = "connection-events";
    private static final String HAPTICS_KEY = "control-haptics";
    private static final String TWO_PAGE_KEY = "watch-two-page";
    private static final String PHONE_ADDR_KEY = "preferred-phone-address";
    private static final String LOCAL_THEME_KEY = "theme-local";
    private static final String PHONE_THEME_KEY = "theme-phone";
    private static final String FOLLOW_THEME_KEY = "theme-follow-phone";

    private record PendingRequest(String id, String type, String action, String controlDirection) {}
    private record EventEntry(long ts, String event, String id, String type, String detail) {}

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Bitmap> assetCache = new HashMap<>();
    private final List<EventEntry> events = new ArrayList<>();

    private BluetoothPhoneClient client;
    private SharedPreferences prefs;
    private Vibrator vibrator;

    private FrameLayout homeShell;
    private VerticalPagerView homePager;
    private LinearLayout pagerDots;
    private View pagerDot0;
    private View pagerDot1;
    private LinearLayout singlePage;
    private TextView singleRefreshView;
    private TextView singleRangeView;
    private ImageView singleCarView;
    private TextView singleEmptyView;
    private LinearLayout singleGridContainer;
    private ScrollView settingsScroll;
    private LinearLayout confirmScreen;
    private FrameLayout toastBox;
    private TextView toastView;

    private TextView vehicleNameView;
    private TextView totalHeroGhostView;
    private TextView totalHeroView;
    private TextView totalHeroUnitView;
    private View electricSummary;
    private View fuelSummary;
    private TextView electricText;
    private TextView fuelText;
    private View electricFill;
    private View fuelFill;
    private View connectionDot;
    private TextView connectionLabel;
    private View vehicleMotionGroup;
    private ImageView vehicleParkedIcon;
    private TextView vehicleMotionView;
    private ImageView carImageView;
    private TextView controlsRefreshView;
    private TextView emptyView;
    private LinearLayout gridContainer;

    private ImageView confirmIconView;
    private TextView confirmTitleView;
    private Switch hapticsSwitch;
    private Switch twoPageSwitch;
    private TextView phoneTargetView;
    private LinearLayout diagnosticsBox;
    private ImageView themeBackdrop;
    private FrameLayout watchRoot;
    private Drawable classicRootBackground;
    private final ThemeStyles themeStyles = new ThemeStyles();
    private View mileagePage;
    private Drawable classicMileageBackground;
    private TextView themeSetting;
    private ThemeClock themeClock;
    private ThemeConfig localTheme = ThemeConfig.CLASSIC;
    private ThemeConfig phoneTheme = ThemeConfig.CLASSIC;
    private ThemeConfig.Variant themeVariant;
    private boolean followPhoneTheme;
    private int controlColumns;
    private boolean narrowScreen;

    private boolean visible;
    private boolean roundScreen;
    private WatchProtocol.WatchScale uiScale;
    private boolean settingsOpen;
    private boolean hapticsEnabled = true;
    private boolean twoPageMode = true;
    private Object backInvokedCallback;
    private boolean backCallbackRegistered;
    private boolean swipeTracking;
    private boolean swipeTriggered;
    private float swipeStartX;
    private float swipeStartY;
    private String singleRangeSummary = "";
    private boolean handshakeComplete;
    private boolean linked;
    private boolean hasReply;
    private boolean phoneConfirmation;
    private boolean controlPending;
    private boolean cachedVehicle;
    private boolean controlDirectionsSupported;

    private String mode = "";
    private String vehicleName = DEFAULT_VEHICLE_NAME;
    private String brand = "GALAXY";
    private String modeLabel = "待连接";
    private String motionLabel = "状态未知";
    private String updatedLabel = "等待车况";
    private String compatibilityNotice = "";
    private String controlNotice = "";
    private String confirmAction = "";
    private String confirmToken = "";
    private String confirmTitle = "";
    private String confirmIconAsset = "";
    private String storedSnapshot = "";
    private String carImageHash = "";
    private String failedCarImageHash = "";
    private String imageSyncHash = "";
    private int imageSyncTotal;
    private final List<String> imageSyncChunks = new ArrayList<>();
    private final Map<String, String> controlIconHashes = new LinkedHashMap<>();
    private final Map<String, String> controlIconTargets = new LinkedHashMap<>();
    private final Map<String, String> controlIconFiles = new LinkedHashMap<>();
    private final List<String> controlIconOrder = new ArrayList<>();
    private final Map<String, Bitmap> controlIconBitmaps = new LinkedHashMap<>();
    private final List<String> failedControlIconHashes = new ArrayList<>();
    private final List<IconSyncTarget> iconSyncQueue = new ArrayList<>();
    private String iconSyncHash = "";
    private int iconSyncTotal;
    private final List<String> iconSyncChunks = new ArrayList<>();

    private List<String> mapped = new ArrayList<>();
    private List<String> available = new ArrayList<>();
    private Map<String, String> controlDirections = new LinkedHashMap<>();
    private JSONObject lynkControlLabels;
    private JSONObject controlStates;
    private JSONObject blockedControls = new JSONObject();
    private List<String> features;
    private PendingRequest pending;
    private int sequence;
    private int reconnectAttempt;
    private boolean reconnectScheduled;
    private boolean receiptShowing;
    private boolean interruptedExecution;
    private boolean statusOnly;
    private String connectionError = "";

    private final Runnable pollTask = this::syncStatus;
    private final Runnable reconnectTask = () -> {
        reconnectScheduled = false;
        if (visible && client.hasPermission()) ensureBluetoothAndConnect();
    };
    private final Runnable toastTask = this::clearToast;
    private final Runnable requestTimeoutTask = this::onRequestTimeout;
    private final Runnable confirmTimeoutTask = this::onConfirmTimeout;
    private final Runnable secondPulseTask = this::vibrateShort;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        localTheme = readTheme(LOCAL_THEME_KEY);
        phoneTheme = readTheme(PHONE_THEME_KEY);
        followPhoneTheme = prefs.getBoolean(FOLLOW_THEME_KEY, true);
        vibrator = getSystemService(Vibrator.class);
        hapticsEnabled = !"off".equals(prefs.getString(HAPTICS_KEY, "on"));
        twoPageMode = !"off".equals(prefs.getString(TWO_PAGE_KEY, "on"));
        roundScreen = getResources().getConfiguration().isScreenRound();
        DisplayMetrics dm = getResources().getDisplayMetrics();
        controlColumns = WatchProtocol.controlColumns(roundScreen, dm.widthPixels, dm.heightPixels);
        narrowScreen = !roundScreen && dm.heightPixels >= dm.widthPixels * 1.8f;
        int diameterPx = Math.min(dm.widthPixels, dm.heightPixels);
        uiScale = new WatchProtocol.WatchScale(roundScreen, diameterPx, dm.density);
        loadEvents();

        client = new BluetoothPhoneClient(this, main, this);
        client.setPreferredAddress(prefs.getString(PHONE_ADDR_KEY, ""));

        setContentView(buildUi());
        themeClock = new ThemeClock(this, this::activeTheme, this::renderTheme);
        loadSnapshot();
        loadCachedControlIcons();
        loadCachedCarImage();
        if (carImageHash.isEmpty()) showDefaultCarImage();
        renderAll();
    }

    @Override protected void onStart() {
        super.onStart();
        visible = true;
        themeClock.start();
        reconnectAttempt = 0;
        connectionError = "";
        statusOnly = hasReply || interruptedExecution;
        if (interruptedExecution) {
            interruptedExecution = false;
            showExecutionReceipt("unknown", "");
        }
        updateBackInvokedCallback();
        ensureBluetoothAndConnect();
    }

    @Override protected void onStop() {
        visible = false;
        themeClock.stop();
        stopSession();
        super.onStop();
    }

    @Override protected void onDestroy() {
        unregisterBackInvokedCallback();
        super.onDestroy();
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        themeClock.refresh();
    }

    private boolean hasBackTarget() {
        return WatchProtocol.shouldInterceptBack(!confirmTitle.isEmpty(), settingsOpen,
                useTwoPageShell(), homePager != null ? homePager.currentPage() : 0);
    }

    private boolean handleBackNavigation() {
        if (!confirmTitle.isEmpty()) {
            cancelConfirmation();
            return true;
        }
        if (settingsOpen) {
            settingsOpen = false;
            renderScreens();
            return true;
        }
        if (useTwoPageShell() && homePager != null && homePager.currentPage() > 0) {
            homePager.setCurrentPage(0, true);
            updateBackInvokedCallback();
            return true;
        }
        return false;
    }

    private void updateBackInvokedCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        boolean needCallback = hasBackTarget();
        if (needCallback && !backCallbackRegistered) {
            android.window.OnBackInvokedCallback callback =
                    (android.window.OnBackInvokedCallback) backInvokedCallback;
            if (callback == null) {
                callback = this::handleBackNavigation;
                backInvokedCallback = callback;
            }
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback);
            backCallbackRegistered = true;
        } else if (!needCallback && backCallbackRegistered) {
            unregisterBackInvokedCallback();
        }
    }

    private void unregisterBackInvokedCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || !backCallbackRegistered) return;
        android.window.OnBackInvokedCallback callback =
                (android.window.OnBackInvokedCallback) backInvokedCallback;
        if (callback != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(callback);
        }
        backCallbackRegistered = false;
    }

    private boolean isTouchOnView(View view, MotionEvent event) {
        if (view == null || view.getVisibility() != View.VISIBLE) return false;
        int[] loc = new int[2];
        view.getLocationOnScreen(loc);
        float x = event.getRawX();
        float y = event.getRawY();
        return x >= loc[0] && x <= loc[0] + view.getWidth()
                && y >= loc[1] && y <= loc[1] + view.getHeight();
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            swipeStartX = event.getX();
            swipeStartY = event.getY();
            swipeTriggered = false;
            swipeTracking = !isTouchOnView(hapticsSwitch, event) && !isTouchOnView(twoPageSwitch, event);
        } else if (action == MotionEvent.ACTION_MOVE && swipeTracking && !swipeTriggered) {
            float dx = event.getX() - swipeStartX;
            float dy = Math.abs(event.getY() - swipeStartY);
            if (!confirmTitle.isEmpty() || settingsOpen) {
                if (dx > r(42) && dx > dy * 1.6f) {
                    swipeTriggered = true;
                    swipeTracking = false;
                    MotionEvent cancel = MotionEvent.obtain(event);
                    cancel.setAction(MotionEvent.ACTION_CANCEL);
                    super.dispatchTouchEvent(cancel);
                    cancel.recycle();
                    handleBackNavigation();
                    return true;
                }
            } else if (!hasBackTarget()) {
                if (swipeStartX <= r(36) && dx > r(72) && dx > dy * 1.8f) {
                    swipeTriggered = true;
                    swipeTracking = false;
                    finish();
                    return true;
                }
            }
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            boolean consumed = swipeTriggered;
            swipeTracking = false;
            swipeTriggered = false;
            if (consumed) return true;
        }
        return super.dispatchTouchEvent(event);
    }

    @Override public void onBackPressed() {
        if (handleBackNavigation()) return;
        super.onBackPressed();
    }

    @Override public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (useTwoPageShell() && homeShell != null && homeShell.getVisibility() == View.VISIBLE
                && homePager != null && homePager.handleCrownMotion(event)) {
            return true;
        }
        return super.dispatchGenericMotionEvent(event);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == BT_PERMISSION_REQUEST && client.hasPermission() && visible) {
            client.connect();
        }
    }

    private void ensureBluetoothAndConnect() {
        if (!visible) return;
        cancelReconnect();
        if (Build.VERSION.SDK_INT >= 31
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, BT_PERMISSION_REQUEST);
            modeLabel = "待授权";
            renderMeta();
            return;
        }
        if (!client.isConnected()) {
            modeLabel = "连接中";
            renderMeta();
            client.connect();
        } else if (handshakeComplete) {
            syncStatus();
        }
    }

    private void stopSession() {
        main.removeCallbacks(pollTask);
        cancelReconnect();
        clearToast();
        main.removeCallbacks(requestTimeoutTask);
        main.removeCallbacks(confirmTimeoutTask);
        main.removeCallbacks(secondPulseTask);
        clearConfirmationState();
        interruptedExecution = pending != null && "execute".equals(pending.type());
        pending = null;
        resetImageSync();
        handshakeComplete = false;
        linked = false;
        client.disconnect(null);
    }

    @Override public void onConnected(String deviceName) {
        if (!visible) return;
        cancelReconnect();
        connectionError = "";
        recordEvent("bt_connected", null, deviceName);
        handshakeComplete = false;
        sendRequest("hello", null, null, null);
    }

    @Override public void onMessage(byte[] data) {
        if (!visible || pending == null) return;
        if (data.length > WatchFraming.MAX_FRAME_BYTES) {
            recordEvent("drop", pending, "too_large");
            return;
        }
        try {
            JSONObject reply = new JSONObject(new String(data, StandardCharsets.UTF_8));
            if (!pending.id().equals(reply.optString("id"))) return;
            handleReply(pending, reply);
        } catch (JSONException ignored) {
        }
    }

    @Override public void onDisconnected(String reason) {
        if (!visible) return;
        disconnect(reason, "closed", pending);
    }

    @Override public void onError(String message) {
        if (!visible) return;
        modeLabel = "未连接";
        if (!message.equals(connectionError) && !receiptShowing) showToast(message);
        connectionError = message;
        renderAll();
        scheduleReconnect();
    }

    private void scheduleReconnect() {
        main.removeCallbacks(pollTask);
        if (!visible || reconnectScheduled || !client.hasPermission()) return;
        reconnectScheduled = true;
        long delay = WatchProtocol.reconnectDelayMillis(reconnectAttempt);
        reconnectAttempt = Math.min(5, reconnectAttempt + 1);
        main.postDelayed(reconnectTask, delay);
    }

    private void cancelReconnect() {
        main.removeCallbacks(reconnectTask);
        reconnectScheduled = false;
    }

    private void syncStatus() {
        if (!visible || !compatibilityNotice.isEmpty()) return;
        if (!client.isConnected()) {
            scheduleReconnect();
            return;
        }
        if (!handshakeComplete) {
            sendRequest("hello", null, null, null);
            return;
        }
        sendRequest("status", null, null, null);
    }

    private void schedulePoll() {
        main.removeCallbacks(pollTask);
        if (!visible || pending != null || !confirmTitle.isEmpty() || !compatibilityNotice.isEmpty()) return;
        long delay = phoneConfirmation ? PHONE_CONFIRM_INTERVAL_MS
                : controlPending ? CONTROL_STATUS_INTERVAL_MS : STATUS_INTERVAL_MS;
        main.postDelayed(pollTask, delay);
    }

    private boolean isBusy() {
        return pending != null && !"status".equals(pending.type())
                && !"car_image".equals(pending.type())
                && !"control_icon".equals(pending.type());
    }

    private void triggerRefresh() {
        if (isBusy() || !confirmTitle.isEmpty()) return;
        if (controlPending) {
            showToast(controlNotice.isEmpty() ? "正在同步车况" : controlNotice);
            syncStatus();
            return;
        }
        if (!client.isConnected()) {
            ensureBluetoothAndConnect();
            return;
        }
        if (supportsRequest("refresh")) showToast("正在刷新车况…");
        sendRequest("refresh", null, null, null);
    }

    private void sendRequest(String type, String action, String token, String direction) {
        if (!visible || !confirmTitle.isEmpty()) return;
        if (!"hello".equals(type) && !handshakeComplete) return;
        if (!supportsRequest(type)) {
            showToast("手机端不支持此功能，请更新到配套版本");
            if ("prepare".equals(type) || "execute".equals(type)) vibrateFailure();
            return;
        }
        if (pending != null && ((!"status".equals(pending.type()) && !"car_image".equals(pending.type())
                && !"control_icon".equals(pending.type()))
                || "status".equals(type) || "car_image".equals(type) || "control_icon".equals(type))) return;
        clearPending();
        main.removeCallbacks(pollTask);

        String id = Long.toString(System.currentTimeMillis(), 36) + "_" + Long.toString(++sequence, 36);
        PendingRequest req = new PendingRequest(id, type, action, direction);
        pending = req;
        renderMeta();

        long timeout = "hello".equals(type) ? HELLO_TIMEOUT_MS : "refresh".equals(type) ? 13_000 : 8_000;
        main.postDelayed(requestTimeoutTask, timeout);
        try {
            JSONObject request = "car_image".equals(type)
                    ? WatchProtocol.buildCarImageRequest(id, imageSyncHash, imageSyncChunks.size())
                    : "control_icon".equals(type)
                    ? WatchProtocol.buildControlIconRequest(id, iconSyncHash, iconSyncChunks.size())
                    : WatchProtocol.buildRequest(id, type, action, token, direction, BuildConfig.VERSION_NAME);
            if ("status".equals(type) && supportsRequest("control_icon")) {
                request.put("themeDay", themeStyles.isDay());
            }
            if ("prepare".equals(type)) request.put("brand", brand);
            if ("execute".equals(type) && features != null && features.contains(WatchProtocol.COMPACT_EXECUTE_ACK)) {
                request.put("compactAck", true);
            }
            recordEvent("send", req, "");
            client.send(request.toString(), () -> {
                if (pending != req) return;
                recordEvent("send_failed", req, "");
                if ("hello".equals(type)) finishHello(null);
                else if ("car_image".equals(type)) {
                    clearPending();
                    resetImageSync();
                    if (!startNextControlIconSync()) schedulePoll();
                } else if ("control_icon".equals(type)) {
                    clearPending();
                    resetIconSync();
                    if (!startNextControlIconSync()) schedulePoll();
                } else disconnect("发送失败，请检查蓝牙连接", "send_failed", req);
            });
        } catch (JSONException invalid) {
            clearPending();
        }
    }

    private void onRequestTimeout() {
        if (pending == null) return;
        PendingRequest req = pending;
        recordEvent("timeout", req, "");
        if ("hello".equals(req.type())) {
            finishHello(null);
        } else if ("car_image".equals(req.type())) {
            clearPending();
            resetImageSync();
            if (!startNextControlIconSync()) schedulePoll();
        } else if ("control_icon".equals(req.type())) {
            clearPending();
            resetIconSync();
            if (!startNextControlIconSync()) schedulePoll();
        } else {
            disconnect("手机回复超时，请检查连接", "timeout", req);
        }
    }

    private void clearPending() {
        main.removeCallbacks(requestTimeoutTask);
        pending = null;
    }

    private void finishHello(JSONObject reply) {
        clearPending();
        if (reply != null) {
            JSONArray featArray = reply.optJSONArray("features");
            List<String> list = new ArrayList<>();
            if (featArray != null) {
                for (int i = 0; i < featArray.length(); i++) list.add(featArray.optString(i));
            }
            features = list;
            compatibilityNotice = WatchProtocol.compatibilityMessage(reply, BuildConfig.VERSION_NAME);
            if (!compatibilityNotice.isEmpty()) {
                modeLabel = "版本不符";
                showToast(compatibilityNotice);
                renderAll();
                return;
            }
            receiveTheme(reply);
        }
        handshakeComplete = true;
        syncStatus();
    }

    private boolean supportsRequest(String type) {
        if ("status".equals(type) || "hello".equals(type)) return true;
        String feature = switch (type) {
            case "refresh" -> "refresh";
            case "prepare", "execute" -> "confirm";
            case "cancel" -> "cancel";
            case "control_icon" -> WatchProtocol.CONTROL_ICONS_FEATURE;
            default -> type;
        };
        return features == null || features.contains(feature);
    }

    private void handleReply(PendingRequest request, JSONObject reply) {
        String err = reply.optString("error");
        if (reply.optInt("v") == WatchProtocol.PROTOCOL && "reply".equals(reply.optString("type")) && !err.isEmpty()) {
            String message = "too_large".equals(err) ? "手机回复超过 2048 B"
                    : "internal".equals(err) ? "手机端内部错误" : "手机返回未知错误";
            recordEvent("recv", request, err);
            disconnect(message, "reply_error", request);
            return;
        }
        if ("hello".equals(request.type())) {
            if ("hello-reply".equals(reply.optString("type")) && reply.has("v") && reply.has("protocol")) {
                recordEvent("recv", request, "hello-reply");
                applyBrand(reply.optString("brand"));
                finishHello(reply);
            }
            return;
        }
        if ("execute".equals(request.type()) && WatchProtocol.isExecuteAck(reply)) {
            recordEvent("recv", request, "execute_ack:" + reply.optString("status"));
            clearPending();
            clearConfirmationState();
            available.clear();
            controlPending = true;
            controlNotice = "";
            statusOnly = true;
            String status = reply.optString("status");
            showExecutionReceipt(status, "unknown".equals(status) ? "" : reply.optString("message"));
            renderAll();
            syncStatus();
            return;
        }
        if ("car_image".equals(request.type())) {
            if (reply.optInt("v") != WatchProtocol.PROTOCOL || !"car_image_reply".equals(reply.optString("type"))) return;
            clearPending();
            String hash = reply.optString("hash");
            int index = reply.optInt("index", -1);
            int total = reply.optInt("total", 0);
            String data = reply.optString("data");
            if (!hash.equals(imageSyncHash) || index != imageSyncChunks.size() || total <= 0 || data.isEmpty()) {
                recordEvent("car_img_fail", request, "bad_chunk");
                resetImageSync();
                schedulePoll();
                return;
            }
            imageSyncChunks.add(data);
            if (imageSyncChunks.size() < total) {
                sendRequest("car_image", null, null, null);
                return;
            }
            String completedHash = imageSyncHash;
            String joined = String.join("", imageSyncChunks);
            resetImageSync();
            saveAndDisplayCarImage(completedHash, joined);
            if (!startNextControlIconSync()) schedulePoll();
            return;
        }
        if ("control_icon".equals(request.type())) {
            if (reply.optInt("v") != WatchProtocol.PROTOCOL || !"control_icon_reply".equals(reply.optString("type"))) return;
            clearPending();
            String hash = reply.optString("hash");
            int index = reply.optInt("index", -1);
            int total = reply.optInt("total", 0);
            String data = reply.optString("data");
            if (!hash.equals(iconSyncHash) || index != iconSyncChunks.size() || total <= 0 || data.isEmpty()) {
                resetIconSync();
                if (!startNextControlIconSync()) schedulePoll();
                return;
            }
            iconSyncChunks.add(data);
            if (iconSyncChunks.size() < total) {
                sendRequest("control_icon", null, null, null);
                return;
            }
            String completedHash = iconSyncHash;
            String joined = String.join("", iconSyncChunks);
            resetIconSync();
            saveAndDisplayControlIcon(completedHash, joined);
            if (!startNextControlIconSync()) schedulePoll();
            return;
        }
        if (reply.optInt("v") != WatchProtocol.PROTOCOL || !"reply".equals(reply.optString("type"))) return;
        String replyMode = reply.optString("mode");
        if (!"demo".equals(replyMode) && !"live".equals(replyMode)) return;

        recordEvent("recv", request, "");
        reconnectAttempt = 0;
        applyBrand(reply.optString("brand"));
        receiveTheme(reply);
        boolean firstReply = !linked || !mode.equals(replyMode);
        clearPending();
        linked = true;
        hasReply = true;
        mode = replyMode;
        modeLabel = "demo".equals(mode) ? "演示" : "已连接";
        phoneConfirmation = reply.optBoolean("phoneConfirmation", false);
        String previousNotice = controlNotice;
        boolean wasControlPending = controlPending;
        controlNotice = reply.optString("controlNotice");
        blockedControls = reply.optJSONObject("blocked") != null ? reply.optJSONObject("blocked") : new JSONObject();
        controlPending = reply.optBoolean("controlPending", false) || phoneConfirmation;
        if (phoneConfirmation) controlNotice = "请在手机确认" + brandName() + "操作";

        List<String> replyAvailable = WatchProtocol.knownActions(reply.optJSONArray("available"));
        List<String> replyMapped = reply.has("mapped")
                ? WatchProtocol.knownActions(reply.optJSONArray("mapped"))
                : new ArrayList<>(replyAvailable);
        replyAvailable.removeIf(action -> "LYNKCO".equals(brand) != action.startsWith("lynk_"));
        replyMapped.removeIf(action -> "LYNKCO".equals(brand) != action.startsWith("lynk_"));
        if (replyMapped.size() > WatchProtocol.HOME_ACTIONS) {
            replyMapped = new ArrayList<>(replyMapped.subList(0, WatchProtocol.HOME_ACTIONS));
        }
        boolean partialUpdate = controlPending && !firstReply && replyMapped.size() < mapped.size()
                && mapped.containsAll(replyMapped);
        if (!partialUpdate) mapped = replyMapped;
        lynkControlLabels = "LYNKCO".equals(brand) ? reply.optJSONObject("controlLabels") : null;
        controlStates = reply.optJSONObject("controlStates");

        JSONObject reportedDirections = reply.optJSONObject("controlDirections");
        controlDirectionsSupported = reportedDirections != null;
        cachedVehicle = reply.optBoolean("vehicleCached", false);
        Map<String, String> directions = cachedVehicle
                ? Map.of()
                : WatchProtocol.readControlDirections(reportedDirections, reply.optString("lockState"));

        available = new ArrayList<>();
        if (!controlPending) {
            for (String action : replyAvailable) {
                if (mapped.contains(action) && WatchProtocol.controlHint(controlStates, action).isEmpty()
                        && ("demo".equals(mode) || directions.containsKey(action)
                        || (!WatchProtocol.needsDirection(action, controlDirectionsSupported)
                        && !("lock".equals(action) && cachedVehicle)))) {
                    available.add(action);
                }
            }
        }
        if (controlPending && !firstReply && !cachedVehicle) {
            controlDirections.putAll(directions);
        } else {
            controlDirections = new LinkedHashMap<>(directions);
        }

        if (reply.has("vehicleName")) {
            String nextName = reply.optString("vehicleName").trim();
            vehicleName = nextName.isEmpty() ? DEFAULT_VEHICLE_NAME : nextName;
        }
        JSONObject vehicle = reply.optJSONObject("vehicle");
        applyVehicle(vehicle, cachedVehicle);
        if ("live".equals(mode) && vehicle != null && vehicle.optLong("updatedAt") > 0) {
            try {
                JSONObject snapshot = new JSONObject(vehicle.toString());
                snapshot.put("brand", brand);
                snapshot.put("mapped", new JSONArray(mapped));
                if (lynkControlLabels != null) snapshot.put("controlLabels", lynkControlLabels);
                snapshot.put("vehicleName", vehicleName);
                String text = snapshot.toString();
                if (!text.equals(storedSnapshot)) {
                    storedSnapshot = text;
                    prefs.edit().putString(SNAPSHOT_KEY, text).apply();
                }
            } catch (JSONException ignored) {
            }
        }

        String voiceAction = WatchProtocol.voiceAction(brand, reply.optString("voiceAction"));
        boolean allowVoice = !statusOnly;
        if ("status".equals(request.type())) statusOnly = false;
        if ("status".equals(request.type()) && allowVoice && !voiceAction.isEmpty()
                && available.contains(voiceAction) && confirmTitle.isEmpty()) {
            selectAction(voiceAction);
            return;
        }
        String status = reply.optString("status");
        String replyAction = reply.optString("action");
        String token = reply.optString("token");
        String message = reply.optString("message");
        boolean directionChanged = request.controlDirection() != null
                && !request.controlDirection().equals(directions.get(request.action()));

        if ("prepare".equals(request.type()) && "confirm".equals(status) && request.action().equals(replyAction)
                && !directionChanged && token.length() == 36 && !message.isEmpty()) {
            clearToast();
            confirmAction = request.action();
            confirmToken = token;
            confirmIconAsset = WatchProtocol.iconAsset(request.action(), directions.get(request.action()),
                    WatchProtocol.lynkControlLabel(lynkControlLabels, request.action()));
            confirmTitle = message;
            main.removeCallbacks(confirmTimeoutTask);
            main.postDelayed(confirmTimeoutTask, CONFIRM_TIMEOUT_MS);
        } else if ("execute".equals(request.type()) && "live".equals(mode)) {
            showExecutionReceipt(status, "unknown".equals(status) ? "" : message);
        } else if (!"status".equals(request.type())) {
            boolean dispatched = "execute".equals(request.type()) && "dispatched".equals(status);
            String toastMessage = message;
            if (controlPending && (phoneConfirmation || !"dispatched".equals(status))) {
                toastMessage = controlNotice.isEmpty() ? "正在同步车况" : controlNotice;
            } else if (dispatched && "live".equals(mode)) {
                toastMessage = "指令已发送";
            } else if ("confirm".equals(status) && directionChanged) {
                toastMessage = "按钮状态已变化，请重新选择";
            }
            showToast(toastMessage);
            if (dispatched) vibrateShort();
            else if ("prepare".equals(request.type()) || "execute".equals(request.type())) vibrateFailure();
        } else if ((!receiptShowing || phoneConfirmation)
                && (firstReply || !previousNotice.equals(controlNotice) || wasControlPending != controlPending)) {
            clearToast();
            if (!controlPending || phoneConfirmation) showToast(controlNotice);
        }
        renderAll();
        syncActionIconMetadata(reply.optJSONObject("actionIcons"));
        String imgHash = reply.optString("carImageHash");
        int imgChunks = reply.optInt("carImageChunks", 0);
        boolean checkImage = "status".equals(request.type()) || "refresh".equals(request.type());
        if (checkImage && supportsRequest("car_image") && !controlPending && confirmTitle.isEmpty()
                && !imgHash.isEmpty() && imgChunks > 0 && !imgHash.equals(carImageHash) && !imgHash.equals(failedCarImageHash)) {
            if (!imgHash.equals(imageSyncHash)) {
                imageSyncHash = imgHash;
                imageSyncTotal = imgChunks;
                imageSyncChunks.clear();
                recordEvent("car_img_start", request, imgHash + "x" + imgChunks);
            }
            sendRequest("car_image", null, null, null);
            return;
        }
        if (checkImage && supportsRequest("control_icon") && !controlPending && confirmTitle.isEmpty()
                && startNextControlIconSync()) {
            return;
        }
        schedulePoll();
    }

    private void onConfirmTimeout() {
        cancelConfirmation();
        showToast("确认已过期，请重新选择");
        vibrateFailure();
    }

    private void selectAction(String action) {
        if (isBusy() || !confirmTitle.isEmpty()) return;
        if (!linked) {
            showToast("请先连接手机");
            vibrateFailure();
            return;
        }
        if (controlPending) {
            showToast(controlNotice.isEmpty() ? "正在同步车况" : controlNotice);
            vibrateFailure();
            syncStatus();
            return;
        }
        if (!mapped.contains(action)) {
            showToast("请在手机核对" + brandName() + "小组件配置");
            vibrateFailure();
            return;
        }
        String slotHint = WatchProtocol.controlHint(controlStates, action);
        if (!slotHint.isEmpty()) {
            showToast(slotHint);
            return;
        }
        if ("live".equals(mode) && !available.contains(action)
                && (WatchProtocol.needsDirection(action, controlDirectionsSupported)
                || ("lock".equals(action) && cachedVehicle))) {
            String reason = blockedControls.optString(action);
            String notice = (controlDirectionsSupported || "lock".equals(action))
                    ? (!controlNotice.isEmpty() ? controlNotice : (!reason.isEmpty() ? reason : "请刷新车况，核对按钮状态"))
                    : "请更新手机端后使用此项车控";
            showToast(notice);
            vibrateFailure();
            return;
        }
        clearToast();
        if (supportsRequest("prepare")) vibrateShort();
        sendRequest("prepare", action, null, controlDirections.get(action));
    }

    private void executeConfirmation() {
        if (confirmToken.isEmpty()) return;
        String action = confirmAction;
        String token = confirmToken;
        clearConfirmationState();
        vibrateShort();
        showToast("demo".equals(mode) ? "正在执行演示…" : "正在发送命令…");
        renderScreens();
        sendRequest("execute", action, token, null);
    }

    private void cancelConfirmation() {
        String token = confirmToken;
        clearConfirmationState();
        if (!token.isEmpty()) {
            showToast("已取消操作");
            if (linked && client.isConnected() && supportsRequest("cancel")) {
                try {
                    String id = Long.toString(System.currentTimeMillis(), 36) + "_" + Long.toString(++sequence, 36);
                    String payload = WatchProtocol.buildRequest(id, "cancel", null, token, null, BuildConfig.VERSION_NAME).toString();
                    client.send(payload, () -> {});
                } catch (JSONException ignored) {
                }
            }
        }
        renderScreens();
        schedulePoll();
    }

    private void clearConfirmationState() {
        main.removeCallbacks(confirmTimeoutTask);
        confirmAction = "";
        confirmToken = "";
        confirmTitle = "";
        confirmIconAsset = "";
    }

    private void disconnect(String message, String reason, PendingRequest request) {
        boolean waitingForControl = pending != null && "execute".equals(pending.type());
        boolean controlInterrupted = !confirmTitle.isEmpty()
                || (request != null && ("prepare".equals(request.type()) || "execute".equals(request.type()) || "cancel".equals(request.type())));
        boolean replyError = "reply_error".equals(reason);
        if (!replyError) client.disconnect(null);
        recordEvent("disconnect", request, reason);
        clearPending();
        clearConfirmationState();
        resetImageSync();
        resetIconSync();
        iconSyncQueue.clear();
        handshakeComplete = false;
        features = null;
        compatibilityNotice = "";
        linked = false;
        phoneConfirmation = false;
        controlPending = false;
        cachedVehicle = false;
        blockedControls = new JSONObject();
        controlStates = null;
        statusOnly = true;
        controlDirectionsSupported = false;
        controlDirections.clear();
        modeLabel = "demo".equals(mode) ? "演示离线" : "离线";
        available.clear();
        if (controlInterrupted && !waitingForControl) vibrateFailure();
        if (waitingForControl) showExecutionReceipt("unknown", replyError ? message : "");
        else if (!receiptShowing && !reconnectScheduled && reconnectAttempt == 0) showToast(message);
        renderAll();
        if (replyError) schedulePoll();
        else scheduleReconnect();
    }

    private void applyVehicle(JSONObject vehicle, boolean cached) {
        String hero = WatchProtocol.heroDisplay(vehicle);
        if (roundScreen) {
            String unit = WatchProtocol.heroUnit(hero);
            totalHeroView.setText(WatchProtocol.heroNumber(hero));
            if (unit.isEmpty()) {
                totalHeroGhostView.setVisibility(View.GONE);
                totalHeroUnitView.setVisibility(View.GONE);
            } else {
                totalHeroGhostView.setText(unit);
                totalHeroUnitView.setText(unit);
                totalHeroGhostView.setVisibility(View.INVISIBLE);
                totalHeroUnitView.setVisibility(View.VISIBLE);
            }
        } else {
            totalHeroView.setText(hero);
            totalHeroGhostView.setVisibility(View.GONE);
            totalHeroUnitView.setVisibility(View.GONE);
        }
        WatchProtocol.EnergyBreakdown energy = WatchProtocol.energyBreakdown(vehicle);
        electricSummary.setVisibility(energy.showElectric() ? View.VISIBLE : View.GONE);
        fuelSummary.setVisibility(energy.showFuel() ? View.VISIBLE : View.GONE);
        electricText.setText(energy.electric());
        electricText.setContentDescription("纯电续航 " + energy.electric());
        fuelText.setText(energy.fuel());
        fuelText.setContentDescription("燃油续航 " + energy.fuel());
        updateBar(electricFill, WatchProtocol.validPercent(vehicle, "electricPercent"));
        updateBar(fuelFill, WatchProtocol.validPercent(vehicle, "fuelPercent"));
        String motion = vehicle == null ? "" : vehicle.optString("motion").trim();
        if ("LYNKCO".equals(brand) && (motion.isEmpty() || "状态未知".equals(motion)
                || "未知".equals(motion) || "--".equals(motion) || "—".equals(motion))) {
            motionLabel = "";
        } else {
            motionLabel = motion.isEmpty() ? "状态未知" : motion;
        }
        String updated = vehicle == null ? "" : vehicle.optString("updatedLabel");
        updatedLabel = updated.isEmpty() ? "等待车况" : (cached ? "上次 " : "") + updated;
        singleRangeSummary = WatchProtocol.singleScreenRangeSummary(vehicle);
    }

    private void updateBar(View fill, int percent) {
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) fill.getLayoutParams();
        params.weight = Math.max(0, percent);
        fill.setLayoutParams(params);
        fill.setVisibility(percent > 0 ? View.VISIBLE : View.INVISIBLE);
    }

    private void resetImageSync() {
        imageSyncHash = "";
        imageSyncTotal = 0;
        imageSyncChunks.clear();
    }

    private void resetIconSync() {
        iconSyncHash = "";
        iconSyncTotal = 0;
        iconSyncChunks.clear();
    }

    private void loadCachedControlIcons() {
        String raw = prefs.getString(CONTROL_ICON_CACHE_KEY, "");
        if (raw.isEmpty()) return;
        try {
            JSONObject saved = new JSONObject(raw);
            JSONObject filesObj = saved.optJSONObject("files");
            JSONArray orderArr = saved.optJSONArray("order");
            if (filesObj != null) {
                if (orderArr != null) {
                    for (int i = 0; i < orderArr.length(); i++) {
                        String hash = orderArr.optString(i);
                        String path = filesObj.optString(hash);
                        if (!hash.isEmpty() && !path.isEmpty() && new File(path).isFile() && !controlIconFiles.containsKey(hash)) {
                            controlIconFiles.put(hash, path);
                            controlIconOrder.add(hash);
                        }
                    }
                }
                var keys = filesObj.keys();
                while (keys.hasNext()) {
                    String hash = keys.next();
                    String path = filesObj.optString(hash);
                    if (!hash.isEmpty() && !path.isEmpty() && new File(path).isFile() && !controlIconFiles.containsKey(hash)) {
                        controlIconFiles.put(hash, path);
                        controlIconOrder.add(hash);
                    }
                }
            }
            if (brand.equals(saved.optString("brand", "GALAXY"))) {
                JSONObject actionsObj = saved.optJSONObject("actions");
                if (actionsObj != null) {
                    var actionKeys = actionsObj.keys();
                    while (actionKeys.hasNext()) {
                        String action = actionKeys.next();
                        String hash = actionsObj.optString(action);
                        if (!hash.isEmpty() && controlIconFiles.containsKey(hash)) {
                            controlIconHashes.put(action, hash);
                        }
                    }
                }
            }
        } catch (JSONException ignored) {
        }
    }

    private void persistControlIconCache() {
        try {
            JSONObject filesObj = new JSONObject();
            JSONArray orderArr = new JSONArray();
            for (String hash : controlIconOrder) {
                String path = controlIconFiles.get(hash);
                if (path != null && !path.isEmpty()) {
                    filesObj.put(hash, path);
                    orderArr.put(hash);
                }
            }
            JSONObject actionsObj = new JSONObject();
            for (Map.Entry<String, String> entry : controlIconHashes.entrySet()) {
                if (controlIconFiles.containsKey(entry.getValue())) {
                    actionsObj.put(entry.getKey(), entry.getValue());
                }
            }
            prefs.edit().putString(CONTROL_ICON_CACHE_KEY, new JSONObject()
                    .put("brand", brand)
                    .put("actions", actionsObj)
                    .put("files", filesObj)
                    .put("order", orderArr)
                    .toString()).apply();
        } catch (JSONException ignored) {
        }
    }

    private void syncActionIconMetadata(JSONObject rawIcons) {
        if (rawIcons == null) return;
        iconSyncQueue.clear();
        List<String> seenHashes = new ArrayList<>();
        boolean changed = false;
        for (String action : mapped) {
            JSONArray entry = rawIcons.optJSONArray(action);
            if (entry == null || entry.length() < 2) continue;
            String hash = entry.optString(0);
            int total = entry.optInt(1, 0);
            if (hash.isEmpty() || total <= 0) continue;
            controlIconTargets.put(action, hash);
            String cachedPath = controlIconFiles.get(hash);
            if (cachedPath != null && new File(cachedPath).isFile()) {
                if (!hash.equals(controlIconHashes.get(action))) {
                    controlIconHashes.put(action, hash);
                    changed = true;
                }
            } else if (!failedControlIconHashes.contains(hash) && !seenHashes.contains(hash)) {
                seenHashes.add(hash);
                iconSyncQueue.add(new IconSyncTarget(hash, total));
            }
        }
        if (changed) {
            persistControlIconCache();
            renderGrid();
        }
    }

    private boolean startNextControlIconSync() {
        if (!visible || !linked || !supportsRequest("control_icon") || !iconSyncHash.isEmpty()) return false;
        while (!iconSyncQueue.isEmpty()) {
            IconSyncTarget next = iconSyncQueue.remove(0);
            if (next.hash().isEmpty() || controlIconFiles.containsKey(next.hash())
                    || failedControlIconHashes.contains(next.hash())) {
                continue;
            }
            iconSyncHash = next.hash();
            iconSyncTotal = next.total();
            iconSyncChunks.clear();
            sendRequest("control_icon", null, null, null);
            return true;
        }
        return false;
    }

    private void saveAndDisplayControlIcon(String hash, String base64Text) {
        try {
            byte[] png = Base64.decode(base64Text, Base64.DEFAULT);
            Bitmap bitmap = BitmapFactory.decodeByteArray(png, 0, png.length);
            if (bitmap == null) {
                failedControlIconHashes.add(hash);
                return;
            }
            File target = new File(getFilesDir(), "cicon_" + hash + ".png");
            try (FileOutputStream out = new FileOutputStream(target)) {
                out.write(png);
            }
            controlIconBitmaps.put(hash, bitmap);
            controlIconFiles.put(hash, target.getAbsolutePath());
            controlIconOrder.remove(hash);
            controlIconOrder.add(hash);
            while (controlIconOrder.size() > MAX_CONTROL_ICON_FILES) {
                String victim = null;
                for (String candidate : controlIconOrder) {
                    if (!controlIconHashes.containsValue(candidate)) {
                        victim = candidate;
                        break;
                    }
                }
                if (victim == null) victim = controlIconOrder.get(0);
                controlIconOrder.remove(victim);
                String oldPath = controlIconFiles.remove(victim);
                controlIconBitmaps.remove(victim);
                if (oldPath != null && !oldPath.isEmpty()) {
                    //noinspection ResultOfMethodCallIgnored
                    new File(oldPath).delete();
                }
            }
            boolean changed = false;
            for (String action : mapped) {
                if (hash.equals(controlIconTargets.get(action))) {
                    controlIconHashes.put(action, hash);
                    changed = true;
                }
            }
            persistControlIconCache();
            if (changed) renderGrid();
            if (!confirmTitle.isEmpty() && hash.equals(controlIconHashes.get(confirmAction))) {
                confirmIconView.setTag(Boolean.TRUE);
                confirmIconView.clearColorFilter();
                confirmIconView.setImageBitmap(bitmap);
            }
        } catch (IllegalArgumentException | IOException failed) {
            failedControlIconHashes.add(hash);
        }
    }

    private Bitmap controlIconBitmap(String action) {
        if (action == null || action.isEmpty()) return null;
        String hash = controlIconHashes.get(action);
        if (hash == null || hash.isEmpty()) return null;
        Bitmap cached = controlIconBitmaps.get(hash);
        if (cached != null && !cached.isRecycled()) return cached;
        String path = controlIconFiles.get(hash);
        if (path == null || path.isEmpty()) return null;
        File file = new File(path);
        if (!file.isFile()) {
            controlIconFiles.remove(hash);
            controlIconOrder.remove(hash);
            controlIconHashes.remove(action);
            persistControlIconCache();
            return null;
        }
        Bitmap decoded = BitmapFactory.decodeFile(file.getAbsolutePath());
        if (decoded != null) controlIconBitmaps.put(hash, decoded);
        return decoded;
    }

    private void showCarBitmap(Bitmap bitmap) {
        if (carImageView != null) carImageView.setImageBitmap(bitmap);
        if (singleCarView != null) singleCarView.setImageBitmap(bitmap);
    }

    private void showDefaultCarImage() {
        showCarBitmap("ZEEKR".equals(brand) ? null : assetBitmap("starshine-8.png"));
    }

    private void loadCachedCarImage() {
        if (!brand.equals(prefs.getString("carImageBrand", "GALAXY"))) return;
        String hash = prefs.getString(CAR_IMAGE_HASH_KEY, "");
        String path = prefs.getString(CAR_IMAGE_FILE_KEY, "");
        if (hash.isEmpty() || path.isEmpty()) return;
        File file = new File(path);
        if (!file.isFile()) return;
        Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath());
        if (bitmap != null) {
            carImageHash = hash;
            showCarBitmap(bitmap);
        }
    }

    private void saveAndDisplayCarImage(String hash, String base64Text) {
        try {
            byte[] png = Base64.decode(base64Text, Base64.DEFAULT);
            Bitmap bitmap = BitmapFactory.decodeByteArray(png, 0, png.length);
            if (bitmap == null) {
                failedCarImageHash = hash;
                recordEvent("car_img_fail", null, "decode_null");
                return;
            }
            File target = new File(getFilesDir(), "car_" + hash + ".png");
            try (FileOutputStream out = new FileOutputStream(target)) {
                out.write(png);
            }
            String previousPath = prefs.getString(CAR_IMAGE_FILE_KEY, "");
            if (!previousPath.isEmpty() && !previousPath.equals(target.getAbsolutePath())) {
                //noinspection ResultOfMethodCallIgnored
                new File(previousPath).delete();
            }
            prefs.edit().putString("carImageBrand", brand).putString(CAR_IMAGE_HASH_KEY, hash)
                    .putString(CAR_IMAGE_FILE_KEY, target.getAbsolutePath()).apply();
            carImageHash = hash;
            showCarBitmap(bitmap);
            recordEvent("car_img_ok", null, hash);
        } catch (IllegalArgumentException | IOException failed) {
            failedCarImageHash = hash;
            recordEvent("car_img_fail", null, failed.getClass().getSimpleName());
        }
    }

    private static String normalizeBrand(String value) {
        return "LYNKCO".equals(value) || "GEELY".equals(value) || "ZEEKR".equals(value) ? value : "GALAXY";
    }

    private String brandName() {
        return switch (brand) {
            case "LYNKCO" -> "领克";
            case "GEELY" -> "吉利汽车";
            case "ZEEKR" -> "极氪";
            default -> "银河";
        };
    }

    private void applyBrand(String value) {
        String next = normalizeBrand(value);
        if (brand.equals(next)) return;
        brand = next;
        clearConfirmationState();
        failedCarImageHash = "";
        mapped = new ArrayList<>();
        available = new ArrayList<>();
        controlDirections = new LinkedHashMap<>();
        lynkControlLabels = null;
        controlStates = null;
        carImageHash = "";
        resetImageSync();
        resetIconSync();
        iconSyncQueue.clear();
        controlIconHashes.clear();
        controlIconTargets.clear();
        failedControlIconHashes.clear();
        showDefaultCarImage();
        storedSnapshot = "";
        prefs.edit().putString(SNAPSHOT_KEY, "").apply();
        vehicleName = DEFAULT_VEHICLE_NAME;
        applyVehicle(new JSONObject(), true);
    }

    private void loadSnapshot() {
        String raw = prefs.getString(SNAPSHOT_KEY, "");
        if (raw.isEmpty() || hasReply) return;
        try {
            JSONObject snapshot = new JSONObject(raw);
            if (snapshot.optLong("updatedAt") <= 0) return;
            brand = normalizeBrand(snapshot.optString("brand"));
            lynkControlLabels = "LYNKCO".equals(brand) ? snapshot.optJSONObject("controlLabels") : null;
            storedSnapshot = raw;
            String savedName = snapshot.optString("vehicleName").trim();
            if (!savedName.isEmpty()) vehicleName = savedName;
            applyVehicle(snapshot, true);
            modeLabel = "离线缓存";
            mapped = WatchProtocol.knownActions(snapshot.optJSONArray("mapped"));
            if (mapped.size() > WatchProtocol.HOME_ACTIONS) {
                mapped = new ArrayList<>(mapped.subList(0, WatchProtocol.HOME_ACTIONS));
            }
        } catch (JSONException ignored) {
        }
    }

    private void loadEvents() {
        try {
            JSONArray array = new JSONArray(prefs.getString(EVENTS_KEY, "[]"));
            for (int i = Math.max(0, array.length() - 20); i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                events.add(new EventEntry(item.getLong("ts"), item.getString("event"),
                        item.optString("id"), item.optString("type"), item.optString("detail")));
            }
        } catch (JSONException ignored) {
            events.clear();
        }
    }

    private void recordEvent(String event, PendingRequest req, String detail) {
        events.add(new EventEntry(System.currentTimeMillis(), event,
                req == null ? "" : req.id(), req == null ? "" : req.type(), detail == null ? "" : detail));
        while (events.size() > 20) events.remove(0);
        JSONArray array = new JSONArray();
        try {
            for (EventEntry entry : events) {
                array.put(new JSONObject().put("ts", entry.ts()).put("event", entry.event())
                        .put("id", entry.id()).put("type", entry.type()).put("detail", entry.detail()));
            }
            prefs.edit().putString(EVENTS_KEY, array.toString()).apply();
        } catch (JSONException ignored) {
        }
        if (settingsOpen) renderDiagnostics();
    }

    private void showToast(String message) {
        if (!visible || message == null || message.isEmpty()) return;
        receiptShowing = false;
        main.removeCallbacks(toastTask);
        toastView.setText(message);
        toastBox.setVisibility(View.VISIBLE);
        main.postDelayed(toastTask, TOAST_DURATION_MS);
    }

    private void clearToast() {
        receiptShowing = false;
        main.removeCallbacks(toastTask);
        toastBox.setVisibility(View.GONE);
    }

    private void showExecutionReceipt(String status, String message) {
        String text = switch (status) {
            case "dispatched" -> "指令已发送";
            case "unknown" -> (message.isEmpty() ? "" : message + "，") + "结果待核对，请在" + brandName() + " App 查看";
            default -> message;
        };
        showToast(text);
        receiptShowing = !text.isEmpty();
        if ("dispatched".equals(status)) vibrateShort();
        else vibrateFailure();
    }

    private void vibrateShort() {
        if (!visible || !hapticsEnabled || vibrator == null || !vibrator.hasVibrator()) return;
        vibrator.vibrate(VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE));
    }

    private void vibrateCrown(boolean pageSwitched) {
        if (!visible || !hapticsEnabled || vibrator == null || !vibrator.hasVibrator()) return;
        if (Build.VERSION.SDK_INT >= 29) {
            vibrator.vibrate(VibrationEffect.createPredefined(
                    pageSwitched ? VibrationEffect.EFFECT_CLICK : VibrationEffect.EFFECT_TICK));
        } else {
            vibrator.vibrate(VibrationEffect.createOneShot(pageSwitched ? 28 : 14, pageSwitched ? 180 : 90));
        }
    }

    private void vibrateFailure() {
        if (!visible || !hapticsEnabled || vibrator == null || !vibrator.hasVibrator()) return;
        main.removeCallbacks(secondPulseTask);
        vibrateShort();
        main.postDelayed(secondPulseTask, FAILURE_PULSE_GAP_MS);
    }

    private void renderAll() {
        renderScreens();
        renderMeta();
        renderGrid();
        renderDiagnostics();
    }

    private void renderScreens() {
        boolean showConfirm = !confirmTitle.isEmpty();
        confirmScreen.setVisibility(showConfirm ? View.VISIBLE : View.GONE);
        settingsScroll.setVisibility(!showConfirm && settingsOpen ? View.VISIBLE : View.GONE);
        homeShell.setVisibility(!showConfirm && !settingsOpen ? View.VISIBLE : View.GONE);
        if (showConfirm) {
            confirmTitleView.setText(confirmTitle);
            Bitmap syncedConfirm = controlIconBitmap(confirmAction);
            if (syncedConfirm != null) {
                confirmIconView.setTag(Boolean.TRUE);
                confirmIconView.clearColorFilter();
                confirmIconView.setImageBitmap(syncedConfirm);
            } else {
                confirmIconView.setTag(Boolean.FALSE);
                if (themeVariant == ThemeConfig.Variant.DAY) {
                    confirmIconView.setColorFilter(ThemeStyles.DAY.ink());
                } else {
                    confirmIconView.clearColorFilter();
                }
                confirmIconView.setImageBitmap(assetBitmap(confirmIconAsset));
            }
            clearToast();
        } else if (settingsOpen) {
            renderDiagnostics();
            updatePhoneTargetLabel();
            settingsScroll.requestFocus();
        } else if (useTwoPageShell()) {
            homePager.requestFocus();
        } else if (singlePage != null) {
            singlePage.requestFocus();
        }
        updateBackInvokedCallback();
    }

    private boolean useTwoPageShell() {
        return (!roundScreen && controlColumns == 1) || twoPageMode;
    }

    private void applyShellMode() {
        boolean twoPage = useTwoPageShell();
        if (homePager != null) homePager.setVisibility(twoPage ? View.VISIBLE : View.GONE);
        if (pagerDots != null) pagerDots.setVisibility(twoPage ? View.VISIBLE : View.GONE);
        if (singlePage != null) singlePage.setVisibility(twoPage ? View.GONE : View.VISIBLE);
        updateBackInvokedCallback();
    }

    private void renderMeta() {
        vehicleNameView.setText(vehicleName);
        String refreshText = phoneConfirmation ? "请在手机确认"
                : controlPending ? "同步中…"
                : pending != null && !"status".equals(pending.type()) && !"car_image".equals(pending.type())
                && !"control_icon".equals(pending.type()) ? "请稍候…"
                : updatedLabel;
        connectionLabel.setText(modeLabel);
        vehicleMotionView.setText(motionLabel);
        vehicleMotionGroup.setVisibility(motionLabel.isEmpty() ? View.GONE : View.VISIBLE);
        vehicleParkedIcon.setVisibility("已驻车".equals(motionLabel) ? View.VISIBLE : View.GONE);
        String pullText = homePager.pullRefreshText();
        controlsRefreshView.setText(pullText.isEmpty() ? refreshText : pullText);
        if (singleRefreshView != null) {
            singleRefreshView.setText(refreshText);
        }
        if (singleRangeView != null) {
            singleRangeView.setText(singleRangeSummary);
            singleRangeView.setVisibility(singleRangeSummary.isEmpty() ? View.GONE : View.VISIBLE);
        }
        boolean online = "已连接".equals(modeLabel) || "演示".equals(modeLabel);
        GradientDrawable dotBg = (GradientDrawable) connectionDot.getBackground();
        dotBg.setColor(online ? MINT : Color.rgb(120, 134, 140));
    }

    private void renderGrid() {
        if (gridContainer != null) renderControlRows(gridContainer, emptyView, false);
        if (singleGridContainer != null) renderControlRows(singleGridContainer, singleEmptyView, true);
    }

    private void renderControlRows(LinearLayout targetGrid, TextView targetEmpty, boolean compactSingleScreen) {
        targetGrid.removeAllViews();
        if (!compatibilityNotice.isEmpty()) {
            targetEmpty.setText(compatibilityNotice);
            targetEmpty.setVisibility(View.VISIBLE);
            return;
        }
        if (mapped.isEmpty()) {
            targetEmpty.setText(linked ? "请在手机配置" + brandName() + "卡片" : "连接手机后显示已映射车控");
            targetEmpty.setVisibility(View.VISIBLE);
            return;
        }
        targetEmpty.setVisibility(View.GONE);
        int count = mapped.size();
        int rowColumns = WatchProtocol.controlRowColumns(controlColumns, count);
        boolean single = rowColumns == 1;
        int orbSize;
        int iconSize;
        int label336;
        int labelRectSp;
        int tileHeight;
        if (compactSingleScreen) {
            if (count == 4) {
                orbSize = u(34, 32);
                iconSize = u(26, 24);
                label336 = 13;
                labelRectSp = 12;
                tileHeight = r(60);
            } else {
                orbSize = u(count <= 1 ? 52 : count == 2 ? 44 : 36, count <= 1 ? 46 : count == 2 ? 40 : 34);
                iconSize = u(count <= 1 ? 38 : count == 2 ? 34 : 28, count <= 1 ? 34 : count == 2 ? 30 : 25);
                label336 = count <= 1 ? 16 : count == 2 ? 14 : 13;
                labelRectSp = 13;
                tileHeight = u(count <= 2 ? 76 : 70, 68);
            }
        } else {
            orbSize = single ? r(34) : u(uiScale.roundOrb336(count), count <= 1 ? 56 : count == 2 ? 48 : count == 3 ? 36 : 38);
            iconSize = single ? r(24) : u(uiScale.roundIcon336(count), count <= 1 ? 42 : count == 2 ? 36 : count == 3 ? 26 : 28);
            label336 = uiScale.roundLabel336(count);
            labelRectSp = count <= 1 ? 16 : count == 2 ? 15 : count == 3 ? 12 : 13;
            tileHeight = single ? r(52) : count <= 1 ? r(98) : count == 2 ? r(88) : count == 3 ? r(74) : r(80);
        }
        int rowGap = compactSingleScreen ? r(6) : r(8);
        int colGap = rowColumns == 3 ? r(6) : r(8);
        for (int i = 0; i < count; i += rowColumns) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
            if (i > 0) rowParams.topMargin = rowGap;
            for (int col = 0; col < rowColumns && i + col < count; col++) {
                LinearLayout.LayoutParams tileParams = new LinearLayout.LayoutParams(0, tileHeight, 1);
                if (col > 0) tileParams.setMarginStart(colGap);
                row.addView(buildOrbTile(mapped.get(i + col), orbSize, iconSize, label336, labelRectSp,
                        single, compactSingleScreen), tileParams);
            }
            targetGrid.addView(row, rowParams);
        }
    }

    private View buildOrbTile(String action, int orbSize, int iconSize, int label336, int labelRectSp,
                              boolean single, boolean compactSingleScreen) {
        boolean isAvailable = available.contains(action);
        String direction = controlDirections.get(action);
        boolean isActive = WatchProtocol.isActionActive(action, direction, isAvailable);
        String label = action.startsWith("lynk_") ? WatchProtocol.lynkControlLabel(lynkControlLabels, action)
                : WatchProtocol.controlLabel(action, direction);
        String slotHint = WatchProtocol.controlHint(controlStates, action);
        String hint = !linked ? "待连接" : !slotHint.isEmpty() ? slotHint
                : (!isAvailable && !controlPending ? "暂不可用" : "");

        LinearLayout cell = new LinearLayout(this);
        boolean hasSingle = single;
        cell.setOrientation(hasSingle ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER);
        boolean day = themeStyles.isDay();
        int panel = isAvailable
                ? (isActive
                    ? (day ? 0xdb8ec5ff : 0x9e1862e8)
                    : (day ? 0xc2f3f8fc : 0xb81d2c43))
                : (day ? 0x7ae0eaf2 : 0x8f121c2a);
        GradientDrawable tileBackground = rounded(panel, 14);
        cell.setBackground(tileBackground);
        cell.setPadding(r(6), compactSingleScreen ? r(2) : r(4), r(6), compactSingleScreen ? r(2) : r(4));
        if (slotHint.isEmpty()) cell.setOnClickListener(view -> selectAction(action));
        else cell.setEnabled(false);

        FrameLayout orb = new FrameLayout(this);
        ImageView icon = new ImageView(this);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        Bitmap syncedIcon = controlIconBitmap(action);
        if (syncedIcon != null) {
            icon.setTag(Boolean.TRUE);
            icon.clearColorFilter();
            icon.setImageBitmap(syncedIcon);
        } else {
            icon.setTag(Boolean.FALSE);
            icon.setImageBitmap(assetBitmap(WatchProtocol.iconAsset(action, direction, label)));
            tintIcon(icon);
        }
        icon.setAlpha(isAvailable ? 1f : 0.62f);
        orb.addView(icon, new FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER));
        int compactOrb = single ? orbSize : Math.min(orbSize, iconSize + r(4));
        cell.addView(orb, new LinearLayout.LayoutParams(compactOrb, compactOrb));

        boolean isLoading = "加载中".equals(slotHint);
        TextView labelView = text(isLoading ? "加载中" : label, label336, labelRectSp, isLoading ? MUTED : INK, !isLoading);
        labelView.setSingleLine(true);
        labelView.setEllipsize(TextUtils.TruncateAt.END);
        labelView.setGravity(Gravity.CENTER);
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(-1, -2);
        if (single) {
            int width = getResources().getDisplayMetrics().widthPixels - 2 * u(34, 12) - r(24) - compactOrb;
            labelParams.width = Math.max(0, Math.min(r(94), width));
            labelParams.setMarginStart(r(8));
        } else {
            labelParams.topMargin = r(2);
        }
        labels.addView(labelView);

        if (!hint.isEmpty() && !isLoading) {
            TextView hintView = text(hint, 11, 10, MUTED, false);
            hintView.setSingleLine(true);
            hintView.setEllipsize(TextUtils.TruncateAt.END);
            hintView.setGravity(Gravity.CENTER);
            labels.addView(hintView);
        }
        cell.addView(labels, labelParams);
        return cell;
    }

    private void renderDiagnostics() {
        diagnosticsBox.removeAllViews();
        diagnosticsBox.addView(text("连接记录", 14, 13, INK, true));
        if (events.isEmpty()) {
            TextView none = text("暂无记录", 12, 11, MUTED, false);
            none.setPadding(0, r(4), 0, 0);
            diagnosticsBox.addView(none);
            return;
        }
        SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
        int start = Math.max(0, events.size() - 3);
        for (int i = events.size() - 1; i >= start; i--) {
            EventEntry entry = events.get(i);
            StringBuilder line = new StringBuilder(fmt.format(new Date(entry.ts())))
                    .append(' ').append(entry.event());
            if (!entry.type().isEmpty()) line.append(' ').append(entry.type());
            if (!entry.detail().isEmpty()) line.append(' ').append(entry.detail());
            if (!entry.id().isEmpty()) line.append('\n').append(entry.id());
            TextView row = text(line.toString(), 12, 10, MUTED, false);
            row.setPadding(0, r(4), 0, 0);
            diagnosticsBox.addView(row);
        }
    }

    private void updatePhoneTargetLabel() {
        String addr = client.preferredAddress();
        phoneTargetView.setText(addr.isEmpty() ? "自动连接已配对手机" : "指定设备：" + addr);
    }

    private void choosePhoneDevice() {
        List<BluetoothPhoneClient.PairedDevice> devices = client.bondedDevices();
        if (devices.isEmpty()) {
            showToast("暂无已配对蓝牙设备");
            return;
        }
        String[] labels = new String[devices.size() + 1];
        labels[0] = "自动选择已配对手机";
        for (int i = 0; i < devices.size(); i++) {
            BluetoothPhoneClient.PairedDevice device = devices.get(i);
            labels[i + 1] = device.name() + "\n" + device.address();
        }
        new AlertDialog.Builder(themeStyles.dialogContext(this))
                .setTitle("选择配对手机")
                .setItems(labels, (dialog, index) -> {
                    String selected = index == 0 ? "" : devices.get(index - 1).address();
                    client.setPreferredAddress(selected);
                    prefs.edit().putString(PHONE_ADDR_KEY, selected).apply();
                    updatePhoneTargetLabel();
                    client.disconnect("reconnect");
                    reconnectAttempt = 0;
                    ensureBluetoothAndConnect();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void updatePagerDots(int page) {
        updateDot(pagerDot0, page == 0);
        updateDot(pagerDot1, page == 1);
    }

    private void updateDot(View dot, boolean active) {
        int size = roundScreen ? r(active ? 6 : 4) : r(active ? 7 : 5);
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) dot.getLayoutParams();
        params.width = size;
        params.height = size;
        dot.setLayoutParams(params);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        int activeColor = roundScreen ? Color.argb(210, 158, 233, 206) : MINT;
        int idleColor = roundScreen ? Color.argb(110, 142, 166, 197) : Color.rgb(79, 99, 110);
        bg.setColor(active ? activeColor : idleColor);
        dot.setBackground(bg);
    }

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        watchRoot = root;
        if (roundScreen) {
            root.setBackgroundColor(Color.rgb(10, 16, 27));
        } else {
            root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{Color.rgb(15, 27, 46), Color.rgb(24, 44, 77), Color.rgb(8, 13, 22)}));
        }
        classicRootBackground = root.getBackground();

        homeShell = new FrameLayout(this);
        themeBackdrop = new ImageView(this);
        themeBackdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        themeBackdrop.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        homeShell.addView(themeBackdrop, new FrameLayout.LayoutParams(-1, -1));
        homePager = new VerticalPagerView(this);
        mileagePage = buildMileagePage();
        classicMileageBackground = mileagePage.getBackground();
        homePager.addView(mileagePage);
        homePager.addView(buildControlsPage());
        homePager.setOnPageChangeListener(page -> {
            updatePagerDots(page);
            updateBackInvokedCallback();
        });
        homePager.setOnCrownHapticListener(this::vibrateCrown);
        homePager.setOnPullRefresh(this::renderMeta, this::triggerRefresh,
                () -> visible && !isBusy() && !settingsOpen && !controlPending && confirmTitle.isEmpty());
        homeShell.addView(homePager, new FrameLayout.LayoutParams(-1, -1));

        pagerDots = new LinearLayout(this);
        pagerDots.setOrientation(LinearLayout.VERTICAL);
        pagerDots.setGravity(Gravity.CENTER_HORIZONTAL);
        pagerDot0 = new View(this);
        pagerDot1 = new View(this);
        pagerDots.addView(pagerDot0, new LinearLayout.LayoutParams(r(7), r(7)));
        LinearLayout.LayoutParams dot1Params = new LinearLayout.LayoutParams(r(5), r(5));
        dot1Params.topMargin = r(6);
        pagerDots.addView(pagerDot1, dot1Params);
        pagerDot0.setOnClickListener(v -> homePager.setCurrentPage(0, true));
        pagerDot1.setOnClickListener(v -> homePager.setCurrentPage(1, true));
        updatePagerDots(0);

        FrameLayout.LayoutParams dotsParams = new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.CENTER_VERTICAL);
        dotsParams.setMarginEnd(u(11, 10));
        homeShell.addView(pagerDots, dotsParams);

        singlePage = buildSinglePage();
        homeShell.addView(singlePage, new FrameLayout.LayoutParams(-1, -1));
        applyShellMode();

        int safeInset = u(50, 14);
        confirmScreen = new LinearLayout(this);
        confirmScreen.setOrientation(LinearLayout.VERTICAL);
        confirmScreen.setGravity(Gravity.CENTER);
        confirmScreen.setPadding(safeInset, r(28), safeInset, r(28));
        confirmScreen.setVisibility(View.GONE);
        LinearLayout confirmHeader = new LinearLayout(this);
        confirmHeader.setGravity(Gravity.CENTER);
        confirmIconView = new ImageView(this);
        tintIcon(confirmIconView);
        int confirmIconSize = u(26, 24);
        confirmHeader.addView(confirmIconView, new LinearLayout.LayoutParams(confirmIconSize, confirmIconSize));
        confirmTitleView = text("", 24, 20, INK, true);
        confirmTitleView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams ctParams = new LinearLayout.LayoutParams(-2, -2);
        ctParams.setMarginStart(r(8));
        confirmHeader.addView(confirmTitleView, ctParams);
        confirmScreen.addView(confirmHeader, new LinearLayout.LayoutParams(-1, -2));

        TextView confirmBtn = actionButton("确认操作", MINT, Color.rgb(16, 37, 30), true, v -> executeConfirmation());
        LinearLayout.LayoutParams cbParams = new LinearLayout.LayoutParams(-1, u(50, 44));
        cbParams.topMargin = u(18, 16);
        confirmScreen.addView(confirmBtn, cbParams);

        TextView cancelBtn = actionButton("取消", Color.rgb(38, 54, 61), INK, false, v -> cancelConfirmation());
        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(-1, u(50, 44));
        cancelParams.topMargin = u(9, 8);
        confirmScreen.addView(cancelBtn, cancelParams);

        settingsScroll = new ScrollView(this);
        settingsScroll.setFocusable(true);
        settingsScroll.setFocusableInTouchMode(true);
        settingsScroll.setVerticalScrollBarEnabled(false);
        settingsScroll.setVisibility(View.GONE);
        LinearLayout settingsContent = new LinearLayout(this);
        settingsContent.setOrientation(LinearLayout.VERTICAL);
        settingsContent.setPadding(safeInset, u(52, 34), safeInset, u(52, 34));
        settingsScroll.addView(settingsContent, new ScrollView.LayoutParams(-1, -2));

        settingsContent.addView(text("设置", 21, 18, INK, true));
        LinearLayout themeBox = new LinearLayout(this);
        themeBox.setOrientation(LinearLayout.VERTICAL);
        bindSurface(themeBox, META, 12);
        themeBox.setPadding(r(10), r(8), r(10), r(8));
        themeBox.addView(text("外观主题", 14, 13, INK, true));
        themeSetting = text("", 12, 11, MUTED, false);
        themeBox.addView(themeSetting);
        themeBox.setOnClickListener(view -> openThemeEditor());
        LinearLayout.LayoutParams themeParams = new LinearLayout.LayoutParams(-1, -2);
        themeParams.topMargin = r(10);
        settingsContent.addView(themeBox, themeParams);

        TextView iconSetting = text("桌面图标", 14, 13, INK, true);
        bindSurface(iconSetting, META, 12);
        iconSetting.setPadding(r(10), r(8), r(10), r(8));
        iconSetting.setOnClickListener(view -> LauncherIcons.show(this, new ThemeDialogs(this, themeStyles),
                "com.garage.watchcontrol.WatchActivity", R.mipmap.ic_launcher, R.mipmap.ic_launcher_mono,
                R.mipmap.ic_launcher_coral, R.mipmap.ic_launcher_cobalt));
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(-1, -2);
        iconParams.topMargin = r(10);
        settingsContent.addView(iconSetting, iconParams);

        LinearLayout hapticsBox = new LinearLayout(this);
        hapticsBox.setGravity(Gravity.CENTER_VERTICAL);
        bindSurface(hapticsBox, META, 12);
        hapticsBox.setPadding(r(10), r(8), r(10), r(8));
        hapticsBox.addView(text("车控震动", 15, 14, INK, false), new LinearLayout.LayoutParams(0, -2, 1));
        hapticsSwitch = new Switch(this);
        hapticsSwitch.setChecked(hapticsEnabled);
        themeStyles.bind(hapticsSwitch, (view, palette) -> ((Switch) view).setThumbTintList(new ColorStateList(
                new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{foreground(MINT, palette), foreground(MUTED, palette)})));
        hapticsSwitch.setOnCheckedChangeListener((buttonView, checked) -> {
            hapticsEnabled = checked;
            prefs.edit().putString(HAPTICS_KEY, checked ? "on" : "off").apply();
        });
        hapticsBox.addView(hapticsSwitch);
        LinearLayout.LayoutParams hbParams = new LinearLayout.LayoutParams(-1, -2);
        hbParams.topMargin = r(10);
        settingsContent.addView(hapticsBox, hbParams);

        if (roundScreen || controlColumns == 2) {
            LinearLayout twoPageBox = new LinearLayout(this);
            twoPageBox.setGravity(Gravity.CENTER_VERTICAL);
            bindSurface(twoPageBox, META, 12);
            twoPageBox.setPadding(r(10), r(8), r(10), r(8));
            twoPageBox.addView(text("双屏显示", 15, 14, INK, false), new LinearLayout.LayoutParams(0, -2, 1));
            twoPageSwitch = new Switch(this);
            twoPageSwitch.setChecked(twoPageMode);
            themeStyles.bind(twoPageSwitch, (view, palette) -> ((Switch) view).setThumbTintList(new ColorStateList(
                    new int[][]{{android.R.attr.state_checked}, {}},
                    new int[]{foreground(MINT, palette), foreground(MUTED, palette)})));
            twoPageSwitch.setOnCheckedChangeListener((buttonView, checked) -> {
                twoPageMode = checked;
                prefs.edit().putString(TWO_PAGE_KEY, checked ? "on" : "off").apply();
                applyShellMode();
                renderAll();
            });
            twoPageBox.addView(twoPageSwitch);
            LinearLayout.LayoutParams tpParams = new LinearLayout.LayoutParams(-1, -2);
            tpParams.topMargin = r(8);
            settingsContent.addView(twoPageBox, tpParams);
        }

        LinearLayout phoneBox = new LinearLayout(this);
        phoneBox.setOrientation(LinearLayout.VERTICAL);
        bindSurface(phoneBox, META, 12);
        phoneBox.setPadding(r(10), r(8), r(10), r(8));
        phoneBox.addView(text("配对手机", 14, 13, INK, true));
        phoneTargetView = text("自动连接已配对手机", 12, 11, MUTED, false);
        phoneBox.addView(phoneTargetView);
        phoneBox.setOnClickListener(view -> choosePhoneDevice());
        LinearLayout.LayoutParams pbParams = new LinearLayout.LayoutParams(-1, -2);
        pbParams.topMargin = r(8);
        settingsContent.addView(phoneBox, pbParams);

        diagnosticsBox = new LinearLayout(this);
        diagnosticsBox.setOrientation(LinearLayout.VERTICAL);
        bindSurface(diagnosticsBox, PANEL, 12);
        diagnosticsBox.setPadding(r(10), r(8), r(10), r(8));
        LinearLayout.LayoutParams diagParams = new LinearLayout.LayoutParams(-1, -2);
        diagParams.topMargin = r(8);
        settingsContent.addView(diagnosticsBox, diagParams);

        LinearLayout backBtn = new LinearLayout(this);
        backBtn.setOrientation(LinearLayout.HORIZONTAL);
        backBtn.setGravity(Gravity.CENTER);
        bindSurface(backBtn, META, 14);
        ImageView backIcon = new ImageView(this);
        backIcon.setImageBitmap(assetBitmap("back.png"));
        backIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        tintIcon(backIcon);
        int backIconSize = u(18, 16);
        backBtn.addView(backIcon, new LinearLayout.LayoutParams(backIconSize, backIconSize));
        TextView backLabel = text("返回", 17, 15, INK, false);
        LinearLayout.LayoutParams blParams = new LinearLayout.LayoutParams(-2, -2);
        blParams.setMarginStart(r(6));
        backBtn.addView(backLabel, blParams);
        backBtn.setOnClickListener(view -> {
            settingsOpen = false;
            renderScreens();
        });
        LinearLayout.LayoutParams backParams = new LinearLayout.LayoutParams(-1, u(44, 42));
        backParams.topMargin = r(10);
        settingsContent.addView(backBtn, backParams);

        toastBox = new FrameLayout(this);
        bindSurface(toastBox, Color.rgb(48, 67, 74), 14);
        toastBox.setPadding(r(12), r(8), r(12), r(8));
        toastBox.setVisibility(View.GONE);
        toastView = text("", 14, 13, INK, false);
        toastView.setGravity(Gravity.CENTER);
        toastBox.addView(toastView, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));

        root.addView(homeShell, new FrameLayout.LayoutParams(-1, -1));
        root.addView(settingsScroll, new FrameLayout.LayoutParams(-1, -1));
        root.addView(confirmScreen, new FrameLayout.LayoutParams(-1, -1));
        FrameLayout.LayoutParams toastParams = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER);
        toastParams.setMargins(safeInset, 0, safeInset, 0);
        root.addView(toastBox, toastParams);
        return root;
    }

    private View buildMileagePage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER_HORIZONTAL);
        int sidePadding = u(14, narrowScreen ? 9 : 14);
        page.setPadding(sidePadding, u(26, 27), sidePadding, u(8, 11));
        if (roundScreen) {
            GradientDrawable circleGradient = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{Color.rgb(19, 56, 122), Color.rgb(11, 33, 77), Color.rgb(3, 8, 18), Color.BLACK, Color.BLACK});
            page.setBackground(circleGradient);
        }

        vehicleNameView = photoText(vehicleName, 20, 14, Color.rgb(238, 244, 255), true);
        vehicleNameView.setSingleLine(true);
        vehicleNameView.setEllipsize(TextUtils.TruncateAt.END);
        vehicleNameView.setGravity(Gravity.CENTER);
        if (roundScreen) vehicleNameView.setMaxWidth(r(196));
        page.addView(vehicleNameView, new LinearLayout.LayoutParams(-2, -2));

        LinearLayout heroNumRow = new LinearLayout(this);
        heroNumRow.setOrientation(LinearLayout.HORIZONTAL);
        heroNumRow.setGravity(Gravity.CENTER_HORIZONTAL);
        heroNumRow.setBaselineAligned(true);
        totalHeroGhostView = text(" km", 17, 17, Color.TRANSPARENT, false);
        totalHeroGhostView.setSingleLine(true);
        totalHeroGhostView.setVisibility(roundScreen ? View.INVISIBLE : View.GONE);
        totalHeroView = photoText(roundScreen ? "—" : "— km", 46, 40, INK, true);
        totalHeroView.setSingleLine(true);
        totalHeroUnitView = photoText(" km", 17, 17, SUB_INK, false);
        totalHeroUnitView.setSingleLine(true);
        totalHeroUnitView.setVisibility(roundScreen ? View.VISIBLE : View.GONE);
        heroNumRow.addView(totalHeroGhostView);
        heroNumRow.addView(totalHeroView);
        heroNumRow.addView(totalHeroUnitView);
        page.addView(heroNumRow, new LinearLayout.LayoutParams(-2, -2));

        LinearLayout splitRow = new LinearLayout(this);
        splitRow.setGravity(Gravity.CENTER);
        electricText = photoText("— km", 17, narrowScreen ? 12 : 15, Color.WHITE, true);
        fuelText = photoText("— km", 17, narrowScreen ? 12 : 15, Color.WHITE, true);
        electricFill = new View(this);
        fuelFill = new View(this);
        electricSummary = buildEnergyBox("energy-electric.png", electricText, electricFill, ELECTRIC);
        fuelSummary = buildEnergyBox("energy-fuel.png", fuelText, fuelFill, FUEL);
        int energyMargin = u(5, narrowScreen ? 4 : 5);
        int availableWidth = getResources().getDisplayMetrics().widthPixels - 2 * sidePadding;
        int energyWidth = Math.min(u(96, 90), Math.max(1, availableWidth / 2 - 2 * energyMargin));
        for (View energy : List.of(electricSummary, fuelSummary)) {
            LinearLayout.LayoutParams bounds = new LinearLayout.LayoutParams(energyWidth, -2);
            bounds.setMarginStart(energyMargin);
            bounds.setMarginEnd(energyMargin);
            splitRow.addView(energy, bounds);
        }
        LinearLayout.LayoutParams energyParams = new LinearLayout.LayoutParams(-1, r(36));
        energyParams.topMargin = r(4);
        page.addView(splitRow, energyParams);

        carImageView = new ImageView(this);
        carImageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        if (roundScreen) carImageView.setPadding(r(8), 0, r(8), 0);
        LinearLayout.LayoutParams carParams = new LinearLayout.LayoutParams(-1, 0, 1);
        carParams.topMargin = u(4, 4);
        page.addView(carImageView, carParams);
        LinearLayout.LayoutParams motionParams = new LinearLayout.LayoutParams(-2, u(25, 24));
        motionParams.bottomMargin = r(6);
        page.addView(buildHomeStatus(), motionParams);
        ImageView nextPage = new ImageView(this);
        nextPage.setImageBitmap(assetBitmap("chevron-down.png"));
        nextPage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        nextPage.setPadding(r(15), r(9), r(15), r(9));
        nextPage.setContentDescription("下滑查看车控");
        nextPage.setOnClickListener(view -> homePager.setCurrentPage(1, true));
        page.addView(nextPage, new LinearLayout.LayoutParams(r(48), r(30)));

        return page;
    }

    private View buildHomeStatus() {
        LinearLayout status = new LinearLayout(this);
        status.setGravity(Gravity.CENTER);
        status.addView(buildHomeConnection(), new LinearLayout.LayoutParams(-2, -1));
        vehicleMotionGroup = buildHomeMotion();
        LinearLayout.LayoutParams motionBounds = new LinearLayout.LayoutParams(-2, -1);
        motionBounds.setMarginStart(u(8, narrowScreen ? 4 : 8));
        status.addView(vehicleMotionGroup, motionBounds);
        return status;
    }

    private View buildHomeConnection() {
        LinearLayout connection = new LinearLayout(this);
        connection.setGravity(Gravity.CENTER_VERTICAL);
        connection.setPadding(r(4), 0, r(4), 0);
        connectionDot = new View(this);
        connectionDot.setBackground(rounded(Color.rgb(120, 134, 140), 3));
        connection.addView(connectionDot, new LinearLayout.LayoutParams(r(5), r(5)));
        connectionLabel = homeStatusText("待连接");
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(-2, -2);
        labelParams.setMarginStart(r(4));
        connection.addView(connectionLabel, labelParams);
        connection.setOnClickListener(view -> triggerRefresh());
        return connection;
    }

    private View buildHomeMotion() {
        LinearLayout motion = new LinearLayout(this);
        motion.setGravity(Gravity.CENTER);
        vehicleParkedIcon = new ImageView(this);
        vehicleParkedIcon.setImageBitmap(assetBitmap("parking-status.png"));
        vehicleParkedIcon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        vehicleParkedIcon.setVisibility(View.GONE);
        LinearLayout.LayoutParams iconBounds = new LinearLayout.LayoutParams(r(17), r(17));
        iconBounds.setMarginEnd(r(5));
        motion.addView(vehicleParkedIcon, iconBounds);
        vehicleMotionView = homeStatusText("");
        vehicleMotionView.setMaxWidth(u(120, narrowScreen ? 56 : 120));
        motion.addView(vehicleMotionView);
        return motion;
    }

    private TextView homeStatusText(String label) {
        TextView view = photoText(label, 16, 15, INK, false);
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        view.setLetterSpacing(0.035f);
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.END);
        return view;
    }

    private View buildControlsPage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER_HORIZONTAL);
        page.setPadding(u(34, 12), u(14, narrowScreen ? 28 : 14), u(34, 12), u(14, narrowScreen ? 28 : 14));

        LinearLayout topPill = new LinearLayout(this);
        topPill.setOrientation(LinearLayout.HORIZONTAL);
        topPill.setGravity(Gravity.CENTER_VERTICAL);
        topPill.setPadding(u(12, 10), r(4), u(12, 10), r(4));
        ImageView refreshIcon = new ImageView(this);
        refreshIcon.setImageBitmap(assetBitmap("refresh.png"));
        topPill.addView(refreshIcon, new LinearLayout.LayoutParams(r(13), r(13)));
        controlsRefreshView = photoText("等待车况", 13, 12, SUB_INK, false);
        controlsRefreshView.setSingleLine(true);
        LinearLayout.LayoutParams crParams = new LinearLayout.LayoutParams(-2, -2);
        crParams.setMarginStart(u(5, 4));
        topPill.addView(controlsRefreshView, crParams);
        topPill.setOnClickListener(v -> triggerRefresh());
        page.addView(topPill, new LinearLayout.LayoutParams(-2, -2));

        FrameLayout centerArea = new FrameLayout(this);
        emptyView = text("连接手机后显示已映射车控", 15, 13, MUTED, false);
        emptyView.setGravity(Gravity.CENTER);
        bindSurface(emptyView, ORB_UNASSIGNED, 14);
        emptyView.setPadding(u(10, 12), u(14, 16), u(10, 12), u(14, 16));
        centerArea.addView(emptyView, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));

        gridContainer = new LinearLayout(this);
        gridContainer.setOrientation(LinearLayout.VERTICAL);
        gridContainer.setGravity(Gravity.CENTER);
        if (controlColumns == 1) {
            ScrollView controlsScroll = new ScrollView(this);
            controlsScroll.setFillViewport(true);
            controlsScroll.setVerticalScrollBarEnabled(false);
            controlsScroll.addView(gridContainer, new ScrollView.LayoutParams(-1, -2));
            centerArea.addView(controlsScroll, new FrameLayout.LayoutParams(-1, -1));
            homePager.setScrollableControls(controlsScroll);
        } else {
            centerArea.addView(gridContainer, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
        }
        page.addView(centerArea, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout settingsPill = new LinearLayout(this);
        settingsPill.setOrientation(LinearLayout.HORIZONTAL);
        settingsPill.setGravity(Gravity.CENTER);
        bindSurface(settingsPill, META, 18);
        ImageView gear = new ImageView(this);
        gear.setImageBitmap(assetBitmap("settings.png"));
        tintIcon(gear);
        int gearSize = u(16, 14);
        settingsPill.addView(gear, new LinearLayout.LayoutParams(gearSize, gearSize));
        TextView settingsLabel = text("设置", 14, 12, SUB_INK, false);
        LinearLayout.LayoutParams slParams = new LinearLayout.LayoutParams(-2, -2);
        slParams.setMarginStart(u(5, 4));
        settingsPill.addView(settingsLabel, slParams);
        settingsPill.setOnClickListener(v -> {
            if ((pending != null && !"hello".equals(pending.type())) || !confirmTitle.isEmpty()) return;
            settingsOpen = true;
            renderScreens();
        });
        page.addView(settingsPill, new LinearLayout.LayoutParams(u(112, 110), u(36, 38)));

        return page;
    }

    private LinearLayout buildSinglePage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER_HORIZONTAL);
        page.setFocusable(true);
        page.setFocusableInTouchMode(true);
        page.setPadding(u(28, 12), u(15, 12), u(28, 12), u(12, 12));
        if (roundScreen) {
            page.setBackground(classicMileageBackground);
        }

        LinearLayout topPill = new LinearLayout(this);
        topPill.setOrientation(LinearLayout.HORIZONTAL);
        topPill.setGravity(Gravity.CENTER_VERTICAL);
        topPill.setPadding(r(10), r(2), r(10), r(2));
        ImageView refreshIcon = new ImageView(this);
        refreshIcon.setImageBitmap(assetBitmap("refresh.png"));
        topPill.addView(refreshIcon, new LinearLayout.LayoutParams(r(13), r(13)));
        singleRefreshView = photoText("等待车况", 14, 13, INK, false);
        singleRefreshView.setSingleLine(true);
        singleRefreshView.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams srParams = new LinearLayout.LayoutParams(-2, -2);
        srParams.setMarginStart(r(5));
        topPill.addView(singleRefreshView, srParams);
        topPill.setOnClickListener(v -> triggerRefresh());
        page.addView(topPill, new LinearLayout.LayoutParams(-2, -2));

        singleRangeView = photoText("", 13, 12, SUB_INK, false);
        singleRangeView.setSingleLine(true);
        singleRangeView.setEllipsize(TextUtils.TruncateAt.END);
        singleRangeView.setGravity(Gravity.CENTER);
        singleRangeView.setVisibility(View.GONE);
        LinearLayout.LayoutParams rangeParams = new LinearLayout.LayoutParams(-1, -2);
        rangeParams.topMargin = r(2);
        page.addView(singleRangeView, rangeParams);

        singleCarView = new ImageView(this);
        singleCarView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams carParams = new LinearLayout.LayoutParams(-1, 0, 1);
        carParams.topMargin = r(4);
        carParams.bottomMargin = r(4);
        page.addView(singleCarView, carParams);

        FrameLayout controlsArea = new FrameLayout(this);
        singleEmptyView = text("连接手机后显示已映射车控", 14, 12, MUTED, false);
        singleEmptyView.setGravity(Gravity.CENTER);
        bindSurface(singleEmptyView, ORB_UNASSIGNED, 14);
        singleEmptyView.setPadding(r(10), r(10), r(10), r(10));
        controlsArea.addView(singleEmptyView, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
        singleGridContainer = new LinearLayout(this);
        singleGridContainer.setOrientation(LinearLayout.VERTICAL);
        singleGridContainer.setGravity(Gravity.CENTER);
        controlsArea.addView(singleGridContainer, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
        LinearLayout.LayoutParams areaParams = new LinearLayout.LayoutParams(-1, -2);
        areaParams.bottomMargin = r(6);
        page.addView(controlsArea, areaParams);

        LinearLayout singleSettings = new LinearLayout(this);
        singleSettings.setOrientation(LinearLayout.HORIZONTAL);
        singleSettings.setGravity(Gravity.CENTER);
        bindSurface(singleSettings, META, 17);
        ImageView gear = new ImageView(this);
        gear.setImageBitmap(assetBitmap("settings.png"));
        tintIcon(gear);
        int gearSize = u(15, 13);
        singleSettings.addView(gear, new LinearLayout.LayoutParams(gearSize, gearSize));
        TextView settingsLabel = text("设置", 13, 12, SUB_INK, false);
        LinearLayout.LayoutParams slParams = new LinearLayout.LayoutParams(-2, -2);
        slParams.setMarginStart(r(4));
        singleSettings.addView(settingsLabel, slParams);
        singleSettings.setOnClickListener(v -> {
            if ((pending != null && !"hello".equals(pending.type())) || !confirmTitle.isEmpty()) return;
            settingsOpen = true;
            renderScreens();
        });
        page.addView(singleSettings, new LinearLayout.LayoutParams(u(104, 102), u(32, 34)));

        return page;
    }

    private ThemeConfig readTheme(String key) {
        ThemeConfig value = ThemeConfig.parse(prefs.getString(key, ""));
        return value == null ? ThemeConfig.CLASSIC : value;
    }

    private ThemeConfig activeTheme() {
        return followPhoneTheme ? phoneTheme : localTheme;
    }

    private void receiveTheme(JSONObject reply) {
        ThemeConfig received = ThemeConfig.fromJson(reply.optJSONObject("theme"));
        if (received == null || received.equals(phoneTheme)) return;
        phoneTheme = received;
        prefs.edit().putString(PHONE_THEME_KEY, phoneTheme.encode()).apply();
        if (followPhoneTheme) themeClock.refresh();
    }

    private void openThemeEditor() {
        ThemeEditor.showOnWatch(this, themeStyles, localTheme, followPhoneTheme, (choice, follow) -> {
            localTheme = choice;
            followPhoneTheme = follow;
            prefs.edit().putString(LOCAL_THEME_KEY, choice.encode()).putBoolean(FOLLOW_THEME_KEY, follow).apply();
            themeClock.refresh();
        });
    }

    private void renderTheme(ZonedDateTime now) {
        ThemeConfig active = activeTheme();
        ThemeConfig.Variant variant = active.activeVariant(now, ThemeStyles.systemVariant(this));
        boolean hasArtwork = ThemeArtwork.show(themeBackdrop, active.wearableAsset(variant),
                variant == ThemeConfig.Variant.DAY ? 0x1fe7eff4 : 0x1a0d1626);
        ThemeConfig.Variant next = hasArtwork ? variant : null;
        ThemeStyles.Palette palette = ThemeStyles.forVariant(next);
        if (palette == null) watchRoot.setBackground(classicRootBackground);
        else watchRoot.setBackgroundColor(palette.background());
        mileagePage.setBackground(hasArtwork ? null : classicMileageBackground);
        if (singlePage != null && roundScreen) {
            singlePage.setBackground(hasArtwork ? null : classicMileageBackground);
        }
        String summary = active.summary(variant, ThemeConfig.SYSTEM_LABEL);
        themeSetting.setText((followPhoneTheme ? "跟随手机 · " : "") + summary);
        if (themeVariant != next) {
            themeVariant = next;
            themeStyles.apply(palette);
            themeStyles.applySystemBars(getWindow(), BG);
            renderGrid();
        }
    }

    private TextView photoText(String label, int roundSize, int rectSize, int classicColor, boolean bold) {
        TextView view = text(label, roundSize, rectSize, classicColor, bold);
        // Both variants use dark artwork behind the header. Text in panels uses its own palette.
        themeStyles.bind(view, (target, palette) -> ((TextView) target).setTextColor(
                palette == null ? classicColor : 0xffeff6fc));
        return view;
    }

    private LinearLayout buildEnergyBox(String iconName, TextView valueView, View fillView, int fillColor) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(buildEnergyReading(iconName, valueView));

        LinearLayout track = new LinearLayout(this);
        track.setOrientation(LinearLayout.HORIZONTAL);
        track.setWeightSum(100);
        track.setBackground(rounded(0x70d3deeb, 3));
        fillView.setBackground(rounded(fillColor, 3));
        int trackHeight = r(5);
        track.addView(fillView, new LinearLayout.LayoutParams(0, trackHeight, 0));
        LinearLayout.LayoutParams trackParams = new LinearLayout.LayoutParams(-1, trackHeight);
        trackParams.topMargin = r(4);
        box.addView(track, trackParams);
        return box;
    }

    private View buildEnergyReading(String iconName, TextView value) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(this);
        icon.setImageBitmap(assetBitmap(iconName));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        int iconSize = u(19, narrowScreen ? 15 : 19);
        row.addView(icon, new LinearLayout.LayoutParams(iconSize, iconSize));
        value.setSingleLine(true);
        value.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams valueParams = new LinearLayout.LayoutParams(0, -2, 1);
        valueParams.setMarginStart(r(4));
        row.addView(value, valueParams);
        return row;
    }

    private TextView actionButton(String label, int bg, int fg, boolean bold, View.OnClickListener listener) {
        TextView btn = text(label, 19, 16, fg, bold);
        btn.setGravity(Gravity.CENTER);
        bindSurface(btn, bg, 14);
        btn.setOnClickListener(listener);
        return btn;
    }

    private TextView text(String value, int round336, int rectSp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        if (roundScreen && uiScale != null) {
            view.setTextSize(TypedValue.COMPLEX_UNIT_PX, uiScale.textPx(round336));
            view.setIncludeFontPadding(false);
        } else {
            view.setTextSize(rectSp);
        }
        themeStyles.bind(view, (target, palette) -> ((TextView) target).setTextColor(foreground(color, palette)));
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private void bindSurface(View view, int color, int radius) {
        themeStyles.bind(view, (target, palette) -> target.setBackground(rounded(surfaceColor(color, palette), radius)));
    }

    private void tintIcon(ImageView view) {
        themeStyles.bind(view, (target, palette) -> {
            ImageView image = (ImageView) target;
            if (!Boolean.TRUE.equals(image.getTag()) && palette == ThemeStyles.DAY) image.setColorFilter(palette.ink());
            else image.clearColorFilter();
        });
    }

    private static int foreground(int color, ThemeStyles.Palette palette) {
        if (palette == null || color == Color.TRANSPARENT) return color;
        if (color == MINT) return palette.accent();
        if (color == MUTED || color == SUB_INK) return palette.muted();
        return palette.ink();
    }

    private static int surfaceColor(int color, ThemeStyles.Palette palette) {
        if (palette == null) return color;
        if (color == MINT) return palette.primary();
        if (color == PANEL || color == ORB_UNASSIGNED) return palette.surface();
        return palette.control();
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(r(radius));
        return drawable;
    }

    private int u(int round336, int rectDp) {
        return uiScale.px(round336, rectDp);
    }

    private int r(int value) {
        return uiScale.px(value, value);
    }

    private Bitmap assetBitmap(String name) {
        if (name == null || name.isEmpty()) return null;
        Bitmap cached = assetCache.get(name);
        if (cached != null) return cached;
        try (InputStream stream = getAssets().open(name)) {
            Bitmap bitmap = BitmapFactory.decodeStream(stream);
            if (bitmap != null) assetCache.put(name, bitmap);
            return bitmap;
        } catch (IOException ignored) {
            return null;
        }
    }
}
