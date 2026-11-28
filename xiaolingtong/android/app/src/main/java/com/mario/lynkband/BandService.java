// Adapted for Wrist Lynk from zjs215/geely-band-control (Apache-2.0). See NOTICE.
package com.mario.lynkband;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.graphics.drawable.Icon;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

public final class BandService extends Service {
    private static final String CHANNEL = "band_connection";
    private static final int NOTIFICATION_ID = 1;

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        WristApp app = (WristApp) getApplication();
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "穿戴通信", NotificationManager.IMPORTANCE_LOW));
        int pendingFlags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), pendingFlags);
        Notification notice = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_sky)
                .setLargeIcon(Icon.createWithResource(this, R.mipmap.ic_phone_sky))
                .setContentTitle(getString(R.string.app_name))
                .setContentText("穿戴通信正在运行")
                .setContentIntent(open)
                .setOngoing(true)
                .build();
        try {
            startForeground(NOTIFICATION_ID, notice);
            app.setService(true);
        } catch (SecurityException denied) {
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override public void onDestroy() {
        ((WristApp) getApplication()).setService(false);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }
}
