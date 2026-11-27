package com.otrong.carcontrol.appearance;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class ThemeClock {
    private final Context context;
    private final List<Supplier<ThemeConfig>> settings;
    private final Consumer<ZonedDateTime> render;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = this::refresh;
    private final BroadcastReceiver timeChange = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            refresh();
        }
    };
    private boolean started;

    public ThemeClock(Context context, Supplier<ThemeConfig> settings, Consumer<ZonedDateTime> render) {
        this(context, List.of(settings), render);
    }

    public ThemeClock(Context context, List<Supplier<ThemeConfig>> settings, Consumer<ZonedDateTime> render) {
        this.context = context;
        this.settings = List.copyOf(settings);
        this.render = render;
    }

    public void start() {
        if (!started) {
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIME_CHANGED);
            filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(timeChange, filter, Context.RECEIVER_NOT_EXPORTED);
            else context.registerReceiver(timeChange, filter);
            started = true;
        }
        refresh();
    }

    public void stop() {
        if (!started) return;
        started = false;
        handler.removeCallbacks(tick);
        context.unregisterReceiver(timeChange);
    }

    public void refresh() {
        if (!started) return;
        handler.removeCallbacks(tick);
        ZonedDateTime now = ZonedDateTime.now();
        render.accept(now);
        long delay = settings.stream().mapToLong(theme -> theme.get().nextChangeMillis(now))
                .filter(next -> next > 0).min().orElse(0);
        if (delay > 0) handler.postDelayed(tick, delay);
    }
}
