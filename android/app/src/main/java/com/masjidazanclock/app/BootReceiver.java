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
 * restart without needing the app to be opened again first.
 *
 * Only re-schedules times that are still in the future — a past prayer time
 * from before the reboot is simply skipped, it won't fire retroactively.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
            && !"android.intent.action.QUICKBOOT_POWERON".equals(intent.getAction())) {
            return;
        }

        SharedPreferences prefs = context.getSharedPreferences(AzanAlarmPlugin.PREFS_NAME, Context.MODE_PRIVATE);
        String json = prefs.getString(AzanAlarmPlugin.KEY_SCHEDULE_JSON, null);
        if (json == null) return;

        try {
            JSONArray arr = new JSONArray(json);
            long now = System.currentTimeMillis();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject entry = arr.getJSONObject(i);
                String key     = entry.getString("key");
                String nameEn  = entry.optString("nameEn", key);
                long azanMs    = entry.getLong("azanMs");
                long iqamaMs   = entry.optLong("iqamaMs", 0L);
                long notify5Ms = azanMs - 5 * 60 * 1000L;
                long iqama5Ms  = iqamaMs > 0 ? iqamaMs - 5 * 60 * 1000L : 0L;

                if (notify5Ms > now) {
                    AzanAlarmPlugin.scheduleOne(context, key, nameEn, iqamaMs, notify5Ms, AzanAlarmPlugin.TYPE_NOTIFY5);
                }
                if (azanMs > now) {
                    AzanAlarmPlugin.scheduleOne(context, key, nameEn, iqamaMs, azanMs, AzanAlarmPlugin.TYPE_AZAN);
                }
                if (iqama5Ms > now) {
                    AzanAlarmPlugin.scheduleOne(context, key, nameEn, iqamaMs, iqama5Ms, AzanAlarmPlugin.TYPE_IQAMA5);
                }
            }
        } catch (JSONException ignored) {}
    }
}
