package io.roadmoment.passed;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TimeZone;
import org.junit.Test;

public class ChatTest {
    static final TimeZone KST = TimeZone.getTimeZone("Asia/Seoul");
    static final long DAY = 86400000L, NOW = 1793000000000L; // an afternoon in 2026

    private static Saved.Item item(String name, String category, long at) {
        return new Saved.Item(name, name, category, "", "", "", 0, 0, at, true, new ArrayList<>());
    }

    @Test public void recognisesWhatIsSaidToTheApp() {
        assertTrue(Chat.recall("아까 찜한 데 어디였지?"));
        assertTrue(Chat.recall("오늘 찜한 거 뭐 있어"));
        assertTrue(Chat.recall("찜한 꽃살집으로 가자"));
        assertFalse(Chat.recall("방금 오른쪽 거 찜해 줘"));
        assertTrue(Chat.interest("와 저기 맛있겠다"));
        assertTrue(Chat.interest("줄 엄청 길다 저기"));
        assertFalse(Chat.interest("오늘 날씨 좋다"));
        assertTrue(Chat.thanks("고마워"));
        assertTrue(Chat.go("거기 가자"));
        assertTrue(Hints.parse("아까 찜한 집 어디였지").kinds.isEmpty()); // "찜한 집" is not a kind of shop
    }

    @Test public void answersFromTodaysSaves() {
        List<Saved.Item> items = Arrays.asList(
                item("꽃살집", "음식점 > 한식 > 육류,고기", NOW - 3600000),
                item("콘티키빠", "음식점 > 술집", NOW - 600000),
                item("어제카페", "음식점 > 카페", NOW - DAY - 3600000));
        Chat.Period today = Chat.period("아까 찜한 데 어디였지", NOW, KST);
        List<Saved.Item> found = Chat.find(items, today, Hints.parse("아까 찜한 데 어디였지"));
        assertEquals(2, found.size());
        assertEquals("콘티키빠", found.get(0).name); // newest first
        assertEquals("오늘 찜한 곳은 콘티키빠, 꽃살집, 두 곳이에요. 어디로 갈까요?", Chat.answer(found, 0, today, Hints.parse("아까 찜한 데")));

        Hints meat = Hints.parse("오늘 찜한 고깃집 어디였지");
        List<Saved.Item> one = Chat.find(items, today, meat);
        assertEquals("오늘 찜한 고기집은 꽃살집이에요. 가 볼까요?", Chat.answer(one, 0, today, meat));

        Chat.Period yesterday = Chat.period("어제 찜한 거 뭐야", NOW, KST);
        assertEquals("어제카페", Chat.find(items, yesterday, Hints.parse("어제 찜한 거 뭐야")).get(0).name);
        assertEquals("오늘 찜한 곳은 없어요.", Chat.answer(new ArrayList<>(), 0, today, Hints.parse("오늘 찜한 거")));
        assertEquals("오늘 찜한 건 아직 후보로만 있어요. 차 세우고 골라 주세요.", Chat.answer(new ArrayList<>(), 1, today, Hints.parse("오늘 찜한 거")));
    }

    @Test public void picksFromTheListReadOut() {
        List<Saved.Item> list = Arrays.asList(item("꽃살집", "음식점", 0), item("우둔살집", "음식점", 0), item("콘티키빠", "음식점", 0));
        assertEquals(0, Chat.pick("꽃살집으로 가자", list));
        assertEquals(1, Chat.pick("우둔살", list));
        assertEquals(1, Chat.pick("두 번째", list));
        assertEquals(2, Chat.pick("마지막 거", list));
        assertEquals(-1, Chat.pick("살집", list)); // shared by two names
        assertEquals("꽃살집", Chat.named("찜한 꽃살집으로 안내해 줘", list).name);
        assertNull(Chat.named("두 번째로 가자", list)); // no list was read out
    }

    @Test public void remarksOnARunOfTheSameKind() {
        List<Saved.Item> items = Arrays.asList(
                item("꽃살집", "음식점 > 한식 > 육류,고기", NOW - DAY), item("우둔살집", "음식점 > 한식 > 육류,고기", NOW - 2 * DAY));
        Place third = new Place("p3", "갈비명가", "음식점 > 한식 > 육류,고기 > 갈비", 0, 0);
        assertEquals("요즘 고기집 자주 찜하시네요.", Chat.streak(items, third, NOW));
        assertNull(Chat.streak(items.subList(0, 1), third, NOW));
        assertNull(Chat.streak(items, new Place("c", "카페만월경", "음식점 > 카페", 0, 0), NOW));
    }
}
