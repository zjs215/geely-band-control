package com.garage.bandcontrol;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Publishes token-protected App Shortcuts so system voice assistants (such as vivo BlueLM / Honor YOYO)
 * or home-screen shortcuts can trigger vehicle controls without relying on vendor notification actions.
 */
final class VoiceShortcuts {
    private static final String TAG = "VoiceShortcuts";
    static final String ACTION_DISPATCH = "com.garage.bandcontrol.ACTION_VOICE_SHORTCUT";
    static final String EXTRA_COMMAND = "command";
    static final String EXTRA_TOKEN = "token";

    private VoiceShortcuts() {}

    static String generateToken() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            hex.append(Character.forDigit((value >> 4) & 0xF, 16));
            hex.append(Character.forDigit(value & 0xF, 16));
        }
        return hex.toString();
    }

    static boolean isValidToken(String expected, String provided) {
        if (expected == null || expected.isEmpty() || provided == null || provided.isEmpty()) return false;
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8));
    }

    static MiHomeVoice.Command resolveShortcutCommand(String action, String expectedToken,
                                                      String providedToken, String commandCode) {
        if (!ACTION_DISPATCH.equals(action)) return null;
        if (!isValidToken(expectedToken, providedToken)) return null;
        MiHomeVoice.Command command = MiHomeVoice.Command.fromText(commandCode);
        return command != null && command.action != null ? command : null;
    }

    static List<MiHomeVoice.Command> selectDynamicCommands(Collection<CarAction> mappedActions, int maxCount) {
        if (mappedActions == null || maxCount <= 0) return List.of();
        Set<MiHomeVoice.Command> selected = new LinkedHashSet<>();
        for (CarAction action : mappedActions) {
            if (action == null) continue;
            for (MiHomeVoice.Command command : MiHomeVoice.Command.values()) {
                if (command.action == action) {
                    selected.add(command);
                    if (selected.size() >= maxCount) return List.copyOf(selected);
                }
            }
        }
        return List.copyOf(selected);
    }

    static List<MiHomeVoice.Command> allControlCommands() {
        List<MiHomeVoice.Command> result = new ArrayList<>();
        for (MiHomeVoice.Command command : MiHomeVoice.Command.values()) {
            if (command.action != null) result.add(command);
        }
        return result;
    }

    static Intent createIntent(Context context, MiHomeVoice.Command command, String token) {
        return new Intent(context, VoiceShortcutActivity.class)
                .setAction(ACTION_DISPATCH)
                .putExtra(EXTRA_COMMAND, command.code)
                .putExtra(EXTRA_TOKEN, token);
    }

    static ShortcutInfo buildShortcut(Context context, MiHomeVoice.Command command, String token) {
        return new ShortcutInfo.Builder(context, "voice_" + command.name().toLowerCase())
                .setShortLabel("银河" + command.label)
                .setLongLabel("智在银河：" + command.label)
                .setIcon(Icon.createWithResource(context, R.drawable.ic_garage))
                .setIntent(createIntent(context, command, token))
                .build();
    }

    static void syncDynamicShortcuts(Context context, Collection<CarAction> mappedActions, String token) {
        if (token == null || token.isEmpty()) return;
        ShortcutManager manager = context.getSystemService(ShortcutManager.class);
        if (manager == null) return;
        int limit = Math.min(4, manager.getMaxShortcutCountPerActivity());
        List<MiHomeVoice.Command> commands = selectDynamicCommands(mappedActions, limit);
        List<ShortcutInfo> shortcuts = new ArrayList<>(commands.size());
        for (MiHomeVoice.Command command : commands) {
            shortcuts.add(buildShortcut(context, command, token));
        }
        try {
            manager.setDynamicShortcuts(shortcuts);
        } catch (IllegalArgumentException | IllegalStateException failed) {
            Log.w(TAG, "Unable to sync dynamic voice shortcuts", failed);
        }
    }

    static boolean pinShortcut(Context context, MiHomeVoice.Command command, String token) {
        if (command == null || command.action == null || token == null || token.isEmpty()) return false;
        ShortcutManager manager = context.getSystemService(ShortcutManager.class);
        if (manager == null || !manager.isRequestPinShortcutSupported()) return false;
        try {
            return manager.requestPinShortcut(buildShortcut(context, command, token), null);
        } catch (IllegalArgumentException | IllegalStateException failed) {
            Log.w(TAG, "Unable to pin voice shortcut", failed);
            return false;
        }
    }
}
