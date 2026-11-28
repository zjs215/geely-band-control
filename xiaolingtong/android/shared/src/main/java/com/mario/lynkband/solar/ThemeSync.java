package com.mario.lynkband.solar;

import android.content.SharedPreferences;
import org.json.JSONException;
import org.json.JSONObject;

/** A landscape choice keeps its original timestamp and author when relayed to the other device. */
public final class ThemeSync {
    private ThemeSync() {}

    public static String scene(SharedPreferences prefs, String key) {
        Object raw = prefs.getAll().get(key);
        return raw instanceof String value && validScene(value) ? value : "SKY";
    }

    public static boolean flag(SharedPreferences prefs, String key, boolean fallback) {
        Object raw = prefs.getAll().get(key);
        return raw instanceof Boolean value ? value : fallback;
    }

    public static int minute(SharedPreferences prefs, String key, int fallback) {
        Object raw = prefs.getAll().get(key);
        return raw instanceof Integer value && value >= 0 && value < 1440 ? value : fallback;
    }

    public static String legacyThemeId(Object scene) {
        return scene instanceof String value && validScene(value)
                ? value.toLowerCase(java.util.Locale.ROOT) : null;
    }

    static String themeIdForTitle(String title) {
        return switch (title) {
            case "蓝天与星空" -> "sky";
            case "山脊营地" -> "ridge";
            case "城市天际线" -> "city";
            case "海岸灯塔" -> "coast";
            case "雪山映湖" -> "alpine";
            case "雾隐竹海" -> "bamboo";
            case "光栅棱镜" -> "prism";
            case "脉冲电路" -> "circuit";
            case "布丁的午后" -> "paw";
            case "流体折光" -> "flux";
            case "折角构成" -> "origami";
            case "磁带纪元" -> "tape";
            case "仓鼠邮差" -> "hamster";
            case "珐琅流沙" -> "enamel";
            default -> "generic";
        };
    }

    private static boolean validScene(String scene) {
        return "SKY".equals(scene) || "RIDGE".equals(scene) || "CITY".equals(scene)
                || "COAST".equals(scene) || "ALPINE".equals(scene);
    }

    private static long revision(SharedPreferences prefs, String key) {
        // On upgrade a previously selected theme outranks a fresh installation's default.
        if (!(prefs.getAll().get(key) instanceof String value) || !validScene(value)) return 0L;
        Object stored = prefs.getAll().get(key + ".revision");
        return stored instanceof Long v && v >= 0 && v <= 253402300799999L ? v : 1L;
    }

    public static synchronized void select(SharedPreferences prefs, String key, String scene, boolean phone) {
        if (!validScene(scene)) return;
        long next = Math.max(System.currentTimeMillis(), revision(prefs, key) + 1);
        save(prefs, key, scene, next, phone);
    }

    private static void save(SharedPreferences prefs, String key, String scene, long time, boolean phone) {
        prefs.edit().putString(key, scene).putLong(key + ".revision", time)
                .putBoolean(key + ".fromPhone", phone).apply();
    }

    public static synchronized JSONObject snapshot(SharedPreferences prefs, String key, boolean phone) {
        String scene = scene(prefs, key);
        try {
            return new JSONObject().put("scene", validScene(scene) ? scene : "SKY")
                    .put("revision", revision(prefs, key))
                    .put("fromPhone", flag(prefs, key + ".fromPhone", phone));
        } catch (JSONException invalidConstantKey) { throw new AssertionError(invalidConstantKey); }
    }

    public static synchronized boolean merge(SharedPreferences prefs, String key, boolean phone, JSONObject peer) {
        if (peer == null || !(peer.opt("scene") instanceof String scene) || !validScene(scene)
                || !(peer.opt("fromPhone") instanceof Boolean fromPhone)) return false;
        Object rev = peer.opt("revision");
        if (!(rev instanceof Long || rev instanceof Integer)) return false;
        long time = ((Number) rev).longValue();
        if (time < 0 || time > 253402300799999L) return false;
        long local = revision(prefs, key);
        int order = Long.compare(time, local);
        if (order == 0) order = Boolean.compare(fromPhone, flag(prefs, key + ".fromPhone", phone));
        if (order == 0) order = scene.compareTo(scene(prefs, key));
        if (order <= 0) return false;
        save(prefs, key, scene, time, fromPhone);
        return true;
    }
}
