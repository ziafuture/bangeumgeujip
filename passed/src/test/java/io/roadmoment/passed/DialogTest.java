package io.roadmoment.passed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class DialogTest {
    static class Log implements Dialog.Out {
        final List<String> said = new ArrayList<>(); final List<String> saved = new ArrayList<>();
        @Override public void say(String text, boolean thenListen) { said.add(text + (thenListen ? " ?" : "")); }
        @Override public void save(List<PassedFinder.Candidate> picks, boolean confirmed) {
            StringBuilder b = new StringBuilder();
            for (PassedFinder.Candidate c : picks) b.append(b.length() == 0 ? "" : "/").append(c.place.name);
            saved.add(b + (confirmed ? "" : "(미확인)"));
        }
        @Override public String savedSentence(PassedFinder.Candidate c) { return "저장했어요."; }
        boolean closed;
        @Override public void closed() { closed = true; }
        String last() { return said.get(said.size() - 1); }
    }
    /** name, seconds passed before speech, score. */
    static PassedFinder.Candidate c(String name, double secondsBefore, double score) {
        return new PassedFinder.Candidate(new Place(name, name, "음식점 > 한식 > 육류,고기", 0, 0), 10, Math.round(secondsBefore * 1000), Hints.Side.RIGHT, score);
    }
    static List<PassedFinder.Candidate> clear() { return Arrays.asList(c("달무리", 2, 2.0), c("노을", 3, 1.0)); }
    static List<PassedFinder.Candidate> close() { return Arrays.asList(c("한우마을", 2.2, 2.0), c("돼지명가", 3.5, 1.9), c("소문난갈비", 5, 1.2)); }
    static Hints meatRight() { return Hints.parse("방금 우측 고기집 뭐야"); }

    @Test public void clearWinnerGetsOneQuestion() {
        Log log = new Log(); Dialog d = new Dialog(log);
        d.start(clear(), Hints.parse("방금 그거 뭐야"));
        assertEquals("오른쪽 달무리, 고기집 맞죠? ?", log.said.get(0));
        d.answer("응 맞아");
        assertEquals(Arrays.asList("달무리"), log.saved);
        assertFalse(d.awaitingAnswer());
    }

    @Test public void closeCallKeepsBothWithoutAskingWhenPairsAreOff() {
        Log log = new Log(); Dialog d = new Dialog(log, false);
        d.start(close(), meatRight());
        assertEquals(Arrays.asList("한우마을/돼지명가(미확인)"), log.saved);
        assertEquals("오른쪽 고기집 두 곳 담아 둘게요. 차 세우고 골라 주세요.", log.last());
        assertFalse(d.awaitingAnswer());
        assertEquals(1, log.said.size());
    }

    @Test public void closeCallOffersBothInPassingOrder() {
        Log log = new Log(); Dialog d = new Dialog(log, true);
        d.start(close(), meatRight());
        assertEquals("돼지명가와 한우마을 중 어디예요? ?", log.said.get(0));
    }

    /** With a front passenger the pair question says once per trip that they may pick on the screen. */
    @Test public void passengerHearsTheyMayTap() {
        Log log = new Log(); Dialog d = new Dialog(log); d.passenger(true);
        d.start(close(), meatRight());
        assertEquals("돼지명가와 한우마을 중 어디예요? 옆에서 화면으로 골라 주셔도 돼요. ?", log.last());
        d.answer("둘 다");
        d.start(close(), meatRight());
        assertEquals("돼지명가와 한우마을 중 어디예요? ?", log.last());
    }

    @Test public void pairAnsweredByOrder() {
        Log log = new Log(); Dialog d = new Dialog(log, true); d.start(close(), meatRight());
        d.answer("먼저 거");
        assertEquals(Arrays.asList("돼지명가"), log.saved);
        log = new Log(); d = new Dialog(log, true); d.start(close(), meatRight());
        d.answer("나중 거");
        assertEquals(Arrays.asList("한우마을"), log.saved);
        log = new Log(); d = new Dialog(log, true); d.start(close(), meatRight());
        d.answer("나중에 본 거");
        assertEquals(Arrays.asList("한우마을"), log.saved);
        log = new Log(); d = new Dialog(log, true); d.start(close(), meatRight());
        d.answer("먼저 본 가게");
        assertEquals(Arrays.asList("돼지명가"), log.saved);
    }

    @Test public void pairAnsweredByName() {
        Log log = new Log(); Dialog d = new Dialog(log, true); d.start(close(), meatRight());
        d.answer("한우 거");
        assertEquals(Arrays.asList("한우마을"), log.saved);
    }

    @Test public void pairBoth() {
        Log log = new Log(); Dialog d = new Dialog(log, true); d.start(close(), meatRight());
        d.answer("둘 다");
        assertEquals(Arrays.asList("돼지명가", "한우마을"), log.saved);
        assertEquals("돼지명가, 한우마을 둘 다 찜했어요.", log.last());
    }

    /** The user's own example (2026-11-03): two names sharing "살집", answered vaguely or by name. */
    @Test public void pairByNameSharingAWord() {
        List<PassedFinder.Candidate> l = Arrays.asList(c("우둔살집", 2, 2.0), c("꽃살집", 4, 1.9));
        Log log = new Log(); Dialog d = new Dialog(log); d.start(l, Hints.parse("방금 우측에 지나간 음식점 뭐야?"));
        assertEquals("꽃살집과 우둔살집 중 어디예요? ?", log.last());
        d.answer("모르겠어");
        assertEquals(Arrays.asList("꽃살집", "우둔살집"), log.saved);
        assertEquals("그럼 꽃살집, 우둔살집 둘 다 찜했어요.", log.last());

        log = new Log(); d = new Dialog(log); d.start(l, Hints.parse("방금 우측에 지나간 음식점 뭐야?"));
        d.answer("꽃살집이었던 거 같은데");
        assertEquals(Arrays.asList("꽃살집"), log.saved);
        log = new Log(); d = new Dialog(log); d.start(l, Hints.parse("방금 우측에 지나간 음식점 뭐야?"));
        d.answer("우둔살");
        assertEquals(Arrays.asList("우둔살집"), log.saved);
        assertEquals(Hints.Answer.UNSURE, Hints.answer("잘 몰라"));
        assertEquals(Hints.Answer.UNSURE, Hints.answer("기억 안 나"));
        assertEquals(Hints.Answer.UNSURE, Hints.answer("헷갈려"));
    }

    @Test public void pairNeitherAsksThird() {
        Log log = new Log(); Dialog d = new Dialog(log, true); d.start(close(), meatRight());
        d.answer("아니 둘 다 아니야");
        assertEquals("오른쪽 소문난갈비, 고기집 맞죠? ?", log.last());
        d.answer("응");
        assertEquals(Arrays.asList("소문난갈비"), log.saved);
    }

    @Test public void silenceSavesGroupToPickLater() {
        Log log = new Log(); Dialog d = new Dialog(log, true); d.start(close(), meatRight());
        d.answer(null);
        assertEquals(Arrays.asList("돼지명가/한우마을/소문난갈비(미확인)"), log.saved);
        assertEquals("후보로 담아 둘게요.", log.last());
    }

    @Test public void singleThenEarlierMovesBackInTime() {
        List<PassedFinder.Candidate> l = Arrays.asList(c("가", 2, 3.0), c("나", 1, 2.0), c("다", 4, 1.0), c("라", 6, 0.5));
        Log log = new Log(); Dialog d = new Dialog(log); d.start(l, Hints.parse("방금"));
        assertTrue(log.last().contains("가"));
        d.answer("아니 그 전에 거");
        assertTrue(log.last(), log.last().contains("다")); // passed 4 s ago: the nearest one before "가"
        d.answer("아니 더 먼저");
        assertTrue(log.last(), log.last().contains("라"));
        d.answer("응");
        assertEquals(Arrays.asList("라"), log.saved);
    }

    @Test public void singleThenLaterMovesForward() {
        List<PassedFinder.Candidate> l = Arrays.asList(c("가", 3, 3.0), c("나", 1, 1.0), c("다", 5, 2.0));
        Log log = new Log(); Dialog d = new Dialog(log); d.start(l, Hints.parse("방금"));
        d.answer("아니 방금 지난 거");
        assertTrue(log.last(), log.last().contains("나"));
    }

    @Test public void singleAnsweredWithAnotherName() {
        List<PassedFinder.Candidate> l = Arrays.asList(c("달무리커피", 2, 3.0), c("노을베이커리", 3, 1.0));
        Log log = new Log(); Dialog d = new Dialog(log); d.start(l, Hints.parse("방금"));
        d.answer("아니 노을");
        assertEquals(Arrays.asList("노을베이커리"), log.saved);
    }

    @Test public void atMostThreeQuestions() {
        List<PassedFinder.Candidate> l = Arrays.asList(c("a", 2, 5), c("b", 3, 4), c("c", 4, 3), c("d", 5, 2), c("e", 6, 1));
        Log log = new Log(); Dialog d = new Dialog(log); d.start(l, Hints.parse("방금"));
        d.answer("아니"); d.answer("아니"); d.answer("아니");
        assertEquals("알겠어요, 안 할게요.", log.last());
        assertEquals(4, log.said.size());
        assertTrue(log.saved.isEmpty());
    }

    @Test public void cancelEndsQuietly() {
        Log log = new Log(); Dialog d = new Dialog(log); d.start(clear(), Hints.parse("방금"));
        d.answer("아 됐어");
        assertTrue(log.closed);
        assertTrue(log.saved.isEmpty());
        assertFalse(d.awaitingAnswer());
        assertEquals(1, log.said.size()); // no extra speech
    }

    @Test public void unclearAnswerIsAskedAgainOnce() {
        Log log = new Log(); Dialog d = new Dialog(log); d.start(clear(), Hints.parse("방금"));
        d.answer("음 글쎄");
        assertEquals("응이나 아니, 아니면 이름으로 말해 주세요. ?", log.last());
        d.answer("음 글쎄");
        assertEquals(Arrays.asList("달무리/노을(미확인)"), log.saved);
    }

    @Test public void bareYesToPairAsksForOrder() {
        Log log = new Log(); Dialog d = new Dialog(log, true); d.start(close(), meatRight());
        d.answer("응");
        assertTrue(log.saved.isEmpty());
        assertEquals("아까 본 가게예요, 방금 본 가게예요? ?", log.last());
        d.answer("방금 거");
        assertEquals(Arrays.asList("한우마을"), log.saved);
        log = new Log(); d = new Dialog(log, true); d.start(close(), meatRight());
        d.answer("응"); d.answer("응"); // still no choice: keep both for later
        assertEquals(Arrays.asList("돼지명가/한우마을/소문난갈비(미확인)"), log.saved);
    }

    @Test public void clippedYesIsNotTakenAsYes() {
        // ElevenLabs test: "음, 글쎄" (about 1.5 s of speech) came back as "응".
        assertTrue(Hints.clipped("응", 1090));  // measured on the emulator mic
        assertFalse(Hints.clipped("응", 399));
        assertTrue(Hints.clipped("응", 1500));
        assertFalse(Hints.clipped("응", 400));
        assertFalse(Hints.clipped("응 맞아", 1500));
        assertFalse(Hints.clipped("아니", 1500));
        Log log = new Log(); Dialog d = new Dialog(log); d.start(clear(), Hints.parse("방금"));
        d.answer("응", Hints.clipped("응", 1500));
        assertTrue(log.saved.isEmpty());
        assertEquals("응이나 아니, 아니면 이름으로 말해 주세요. ?", log.last());
        d.answer("응", Hints.clipped("응", 400));
        assertEquals(Arrays.asList("달무리"), log.saved);
    }

    @Test public void rejectingANameInPairPicksTheOther() {
        Log log = new Log(); Dialog d = new Dialog(log, true); d.start(close(), meatRight());
        d.answer("한우 말고");
        assertEquals(Arrays.asList("돼지명가"), log.saved);
    }

    @Test public void answerParsing() {
        assertEquals(Hints.Answer.NO, Hints.answer("어 아니야"));
        assertEquals(Hints.Answer.NO, Hints.answer("안 맞아"));
        assertEquals(Hints.Answer.CANCEL, Hints.answer("저장하지 마"));
        assertEquals(Hints.Answer.CANCEL, Hints.answer("다음에"));
        assertEquals(Hints.Answer.NONE, Hints.answer("카페네"));
        assertEquals(Hints.Answer.NONE, Hints.answer("어디였지"));
        assertEquals(Hints.Answer.NO, Hints.answer("둘 다 아니야"));
        assertEquals(Hints.Answer.LATER, Hints.answer("방금 전에 거"));
        assertEquals(Hints.Answer.YES, Hints.answer("네"));
        assertEquals(Hints.Answer.YES, Hints.answer("응 맞아"));
        assertEquals(Hints.Answer.NO, Hints.answer("아뇨"));
    }

    @Test public void triggerCollisions() {
        assertFalse(Hints.parse("오늘 갈비찜 먹을까").request);
        assertFalse(Hints.parse("아까워 그거").request);
        assertFalse(Hints.parse("저기 봐봐").request);           // weak word alone
        assertTrue(Hints.parse("저기 오른쪽 카페 뭐야").request); // weak word + side/kind
        assertTrue(Hints.parse("이거 찜해 줘").request);
        assertTrue(Hints.parse("아까 그 집").request);
        assertTrue(Hints.parse("꽃게 맛있겠다").kinds.isEmpty());
        assertTrue(Hints.parse("빵빵거리지 마").kinds.isEmpty());
        assertTrue(Hints.parse("국회의원 사무실").kinds.isEmpty());
    }

    @Test public void nothingFound() {
        Log log = new Log(); Dialog d = new Dialog(log);
        d.start(new ArrayList<>(), Hints.parse("방금"));
        assertFalse(d.awaitingAnswer());
        assertEquals("지나친 가게를 못 찾았어요.", log.said.get(0));
    }

    @Test public void kinds() {
        assertEquals(Arrays.asList("고기집"), Hints.parse("방금 우측 고기집 뭐야").kinds);
        assertEquals(Arrays.asList("고기집"), Hints.parse("방금 그 고깃집 식당").kinds);
        assertEquals(Arrays.asList("고기집"), Hints.parse("아까 갈비집 저장해").kinds);
        assertEquals(Arrays.asList("카페"), Hints.parse("방금 그 카페에 뭐야").kinds);
        assertEquals(Arrays.asList("식당"), Hints.parse("방금 그 식당").kinds);
        assertEquals(Hints.Side.RIGHT, Hints.parse("방금 우측 고기집 뭐야").side);
        assertEquals("이", Dialog.subject("고기집"));
        assertEquals("가", Dialog.subject("카페"));
    }

    @Test public void parsesKakaoResponse() throws Exception {
        String body = "{\"documents\":[{\"id\":\"123\",\"place_name\":\"테스트카페\",\"category_name\":\"음식점 > 카페\","
                + "\"x\":\"126.9230\",\"y\":\"37.5600\",\"place_url\":\"http://place.map.kakao.com/123\",\"distance\":\"40\"}],\"meta\":{}}";
        Map<String, Place> into = new LinkedHashMap<>();
        KakaoPlaces.parse(body, into);
        Place p = into.get("123");
        assertEquals("테스트카페", p.name);
        assertEquals(37.56, p.lat, 1e-9);
        assertEquals("카페", p.shortCategory());
    }

    /** The colour the driver gave is said back, and learned only from a pick of one place. */
    @Test public void signColourIsSaidBackAndLearnedFromAPick() {
        final List<String> learned = new ArrayList<>();
        Log log = new Log() { @Override public void picked(PassedFinder.Candidate c, Hints h) { learned.add(c.place.name + "=" + h.color); } };
        Dialog d = new Dialog(log);
        d.start(close(), Hints.parse("좀전에 빨간 간판 음식점 뭐야"));
        assertEquals("빨간 간판이면 돼지명가와 한우마을 중 어디예요? ?", log.said.get(0));
        d.answer("한우마을");
        assertEquals(Arrays.asList("한우마을=빨강"), learned);

        learned.clear(); log.said.clear();
        d.start(close(), Hints.parse("좀전에 빨간 간판 음식점 뭐야"));
        d.answer("모르겠어"); // both kept, neither is known to be red
        assertTrue(learned.isEmpty());

        List<PassedFinder.Candidate> known = Arrays.asList(
                new PassedFinder.Candidate(new Place("달무리", "달무리", "음식점 > 한식", 0, 0), 10, 2000, Hints.Side.RIGHT, 3.0, "빨강"), c("노을", 3, 1.0));
        d.start(known, Hints.parse("좀전에 빨간 간판 뭐야"));
        assertEquals("오른쪽 빨간 간판 달무리, 한식집 맞죠? ?", log.last());
    }
}
