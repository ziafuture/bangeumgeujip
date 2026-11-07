package io.roadmoment.passed;

import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;

/**
 * Energy-gated utterance cutter adapted from the 0.2 probe. Not a trained VAD.
 * Onset is reported in SystemClock.elapsedRealtime() so it shares a time base with the trail.
 */
final class Segmenter {
    interface Sink {
        /** @param voicedMs first to last sound in the cut, softer parts included; used to spot transcripts that lost words. */
        void utterance(byte[] pcm, long onsetElapsed, boolean manual, int voicedMs);
        /** A manual window (answer or button) closed without any speech. */
        default void silence() {}
    }
    private static final int BYTES_PER_SEC = 32000; // 16 kHz mono PCM16
    private final Sink sink;
    private final ArrayDeque<byte[]> lead = new ArrayDeque<>();
    private ByteArrayOutputStream speech;
    private int hot, quietBytes, lowBytes, maxBytes;
    private long spanStart, spanEnd, lastLoud;
    private boolean manual, voiced;
    private long onset;
    /**
     * Speech must stand this far above the running noise floor. A fixed -40 dBFS missed quiet voices in a quiet
     * cabin (ElevenLabs test, 2026-11-03) and would fire on road noise in a loud one.
     */
    static final double MARGIN_DB = 12, MIN_THRESHOLD_DB = -48, MAX_THRESHOLD_DB = -30;
    /** Softer sounds (a trailing "글쎄") still count toward how long someone spoke. Below MARGIN_DB, so the floor never does. */
    static final double SPAN_DB = 8;
    /**
     * Once speech has started it only counts as over when the level falls this far below the start threshold. An
     * elderly woman's voice reached the mic just 1-6 dB over the threshold and dipped under it mid-word, so a single
     * gate cut "아까 그 집 뭐였더라" to "뭐였더라" (ElevenLabs age test, 2026-11-03).
     */
    static final double HOLD_DB = 6;
    /** Or when it stays under the start threshold this long. Longer than a mid-word dip. */
    static final int LOW_END_MS = 1500;
    private double floorDb = -60;
    /**
     * The floor is a low percentile of the last few seconds, fed whether or not someone is speaking. It used to follow
     * the quietest chunks and stop while speech was open, so in a room at -41..-48 dB it settled near -59, the -47 dB
     * threshold sat inside the room noise, noise opened "speech" that ran to the 8 s cap, and real words were cut or
     * buried (emulator, 2026-11-07). A percentile ignores speech (a minority of chunks) and follows the real background.
     */
    static final int FLOOR_CHUNKS = 60, FLOOR_PERCENTILE = 20;
    private final double[] levels = new double[FLOOR_CHUNKS];
    private int levelCount, levelNext;

    double thresholdDb() { return Math.max(MIN_THRESHOLD_DB, Math.min(MAX_THRESHOLD_DB, floorDb + MARGIN_DB)); }

    private void trackFloor(double db) {
        levels[levelNext] = db; levelNext = (levelNext + 1) % FLOOR_CHUNKS; if (levelCount < FLOOR_CHUNKS) levelCount++;
        if (levelCount < 10) return; // keep the default until there is a second of audio
        double[] sorted = java.util.Arrays.copyOf(levels, levelCount); java.util.Arrays.sort(sorted);
        floorDb = sorted[levelCount * FLOOR_PERCENTILE / 100];
    }

    Segmenter(Sink sink) { this.sink = sink; }

    static final int MANUAL_MAX_SEC = 8;
    private int waitBytes;

    /**
     * Open a window regardless of level, e.g. after a question or a button press. It waits up to
     * {@code seconds} for speech to start; once someone speaks it runs until 0.8 s of quiet (max 8 s).
     */
    synchronized void arm(long nowElapsed, int seconds) {
        speech = new ByteArrayOutputStream(); hot = quietBytes = lowBytes = 0; manual = true; voiced = false;
        waitBytes = seconds * BYTES_PER_SEC; maxBytes = MANUAL_MAX_SEC * BYTES_PER_SEC; onset = nowElapsed; lead.clear(); spanStart = spanEnd = 0;
    }

    /** Someone is speaking right now (not an armed answer window). */
    synchronized boolean capturing() { return speech != null && voiced && !manual; }

    synchronized void reset() { speech = null; lead.clear(); hot = quietBytes = lowBytes = 0; }

    /** @param enabled false drops audio without buffering (parked, speaking, or system-silenced). */
    synchronized void accept(short[] buffer, int n, double db, long nowElapsed, boolean enabled) {
        if (!enabled && !manual) { reset(); return; }
        byte[] pcm = new byte[n * 2];
        for (int i = 0; i < n; i++) { pcm[i * 2] = (byte)buffer[i]; pcm[i * 2 + 1] = (byte)(buffer[i] >> 8); }
        trackFloor(db);
        double threshold = thresholdDb();
        if (speech == null) {
            lead.addLast(pcm); if (lead.size() > 3) lead.removeFirst();
            hot = db > threshold ? hot + 1 : 0;
            if (hot >= 2) {
                speech = new ByteArrayOutputStream(); manual = false; voiced = true; maxBytes = 8 * BYTES_PER_SEC; quietBytes = lowBytes = 0; spanStart = nowElapsed - 100; spanEnd = lastLoud = nowElapsed;
                onset = nowElapsed - 100L * (lead.size()); // chunks are 100 ms; lead holds pre-roll
                for (byte[] chunk : lead) speech.write(chunk, 0, chunk.length);
                lead.clear();
            }
            return;
        }
        speech.write(pcm, 0, pcm.length);
        if (db > threshold) { voiced = true; lastLoud = nowElapsed; }
        if (db > threshold - SPAN_DB) { if (spanStart == 0) spanStart = nowElapsed; spanEnd = nowElapsed; }
        quietBytes = db <= threshold - HOLD_DB ? quietBytes + pcm.length : 0;
        lowBytes = db <= threshold ? lowBytes + pcm.length : 0;
        // A manual window waits for speech; it only ends early on silence after something was said.
        boolean endOfSpeech = voiced && speech.size() > BYTES_PER_SEC / 2
                && (quietBytes >= BYTES_PER_SEC * 8 / 10 || lowBytes >= BYTES_PER_SEC / 1000 * LOW_END_MS);
        // Cut by the long-low rule, the tail was the room, not a soft word: it must not make "응" look long.
        if (endOfSpeech && quietBytes < BYTES_PER_SEC * 8 / 10) spanEnd = Math.min(spanEnd, lastLoud);
        boolean nobodySpoke = manual && !voiced && speech.size() >= waitBytes;
        if (nobodySpoke) { speech = null; hot = quietBytes = lowBytes = 0; manual = false; lead.clear(); sink.silence(); return; }
        if (speech.size() >= maxBytes || endOfSpeech) {
            byte[] u = speech.toByteArray(); boolean m = manual, v = voiced; long at = onset; int ms = spanStart == 0 ? 0 : (int)(spanEnd - spanStart) + 100;
            speech = null; hot = quietBytes = lowBytes = 0; manual = false; voiced = false; lead.clear();
            if (v) sink.utterance(u, at, m, ms); // never send pure silence: the STT engine can hang on it
        }
    }
}
