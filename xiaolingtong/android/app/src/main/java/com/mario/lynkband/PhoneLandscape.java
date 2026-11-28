package com.mario.lynkband;

import android.content.Context;
import android.content.SharedPreferences;

import com.mario.lynkband.solar.SolarController;
import com.mario.lynkband.solar.ThemeSync;

/** The paired watch shares the landscape choice. Solar settings are local to the phone. */
enum PhoneLandscape {
    SKY("蓝天与星空", R.drawable.phone_day_landscape, R.drawable.watch_stars),
    RIDGE("山脊营地", R.drawable.phone_day_landscape, R.drawable.watch_stars),
    CITY("城市天际线", R.drawable.phone_day_landscape, R.drawable.watch_stars),
    COAST("海岸灯塔", R.drawable.phone_day_landscape, R.drawable.watch_stars),
    ALPINE("雪山映湖", R.drawable.phone_day_landscape, R.drawable.watch_stars);

    static final String KEY = "landscape";
    final String title;
    private final int dayImage, nightImage;

    PhoneLandscape(String title, int dayImage, int nightImage) {
        this.title = title;
        this.dayImage = dayImage;
        this.nightImage = nightImage;
    }

    int image(boolean night) { return night ? nightImage : dayImage; }

    int baseColor(boolean night) {
        return switch (this) {
            case SKY -> night ? 0xff162331 : 0xffc6dfef;
            case RIDGE -> night ? 0xff192a2c : 0xffcedecb;
            case CITY -> night ? 0xff222438 : 0xffd6dce9;
            case COAST -> night ? 0xff152934 : 0xffcce4e5;
            case ALPINE -> night ? 0xff19283b : 0xffd1e3e9;
        };
    }

    static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("phoneTheme", Context.MODE_PRIVATE);
    }

    static PhoneLandscape selected(SharedPreferences prefs) {
        String name = ThemeSync.scene(prefs, KEY);
        for (PhoneLandscape scene : values()) if (scene.name().equals(name)) return scene;
        return SKY;
    }

    String themeId() { return name().toLowerCase(java.util.Locale.ROOT); }

    static PhoneLandscape compatibleWith(ThemeLibrary.Entry entry) {
        if (entry != null) {
            for (PhoneLandscape scene : values()) {
                if (scene.themeId().equals(entry.id()) && scene.title.equals(entry.title())) return scene;
            }
        }
        return SKY;
    }

    void select(SharedPreferences prefs) {
        ThemeSync.select(prefs, KEY, name(), true);
    }

    static boolean night(Context context) {
        return SolarController.initialNight(context, preferences(context));
    }
}
