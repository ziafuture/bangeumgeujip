package io.roadmoment.passed;

import android.media.AudioManager;
import android.media.ToneGenerator;

/** Short cues so the driver knows when the app is listening, without looking. */
final class Earcon {
    private static ToneGenerator tones;
    private Earcon() {}

    private static synchronized void play(int tone, int ms) {
        try {
            if (tones == null) tones = new ToneGenerator(AudioManager.STREAM_MUSIC, 60);
            boolean ok = tones.startTone(tone, ms);
            android.util.Log.i("BangeumEarcon", "tone " + tone + " started=" + ok);
        } catch (RuntimeException e) { android.util.Log.w("BangeumEarcon", "tone failed", e); } // the screen still shows the state
    }

    /** Mic opens for an answer. */
    static void open() { play(ToneGenerator.TONE_PROP_BEEP, 120); }

    /** Listening ended or the driver called it off. */
    static void close() { play(ToneGenerator.TONE_PROP_ACK, 120); }
}
