package com.garage.bandcontrol;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class VoiceShortcutsTest {
    @Test public void tokenValidationRejectsMissingOrMismatchedValues() {
        String token = VoiceShortcuts.generateToken();
        assertEquals(32, token.length());
        assertTrue(VoiceShortcuts.isValidToken(token, token));
        assertFalse(VoiceShortcuts.isValidToken(token, token + "x"));
        assertFalse(VoiceShortcuts.isValidToken(token, ""));
        assertFalse(VoiceShortcuts.isValidToken(token, null));
        assertFalse(VoiceShortcuts.isValidToken("", ""));
        assertFalse(VoiceShortcuts.isValidToken(null, token));
    }

    @Test public void resolvesOnlyTokenVerifiedControlCommands() {
        String token = "0123456789abcdef0123456789abcdef";
        assertEquals(MiHomeVoice.Command.LOCK, VoiceShortcuts.resolveShortcutCommand(
                VoiceShortcuts.ACTION_DISPATCH, token, token, MiHomeVoice.Command.LOCK.code));
        assertNull(VoiceShortcuts.resolveShortcutCommand(
                "android.intent.action.VIEW", token, token, MiHomeVoice.Command.LOCK.code));
        assertNull(VoiceShortcuts.resolveShortcutCommand(
                VoiceShortcuts.ACTION_DISPATCH, token, "wrong", MiHomeVoice.Command.LOCK.code));
        assertNull(VoiceShortcuts.resolveShortcutCommand(
                VoiceShortcuts.ACTION_DISPATCH, token, token, MiHomeVoice.Command.TEST.code));
        assertNull(VoiceShortcuts.resolveShortcutCommand(
                VoiceShortcuts.ACTION_DISPATCH, token, token, "GARAGE_V1:UNKNOWN"));
    }

    @Test public void dynamicSelectionExpandsLockAndRespectsLimit() {
        List<MiHomeVoice.Command> selected = VoiceShortcuts.selectDynamicCommands(
                List.of(CarAction.LOCK, CarAction.CLIMATE, CarAction.TRUNK, CarAction.FIND), 4);
        assertEquals(List.of(
                MiHomeVoice.Command.LOCK,
                MiHomeVoice.Command.UNLOCK,
                MiHomeVoice.Command.CLIMATE,
                MiHomeVoice.Command.TRUNK), selected);
        assertTrue(VoiceShortcuts.selectDynamicCommands(List.of(CarAction.FIND), 0).isEmpty());
        assertEquals(12, VoiceShortcuts.allControlCommands().size());
    }
}
