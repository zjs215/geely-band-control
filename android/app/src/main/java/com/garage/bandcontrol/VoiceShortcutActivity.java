package com.garage.bandcontrol;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

/**
 * Lightweight trampoline Activity invoked by system App Shortcuts / voice shortcuts.
 * Requires a valid per-install token so third-party apps cannot forge control intents.
 */
public final class VoiceShortcutActivity extends Activity {
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        handleIntent(getIntent());
        finish();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
        finish();
    }

    private void handleIntent(Intent intent) {
        GarageApp app = (GarageApp) getApplication();
        if (intent == null) return;
        MiHomeVoice.Command command = VoiceShortcuts.resolveShortcutCommand(
                intent.getAction(),
                app.shortcutToken(),
                intent.getStringExtra(VoiceShortcuts.EXTRA_TOKEN),
                intent.getStringExtra(VoiceShortcuts.EXTRA_COMMAND));
        if (command == null) {
            Toast.makeText(this, "快捷方式凭证已失效，请在智在银河重新添加", Toast.LENGTH_SHORT).show();
            return;
        }
        String result = app.onVoiceShortcut(command);
        Toast.makeText(this, result, Toast.LENGTH_SHORT).show();
    }
}
