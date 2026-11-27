package com.garage.bandcontrol;

import org.junit.Test;
import org.json.JSONObject;

import java.time.Instant;
import java.time.ZoneId;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public final class WidgetReadingsTest {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final long NOW = Instant.parse("2026-11-24T01:16:00Z").toEpochMilli();

    @Test public void zeekrRequiresAProviderTimeAndPreservesExplicitUnits() {
        assertEquals(VehicleStatus.EMPTY, WidgetReadings.parse(BrandConfig.ZEEKR, distance("277", "km"),
                energy("666", "km", null), energy("666", "km", null), "", "", NOW, SHANGHAI));
        VehicleStatus electric = WidgetReadings.parse(BrandConfig.ZEEKR, distance("50", "%"),
                energy("", "", null), energy("", "", null), "已插枪(交流)", "上次更新 11-24 09:15", NOW, SHANGHAI);
        assertNull(electric.totalKm());
        assertEquals(Integer.valueOf(50), electric.electricPercent());
        assertEquals(NOW - 60_000, electric.updatedAt());
        VehicleStatus hybrid = WidgetReadings.parse(BrandConfig.ZEEKR, distance("700", "km"),
                energy("105km", "", null), energy("600km", "", null), "", "上次更新 11-24 09:15", NOW, SHANGHAI);
        assertEquals(Integer.valueOf(700), hybrid.totalKm());
        assertEquals(Integer.valueOf(105), hybrid.electricKm());
        assertEquals(Integer.valueOf(600), hybrid.fuelKm());
        assertNull(hybrid.electricPercent());
    }

    @Test public void keepsFieldIdentityEvenWhenTheRangesDoNotAddUp() {
        VehicleStatus status = WidgetReadings.parse(distance("700", "km"), energy("76", "km", 79),
                energy("658 km", "", 43), "已驻车", "11-24 09:15", NOW, SHANGHAI);
        assertEquals(Integer.valueOf(700), status.totalKm());
        assertEquals(Integer.valueOf(76), status.electricKm());
        assertEquals(Integer.valueOf(658), status.fuelKm());
        assertEquals(Integer.valueOf(79), status.electricPercent());
        assertEquals(Integer.valueOf(43), status.fuelPercent());
        assertEquals("已驻车", status.motion());
        assertEquals(NOW - 60_000, status.updatedAt());
    }

    @Test public void keepsZeroProgressAndOmitsUnavailableProgress() throws Exception {
        VehicleStatus status = WidgetReadings.parse(distance("1,007", "km"), energy("0", "公里", 0),
                energy("37", "%", null), "充电中", "11-24 09:15", NOW, SHANGHAI);
        assertEquals(Integer.valueOf(1007), status.totalKm());
        assertEquals(Integer.valueOf(0), status.electricKm());
        assertNull(status.fuelKm());
        assertEquals("充电中", status.motion());
        JSONObject json = status.json();
        assertEquals(0, json.getInt("electricPercent"));
        assertNull(json.opt("fuelPercent"));
    }

    @Test public void rejectsTemplateAndInvalidUpdateTime() {
        assertEquals(VehicleStatus.EMPTY, WidgetReadings.parse(distance("364", "km"), energy("37", "%", 36),
                energy("37", "%", 37), "已驻车", "03-15 12:00", NOW, SHANGHAI));
        assertEquals(VehicleStatus.EMPTY, WidgetReadings.parse(distance("734", "km"), energy("76", "km", 78),
                energy("658", "km", 46), "已驻车", "11-24 21:15", NOW, SHANGHAI));
    }

    @Test public void geelyRejectsInitialTemplateAndAcceptsFuelOnly() {
        assertEquals(VehicleStatus.EMPTY, WidgetReadings.parse(BrandConfig.GEELY, distance("--", "km"),
                energy("36", "%", 36), energy("37", "%", 37), "", "更新于03/15 12:00", NOW, SHANGHAI));
        VehicleStatus status = WidgetReadings.parse(BrandConfig.GEELY, distance("0", "km"),
                energy("", "", null), energy("0", "%", 0), "已停车", "更新于11/24 09:15", NOW, SHANGHAI);
        assertEquals(Integer.valueOf(0), status.totalKm());
        assertEquals(Integer.valueOf(0), status.fuelPercent());
        assertNull(status.electricPercent());
        assertNull(status.fuelKm());
        assertEquals(NOW - 60_000, status.updatedAt());
    }

    @Test public void interpretsDecemberCardDateInEarlyJanuary() {
        long january = Instant.parse("2026-12-31T16:01:00Z").toEpochMilli();
        VehicleStatus status = WidgetReadings.parse(distance("734", "km"), energy("76", "km", null),
                energy("658", "km", null), "已驻车", "12-31 23:59", january, SHANGHAI);
        assertEquals(january - 120_000, status.updatedAt());
    }

    @Test public void parsesChineseAndEnglishTwelveHourTimestamps() {
        long afternoonNow = Instant.parse("2026-11-25T09:20:00Z").toEpochMilli(); // 17:20 +08:00
        VehicleStatus afternoon = WidgetReadings.parse(distance("643", "km"), energy("97", "%", 97),
                energy("517", "km", 48), "已驻车", "11-25 下午4:48", afternoonNow, SHANGHAI);
        assertEquals(Instant.parse("2026-11-25T08:48:00Z").toEpochMilli(), afternoon.updatedAt());

        VehicleStatus noon = WidgetReadings.parse(distance("643", "km"), energy("97", "%", 97),
                energy("517", "km", 48), "已驻车", "11月25日 中午12:15", afternoonNow, SHANGHAI);
        assertEquals(Instant.parse("2026-11-25T04:15:00Z").toEpochMilli(), noon.updatedAt());

        VehicleStatus earlyMorning = WidgetReadings.parse(distance("643", "km"), energy("97", "%", 97),
                energy("517", "km", 48), "已驻车", "11-25 凌晨12:30", afternoonNow, SHANGHAI);
        assertEquals(Instant.parse("2026-11-24T16:30:00Z").toEpochMilli(), earlyMorning.updatedAt());

        VehicleStatus morningPm = WidgetReadings.parse(distance("643", "km"), energy("97", "%", 97),
                energy("517", "km", 48), "已驻车", "11-25 4:48 PM", afternoonNow, SHANGHAI);
        assertEquals(Instant.parse("2026-11-25T08:48:00Z").toEpochMilli(), morningPm.updatedAt());
    }

    @Test public void acceptsPercentOnlyReadingsAndParsesPercentageText() {
        VehicleStatus ev = WidgetReadings.parse(distance("", ""), energy("82", "%", 82),
                energy("", "", null), "已驻车", "11-24 09:15", NOW, SHANGHAI);
        assertNull(ev.totalKm());
        assertNull(ev.electricKm());
        assertEquals(Integer.valueOf(82), ev.electricPercent());
        assertEquals(Integer.valueOf(82), distance("82", "%").percent());
        assertEquals(Integer.valueOf(100), distance("100%", "").percent());
        assertNull(distance("120", "%").percent());
    }

    private static WidgetReadings.Distance distance(String value, String unit) {
        return new WidgetReadings.Distance(value, unit);
    }

    private static WidgetReadings.Energy energy(String value, String unit, Integer percent) {
        return new WidgetReadings.Energy(distance(value, unit), percent);
    }
}
