package com.otrong.carcontrol.phone;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ConfirmationTest {
    @Test public void aConfirmationOnlyDispatchesOnceForItsNode() {
        Confirmation registry = new Confirmation();
        String token = registry.prepare("band-a", CarAction.CLIMATE, "card-1-air", 0);
        assertFalse(registry.consume("band-b", CarAction.CLIMATE, token, "card-1-air", 100));
        assertTrue(registry.consume("band-a", CarAction.CLIMATE, token, "card-1-air", 100));
        assertFalse(registry.consume("band-a", CarAction.CLIMATE, token, "card-1-air", 120));
    }

    @Test public void rejectsExpiredTokensAndChangedButtons() {
        Confirmation registry = new Confirmation();
        String expired = registry.prepare("band", CarAction.TRUNK, "mapped-trunk", 0);
        assertFalse(registry.consume("band", CarAction.TRUNK, expired, "mapped-trunk", Confirmation.LIFETIME_MS));
        String changed = registry.prepare("band", CarAction.LOCK, "unlock", 0);
        assertFalse(registry.consume("band", CarAction.LOCK, changed, "lock", 100));
        assertFalse(registry.consume("band", CarAction.LOCK, changed, "unlock", 110));
    }

    @Test public void switchingModesInvalidatesAConfirmation() {
        Confirmation registry = new Confirmation();
        String token = registry.prepare("band", CarAction.SENTRY, "sentry", 0);
        registry.clear();
        assertFalse(registry.consume("band", CarAction.SENTRY, token, "sentry", 50));
    }

    @Test public void onlyTheMatchingNodeAndTokenCanCancelAConfirmation() {
        Confirmation registry = new Confirmation();
        String one = registry.prepare("first", CarAction.CLIMATE, "climate", 0);
        String two = registry.prepare("second", CarAction.SENTRY, "sentry", 0);
        registry.cancel("first", two);
        registry.cancel("second", two);
        assertTrue(registry.consume("first", CarAction.CLIMATE, one, "climate", 50));
        assertFalse(registry.consume("second", CarAction.SENTRY, two, "sentry", 50));
    }
}
