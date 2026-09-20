package com.masjidazanclock.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.PowerManager;

import androidx.core.app.NotificationCompat;

/**
 * Fired by AlarmManager at the exact scheduled instant — works even if the
 * app was fully closed, the phone was in Doze mode, or there is no internet
 * connection at all, because AlarmManager is a pure OS-level mechanism.
 */
public class AlarmReceiver extends BroadcastReceiver {

    public static final String CHANNEL_ID_ALERTS = "prayer_alerts";
    public static final String CHANNEL_ID_AZAN   = "prayer_azan";

    @Override
    public void onReceive(Context context, Intent intent) {
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wakeLock = null;
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MasjidAzanClock:AlarmWakeLock");
            wakeLock.acquire(20 * 1000L);
        }

        try {
            String key    = intent.getStringExtra("key");
            String nameTm = intent.getStringExtra("nameTm");
            int type      = intent.getIntExtra("type", AzanAlarmPlugin.TYPE_NOTIFY5);

            ensureChannels(context);

            if (type == AzanAlarmPlugin.TYPE_NOTIFY5) {
                showNotify5(context, key, nameTm);
            } else {
                showAzanNotification(context, key, nameTm);
                Intent svc = new Intent(context, AzanPlaybackService.class);
                svc.setAction(AzanPlaybackService.ACTION_PLAY);
                svc.putExtra("key", key);
                svc.putExtra("nameTm", nameTm);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(svc);
                } else {
                    context.startService(svc);
                }
            }
        } finally {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        }
    }

    private void ensureChannels(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        if (nm.getNotificationChannel(CHANNEL_ID_ALERTS) == null) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID_ALERTS, "Prayer Reminders", NotificationManager.IMPORTANCE_HIGH
            );
            ch.setDescription("5-minutes-before prayer reminders");
            ch.enableVibration(true);
            nm.createNotificationChannel(ch);
        }
        if (nm.getNotificationChannel(CHANNEL_ID_AZAN) == null) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID_AZAN, "Azan Playing", NotificationManager.IMPORTANCE_HIGH
            );
            ch.setDescription("Shown while the azan is playing");
            ch.setSound(null, null);
            nm.createNotificationChannel(ch);
        }
    }

    private void showNotify5(Context context, String key, String nameTm) {
        Intent openIntent = new Intent(context, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(context, key.hashCode(), openIntent, flags);

        Notification notification = new NotificationCompat.Builder(context, CHANNEL_ID_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle("🕌 " + nameTm + " Azan in 5 minutes")
            .setContentText(nameTm + " prayer starts in 5 minutes")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build();

        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(("notify5_" + key).hashCode(), notification);
    }

    private void showAzanNotification(Context context, String key, String nameTm) {
        Intent openIntent = new Intent(context, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(context, key.hashCode() + 5000, openIntent, flags);

        Notification notification = new NotificationCompat.Builder(context, CHANNEL_ID_AZAN)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle("🕌 " + nameTm + " Prayer Time")
            .setContentText(nameTm + " azan is playing now")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build();

        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(("azan_" + key).hashCode(), notification);
    }
}
