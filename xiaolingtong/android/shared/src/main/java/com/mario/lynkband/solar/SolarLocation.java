package com.mario.lynkband.solar;

import android.annotation.SuppressLint;
import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** A recent system-cached fix or a bounded foreground request. Coordinates stay in memory. */
final class SolarLocation {
    static final long MAX_AGE_MS = TimeUnit.HOURS.toMillis(24);
    private final Context context;
    private final Handler handler;
    private final Runnable changed;
    private final List<String> providers = new ArrayList<>();
    private LocationManager manager;
    // The phone and watch have separate processes. A recreated activity in the same process
    // must resolve its resource theme from the fix that caused the recreation.
    private static volatile Location latestFix;
    private Location best;
    private LocationListener pending;
    private final Runnable timeout = this::tryNextProvider;

    SolarLocation(Context context, Handler handler, Runnable changed) {
        this.context = context;
        this.handler = handler;
        this.changed = changed;
    }

    boolean awaitingFix() { return pending != null; }

    Location recent() {
        if (best != null && remainingMillis(best) <= 0) best = null;
        return best;
    }

    static long remainingMillis(Location fix) {
        long age = TimeUnit.NANOSECONDS.toMillis(SystemClock.elapsedRealtimeNanos() - fix.getElapsedRealtimeNanos());
        return age < 0 || fix.getElapsedRealtimeNanos() <= 0 ? 0 : MAX_AGE_MS - age;
    }

    private boolean consider(Location fix) {
        if (fix == null || !SolarScene.validCoordinates(fix.getLatitude(), fix.getLongitude())
                || remainingMillis(fix) <= 0) return false;
        if (recent() == null || fix.getElapsedRealtimeNanos() >= best.getElapsedRealtimeNanos()) best = fix;
        synchronized (SolarLocation.class) {
            if (latestFix == null || best.getElapsedRealtimeNanos() >= latestFix.getElapsedRealtimeNanos())
                latestFix = best;
        }
        return true;
    }

    void loadCached() {
        manager = context.getSystemService(LocationManager.class);
        if (manager == null || !manager.isLocationEnabled()) {
            best = null;
            latestFix = null;
            return;
        }
        consider(latestFix);
        if (Build.VERSION.SDK_INT >= 31) readLastKnown(LocationManager.FUSED_PROVIDER);
        readLastKnown(LocationManager.NETWORK_PROVIDER);
        readLastKnown(LocationManager.GPS_PROVIDER);
        readLastKnown(LocationManager.PASSIVE_PROVIDER);
    }

    void refresh(boolean force) {
        stop();
        loadCached();
        if (manager == null || !manager.isLocationEnabled() || (!force && recent() != null)) return;
        // Fused and network generally answer without powering up a standalone GNSS receiver.
        if (Build.VERSION.SDK_INT >= 31) providers.add(LocationManager.FUSED_PROVIDER);
        providers.add(LocationManager.NETWORK_PROVIDER);
        providers.add(LocationManager.GPS_PROVIDER);
        tryNextProvider();
    }

    @SuppressLint("MissingPermission")
    private void readLastKnown(String provider) {
        try {
            consider(manager.getLastKnownLocation(provider));
        } catch (IllegalArgumentException | SecurityException ignored) {
            // Provider unavailable or requires fine permission on this device.
        }
    }

    @SuppressLint("MissingPermission")
    private void tryNextProvider() {
        cancelPending();
        while (!providers.isEmpty()) {
            String provider = providers.remove(0);
            try {
                if (!manager.isProviderEnabled(provider)) continue;
                pending = new LocationListener() {
                    @Override public void onLocationChanged(Location fix) {
                        if (pending != this) return;
                        if (consider(fix)) {
                            stop();
                            changed.run();
                        } else tryNextProvider();
                    }
                    @Override public void onProviderDisabled(String provider) {
                        if (pending == this) tryNextProvider();
                    }
                    @Override public void onProviderEnabled(String provider) {}
                    @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
                };
                manager.requestSingleUpdate(provider, pending, Looper.getMainLooper());
                handler.postDelayed(timeout, LocationManager.GPS_PROVIDER.equals(provider) ? 45_000 : 15_000);
                return;
            } catch (IllegalArgumentException | SecurityException ignored) {
                cancelPending();
            }
        }
    }

    private void cancelPending() {
        handler.removeCallbacks(timeout);
        if (pending != null) {
            LocationListener listener = pending;
            pending = null;
            try {
                manager.removeUpdates(listener);
            } catch (SecurityException ignored) { /* Permission revoked during the request. */ }
        }
    }

    void stop() {
        providers.clear();
        cancelPending();
    }

    void forget() {
        stop();
        best = null;
        latestFix = null;
    }
}
