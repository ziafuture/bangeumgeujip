package io.roadmoment.passed;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Kakao Local category/keyword search around a point. Results are used live and not persisted
 * beyond what the user explicitly saves (see docs/bangeum-plan.md on Kakao terms).
 */
final class KakaoPlaces implements PlaceSource {
    // Storefront categories a driver is likely to notice. Kakao has no group code for flowers or clothes.
    private static final String[] DEFAULT_CODES = { "FD6", "CE7", "CS2", "PM9" };
    private static final Map<String, String> KIND_CODES = new LinkedHashMap<>();
    static {
        KIND_CODES.put("카페", "CE7");
        for (String food : new String[] { "빵집", "고기집", "치킨", "피자", "국밥", "분식", "면", "중식", "일식", "양식", "햄버거", "술집", "식당" })
            KIND_CODES.put(food, "FD6");
        KIND_CODES.put("편의점", "CS2"); KIND_CODES.put("약국", "PM9"); KIND_CODES.put("병원", "HP8");
        KIND_CODES.put("주유소", "OL7"); KIND_CODES.put("마트", "MT1");
    }
    /** Keyword to narrow a broad group code (FD6 returns the nearest 15 restaurants of any kind). */
    private static final Map<String, String> KIND_QUERY = new LinkedHashMap<>();
    static {
        KIND_QUERY.put("빵집", "베이커리"); KIND_QUERY.put("고기집", "고기"); KIND_QUERY.put("치킨", "치킨"); KIND_QUERY.put("피자", "피자");
        KIND_QUERY.put("국밥", "국밥"); KIND_QUERY.put("분식", "분식"); KIND_QUERY.put("면", "국수"); KIND_QUERY.put("중식", "중식");
        KIND_QUERY.put("일식", "일식"); KIND_QUERY.put("양식", "양식"); KIND_QUERY.put("햄버거", "햄버거"); KIND_QUERY.put("술집", "술집");
    }
    private final String key;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    KakaoPlaces(String key) { this.key = key; }

    @Override public String label() { return "카카오 로컬"; }

    @Override public void nearby(double lat, double lon, int radiusM, Hints hints, Callback cb) {
        io.execute(() -> {
            try {
                Map<String, Place> merged = new LinkedHashMap<>();
                List<String> codes = new ArrayList<>(), keywords = new ArrayList<>();
                for (String kind : hints.kinds) {
                    String code = KIND_CODES.get(kind);
                    if (code != null) { if (!codes.contains(code)) codes.add(code); } else keywords.add(kind);
                }
                if (codes.isEmpty() && keywords.isEmpty()) for (String c : DEFAULT_CODES) codes.add(c);
                String at = String.format(Locale.US, "x=%.7f&y=%.7f&radius=%d&sort=distance&size=15", lon, lat, radiusM);
                for (String code : codes) parse(get("category.json?category_group_code=" + code + "&" + at), merged);
                for (String kind : hints.kinds) {
                    String q = KIND_QUERY.get(kind);
                    if (q != null) parse(get("keyword.json?query=" + URLEncoder.encode(q, "UTF-8") + "&category_group_code=FD6&" + at), merged);
                }
                for (String word : keywords) parse(get("keyword.json?query=" + URLEncoder.encode(word, "UTF-8") + "&" + at), merged);
                cb.onResult(new ArrayList<>(merged.values()));
            } catch (Exception e) { cb.onError(e.getClass().getSimpleName() + " " + e.getMessage()); }
        });
    }

    static void parse(String body, Map<String, Place> into) throws Exception {
        JSONArray docs = new JSONObject(body).getJSONArray("documents");
        for (int i = 0; i < docs.length(); i++) {
            JSONObject d = docs.getJSONObject(i);
            Place p = new Place(d.getString("id"), d.getString("place_name"), d.optString("category_name"),
                    Double.parseDouble(d.getString("y")), Double.parseDouble(d.getString("x")), d.optString("place_url"),
                    // 지번 주소 carries the 동 name used in spoken summaries; fall back to the road address.
                    d.optString("address_name", d.optString("road_address_name")), d.optString("phone"));
            into.put(p.id, p);
        }
    }

    private String get(String path) throws Exception {
        HttpURLConnection c = (HttpURLConnection)new URL("https://dapi.kakao.com/v2/local/search/" + path).openConnection();
        c.setConnectTimeout(4000); c.setReadTimeout(4000);
        c.setRequestProperty("Authorization", "KakaoAK " + key);
        int code = c.getResponseCode();
        try (InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] b = new byte[8192]; int n;
            while (in != null && (n = in.read(b)) != -1) out.write(b, 0, n);
            String body = out.toString("UTF-8");
            if (code >= 400) throw new IllegalStateException("HTTP " + code + " " + body);
            return body;
        } finally { c.disconnect(); }
    }
}
