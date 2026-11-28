package com.mario.lynkband;

import android.app.Activity;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.widget.RemoteViews;

class LynkWidget {
    static final String OWNER = "com.lynkco.customer";
    static final ComponentName MEDIUM = new ComponentName(OWNER, "com.lynkco.vehicle.widget.ListWidgetProvider");
    static final int REQUEST = 71;
    private final Context context;
    private final SharedPreferences prefs;
    private final AppWidgetManager manager;
    private final AppWidgetHost host;
    private final Runnable changed;
    private final Handler main;
    private final VehiclePhoto watchPhoto = new VehiclePhoto(VehiclePhoto.Target.WATCH);
    private final VehiclePhoto bandPhoto = new VehiclePhoto(VehiclePhoto.Target.BAND);
    private final VehiclePhoto watchPhotoHd = new VehiclePhoto(VehiclePhoto.Target.WATCH_HD);
    private final VehiclePhoto bandPhotoHd = new VehiclePhoto(VehiclePhoto.Target.BAND_HD);
    private boolean listeningRequested;
    private boolean listening;
    private AppWidgetHostView view;
    private int id;
    private long providerVersion;

    LynkWidget(Context context, Handler main, Runnable changed) {
        this.context = context;
        this.main = main;
        this.changed = changed;
        prefs = context.getSharedPreferences("card", Context.MODE_PRIVATE);
        manager = AppWidgetManager.getInstance(context);
        host = new AppWidgetHost(context, 0x4c42) {
            @Override protected AppWidgetHostView onCreateView(Context c, int widgetId, AppWidgetProviderInfo info) {
                return new AppWidgetHostView(c) {
                    @Override public void updateAppWidget(RemoteViews contents) {
                        super.updateAppWidget(contents);
                        main.removeCallbacks(changed);
                        main.post(changed);
                    }
                };
            }
            @Override public void onAppWidgetRemoved(int removedId) {
                super.onAppWidgetRemoved(removedId);
                if (removedId == id) reset();
            }
        };
        id = prefs.getInt("id", -1);
    }

    void listening(boolean active) {
        listeningRequested = active;
        if (active && id > 0 && view == null) {
            AppWidgetProviderInfo info = manager.getAppWidgetInfo(id);
            if (info != null && MEDIUM.equals(info.provider)) attach(id, info);
            else reset();
        }
        boolean needed = active && view != null;
        if (needed == listening) return;
        if (needed) {
            host.startListening();
        } else {
            try {
                host.stopListening();
            } catch (NullPointerException providerRemoved) {
                // Some Android 11 widget services dereference an already removed provider on stop.
                android.util.Log.w("WristLynkHost", "Provider disappeared while stopping widget host", providerRemoved);
            }
        }
        listening = needed;
    }

    boolean autoBind() { return prefs.getBoolean("autoBind", true); }
    boolean bound() { return id > 0; }
    boolean binding() { return prefs.getInt("pending", -1) > 0; }

    String bind(Activity activity) {
        AppWidgetProviderInfo provider = null;
        for (AppWidgetProviderInfo info : manager.getInstalledProviders()) {
            if (MEDIUM.equals(info.provider)) { provider = info; break; }
        }
        if (provider == null) return "请在本机安装并登录中国版领克 App，再回来绑定中号卡片";
        if (id > 0) return "已绑定领克中号卡片";
        int candidate = prefs.getInt("pending", -1);
        if (candidate > 0 && manager.getAppWidgetInfo(candidate) != null) return finish(true);
        if (candidate <= 0) candidate = host.allocateAppWidgetId();
        prefs.edit().putInt("pending", candidate).putBoolean("autoBind", true).apply();
        Bundle options = new Bundle();
        int width = Math.max(320, context.getResources().getConfiguration().screenWidthDp - 24);
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, width);
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, width);
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 175);
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 175);
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY, AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN);
        try {
            if (manager.bindAppWidgetIdIfAllowed(candidate, MEDIUM, options)) return finish(true);
            if (activity == null) return "打开手机伴侣以确认卡片授权";
            activity.startActivityForResult(new Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, candidate)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, MEDIUM)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_OPTIONS, options), REQUEST);
            return "等待 Android 授权";
        } catch (RuntimeException unavailable) {
            finish(false);
            return "系统未能打开小组件授权";
        }
    }

    String finish(boolean granted) {
        int candidate = prefs.getInt("pending", -1);
        if (candidate <= 0) return "绑定请求已结束";
        prefs.edit().remove("pending").putBoolean("autoBind", granted).apply();
        AppWidgetProviderInfo info = manager.getAppWidgetInfo(candidate);
        if (!granted || info == null || !MEDIUM.equals(info.provider) || id > 0) {
            host.deleteAppWidgetId(candidate);
            return "未绑定小组件";
        }
        attach(candidate, info);
        prefs.edit().putInt("id", candidate).apply();
        listening(listeningRequested);
        changed.run();
        return "已绑定领克中号卡片";
    }

    private void attach(int widgetId, AppWidgetProviderInfo info) {
        id = widgetId;
        view = host.createView(context, id, info);
        view.setPadding(0, 0, 0, 0);
        try {
            providerVersion = context.getPackageManager().getPackageInfo(OWNER, 0).getLongVersionCode();
        } catch (PackageManager.NameNotFoundException gone) { providerVersion = 0; }
    }

    void unbind() {
        prefs.edit().putBoolean("autoBind", false).apply();
        int old = id;
        reset();
        if (old > 0) host.deleteAppWidgetId(old);
    }

    private void reset() {
        id = -1;
        view = null;
        prefs.edit().remove("id").apply();
        main.post(changed);
    }

    String identity() { return id + "/" + providerVersion; }
    AppWidgetHostView view() { return view; }
    VehiclePhoto.Image vehiclePhoto(VehiclePhoto.Target target) {
        VehiclePhoto reader = switch (target) {
            case WATCH -> watchPhoto;
            case BAND -> bandPhoto;
            case WATCH_HD -> watchPhotoHd;
            case BAND_HD -> bandPhotoHd;
        };
        return reader.read(content(), identity());
    }

    View content() {
        if (view != null && (view.getWidth() <= 0 || view.isLayoutRequested()) && !view.isAttachedToWindow()) {
            float d = context.getResources().getDisplayMetrics().density;
            int width = Math.round(Math.max(320, context.getResources().getConfiguration().screenWidthDp - 24) * d);
            int height = Math.round(175 * d);
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            view.layout(0, 0, width, height);
        }
        return view;
    }
}
