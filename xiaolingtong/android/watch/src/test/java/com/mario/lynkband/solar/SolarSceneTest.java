package com.mario.lynkband.solar;

import org.junit.Test;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.Assert.*;

public class SolarSceneTest {
    private static long ms(String instant) { return Instant.parse(instant).toEpochMilli(); }

    private static void near(String expected, long actual) {
        assertTrue("Expected within six minutes of " + expected + ", got " + Instant.ofEpochMilli(actual),
                Math.abs(ms(expected) - actual) < Duration.ofMinutes(6).toMillis());
    }

    @Test public void shanghaiSolsticeUsesCivilDateAcrossUtcMidnight() {
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        SolarScene dawn = SolarScene.at(ms("2025-06-20T16:01:00Z"), zone, 31.23, 121.47);
        assertTrue(dawn.night);
        near("2025-06-20T20:51:00Z", dawn.nextCheck);
        assertTrue(dawn.info, dawn.info.startsWith("日出 04:"));
        assertTrue(dawn.info, dawn.info.contains("日落 19:"));
        assertTrue(SolarScene.at(dawn.nextCheck - 1, zone, 31.23, 121.47).night);
        SolarScene daylight = SolarScene.at(dawn.nextCheck, zone, 31.23, 121.47);
        assertFalse(daylight.night);
        near("2025-06-21T11:01:00Z", daylight.nextCheck);
        assertTrue(SolarScene.at(daylight.nextCheck, zone, 31.23, 121.47).night);
    }

    @Test public void equatorialSunriseAndSunsetNearEquinox() {
        SolarScene dawn = SolarScene.at(ms("2025-03-20T00:00:00Z"), ZoneOffset.UTC, 0, 0);
        near("2025-03-20T06:04:00Z", dawn.nextCheck);
        SolarScene noon = SolarScene.at(ms("2025-03-20T12:00:00Z"), ZoneOffset.UTC, 0, 0);
        near("2025-03-20T18:11:00Z", noon.nextCheck);
        assertFalse(noon.night);
    }

    @Test public void sunDoesNotDependOnDevicesTimezone() {
        long now = ms("2025-01-01T00:00:00Z");
        SolarScene kiribati = SolarScene.at(now, ZoneId.of("Pacific/Kiritimati"), 1.87, -157.43);
        SolarScene hawaii = SolarScene.at(now, ZoneId.of("Pacific/Honolulu"), 1.87, -157.43);
        assertFalse(kiribati.night);
        assertEquals(kiribati.night, hawaii.night);
        assertEquals(kiribati.nextCheck, hawaii.nextCheck);
        near("2025-01-01T04:34:00Z", hawaii.nextCheck);
    }

    @Test public void polarDayAndNightReevaluateOnNextLocalDate() {
        ZoneId zone = ZoneId.of("Europe/Oslo");
        SolarScene summer = SolarScene.at(ms("2025-06-21T01:00:00Z"), zone, 69.65, 18.96);
        assertFalse(summer.night);
        assertEquals("全天白昼", summer.info);
        assertEquals(ms("2025-06-21T22:00:00Z"), summer.nextCheck);
        SolarScene winter = SolarScene.at(ms("2025-12-21T11:00:00Z"), zone, 69.65, 18.96);
        assertTrue(winter.night);
        assertEquals("全天黑夜", winter.info);
        assertEquals(ms("2025-12-21T23:00:00Z"), winter.nextCheck);
        assertFalse(SolarScene.at(ms("2025-12-21T11:00:00Z"), ZoneOffset.UTC, -90, 0).night);
    }

    @Test public void invalidOrAbsentCoordinatesFallBackToSixAndEighteenLocal() {
        ZoneId zone = ZoneId.of("Asia/Kathmandu");
        long dawn = ms("2025-01-01T00:15:00Z"), dusk = ms("2025-01-01T12:15:00Z");
        assertEquals(dawn, SolarScene.at(dawn - 1, zone, Double.NaN, 0).nextCheck);
        SolarScene morning = SolarScene.at(dawn, zone, -91, 0);
        assertFalse(morning.night);
        assertEquals(dusk, morning.nextCheck);
        assertTrue(SolarScene.at(dusk, zone, 0, Double.POSITIVE_INFINITY).night);
    }

    @Test public void manualDayMaySpanMidnight() {
        long midnight = ms("2025-07-01T00:00:00Z"), sunrise = ms("2025-07-01T20:15:00Z");
        long sunset = ms("2025-07-01T03:35:00Z");
        SolarScene atMidnight = SolarScene.manual(midnight, ZoneOffset.UTC, 1215, 215);
        assertFalse(atMidnight.night);
        assertEquals(sunset, atMidnight.nextCheck);
        assertTrue(SolarScene.manual(sunset, ZoneOffset.UTC, 1215, 215).night);
        assertEquals(sunrise, SolarScene.manual(sunset, ZoneOffset.UTC, 1215, 215).nextCheck);
        assertFalse(SolarScene.manual(sunrise, ZoneOffset.UTC, 1215, 215).night);
        assertEquals("日出 20:15 · 日落 03:35", atMidnight.info);
    }

    @Test public void invalidManualTimesUseDefaults() {
        long noon = ms("2025-10-17T12:00:00Z");
        for (int[] range : new int[][] {{300, 300}, {-2, 500}, {600, 1500}}) {
            SolarScene scene = SolarScene.manual(noon, ZoneOffset.UTC, range[0], range[1]);
            assertFalse(scene.night);
            assertEquals(ms("2025-10-17T18:00:00Z"), scene.nextCheck);
        }
    }

    @Test public void daylightSavingGapCanSkipAcrossManualSunrise() {
        ZoneId ny = ZoneId.of("America/New_York");
        long jump = ms("2025-03-09T07:00:00Z"); // 01:59:59 -> 03:00
        SolarScene before = SolarScene.manual(jump - 1, ny, 150, 1080);
        assertTrue(before.night);
        assertEquals(jump, before.nextCheck);
        SolarScene after = SolarScene.manual(jump, ny, 150, 1080);
        assertFalse(after.night);
        assertEquals(ms("2025-03-09T22:00:00Z"), after.nextCheck);
    }

    @Test public void daylightSavingOverlapReentersManualNight() {
        ZoneId ny = ZoneId.of("America/New_York");
        long repeat = ms("2025-11-02T06:00:00Z"); // 01:59:59 -> 01:00
        SolarScene before = SolarScene.manual(repeat - 1, ny, 90, 1080);
        assertFalse(before.night);
        assertEquals(repeat, before.nextCheck);
        SolarScene after = SolarScene.manual(repeat, ny, 90, 1080);
        assertTrue(after.night);
        assertEquals(ms("2025-11-02T06:30:00Z"), after.nextCheck);
    }
}
