package io.roadmoment.passed;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.TimeZone;

/**
 * Small talk around saving, so the app answers like someone in the passenger seat: "아까 찜한 데 어디였지?" is
 * answered from the saves and can turn into a route, "와 저기 맛있겠다" gets "찜해 둘까요?", "고마워" gets a
 * reply, and a run of the same kind of place gets a remark. Only our own saves are used; nothing is made up.
 */
final class Chat {
    private Chat() {}

    /** Asking about earlier saves: a save word in the past tense plus a question or a "tell me". */
    static boolean recall(String text) {
        String c = Hints.compact(text);
        if (!has(c, "찜한", "찜했", "찜해둔", "찜해놓", "찜해논", "저장한", "저장했", "저장해둔", "저장해놓", "저장해논", "담은", "담았", "담아둔")) return false;
        return has(c, "뭐", "머", "어디", "어데", "어딨", "있어", "있나", "있었", "있노", "있드", "있당", "있슈", "알려", "읽어", "몇", "무슨", "어떤",
                "가자", "가줘", "안내");
    }

    /** "가자", "안내해 줘", "들르자": the driver wants to go somewhere. */
    static boolean go(String text) {
        return has(Hints.compact(text), "가자", "가줘", "가주", "안내", "들르자", "들를래", "들러", "가볼래", "가볼까", "갈래", "갈까",
                "가보자", "가보까", "가봅시", "갑시다", "가입시", "가십시", "가유", "가쥬"); // 경상 가입시더, 충청 가유
    }

    /** Said on seeing a shop, not a request: worth one "찜해 둘까요?". */
    static boolean interest(String text) {
        String c = Hints.compact(text);
        return has(c, "맛있겠", "맛있어보", "맛있어보여", "맛집같", "맛집인가", "가보고싶", "가봐야겠", "가봐야지", "줄서", "줄이길", "줄길",
                "사람많", "웨이팅", "분위기좋", "괜찮아보", "궁금하다", "궁금해", "예쁘다", "이쁘다", "새로생겼", "새로생긴")
                || c.matches(".*줄.{0,3}(길|서있).*") // "줄 엄청 길다"
                || c.matches(".*맛.{0,2}(있|나)(겠|겄|것|긋|어보|아보).*") // "맛나겠다", 전라 "맛있겄다"
                || c.matches(".*사람.{0,4}많.*") || has(c, "줄봐", "줄좀봐"); // "사람 억수로 많네"
    }

    /** Thanks or praise. Only answered right after the app spoke; otherwise it was probably said to someone else. */
    static boolean thanks(String text) {
        return has(Hints.compact(text), "고마워", "고마버", "고마바", "고마와", "고맙", "감사", "땡큐", "잘했", "최고", "똑똑하", "똑똑해",
                "욕봤", "수고했", "수고하"); // not "고마": 경상 "고마해라" is "stop it"
    }

    private static final String[] YOU_ARE_WELCOME = { "천만에요.", "별말씀을요.", "언제든 불러 주세요." };
    static String welcome(int n) { return YOU_ARE_WELCOME[Math.floorMod(n, YOU_ARE_WELCOME.length)]; }

    /** A time span in what was said, [from, to) in wall-clock ms, with the word to say back ("오늘", "" for any time). */
    static final class Period {
        final long from, to; final String word;
        Period(long from, long to, String word) { this.from = from; this.to = to; this.word = word; }
    }

    static Period period(String text, long now, TimeZone tz) {
        String c = Hints.compact(text);
        long today = startOfDay(now, tz), day = 86400000L;
        if (c.contains("어제")) return new Period(today - day, today, "어제");
        if (has(c, "오늘", "아까", "방금", "좀전", "조금전")) return new Period(today, Long.MAX_VALUE, "오늘");
        if (c.contains("지난주")) return new Period(now - 14 * day, now - 7 * day + day, "지난주"); // loosely: a week or two ago
        if (has(c, "이번주", "요즘", "최근", "요새")) return new Period(now - 7 * day, Long.MAX_VALUE, "이번 주");
        return new Period(0, Long.MAX_VALUE, "");
    }

    static long startOfDay(long now, TimeZone tz) {
        Calendar cal = Calendar.getInstance(tz); cal.setTimeInMillis(now);
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }

    /** Confirmed saves in the period and of the kind asked about ("찜한 고깃집"), newest first. */
    static List<Saved.Item> find(List<Saved.Item> items, Period p, Hints h) {
        List<Saved.Item> out = new ArrayList<>();
        for (Saved.Item i : items) {
            if (!i.confirmed || Saved.isGroup(i) || i.savedAt < p.from || i.savedAt >= p.to) continue;
            if (!h.kinds.isEmpty() && !ofKind(i, h.kinds.get(0))) continue;
            out.add(i);
        }
        Collections.sort(out, (a, b) -> Long.compare(b.savedAt, a.savedAt));
        return out;
    }

    /** Unconfirmed guesses in the period, still to be picked after parking. */
    static int pending(List<Saved.Item> items, Period p) {
        int n = 0;
        for (Saved.Item i : items) if (!i.confirmed && i.savedAt >= p.from && i.savedAt < p.to) n++;
        return n;
    }

    private static boolean ofKind(Saved.Item i, String kind) {
        if (Saved.kind(i).equals(kind)) return true;
        String text = i.category + " " + i.name;
        for (String w : Hints.categoryWords(kind)) if (text.contains(w)) return true;
        return false;
    }

    static final int READ_OUT = 3;

    /**
     * The answer to a recall question. One place ends in "가 볼까요?", two or more in "어디로 갈까요?" with at most
     * {@link #READ_OUT} names read out, newest first.
     */
    static String answer(List<Saved.Item> found, int pending, Period p, Hints h) {
        String when = p.word.isEmpty() ? "" : p.word + " ";
        String what = h.kinds.isEmpty() ? "곳" : Hints.spokenKind(h);
        if (found.isEmpty()) {
            if (pending > 0) return when + "찜한 건 아직 후보로만 있어요. 차 세우고 골라 주세요.";
            return when + "찜한 " + what + Dialog.topic(what) + " 없어요.";
        }
        String head = when + "찜한 " + what + Dialog.topic(what) + " ";
        if (found.size() == 1) return head + found.get(0).name + Dialog.copula(found.get(0).name) + ". 가 볼까요?";
        List<String> names = new ArrayList<>();
        for (int i = 0; i < Math.min(READ_OUT, found.size()); i++) names.add(found.get(i).name);
        String list = String.join(", ", names);
        if (found.size() <= READ_OUT) return head + list + ", " + count(found.size()) + " 곳이에요. 어디로 갈까요?";
        return head + count(found.size()) + " 곳이에요. 최근엔 " + list + Dialog.copula(names.get(names.size() - 1)) + ". 어디로 갈까요?";
    }

    /** Which of the places read out the driver picked: by name, or "첫 번째" / "두 번째" / "마지막". -1 when unclear. */
    static int pick(String text, List<Saved.Item> choices) {
        int hit = -1;
        for (int i = 0; i < choices.size(); i++) {
            Place p = place(choices.get(i));
            boolean mine = Hints.mentions(text, p); // a part of the name no other choice shares
            for (int j = 0; j < choices.size(); j++) if (j != i) mine &= Hints.mentions(text, p, place(choices.get(j)));
            if (mine) { if (hit >= 0) return -1; hit = i; }
        }
        if (hit >= 0) return hit;
        String c = Hints.compact(text);
        if (has(c, "마지막")) return choices.size() - 1;
        if (has(c, "첫번째", "첫째", "처음", "첫번")) return 0;
        if (has(c, "두번째", "둘째", "두번")) return choices.size() > 1 ? 1 : -1;
        if (has(c, "세번째", "셋째", "세번")) return choices.size() > 2 ? 2 : -1;
        return -1;
    }

    /** A saved place named in what was said ("찜한 꽃살집으로 가자"), or null when none or more than one. */
    static Saved.Item named(String text, List<Saved.Item> items) {
        List<Saved.Item> confirmed = new ArrayList<>();
        for (Saved.Item i : items) if (i.confirmed && !Saved.isGroup(i)) confirmed.add(i);
        int k = pick(text, confirmed);
        // Ordinals mean nothing without a list read out first.
        return k >= 0 && Hints.mentions(text, place(confirmed.get(k))) ? confirmed.get(k) : null;
    }

    static final long STREAK_DAYS = 7;

    /**
     * A remark when the driver keeps saving the same kind of place: "요즘 고기집 자주 찜하시네요." after two other
     * confirmed saves of that kind in the last week. Null otherwise.
     */
    static String streak(List<Saved.Item> items, Place p, long now) {
        String kind = Hints.kindOf(p.category, p.name);
        if (kind.equals("기타")) return null;
        int n = 0;
        for (Saved.Item i : items)
            if (i.confirmed && !i.id.equals(p.id) && now - i.savedAt < STREAK_DAYS * 86400000L && Saved.kind(i).equals(kind)) n++;
        return n >= 2 ? "요즘 " + spoken(kind) + " 자주 찜하시네요." : null;
    }

    private static String spoken(String kind) { return kind.equals("면") ? "면 요리" : kind; }

    private static final String[] NATIVE = { "", "한", "두", "세", "네", "다섯", "여섯", "일곱", "여덟", "아홉", "열" };
    static String count(int n) { return n < NATIVE.length ? NATIVE[n] : String.valueOf(n); }

    static Place place(Saved.Item i) { return new Place(i.id, i.name, i.category, i.lat, i.lon); }

    private static boolean has(String c, String... words) { for (String w : words) if (c.contains(w)) return true; return false; }
}
