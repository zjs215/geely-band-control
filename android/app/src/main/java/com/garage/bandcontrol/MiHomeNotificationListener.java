package com.garage.bandcontrol;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

public final class MiHomeNotificationListener extends NotificationListenerService {
    static boolean hasAccess(Context context) {
        return context.getSystemService(NotificationManager.class)
                .isNotificationListenerAccessGranted(new ComponentName(context, MiHomeNotificationListener.class));
    }

    @Override public void onListenerConnected() {
        app().setVoiceListenerConnected(true);
    }

    @Override public void onListenerDisconnected() {
        app().setVoiceListenerConnected(false);
    }

    @Override public void onDestroy() {
        app().setVoiceListenerConnected(false);
        super.onDestroy();
    }

    @Override public void onNotificationPosted(StatusBarNotification posted) {
        GarageApp app = app();
        MiHomeVoice.Source source = MiHomeVoice.sourceOf(posted.getPackageName());
        if (!app.voiceEnabled() || source == null) return;
        Notification notification = posted.getNotification();
        Bundle extras = notification.extras;
        if (extras == null || (notification.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;
        CharSequence text = extras.getCharSequence(Notification.EXTRA_TEXT);
        CharSequence bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
        CharSequence title = extras.getCharSequence(Notification.EXTRA_TITLE);
        MiHomeVoice.Match match = MiHomeVoice.match(text, bigText, title);
        if (match != null) {
            app.onVoiceNotification(source, posted.getKey(), posted.getPostTime(), match);
            return;
        }
        String hint = MiHomeVoice.unmatchedHint(text, bigText, title);
        if (!hint.isEmpty()) app.onUnmatchedVoiceNotification(source, hint);
    }

    private GarageApp app() { return (GarageApp) getApplication(); }
}
