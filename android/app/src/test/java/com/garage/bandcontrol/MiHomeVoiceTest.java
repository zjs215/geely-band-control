package com.garage.bandcontrol;

import org.junit.Test;

import java.util.EnumSet;

import static com.garage.bandcontrol.MiHomeVoice.Admission.ACCEPTED;
import static com.garage.bandcontrol.MiHomeVoice.Admission.DUPLICATE;
import static com.garage.bandcontrol.MiHomeVoice.Admission.STALE;
import static com.garage.bandcontrol.MiHomeVoice.Command.FIND;
import static com.garage.bandcontrol.MiHomeVoice.Command.LOCK;
import static com.garage.bandcontrol.MiHomeVoice.Command.TEST;
import static com.garage.bandcontrol.MiHomeVoice.Command.UNLOCK;
import static com.garage.bandcontrol.MiHomeVoice.Command.WINDOW_OPEN;
import static com.garage.bandcontrol.MiHomeVoice.Command.WINDOW_VENT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class MiHomeVoiceTest {
    @Test public void acceptsOnlySupportedVoicePackages() {
        assertEquals(MiHomeVoice.Source.MI_HOME, MiHomeVoice.sourceOf("com.xiaomi.smarthome"));
        assertEquals(MiHomeVoice.Source.COLOROS_SHORTCUTS, MiHomeVoice.sourceOf("com.coloros.shortcuts"));
        assertTrue(MiHomeVoice.isSource("com.xiaomi.smarthome"));
        assertTrue(MiHomeVoice.isSource("com.coloros.shortcuts"));
        assertFalse(MiHomeVoice.isSource("com.heytap.speechassist"));
        assertFalse(MiHomeVoice.isSource("com.xiaomi.smarthome.other"));
        assertFalse(MiHomeVoice.isSource("com.coloros.shortcuts.other"));
        assertFalse(MiHomeVoice.isSource("米家"));
        assertFalse(MiHomeVoice.isSource("小布指令"));
        assertFalse(MiHomeVoice.isSource(null));
    }

    @Test public void recognizesExactAndTrimmedCodesAndRejectsConflictingFields() {
        assertEquals(new MiHomeVoice.Match(TEST, "正文"),
                MiHomeVoice.match(new StringBuilder(TEST.code), TEST.code, "米家"));
        assertEquals(new MiHomeVoice.Match(TEST, "正文"),
                MiHomeVoice.match("  " + TEST.code + "\n", null, "小布指令"));
        assertEquals(new MiHomeVoice.Match(FIND, "展开正文"),
                MiHomeVoice.match("米家", FIND.code, null));
        assertEquals(new MiHomeVoice.Match(FIND, "标题"),
                MiHomeVoice.match(null, null, FIND.code));
        assertEquals(CarAction.FIND, FIND.action);
        assertNull(TEST.action);
        assertNull(MiHomeVoice.match("手机通知 " + FIND.code, null, null));
        assertNull(MiHomeVoice.match("提醒：" + FIND.code, null, null));
        assertNull(MiHomeVoice.match("GARAGE_V1:UNKNOWN", null, null));
        assertNull(MiHomeVoice.match(TEST.code, null, FIND.code));
    }

    @Test public void unmatchedHintOnlyFlagsMessagesWithCommandPrefix() {
        assertEquals("", MiHomeVoice.unmatchedHint("记得下楼拿快递", null, "小布指令"));
        assertEquals("正文含指令前缀但未精确匹配，请确保内容仅填指令码且无冲突",
                MiHomeVoice.unmatchedHint("提醒：" + FIND.code, null, "小布指令"));
        assertEquals("正文、标题含指令前缀但未精确匹配，请确保内容仅填指令码且无冲突",
                MiHomeVoice.unmatchedHint(TEST.code, null, FIND.code));
    }

    @Test public void everyCarActionHasAnExactVoiceCode() {
        EnumSet<CarAction> mapped = EnumSet.noneOf(CarAction.class);
        for (MiHomeVoice.Command command : MiHomeVoice.Command.values()) {
            assertEquals(command, MiHomeVoice.match(command.code, null, null).command());
            if (command.action != null) mapped.add(command.action);
        }
        assertEquals(EnumSet.allOf(CarAction.class), mapped);
        assertEquals(13, MiHomeVoice.Command.values().length);
    }

    @Test public void lockAndUnlockOnlyUseTheCorrectButtonDirection() {
        assertEquals(CarAction.LOCK, LOCK.action);
        assertEquals(CarAction.LOCK, UNLOCK.action);
        assertEquals("", LOCK.directionBlock(ControlDirection.LOCK));
        assertEquals("", UNLOCK.directionBlock(ControlDirection.UNLOCK));
        assertFalse(LOCK.directionBlock(ControlDirection.UNLOCK).isEmpty());
        assertFalse(UNLOCK.directionBlock(ControlDirection.LOCK).isEmpty());
        assertFalse(UNLOCK.directionBlock(null).isEmpty());
    }

    @Test public void namedWindowOpeningCommandsNeverUseTheClosingDirection() {
        for (MiHomeVoice.Command command : new MiHomeVoice.Command[]{WINDOW_OPEN, WINDOW_VENT}) {
            assertEquals("", command.directionBlock(ControlDirection.OPEN));
            assertFalse(command.directionBlock(ControlDirection.CLOSE).isEmpty());
            assertFalse(command.directionBlock(null).isEmpty());
        }
    }

    @Test public void onlyFreshPostsAreAccepted() {
        MiHomeVoice voice = new MiHomeVoice();
        long now = 60_000;
        assertEquals(STALE, voice.receive("old", now - MiHomeVoice.MAX_AGE_MS - 1, now, 0, TEST));
        assertEquals(STALE, voice.receive("future", now + 1, now, 0, FIND));
        assertEquals(ACCEPTED, voice.receive("fresh", now - MiHomeVoice.MAX_AGE_MS, now, 0, TEST));
    }

    @Test public void aRepeatedNotificationNeverStartsAnotherDispatchWithinItsLifetime() {
        MiHomeVoice voice = new MiHomeVoice();
        assertEquals(ACCEPTED, voice.receive("mi-home-alert", 60_000, 60_000, 100, FIND));
        assertEquals(DUPLICATE, voice.receive("mi-home-alert", 60_000, 75_000, 15_100, FIND));
        assertEquals(ACCEPTED, voice.receive("mi-home-alert", 75_000, 75_000, 15_100, FIND));
    }

    @Test public void repeatWindowOnlySuppressesTheSameCommand() {
        MiHomeVoice voice = new MiHomeVoice();
        long now = 60_000;
        assertEquals(ACCEPTED, voice.receive("first", now, now, 0, FIND));
        assertEquals(DUPLICATE, voice.receive("redelivery", now + 1, now + 1, 1, FIND));
        assertEquals(ACCEPTED, voice.receive("test", now + 1, now + 1, 1, TEST));
        assertEquals(ACCEPTED, voice.receive("other", now + 1, now + 1, 1, LOCK));
        long repeat = MiHomeVoice.CONTROL_REPEAT_MS;
        assertEquals(DUPLICATE, voice.receive("redelivery", now + 1, now + repeat, repeat, FIND));
        assertEquals(ACCEPTED, voice.receive("new-find", now + repeat, now + repeat, repeat, FIND));
    }
}
