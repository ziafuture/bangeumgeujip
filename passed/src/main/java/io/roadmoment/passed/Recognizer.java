package io.roadmoment.passed;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.util.Locale;
import ai.pleos.playground.stt.SpeechToText;
import ai.pleos.playground.stt.constant.ErrorType;
import ai.pleos.playground.stt.constant.Mode;
import ai.pleos.playground.stt.listener.OnInitializeListener;
import ai.pleos.playground.stt.listener.ResultListener;

/**
 * Pleos on-device STT fed with app-owned PCM (from the 0.2 probe). One request in flight;
 * SDK calls and callbacks run on the main looper. Transcripts are handed over, never stored here.
 */
final class Recognizer {
    interface Listener { void onText(String text, long onsetElapsed, int voicedMs); void onStatus(String status); }
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Context context;
    private final Listener out;
    private SpeechToText engine;
    private boolean ready, busy, closed, ended, finalReceived;
    private long generation, onset;
    private int voicedMs;
    private String text = "";

    Recognizer(Context context, Listener out) { this.context = context; this.out = out; }

    void start() { main.post(() -> {
        if (closed) return;
        out.onStatus("음성 인식 연결 중");
        try {
            engine = new SpeechToText(context, Mode.ON_DEVICE);
            engine.addListener(listener);
            engine.initialize(new OnInitializeListener() {
                @Override public void onSuccess() { main.post(() -> {
                    if (closed) return;
                    engine.setLocale(Locale.KOREA); ready = true; out.onStatus("듣는 중");
                }); }
                @Override public void onFailure(ErrorType error) { main.post(() -> fail("초기화", error)); }
            });
            main.postDelayed(() -> { if (!closed && !ready) out.onStatus("음성 인식 연결 지연 · 서비스·권한 확인"); }, 10000);
        } catch (Exception | LinkageError e) { out.onStatus("음성 인식 사용 불가: " + e.getClass().getSimpleName()); }
    }); }

    boolean idle() { return ready && !busy; }

    void submit(byte[] pcm, long onsetElapsed, int voiced) { main.post(() -> {
        if (closed || !ready || busy) return;
        busy = true; ended = finalReceived = false; text = ""; onset = onsetElapsed; voicedMs = voiced;
        final long request = ++generation;
        normalize(pcm);
        try {
            engine.sendAudio(pcm);
            engine.completeAudioSend();
            main.postDelayed(() -> {
                if (!closed && busy && generation == request) { out.onStatus("인식 지연 · 음성 인식을 다시 연결합니다"); restart(); }
            }, 8000);
        } catch (Exception | LinkageError e) { busy = false; out.onStatus("인식 오류: " + e.getClass().getSimpleName()); }
    }); }

    /** Peak-based gain up to 16x on the recognition copy only; quiet cabin mics otherwise time out. */
    private static void normalize(byte[] pcm) {
        int peak = 0;
        for (int i = 0; i + 1 < pcm.length; i += 2) peak = Math.max(peak, Math.abs((short)((pcm[i] & 255) | (pcm[i + 1] << 8))));
        double gain = peak == 0 ? 1 : Math.max(1, Math.min(16.0, 24576.0 / peak));
        for (int i = 0; i + 1 < pcm.length; i += 2) {
            int v = (short)((pcm[i] & 255) | (pcm[i + 1] << 8));
            int s = Math.max(-32768, Math.min(32767, (int)(v * gain)));
            pcm[i] = (byte)s; pcm[i + 1] = (byte)(s >> 8);
        }
    }

    private final ResultListener listener = new ResultListener() {
        @Override public void onReady() {}
        @Override public void onStartedRecognition() {}
        @Override public void onUpdated(String t, boolean completed) { main.post(() -> {
            if (closed || !busy || !completed || finalReceived) return;
            finalReceived = true; text = t;
            if (ended) finish();
        }); }
        @Override public void onUpdatedEpdData(long on, long off) {}
        @Override public void onUpdatedRms(double rms) {}
        @Override public void onEndedRecognition() { main.post(() -> {
            if (closed || !busy) return;
            // Pleos 3.3.1 can dispatch the end event before the final transcript.
            ended = true;
            if (finalReceived) finish();
            else { final long request = generation; main.postDelayed(() -> { if (!closed && busy && generation == request) finish(); }, 750); }
        }); }
        @Override public void onError(ErrorType error) { main.post(() -> fail("인식", error)); }
    };

    private void finish() {
        busy = false; ++generation;
        if (finalReceived && !text.isEmpty()) out.onText(text, onset, voicedMs);
    }

    private void fail(String stage, ErrorType error) {
        if (closed) return;
        out.onStatus(stage + " 실패: " + error + " · 다시 연결합니다");
        restart();
    }

    /** Drop the engine and reconnect, so one stuck request cannot silence the app for the rest of the drive. */
    private void restart() {
        busy = false; ready = false; ++generation;
        SpeechToText old = engine; engine = null;
        if (old != null) { try { old.removeListener(listener); old.stop(); old.release(); } catch (Exception | LinkageError ignored) {} }
        main.postDelayed(this::start, 1000);
    }

    void close() { main.post(() -> {
        closed = true; ready = false; busy = false;
        if (engine != null) { try { engine.removeListener(listener); engine.release(); } catch (Exception | LinkageError ignored) {} }
    }); }
}
