package com.garage.watchcontrol;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class WatchProtocolTest {
    @Test public void pullRefreshRequiresAnUpwardGestureAndEnoughTravel() {
        assertEquals(0, VerticalPagerView.refreshStage(-85, 0, 8, 50));
        assertEquals(0, VerticalPagerView.refreshStage(60, 62, 8, 50));
        assertEquals(0, VerticalPagerView.refreshStage(8, 0, 8, 50));
        assertEquals(1, VerticalPagerView.refreshStage(36, 12, 8, 50));
        assertEquals(2, VerticalPagerView.refreshStage(51, 11, 8, 50));
    }
    @Test public void reconnectStartsImmediatelyAndCapsTheDelay() {
        long[] expected = {0, 1000, 2000, 4000, 8000, 16000, 16000, 16000};
        for (int attempt = 0; attempt < expected.length; attempt++) {
            assertEquals(expected[attempt], WatchProtocol.reconnectDelayMillis(attempt));
        }
    }

    @Test public void executionAcknowledgmentsAndPerSlotHintsAreExplicit() throws Exception {
        JSONObject ack = new JSONObject().put("v", 1).put("type", "execute_ack").put("status", "dispatched");
        assertTrue(WatchProtocol.isExecuteAck(ack));
        assertTrue(WatchProtocol.isExecuteAck(ack.put("status", "unknown")));
        assertFalse(WatchProtocol.isExecuteAck(ack.put("status", "ready")));
        assertFalse(WatchProtocol.isExecuteAck(ack.put("status", "blocked").put("type", "reply")));
        JSONObject states = new JSONObject().put("lynk_unlock", "loading").put("climate", "unknown")
                .put("lock", "ready");
        assertEquals("加载中", WatchProtocol.controlHint(states, "lynk_unlock"));
        assertEquals("状态未知", WatchProtocol.controlHint(states, "climate"));
        assertEquals("", WatchProtocol.controlHint(states, "lock"));
    }

    @Test public void controlGridUsesShapeAndAspectRatio() {
        assertEquals(2, WatchProtocol.controlColumns(true, 480, 480));
        assertEquals(2, WatchProtocol.controlColumns(false, 400, 400));
        assertEquals(1, WatchProtocol.controlColumns(false, 336, 396));
        assertEquals(1, WatchProtocol.controlColumns(false, 336, 480));
        assertEquals(1, WatchProtocol.controlColumns(false, 192, 490));
    }
    @org.junit.Test public void lynkVoiceFiltersBrandAndUnverifiedActions() {
        org.junit.Assert.assertEquals("lynk_unlock", WatchProtocol.voiceAction("LYNKCO", "lynk_unlock"));
        org.junit.Assert.assertEquals("lynk_window", WatchProtocol.voiceAction("LYNKCO", "lynk_window"));
        for (String action : new String[]{"lynk_kjck", "lynk_ccqk", "lock", "engine"})
            org.junit.Assert.assertEquals("", WatchProtocol.voiceAction("LYNKCO", action));
        org.junit.Assert.assertEquals("", WatchProtocol.voiceAction("GALAXY", "lynk_unlock"));
    }
    @Test public void buildsHelloAndPrepareRequestsMatchingCompanionWireFormat() throws Exception {
        JSONObject hello = WatchProtocol.buildRequest("abc_1", "hello", null, null, null, "0.1.23");
        assertEquals(1, hello.getInt("v"));
        assertEquals("hello", hello.getString("type"));
        assertEquals("0.1.23", hello.getString("bandApp"));
        assertEquals("0.1.14", hello.getString("minPhoneApp"));

        JSONObject prepare = WatchProtocol.buildRequest("abc_2", "prepare", "climate", null, "open", "0.1.23");
        assertEquals("prepare", prepare.getString("type"));
        assertEquals("climate", prepare.getString("action"));
        assertEquals("open", prepare.getString("controlDirection"));

        JSONObject carImg = WatchProtocol.buildCarImageRequest("abc_3", "hash1234", 2);
        assertEquals("car_image", carImg.getString("type"));
        assertEquals("hash1234", carImg.getString("hash"));
        assertEquals(2, carImg.getInt("index"));
    }

    @Test public void validatesCompatibilityAndDirectionLabels() throws Exception {
        assertEquals("", WatchProtocol.compatibilityMessage(
                new JSONObject().put("v", 1).put("protocol", 1).put("phoneApp", "0.1.23"), "0.1.23"));
        assertEquals("请更新手机端到配套版本", WatchProtocol.compatibilityMessage(
                new JSONObject().put("v", 1).put("protocol", 1).put("phoneApp", "0.1.10"), "0.1.23"));
        assertEquals("请更新手表端到配套版本", WatchProtocol.compatibilityMessage(
                new JSONObject().put("v", 1).put("protocol", 1).put("phoneApp", "1.0.0"), "0.1.23"));

        Map<String, String> directions = WatchProtocol.readControlDirections(
                new JSONObject().put("lock", "unlock").put("window_vent", "close").put("climate", "open"), null);
        assertEquals("解锁", WatchProtocol.controlLabel("lock", directions.get("lock")));
        assertEquals("关窗", WatchProtocol.controlLabel("window_vent", directions.get("window_vent")));
        assertEquals("开空调", WatchProtocol.controlLabel("climate", directions.get("climate")));
        assertEquals("lock-open.png", WatchProtocol.iconAsset("lock", "lock"));
        assertEquals("lock.png", WatchProtocol.iconAsset("lock", "unlock"));
        assertTrue(WatchProtocol.needsDirection("climate", false));
        assertFalse(WatchProtocol.needsDirection("trunk", true));
        assertEquals(2, WatchProtocol.knownActions(new JSONArray().put("climate").put("climate").put("bad").put("find")).size());
    }

    @Test public void crownScrollSwitchesBetweenFirstAndSecondPageWithThresholdAndDebounce() {
        // Light accidental brushes stay below CROWN_TRIGGER_THRESHOLD (1.4f).
        assertEquals(-1, VerticalPagerView.nextPageFromCrown(0, 2, -0.4f, 1000L, 0L));
        assertEquals(-1, VerticalPagerView.nextPageFromCrown(0, 2, -1.0f, 1000L, 0L));
        assertEquals(1, VerticalPagerView.nextPageFromCrown(0, 2, -1.5f, 1000L, 0L));
        assertEquals(0, VerticalPagerView.nextPageFromCrown(1, 2, 1.5f, 1500L, 1000L));
        assertEquals(-1, VerticalPagerView.nextPageFromCrown(0, 2, 1.5f, 1500L, 1000L));
        assertEquals(-1, VerticalPagerView.nextPageFromCrown(1, 2, -1.5f, 1500L, 1000L));
        assertEquals(-1, VerticalPagerView.nextPageFromCrown(0, 2, -1.5f, 1200L, 1000L));
        assertEquals(-1, VerticalPagerView.nextPageFromCrown(0, 2, 0.0f, 1500L, 1000L));

        // Intentional ticks within 260 ms accumulate to trigger; stale ticks reset.
        VerticalPagerView.CrownStep firstTick = VerticalPagerView.stepCrownScroll(0, 2, -0.8f, 0f, 1000L, 0L, 0L);
        assertEquals(-1, firstTick.targetPage());
        assertEquals(-0.8f, firstTick.nextAccum(), 0.001f);
        VerticalPagerView.CrownStep secondTick = VerticalPagerView.stepCrownScroll(
                0, 2, -0.8f, firstTick.nextAccum(), 1120L, 1000L, 0L);
        assertEquals(1, secondTick.targetPage());

        VerticalPagerView.CrownStep staleTick = VerticalPagerView.stepCrownScroll(
                0, 2, -0.8f, firstTick.nextAccum(), 1400L, 1000L, 0L);
        assertEquals(-1, staleTick.targetPage());
        assertEquals(-0.8f, staleTick.nextAccum(), 0.001f);
    }

    @Test public void fallsBackToPercentageWhenKilometersAreUnavailable() throws Exception {
        JSONObject hybrid = new JSONObject()
                .put("totalKm", 643)
                .put("electricPercent", 97)
                .put("fuelKm", 517)
                .put("fuelPercent", 48);
        assertEquals("643 km", WatchProtocol.heroDisplay(hybrid));
        WatchProtocol.EnergyBreakdown hybridEnergy = WatchProtocol.energyBreakdown(hybrid);
        assertEquals("97%", hybridEnergy.electric());
        assertEquals("517 km", hybridEnergy.fuel());
        assertTrue(hybridEnergy.showElectric());
        assertTrue(hybridEnergy.showFuel());

        JSONObject ev = new JSONObject().put("electricPercent", 84);
        assertEquals("84%", WatchProtocol.heroDisplay(ev));
        assertEquals("84%", WatchProtocol.heroNumber("84%"));
        assertEquals("", WatchProtocol.heroUnit("84%"));
        assertEquals("301", WatchProtocol.heroNumber("301 km"));
        assertEquals(" km", WatchProtocol.heroUnit("301 km"));
        WatchProtocol.EnergyBreakdown evEnergy = WatchProtocol.energyBreakdown(ev);
        assertEquals("84%", evEnergy.electric());
        assertEquals("— km", evEnergy.fuel());
        assertTrue(evEnergy.showElectric());
        assertFalse(evEnergy.showFuel());

        WatchProtocol.EnergyBreakdown fuelOnly = WatchProtocol.energyBreakdown(new JSONObject().put("fuelKm", 0));
        assertFalse(fuelOnly.showElectric());
        assertTrue(fuelOnly.showFuel());
        assertEquals("0 km", fuelOnly.fuel());

        WatchProtocol.EnergyBreakdown unknown = WatchProtocol.energyBreakdown(null);
        assertTrue(unknown.showElectric());
        assertTrue(unknown.showFuel());
    }

    @Test public void adaptsRoundWatchHomeMetricsByDialSizeAndPreservesRectDefault() {
        WatchProtocol.RoundHomeMetrics compact = WatchProtocol.RoundHomeMetrics.forDiameterDp(192);
        WatchProtocol.RoundHomeMetrics standard = WatchProtocol.RoundHomeMetrics.forDiameterDp(233);
        WatchProtocol.RoundHomeMetrics large = WatchProtocol.RoundHomeMetrics.forDiameterDp(290);
        WatchProtocol.RoundHomeMetrics rect = WatchProtocol.RoundHomeMetrics.rectDefault();

        assertEquals(14, rect.hPadDp());
        assertEquals(0, rect.modelMaxWidthDp());
        assertEquals(40, standard.heroSp());
        assertEquals(22, standard.hPadDp());
        assertTrue(compact.heroSp() < standard.heroSp());
        assertTrue(compact.hPadDp() < standard.hPadDp());
        assertTrue(large.heroSp() > standard.heroSp());
        assertTrue(large.hPadDp() > standard.hPadDp());
        assertTrue(compact.modelMaxWidthDp() < standard.modelMaxWidthDp()
                && standard.modelMaxWidthDp() < large.modelMaxWidthDp());
    }

    @Test public void mapsCircularWatchDimensionsToS5DesignWidth336AndFitsWithoutOverflow() {
        for (int diameterPx : new int[]{396, 454, 466, 480}) {
            WatchProtocol.WatchScale scale = new WatchProtocol.WatchScale(true, diameterPx, 2.0f);
            assertEquals(diameterPx, scale.px(336, 233));
            assertEquals(Math.round(diameterPx * (72 / 336.0f)), scale.px(scale.roundOrb336(4), 58));
            assertEquals(Math.round(diameterPx * (48 / 336.0f)), scale.px(scale.roundIcon336(4), 40));
            assertEquals(diameterPx * (16 / 336.0f), scale.textPx(scale.roundLabel336(4)), 0.05f);
            assertTrue(WatchProtocol.WatchScale.roundControlsEstimatedHeight336(1, true) <= 336);
            assertTrue(WatchProtocol.WatchScale.roundControlsEstimatedHeight336(2, true) <= 336);
            assertTrue(WatchProtocol.WatchScale.roundControlsEstimatedHeight336(4, true) <= 336);
        }
        WatchProtocol.WatchScale rect = new WatchProtocol.WatchScale(false, 400, 2.0f);
        assertEquals(28, rect.px(36, 14));
    }
}
