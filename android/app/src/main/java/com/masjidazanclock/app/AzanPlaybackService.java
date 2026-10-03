package com.masjidazanclock.app;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
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

    // A direct reference to the currently-running instance (if any), so the
    // Capacitor plugin can stop playback with a plain in-process method call
    // instead of sending a new Intent via Context.startService(). The
    // Intent route could silently fail with a background-start restriction
    // exception depending on the app's exact state when Stop was tapped —
    // this direct reference always works, regardless of app state.
    private static AzanPlaybackService runningInstance;
    private static String currentPlayingKey = null;

    public static void stopIfRunning() {
        if (runningInstance != null) {
            runningInstance.stopPlayback();
        }
    }

    // Lets the app ASK "is something playing right now, and which prayer?"
    // — needed for the case where the app is opened (e.g. by tapping the
    // notification) AFTER azan already started: the "started" event fired
    // before anyone was listening, so without this query the app would
    // never find out it's currently playing and the Stop button would
    // never appear until playback ends on its own.
    public static boolean isCurrentlyPlaying() {
        return runningInstance != null;
    }

    public static String getCurrentPlayingKey() {
        return currentPlayingKey;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;

        if (ACTION_STOP.equals(action)) {
            stopPlayback();
            return START_NOT_STICKY;
        }

        String key     = intent != null ? intent.getStringExtra("key") : "Dhuhr";
        String nameEn  = intent != null ? intent.getStringExtra("nameEn") : "";
        boolean silent = intent != null && intent.getBooleanExtra("silent", false);

        if (silent) {
            // Emergency silent mode: no audio, no ongoing "Stop" state —
            // just a quiet, dismissible notification so you still know the
            // azan time arrived, without any sound interrupting you.
            startForeground(NOTIF_ID, buildSilentNotification(nameEn));
            stopForeground(false); // detach immediately; notification stays, service ends
            stopSelf();
            return START_NOT_STICKY;
        }

        runningInstance = this;
        currentPlayingKey = key;
        startForeground(NOTIF_ID, buildNotification(nameEn));
        AzanAlarmPlugin.notifyPlaybackState(true, key); // tells the app UI "azan is now playing"
        playAzan(key);

        return START_NOT_STICKY;
    }

    private Notification buildSilentNotification(String nameEn) {
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent openPi = PendingIntent.getActivity(this, 2, openIntent, flags);

        return new NotificationCompat.Builder(this, AlarmReceiver.CHANNEL_ID_AZAN)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle("🔇 " + nameEn + " Azan time (Silent Mode)")
            .setContentText("Silent Mode is on — no sound played")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(openPi)
            .setSilent(true)
            .build();
    }

    private void playAzan(String key) {
        stopPlaybackInternal();

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MasjidAzanClock:PlaybackWakeLock");
            wakeLock.acquire(6 * 60 * 1000L); // safety cap: azan recordings are a few minutes long
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

        // Raise the alarm-stream volume so the azan is clearly audible even
        // if the media/alarm volume was left low.
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am != null) {
            int max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM);
            am.setStreamVolume(AudioManager.STREAM_ALARM, max, 0);
        }

        // Apply the app's own volume slider on top of that. The stream
        // being maxed out above just guarantees it's never silently muted
        // by a low phone alarm-volume setting — this is what actually
        // makes the in-app slider have a real effect on the azan's
        // loudness, which it never did before (previously the slider only
        // affected the manual "PLAY AZAN" test button, never the real
        // scheduled azan).
        SharedPreferences prefs = getSharedPreferences(AzanAlarmPlugin.PREFS_NAME, MODE_PRIVATE);
        float vol = prefs.getFloat(AzanAlarmPlugin.KEY_VOLUME, 1.0f);
        mediaPlayer.setVolume(vol, vol);

        mediaPlayer.start();
    }

    private Notification buildNotification(String nameEn) {
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
            .setContentTitle("⏹ " + nameEn + " Azan playing now")
            .setContentText("Tap STOP to silence")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .setContentIntent(openPi)
            .addAction(0, "Stop", stopPi)
            .build();
    }

    private void stopPlayback() {
        stopPlaybackInternal();
        stopForeground(true);
        stopSelf();
        if (runningInstance == this) runningInstance = null;
        currentPlayingKey = null;
        // Tells the app UI "azan has stopped" — covers every case the same
        // way: natural finish, tapped Stop in the notification, or tapped
        // Stop inside the app. This is the one place all three funnel
        // through, so the app and notification always end up in sync.
        AzanAlarmPlugin.notifyPlaybackState(false, null);
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
        if (runningInstance == this) runningInstance = null;
        currentPlayingKey = null;
        // Safety net in case the OS kills the service directly without
        // going through stopPlayback() — harmless to send this twice.
        AzanAlarmPlugin.notifyPlaybackState(false, null);
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
