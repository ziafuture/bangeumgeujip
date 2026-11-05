package io.roadmoment.passed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class PassedFinderTest {
    // Straight road heading east at 15 m/s (54 km/h), one fix per second.
    static final double LAT = 37.5600, LON = 126.9230, V = 15;
    static final double M_PER_DEG_LAT = 111195, M_PER_DEG_LON = 111195 * Math.cos(Math.toRadians(LAT));

    static Trail drive(int seconds) {
        Trail t = new Trail(120000);
        for (int s = 0; s <= seconds; s++) t.add(LAT, LON + s * V / M_PER_DEG_LON, s * 1000L);
        return t;
    }
    /** Place at x metres east of start, y metres north (positive = left of an eastbound car). */
    static Place at(String name, String cat, double x, double y) {
        return new Place(name, name, cat, LAT + y / M_PER_DEG_LAT, LON + x / M_PER_DEG_LON);
    }

    @Test public void picksPlacePassedJustBeforeSpeech() {
        Trail t = drive(30);
        // Speech at 20 s: car is at 300 m. Passed 270 m two seconds ago; 150 m passed ten seconds ago; 330 m is ahead.
        List<Place> ps = Arrays.asList(at("오래전", "음식점", 150, 12), at("방금", "음식점", 270, 12), at("앞쪽", "음식점", 330, 12));
        List<PassedFinder.Candidate> r = PassedFinder.rank(t, 20000, ps, Hints.parse("방금 그거 뭐야"));
        assertEquals("방금", r.get(0).place.name);
        assertEquals(2000, r.get(0).passedMsBeforeSpeech, 50);
    }

    @Test public void sideWordsSelectTheRightSide() {
        Trail t = drive(30);
        List<Place> ps = Arrays.asList(at("북쪽집", "카페", 270, 15), at("남쪽집", "카페", 268, -15));
        assertEquals("북쪽집", PassedFinder.rank(t, 20000, ps, Hints.parse("방금 왼쪽 카페 뭐야")).get(0).place.name);
        assertEquals("남쪽집", PassedFinder.rank(t, 20000, ps, Hints.parse("방금 오른쪽 카페 뭐야")).get(0).place.name);
        assertEquals(Hints.Side.LEFT, PassedFinder.rank(t, 20000, ps, Hints.parse("방금")).stream()
                .filter(c -> c.place.name.equals("북쪽집")).findFirst().get().side);
    }

    @Test public void categoryWordBeatsSlightlyCloserShop() {
        Trail t = drive(30);
        List<Place> ps = Arrays.asList(at("편의점24", "가정,생활 > 편의점", 272, 8), at("소금빵", "음식점 > 간식 > 제과,베이커리", 265, 18));
        assertEquals("소금빵", PassedFinder.rank(t, 20000, ps, Hints.parse("방금 그 빵집 뭐야")).get(0).place.name);
    }

    @Test public void spokenNameWins() {
        Trail t = drive(30);
        List<Place> ps = Arrays.asList(at("블루보틀", "카페", 270, 12), at("어니언", "카페", 255, 12));
        assertEquals("어니언", PassedFinder.rank(t, 20000, ps, Hints.parse("방금 어니언 저장해")).get(0).place.name);
    }

    /** 갈비 골목: four meat places on the right 25 m apart, a cafe between them, one meat place on the left. */
    @Test public void meatStreetNarrowsToRightSideMeatAndFlagsCloseCall() {
        Trail t = drive(30);
        List<Place> ps = Arrays.asList(
                at("첫째갈비", "음식점 > 한식 > 육류,고기 > 갈비", 230, -12), at("둘째삼겹", "음식점 > 한식 > 육류,고기 > 삼겹살", 255, -14),
                at("중간카페", "음식점 > 카페", 262, -12), at("셋째한우", "음식점 > 한식 > 육류,고기", 280, -13),
                at("넷째곱창", "음식점 > 한식 > 육류,고기 > 곱창,막창", 305, -12), at("왼쪽갈비", "음식점 > 한식 > 육류,고기 > 갈비", 270, 13));
        // Speech at 20 s (car at 300 m): 셋째한우 was passed ~1.3 s ago, 둘째삼겹 ~3 s ago.
        List<PassedFinder.Candidate> r = PassedFinder.rank(t, 20000, ps, Hints.parse("방금 우측 고기집 뭐야"));
        java.util.Set<String> top2 = new java.util.HashSet<>(Arrays.asList(r.get(0).place.name, r.get(1).place.name));
        assertEquals(new java.util.HashSet<>(Arrays.asList("셋째한우", "둘째삼겹")), top2);
        for (int i = 0; i < 4; i++) assertTrue(r.get(i).place.category.contains("고기"));
        assertTrue(r.get(0).score - r.get(1).score < Dialog.AMBIGUOUS); // too close to call: the dialog offers both
        assertFalse(r.subList(0, 3).stream().anyMatch(c -> c.place.name.equals("왼쪽갈비")));
    }

    @Test public void spokenKindAndSideFilterOutOthers() {
        Trail t = drive(30);
        List<Place> ps = Arrays.asList(at("고기A", "음식점 > 한식 > 육류,고기", 270, -12), at("닭발B", "음식점 > 한식 > 닭요리", 272, -10),
                at("고기C", "음식점 > 한식 > 육류,고기", 268, 12));
        List<PassedFinder.Candidate> r = PassedFinder.rank(t, 20000, ps, Hints.parse("방금 우측 고기집"));
        assertEquals(1, r.size());
        assertEquals("고기A", r.get(0).place.name);
        // Nothing matches "약국": keep everything rather than return nothing.
        assertEquals(3, PassedFinder.rank(t, 20000, ps, Hints.parse("방금 약국")).size());
    }

    @Test public void farFromRoadIsIgnored() {
        Trail t = drive(30);
        assertTrue(PassedFinder.rank(t, 20000, Arrays.asList(at("뒷골목", "카페", 270, 120)), Hints.parse("방금")).isEmpty());
    }

    @Test public void searchAreaCoversRecentPath() {
        Trail t = drive(30);
        double[] a = PassedFinder.searchArea(t, 20000);
        assertNotNull(a);
        assertTrue(a[2] >= 100 && a[2] <= 300);
        assertEquals(15, t.speed(10000, 20000), 0.1);
    }

    @Test public void parsesRequestsAndAnswers() {
        assertTrue(Hints.parse("방금 그거 뭐야?").request);
        assertFalse(Hints.parse("오늘 날씨 좋다").request);
        assertEquals(Hints.Answer.YES, Hints.answer("응 맞아"));
        assertEquals(Hints.Answer.NEXT, Hints.answer("아니 그 옆"));
        assertEquals(Hints.Answer.NO, Hints.answer("아니야"));
    }

    /** Transcripts from the ElevenLabs age test (2026-11-03). */
    @Test public void survivesLostOpeningWordAndCommonMishearings() {
        // A 70s woman's "방금 그거 뭐야" lost its first word: not a request, but worth a check.
        assertFalse(Hints.parse("그 뭐야").request);
        assertTrue(Hints.parse("그 뭐야").maybe);
        assertTrue(Hints.parse("그렇게 이름이 뭐였지?").maybe);
        assertFalse(Hints.parse("오늘 저녁 뭐 먹지").maybe);
        assertFalse(Hints.parse("방금 그거 뭐야").maybe); // already a request
        assertTrue(Hints.parse("여기 오른쪽 카페 모양").request); // "저기" heard as "여기"
        assertFalse(Hints.parse("여기 어디야").request);
        // Order answers: the robust words, and how "먼저 거" / "나중 거" were misheard.
        assertEquals(Hints.Answer.EARLIER, Hints.answer("아까 거"));
        assertEquals(Hints.Answer.LATER, Hints.answer("방금 거"));
        assertEquals(Hints.Answer.EARLIER, Hints.answer("원조가"));
        assertEquals(Hints.Answer.LATER, Hints.answer("나준 거"));
        assertEquals(Hints.Answer.LATER, Hints.answer("낮은 거"));
        assertEquals(Hints.Answer.CANCEL, Hints.answer("아니 됐었어"));
    }

    @Test public void manyWaysOfSayingNo() {
        for (String no : new String[] { "아니", "아니라고", "아니라구요", "아닌데", "아닌 것 같아", "아님", "노우", "노", "No", "no no", "nope",
                "놉", "싫어", "안 돼", "필요 없어", "틀렸어", "그거 말고" })
            assertEquals(no, Hints.Answer.NO, Hints.answer(no));
        assertEquals(Hints.Answer.NONE, Hints.answer("노래 틀어 줘"));
        assertEquals(Hints.Answer.YES, Hints.answer("응"));
        assertEquals(Hints.Answer.LATER, Hints.answer("아니 나중 거"));      // order words still win
        assertEquals(Hints.Answer.CANCEL, Hints.answer("아니 됐어"));
    }

    @Test public void unlistedShopKindsNarrowTheSearch() {
        assertEquals(java.util.Arrays.asList("호떡"), Hints.parse("방금 호떡집 뭐야").kinds);
        assertEquals(java.util.Arrays.asList("감자탕"), Hints.parse("아까 그 감자탕집이 어디였지").kinds);
        assertEquals(java.util.Arrays.asList("감자탕"), Hints.parse("방금 감자탕 가게").kinds);
        assertTrue(Hints.parse("저기 호떡집 뭐야").request);            // a named kind makes "저기" count
        assertEquals("호떡집", Hints.spokenKind(Hints.parse("방금 호떡집 뭐야")));
        assertEquals("빵집", Hints.spokenKind(Hints.parse("방금 빵집 뭐야")));  // listed kinds unchanged
        assertTrue(Hints.parse("방금 그집 뭐야").kinds.isEmpty());
        assertTrue(Hints.parse("방금 지나간 집 뭐야").kinds.isEmpty());
        assertTrue(Hints.parse("아까 맛집 어디야").kinds.contains("식당"));
    }

    /** Drivers know chains at a glance: a local shop passed at the same moment comes first unless the brand is named. */
    @Test public void chainsRankBelowLocalShops() {
        Trail t = drive(30);
        // Speech at 20 s, car at 300 m. The chain was passed 2.5 s before (the peak), the local café 3.5 s before.
        List<Place> ps = Arrays.asList(at("스타벅스 연남점", "음식점 > 카페 > 커피전문점 > 스타벅스", 262, -12),
                at("달무리커피", "음식점 > 카페 > 커피전문점", 247, -12));
        assertEquals("달무리커피", PassedFinder.rank(t, 20000, ps, Hints.parse("방금 그 카페 뭐야")).get(0).place.name);
        assertEquals("스타벅스 연남점", PassedFinder.rank(t, 20000, ps, Hints.parse("방금 스타벅스 어디였지")).get(0).place.name);
        // A chain passed clearly closer to the moment still wins: it may be what the driver meant.
        List<Place> far = Arrays.asList(at("스타벅스 연남점", "음식점 > 카페", 262, -12), at("달무리커피", "음식점 > 카페", 160, -12));
        assertEquals("스타벅스 연남점", PassedFinder.rank(t, 20000, far, Hints.parse("방금 그 카페 뭐야")).get(0).place.name);
        // Asking about a convenience store means a chain.
        List<Place> cs = Arrays.asList(at("GS25 연남점", "편의점 > GS25", 262, -12), at("연남상회", "편의점", 247, -12));
        assertEquals("GS25 연남점", PassedFinder.rank(t, 20000, cs, Hints.parse("방금 편의점 뭐야")).get(0).place.name);
        assertTrue(Chains.is(new Place("1", "CU 홍대점", "", 0, 0)));
        assertFalse(Chains.is(new Place("2", "꽃살집", "", 0, 0)));
    }

    /** "빨간 간판" lifts the place drivers said had a red sign, and sinks one known to be another colour. */
    @Test public void signColoursDriversGaveNarrowTheSearch() {
        Hints h = Hints.parse("좀전에 빨간 간판 음식점 뭐야?");
        assertTrue(h.request);
        assertEquals("빨강", h.color);
        assertTrue(h.kinds.contains("식당"));
        assertEquals("파랑", Hints.parse("저기 파란 간판 뭐야").color);
        assertTrue(Hints.parse("저기 파란 간판 뭐야").request); // a colour says which place, like a side does
        assertEquals(null, Hints.parse("방금 그거 뭐야").color);
        assertTrue(Hints.parse("방금 빨간 집 뭐야").kinds.isEmpty()); // "빨간 집" is a colour, not a kind of shop

        Trail t = drive(30);
        // The peak-time shop is unknown; the red one was passed a second later than the peak.
        List<Place> ps = Arrays.asList(at("꽃살집", "음식점 > 한식", 262, -12), at("우둔살집", "음식점 > 한식", 250, -12));
        java.util.Map<String, String> signs = new java.util.HashMap<>();
        assertEquals("꽃살집", PassedFinder.rank(t, 20000, ps, h, 2500, signs).get(0).place.name);
        signs.put("우둔살집", "빨강");
        PassedFinder.Candidate top = PassedFinder.rank(t, 20000, ps, h, 2500, signs).get(0);
        assertEquals("우둔살집", top.place.name);
        assertEquals("빨강", top.sign);
        signs.put("우둔살집", "파랑"); signs.put("꽃살집", "빨강");
        assertEquals("꽃살집", PassedFinder.rank(t, 20000, ps, h, 2500, signs).get(0).place.name);
        // Without a colour in the request, known colours change nothing.
        assertEquals("꽃살집", PassedFinder.rank(t, 20000, ps, Hints.parse("방금 음식점 뭐야"), 2500, signs).get(0).place.name);
    }
}
