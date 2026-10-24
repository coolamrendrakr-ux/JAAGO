package com.jaago.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import androidx.core.app.NotificationCompat;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Plays the alarm sound and vibration in the foreground, and shows a full-screen
 * notification that opens Jaago over the lock screen. It keeps ringing until the
 * wake mission is completed in the app, or for 10 minutes at most.
 */
public class AlarmService extends Service {
    static final String ACTION_START = "com.jaago.app.START";
    static final String ACTION_STOP = "com.jaago.app.STOP";
    static final String ACTION_PAUSE = "com.jaago.app.PAUSE";
    static final String ACTION_RESUME = "com.jaago.app.RESUME";
    static final String EXTRA_RINGING = "jaago_ringing";

    private static final String CHANNEL_ID = "jaago_ringing_v1";
    private static final int NOTIFICATION_ID = 4201;
    private static final long MAX_RING_MS = 10 * 60 * 1000L;

    interface Listener {
        void onRinging(JSONObject alarm);
        void onStopped();
    }

    static volatile Listener listener;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private MediaPlayer player;
    private Vibrator vibrator;
    private PowerManager.WakeLock wakeLock;
    private float volume = 0.3f;
    private boolean paused = false;

    private final Runnable timeout = this::stopRinging;
    private final Runnable ramp = new Runnable() {
        @Override
        public void run() {
            if (player != null && !paused && volume < 1f) {
                volume = Math.min(1f, volume + 0.1f);
                try {
                    player.setVolume(volume, volume);
                } catch (IllegalStateException ignored) {
                }
            }
            if (volume < 1f) handler.postDelayed(this, 3000);
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;

        if (ACTION_STOP.equals(action)) {
            stopRinging();
            return START_NOT_STICKY;
        }
        if (ACTION_PAUSE.equals(action)) {
            paused = true;
            if (player != null && player.isPlaying()) player.pause();
            if (vibrator != null) vibrator.cancel();
            if (player == null) stopSelfIfIdle();
            return START_NOT_STICKY;
        }
        if (ACTION_RESUME.equals(action)) {
            paused = false;
            if (player != null && !player.isPlaying()) player.start();
            if (player != null) startVibration();
            if (player == null) stopSelfIfIdle();
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(action)) {
            stopSelfIfIdle();
            return START_NOT_STICKY;
        }

        JSONObject alarm;
        try {
            alarm = new JSONObject(intent.getStringExtra("alarm"));
        } catch (JSONException | NullPointerException e) {
            stopSelf();
            return START_NOT_STICKY;
        }

        // A second alarm firing while one rings simply takes over.
        releaseSound();
        AlarmStore.setRinging(this, alarm);
        removeFromSchedule(alarm.optInt("id"));

        createChannel();
        Notification n = buildNotification(alarm);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }

        acquireWakeLock();
        startSound();
        startVibration();
        handler.removeCallbacks(timeout);
        handler.postDelayed(timeout, MAX_RING_MS);

        Listener l = listener;
        if (l != null) l.onRinging(alarm);
        return START_NOT_STICKY;
    }

    private void removeFromSchedule(int id) {
        org.json.JSONArray all = AlarmStore.getScheduled(this);
        org.json.JSONArray kept = new org.json.JSONArray();
        for (int i = 0; i < all.length(); i++) {
            JSONObject o = all.optJSONObject(i);
            if (o != null && o.optInt("id") != id) kept.put(o);
        }
        AlarmStore.setScheduled(this, kept);
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Ringing alarms", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Shows the alarm screen when an alarm rings");
        ch.setSound(null, null); // sound is played by this service on the alarm stream
        ch.enableVibration(false);
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotification(JSONObject alarm) {
        Intent open = new Intent(this, MainActivity.class);
        open.putExtra(EXTRA_RINGING, true);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent full = PendingIntent.getActivity(
            this, 2, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String label = alarm.optString("label", "Alarm");
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(label)
            .setContentText("Tap to open Jaago and stop the alarm")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(full)
            .setFullScreenIntent(full, true)
            .build();
    }

    private void startSound() {
        Uri uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM);
        if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
        if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        paused = false;
        volume = 0.3f;
        try {
            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build());
            player.setDataSource(this, uri);
            player.setLooping(true);
            player.prepare();
            player.setVolume(volume, volume);
            player.start();
            handler.postDelayed(ramp, 3000);
        } catch (Exception e) {
            releasePlayer();
        }
    }

    private void startVibration() {
        if (vibrator == null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                VibratorManager vm = (VibratorManager) getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                vibrator = vm.getDefaultVibrator();
            } else {
                vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            }
        }
        if (vibrator == null || !vibrator.hasVibrator()) return;
        long[] pattern = {0, 800, 700};
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0));
        } else {
            vibrator.vibrate(pattern, 0);
        }
    }

    private void acquireWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) return;
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jaago:ringing");
        wakeLock.acquire(MAX_RING_MS + 5000);
    }

    private void releasePlayer() {
        if (player != null) {
            try {
                player.stop();
            } catch (IllegalStateException ignored) {
            }
            player.release();
            player = null;
        }
    }

    private void releaseSound() {
        handler.removeCallbacks(ramp);
        releasePlayer();
        if (vibrator != null) vibrator.cancel();
    }

    private void stopSelfIfIdle() {
        if (player == null && AlarmStore.getRinging(this) == null) stopSelf();
    }

    void stopRinging() {
        handler.removeCallbacksAndMessages(null);
        releaseSound();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
        boolean wasRinging = AlarmStore.getRinging(this) != null;
        AlarmStore.setRinging(this, null);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
        stopSelf();
        Listener l = listener;
        if (wasRinging && l != null) l.onStopped();
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        releaseSound();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }
}
