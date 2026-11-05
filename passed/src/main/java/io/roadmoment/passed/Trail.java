package io.roadmoment.passed;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Recent location fixes in one monotonic time base (elapsed ms). Keeps only the last window. */
public final class Trail {
    public static final class Fix {
        public final double lat, lon; public final long at;
        public Fix(double lat, double lon, long at) { this.lat = lat; this.lon = lon; this.at = at; }
    }
    private final ArrayDeque<Fix> fixes = new ArrayDeque<>();
    private final long keepMs;
    public Trail(long keepMs) { this.keepMs = keepMs; }

    public synchronized void add(double lat, double lon, long at) {
        if (!fixes.isEmpty() && at <= fixes.peekLast().at) return;
        fixes.addLast(new Fix(lat, lon, at));
        while (fixes.size() > 2 && at - fixes.peekFirst().at > keepMs) fixes.removeFirst();
    }

    /** Fixes covering [from, to], including one fix on each side so the window can be interpolated. */
    public synchronized List<Fix> window(long from, long to) {
        List<Fix> out = new ArrayList<>(); Fix before = null;
        for (Fix f : fixes) {
            if (f.at < from) { before = f; continue; }
            if (out.isEmpty() && before != null) out.add(before);
            out.add(f);
            if (f.at > to) break;
        }
        if (out.isEmpty() && before != null) out.add(before);
        return out;
    }

    /** Interpolated position at time t, or null if the trail does not reach t. */
    public synchronized Fix at(long t) {
        Fix prev = null;
        for (Fix f : fixes) {
            if (f.at >= t) {
                if (prev == null) return f.at == t ? f : null;
                double k = (t - prev.at) / (double)(f.at - prev.at);
                return new Fix(prev.lat + (f.lat - prev.lat) * k, prev.lon + (f.lon - prev.lon) * k, t);
            }
            prev = f;
        }
        return null;
    }

    /** Mean speed in m/s over [from, to]. Vehicle SDK exposes no speed, so it is derived from fixes. */
    public synchronized double speed(long from, long to) {
        List<Fix> w = window(from, to); if (w.size() < 2) return 0;
        double d = 0;
        for (int i = 1; i < w.size(); i++) d += Geo.distance(w.get(i - 1), w.get(i));
        long dt = w.get(w.size() - 1).at - w.get(0).at;
        return dt <= 0 ? 0 : d * 1000.0 / dt;
    }
}
