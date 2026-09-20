package com.masjidazanclock.app;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

/**
 * Plays the azan audio automatically and fully in the foreground so Android
 * does not kill it partway through. This is what lets the azan "just play"
 * without the user needing to tap anything — the one thing a website/PWA
 * can never do, but a native foreground service can.
 */
public class AzanPlaybackService extends Service {

    public static final String ACTION_PLAY = "com.masjidazanclock.app.ACTION_PLAY";
    public static final String ACTION_STOP = "com.masjidazanclock.app.ACTION_STOP";
    private static final int NOTIF_ID = 991199;

    private MediaPlayer mediaPlayer;
    private PowerManager.WakeLock wakeLock;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;

        if (ACTION_STOP.equals(action)) {
            stopPlayback();
            return START_NOT_STICKY;
        }

        String key    = intent != null ? intent.getStringExtra("key") : "Dhuhr";
        String nameTm = intent != null ? intent.getStringExtra("nameTm") : "";

        startForeground(NOTIF_ID, buildNotification(nameTm));
        playAzan(key);

        return START_NOT_STICKY;
    }

    private void playAzan(String key) {
        stopPlaybackInternal();

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MasjidAzanClock:PlaybackWakeLock");
            wakeLock.acquire(6 * 60 * 1000L);
        }

        int resId = "Fajr".equals(key) ? R.raw.azan_fajr : R.raw.azan_normal;

        mediaPlayer = MediaPlayer.create(this, resId);
        if (mediaPlayer == null) {
            stopSelf();
            return;
        }

        AudioAttributes attrs = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build();
        mediaPlayer.setAudioAttributes(attrs);
        mediaPlayer.setLooping(false);
        mediaPlayer.setOnCompletionListener(mp -> stopPlayback());
        mediaPlayer.setOnErrorListener((mp, what, extra) -> {
            stopPlayback();
            return true;
        });

        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am != null) {
            int max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM);
            am.setStreamVolume(AudioManager.STREAM_ALARM, max, 0);
        }

        mediaPlayer.start();
    }

    private Notification buildNotification(String nameTm) {
        Intent stopIntent = new Intent(this, AzanPlaybackService.class);
        stopIntent.setAction(ACTION_STOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent stopPi = PendingIntent.getService(this, 0, stopIntent, flags);

        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPi = PendingIntent.getActivity(this, 1, openIntent, flags);

        return new NotificationCompat.Builder(this, AlarmReceiver.CHANNEL_ID_AZAN)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle("🕌 " + nameTm + " Azan")
            .setContentText("Playing now — tap STOP to silence")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .setContentIntent(openPi)
            .addAction(0, "STOP", stopPi)
            .build();
    }

    private void stopPlayback() {
        stopPlaybackInternal();
        stopForeground(true);
        stopSelf();
    }

    private void stopPlaybackInternal() {
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) mediaPlayer.stop();
                mediaPlayer.release();
            } catch (Exception ignored) {}
            mediaPlayer = null;
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            wakeLock = null;
        }
    }

    @Override
    public void onDestroy() {
        stopPlaybackInternal();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
