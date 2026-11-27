package com.garage.bandcontrol;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public final class LockControlTest {
    @Test public void recognizedLockIconRemainsReadyRegardlessOfCardStatus() {
        for (String lockState : new String[]{"locked", "unlocked"}) {
            assertEquals("", LockControl.blockedReason(lockState));
        }
    }

    @Test public void unrecognizedLockStateRemainsBlocked() {
        for (String lockState : new String[]{"", "unknown", "invalid"}) {
            assertFalse(LockControl.blockedReason(lockState).isEmpty());
        }
    }
}
