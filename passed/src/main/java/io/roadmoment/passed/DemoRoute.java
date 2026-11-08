package io.roadmoment.passed;

import java.util.ArrayList;
import java.util.List;

/**
 * The demo drive: due east from {@link #LAT},{@link #LON} one step a second. Its timeline (which bundled place the car
 * passes when, on which side) drives the demo card, so a reviewer knows when to ask and what to ask about.
 */
final class DemoRoute {
    static final double LAT = 37.556, LON = 126.922, STEP_LON = 0.0001361;
    static final int SECONDS = 96;
    /** A shop passed this many seconds ago is still "방금". */
    static final int JUST_PASSED = 8;

    static final class Stop {
        final int second; final boolean left; final String name, word;
        Stop(int second, boolean left, String name, String word) { this.second = second; this.left = left; this.name = name; this.word = word; }
        String side() { return left ? "왼쪽" : "오른쪽"; }
        /** What a driver would say about it: "방금 왼쪽 카페 뭐야". */
        String question() { return "방금 " + side() + " " + word + " 뭐야"; }
    }

    static List<Stop> stops(List<Place> places) {
        List<Stop> out = new ArrayList<>();
        for (Place p : places) {
            int s = (int) Math.round((p.lon - LON) / STEP_LON);
            if (s >= 0 && s <= SECONDS) out.add(new Stop(s, p.lat > LAT, p.name, word(Hints.kindOf(p.category, p.name))));
        }
        out.sort((a, b) -> Integer.compare(a.second, b.second));
        return out;
    }

    /** The kind as a driver says it ("면" → "국숫집"). */
    static String word(String kind) {
        switch (kind) {
            case "면": return "국숫집";
            case "치킨": return "치킨집";
            case "분식": return "분식집";
            case "양식": return "브런치집";
            case "기타": return "가게";
            default: return kind;
        }
    }

    /** The latest stop passed at most {@link #JUST_PASSED} seconds ago, or null. */
    static Stop passed(List<Stop> stops, int second) {
        Stop last = null;
        for (Stop s : stops) if (s.second <= second && second - s.second <= JUST_PASSED) last = s;
        return last;
    }

    static Stop next(List<Stop> stops, int second) {
        for (Stop s : stops) if (s.second > second) return s;
        return null;
    }
}
