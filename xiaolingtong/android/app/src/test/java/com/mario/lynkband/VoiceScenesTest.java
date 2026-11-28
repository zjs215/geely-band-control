package com.mario.lynkband;

import org.junit.Test;
import static org.junit.Assert.*;

public class VoiceScenesTest {
    private final VoiceScenes scenes = new VoiceScenes();
    private int executions;
    private final VoiceScenes.Card card = new VoiceScenes.Card() {
        public VoiceScenes.Choice prepare(String name) {
            return name.equals("上锁") ? new VoiceScenes.Choice("slot1", "pixels", name, "ticket") : null;
        }
        public String execute(VoiceScenes.Choice choice) { executions++; return "已转交 " + choice.label(); }
        public void cancel() {}
    };
    private String post(String code, long time) {
        return scenes.receive(code, "notice", time, time, time, true, card);
    }
    @Test public void everyPresetCanBeCopiedAndUnderstoodWithoutGuessingDirection() {
        for (VoicePreset p : VoicePreset.values()) {
            String code = VoiceScenes.ACTION + p.label;
            assertEquals(code, VoiceScenes.command(code));
        }
        assertEquals("打开空调", VoicePreset.canonical("开启空调"));
        assertEquals("关闭空调", VoicePreset.canonical("关空调"));
        assertFalse(VoiceScenes.concrete("空调"));
        assertFalse(VoiceScenes.concrete("车锁"));
        assertEquals("已转交 上锁", scenes.receive("LYNK:动作:锁车", "a", 10, 10, 10, false, card));
    }
    @Test public void shortRemarksKeepActionDirectionAndExistingVoiceCommands() {
        String[][] pairs = {{"打开车窗", "开车窗"}, {"关闭车窗", "关车窗"}, {"打开空调", "开空调"},
                {"关闭空调", "关空调"}, {"上锁", "上锁"}, {"解锁", "解锁"}};
        for (String[] pair : pairs) {
            assertEquals(pair[1], VoicePreset.display(pair[0]));
            assertEquals(pair[0], VoicePreset.canonical(pair[1]));
        }
        assertEquals("车窗", VoicePreset.display("车窗"));
    }

    @Test public void confirmsOnlyTheCurrentChoiceOnce() {
        assertTrue(post("LYNK:确认", 1).contains("没有"));
        assertTrue(post("LYNK:动作:上锁", 2).contains("待执行"));
        assertEquals(0, executions);
        assertEquals("已转交 上锁", post("LYNK:确认", 3));
        post("LYNK:确认", 3);
        post("LYNK:确认", 4);
        assertEquals(1, executions);
    }
    @Test public void rejectsConflictsStaleConfirmationAndDuplicateNotification() {
        assertEquals("", VoiceScenes.command("LYNK:动作:上锁", "LYNK:确认"));
        assertEquals("", VoiceScenes.command("LYNK:动作:车锁"));
        assertEquals("LYNK:确认", VoiceScenes.command("通知", "LYNK:确认"));
        post("LYNK:动作:上锁", 100);
        assertEquals("", scenes.receive("LYNK:确认", "other", 99, 110, 110, true, card));
        post("LYNK:动作:解锁", 120);
        post("LYNK:确认", 130);
        post("LYNK:动作:上锁", 150);
        post("LYNK:确认", 15150);
        post("LYNK:动作:上锁", 20000);
        post("LYNK:取消", 20001);
        post("LYNK:确认", 20002);
        assertEquals(0, executions);
    }
    @Test public void directModeDispatchesOnceAndDoesNotLeaveAConfirmation() {
        assertEquals("已转交 上锁", scenes.receive("LYNK:动作:上锁", "a", 10, 10, 10, false, card));
        scenes.receive("LYNK:动作:上锁", "a", 10, 10, 10, false, card);
        scenes.receive("LYNK:动作:上锁", "b", 12, 12, 12, false, card);
        post("LYNK:确认", 13);
        assertEquals(1, executions);
    }
    @Test public void unavailableActionAndConnectionResetNeverExecute() {
        post("LYNK:动作:寻车", 1);
        post("LYNK:确认", 2);
        post("LYNK:动作:上锁", 3);
        scenes.clear();
        post("LYNK:确认", 4);
        assertEquals(0, executions);
    }
}
