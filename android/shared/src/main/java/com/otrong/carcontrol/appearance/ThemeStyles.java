package com.otrong.carcontrol.appearance;

import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.Window;
import android.view.WindowInsetsController;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BiConsumer;

public final class ThemeStyles {
    public record Palette(int background, int surface, int control, int primary, int ink, int muted, int accent, int border) {}

    public static final Palette DAY = new Palette(0xffe7eff4, 0xeef3f8fc, 0xffdbe8f0, 0xffc5e3dc,
            0xff152c3c, 0xff415b6c, 0xff126052, 0xffa7becc);
    public static final Palette NIGHT = new Palette(0xff0d1626, 0xe9142235, 0xff24364b, 0xff214744,
            0xfff2f6fc, 0xffa4b9ce, 0xffa3e5d0, 0xff405976);

    private final Map<View, BiConsumer<View, Palette>> bindings = new WeakHashMap<>();
    private Palette palette;

    public static Palette forVariant(ThemeConfig.Variant variant) {
        if (variant == null) return null;
        return variant == ThemeConfig.Variant.DAY ? DAY : NIGHT;
    }

    public static ThemeConfig.Variant systemVariant(Context context) {
        int nightMode = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return nightMode == Configuration.UI_MODE_NIGHT_YES ? ThemeConfig.Variant.NIGHT : ThemeConfig.Variant.DAY;
    }

    public boolean isDay() { return palette == DAY; }

    Palette currentPalette() { return palette; }

    public Context dialogContext(Context context) {
        return isDay() ? new ContextThemeWrapper(context, android.R.style.Theme_Material_Light_Dialog_Alert) : context;
    }

    public void applySystemBars(Window window, int classic) {
        int background = palette == null ? classic : palette.background();
        applySystemBars(window, background, isDay());
    }

    @SuppressWarnings("deprecation")
    public static void applySystemBars(Window window, int background, boolean lightSurface) {
        window.setStatusBarColor(background);
        window.setNavigationBarColor(background);
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = window.getInsetsController();
            int bars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            if (controller != null) controller.setSystemBarsAppearance(lightSurface ? bars : 0, bars);
        } else {
            View decor = window.getDecorView();
            int bars = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            decor.setSystemUiVisibility((decor.getSystemUiVisibility() & ~bars) | (lightSurface ? bars : 0));
        }
    }

    public void bind(View view, BiConsumer<View, Palette> update) {
        bindings.merge(view, update, BiConsumer::andThen);
        update.accept(view, palette);
    }

    public void apply(Palette next) {
        if (next == palette) return;
        palette = next;
        bindings.forEach((view, update) -> update.accept(view, next));
    }
}
