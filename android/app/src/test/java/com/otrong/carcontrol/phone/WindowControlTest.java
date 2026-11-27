package com.otrong.carcontrol.phone;

import org.junit.Test;

import static com.otrong.carcontrol.phone.ControlDirection.CLOSE;
import static com.otrong.carcontrol.phone.ControlDirection.OPEN;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

public final class WindowControlTest {
    @Test public void windowDirectionFollowsRecognizedIconDirectly() {
        assertEquals(OPEN, WindowControl.resolve(OPEN).direction());
        assertEquals(CLOSE, WindowControl.resolve(CLOSE).direction());
        WindowControl.Decision unknown = WindowControl.resolve(null);
        assertNull(unknown.direction());
        assertFalse(unknown.blockedReason().isEmpty());
    }
}
