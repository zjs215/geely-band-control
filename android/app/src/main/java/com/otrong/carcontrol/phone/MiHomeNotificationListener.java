package com.otrong.carcontrol.phone;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

public class MiHomeNotificationListener extends NotificationListenerService {
    static boolean hasAccess(Context context) {
        return context.getSystemService(NotificationManager.class)
                .isNotificationListenerAccessGranted(new ComponentName(context, com.garage.bandcontrol.MiHomeNotificationListener.class));
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
        if (!app.voiceEnabled() || !MiHomeVoice.isSource(posted.getPackageName())) return;
        Notification notification = posted.getNotification();
        Bundle extras = notification.extras;
        if (extras == null || (notification.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;
        MiHomeVoice.Match match = MiHomeVoice.match(app.brand.namespace, extras.getCharSequence(Notification.EXTRA_TEXT),
                extras.getCharSequence(Notification.EXTRA_BIG_TEXT), extras.getCharSequence(Notification.EXTRA_TITLE));
        if (match != null) app.onVoiceNotification(posted.getKey(), posted.getPostTime(), match);
    }

    private GarageApp app() { return (GarageApp) getApplication(); }
}
