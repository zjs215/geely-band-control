package com.mario.lynkband.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.res.Configuration;

public final class Appearance {
    private static final String[] NAMES = {"跟随系统", "浅色", "深色"};
    private static final int[] MODES = {0, Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES};

    private Appearance() {}

    public static int selected(Context context) {
        int index = context.getSharedPreferences("appearance", Context.MODE_PRIVATE).getInt("mode", 0);
        return index >= 0 && index < MODES.length ? index : 0;
    }

    public static Context wrap(Context base) {
        int night = MODES[selected(base)];
        if (night == 0) return base;
        Configuration override = new Configuration();
        override.uiMode = night;
        return base.createConfigurationContext(override);
    }

    public static boolean dark(Context context) {
        int mode = MODES[selected(context)];
        if (mode == 0) mode = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    public static String label(Context context) {
        return "外观 · " + NAMES[selected(context)];
    }

    public static void select(Activity activity, int index) {
        if (index == selected(activity)) return;
        activity.getSharedPreferences("appearance", Context.MODE_PRIVATE)
                .edit().putInt("mode", index).apply();
        activity.recreate();
    }

    public static void choose(Activity activity) {
        int current = selected(activity);
        new AlertDialog.Builder(activity).setTitle("外观")
                .setSingleChoiceItems(NAMES, current, (dialog, index) -> {
                    dialog.dismiss();
                    select(activity, index);
                }).setNegativeButton("取消", null).show();
    }
}
