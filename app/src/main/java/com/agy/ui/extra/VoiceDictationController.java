package com.agy.ui.extra;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Pure Android SDK voice dictation controller backed by android.speech.SpeechRecognizer
 * with automatic RecognizerIntent activity fallback when on-device SpeechRecognizer
 * service binding is unavailable. Must be driven on the main thread.
 */
public final class VoiceDictationController {

    private static final String TAG = "VoiceDictation";

    public static final int REQUEST_VOICE_DICTATION = 2003;
    public static final int REQUEST_RECORD_AUDIO_DICTATION = 1004;

    public interface Listener {
        void onVoiceText(String text, boolean isFinal);
        void onDictationStateChanged(boolean listening, String error);
    }

    private final Activity activity;
    private final Listener listener;

    private SpeechRecognizer speechRecognizer;
    private boolean listening = false;
    private boolean pendingStartAfterPermission = false;

    public VoiceDictationController(Activity activity, Listener listener) {
        this.activity = activity;
        this.listener = listener;
    }

    public boolean isListening() {
        return listening;
    }

    public boolean start() {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            return false;
        }
        if (!hasRecordAudioPermission()) {
            pendingStartAfterPermission = true;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                activity.requestPermissions(
                        new String[]{Manifest.permission.RECORD_AUDIO},
                        REQUEST_RECORD_AUDIO_DICTATION);
                return true;
            }
            return false;
        }
        return startInternal();
    }

    public void onRecordAudioPermissionResult(boolean granted) {
        if (!pendingStartAfterPermission) {
            return;
        }
        pendingStartAfterPermission = false;
        if (granted) {
            startInternal();
        } else {
            listening = false;
            notifyState(false, "permission_denied");
        }
    }

    public boolean stop() {
        pendingStartAfterPermission = false;
        boolean wasListening = listening;
        listening = false;
        if (speechRecognizer != null) {
            try {
                speechRecognizer.stopListening();
            } catch (Throwable t) {
                try {
                    speechRecognizer.cancel();
                } catch (Throwable ignored) {
                }
            }
        }
        if (wasListening) {
            notifyState(false, null);
        }
        return true;
    }

    public boolean handleActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_VOICE_DICTATION) {
            return false;
        }
        listening = false;
        if (resultCode == Activity.RESULT_OK && data != null) {
            ArrayList<String> matches = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            String best = firstNonEmpty(matches);
            if (best != null && listener != null) {
                listener.onVoiceText(best, true);
            }
            notifyState(false, null);
        } else {
            notifyState(false, "cancelled");
        }
        return true;
    }

    public void destroy() {
        pendingStartAfterPermission = false;
        listening = false;
        if (speechRecognizer != null) {
            try {
                speechRecognizer.cancel();
            } catch (Throwable ignored) {
            }
            try {
                speechRecognizer.destroy();
            } catch (Throwable ignored) {
            }
            speechRecognizer = null;
        }
    }

    private boolean startInternal() {
        if (listening) {
            stop();
        }
        Intent intent = buildRecognizerIntent();
        if (SpeechRecognizer.isRecognitionAvailable(activity)) {
            try {
                ensureRecognizer();
                listening = true;
                notifyState(true, null);
                speechRecognizer.startListening(intent);
                return true;
            } catch (Throwable t) {
                Log.w(TAG, "SpeechRecognizer.startListening failed, trying intent fallback: " + t.getMessage());
                listening = false;
                destroyRecognizerQuietly();
            }
        }
        return startIntentFallback(intent);
    }

    private boolean startIntentFallback(Intent intent) {
        try {
            intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to Antigravity");
            listening = true;
            notifyState(true, null);
            activity.startActivityForResult(intent, REQUEST_VOICE_DICTATION);
            return true;
        } catch (ActivityNotFoundException e) {
            Log.w(TAG, "No speech recognition activity installed");
            listening = false;
            notifyState(false, "unavailable");
            return false;
        } catch (Throwable t) {
            Log.w(TAG, "RecognizerIntent fallback failed: " + t.getMessage());
            listening = false;
            notifyState(false, "unavailable");
            return false;
        }
    }

    private void ensureRecognizer() {
        if (speechRecognizer != null) {
            return;
        }
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(activity);
        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override
            public void onReadyForSpeech(Bundle params) {
                listening = true;
                notifyState(true, null);
            }

            @Override
            public void onBeginningOfSpeech() {
            }

            @Override
            public void onRmsChanged(float rmsdB) {
            }

            @Override
            public void onBufferReceived(byte[] buffer) {
            }

            @Override
            public void onEndOfSpeech() {
            }

            @Override
            public void onError(int error) {
                listening = false;
                String code = mapError(error);
                Log.w(TAG, "SpeechRecognizer error=" + error + " (" + code + ")");
                notifyState(false, code);
            }

            @Override
            public void onResults(Bundle results) {
                listening = false;
                String best = extractBest(results);
                if (best != null && listener != null) {
                    listener.onVoiceText(best, true);
                }
                notifyState(false, null);
            }

            @Override
            public void onPartialResults(Bundle partialResults) {
                String partial = extractBest(partialResults);
                if (partial != null && listener != null) {
                    listener.onVoiceText(partial, false);
                }
            }

            @Override
            public void onEvent(int eventType, Bundle params) {
            }
        });
    }

    private void destroyRecognizerQuietly() {
        if (speechRecognizer != null) {
            try {
                speechRecognizer.destroy();
            } catch (Throwable ignored) {
            }
            speechRecognizer = null;
        }
    }

    private Intent buildRecognizerIntent() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, activity.getPackageName());
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag());
        return intent;
    }

    private boolean hasRecordAudioPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private void notifyState(boolean isListening, String error) {
        if (listener != null) {
            listener.onDictationStateChanged(isListening, error);
        }
    }

    private static String extractBest(Bundle bundle) {
        if (bundle == null) {
            return null;
        }
        return firstNonEmpty(bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION));
    }

    private static String firstNonEmpty(ArrayList<String> list) {
        if (list == null) {
            return null;
        }
        for (String item : list) {
            if (item != null && !item.trim().isEmpty()) {
                return item.trim();
            }
        }
        return null;
    }

    private static String mapError(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_AUDIO:
                return "audio";
            case SpeechRecognizer.ERROR_CLIENT:
                return "client";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                return "permission_denied";
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                return "network";
            case SpeechRecognizer.ERROR_NO_MATCH:
                return "no_match";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                return "busy";
            case SpeechRecognizer.ERROR_SERVER:
                return "server";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                return "speech_timeout";
            default:
                return "error_" + error;
        }
    }
}
