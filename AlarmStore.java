package com.jaago.app;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Remembers scheduled alarms (so they survive a reboot) and the alarm that is ringing right now. */
final class AlarmStore {
    private static final String PREFS = "jaago_alarms";
    private static final String KEY_SCHEDULED = "scheduled";
    private static final String KEY_RINGING = "ringing";

    private AlarmStore() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static JSONArray getScheduled(Context ctx) {
        try {
            return new JSONArray(prefs(ctx).getString(KEY_SCHEDULED, "[]"));
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    static void setScheduled(Context ctx, JSONArray alarms) {
        prefs(ctx).edit().putString(KEY_SCHEDULED, alarms.toString()).apply();
    }

    static JSONObject getRinging(Context ctx) {
        String s = prefs(ctx).getString(KEY_RINGING, null);
        if (s == null) return null;
        try {
            return new JSONObject(s);
        } catch (JSONException e) {
            return null;
        }
    }

    static void setRinging(Context ctx, JSONObject alarm) {
        SharedPreferences.Editor e = prefs(ctx).edit();
        if (alarm == null) e.remove(KEY_RINGING);
        else e.putString(KEY_RINGING, alarm.toString());
        e.commit();
    }
}
