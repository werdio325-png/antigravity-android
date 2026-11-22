package com.agy.core;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import org.json.JSONObject;

import java.util.Locale;

/**
 * Foreground service keeping Antigravity Live Voice Call and Voice Dictation
 * alive in the background with a bounded partial wake lock, non-intrusive
 * gentle audio focus (yielding VoIP audio mode / routing to Chromium WebView's
 * AAudio MMAP stream), and notification shade Mute/Stop controls.
 */
public final class VoiceForegroundService extends Service {

    private static final String TAG = "VoiceForegroundService";

    public static final String CHANNEL_VOICE = "agy_voice";
    public static final int NOTIFICATION_ID = 1002;

    public static final String ACTION_VOICE_START = "com.agy.ACTION_VOICE_START";
    public static final String ACTION_VOICE_STOP = "com.agy.ACTION_VOICE_STOP";
    public static final String ACTION_VOICE_TOGGLE_MUTE = "com.agy.ACTION_VOICE_TOGGLE_MUTE";
    public static final String ACTION_VOICE_SET_MUTE = "com.agy.ACTION_VOICE_SET_MUTE";
    public static final String ACTION_VOICE_STATE_CHANGED = "com.agy.ACTION_VOICE_STATE_CHANGED";

    public static final String EXTRA_ACTIVE = "active";
    public static final String EXTRA_MUTED = "muted";
    public static final String EXTRA_STATUS_TEXT = "status_text";
    public static final String EXTRA_SPEAKERPHONE = "speakerphone";

    private static final String PERMISSION_INTERNAL_BROADCAST = "com.agy.permission.INTERNAL_BROADCAST";
    private static final String DEFAULT_STATUS_TEXT = "Voice session active";
    private static final long WAKE_LOCK_TIMEOUT_MS = 60L * 60L * 1000L;

    public interface StateListener {
        void onStateChanged(boolean active, boolean muted);
    }

    public static volatile boolean active = false;
    public static volatile boolean muted = false;
    private static volatile String currentStatusText = "";
    private static volatile StateListener stateListener;

    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    private NotificationManager notificationManager;
    private AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest;
    private PowerManager.WakeLock wakeLock;

    private boolean hasAudioFocus = false;
    private boolean sessionStarted = false;

    /**
     * Passive focus listener: never calls setMode/setMicrophoneMute/stopSelf on
     * focus transitions, because Chromium WebView requests its own in-process
     * audio focus for AudioContext / AAudio MMAP streams.
     */
    private final AudioManager.OnAudioFocusChangeListener focusChangeListener =
            new AudioManager.OnAudioFocusChangeListener() {
                @Override
                public void onAudioFocusChange(int focusChange) {
                    Log.d(TAG, "gentle audio focus change: " + focusChange);
                }
            };

    public static void setStateListener(StateListener listener) {
        stateListener = listener;
    }

    public static boolean isActive() {
        return active;
    }

    public static boolean isMuted() {
        return muted;
    }

    public static void start(Context context) {
        start(context, null, true);
    }

    public static void start(Context context, String statusText, boolean speakerphone) {
        if (context == null) {
            return;
        }
        Context appCtx = context.getApplicationContext();
        Intent intent = new Intent(appCtx, VoiceForegroundService.class);
        intent.setAction(ACTION_VOICE_START);
        if (statusText != null && !statusText.trim().isEmpty()) {
            intent.putExtra(EXTRA_STATUS_TEXT, statusText.trim());
        }
        intent.putExtra(EXTRA_SPEAKERPHONE, speakerphone);
        boolean started = false;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appCtx.startForegroundService(intent);
            } else {
                appCtx.startService(intent);
            }
            started = true;
        } catch (Throwable t) {
            Log.w(TAG, "startForegroundService failed, trying startService: " + t.getMessage());
            try {
                appCtx.startService(intent);
                started = true;
            } catch (Throwable inner) {
                Log.w(TAG, "failed starting VoiceForegroundService: " + inner.getMessage());
            }
        }
        if (started) {
            active = true;
            if (statusText != null && !statusText.trim().isEmpty()) {
                currentStatusText = statusText.trim();
            } else if (currentStatusText == null || currentStatusText.isEmpty()) {
                currentStatusText = DEFAULT_STATUS_TEXT;
            }
        }
    }

    public static void stop(Context context) {
        if (context == null) {
            return;
        }
        Context appCtx = context.getApplicationContext();
        boolean wasActive = active || muted;
        boolean stoppedService = false;
        try {
            stoppedService = appCtx.stopService(new Intent(appCtx, VoiceForegroundService.class));
        } catch (Throwable t) {
            Log.w(TAG, "stopService failed: " + t.getMessage());
        }
        active = false;
        muted = false;
        currentStatusText = "";
        if (!stoppedService && wasActive) {
            dispatchStateChanged(appCtx, false, false);
        }
    }

    public static void setMuted(Context context, boolean mute) {
        if (context == null) {
            return;
        }
        Context appCtx = context.getApplicationContext();
        boolean prevMuted = muted;
        muted = mute;
        if (active) {
            Intent intent = new Intent(appCtx, VoiceForegroundService.class);
            intent.setAction(ACTION_VOICE_SET_MUTE);
            intent.putExtra(EXTRA_MUTED, mute);
            try {
                appCtx.startService(intent);
            } catch (Throwable t) {
                Log.w(TAG, "setMuted service delivery failed: " + t.getMessage());
                dispatchStateChanged(appCtx, active, muted);
            }
        } else if (prevMuted != mute) {
            dispatchStateChanged(appCtx, false, mute);
        }
    }

    public static void toggleMute(Context context) {
        setMuted(context, !muted);
    }

    public static JSONObject getStatusJson() {
        JSONObject resp = new JSONObject();
        try {
            resp.put("ok", true);
            resp.put("active", active);
            resp.put("muted", muted);
            resp.put("status_text", currentStatusText != null ? currentStatusText : "");
        } catch (Exception ignored) {
        }
        return resp;
    }

    /** Handles GatewayDispatcher {@code command: "voice_service"}. */
    public static JSONObject handleCommand(Context context, JSONObject payload) {
        JSONObject resp = new JSONObject();
        try {
            String action = payload != null
                    ? payload.optString("action", "status").trim().toLowerCase(Locale.US)
                    : "status";
            if ("start".equals(action)) {
                String statusText = payload != null ? payload.optString("status_text", "").trim() : "";
                boolean speakerphone = payload == null || payload.optBoolean("speakerphone", true);
                muted = false;
                start(context, statusText.isEmpty() ? DEFAULT_STATUS_TEXT : statusText, speakerphone);
                return getStatusJson();
            } else if ("stop".equals(action)) {
                stop(context);
                return getStatusJson();
            } else if ("mute".equals(action)) {
                setMuted(context, true);
                return getStatusJson();
            } else if ("unmute".equals(action)) {
                setMuted(context, false);
                return getStatusJson();
            } else if ("toggle_mute".equals(action)) {
                toggleMute(context);
                return getStatusJson();
            } else if ("status".equals(action)) {
                return getStatusJson();
            } else {
                resp.put("ok", false);
                resp.put("error", "unknown voice_service action: " + action);
                return resp;
            }
        } catch (Exception e) {
            try {
                resp.put("ok", false);
                resp.put("error", e.getMessage());
            } catch (Exception ignored) {
            }
            return resp;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        ensureChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null && intent.getAction() != null
                ? intent.getAction()
                : ACTION_VOICE_START;

        if (ACTION_VOICE_STOP.equals(action)) {
            Log.i(TAG, "received ACTION_VOICE_STOP");
            if (!sessionStarted) {
                startForegroundSafe(buildNotification("Stopping voice session...", muted));
            }
            stopSessionAndSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_VOICE_TOGGLE_MUTE.equals(action)) {
            if (!sessionStarted) {
                startForegroundSafe(buildNotification(DEFAULT_STATUS_TEXT, muted));
                stopSessionAndSelf();
                return START_NOT_STICKY;
            }
            applyMuted(!muted);
            return START_NOT_STICKY;
        }

        if (ACTION_VOICE_SET_MUTE.equals(action)) {
            boolean targetMute = intent != null && intent.getBooleanExtra(EXTRA_MUTED, false);
            if (!sessionStarted) {
                muted = targetMute;
                dispatchStateChanged(this, false, targetMute);
                stopSelf();
                return START_NOT_STICKY;
            }
            applyMuted(targetMute);
            return START_NOT_STICKY;
        }

        // ACTION_VOICE_START (or default)
        String requestedStatus = intent != null ? intent.getStringExtra(EXTRA_STATUS_TEXT) : null;
        if (requestedStatus != null && !requestedStatus.trim().isEmpty()) {
            currentStatusText = requestedStatus.trim();
        } else if (currentStatusText == null || currentStatusText.isEmpty()) {
            currentStatusText = DEFAULT_STATUS_TEXT;
        }

        boolean newlyStarted = !sessionStarted;
        sessionStarted = true;
        active = true;

        startForegroundSafe(buildNotification(currentStatusText, muted));
        acquireWakeLock();
        configureGentleAudioFocus();

        if (newlyStarted) {
            recordVoiceEvent("voice.session.started", true, muted);
        }
        dispatchStateChanged(this, true, muted);
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        boolean wasStarted = sessionStarted || active;
        sessionStarted = false;
        active = false;
        muted = false;
        currentStatusText = "";

        releaseGentleAudioFocus();
        releaseWakeLock();

        try {
            stopForeground(true);
        } catch (Throwable ignored) {
        }
        if (notificationManager != null) {
            try {
                notificationManager.cancel(NOTIFICATION_ID);
            } catch (Throwable ignored) {
            }
        }
        if (wasStarted) {
            recordVoiceEvent("voice.session.stopped", false, false);
            dispatchStateChanged(this, false, false);
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void stopSessionAndSelf() {
        sessionStarted = false;
        active = false;
        muted = false;
        currentStatusText = "";
        releaseGentleAudioFocus();
        releaseWakeLock();
        try {
            stopForeground(true);
        } catch (Throwable ignored) {
        }
        recordVoiceEvent("voice.session.stopped", false, false);
        dispatchStateChanged(this, false, false);
        stopSelf();
    }

    private void applyMuted(boolean targetMuted) {
        muted = targetMuted;
        // Delegate live mic muting to WebView MediaStreamTrack via StateListener /
        // ACTION_VOICE_STATE_CHANGED instead of AudioManager.setMicrophoneMute() so
        // we never disrupt Chromium WebView's active AAudio MMAP capture stream.
        updateNotification();
        recordVoiceEvent("voice.mute.changed", active, muted);
        dispatchStateChanged(this, active, muted);
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && notificationManager != null) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_VOICE,
                    "Antigravity Voice Session",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Ongoing controls for active Antigravity voice sessions");
            channel.setShowBadge(false);
            notificationManager.createNotificationChannel(channel);
        }
    }

    private void startForegroundSafe(Notification notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            boolean hasRecordAudio = checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED;
            if (hasRecordAudio) {
                try {
                    startForeground(NOTIFICATION_ID, notification,
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
                    return;
                } catch (SecurityException | IllegalArgumentException e) {
                    Log.w(TAG, "startForeground(MICROPHONE) fell back: " + e.getMessage());
                }
            }
        }
        startForeground(NOTIFICATION_ID, notification);
    }

    private void updateNotification() {
        if (notificationManager == null || !active) {
            return;
        }
        try {
            String base = (currentStatusText == null || currentStatusText.isEmpty())
                    ? DEFAULT_STATUS_TEXT
                    : currentStatusText;
            notificationManager.notify(NOTIFICATION_ID, buildNotification(base, muted));
        } catch (Throwable t) {
            Log.w(TAG, "updateNotification failed: " + t.getMessage());
        }
    }

    private Notification buildNotification(String statusText, boolean isMuted) {
        int icon = getResources().getIdentifier("ic_notification", "drawable", getPackageName());
        if (icon == 0) {
            icon = android.R.drawable.ic_btn_speak_now;
        }

        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            piFlags |= PendingIntent.FLAG_IMMUTABLE;
        }

        Intent openApp = new Intent();
        openApp.setClassName(getPackageName(), "com.agy.ui.MainActivity");
        openApp.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentPi = PendingIntent.getActivity(this, 10020, openApp, piFlags);

        Intent muteIntent = new Intent(this, VoiceForegroundService.class);
        muteIntent.setAction(ACTION_VOICE_TOGGLE_MUTE);
        PendingIntent mutePi = PendingIntent.getService(this, 10021, muteIntent, piFlags);

        Intent stopIntent = new Intent(this, VoiceForegroundService.class);
        stopIntent.setAction(ACTION_VOICE_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 10022, stopIntent, piFlags);

        String body = isMuted ? "Microphone muted" : statusText;
        String muteLabel = isMuted ? "Unmute" : "Mute";

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_VOICE)
                : new Notification.Builder(this);

        builder.setContentTitle("Antigravity Voice Session")
                .setContentText(body)
                .setSmallIcon(icon)
                .setContentIntent(contentPi)
                .setOngoing(true)
                .setAutoCancel(false)
                .setCategory(Notification.CATEGORY_CALL)
                .addAction(new Notification.Action.Builder(0, muteLabel, mutePi).build())
                .addAction(new Notification.Action.Builder(0, "Stop Voice", stopPi).build());

        return builder.build();
    }

    private void acquireWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                return;
            }
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "agy::voice");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS);
            }
        } catch (Throwable t) {
            Log.w(TAG, "voice wakelock acquire failed: " + t.getMessage());
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Throwable t) {
            Log.w(TAG, "voice wakelock release failed: " + t.getMessage());
        }
        wakeLock = null;
    }

    @SuppressWarnings("deprecation")
    private void configureGentleAudioFocus() {
        if (audioManager == null || hasAudioFocus) {
            return;
        }
        try {
            // If Chromium WebView already entered MODE_IN_COMMUNICATION for its VoIP/AAudio
            // MMAP stream, do NOT call setMode(), setSpeakerphoneOn(), or request competing
            // audio focus — stealing route/focus invalidates WebView's AAudio MMAP stream.
            if (audioManager.getMode() == AudioManager.MODE_IN_COMMUNICATION) {
                Log.d(TAG, "WebView already owns MODE_IN_COMMUNICATION; skipping competing AudioFocus/mode changes");
                return;
            }

            int focusResult;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                AudioAttributes attrs = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build();
                audioFocusRequest = new AudioFocusRequest.Builder(
                        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                        .setAudioAttributes(attrs)
                        .setAcceptsDelayedFocusGain(false)
                        .setOnAudioFocusChangeListener(focusChangeListener)
                        .build();
                focusResult = audioManager.requestAudioFocus(audioFocusRequest);
            } else {
                focusResult = audioManager.requestAudioFocus(
                        focusChangeListener,
                        AudioManager.STREAM_MUSIC,
                        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
            }
            hasAudioFocus = (focusResult == AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
        } catch (Throwable t) {
            Log.w(TAG, "configureGentleAudioFocus failed: " + t.getMessage());
        }
    }

    @SuppressWarnings("deprecation")
    private void releaseGentleAudioFocus() {
        if (audioManager == null || !hasAudioFocus) {
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioFocusRequest != null) {
                audioManager.abandonAudioFocusRequest(audioFocusRequest);
            } else {
                audioManager.abandonAudioFocus(focusChangeListener);
            }
        } catch (Throwable t) {
            Log.w(TAG, "abandonAudioFocus failed: " + t.getMessage());
        } finally {
            hasAudioFocus = false;
            audioFocusRequest = null;
        }
    }

    private void recordVoiceEvent(String eventName, boolean isActive, boolean isMuted) {
        try {
            JSONObject data = new JSONObject();
            data.put("active", isActive);
            data.put("muted", isMuted);
            CoreEventWatcher.appendEventLine(getFilesDir(), eventName, "android:voice_service", data);
        } catch (Exception ignored) {
        }
    }

    private static void dispatchStateChanged(Context context, final boolean isActive, final boolean isMuted) {
        if (context != null) {
            try {
                Intent broadcast = new Intent(ACTION_VOICE_STATE_CHANGED);
                broadcast.setPackage(context.getPackageName());
                broadcast.putExtra(EXTRA_ACTIVE, isActive);
                broadcast.putExtra(EXTRA_MUTED, isMuted);
                context.sendBroadcast(broadcast, PERMISSION_INTERNAL_BROADCAST);
            } catch (Throwable t) {
                Log.d(TAG, "voice state broadcast failed: " + t.getMessage());
            }
        }
        final StateListener listener = stateListener;
        if (listener != null) {
            MAIN_HANDLER.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        listener.onStateChanged(isActive, isMuted);
                    } catch (Throwable t) {
                        Log.w(TAG, "voice state listener error: " + t.getMessage());
                    }
                }
            });
        }
    }
}
