package com.masjidazanclock.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Android clears all AlarmManager alarms on every reboot. This receiver
 * re-schedules everything from the last-known schedule (saved to
 * SharedPreferences by AzanAlarmPlugin) so alerts keep working after a
 * restart without needing the app to be opened again first. It also runs
 * after the app itself is updated, as a safety net.
 *
 * Saved alarms that are still in the future are restored as they are. Any
 * that are already in the past (the phone restarted days after the app was
 * last opened) are moved forward by whole look-ahead windows, keeping the
 * same clock time, instead of being dropped — so the azan never goes silent.
 * Exact times are recalculated the next time the app is opened.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
            && !"android.intent.action.QUICKBOOT_POWERON".equals(action)
            && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }

        SharedPreferences prefs = context.getSharedPreferences(AzanAlarmPlugin.PREFS_NAME, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();

        // Older single Sunrise alarm (before per-day alarms existed), if any.
        long sunriseMs = prefs.getLong(AzanAlarmPlugin.KEY_SUNRISE_MS, 0L);
        if (sunriseMs > 0) {
            sunriseMs += AzanAlarmPlugin.shiftToFuture(sunriseMs, now, AzanAlarmPlugin.DAY_MS);
            AzanAlarmPlugin.scheduleOne(context, "Sunrise", "Sunrise", 0L, sunriseMs, AzanAlarmPlugin.TYPE_SUNRISE);
        }

        // Weekly Jumma reminder (rolled forward by whole weeks if stale).
        long jummaMs = prefs.getLong(AzanAlarmPlugin.KEY_JUMMA_MS, 0L);
        if (jummaMs > 0) {
            jummaMs += AzanAlarmPlugin.shiftToFuture(jummaMs, now, AzanAlarmPlugin.WEEK_MS);
            AzanAlarmPlugin.scheduleOne(context, "Jumma", "Jumma", 0L, jummaMs, AzanAlarmPlugin.TYPE_JUMMA);
        }

        String json = prefs.getString(AzanAlarmPlugin.KEY_SCHEDULE_JSON, null);
        if (json == null) return;

        int windowDays = prefs.getInt(AzanAlarmPlugin.KEY_WINDOW_DAYS, AzanAlarmPlugin.DEFAULT_WINDOW_DAYS);
        long windowMs = Math.max(1, windowDays) * AzanAlarmPlugin.DAY_MS;

        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject entry = arr.getJSONObject(i);
                String key     = entry.getString("key");
                String slot    = entry.optString("slot", key);
                int onlyType   = entry.optInt("onlyType", -1);
                String nameEn  = entry.optString("nameEn", key);
                long azanMs    = entry.getLong("azanMs");
                long iqamaMs   = entry.optLong("iqamaMs", 0L);
                long iqama5Ms  = entry.has("iqamaReminderMs")
                    ? entry.getLong("iqamaReminderMs")
                    : (iqamaMs > 0 ? iqamaMs - 5 * 60 * 1000L : 0L);

                // Per-day alarms come back after a whole window; older
                // single alarms come back daily.
                long stepMs = !slot.equals(key) ? windowMs : AzanAlarmPlugin.DAY_MS;

                // Move the whole prayer (azan, 5-min reminder, iqama reminder,
                // iqama) forward together until its LAST event is in the future.
                long latest = onlyType >= 0 ? azanMs : Math.max(azanMs, iqamaMs);
                long shift = AzanAlarmPlugin.shiftToFuture(latest, now, stepMs);
                azanMs += shift;
                if (iqamaMs > 0) iqamaMs += shift;
                if (iqama5Ms > 0) iqama5Ms += shift;

                if (onlyType == AzanAlarmPlugin.TYPE_SUNRISE) {
                    if (azanMs > now) {
                        AzanAlarmPlugin.scheduleOne(context, slot, key, nameEn, 0L, azanMs, AzanAlarmPlugin.TYPE_SUNRISE);
                    }
                    continue;
                }

                long notify5Ms = azanMs - 5 * 60 * 1000L;
                if (notify5Ms > now) {
                    AzanAlarmPlugin.scheduleOne(context, slot, key, nameEn, iqamaMs, notify5Ms, AzanAlarmPlugin.TYPE_NOTIFY5);
                }
                if (azanMs > now) {
                    AzanAlarmPlugin.scheduleOne(context, slot, key, nameEn, iqamaMs, azanMs, AzanAlarmPlugin.TYPE_AZAN);
                }
                if (iqama5Ms > now) {
                    AzanAlarmPlugin.scheduleOne(context, slot, key, nameEn, iqamaMs, iqama5Ms, AzanAlarmPlugin.TYPE_IQAMA5);
                }
            }
        } catch (JSONException ignored) {}
    }
}
