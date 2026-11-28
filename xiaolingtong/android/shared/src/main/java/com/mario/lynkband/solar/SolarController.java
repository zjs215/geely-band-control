package com.mario.lynkband.solar;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

import java.time.ZoneId;

/** Owns the scene timer, time-change receiver and optional location while the activity is visible. */
public final class SolarController {
    public interface Listener {
        void sceneChanged(boolean night, String info);
    }

    public static final int REQUEST_LOCATION = 72;
    private static final String COARSE = Manifest.permission.ACCESS_COARSE_LOCATION;
    private final Activity activity;
    private final Listener listener;
    private final SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SolarLocation location;
    private final boolean allowLocation;
    private boolean started;
    private SolarScene last;
    private final Runnable recheck = this::recompute;
    private final BroadcastReceiver clock = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (LocationManager.MODE_CHANGED_ACTION.equals(intent.getAction())) refresh(false, false);
            else recompute();
        }
    };

    public SolarController(Activity activity, SharedPreferences prefs, Listener listener) {
        this(activity, prefs, listener, true);
    }

    public SolarController(Activity activity, SharedPreferences prefs, Listener listener, boolean allowLocation) {
        this.activity = activity;
        this.prefs = prefs;
        this.listener = listener;
        this.allowLocation = allowLocation;
        location = allowLocation ? new SolarLocation(activity, handler, () -> apply(false)) : null;
    }

    public record Snapshot(boolean night, String info) {}

    /** Resolve resource configuration before inflation, using only an existing fix if permitted. */
    public static boolean initialNight(Context context, SharedPreferences prefs) {
        return current(context, prefs).night();
    }

    public static Snapshot current(Context context, SharedPreferences prefs) {
        Location fix = null;
        if (!ThemeSync.flag(prefs, "manual", false) && allowed(context)) {
            SolarLocation cached = new SolarLocation(context, new Handler(Looper.getMainLooper()), () -> {});
            cached.loadCached();
            fix = cached.recent();
        }
        SolarScene scene = calculate(prefs, System.currentTimeMillis(), fix, false);
        return new Snapshot(scene.night, scene.info);
    }

    public void initialize() {
        if (allowLocation && !ThemeSync.flag(prefs, "manual", false) && allowed(activity)) location.loadCached();
        apply(true);
    }

    public void start() {
        if (started) return;
        started = true;
        IntentFilter filter = new IntentFilter(Intent.ACTION_TIME_CHANGED);
        filter.addAction(Intent.ACTION_DATE_CHANGED);
        filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        filter.addAction(Intent.ACTION_SCREEN_ON); // Handler timers pause in deep sleep.
        if (allowLocation) filter.addAction(LocationManager.MODE_CHANGED_ACTION);
        if (Build.VERSION.SDK_INT >= 33) activity.registerReceiver(clock, filter, Context.RECEIVER_NOT_EXPORTED);
        else activity.registerReceiver(clock, filter);
        refresh(true, false);
    }

    public void stop() {
        if (!started) return;
        started = false;
        activity.unregisterReceiver(clock);
        handler.removeCallbacks(recheck);
        if (allowLocation) location.stop();
    }

    private static boolean allowed(Context context) {
        return context.checkSelfPermission(COARSE) == PackageManager.PERMISSION_GRANTED;
    }

    public void settingsChanged() { refresh(false, false); }

    public void locate() {
        if (!allowLocation || !started || ThemeSync.flag(prefs, "manual", false)) return;
        if (allowed(activity)) refresh(false, true);
        else if (ThemeSync.flag(prefs, "locationAsked", false)
                && !activity.shouldShowRequestPermissionRationale(COARSE)) {
            try {
                activity.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", activity.getPackageName(), null)));
            } catch (ActivityNotFoundException unavailable) {
                toast("可在系统设置中允许大致位置");
            }
        } else {
            prefs.edit().putBoolean("locationAsked", true).apply();
            activity.requestPermissions(new String[] {COARSE}, REQUEST_LOCATION);
        }
    }

    public void permissionResult() {
        if (!allowLocation) return;
        if (started && !allowed(activity)) toast("未获定位权限，按本地 06:00–18:00 切换");
        refresh(false, false);
    }

    private void toast(String message) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
    }

    private void recompute() {
        if (!started) return;
        if (allowLocation && !ThemeSync.flag(prefs, "manual", false) && allowed(activity) && location.awaitingFix()) apply(false);
        else refresh(false, false);
    }

    private void refresh(boolean initialize, boolean forceFix) {
        if (!started) return;
        if (allowLocation) {
            if (ThemeSync.flag(prefs, "manual", false) || !allowed(activity)) location.forget();
            else location.refresh(forceFix);
        }
        apply(initialize);
    }

    private static SolarScene calculate(SharedPreferences prefs, long now, Location fix, boolean forceManual) {
        ZoneId zone = ZoneId.systemDefault();
        if (forceManual || ThemeSync.flag(prefs, "manual", false))
            return SolarScene.manual(now, zone, ThemeSync.minute(prefs, "riseMinute", 360), ThemeSync.minute(prefs, "setMinute", 1080));
        return SolarScene.at(now, zone, fix == null ? Double.NaN : fix.getLatitude(),
                fix == null ? Double.NaN : fix.getLongitude());
    }

    private void apply(boolean initialize) {
        long now = System.currentTimeMillis();
        Location fix = allowLocation && !ThemeSync.flag(prefs, "manual", false) && allowed(activity) ? location.recent() : null;
        SolarScene scene = calculate(prefs, now, fix, !allowLocation);
        boolean changed = last == null || last.night != scene.night || !last.info.equals(scene.info);
        last = scene;
        if (initialize || changed) listener.sceneChanged(scene.night, scene.info);
        handler.removeCallbacks(recheck);
        if (started) {
            long next = scene.nextCheck;
            if (fix != null) next = Math.min(next, now + SolarLocation.remainingMillis(fix));
            handler.postDelayed(recheck, Math.max(1, next - System.currentTimeMillis()));
        }
    }
}
