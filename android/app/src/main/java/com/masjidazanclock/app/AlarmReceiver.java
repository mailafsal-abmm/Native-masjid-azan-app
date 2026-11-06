package com.masjidazanclock.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
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

            // First thing, before anything below can possibly fail: queue the
            // NEXT occurrence of this same alarm. Without this, every alarm
            // was one-shot — once it fired, nothing scheduled tomorrow's, so
            // the azan silently stopped whenever the app hadn't been opened
            // for a day or more (the app only re-schedules when opened).
            rearmNext(context, intent, key, nameEn, iqamaMs, type);

            SharedPreferences prefs = context.getSharedPreferences(AzanAlarmPlugin.PREFS_NAME, Context.MODE_PRIVATE);
            boolean silent = prefs.getBoolean(AzanAlarmPlugin.KEY_SILENT_MODE, false);

            ensureChannels(context);

            if (type == AzanAlarmPlugin.TYPE_NOTIFY5) {
                showNotify5(context, key, nameEn, iqamaMs, silent);
            } else if (type == AzanAlarmPlugin.TYPE_IQAMA5) {
                showIqama5(context, key, nameEn, silent);
            } else if (type == AzanAlarmPlugin.TYPE_SUNRISE) {
                showSunrise(context, silent);
            } else if (type == AzanAlarmPlugin.TYPE_JUMMA) {
                showJumma(context, silent);
            } else {
                // Single notification for azan time: AzanPlaybackService posts
                // its own ongoing "Stop" notification the instant it starts
                // (see buildNotification() there) — no separate transient
                // "playing now" notification is shown alongside it anymore.
                Intent svc = new Intent(context, AzanPlaybackService.class);
                svc.setAction(AzanPlaybackService.ACTION_PLAY);
                svc.putExtra("key", key);
                svc.putExtra("nameEn", nameEn);
                svc.putExtra("silent", silent);
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(svc);
                    } else {
                        context.startService(svc);
                    }
                } catch (Exception e) {
                    // Android refused to start the player from the background
                    // (some phones/battery modes do). Don't fail silently —
                    // at least post a visible notification for this prayer.
                    showAzanFallback(context, key, nameEn);
                }
            }
        } finally {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        }
    }

    /**
     * Schedules the same alarm again for its next occurrence: +1 day (+7 for
     * the weekly Jumma reminder), keeping the same clock time. The exact,
     * freshly calculated prayer times are restored every time the app is
     * opened; until then, this keeps the alarms going on their last-known
     * times (prayer times move only a few seconds per day, so any drift while
     * the app stays closed is small). Same request code as the original, so
     * it simply replaces it and can never produce a duplicate.
     */
    private void rearmNext(Context context, Intent intent, String key, String nameEn, long iqamaMs, int type) {
        try {
            long trig = intent.getLongExtra("triggerAtMs", 0L);
            if (trig <= 0 || key == null) return;
            String slot = intent.getStringExtra("slot");
            if (slot == null) slot = key;

            long step;
            if (type == AzanAlarmPlugin.TYPE_JUMMA) {
                step = AzanAlarmPlugin.WEEK_MS;               // weekly reminder
            } else if (!slot.equals(key)) {
                // One of the multi-day alarms ("Fajr_3" ...): come back after
                // the whole look-ahead window, so the days stay covered.
                SharedPreferences prefs = context.getSharedPreferences(AzanAlarmPlugin.PREFS_NAME, Context.MODE_PRIVATE);
                int days = prefs.getInt(AzanAlarmPlugin.KEY_WINDOW_DAYS, AzanAlarmPlugin.DEFAULT_WINDOW_DAYS);
                step = Math.max(1, days) * AzanAlarmPlugin.DAY_MS;
            } else {
                step = AzanAlarmPlugin.DAY_MS;                // older single alarm
            }

            long now = System.currentTimeMillis();
            long shift = step + AzanAlarmPlugin.shiftToFuture(trig + step, now, step);
            long nextTrigger = trig + shift;
            long nextIqama = iqamaMs > 0 ? iqamaMs + shift : 0L;
            AzanAlarmPlugin.scheduleOne(context, slot, key, nameEn, nextIqama, nextTrigger, type);
        } catch (Exception ignored) {
            // never let a re-arm problem stop this alarm's own notification/azan
        }
    }

    private void showAzanFallback(Context context, String key, String nameEn) {
        try {
            Intent openIntent = new Intent(context, MainActivity.class);
            openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi = PendingIntent.getActivity(context, key.hashCode() + 5000, openIntent, flags);
            Notification n = new NotificationCompat.Builder(context, CHANNEL_ID_ALERTS)
                .setSmallIcon(R.drawable.ic_stat_notify)
                .setContentTitle("🕌 " + nameEn + " Azan time")
                .setContentText("Open the app to play the azan")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build();
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(("azanfallback_" + key).hashCode(), n);
        } catch (Exception ignored) {}
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

    private void showNotify5(Context context, String key, String nameEn, long iqamaMs, boolean silent) {
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
            body = "Iqama at " + iqamaTime;
        } else {
            body = nameEn + " Azan starts in 5 mins";
        }

        Notification notification = new NotificationCompat.Builder(context, CHANNEL_ID_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle("🕌 Inshaa Allah - " + nameEn + " Azan starts in 5 mins")
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setSilent(silent)
            .build();

        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(("notify5_" + key).hashCode(), notification);
    }

    private void showIqama5(Context context, String key, String nameEn, boolean silent) {
        Intent openIntent = new Intent(context, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(context, key.hashCode() + 9000, openIntent, flags);

        Notification notification = new NotificationCompat.Builder(context, CHANNEL_ID_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle("🕌 Inshaa allah - " + nameEn + " Iqama starts in 5 minutes")
            .setContentText(nameEn + " Prayer starts in 5 minutes")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setSilent(silent)
            .build();

        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(("iqama5_" + key).hashCode(), notification);
    }

    private void showSunrise(Context context, boolean silent) {
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
            .setContentTitle("🌅 Sunrise — Alhamdulillah")
            .setContentText("Sunrise is now")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setSilent(silent)
            .build();

        NotificationManager nm2 = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm2 != null) nm2.notify("sunrise".hashCode(), notification);
    }

    private void showJumma(Context context, boolean silent) {
        // Plain notification only — no azan sound, as requested.
        Intent openIntent = new Intent(context, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(context, "Jumma".hashCode(), openIntent, flags);

        Notification notification = new NotificationCompat.Builder(context, CHANNEL_ID_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle("🕌 Jumma reminder — Alhamdulillah")
            .setContentText("Noon - Inshaa Allah Ready for Jumma")
            .setStyle(new NotificationCompat.BigTextStyle().bigText("Noon - Inshaa Allah Ready for Jumma"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setSilent(silent)
            .build();

        NotificationManager nm3 = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm3 != null) nm3.notify("jumma".hashCode(), notification);
    }
}
