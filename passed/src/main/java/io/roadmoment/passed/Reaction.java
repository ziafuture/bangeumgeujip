package io.roadmoment.passed;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * This driver's typical delay between passing a place and starting to speak, learned from confirmed saves.
 * The ranking's time peak starts at the population guess (2.5 s) and moves to the driver's median.
 */
public final class Reaction {
    static final long DEFAULT_MS = 2500, MIN_MS = 800, MAX_MS = 5000;
    static final int MIN_SAMPLES = 3, KEEP = 20;
    private final List<Long> samples = new ArrayList<>();

    public Reaction(List<Long> stored) { if (stored != null) for (Long s : stored) add(s); }

    /** A confirmed pick: how long before speech the car passed it. Implausible values are ignored. */
    public synchronized void add(long passedMsBeforeSpeech) {
        if (passedMsBeforeSpeech < 0 || passedMsBeforeSpeech > 9000) return;
        samples.add(passedMsBeforeSpeech);
        while (samples.size() > KEEP) samples.remove(0);
    }

    public synchronized long peakMs() {
        if (samples.size() < MIN_SAMPLES) return DEFAULT_MS;
        List<Long> s = new ArrayList<>(samples); Collections.sort(s);
        long median = s.size() % 2 == 1 ? s.get(s.size() / 2) : (s.get(s.size() / 2 - 1) + s.get(s.size() / 2)) / 2;
        return Math.max(MIN_MS, Math.min(MAX_MS, median));
    }

    public synchronized List<Long> samples() { return new ArrayList<>(samples); }
}
