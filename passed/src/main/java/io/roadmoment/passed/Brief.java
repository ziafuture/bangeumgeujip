package io.roadmoment.passed;

/**
 * One-sentence spoken summaries built only from data we actually have (provider category and address,
 * our own saves). No opening hours, ratings or reviews: the Kakao Local API does not provide them.
 */
public final class Brief {
    private Brief() {}

    /** Result first, by name so the driver hears what was kept: "꽃살집 찜했어요." plus "3주 전에도 찜했죠." when saved before. */
    public static String saved(Place p, long previousSavedAt, long now) {
        String name = p.name == null ? "" : p.name.trim();
        StringBuilder b = new StringBuilder(name.isEmpty() ? "찜했어요." : name + " 찜했어요.");
        if (previousSavedAt > 0) b.append(' ').append(again(previousSavedAt, now)).append(" 찜했죠.");
        return b.toString();
    }

    /** "갈비", "육류,고기", "카페" → words a person would say. */
    static String kind(Place p) {
        String last = p.shortCategory(), full = p.category;
        if (last.isEmpty()) return "";
        if (full.contains("카페") || last.contains("커피")) return "카페";
        if (full.contains("베이커리") || full.contains("제과")) return "빵집";
        if (last.equals("육류,고기")) return "고기집";
        String word = last.split(",")[0].trim();
        if (!full.startsWith("음식점") || word.endsWith("집") || word.endsWith("점")) return word;
        return word + "집";
    }

    /** Neighbourhood from a Korean address: the first token ending in 동, 가, 리, 읍 or 면 ("종로1가" yes, "101동" no). */
    static String area(String address) {
        if (address == null) return "";
        for (String t : address.split("\\s+"))
            if (t.matches("[가-힣][가-힣0-9.]*[동가리읍면]")) return t;
        return "";
    }

    /** "아까도", "어제도", "3주 전에도". */
    static String again(long then, long now) {
        String a = ago(then, now);
        return a.equals("오늘") ? "아까도" : a.equals("어제") ? "어제도" : a + "에도";
    }

    static String ago(long then, long now) {
        long d = Math.max(0, now - then) / 86400000L;
        if (d == 0) return "오늘";
        if (d == 1) return "어제";
        if (d < 7) return d + "일 전";
        if (d < 35) return (d / 7) + "주 전";
        if (d < 365) return (d / 30) + "달 전";
        return (d / 365) + "년 전";
    }

    /** Offer when a route starts: "목적지 근처에 3주 전 찜한 꽃살집이 있어요. 들를까요?" */
    public static String nearDestination(String name, long savedAt, int count, long now) {
        if (count > 1) return "목적지 근처에 찜한 곳이 " + count + "곳 있어요. 제일 가까운 " + name + ", 들를까요?";
        return "목적지 근처에 " + ago(savedAt, now) + " 찜한 " + name + Dialog.subject(name) + " 있어요. 들를까요?";
    }

    /** Arrival summary, or null when there is nothing worth saying. */
    public static String arrival(int savedThisTrip, String nearbySavedName) {
        StringBuilder b = new StringBuilder();
        if (savedThisTrip > 0) b.append("오늘 ").append(savedThisTrip).append("곳 찜했어요.");
        if (nearbySavedName != null) b.append(b.length() == 0 ? "" : " ").append(nearbySavedName).append("도 근처예요.");
        return b.length() == 0 ? null : b.toString();
    }
}
