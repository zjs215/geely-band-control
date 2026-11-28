package com.mario.lynkband.solar;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.zone.ZoneOffsetTransition;
import java.time.zone.ZoneRules;
import java.util.Locale;

/** Sunrise and sunset for one civil date. Times are UTC instants, including across DST. */
final class SolarScene {
    private static final long HOUR = 3_600_000L;
    private static final long DAY = 24 * HOUR;
    private static final long J2000 = 946728000000L; // 2000-01-01 12:00 UTC
    private static final double RAD = Math.PI / 180;
    // Standard apparent sunrise: atmospheric refraction plus the solar disc's radius.
    private static final double HORIZON = Math.sin(-0.833 * RAD);
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);
    final boolean night;
    final long nextCheck;
    final String info;

    private SolarScene(boolean night, long nextCheck, String info) {
        this.night = night;
        this.nextCheck = nextCheck;
        this.info = info;
    }

    static boolean validCoordinates(double lat, double lon) {
        return lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180;
    }

    static SolarScene manual(long now, ZoneId zone, int rise, int set) {
        if (rise < 0 || rise >= 1440 || set < 0 || set >= 1440 || rise == set) {
            rise = 360;
            set = 1080;
        }
        return localSchedule(now, zone, rise, set,
                "日出 " + minuteLabel(rise) + " · 日落 " + minuteLabel(set));
    }

    private static SolarScene localSchedule(long now, ZoneId zone, int rise, int set, String info) {
        Instant instant = Instant.ofEpochMilli(now);
        LocalDateTime local = instant.atZone(zone).toLocalDateTime();
        boolean night = isNight(local, rise, set);
        ZoneRules rules = zone.getRules();
        LocalDate date = local.toLocalDate();
        long next = Long.MAX_VALUE;
        // A window may have two boundaries for the same wall time during a clock overlap.
        for (int day = 0; day <= 2; day++) {
            for (int minute : new int[] {rise, set}) {
                LocalDateTime boundary = date.plusDays(day).atStartOfDay().plusMinutes(minute);
                for (ZoneOffset offset : rules.getValidOffsets(boundary)) {
                    long time = boundary.toInstant(offset).toEpochMilli();
                    if (time > now) next = Math.min(next, time);
                }
            }
        }
        // A clock jump may cross a boundary that does not exist in the local calendar.
        ZoneOffsetTransition clockChange = rules.nextTransition(instant);
        if (clockChange != null) next = Math.min(next, clockChange.toEpochSecond() * 1000);
        return new SolarScene(night, next, info);
    }

    private static boolean isNight(LocalDateTime time, int rise, int set) {
        int minute = time.getHour() * 60 + time.getMinute();
        return rise < set ? minute < rise || minute >= set : minute < rise && minute >= set;
    }

    private static String minuteLabel(int minute) {
        return String.format(Locale.ROOT, "%02d:%02d", minute / 60, minute % 60);
    }

    static SolarScene at(long now, ZoneId zone, double lat, double lon) {
        if (!validCoordinates(lat, lon))
            return localSchedule(now, zone, 360, 1080, "未定位 · 本地 06:00–18:00");

        LocalDate date = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
        long tomorrow = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
        long start = date.atStartOfDay(zone).toInstant().toEpochMilli();
        Sun sun = new Sun(lat, lon);
        long rise = Long.MIN_VALUE, set = Long.MIN_VALUE, next = tomorrow;
        // Split at the actual daily extrema. Even a few minutes of polar daylight can lie
        // entirely between two hourly samples. Longitude determines mean solar midnight.
        long solarOffset = Math.round(lon / 360 * DAY);
        long halfDay = DAY / 2;
        long cycle = Math.floorDiv(start + solarOffset, halfDay);
        long left = start;
        for (long i = cycle; left < tomorrow; i++) {
            long center = i * halfDay - solarOffset;
            long right = center - 2 * HOUR >= tomorrow ? tomorrow
                    : Math.min(tomorrow, sun.extremum(center, (i & 1L) != 0));
            if (right <= left) continue;
            boolean rising = !sun.day(left);
            if (sun.day(right) == rising) {
                long crossing = sun.crossing(left, right, rising);
                if (crossing < tomorrow) {
                    if (rising) rise = crossing;
                    else set = crossing;
                    if (crossing > now) next = Math.min(next, crossing);
                }
            }
            left = right;
        }
        boolean night = !sun.day(now);
        String info;
        if (rise == Long.MIN_VALUE && set == Long.MIN_VALUE) info = night ? "全天黑夜" : "全天白昼";
        else if (rise == Long.MIN_VALUE) info = "日落 " + clock(set, zone);
        else if (set == Long.MIN_VALUE) info = "日出 " + clock(rise, zone);
        else info = "日出 " + clock(rise, zone) + " · 日落 " + clock(set, zone);
        return new SolarScene(night, next, info);
    }

    private static String clock(long time, ZoneId zone) {
        return CLOCK.format(Instant.ofEpochMilli(time + 30_000L).atZone(zone));
    }

    private static final class Sun {
        final double sinLat, cosLat, longitude;

        Sun(double lat, double lon) {
            sinLat = Math.sin(lat * RAD);
            cosLat = Math.cos(lat * RAD);
            longitude = lon;
        }

        // Low-order solar ephemeris: ecliptic longitude -> equatorial position -> altitude.
        double sineAltitude(long time) {
            double days = (time - J2000) / (double) DAY;
            double anomaly = (357.528 + 0.9856003 * days) * RAD;
            double ecliptic = (280.460 + 0.9856474 * days + 1.915 * Math.sin(anomaly)
                    + 0.020 * Math.sin(2 * anomaly)) * RAD;
            double obliquity = (23.439 - 0.0000004 * days) * RAD;
            double sinDec = Math.sin(obliquity) * Math.sin(ecliptic);
            double ra = Math.atan2(Math.cos(obliquity) * Math.sin(ecliptic), Math.cos(ecliptic));
            double hourAngle = (280.46061837 + 360.98564736629 * days + longitude) * RAD - ra;
            return sinLat * sinDec + cosLat * Math.sqrt(1 - sinDec * sinDec) * Math.cos(hourAngle);
        }

        boolean day(long time) {
            return sineAltitude(time) >= HORIZON;
        }

        long extremum(long center, boolean maximum) {
            long lo = center - 2 * HOUR, hi = center + 2 * HOUR;
            while (hi - lo > 1000) {
                long step = (hi - lo) / 3;
                if ((sineAltitude(lo + step) < sineAltitude(hi - step)) == maximum) lo += step;
                else hi -= step;
            }
            return (lo + hi) / 2;
        }

        long crossing(long lo, long hi, boolean rising) {
            while (hi - lo > 1) {
                long mid = lo + (hi - lo) / 2;
                if (day(mid) == rising) hi = mid;
                else lo = mid;
            }
            return hi;
        }
    }
}
