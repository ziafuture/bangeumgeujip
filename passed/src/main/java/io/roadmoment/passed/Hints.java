package io.roadmoment.passed;

import java.util.ArrayList;
import java.util.List;

/** What a final transcript tells us: is it a request, which side, which kind of place, any name. */
public final class Hints {
    public enum Side { LEFT, RIGHT, ANY }
    /** EARLIER/LATER refer to passing order: "아까 거" / "먼저 거" is the one passed first, "방금 거" the most recent. */
    public enum Answer { YES, NO, NEXT, EARLIER, LATER, BOTH, UNSURE, CANCEL, NONE }

    /** A kind of place: words a driver says, and words that appear in provider category text. */
    static final class Kind {
        final String name; final String[] spoken, category;
        Kind(String name, String[] spoken, String[] category) { this.name = name; this.spoken = spoken; this.category = category; }
    }

    /** Words that start a request on their own. */
    private static final String[] STRONG = { "방금", "아까", "좀전에", "조금전에", "쫌전에", "쪼매전에", "쪼까전에", "쪼끔전에", "조끔전에",
            "저장해", "저장하", "저장혀", "찜해", "찜하", "찜혀", "찜좀" };
    /** 경상·전라 "금방" is "방금", but also "soon" ("금방 갈게"): a request only with a question or 지나. */
    private static final String[] JUST_NOW = { "금방" };
    private static final String[] JUST_NOW_WITH = { "뭐", "머", "무신", "무시", "지나" };
    /** Common in passenger talk: count only together with a side or a kind of place ("저기 오른쪽 카페"). */
    private static final String[] WEAK = { "저거", "저기", "여기", "그거뭐", "뭐였", "그집", "저짝", "쩌그", "쩌기", "저그", "요짝", "이짝", "조짝" };
    /**
     * Question endings that survive when the opening word does not: an elderly woman's "방금 그거 뭐야?" came back
     * as "뭐야" or "그 뭐야" (ElevenLabs age test, 2026-11-03). Passengers say these too, so they only earn a
     * "방금 지나친 가게요?" check, never a search on their own.
     */
    private static final String[] MAYBE_END = { "뭐야", "뭐지", "뭐였더라", "뭐더라", "뭐였지", "뭐였어", "뭐였죠", "뭐예요", "뭐에요",
            "뭐꼬", "뭐고", "뭐노", "머꼬", "머고", "뭐였노", "뭐였드라", "뭐드라", "뭐여", "뭐였여", "뭐유", "뭐래유", "뭐였슈",
            "뭐당가", "뭐랑가", "뭐란가", "뭐다냐", "무신거라", "무신거", "무시거라", "무시거" }; // 경상, 충청, 전라, 제주
    /** Trailing words after the question ("뭐였더라 그거"). */
    private static final String[] TAIL = { "그거", "저거", "그집", "그거요" };
    /** Look like a trigger but are not ("아까워", "갈비찜" is already excluded by STRONG). */
    private static final String[] NOT_TRIGGER = { "아까워", "아깝", "아까운" };
    // Order matters: more specific kinds first, so "고깃집" is meat, not the generic restaurant.
    static final Kind[] KINDS = {
        kind("카페", "카페,커피,까페", "카페,커피"),
        kind("빵집", "빵집,빵가게,베이커리,제과,도넛", "베이커리,제과,빵,도넛"),
        kind("고기집", "고기,고깃,갈비,삼겹,곱창,막창,족발,보쌈,정육,소고기,돼지", "육류,고기,갈비,삼겹살,곱창,막창,족발,보쌈,정육"),
        kind("치킨", "치킨,통닭,닭강정", "치킨,닭강정"),
        kind("피자", "피자", "피자"),
        kind("국밥", "국밥,순대국,해장국,설렁탕,곰탕", "국밥,해장국,설렁탕,곰탕"),
        kind("분식", "분식,떡볶이,김밥", "분식,떡볶이,김밥"),
        kind("면", "국수,칼국수,냉면,라면,우동", "국수,칼국수,냉면,우동"),
        kind("중식", "중국집,중식,짜장,짬뽕,마라", "중식,중국요리,마라"),
        kind("일식", "일식,초밥,스시,라멘,돈까스,돈가스", "일식,초밥,롤,라멘,돈까스,돈가스"),
        kind("양식", "양식,파스타,브런치,스테이크", "양식,파스타,브런치,스테이크"),
        kind("햄버거", "햄버거,버거", "햄버거"),
        kind("술집", "술집,주점,호프,포차,이자카야,와인바", "술집,주점,호프,요리주점,포장마차,이자카야,와인"),
        kind("식당", "식당,음식점,맛집,밥집", "음식점"),
        kind("꽃집", "꽃집,꽃가게", "꽃"),
        kind("편의점", "편의점", "편의점,CU,GS25,세븐일레븐,이마트24"),
        kind("병원", "병원,치과,한의원,내과,소아과", "병원,의원,치과"),
        kind("약국", "약국", "약국"),
        kind("주유소", "주유소,주유", "주유소"),
        kind("마트", "마트", "마트"),
        kind("옷가게", "옷가게,옷집,의류", "의류,패션"),
    };
    private static Kind kind(String name, String spoken, String category) { return new Kind(name, spoken.split(","), category.split(",")); }

    /** Sign colours: the name we store, then words a driver says ("빨간 간판", "레드"). */
    private static final String[][] COLORS = {
        { "빨강", "빨간", "빨강", "붉은", "레드" }, { "주황", "주황", "오렌지" }, { "노랑", "노란", "노랑", "옐로" },
        { "초록", "초록", "녹색", "연두", "그린" }, { "파랑", "파란", "파랑", "블루", "하늘색" }, { "남색", "남색", "네이비" },
        { "보라", "보라", "퍼플" }, { "분홍", "분홍", "핑크" }, { "검정", "검은", "검정", "까만", "블랙" },
        { "흰색", "흰", "하얀", "화이트" }, { "갈색", "갈색", "브라운" }, { "금색", "금색", "골드", "황금" },
    };

    /** How a stored colour is said before 간판: 빨강 → "빨간". */
    static String colorWord(String color) {
        switch (color) {
            case "빨강": return "빨간"; case "노랑": return "노란"; case "파랑": return "파란";
            case "검정": return "검은"; case "흰색": return "하얀";
            default: return color;
        }
    }

    /**
     * {@code maybe}: not a request, but could be one whose opening word was lost; confirm before searching.
     * {@code color}: the sign colour the driver mentioned ("빨강"), or null.
     */
    public final boolean request, maybe; public final Side side; public final List<String> kinds; public final String compact, color;

    private Hints(boolean request, boolean maybe, Side side, List<String> kinds, String compact, String color) {
        this.request = request; this.maybe = maybe; this.side = side; this.kinds = kinds; this.compact = compact; this.color = color;
    }


    /** Says which place: a side, a kind or a sign colour. */
    boolean specific() { return side != Side.ANY || !kinds.isEmpty() || color != null; }

    /**
     * The app's own voice heard back through the mic ("[데모] 달무리 커피 찜했어요." as "대모다부리 커피 찜했어요"): the
     * emulator plays it on the host speaker next to the host mic, and a car without echo cancelling could do the same.
     * Character-pair overlap (Dice) of at least half; short replies like "응" never count.
     */
    static boolean echo(String heard, String said) {
        String h = compact(heard), s = compact(said);
        if (h.length() < 4 || s.length() < 4) return false;
        java.util.Map<String, Integer> pairs = new java.util.HashMap<>();
        for (int i = 0; i + 1 < s.length(); i++) pairs.merge(s.substring(i, i + 2), 1, Integer::sum);
        int common = 0;
        for (int i = 0; i + 1 < h.length(); i++) {
            Integer n = pairs.get(h.substring(i, i + 2));
            if (n != null && n > 0) { common++; pairs.put(h.substring(i, i + 2), n - 1); }
        }
        return 2.0 * common / (h.length() - 1 + s.length() - 1) >= 0.5;
    }

    static String compact(String text) { return text == null ? "" : text.replaceAll("[\\s\\p{Punct}]", ""); }

    public static Hints parse(String text) {
        String c = compact(text);
        String t = c;
        for (String w : NOT_TRIGGER) t = t.replace(w, "");
        boolean strong = false, weak = false;
        for (String w : STRONG) if (t.contains(w)) { strong = true; break; }
        for (String w : JUST_NOW) if (t.contains(w) && has(t, JUST_NOW_WITH)) strong = true;
        for (String w : WEAK) if (t.contains(w)) { weak = true; break; }
        Side side = has(c, "왼쪽", "왼편", "좌측", "왼짝", "왼켠") ? Side.LEFT
                : has(c, "오른쪽", "오른편", "우측", "오른짝", "오른켠", "바른쪽") ? Side.RIGHT : Side.ANY;
        List<String> kinds = new ArrayList<>();
        for (Kind k : KINDS) for (String w : k.spoken) if (c.contains(w)) { kinds.add(k.name); break; }
        if (kinds.isEmpty()) { String other = otherKind(text); if (other != null) kinds.add(other); }
        // "고깃집 식당" or "맛집 카페": a specific kind makes the generic restaurant word redundant.
        if (kinds.size() > 1) kinds.remove("식당");
        String color = null;
        for (String[] k : COLORS) { for (int i = 1; i < k.length && color == null; i++) if (c.contains(k[i])) color = k[0]; if (color != null) break; }
        String end = t;
        for (String w : TAIL) if (end.endsWith(w) && end.length() > w.length()) end = end.substring(0, end.length() - w.length());
        boolean asks = false;
        for (String w : MAYBE_END) if (end.endsWith(w)) { asks = true; break; }
        // A question about a side, kind or colour is a request without 저기: "저 오른짝 고깃집 뭐꼬".
        boolean request = strong || ((weak || asks) && (side != Side.ANY || !kinds.isEmpty() || color != null));
        boolean maybe = !request && asks;
        return new Hints(request, maybe, side, kinds, c, color);
    }

    private static final java.util.regex.Pattern OTHER_KIND = java.util.regex.Pattern.compile("([가-힣]{2,6})(집|가게)(이랑|이야|이|은|는|을|를|에|도)?");
    private static final String[] NOT_KIND = { "그", "이", "저", "맛", "무슨", "어떤", "어느", "우리", "옆", "앞", "뒤", "아까", "방금", "지나간", "지나친",
            "찜한", "찜했던", "찜해둔", "찜해놓은", "저장한", "저장해둔", "담은", "담아둔",
            "간판", "빨간", "노란", "파란", "검은", "하얀", "초록", "분홍", "주황", "보라", "까만" };

    /**
     * A kind we have no list entry for, from "호떡집" or "감자탕 가게" → "호떡", "감자탕". The place search then
     * looks it up by keyword, so any shop type a driver names can narrow the search.
     */
    static String otherKind(String text) {
        String[] words = text == null ? new String[0] : text.split("[\\s\\p{Punct}]+");
        for (int i = 0; i < words.length; i++) {
            // "감자탕 가게": the kind is the word before a lone 집/가게.
            String w = i > 0 && words[i].matches("(집|가게)(이랑|이야|이|은|는|을|를|에|도)?") ? words[i - 1] + "집" : words[i];
            java.util.regex.Matcher m = OTHER_KIND.matcher(w);
            if (!m.matches()) continue;
            String root = m.group(1);
            boolean skip = false;
            for (String n : NOT_KIND) if (root.equals(n) || root.endsWith(n) && n.length() >= 2) skip = true;
            if (!skip) return root;
        }
        return null;
    }

    /** Category words a provider uses for this kind, used to match Place.category text. */
    static String[] categoryWords(String kind) {
        for (Kind k : KINDS) if (k.name.equals(kind)) return k.category;
        return new String[] { kind };
    }

    /**
     * Kind for grouping the saved list, from category path and name ("음식점 > 카페 > 커피전문점 카페만월경" → "카페").
     * Unmatched restaurants fall back to the provider's second level ("한식"); anything else is "기타".
     */
    static String kindOf(String category, String name) {
        String text = category + " " + name;
        for (Kind k : KINDS) if (!k.name.equals("식당")) for (String w : k.category) if (text.contains(w)) return k.name;
        String[] path = category.split(">");
        if (path.length > 1 && path[0].trim().equals("음식점")) return path[1].trim();
        return "기타";
    }

    /** Word to use in speech for the kind the driver asked about ("고기집이 두 곳…"). */
    static String spokenKind(Hints h) {
        if (h == null || h.kinds.isEmpty()) return "가게";
        String k = h.kinds.get(0);
        for (Kind known : KINDS) if (known.name.equals(k)) return k;
        return k + "집"; // "호떡" → "호떡집"
    }

    public static Answer answer(String text) {
        String c = compact(text).toLowerCase(java.util.Locale.ROOT); // the recogniser sometimes writes "No" / "OK" in Latin letters
        // "아이고" is a sigh at the start ("아이고 맞다") but 경상 "not it" at the end ("그거 아이고").
        if (c.startsWith("아이고") || c.startsWith("아이구")) c = c.substring(3);
        if (c.isEmpty()) return Answer.NONE;
        // Stopping always wins: "됐어", "취소", "저장하지 마", "다음에" (= later, not "next one").
        if (has(c, "됐어", "됐었", "됐다", "됐슈", "됐당", "됐소", "됐구", "취소", "그만", "하지마", "하지말", "다음에",
                "치아라", "치아뿌", "치워", "고마해", "고마하", "냅둬", "냅두", "놔둬", "놔두")) return Answer.CANCEL;
        // "모르겠어", "기억 안 나", "헷갈려": the driver cannot tell which one.
        if (has(c, "모르", "몰라", "몰르", "모린", "기억안", "기억이안", "기억도안", "생각안", "생각이안", "생각도안", "까먹", "까묵", "헷갈", "햇갈"))
            return Answer.UNSURE;
        if (has(c, "둘다", "두개다", "둘이다", "둘모두", "두곳다", "두군데다", "전부")) return c.contains("아니") || c.contains("아냐") ? Answer.NO : Answer.BOTH;
        // Order words win over a leading "아니": "아니 나중 거" means the later one. "방금 전" is recent.
        // Misheard forms from the age test: "나중 거" as "나준 거" / "낮은 거", "먼저 거" as "원조가" / "원조커".
        if (has(c, "방금", "금방", "나중", "두번째", "둘째", "마지막", "뒤에거", "뒤에꺼", "뒤엣", "나준거", "나준꺼", "낮은거", "낮은꺼")) return Answer.LATER;
        if (has(c, "아까", "먼저", "처음", "첫", "그전", "전에", "앞에거", "앞에꺼", "앞엣", "원조가", "원조거", "원조꺼", "원조커")) return Answer.EARLIER;
        if (has(c, "옆", "다른거", "다른데", "다음거", "다음꺼")) return Answer.NEXT;
        // "아니라고", "아닌데", "노우", "No", "싫어": any way of saying no. A bare "노" only on its own ("노래" is not no).
        if (has(c, "아니", "아냐", "아뇨", "아닌", "아님", "아녀", "노노", "노우", "놉", "no", "nope", "틀려", "틀렸", "틀린", "안맞", "말고",
                "싫어", "싫은데", "안돼", "안되", "필요없", "않아", "않은", "안그래", "안그렇",
                "아이다", "아인데", "아이라", "아이요", "아입니", "아이거든") || c.equals("노") || c.endsWith("아이고")) return Answer.NO; // 경상 "아이다"
        if (c.equals("응") || c.startsWith("응응") || c.startsWith("응맞") || c.startsWith("응그") || c.equals("어") || c.startsWith("어맞")
                || c.startsWith("네") || c.startsWith("넵") || c.equals("예") || c.startsWith("예맞") || c.startsWith("예예") || c.equals("예게")
                || c.equals("잉") || c.equals("웅") || c.equals("엉") || c.startsWith("어어") || c.startsWith("으응") || c.startsWith("하모") || c.equals("기여")
                || has(c, "맞아", "맞죠", "맞어", "맞다", "맞심", "맞습", "맞당", "맞제", "맞지", "맞는", "맞네", "그래", "그려", "그류",
                        "그라제", "그라자", "그렇지", "그렇제", "좋아", "저장해", "찜해")) return Answer.YES; // + 경상 하모, 충청 그려, 제주 기여
        if (c.startsWith("응") && c.length() <= 3) return Answer.YES;
        return Answer.NONE;
    }

    /** A bare "응" longer than this was probably more speech the recognizer dropped, e.g. "음, 글쎄" heard as "응". */
    static final int CLIPPED_MS = 800; // emulator mic: a bare "응" measured 400 ms, "음, 글쎄" heard as "응" 1090-1690 ms

    /** One-syllable yes over too much voiced audio: not safe to take as a yes. */
    static boolean clipped(String text, int voicedMs) {
        return voicedMs > CLIPPED_MS && compact(text).length() <= 1 && answer(text) == Answer.YES;
    }

    private static boolean has(String c, String... words) { for (String w : words) if (c.contains(w)) return true; return false; }

    /** "반달 말고", "반달 아니야": the name is followed by a negation ("아니 노을" picks 노을). */
    static boolean rejects(String text, Place p) {
        String said = compact(text), n = compact(p.name);
        for (int i = 0; i + 2 <= n.length(); i++) {
            String s = n.substring(i, i + 2);
            if (GENERIC.contains(s)) continue;
            int at = said.indexOf(s);
            if (at < 0) continue;
            String after = said.substring(at + 2);
            if (after.contains("말고") || after.contains("아니") || after.contains("아냐")) return true;
        }
        return false;
    }

    /** True if the utterance contains a distinctive two-letter piece of the place name. */
    static boolean mentions(String text, Place p) { return mentions(text, p, null); }

    /** Like {@link #mentions(String, Place)}, ignoring parts shared with {@code other}: "꽃살" names 꽃살집, not 우둔살집. */
    static boolean mentions(String text, Place p, Place other) {
        String said = compact(text), n = compact(p.name), o = other == null ? "" : compact(other.name);
        for (int i = 0; i + 2 <= n.length(); i++) {
            String s = n.substring(i, i + 2);
            if (said.contains(s) && !GENERIC.contains(s) && !o.contains(s)) return true;
        }
        return false;
    }
    private static final java.util.Set<String> GENERIC = new java.util.HashSet<>(java.util.Arrays.asList(
            "카페", "커피", "식당", "베이", "이커", "커리", "본점", "지점", "점점", "고기", "치킨", "피자", "국밥", "분식", "호프", "주점", "약국", "마트"));
}
