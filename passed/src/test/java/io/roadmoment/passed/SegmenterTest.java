package io.roadmoment.passed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class SegmenterTest {
    private final List<Integer> cuts = new ArrayList<>();
    private final Segmenter seg = new Segmenter((pcm, onset, manual, voicedMs) -> cuts.add(voicedMs));
    private long now;

    /** Feeds {@code chunks} 100 ms chunks at {@code db} while listening. */
    private void feed(double db, int chunks) {
        short[] buf = new short[1600];
        for (int i = 0; i < chunks; i++) seg.accept(buf, buf.length, db, now += 100, true);
    }

    @Test public void quietVoiceInQuietCabinIsHeard() {
        feed(-62, 30);   // quiet cabin
        feed(-44, 15);   // soft voice: missed by the old fixed -40 dB gate
        feed(-62, 10);
        assertEquals(1, cuts.size());
        assertEquals(1500, (int)cuts.get(0));
    }

    @Test public void softTailCountsTowardLength() {
        feed(-62, 30);
        feed(-40, 3);    // "음"
        feed(-53, 4);    // soft "글쎄": under the speech threshold, over the floor
        feed(-62, 10);
        assertEquals(1, cuts.size());
        assertEquals(700, (int)cuts.get(0));
    }

    @Test public void softDipInsideSpeechDoesNotSplitIt() {
        feed(-62, 30);
        feed(-44, 3);    // "아까"
        feed(-52, 9);    // shaky middle: under the start threshold for 0.9 s
        feed(-44, 3);    // "뭐였더라"
        feed(-62, 10);
        assertEquals(1, cuts.size());
    }

    @Test public void roadNoiseDoesNotTrigger() {
        feed(-38, 100);  // steady loud cabin: the floor rises, so the noise alone never cuts
        cuts.clear();
        feed(-38, 50);
        assertTrue(cuts.isEmpty());
        feed(-20, 10);   // speech over the noise still does
        feed(-38, 10);
        assertEquals(1, cuts.size());
    }

    @Test public void endsWhenTheRoomSitsJustUnderTheThreshold() {
        feed(-62, 30);   // floor learned in a quiet moment: threshold -50
        feed(-40, 15);   // speech
        feed(-53, 16);   // the usual level, under the threshold but above threshold - HOLD_DB
        assertEquals(1, cuts.size());          // cut after 1.5 s, not at the 8 s cap
        assertEquals(1500, (int)cuts.get(0));  // and the room tail is not counted as speech
    }

    @Test public void thresholdStaysInBounds() {
        feed(-90, 50);
        assertEquals(Segmenter.MIN_THRESHOLD_DB, seg.thresholdDb(), 0.01);
    }
}
