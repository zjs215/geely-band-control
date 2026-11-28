package com.mario.lynkband;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.graphics.drawable.Icon;
import android.content.ComponentName;
import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

public final class MiHomeScenesListener extends NotificationListenerService {
    private static final String CHANNEL = "voice_scenes";
    private long connectedSince;

    static boolean access(Context context) {
        return context.getSystemService(NotificationManager.class).isNotificationListenerAccessGranted(
                new ComponentName(context, MiHomeScenesListener.class));
    }
    @Override public void onListenerConnected() {
        connectedSince = System.currentTimeMillis();
        ((WristApp) getApplication()).changed();
    }
    @Override public void onListenerDisconnected() {
        connectedSince = 0;
        WristApp app = (WristApp) getApplication();
        app.resetVoice();
        app.changed();
    }
    @Override public void onNotificationPosted(StatusBarNotification notice) {
        if (connectedSince == 0 || !VoiceScenes.PACKAGE.equals(notice.getPackageName())
                || !android.os.Process.myUserHandle().equals(notice.getUser())
                || notice.getPostTime() < connectedSince) return;
        WristApp app = (WristApp) getApplication();
        if (!app.voiceEnabled()) return;
        Bundle fields = notice.getNotification().extras;
        if (fields == null) return;
        String code = VoiceScenes.command(fields.getCharSequence(Notification.EXTRA_TEXT),
                fields.getCharSequence(Notification.EXTRA_BIG_TEXT), fields.getCharSequence(Notification.EXTRA_TITLE));
        String result = app.voice.receive(code, notice.getKey(), notice.getPostTime(),
                System.currentTimeMillis(), SystemClock.elapsedRealtime(), app.voiceConfirmation(), app.voiceCard);
        if (result.isEmpty()) return;
        app.last = result;
        app.changed();
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "语音车控回执", NotificationManager.IMPORTANCE_DEFAULT));
        manager.notify(32, new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_stat_sky)
                .setLargeIcon(Icon.createWithResource(this, R.mipmap.ic_phone_sky))
                .setContentTitle("领克语音车控").setContentText(result)
                .setStyle(new Notification.BigTextStyle().bigText(result))
                .setVisibility(Notification.VISIBILITY_PRIVATE).setTimeoutAfter(15000).setAutoCancel(true).build());
    }
}
