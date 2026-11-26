package com.garage.bandcontrol;

import org.junit.Test;

import static com.garage.bandcontrol.ControlDirection.CLOSE;
import static com.garage.bandcontrol.ControlDirection.OPEN;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

public final class WindowControlTest {
    @Test public void windowDirectionFollowsRecognizedIconAcrossUnlockedAndCompoundStates() {
        assertEquals(CLOSE, WindowControl.resolve("车窗未关", CLOSE).direction());
        assertEquals(CLOSE, WindowControl.resolve("车门未锁、车窗未关闭", CLOSE).direction());
        assertEquals(OPEN, WindowControl.resolve("车窗未关", OPEN).direction());
        assertEquals(OPEN, WindowControl.resolve("车门未锁、车窗未关", OPEN).direction());
        assertEquals(OPEN, WindowControl.resolve("已驻车", OPEN).direction());
        assertEquals(OPEN, WindowControl.resolve("车窗已关闭", OPEN).direction());
        assertEquals(CLOSE, WindowControl.resolve("车窗已关闭", CLOSE).direction());
        assertEquals(OPEN, WindowControl.resolve("已停车", OPEN).direction());
        assertEquals(OPEN, WindowControl.resolve("已插枪", OPEN).direction());
        assertEquals(OPEN, WindowControl.resolve("充电中", OPEN).direction());
        assertEquals(OPEN, WindowControl.resolve("已驻车、已插枪", OPEN).direction());
        assertEquals(OPEN, WindowControl.resolve("车门未锁", OPEN).direction());
        assertEquals(CLOSE, WindowControl.resolve("后备箱未关", CLOSE).direction());
        assertEquals(CLOSE, WindowControl.resolve("已插枪", CLOSE).direction());
        assertEquals(CLOSE, WindowControl.resolve("车门未锁", CLOSE).direction());
        assertEquals(CLOSE, WindowControl.resolve("天窗未关", CLOSE).direction());
        assertBlocked("车窗未关", null);
        assertBlocked("已停车", null);
    }

    @Test public void missingAndDrivingOrUnknownWarningsRemainBlocked() {
        assertBlocked("", OPEN);
        assertBlocked("行驶中", OPEN);
        assertBlocked("行驶中", CLOSE);
        assertBlocked("未驻车", OPEN);
        assertBlocked("未知状态", CLOSE);
    }

    private static void assertBlocked(String status, ControlDirection icon) {
        WindowControl.Decision decision = WindowControl.resolve(status, icon);
        assertNull(decision.direction());
        assertFalse(decision.blockedReason().isEmpty());
    }
}
