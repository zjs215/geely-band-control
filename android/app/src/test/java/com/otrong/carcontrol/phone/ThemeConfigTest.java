package com.otrong.carcontrol.phone;

import com.otrong.carcontrol.appearance.ThemeConfig;
import org.junit.Test;

import java.time.ZonedDateTime;

import static com.otrong.carcontrol.appearance.ThemeConfig.Mode;
import static com.otrong.carcontrol.appearance.ThemeConfig.Scene;
import static com.otrong.carcontrol.appearance.ThemeConfig.Variant;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class ThemeConfigTest {
    @Test public void localScheduleSwitchesAtBothBoundaries() {
        ThemeConfig sky = new ThemeConfig(Scene.SKY, Mode.SCHEDULE, Variant.DAY, 360, 1080);
        assertEquals(Variant.NIGHT, sky.activeVariant(359, Variant.DAY));
        assertEquals(Variant.DAY, sky.activeVariant(360, Variant.NIGHT));
        assertEquals(Variant.DAY, sky.activeVariant(1079, Variant.NIGHT));
        assertEquals(Variant.NIGHT, sky.activeVariant(1080, Variant.DAY));
        assertEquals(250L, sky.nextChangeMillis(ZonedDateTime.parse("2026-11-26T17:59:59.750+08:00[Asia/Shanghai]")));
    }

    @Test public void wraparoundAndFixedChoicesUseTheSameWireSchema() throws Exception {
        ThemeConfig scheduled = new ThemeConfig(Scene.RIDGE, Mode.SCHEDULE, Variant.NIGHT, 1410, 375);
        assertEquals(Variant.DAY, scheduled.activeVariant(0, Variant.NIGHT));
        assertEquals(Variant.NIGHT, scheduled.activeVariant(375, Variant.DAY));
        assertEquals(Variant.DAY, scheduled.activeVariant(1410, Variant.NIGHT));
        assertEquals(scheduled, ThemeConfig.parse(scheduled.encode()));
        assertNull(ThemeConfig.fromJson(scheduled.json().put("nightStartMinute", 1410)));
        assertNull(ThemeConfig.fromJson(scheduled.json().put("dayStartMinute", "1410")));
        assertNull(ThemeConfig.fromJson(scheduled.json().put("themeId", "unknown")));

        ThemeConfig fixed = new ThemeConfig(Scene.CITY, Mode.FIXED, Variant.NIGHT, 360, 1080);
        assertEquals(Variant.NIGHT, fixed.activeVariant(360, Variant.DAY));
        assertEquals(Variant.NIGHT, fixed.activeVariant(1080, Variant.NIGHT));
        assertEquals(0L, fixed.nextChangeMillis(ZonedDateTime.now()));
    }

    @Test public void systemModePersistsAndFollowsTheDeviceInsteadOfTheClock() {
        ThemeConfig system = new ThemeConfig(Scene.COAST, Mode.SYSTEM, Variant.NIGHT, 360, 1080);
        assertEquals(system, ThemeConfig.parse(system.encode()));
        assertEquals(Variant.DAY, system.activeVariant(0, Variant.DAY));
        assertEquals(Variant.NIGHT, system.activeVariant(720, Variant.NIGHT));
        assertEquals(0L, system.nextChangeMillis(ZonedDateTime.now()));
        assertEquals("海岸灯塔 · 夜景 · 跟随系统", system.summary(Variant.NIGHT, ThemeConfig.SYSTEM_LABEL));
        assertEquals("海岸灯塔 · 日景 · 跟随手机系统", system.summary(Variant.DAY, ThemeConfig.PHONE_SYSTEM_LABEL));
    }

    @Test public void wearableSyncSendsFixedVariantsUsingTheExistingProtocol() throws Exception {
        ThemeConfig system = new ThemeConfig(Scene.ALPINE, Mode.SYSTEM, Variant.DAY, 470, 1190);
        ThemeConfig day = system.forWearableSync(Variant.DAY);
        ThemeConfig night = system.forWearableSync(Variant.NIGHT);
        assertEquals("fixed", day.json().getString("mode"));
        assertEquals("day", day.json().getString("variant"));
        assertEquals("night", night.json().getString("variant"));
        assertEquals(Scene.ALPINE, night.scene());
        assertEquals(night, ThemeConfig.parse(night.encode()));
        assertEquals(Mode.SYSTEM, system.mode());
        assertEquals(day, day.forWearableSync(Variant.NIGHT));
        ThemeConfig scheduled = new ThemeConfig(Scene.SKY, Mode.SCHEDULE, Variant.DAY, 470, 1190);
        assertEquals(scheduled, scheduled.forWearableSync(Variant.NIGHT));
    }
}
