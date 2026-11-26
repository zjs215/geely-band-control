package com.garage.bandcontrol;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public final class LockControlTest {
    @Test public void accessoryWarningsDoNotHideAnOtherwiseRecognizedLock() {
        for (String status : new String[]{"已驻车", "已停车", "已插枪", "充电中", "已驻车、已插枪", "车窗未关", "天窗未关闭", "车门未关", "后备箱未关"}) {
            assertEquals(status, "", LockControl.blockedReason(true, status, "locked"));
            assertEquals(status, "", LockControl.blockedReason(true, status, "unlocked"));
        }
        assertEquals("", LockControl.blockedReason(true, "车门未锁、车窗未关闭", "unlocked"));
    }

    @Test public void drivingOverridesOtherWarningsRegardlessOfOrder() {
        for (String status : new String[]{"已驻车、行驶中", "行驶中、车窗未关", "车门未锁 未驻车"}) {
            assertEquals("银河卡片显示车辆未驻车", LockControl.blockedReason(true, status, "unlocked"));
        }
    }

    @Test public void staleUnknownAndContradictoryReadingsRemainBlocked() {
        assertFalse(LockControl.blockedReason(false, "已驻车", "locked").isEmpty());
        assertFalse(LockControl.blockedReason(true, "", "locked").isEmpty());
        assertFalse(LockControl.blockedReason(true, "未知状态", "locked").isEmpty());
        assertFalse(LockControl.blockedReason(true, "已驻车", "").isEmpty());
        assertEquals("银河显示车门未锁，锁图标状态不一致",
                LockControl.blockedReason(true, "已驻车、车门未锁", "locked"));
    }
}
