package com.jaago.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import androidx.core.content.ContextCompat;

/** Called by Android at alarm time. Starts the ringing service. */
public class AlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        String alarm = intent.getStringExtra("alarm");
        if (alarm == null) return;
        Intent svc = new Intent(ctx, AlarmService.class);
        svc.setAction(AlarmService.ACTION_START);
        svc.putExtra("alarm", alarm);
        ContextCompat.startForegroundService(ctx, svc);
    }
}
