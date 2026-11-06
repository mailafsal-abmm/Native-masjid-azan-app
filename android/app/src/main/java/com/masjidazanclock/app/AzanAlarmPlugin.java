package com.masjidazanclock.app;

import android.Manifest;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * AzanAlarmPlugin
 * ----------------
 * Schedules exact, Doze-mode-resistant alarms for several kinds of events:
 * a "5 minutes before Azan" notification, the exact "azan time" event
 * (shows a notification AND starts AzanPlaybackService to play the actual
 * azan audio automatically — no tap required), a "5 minutes before Iqama"
 * notification, a plain Sunrise notification (no azan sound), and a weekly
 * Jumma reminder (no azan sound).
 *
 * This works fully offline: the JS side already computes prayer times
 * locally (no network needed), and AlarmManager delivers the wake-up
 * regardless of internet connectivity.
 */
@CapacitorPlugin(
    name = "AzanAlarm",
    permissions = {
        @Permission(strings = { Manifest.permission.POST_NOTIFICATIONS }, alias = "notifications")
    }
)
public class AzanAlarmPlugin extends Plugin {

    public static final String PREFS_NAME = "azan_alarm_prefs";
    public static final String KEY_SCHEDULE_JSON = "schedule_json";

    public static final int TYPE_NOTIFY5 = 0;
    public static final int TYPE_AZAN    = 1;
    public static final int TYPE_IQAMA5  = 2;
    public static final int TYPE_SUNRISE = 3;
    public static final int TYPE_JUMMA   = 4;
    public static final String KEY_SUNRISE_MS = "sunrise_ms";
    public static final String KEY_JUMMA_MS = "jumma_ms";
    public static final String KEY_SILENT_MODE = "silent_mode";
    public static final String KEY_VOLUME = "azan_volume";
    public static final String KEY_WINDOW_DAYS = "window_days";
    public static final int DEFAULT_WINDOW_DAYS = 14;

    public static final long DAY_MS  = 24L * 60 * 60 * 1000;
    public static final long WEEK_MS = 7L * DAY_MS;

    /**
     * How far (in whole multiples of stepMs) an event time must be pushed
     * forward so it lands strictly in the future. 0 if it already is.
     * Used to keep alarms alive when the app hasn't been opened for days.
     */
    static long shiftToFuture(long eventMs, long nowMs, long stepMs) {
        if (eventMs <= 0 || eventMs > nowMs) return 0L;
        return ((nowMs - eventMs) / stepMs + 1) * stepMs;
    }

    // Lets AzanPlaybackService (a completely separate class with no direct
    // access to the JS bridge) tell the app's UI when automatic playback
    // actually starts/stops — including when it finishes naturally on its
    // own. Without this, the app's Stop button has no way of knowing
    // playback ended, since native audio runs independently of the WebView.
    private static AzanAlarmPlugin instance;

    @Override
    public void load() {
        super.load();
        instance = this;
    }

    public static void notifyPlaybackState(boolean playing, String key) {
        if (instance == null) return; // app not open right now — nothing to notify
        JSObject data = new JSObject();
        data.put("playing", playing);
        data.put("key", key == null ? "" : key);
        instance.notifyListeners("azanPlaybackState", data);
    }

    @PluginMethod
    public void schedule(PluginCall call) {
        JSArray prayers = call.getArray("prayers");
        if (prayers == null) {
            call.reject("Missing 'prayers' array");
            return;
        }

        Context ctx = getContext();

        // Cancel everything previously scheduled before setting the new batch,
        // so stale alarms from an old schedule never fire.
        cancelAllKnown(ctx);

        JSONArray toPersist = new JSONArray();

        try {
            for (int i = 0; i < prayers.length(); i++) {
                JSONObject p = prayers.getJSONObject(i);
                String key       = p.getString("key");
                String nameEn    = p.optString("nameEn", key);
                long azanMs      = p.getLong("azanMs");
                long iqamaMs     = p.optLong("iqamaMs", 0L);
                long notify5Ms   = azanMs - 5 * 60 * 1000L;
                // Use the exact reminder time JS already calculated (it
                // correctly shortens the offset for prayers — like
                // Maghrib — where Azan-to-Iqama is under 5 minutes, so
                // this never lands before Azan itself). Falls back to a
                // flat 5-minute offset only if an older JS build didn't
                // send it.
                long iqama5Ms    = p.has("iqamaReminderMs")
                    ? p.getLong("iqamaReminderMs")
                    : (iqamaMs > 0 ? iqamaMs - 5 * 60 * 1000L : 0L);

                // Each entry is one calendar day of one prayer (or Sunrise).
                // "slot" is a unique name per entry (e.g. "Fajr_3") so that 14
                // days of the same prayer can exist as separate alarms; "key"
                // stays the plain prayer name — it still picks the audio file
                // and the notification text everywhere else.
                String slot  = p.optString("slot", key);
                int onlyType = p.optInt("onlyType", -1);

                if (onlyType == TYPE_SUNRISE) {
                    if (azanMs > System.currentTimeMillis()) {
                        scheduleOne(ctx, slot, key, nameEn, 0L, azanMs, TYPE_SUNRISE);
                    }
                } else {
                    if (notify5Ms > System.currentTimeMillis()) {
                        scheduleOne(ctx, slot, key, nameEn, iqamaMs, notify5Ms, TYPE_NOTIFY5);
                    }
                    if (azanMs > System.currentTimeMillis()) {
                        scheduleOne(ctx, slot, key, nameEn, iqamaMs, azanMs, TYPE_AZAN);
                    }
                    if (iqama5Ms > System.currentTimeMillis()) {
                        scheduleOne(ctx, slot, key, nameEn, iqamaMs, iqama5Ms, TYPE_IQAMA5);
                    }
                }

                JSONObject entry = new JSONObject();
                entry.put("key", key);
                entry.put("slot", slot);
                if (onlyType >= 0) entry.put("onlyType", onlyType);
                entry.put("nameEn", nameEn);
                entry.put("azanMs", azanMs);
                entry.put("iqamaMs", iqamaMs);
                entry.put("iqamaReminderMs", iqama5Ms);
                toPersist.put(entry);
            }

            // Persist so BootReceiver can re-schedule after a phone restart,
            // even before the app/WebView has been opened again.
            SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = prefs.edit();
            editor.putString(KEY_SCHEDULE_JSON, toPersist.toString());
            int windowDays = call.getData().optInt("windowDays", 0);
            if (windowDays > 0) editor.putInt(KEY_WINDOW_DAYS, windowDays);

            // Sunrise: a single, plain notification (no azan sound — sunrise
            // isn't a call to prayer). Kept separate from the prayers array
            // since it doesn't have Iqama/azan playback like a real prayer.
            long sunriseMs = call.getData().optLong("sunriseMs", 0L);
            if (sunriseMs > System.currentTimeMillis()) {
                scheduleOne(ctx, "Sunrise", "Sunrise", 0L, sunriseMs, TYPE_SUNRISE);
                editor.putLong(KEY_SUNRISE_MS, sunriseMs);
            } else {
                editor.remove(KEY_SUNRISE_MS);
            }

            // Weekly Jumma reminder: same plain-notification, no-azan-sound
            // treatment as Sunrise. JS recomputes "next Friday 12:30 PM"
            // every time this runs, so re-syncing (on app open, daily
            // rollover, etc.) naturally rolls it forward week to week —
            // no separate weekly-repeating alarm mechanism needed.
            long jummaMs = call.getData().optLong("jummaMs", 0L);
            if (jummaMs > System.currentTimeMillis()) {
                scheduleOne(ctx, "Jumma", "Jumma", 0L, jummaMs, TYPE_JUMMA);
                editor.putLong(KEY_JUMMA_MS, jummaMs);
            } else {
                editor.remove(KEY_JUMMA_MS);
            }

            // Emergency silent mode — persisted so AlarmReceiver/AzanPlaybackService
            // can read it at the moment an alarm actually fires (they run
            // independently of this call, possibly hours/days later).
            boolean silentMode = call.getData().optBoolean("silentMode", false);
            editor.putBoolean(KEY_SILENT_MODE, silentMode);

            // Azan volume, same reasoning — read fresh by AzanPlaybackService
            // at the moment playback actually starts, since that happens
            // independently of this call.
            float volume = (float) call.getData().optDouble("volume", 1.0);
            editor.putFloat(KEY_VOLUME, volume);

            editor.apply();

            JSObject result = new JSObject();
            result.put("scheduled", toPersist.length());
            call.resolve(result);
        } catch (JSONException e) {
            call.reject("Failed to schedule alarms: " + e.getMessage());
        }
    }

    static void scheduleOne(Context ctx, String key, String nameEn, long iqamaMs, long triggerAtMs, int type) {
        scheduleOne(ctx, key, key, nameEn, iqamaMs, triggerAtMs, type);
    }

    static void scheduleOne(Context ctx, String slot, String key, String nameEn, long iqamaMs, long triggerAtMs, int type) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        Intent intent = new Intent(ctx, AlarmReceiver.class);
        intent.putExtra("key", key);
        intent.putExtra("slot", slot);
        intent.putExtra("nameEn", nameEn);
        intent.putExtra("iqamaMs", iqamaMs);
        intent.putExtra("type", type);
        intent.putExtra("triggerAtMs", triggerAtMs);

        int requestCode = requestCodeFor(slot, type);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pi = PendingIntent.getBroadcast(ctx, requestCode, intent, flags);

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (am.canScheduleExactAlarms()) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi);
                } else {
                    // Exact-alarm permission not granted — fall back to an
                    // inexact-but-still-Doze-aware alarm rather than silently
                    // failing. Accuracy may drift by a few minutes in this case.
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi);
                }
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi);
            }
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi);
        }
    }

    static int requestCodeFor(String key, int type) {
        // Stable, unique per (key, type) so re-scheduling replaces the same
        // alarm slot instead of piling up duplicates.
        return (key.hashCode() & 0x00FFFFFF) * 10 + type;
    }

    static void cancelAllKnown(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        // Cancel the sunrise and Jumma alarms unconditionally — cheap no-op
        // if either was never scheduled. This is what prevents duplicate
        // Jumma reminders: every sync clears the old one before (maybe)
        // scheduling a new one, so there's never more than one pending.
        cancelOne(ctx, am, "Sunrise", TYPE_SUNRISE);
        cancelOne(ctx, am, "Jumma", TYPE_JUMMA);

        String json = prefs.getString(KEY_SCHEDULE_JSON, null);
        if (json == null) return;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject entry = arr.getJSONObject(i);
                String key  = entry.getString("key");
                String slot = entry.optString("slot", key);
                int onlyType = entry.optInt("onlyType", -1);
                if (onlyType >= 0) {
                    cancelOne(ctx, am, slot, onlyType);
                } else {
                    for (int type = 0; type <= 2; type++) {
                        cancelOne(ctx, am, slot, type);
                    }
                }
            }
        } catch (JSONException ignored) {}
    }

    static void cancelOne(Context ctx, AlarmManager am, String key, int type) {
        Intent intent = new Intent(ctx, AlarmReceiver.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pi = PendingIntent.getBroadcast(ctx, requestCodeFor(key, type), intent, flags);
        am.cancel(pi);
    }

    @PluginMethod
    public void cancel(PluginCall call) {
        cancelAllKnown(getContext());
        getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(KEY_SCHEDULE_JSON).apply();
        call.resolve();
    }

    @PluginMethod
    public void isExactAlarmAllowed(PluginCall call) {
        JSObject result = new JSObject();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AlarmManager am = (AlarmManager) getContext().getSystemService(Context.ALARM_SERVICE);
            result.put("allowed", am != null && am.canScheduleExactAlarms());
        } else {
            result.put("allowed", true);
        }
        call.resolve(result);
    }

    @PluginMethod
    public void openExactAlarmSettings(PluginCall call) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Intent intent = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM);
            intent.setData(Uri.parse("package:" + getContext().getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(intent);
        }
        call.resolve();
    }

    @PluginMethod
    public void isBatteryOptimizationIgnored(PluginCall call) {
        JSObject result = new JSObject();
        PowerManager pm = (PowerManager) getContext().getSystemService(Context.POWER_SERVICE);
        boolean ignored = pm != null && pm.isIgnoringBatteryOptimizations(getContext().getPackageName());
        result.put("ignored", ignored);
        call.resolve(result);
    }

    @PluginMethod
    public void openBatteryOptimizationSettings(PluginCall call) {
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + getContext().getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(intent);
        } catch (Exception e) {
            Intent intent = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(intent);
        }
        call.resolve();
    }

    @PluginMethod
    public void requestNotificationPermission(PluginCall call) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissionForAlias("notifications", call, "notificationPermCallback");
        } else {
            JSObject result = new JSObject();
            result.put("granted", true);
            call.resolve(result);
        }
    }

    @PermissionCallback
    private void notificationPermCallback(PluginCall call) {
        JSObject result = new JSObject();
        result.put("granted", getPermissionState("notifications").toString().equals("GRANTED"));
        call.resolve(result);
    }

    @PluginMethod
    public void stopAzan(PluginCall call) {
        // Direct in-process call — always works instantly, regardless of
        // whether the app is foreground/background, unlike sending a new
        // Intent via Context.startService() (which can silently throw a
        // background-start-restriction exception depending on exact app
        // state, causing the old "stop sometimes does nothing" bug).
        AzanPlaybackService.stopIfRunning();
        call.resolve();
    }

    // Lets the app directly ask "is azan actually playing right now?" —
    // used whenever the app opens or comes back to the foreground, as a
    // correction on top of the push-based azanPlaybackState events, so the
    // Stop button is always accurate even if a push event was ever missed
    // (e.g. the app wasn't open yet when playback started or stopped).
    @PluginMethod
    public void isAzanPlaying(PluginCall call) {
        JSObject result = new JSObject();
        result.put("playing", AzanPlaybackService.isCurrentlyPlaying());
        result.put("key", AzanPlaybackService.getCurrentPlayingKey() == null ? "" : AzanPlaybackService.getCurrentPlayingKey());
        call.resolve(result);
    }
}
