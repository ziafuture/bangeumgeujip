package io.roadmoment.passed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/** Safety gate, personal reaction time, spoken summaries. */
public class FeaturesTest {
    @Test public void safetyHoldsQuestions() {
        Safety s = new Safety();
        assertNull(s.busy(0));
        s.turnSignal(true, 1000);
        assertEquals("방향지시등 켜짐", s.busy(2000));
        s.turnSignal(false, 3000);
        assertNull(s.busy(3000));
        s.nextTurn(120, 4000);
        assertEquals("곧 회전", s.busy(4500));
        assertNull(s.busy(4000 + Safety.STALE_MS + 1)); // no fresh update: do not hold forever
        s.nextTurn(800, 5000);
        assertNull(s.busy(5000));
        s.caution("SCHOOL_ZONE", 150, 6000);
        assertEquals("주의 구간", s.busy(6000));
        s.caution("SPEED_BUMP", 50, 7000); // a speed bump alone does not need full attention
        assertNull(s.busy(7000));
    }

    @Test public void reactionLearnsMedianAfterThreeConfirmations() {
        Reaction r = new Reaction(null);
        assertEquals(Reaction.DEFAULT_MS, r.peakMs());
        r.add(1200); r.add(1500);
        assertEquals(Reaction.DEFAULT_MS, r.peakMs()); // not enough evidence yet
        r.add(1400);
        assertEquals(1400, r.peakMs());
        r.add(-300); r.add(20000); // impossible delays are ignored
        assertEquals(3, r.samples().size());
        assertEquals(1400, new Reaction(r.samples()).peakMs()); // survives a restart
    }

    @Test public void quickReactorGetsTheRecentPlace() {
        // Two places 30 m apart on the right, passed 1 s and 3 s before speech at 15 m/s.
        Trail t = PassedFinderTest.drive(30);
        List<Place> ps = Arrays.asList(PassedFinderTest.at("1초전", "음식점 > 카페", 285, -12), PassedFinderTest.at("3초전", "음식점 > 카페", 255, -12));
        Hints h = Hints.parse("방금 그 카페");
        assertEquals("3초전", PassedFinder.rank(t, 20000, ps, h).get(0).place.name);       // default 2.5 s
        assertEquals("1초전", PassedFinder.rank(t, 20000, ps, h, 1000).get(0).place.name); // this driver speaks after ~1 s
    }

    @Test public void savedSentence() {
        Place galbi = new Place("1", "연남갈비", "음식점 > 한식 > 육류,고기 > 갈비", 0, 0, "", "서울 마포구 연남동 239-49", "02-000-0000");
        assertEquals("연남갈비 찜했어요.", Brief.saved(galbi, 0, 0));
        Place cafe = new Place("2", "노을", "음식점 > 카페 > 커피전문점", 0, 0, "", "서울 마포구 서교동 1", "");
        long day = 86400000L;
        assertEquals("노을 찜했어요. 3주 전에도 찜했죠.", Brief.saved(cafe, day, 22 * day));
        assertEquals("노을 찜했어요. 아까도 찜했죠.", Brief.saved(cafe, day, day + 3600000));
        assertEquals("노을 찜했어요. 어제도 찜했죠.", Brief.saved(cafe, day, 2 * day + 3600000));
        assertEquals("x 찜했어요.", Brief.saved(new Place("3", "x", "음식점 > 한식 > 육류,고기", 0, 0), 0, 0));
        assertEquals("찜했어요.", Brief.saved(new Place("4", " ", "", 0, 0), 0, 0));
        assertEquals("약국", Brief.kind(new Place("5", "x", "의료,건강 > 약국", 0, 0)));
        assertEquals("치킨집", Brief.kind(new Place("6", "x", "음식점 > 치킨", 0, 0)));
        assertEquals("", Brief.area("서울특별시 마포구"));
        assertEquals("종로1가", Brief.area("서울 종로구 종로1가 1"));
        assertEquals("대치동", Brief.area("서울 강남구 대치동 316 101동")); // building numbers are not neighbourhoods
    }

    @Test public void arrivalSummary() {
        assertNull(Brief.arrival(0, null));
        assertEquals("오늘 2곳 찜했어요.", Brief.arrival(2, null));
        assertEquals("오늘 1곳 찜했어요. 노을도 근처예요.", Brief.arrival(1, "노을"));
        assertNotNull(Brief.arrival(0, "노을"));
    }

    private static Saved.Item item(String name, String category, String address) {
        return new Saved.Item("1", name, category, "", address, "", 0, 0, 0, true, new java.util.ArrayList<>());
    }

    @Test public void savedListGroupsByKindAndDistrict() {
        assertEquals("카페", Saved.kind(item("빔봄", "음식점 > 카페 > 커피전문점", "")));
        assertEquals("고기집", Saved.kind(item("꽃살집", "육류,고기", "")));          // older save: last level only
        assertEquals("편의점", Saved.kind(item("CU 응암스마일점", "CU", "")));       // brand-only category, name says it
        assertEquals("한식", Saved.kind(item("뚝딱", "음식점 > 한식 > 찌개,전골", "")));
        assertEquals("기타", Saved.kind(item("문", "라이브", "")));
        assertEquals("커피전문점", Saved.label(item("빔봄", "음식점 > 카페 > 커피전문점", "")));
        assertEquals("마포구", Saved.district(item("a", "", "서울 마포구 동교동 153-11")));
        assertEquals("분당구", Saved.district(item("a", "", "경기 성남시 분당구 정자동 1")));
        assertEquals("양평군", Saved.district(item("a", "", "경기 양평군 양평읍 1")));
        assertEquals("", Saved.district(item("a", "", "")));
    }

    @Test public void bareSideQuestionIsARequest() {
        assertTrue(Hints.parse("좀 전에 우측에 뭐야?").request);
    }

    /** Route to a destination: confirmed saves within a short walk of it, nearest first; not the destination itself. */
    @Test public void savesNearTheDestination() {
        double lat = 37.5600, lon = 126.9250, m = 1 / 111195.0;
        java.util.List<Saved.Item> items = java.util.Arrays.asList(
                place("far", lat + 900 * m, lon, true), place("꽃살집", lat + 400 * m, lon, true), place("guess", lat + 100 * m, lon, false),
                place("there", lat + 10 * m, lon, true), place("우둔살집", lat + 200 * m, lon, true));
        java.util.List<String> names = new java.util.ArrayList<>();
        for (Saved.Item i : Saved.nearDestination(items, lat, lon)) names.add(i.name);
        assertEquals(java.util.Arrays.asList("우둔살집", "꽃살집"), names);
        long day = 86400000L;
        assertEquals("목적지 근처에 3주 전 찜한 꽃살집이 있어요. 들를까요?", Brief.nearDestination("꽃살집", day, 1, 22 * day));
        assertEquals("목적지 근처에 어제 찜한 노을이 있어요. 들를까요?", Brief.nearDestination("노을", day, 1, 2 * day + 3600000));
        assertEquals("목적지 근처에 오늘 찜한 카페가 있어요. 들를까요?", Brief.nearDestination("카페", day, 1, day + 60000));
        assertEquals("목적지 근처에 찜한 곳이 2곳 있어요. 제일 가까운 우둔살집, 들를까요?", Brief.nearDestination("우둔살집", day, 2, 3 * day));
    }

    private static Saved.Item place(String name, double lat, double lon, boolean confirmed) {
        return new Saved.Item(name, name, "음식점", "", "", "", lat, lon, 0, confirmed, new java.util.ArrayList<>());
    }
}
