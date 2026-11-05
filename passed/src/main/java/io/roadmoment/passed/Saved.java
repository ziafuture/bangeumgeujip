package io.roadmoment.passed;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Places the user chose to keep. Only explicit saves are stored; the location trail is never written.
 * An unconfirmed save may carry alternatives (the other likely places) for the driver to pick after parking.
 * Prototype stores name/coordinates for alerts and routing; check provider terms before release.
 */
final class Saved {
    static final class Item {
        final String id, name, category, url, address, phone; final double lat, lon; final long savedAt; final boolean confirmed;
        final List<Item> alternatives;
        Item(String id, String name, String category, String url, String address, String phone, double lat, double lon,
             long savedAt, boolean confirmed, List<Item> alternatives) {
            this.id = id; this.name = name; this.category = category; this.url = url; this.address = address; this.phone = phone;
            this.lat = lat; this.lon = lon; this.savedAt = savedAt; this.confirmed = confirmed; this.alternatives = alternatives;
        }
        static Item of(Place p, long at, boolean confirmed, List<Item> alternatives) {
            return new Item(p.id, p.name, p.category, p.url, p.address, p.phone, p.lat, p.lon, at, confirmed, alternatives);
        }
    }
    private static final int MAX = 100;
    /** "Near the destination": about a ten-minute walk. Closer than {@code SAME_M} is the destination itself. */
    static final double NEAR_DEST_M = 700, SAME_M = 30;

    /** Confirmed saves within walking distance of a navigation destination, nearest first. */
    static List<Item> nearDestination(List<Item> items, double lat, double lon) {
        List<Item> out = new ArrayList<>(); List<Double> dist = new ArrayList<>();
        Trail.Fix dest = new Trail.Fix(lat, lon, 0);
        for (Item i : items) {
            if (!i.confirmed) continue;
            double d = Geo.distance(dest, new Trail.Fix(i.lat, i.lon, 0));
            if (d <= SAME_M || d > NEAR_DEST_M) continue;
            int k = 0; while (k < dist.size() && dist.get(k) < d) k++;
            out.add(k, i); dist.add(k, d);
        }
        return out;
    }
    private final SharedPreferences prefs;
    Saved(Context context) { prefs = context.getSharedPreferences("saved", Context.MODE_PRIVATE); }

    synchronized List<Item> all() {
        try { return read(new JSONArray(prefs.getString("items", "[]"))); } catch (Exception e) { return new ArrayList<>(); }
    }

    static final String GROUP = "group:";

    /**
     * A confirmed save replaces any earlier entry for the same place. An unconfirmed guess is stored as its own
     * group entry (all candidates, best first) so it never overwrites a place the driver already confirmed.
     */
    synchronized void add(Place p, boolean confirmed, List<Place> alternatives, long now) {
        List<Item> items = all();
        if (confirmed) {
            items.removeIf(i -> i.id.equals(p.id));
            items.add(0, Item.of(p, now, true, new ArrayList<>()));
        } else {
            List<Item> options = new ArrayList<>();
            options.add(Item.of(p, now, false, new ArrayList<>()));
            for (Place a : alternatives) options.add(Item.of(a, now, false, new ArrayList<>()));
            items.add(0, new Item(GROUP + now, p.name, p.category, "", "", "", p.lat, p.lon, now, false, options));
        }
        write(items.subList(0, Math.min(MAX, items.size())));
    }

    /** Last level of the category for display ("음식점 > 카페 > 커피전문점" → "커피전문점"); older saves stored only that. */
    static String label(Item i) { int k = i.category.lastIndexOf('>'); return (k < 0 ? i.category : i.category.substring(k + 1)).trim(); }

    static String kind(Item i) { return Hints.kindOf(i.category, i.name); }

    /** District for grouping ("서울 마포구 동교동 …" → "마포구"; a city or county when there is no 구), or "" without an address. */
    static String district(Item i) {
        String city = "";
        for (String t : i.address.split("\\s+")) {
            if (t.matches("[가-힣]+구")) return t;
            if (city.isEmpty() && t.matches("[가-힣]+[시군]")) city = t;
        }
        return city;
    }

    static boolean isGroup(Item i) { return i.id.startsWith(GROUP); }

    /** Candidates to choose from: a group's options, or (older data) the entry itself plus its alternatives. */
    static List<Item> options(Item i) {
        List<Item> out = new ArrayList<>();
        if (!isGroup(i)) out.add(i);
        out.addAll(i.alternatives);
        return out;
    }

    /** The driver picked which candidate it was: that one becomes a confirmed save. */
    synchronized Item resolve(String id, String chosenId) {
        List<Item> items = all();
        for (int k = 0; k < items.size(); k++) {
            Item i = items.get(k);
            if (!i.id.equals(id)) continue;
            Item pick = null;
            for (Item o : options(i)) if (o.id.equals(chosenId)) pick = o;
            if (pick == null) return null;
            Item done = new Item(pick.id, pick.name, pick.category, pick.url, pick.address, pick.phone, pick.lat, pick.lon, i.savedAt, true, new ArrayList<>());
            items.remove(k);
            items.removeIf(x -> x.id.equals(done.id));
            items.add(Math.min(k, items.size()), done);
            write(items);
            return done;
        }
        return null;
    }

    /** The confirmed save of this place, if any. */
    synchronized Item find(String id) { for (Item i : all()) if (i.id.equals(id) && i.confirmed && i.alternatives.isEmpty()) return i; return null; }

    /** Reaction-time samples (ms) for {@link Reaction}; numbers only, no places or times. */
    synchronized List<Long> reactionSamples() {
        List<Long> out = new ArrayList<>();
        for (String v : prefs.getString("reaction", "").split(",")) if (!v.isEmpty()) try { out.add(Long.parseLong(v)); } catch (NumberFormatException ignored) {}
        return out;
    }

    synchronized void reactionSamples(List<Long> samples) {
        StringBuilder b = new StringBuilder(); for (Long v : samples) b.append(b.length() == 0 ? "" : ",").append(v);
        prefs.edit().putString("reaction", b.toString()).apply();
    }

    synchronized void remove(String id) { List<Item> items = all(); items.removeIf(i -> i.id.equals(id)); write(items); }

    synchronized int countSince(long since) { int n = 0; for (Item i : all()) if (i.savedAt >= since) n++; return n; }

    private static List<Item> read(JSONArray a) throws Exception {
        List<Item> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.getJSONObject(i);
            JSONArray alts = o.optJSONArray("alternatives");
            out.add(new Item(o.getString("id"), o.getString("name"), o.optString("category"), o.optString("url"),
                    o.optString("address"), o.optString("phone"), o.getDouble("lat"), o.getDouble("lon"), o.getLong("savedAt"), o.optBoolean("confirmed"),
                    alts == null ? new ArrayList<>() : read(alts)));
        }
        return out;
    }

    private static JSONArray json(List<Item> items) throws Exception {
        JSONArray a = new JSONArray();
        for (Item i : items) {
            JSONObject o = new JSONObject().put("id", i.id).put("name", i.name).put("category", i.category).put("url", i.url)
                    .put("address", i.address).put("phone", i.phone).put("lat", i.lat).put("lon", i.lon).put("savedAt", i.savedAt).put("confirmed", i.confirmed);
            if (!i.alternatives.isEmpty()) o.put("alternatives", json(i.alternatives));
            a.put(o);
        }
        return a;
    }

    private void write(List<Item> items) {
        try { prefs.edit().putString("items", json(items).toString()).apply(); } catch (Exception ignored) {}
    }
}
