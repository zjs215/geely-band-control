package com.mario.lynkband;

import org.junit.Test;
import static org.junit.Assert.*;

public class CardConfirmationTest {
    private final CardConfirmation pending = new CardConfirmation();

    @Test public void hiddenVehiclePanelCanConfirmOnlyUntilDeadline() {
        pending.start("band:A", "card1", "车A", "position", "slot", "上锁", 100);
        assertEquals("上锁", pending.current("card1", "", 110).label());
        assertNull(pending.current("card1", "", 100 + CardConfirmation.TIMEOUT));
    }

    @Test public void differentVehicleOrBindingInvalidatesEvenIfOriginalReturns() {
        pending.start("watch:A", "card1", "车A", "position", "slot", "开窗", 100);
        assertNull(pending.current("card1", "车B", 101));
        assertNull(pending.current("card1", "车A", 102));
        pending.start("watch:A", "card1", "车A", "position", "slot", "开窗", 200);
        assertNull(pending.current("card2", "", 201));
    }

    @Test public void disconnectRevokesOnlyIssuingDeviceAndReconnectDoesNotReplay() {
        pending.start("band:A", "card1", "车A", "position", "slot", "上锁", 100);
        pending.forget("band:B");
        assertTrue(pending.waiting(101));
        pending.forget("band:A");
        assertNull(pending.current("card1", "车A", 102));
    }
}
