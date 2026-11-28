package com.mario.lynkband;

import org.junit.Test;
import static org.junit.Assert.*;

public final class ActionTicketsTest {
    private final ActionTickets tickets = new ActionTickets();

    @Test public void aConfirmedActionCanRunOnlyOnce() {
        String key = tickets.issue("band", "lock", 10);
        assertTrue(tickets.take("band", key, "lock", 11));
        assertFalse(tickets.take("band", key, "lock", 12));
    }
    @Test public void changedCardDoesNotExecute() {
        String key = tickets.issue("band", "car-a/icon-a", 10);
        assertFalse(tickets.take("band", key, "car-b/icon-a", 11));
        assertFalse(tickets.take("band", key, "car-a/icon-a", 12));
    }
    @Test public void onlyIssuingWatchCanConfirm() {
        String key = tickets.issue("a", "open", 0);
        assertFalse(tickets.take("b", key, "open", 1));
        assertTrue(tickets.take("a", key, "open", 1));
    }
    @Test public void dialogExpiresAtDeadline() {
        String key = tickets.issue("band", "open", 100);
        assertFalse(tickets.take("band", key, "open", 100 + ActionTickets.TIMEOUT));
    }
    @Test public void disconnectAndDisarmRevokePendingActions() {
        String a = tickets.issue("a", "heat", 10);
        String b = tickets.issue("b", "heat", 10);
        tickets.forget("a");
        assertFalse(tickets.take("a", a, "heat", 20));
        tickets.clear();
        assertFalse(tickets.take("b", b, "heat", 20));
    }
    @Test public void newSelectionSupersedesPreviousOne() {
        String old = tickets.issue("band", "heat", 0);
        String current = tickets.issue("band", "cool", 1);
        assertFalse(tickets.take("band", old, "heat", 2));
        assertTrue(tickets.take("band", current, "cool", 3));
    }
    @Test public void officialPlaceholdersDoNotBecomeLiveData() {
        assertEquals("", CardContent.clean("--%"));
        assertEquals("", CardContent.clean("--L"));
        assertEquals("0%", CardContent.clean(" 0% "));
    }
}
