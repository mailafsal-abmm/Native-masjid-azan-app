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
 * Schedules exact, Doze-mode-resistant alarms for two kinds of events per
 * prayer: a "5 minutes before" notification, and the exact "azan time" event
 * (which shows a notification AND starts AzanPlaybackService to play the
 * actual azan audio automatically — no tap required).
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
                String key      = p.getString("key");
                String nameTm   = p.optString("nameTm", key);
                long azanMs     = p.getLong("azanMs");
                long notify5Ms  = azanMs - 5 * 60 * 1000L;

                if (notify5Ms > System.currentTimeMillis()) {
                    scheduleOne(ctx, key, nameTm, notify5Ms, TYPE_NOTIFY5);
                }
                if (azanMs > System.currentTimeMillis()) {
                    scheduleOne(ctx, key, nameTm, azanMs, TYPE_AZAN);
                }

                JSONObject entry = new JSONObject();
                entry.put("key", key);
                entry.put("nameTm", nameTm);
                entry.put("azanMs", azanMs);
                toPersist.put(entry);
            }

            // Persist so BootReceiver can re-schedule after a phone restart,
            // even before the app/WebView has been opened again.
            SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().putString(KEY_SCHEDULE_JSON, toPersist.toString()).apply();

            JSObject result = new JSObject();
            result.put("scheduled", toPersist.length());
            call.resolve(result);
        } catch (JSONException e) {
            call.reject("Failed to schedule alarms: " + e.getMessage());
        }
    }

    static void scheduleOne(Context ctx, String key, String nameTm, long triggerAtMs, int type) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        Intent intent = new Intent(ctx, AlarmReceiver.class);
        intent.putExtra("key", key);
        intent.putExtra("nameTm", nameTm);
        intent.putExtra("type", type);

        int requestCode = requestCodeFor(key, type);
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
        return (key.hashCode() & 0x00FFFFFF) * 10 + type;
    }

    static void cancelAllKnown(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String json = prefs.getString(KEY_SCHEDULE_JSON, null);
        if (json == null) return;
        try {
            JSONArray arr = new JSONArray(json);
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject entry = arr.getJSONObject(i);
                String key = entry.getString("key");
                for (int type = 0; type <= 1; type++) {
                    Intent intent = new Intent(ctx, AlarmReceiver.class);
                    int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        flags |= PendingIntent.FLAG_IMMUTABLE;
                    }
                    PendingIntent pi = PendingIntent.getBroadcast(ctx, requestCodeFor(key, type), intent, flags);
                    am.cancel(pi);
                }
            }
        } catch (JSONException ignored) {}
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
        Intent stopIntent = new Intent(getContext(), AzanPlaybackService.class);
        stopIntent.setAction(AzanPlaybackService.ACTION_STOP);
        getContext().startService(stopIntent);
        call.resolve();
    }
}
