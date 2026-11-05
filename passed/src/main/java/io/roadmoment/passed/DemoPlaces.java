package io.roadmoment.passed;

import android.content.Context;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Fictional places along the bundled demo route (assets/demo_places.json). Works offline and without a key. */
final class DemoPlaces implements PlaceSource {
    private final List<Place> all = new ArrayList<>();

    DemoPlaces(Context context) {
        try (InputStream in = context.getAssets().open("demo_places.json")) {
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream(); byte[] b = new byte[8192]; int n;
            while ((n = in.read(b)) != -1) buf.write(b, 0, n);
            JSONArray a = new JSONArray(buf.toString("UTF-8"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                all.add(new Place(o.getString("id"), o.getString("name"), o.getString("category"), o.getDouble("lat"), o.getDouble("lon"), ""));
            }
        } catch (Exception e) { throw new IllegalStateException("demo_places.json", e); }
    }

    @Override public String label() { return "데모 장소(가상)"; }

    @Override public void nearby(double lat, double lon, int radiusM, Hints hints, Callback cb) {
        List<Place> out = new ArrayList<>();
        Trail.Fix c = new Trail.Fix(lat, lon, 0);
        for (Place p : all) if (Geo.distance(c, new Trail.Fix(p.lat, p.lon, 0)) <= radiusM) out.add(p);
        cb.onResult(out);
    }
}
