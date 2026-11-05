package io.roadmoment.passed;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.util.Locale;
import ai.pleos.playground.tts.TextToSpeech;
import ai.pleos.playground.tts.constant.ErrorType;
import ai.pleos.playground.tts.constant.Mode;
import ai.pleos.playground.tts.listener.EventListener;
import ai.pleos.playground.tts.listener.OnInitializeListener;

/** Pleos on-device TTS with a completion callback. Falls back to an estimated delay when TTS is unavailable. */
final class Voice {
    private static final String TAG = "BangeumVoice";
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextToSpeech engine;
    private boolean ready;
    private Runnable pending;
    volatile boolean speaking;

    private final EventListener events = new EventListener() {
        @Override public void onStart() {}
        @Override public void onReady() {}
        @Override public void onDone() { Log.i(TAG, "tts done"); main.post(Voice.this::done); }
        @Override public void onStop() { main.post(Voice.this::done); }
        @Override public void onError(ErrorType e) { Log.w(TAG, "tts error " + e); main.post(Voice.this::done); }
        @Override public void onUpdatedRms(double rms) {}
    };

    Voice(Context context) {
        try {
            engine = new TextToSpeech(context, Mode.ON_DEVICE);
            // The SDK binds its service in initialize(); listeners can only be attached after that succeeds.
            engine.initialize(new OnInitializeListener() {
                @Override public void onSuccess() { main.post(() -> {
                    try {
                        engine.addEventListener(events);
                        engine.setLocale(Locale.KOREA); ready = true; Log.i(TAG, "tts ready");
                    } catch (Exception | LinkageError e) { Log.w(TAG, "tts setup failed", e); }
                }); }
                @Override public void onFailure(ErrorType e) { ready = false; Log.w(TAG, "tts init failed " + e); }
            });
        } catch (Exception | LinkageError e) { engine = null; Log.w(TAG, "tts unavailable", e); }
    }

    /** Speaks on the main thread; then runs after playback ends (or a timeout). */
    void say(String text, Runnable then) { main.post(() -> {
        speaking = true; pending = then;
        long guard = 1500 + text.length() * 180L;
        if (engine != null && ready) {
            try { engine.speak(text, true); Log.i(TAG, "speak " + text.length() + " chars" + (BuildConfig.DEBUG ? ": " + text : "")); }
            catch (Exception | LinkageError e) { guard = 600 + text.length() * 120L; Log.w(TAG, "speak failed", e); }
        } else { // no TTS: keep the flow going, text is shown in the UI
            guard = 600 + text.length() * 120L; Log.w(TAG, "speak skipped, tts not ready");
        }
        final Runnable mine = then;
        main.postDelayed(() -> { if (pending == mine) done(); }, guard);
    }); }

    private void done() {
        if (!speaking) return;
        speaking = false; Runnable r = pending; pending = null;
        // Let the cabin echo fade before reopening the microphone.
        if (r != null) main.postDelayed(r, 300);
    }

    void close() { main.post(() -> { if (engine != null) try { engine.stop(); engine.release(); } catch (Exception | LinkageError ignored) {} }); }
}
