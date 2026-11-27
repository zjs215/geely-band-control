package com.otrong.carcontrol.phone;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.IBinder;

public final class BandService extends Service {
    private static final String CHANNEL = "band_connection";
    private static final String STOP = "com.garage.bandcontrol.STOP_BAND_SERVICE";
    private static final int NOTIFICATION_ID = 1;

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "穿戴设备通信", NotificationManager.IMPORTANCE_LOW));
        int pendingFlags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), pendingFlags);
        PendingIntent stop = PendingIntent.getService(this, 0,
                new Intent(this, BandService.class).setAction(STOP), pendingFlags);
        Notification notice = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_garage)
                .setContentTitle(getString(R.string.app_name))
                .setContentText("正在运行穿戴设备通信")
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_garage),
                        "停止通信", stop).build())
                .build();
        try {
            startForeground(NOTIFICATION_ID, notice);
            ((GarageApp) getApplication()).setService(true);
        } catch (SecurityException denied) {
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        ((GarageApp) getApplication()).setService(false);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }
}
