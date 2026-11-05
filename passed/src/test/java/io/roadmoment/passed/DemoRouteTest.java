package io.roadmoment.passed;

import static org.junit.Assert.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** The bundled demo: eastbound at 12 m/s along demo-route.gpx, fictional places from assets. */
public class DemoRouteTest {
    static final double LAT = 37.5560, LON = 126.9220, V = 12;
    static final double M_PER_DEG_LON = 111195 * Math.cos(Math.toRadians(LAT));

    static List<Place> places() throws Exception {
        JSONArray a = new JSONArray(new String(Files.readAllBytes(Paths.get("src/main/assets/demo_places.json")), StandardCharsets.UTF_8));
        List<Place> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.getJSONObject(i);
            out.add(new Place(o.getString("id"), o.getString("name"), o.getString("category"), o.getDouble("lat"), o.getDouble("lon")));
        }
        return out;
    }

    static Trail drive() {
        Trail t = new Trail(120000);
        for (int s = 0; s <= 96; s++) t.add(LAT, LON + s * V / M_PER_DEG_LON, s * 1000L);
        return t;
    }

    /** Speech starts `lag` ms after passing x metres. */
    static String best(double x, long lag, String text) throws Exception {
        Trail t = drive(); long onset = Math.round(x / V * 1000) + lag;
        double[] area = PassedFinder.searchArea(t, onset);
        List<Place> near = new ArrayList<>();
        Trail.Fix c = new Trail.Fix(area[0], area[1], 0);
        for (Place p : places()) if (Geo.distance(c, new Trail.Fix(p.lat, p.lon, 0)) <= area[2]) near.add(p);
        return PassedFinder.rank(t, onset, near, Hints.parse(text)).get(0).place.id;
    }

    @Test public void demoScenarios() throws Exception {
        assertEquals("d07", best(520, 2000, "방금 그 카페 뭐야"));         // 노을 카페, left
        assertEquals("d08", best(540, 1500, "방금 오른쪽 거 뭐야"));        // 반달 분식, right, 20 m after the cafe
        assertEquals("d02", best(190, 3000, "아까 그 빵집 저장해"));        // 소금결 베이커리, next to a convenience store
        assertEquals("d05", best(360, 2500, "방금 꽃집"));                 // 푸른잎 꽃집 (no Kakao group code; keyword path)
        assertEquals("d12", best(900, 1000, "방금 새벽 커피 저장"));         // spoken name
    }
}
