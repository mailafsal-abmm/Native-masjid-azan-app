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

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

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
        // Briefly wake the CPU so notification/service startup isn't cut off
        // mid-way if the screen is off and the device is dozing.
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wakeLock = null;
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MasjidAzanClock:AlarmWakeLock");
            wakeLock.acquire(20 * 1000L); // auto-releases after 20s as a safety net
        }

        try {
            String key    = intent.getStringExtra("key");
            String nameEn = intent.getStringExtra("nameEn");
            long iqamaMs  = intent.getLongExtra("iqamaMs", 0L);
            int type      = intent.getIntExtra("type", AzanAlarmPlugin.TYPE_NOTIFY5);

            ensureChannels(context);

            if (type == AzanAlarmPlugin.TYPE_NOTIFY5) {
                showNotify5(context, key, nameEn, iqamaMs);
            } else if (type == AzanAlarmPlugin.TYPE_IQAMA5) {
                showIqama5(context, key, nameEn);
            } else if (type == AzanAlarmPlugin.TYPE_SUNRISE) {
                showSunrise(context);
            } else {
                // Single notification for azan time: AzanPlaybackService posts
                // its own ongoing "Stop" notification the instant it starts
                // (see buildNotification() there) — no separate transient
                // "playing now" notification is shown alongside it anymore.
                Intent svc = new Intent(context, AzanPlaybackService.class);
                svc.setAction(AzanPlaybackService.ACTION_PLAY);
                svc.putExtra("key", key);
                svc.putExtra("nameEn", nameEn);
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
            ch.setSound(null, null); // the azan audio itself plays via the service, not the channel
            nm.createNotificationChannel(ch);
        }
    }

    private void showNotify5(Context context, String key, String nameEn, long iqamaMs) {
        Intent openIntent = new Intent(context, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(context, key.hashCode(), openIntent, flags);

        // The azan starts in 5 minutes, but the actual prayer (Iqama) is
        // usually later — showing its real time here avoids implying
        // prayer itself starts in 5 minutes, which isn't accurate.
        String body;
        if (iqamaMs > 0) {
            String iqamaTime = new SimpleDateFormat("h:mm a", Locale.ENGLISH).format(new Date(iqamaMs));
            body = nameEn + " Azan in 5 minutes · Iqama at " + iqamaTime;
        } else {
            body = nameEn + " Azan in 5 minutes";
        }

        Notification notification = new NotificationCompat.Builder(context, CHANNEL_ID_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle("🕌 " + nameEn + " Azan Starts in 5 minutes")
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build();

        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(("notify5_" + key).hashCode(), notification);
    }

    private void showIqama5(Context context, String key, String nameEn) {
        Intent openIntent = new Intent(context, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(context, key.hashCode() + 9000, openIntent, flags);

        Notification notification = new NotificationCompat.Builder(context, CHANNEL_ID_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle("🕌 " + nameEn + " Iqama starts in 5 minutes")
            .setContentText(nameEn + " Prayer starts in 5 minutes")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build();

        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(("iqama5_" + key).hashCode(), notification);
    }

    private void showSunrise(Context context) {
        // Plain notification only — no azan sound, since sunrise isn't a
        // call to prayer (it's actually when prayer becomes prohibited
        // until Dhuhr).
        Intent openIntent = new Intent(context, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(context, "Sunrise".hashCode(), openIntent, flags);

        Notification notification = new NotificationCompat.Builder(context, CHANNEL_ID_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle("🌅 Sunrise")
            .setContentText("Sunrise time now")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build();

        NotificationManager nm2 = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm2 != null) nm2.notify("sunrise".hashCode(), notification);
    }
}
