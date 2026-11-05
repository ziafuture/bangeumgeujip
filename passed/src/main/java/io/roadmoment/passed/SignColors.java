package io.roadmoment.passed;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;

/**
 * Sign colours drivers told us: "빨간 간판 음식점" followed by a confirmed pick records 빨강 for that place, and the
 * next "빨간 간판" near it ranks it higher. No provider has sign colours, and map imagery may not be captured or
 * analysed, so this is the only source. Kept on the device in this prototype; shared between drivers it would
 * need a server.
 */
final class SignColors {
    private final SharedPreferences prefs;
    SignColors(Context context) { prefs = context.getSharedPreferences("signs", Context.MODE_PRIVATE); }

    /** The colour most often given for this place, or null. */
    synchronized String color(String placeId) {
        try { return top(read().optJSONObject(placeId)); } catch (Exception e) { return null; }
    }

    /** Known colours for the places in a search result, by id. */
    synchronized Map<String, String> known(List<Place> places) {
        Map<String, String> out = new HashMap<>();
        try {
            JSONObject all = read();
            for (Place p : places) { String c = top(all.optJSONObject(p.id)); if (c != null) out.put(p.id, c); }
        } catch (Exception ignored) {}
        return out;
    }

    synchronized void add(String placeId, String color) {
        try {
            JSONObject all = read(), counts = all.optJSONObject(placeId);
            if (counts == null) { counts = new JSONObject(); all.put(placeId, counts); }
            counts.put(color, counts.optInt(color) + 1);
            prefs.edit().putString("colors", all.toString()).apply();
        } catch (Exception ignored) {}
    }

    private JSONObject read() throws Exception { return new JSONObject(prefs.getString("colors", "{}")); }

    private static String top(JSONObject counts) {
        if (counts == null) return null;
        String best = null; int n = 0;
        for (Iterator<String> k = counts.keys(); k.hasNext(); ) { String c = k.next(); if (counts.optInt(c) > n) { n = counts.optInt(c); best = c; } }
        return best;
    }
}
