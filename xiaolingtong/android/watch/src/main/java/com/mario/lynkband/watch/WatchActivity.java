package com.mario.lynkband.watch;

import android.Manifest;
import android.content.Context;
import android.content.res.Configuration;
import com.mario.lynkband.solar.SolarController;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import com.mario.lynkband.transport.PairedDevice;
import com.mario.lynkband.solar.ThemeArt;

import org.json.JSONException;
import org.json.JSONObject;

public final class WatchActivity extends Activity implements PhoneLink.Listener {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final WatchState state = new WatchState();
    private PhoneLink link;
    private WatchThemes themes;
    private int themeBurst;
    private WatchUpdate update;
    private WatchFace face;
    private SolarController solar;
    private JSONObject queuedChoice;
    private boolean queuedRefresh;
    private boolean visible;
    private boolean choosing;
    private boolean firstReply;
    private long connectionStartedAt;
    private long socketReadyAt;
    private int failedConnections;
    private final Runnable reconnect = () -> { if (visible && !link.ready()) start(false); };
    private final Runnable poll =
            () -> {
                if (!visible || !link.ready() || state.confirming()) return;
                long now = SystemClock.elapsedRealtime();
                boolean sendTheme = themeBurst < 4 && themes.ready() && state.canSyncTheme(now);
                send(sendTheme ? ThemeArt.TRANSFER : "status", null);
            };
    private final Runnable expire =
            () -> {
                state.cancel();
                state.note = "确认已过期";
                render();
                schedule();
            };

    @Override protected void attachBaseContext(Context base) {
        Configuration config = new Configuration(base.getResources().getConfiguration());
        config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | Configuration.UI_MODE_NIGHT_NO;
        super.attachBaseContext(base.createConfigurationContext(config));
    }

    @Override
    public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow()
                .getDecorView()
                .setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        link = new PhoneLink(main, this);
        update = new WatchUpdate(this, main, link, saved, this::updateLinkChanged);
        face =
                new WatchFace(
                        this,
                        new WatchFace.Controls() {
                            public void refresh() {
                                if (state.reading()) queuedRefresh = true;
                                else send("refresh", null);
                            }

                            public void prepare(JSONObject item) {
                                if (state.reading()) queuedChoice = item;
                                else send("prepare", item);
                            }

                            public void confirm() {
                                send("execute", null);
                            }

                            public void cancel() {
                                WatchActivity.this.cancel();
                            }

                            public void pair() {
                                start(true);
                            }

                            public void reconnect() {
                                start(false);
                            }

                            @Override public void receiveUpdate() {
                                if (state.controlPending()) {
                                    Toast.makeText(WatchActivity.this, "请等车控完成后再更新", Toast.LENGTH_SHORT).show();
                                    return;
                                }
                                update.receive();
                            }

                            @Override public void solarSettingsChanged() { solar.settingsChanged(); }
                        });
        themes = face.themes();
        solar = new SolarController(this, getSharedPreferences("watchTheme", MODE_PRIVATE),
                face::setLocalSolar, false);
        solar.initialize();
        setContentView(face);
        face.setFocusableInTouchMode(true);
        face.requestFocus();
    }

    @Override
    protected void onStart() {
        super.onStart();
        visible = true;
        update.start();
        solar.start();
        failedConnections = 0;
        start(false);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_SCROLL
                && event.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)
                && face != null
                && face.scrollWithCrown(event.getAxisValue(MotionEvent.AXIS_SCROLL))) return true;
        return super.dispatchGenericMotionEvent(event);
    }

    @Override
    protected void onStop() {
        visible = false;
        update.stop();
        solar.stop();
        face.clearTiles();
        main.removeCallbacks(reconnect);
        main.removeCallbacks(poll);
        main.removeCallbacks(expire);
        queuedChoice = null;
        queuedRefresh = false;
        link.close();
        themes.disconnected();
        state.offline("等待手机连接");
        state.photoResolved = false;
        super.onStop();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        update.save(state);
    }

    @Override
    protected void onActivityResult(int request, int result, android.content.Intent data) {
        super.onActivityResult(request, result, data);
        update.activityResult(request, result);
    }

    @Override
    protected void onDestroy() {
        link.destroy();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (face.back()) {
            queuedChoice = null;
            return;
        }
        super.onBackPressed();
    }

    private void cancel() {
        main.removeCallbacks(expire);
        state.cancel();
        state.note = "";
        render();
        schedule();
    }

    private void updateLinkChanged() {
        if (!visible) return;
        if (update.holdsPhoneLink()) {
            main.removeCallbacks(reconnect);
            main.removeCallbacks(poll);
            main.removeCallbacks(expire);
            queuedChoice = null;
            queuedRefresh = false;
            state.cancel();
            render();
        } else if (link.ready()) {
            schedule();
        } else {
            failedConnections = 0;
            scheduleReconnect();
        }
    }

    @SuppressLint("MissingPermission")
    private void start(boolean choose) {
        if (!visible || update.holdsPhoneLink()) return;
        choosing = choose;
        if (!PairedDevice.allowed(this)) {
            state.status = "连接手机需要附近设备权限";
            render();
            if (choose && Build.VERSION.SDK_INT >= 31)
                requestPermissions(new String[] {Manifest.permission.BLUETOOTH_CONNECT}, 71);
            return;
        }
        BluetoothAdapter adapter = PairedDevice.adapter(this);
        if (adapter == null || !adapter.isEnabled()) {
            state.status = "请先开启手表蓝牙";
            render();
            scheduleReconnect();
            return;
        }
        String address = getPreferences(MODE_PRIVATE).getString("phone", "");
        if (choose) {
            PairedDevice.choose(
                    this,
                    "选择已配对的手机",
                    device -> {
                        getPreferences(MODE_PRIVATE)
                                .edit()
                                .putString("phone", device.getAddress())
                                .apply();
                        begin(adapter, device.getAddress());
                    });
        } else if (!address.isEmpty()) begin(adapter, address);
        else {
            state.status = "请选择已配对的手机";
            render();
        }
    }

    private void begin(BluetoothAdapter adapter, String address) {
        main.removeCallbacks(reconnect);
        main.removeCallbacks(poll);
        main.removeCallbacks(expire);
        state.offline("正在连接手机");
        state.photoResolved = false;
        connectionStartedAt = SystemClock.elapsedRealtime();
        render();
        link.connect(adapter, address);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == 71 && PairedDevice.allowed(this) && visible) start(choosing);
    }

    @Override
    public void connected() {
        if (visible) {
            firstReply = true;
            socketReadyAt = SystemClock.elapsedRealtime();
            Log.i("WristLynkLink", "Bluetooth socket ready in " + (socketReadyAt - connectionStartedAt) + " ms");
            state.status = "正在同步车况";
            send("status", null);
        }
    }

    @Override
    public void lost(String reason) {
        main.removeCallbacks(expire);
        main.removeCallbacks(poll);
        queuedChoice = null;
        queuedRefresh = false;
        String previousNote = state.note;
        themes.disconnected();
        state.offline(reason);
        update.linkLost();
        if (visible) {
            if (!state.note.isEmpty() && !state.note.equals(previousNote))
                Toast.makeText(this, state.note, Toast.LENGTH_LONG).show();
            render();
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        if (!visible || update.holdsPhoneLink() || getPreferences(MODE_PRIVATE).getString("phone", "").isEmpty()) return;
        main.removeCallbacks(reconnect);
        long delay = failedConnections == 0 ? 0 : 1000L << (failedConnections - 1);
        Log.i("WristLynkLink", "Reconnecting in " + delay + " ms");
        failedConnections = Math.min(failedConnections + 1, 5);
        main.postDelayed(reconnect, delay);
    }

    @Override
    public void updateReceived(JSONObject response) { update.reply(response); }

    @Override
    public void received(JSONObject response, android.graphics.Bitmap photo) {
        if (!visible) return;
        boolean wasConfirming = state.confirming();
        String previousNote = state.note;
        boolean statusReply = state.readingStatus();
        boolean initialStatus = firstReply && statusReply;
        boolean themeReply = state.readingTheme();
        try {
            state.apply(response, photo, SystemClock.elapsedRealtime());
            if (statusReply) {
                face.mergeLandscape(response.optJSONObject("landscapeSync"));
                themes.acceptOffer(response);
                themeBurst = 0;
            } else if (themeReply) {
                themes.acceptChunk(response);
                themeBurst++;
            }
            if (firstReply) {
                firstReply = false;
                Log.i("WristLynkLink", "First status after socket ready in " + (SystemClock.elapsedRealtime() - socketReadyAt) + " ms");
            }
            failedConnections = 0;
        } catch (JSONException error) {
            link.close();
            lost("手机响应不兼容，请更新两端应用");
            return;
        }
        render();
        if (!wasConfirming && state.confirming()) {
            main.removeCallbacks(expire);
            main.postDelayed(expire, 12000);
        }
        if (!state.note.isEmpty() && !state.note.equals(previousNote))
            Toast.makeText(this, state.note, Toast.LENGTH_SHORT).show();
        if (update.holdsPhoneLink()) {
            update.linkIdle();
            return;
        }
        JSONObject choice = queuedChoice;
        queuedChoice = null;
        if (choice != null && face.controlsVisible()) {
            for (int i = 0; i < state.actions.length(); i++) {
                JSONObject item = state.actions.optJSONObject(i);
                if (item != null
                        && state.canControl(item)
                        && choice.optString("slot").equals(item.optString("slot"))
                        && choice.optString("key").equals(item.optString("key"))
                        && choice.optString("label").equals(item.optString("label"))) {
                    send("prepare", item);
                    return;
                }
            }
        }
        if (queuedRefresh && !state.confirming()) {
            queuedRefresh = false;
            send("refresh", null);
            return;
        }
        if (initialStatus && !state.confirming()) {
            send("status", null);
            return;
        }
        schedule();
    }

    private void schedule() {
        main.removeCallbacks(poll);
        if (visible && !update.holdsPhoneLink() && link.ready() && !state.confirming()) {
            long now = SystemClock.elapsedRealtime();
            long delay = state.pollDelay(now);
            if (state.canSyncTheme(now) && themes.ready()) delay = themeBurst < 4 ? 80 : 250;
            main.postDelayed(poll, delay);
        }
    }

    private void send(String type, JSONObject item) {
        if (!visible || update.holdsPhoneLink() || !link.ready()) return;
        try {
            JSONObject request = state.request(type, item, SystemClock.elapsedRealtime());
            if (request == null) {
                render();
                return;
            }
            // Show the snapshot before the phone encodes and transfers a fresh photo.
            if (firstReply && "status".equals(type)) request.remove("carPhotoId");
            main.removeCallbacks(poll);
            main.removeCallbacks(expire);
            if ("status".equals(type)) request.put("landscapeSync", face.landscapeSnapshot()).put(ThemeArt.CAPABILITY, true);
            if (ThemeArt.TRANSFER.equals(type)) themes.request(request);
            request.put(com.mario.lynkband.transport.WatchReplyWait.CAPABILITY, true);
            link.send(request);
            render();
        } catch (JSONException invalid) {
            link.close();
            lost("手机按钮数据已变化");
        }
    }

    private void render() {
        face.show(
                state,
                link.ready(),
                !getPreferences(MODE_PRIVATE).getString("phone", "").isEmpty());
    }
}
