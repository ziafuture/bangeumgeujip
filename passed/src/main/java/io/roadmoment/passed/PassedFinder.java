package io.roadmoment.passed;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Ranks places by how likely the driver just passed them when speech started.
 * Drivers usually react 1-4 s after a sign leaves view, so the search looks back from the utterance onset.
 */
public final class PassedFinder {
    public static final long LOOK_BACK_MS = 10000, LOOK_AHEAD_MS = 1000;
    static final double MAX_OFFSET_M = 80, PEAK_REACTION_MS = 2500, REACTION_SPREAD_MS = 2500;
    /** Enough to put a local shop passed at about the same moment first, not enough to hide a chain that is clearly it. */
    static final double CHAIN_PENALTY = 0.6;
    /** A sign colour drivers gave for this place: worth about as much as the side. */
    static final double SIGN_MATCH = 0.8, SIGN_MISMATCH = 0.5;

    public static final class Candidate {
        public final Place place; public final double offsetM, score; public final long passedMsBeforeSpeech; public final Hints.Side side;
        /** Sign colour drivers gave for this place ({@link SignColors}), or null. */
        public final String sign;
        Candidate(Place place, double offsetM, long passedMsBeforeSpeech, Hints.Side side, double score) { this(place, offsetM, passedMsBeforeSpeech, side, score, null); }
        Candidate(Place place, double offsetM, long passedMsBeforeSpeech, Hints.Side side, double score, String sign) {
            this.place = place; this.offsetM = offsetM; this.passedMsBeforeSpeech = passedMsBeforeSpeech; this.side = side; this.score = score; this.sign = sign;
        }
    }

    /** Circle a provider should search: centred on where the car was shortly before speech. */
    public static double[] searchArea(Trail trail, long onset) {
        Trail.Fix c = trail.at(onset - 3000);
        if (c == null) c = trail.at(onset);
        if (c == null) return null;
        double v = trail.speed(onset - LOOK_BACK_MS, onset);
        double radius = Math.min(300, Math.max(100, v * LOOK_BACK_MS / 2000.0 + MAX_OFFSET_M));
        return new double[] { c.lat, c.lon, radius };
    }

    public static List<Candidate> rank(Trail trail, long onset, List<Place> places, Hints hints) {
        return rank(trail, onset, places, hints, (long)PEAK_REACTION_MS);
    }

    public static List<Candidate> rank(Trail trail, long onset, List<Place> places, Hints hints, long peakMs) {
        return rank(trail, onset, places, hints, peakMs, java.util.Collections.emptyMap());
    }

    /**
     * @param peakMs this driver's typical delay from passing a place to speaking (see {@link Reaction}).
     * @param signs  sign colours drivers gave, by place id ({@link SignColors}).
     */
    public static List<Candidate> rank(Trail trail, long onset, List<Place> places, Hints hints, long peakMs, java.util.Map<String, String> signs) {
        List<Trail.Fix> path = trail.window(onset - LOOK_BACK_MS, onset + LOOK_AHEAD_MS);
        List<Candidate> out = new ArrayList<>();
        if (path.size() < 2) return out;
        Trail.Fix o = path.get(0);
        for (Place p : places) {
            double[] q = Geo.enu(o.lat, o.lon, p.lat, p.lon);
            double best = Double.MAX_VALUE, bestT = 0, cross = 0;
            for (int i = 1; i < path.size(); i++) {
                Trail.Fix a = path.get(i - 1), b = path.get(i);
                double[] pa = Geo.enu(o.lat, o.lon, a.lat, a.lon), pb = Geo.enu(o.lat, o.lon, b.lat, b.lon);
                double dx = pb[0] - pa[0], dy = pb[1] - pa[1], len2 = dx * dx + dy * dy;
                if (len2 < 0.01) continue; // stopped: no direction to project on
                double k = Math.max(0, Math.min(1, ((q[0] - pa[0]) * dx + (q[1] - pa[1]) * dy) / len2));
                double ex = q[0] - (pa[0] + dx * k), ey = q[1] - (pa[1] + dy * k), d = Math.hypot(ex, ey);
                if (d < best) { best = d; bestT = a.at + (b.at - a.at) * k; cross = dx * (q[1] - pa[1]) - dy * (q[0] - pa[0]); }
            }
            if (best > MAX_OFFSET_M) continue;
            long before = Math.round(onset - bestT);
            Hints.Side side = cross > 0 ? Hints.Side.LEFT : Hints.Side.RIGHT;
            double score = Math.exp(-best / 30.0);
            double r = (before - peakMs) / REACTION_SPREAD_MS;
            score += Math.exp(-r * r / 2);
            if (before < -LOOK_AHEAD_MS / 2) score -= 0.5; // still ahead of the car when speech began
            if (hints.side != Hints.Side.ANY) score += hints.side == side ? 0.6 : -0.8;
            if (!hints.kinds.isEmpty()) score += matchesKind(p, hints) ? 0.7 : -0.3;
            if (Hints.mentions(hints.compact, p)) score += 1.0;
            if (Chains.demote(hints, p)) score -= CHAIN_PENALTY;
            String sign = signs.get(p.id);
            if (hints.color != null && sign != null) score += sign.equals(hints.color) ? SIGN_MATCH : -SIGN_MISMATCH;
            out.add(new Candidate(p, best, before, side, score, sign));
        }
        Collections.sort(out, (x, y) -> Double.compare(y.score, x.score));
        // What the driver said is a constraint, not just a hint, as long as something satisfies it.
        if (!hints.kinds.isEmpty() && out.stream().anyMatch(c -> matchesKind(c.place, hints))) out.removeIf(c -> !matchesKind(c.place, hints));
        if (hints.side != Hints.Side.ANY && out.stream().anyMatch(c -> c.side == hints.side)) out.removeIf(c -> c.side != hints.side);
        return out;
    }

    private static boolean matchesKind(Place p, Hints h) {
        String text = p.category + " " + p.name;
        for (String kind : h.kinds) for (String w : Hints.categoryWords(kind)) if (text.contains(w)) return true;
        return false;
    }
}
