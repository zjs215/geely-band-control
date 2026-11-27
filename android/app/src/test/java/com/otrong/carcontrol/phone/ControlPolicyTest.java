package com.otrong.carcontrol.phone;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ControlPolicyTest {
    @Test public void allowsArmingWithoutWearableService() {
        assertTrue(ControlPolicy.canArm(true, true));
        assertFalse(ControlPolicy.canArm(false, true));
        assertFalse(ControlPolicy.canArm(true, false));
    }

    @Test public void keepsWidgetsListeningInBackgroundForArmedVoiceControl() {
        assertTrue(ControlPolicy.shouldListenWidgets(false, false, true, true));
        assertTrue(ControlPolicy.shouldListenWidgets(false, true, false, false));
        assertTrue(ControlPolicy.shouldListenWidgets(true, false, false, false));
        assertFalse(ControlPolicy.shouldListenWidgets(false, false, true, false));
        assertFalse(ControlPolicy.shouldListenWidgets(false, false, false, true));
    }

    @Test public void voiceControlDoesNotRequireWearableServiceWhileWearChannelDoes() {
        assertEquals("", ControlPolicy.liveControlNotice("", false, false, true));
        assertEquals("请在手机点击「允许车控」", ControlPolicy.liveControlNotice("", false, false, false));
        assertEquals("请先启动手机端穿戴通信", ControlPolicy.liveControlNotice("", true, false, true));
        assertEquals("", ControlPolicy.liveControlNotice("", true, true, true));
        assertEquals("正在同步银河车况", ControlPolicy.liveControlNotice("正在同步银河车况", false, false, true));
    }
}
