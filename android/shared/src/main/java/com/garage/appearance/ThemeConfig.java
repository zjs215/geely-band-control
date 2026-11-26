package com.garage.appearance;

import org.json.JSONException;
import org.json.JSONObject;

import java.time.ZonedDateTime;
import java.util.Locale;

public record ThemeConfig(Scene scene, Mode mode, Variant variant, int dayStartMinute, int nightStartMinute) {
    public static final String FEATURE = "theme_v1";
    public static final String SYSTEM_LABEL = "跟随系统";
    public static final String PHONE_SYSTEM_LABEL = "跟随手机系统";
    private static final int MINUTES_PER_DAY = 1440;
    public static final ThemeConfig CLASSIC = new ThemeConfig(Scene.CLASSIC, Mode.SCHEDULE, Variant.DAY, 360, 1080);

    public enum Scene {
        CLASSIC("经典"), SKY("蓝天与星空"), RIDGE("山脊营地"), CITY("城市天际线"),
        COAST("海岸灯塔"), ALPINE("雪山映湖");

        public final String title;

        Scene(String title) {
            this.title = title;
        }
    }

    public enum Mode { SCHEDULE, FIXED, SYSTEM }

    public enum Variant {
        DAY("日景"), NIGHT("夜景");

        public final String title;

        Variant(String title) {
            this.title = title;
        }
    }

    public ThemeConfig {
        if (scene == null || mode == null || variant == null || !validMinute(dayStartMinute)
                || !validMinute(nightStartMinute) || (mode == Mode.SCHEDULE && dayStartMinute == nightStartMinute)) {
            throw new IllegalArgumentException("Invalid theme settings");
        }
    }

    public static ThemeConfig parse(String value) {
        try {
            return fromJson(new JSONObject(value));
        } catch (JSONException | NullPointerException ignored) {
            return null;
        }
    }

    public static ThemeConfig fromJson(JSONObject input) {
        if (input == null) return null;
        try {
            return new ThemeConfig(
                    named(Scene.values(), input.getString("themeId")),
                    named(Mode.values(), input.getString("mode")),
                    named(Variant.values(), input.getString("variant")),
                    readMinute(input, "dayStartMinute"), readMinute(input, "nightStartMinute"));
        } catch (JSONException | IllegalArgumentException ignored) {
            return null;
        }
    }

    public JSONObject json() throws JSONException {
        return new JSONObject().put("themeId", id(scene)).put("mode", id(mode)).put("variant", id(variant))
                .put("dayStartMinute", dayStartMinute).put("nightStartMinute", nightStartMinute);
    }

    public String encode() {
        try {
            return json().toString();
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public Variant activeVariant(int localMinute, Variant systemVariant) {
        return switch (mode) {
            case SCHEDULE -> scheduledVariant(localMinute);
            case FIXED -> variant;
            case SYSTEM -> systemVariant;
        };
    }

    private Variant scheduledVariant(int localMinute) {
        int sinceDay = Math.floorMod(localMinute - dayStartMinute, MINUTES_PER_DAY);
        int dayLength = Math.floorMod(nightStartMinute - dayStartMinute, MINUTES_PER_DAY);
        return sinceDay < dayLength ? Variant.DAY : Variant.NIGHT;
    }

    public Variant activeVariant(ZonedDateTime now, Variant systemVariant) {
        return activeVariant(minuteOfDay(now), systemVariant);
    }

    public ThemeConfig forWearableSync(Variant systemVariant) {
        if (mode != Mode.SYSTEM) return this;
        // The wearable protocol already supports fixed variants on every client.
        return new ThemeConfig(scene, Mode.FIXED, systemVariant, dayStartMinute, nightStartMinute);
    }

    public long nextChangeMillis(ZonedDateTime now) {
        if (scene == Scene.CLASSIC || mode != Mode.SCHEDULE) return 0;
        int next = scheduledVariant(minuteOfDay(now)) == Variant.DAY ? nightStartMinute : dayStartMinute;
        long until = Math.floorMod(next - minuteOfDay(now), MINUTES_PER_DAY) * 60_000L
                - now.getSecond() * 1000L - now.getNano() / 1_000_000L;
        var transition = now.getZone().getRules().nextTransition(now.toInstant());
        if (transition != null) until = Math.min(until, transition.getInstant().toEpochMilli() - now.toInstant().toEpochMilli());
        return Math.max(1, until);
    }

    public String asset(Variant active) {
        return scene == Scene.CLASSIC ? "" : "themes/" + id(scene) + "-" + id(active) + ".webp";
    }

    public String summary(Variant active, String systemLabel) {
        if (scene == Scene.CLASSIC) return scene.title;
        String source = switch (mode) {
            case SCHEDULE -> "定时";
            case FIXED -> "固定";
            case SYSTEM -> systemLabel;
        };
        return scene.title + " · " + active.title + " · " + source;
    }

    public static String timeLabel(int minute) {
        return String.format(Locale.ROOT, "%02d:%02d", minute / 60, minute % 60);
    }

    private static int minuteOfDay(ZonedDateTime time) {
        return time.getHour() * 60 + time.getMinute();
    }

    private static int readMinute(JSONObject input, String key) throws JSONException {
        Object value = input.get(key);
        if (value instanceof Number number && number.doubleValue() == number.intValue()
                && validMinute(number.intValue())) return number.intValue();
        throw new JSONException(key);
    }

    private static boolean validMinute(int minute) {
        return minute >= 0 && minute < MINUTES_PER_DAY;
    }

    private static String id(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    private static <E extends Enum<E>> E named(E[] values, String key) {
        for (E value : values) if (id(value).equals(key)) return value;
        return null;
    }
}
