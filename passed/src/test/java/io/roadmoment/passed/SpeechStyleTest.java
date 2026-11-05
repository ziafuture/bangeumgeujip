package io.roadmoment.passed;

import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * How drivers actually talk: regional dialects (경상, 전라, 충청, 강원, 제주), elderly and casual speech, fillers,
 * and the way the recogniser writes run-together or hesitant speech. Each line is a transcript as the recogniser
 * would return it and what the app must take from it.
 */
public class SpeechStyleTest {
    private final List<String> wrong = new ArrayList<>();

    private void request(String text) { if (!Hints.parse(text).request) wrong.add("요청 아님: " + text); }
    private void maybe(String text) { Hints h = Hints.parse(text); if (!h.request && !h.maybe) wrong.add("확인도 안 함: " + text); }
    private void notRequest(String text) { if (Hints.parse(text).request) wrong.add("요청으로 오인: " + text); }
    private void side(String text, Hints.Side s) { if (Hints.parse(text).side != s) wrong.add("방향 " + s + " 놓침: " + text); }
    private void kind(String text, String k) { if (!Hints.parse(text).kinds.contains(k)) wrong.add("종류 " + k + " 놓침: " + text + " → " + Hints.parse(text).kinds); }
    private void answer(String text, Hints.Answer a) { Hints.Answer got = Hints.answer(text); if (got != a) wrong.add("대답 " + a + " 아님(" + got + "): " + text); }
    private void recall(String text) { if (!Chat.recall(text)) wrong.add("찜 목록 질문 놓침: " + text); }
    private void go(String text) { if (!Chat.go(text)) wrong.add("가자 놓침: " + text); }
    private void interest(String text) { if (!Chat.interest(text)) wrong.add("관심 놓침: " + text); }
    private void thanks(String text) { if (!Chat.thanks(text)) wrong.add("감사 놓침: " + text); }
    private void notThanks(String text) { if (Chat.thanks(text)) wrong.add("감사로 오인: " + text); }

    @Test
    public void dialectsAndSpeechStyles() {
        // 경상
        request("방금 그거 뭐꼬"); request("아까 그 집 뭐라카노"); request("금방 지나간 데 뭐고"); request("금방 그 가게 이름 뭐였노");
        request("저 오른짝 고깃집 뭐꼬"); side("저 오른짝 고깃집 뭐꼬", Hints.Side.RIGHT); request("쪼매 전에 지나간 카페 뭐고");
        request("방금 그거 찜해 도"); request("방금 거 저장해 주이소"); maybe("그거 뭐꼬"); maybe("뭐였더라 그거");
        // 전라
        request("방금 거시기 뭐당가"); request("아까 그 집 뭐여"); request("쩌그 왼짝 카페 뭐랑가"); request("저도 인자 카페 뭐란가"); // 인식기가 "쩌그 왼짝"을 이렇게 받아 적음 side("쩌그 왼짝 카페 뭐랑가", Hints.Side.LEFT);
        request("쪼까 전에 지나간 고깃집 뭐여"); request("방금 그거 찜혀 줘"); request("방금 그거 저장혀"); maybe("그거 뭐여");
        // 충청
        request("방금 그거 뭐래유"); request("아까 지나간 국밥집 뭐였슈"); request("저짝 오른쪽 빵집 뭐유"); maybe("그거 뭐유");
        // 강원·제주
        request("방금 그거 뭐래요"); request("방금 그거 무신 거우꽈"); request("요짝 오른쪽 식당 무시거라"); maybe("그거 무신거라");
        // 어르신·정중체·필러·더듬음·빠른 말(붙여 쓰기)
        request("아이고 아까 그 지나온 집이 뭐였는지 좀 알려 줄래요"); request("어 음 방금 그 그거 뭐였지"); request("바 방금 그거 뭐야");
        request("방금그거뭐야"); request("아까그집뭐였더라"); request("방금 지나친 데 이름 좀"); request("방금 그거 뭐였더라 그 빨간 거");
        kind("방금 지나간 고기집 뭐꼬", "고기집"); kind("아까 그 커피집 뭐였노", "카페"); kind("아까 그 국수집 뭐여", "면");
        // 동승자 잡담은 요청이 아님
        notRequest("저기 날씨 좋네"); notRequest("여기 길 막히네"); notRequest("아까워라 그거"); notRequest("오늘 저녁 뭐 묵꼬");

        // 대답: 예
        for (String t : new String[] { "어 맞다", "맞다", "하모", "하모요", "맞심더", "예 맞습니더", "그래 해라", "잉", "응 맞어", "맞당께", "그라제",
                "그려", "그려유", "맞어유", "그류", "기여", "예게", "어어", "응응 맞아", "네네", "넵", "웅", "엉", "그렇지", "맞는 거 같아" })
            answer(t, Hints.Answer.YES);
        // 대답: 아니
        for (String t : new String[] { "아이다", "아인데", "아이라", "아니다", "아니여", "아녀유", "아니랑께", "아니당께", "아이요", "아니 그거 말고", "그거 아이고", "틀렸다", "필요 없다 마", "그렇지 않아" })
            answer(t, Hints.Answer.NO);
        // 대답: 그만
        for (String t : new String[] { "됐다", "고마 됐다", "치아라", "마 치아라", "고마해라", "냅둬", "놔둬라", "됐슈", "됐당께" })
            answer(t, Hints.Answer.CANCEL);
        // 대답: 모르겠음
        for (String t : new String[] { "모르겄어", "모르것는디", "모르겠심더", "모린다", "기억이 안 나네", "생각이 안 나", "까먹었다", "까묵었다", "헷갈리네" })
            answer(t, Hints.Answer.UNSURE);
        // 대답: 둘 다 / 먼저 / 나중
        for (String t : new String[] { "둘 다", "둘 다 해라", "두 개 다", "둘이 다 해 도" }) answer(t, Hints.Answer.BOTH);
        for (String t : new String[] { "아까 꺼", "먼저 꺼", "처음 꺼", "첫 번째 꺼", "앞에 꺼" }) answer(t, Hints.Answer.EARLIER);
        for (String t : new String[] { "방금 꺼", "금방 꺼", "나중 꺼", "뒤에 꺼", "두 번째" }) answer(t, Hints.Answer.LATER);

        // 대화
        for (String t : new String[] { "찜한 거 뭐 있노", "찜해둔 데 어데고", "찜한 데 뭐 있드라", "찜한 거 뭐 있당가", "찜한 거 뭐 있슈", "저장해 놓은 데 어데 있노" }) recall(t);
        for (String t : new String[] { "가입시더", "가보자", "가 보까", "가자잉", "가 봅시다", "가유", "거기로 가 주이소", "가자 마", "그리 가자" }) go(t);
        for (String t : new String[] { "맛있겠다", "억수로 맛있어 보인다", "맛나겠다", "맛있겄다", "맛있것네", "사람 억수로 많네", "사람 겁나 많네", "줄 서 있노", "와 줄 봐라" }) interest(t);
        for (String t : new String[] { "고맙데이", "고맙습니더", "고마버", "고맙당께", "고마워유", "욕봤다", "잘했네", "똑똑하네", "최고다" }) thanks(t);
        notThanks("고마해라");

        assertTrue(wrong.size() + "건\n" + String.join("\n", wrong), wrong.isEmpty());
    }
}
