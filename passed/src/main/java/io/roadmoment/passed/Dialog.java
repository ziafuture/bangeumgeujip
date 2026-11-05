package io.roadmoment.passed;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Short voice confirmation after a request. Asks about one candidate. When the top two are too close to call it
 * names both, in the order they were seen ("꽃살집과 우둔살집 중 어디예요?"). The driver can answer by part of a
 * name ("꽃살집이었던 거 같은데"), by order ("아까 거", "방금 거"; "먼저 거" / "나중 거" also work), "둘 다", or
 * "모르겠어", which keeps both. With {@code askPairs} off it keeps both without asking, to pick after parking.
 * With no usable answer the candidates are saved as one unconfirmed group to settle after parking.
 * At most three questions per request.
 */
public final class Dialog {
    public interface Out {
        void say(String text, boolean thenListen);
        /** picks.get(0) is the best guess; with confirmed=false the rest are alternatives to choose from later. */
        void save(List<PassedFinder.Candidate> picks, boolean confirmed);
        /** What to say after a confirmed save ("저장했어요. …"). */
        String savedSentence(PassedFinder.Candidate c);
        /** The driver called it off ("됐어"): end quietly, the app only plays a closing sound. */
        void closed();
        /** The driver confirmed exactly this one, so what they said about it (a sign colour) can be learned. */
        default void picked(PassedFinder.Candidate c, Hints h) {}
    }
    static final int MAX_ASK = 3, GROUP = 3;
    /** Score gap below which the top two are offered together. */
    static final double AMBIGUOUS = 0.35;

    private final Out out;
    private final boolean askPairs;
    private List<PassedFinder.Candidate> cands = new ArrayList<>();
    private final Set<Integer> asked = new HashSet<>();
    private Hints hints;
    private int single = -1, first = -1, second = -1, questions;
    private boolean reasked; // one "say it again" per request when speech was heard but not understood
    private boolean passenger, toldPassenger; // someone in the front seat can tap the screen; say so once per trip

    public Dialog(Out out) { this(out, true); }
    public Dialog(Out out, boolean askPairs) { this.out = out; this.askPairs = askPairs; }

    /** Front passenger seat occupied: the pair question mentions once that they may pick on the screen. */
    public void passenger(boolean on) { passenger = on; }

    public boolean awaitingAnswer() { return single >= 0 || first >= 0; }

    /** What the screen should show while a question is open. */
    public static final class Ask {
        public final boolean pair; public final Hints.Side side; public final Place first, second;
        Ask(boolean pair, Hints.Side side, Place first, Place second) { this.pair = pair; this.side = side; this.first = first; this.second = second; }
    }

    /** The open question, or null. For a pair, {@code first} is the place seen first. */
    public Ask showing() {
        if (single >= 0) { PassedFinder.Candidate c = cands.get(single); return new Ask(false, c.side, c.place, null); }
        if (first >= 0) {
            PassedFinder.Candidate a = cands.get(first), b = cands.get(second);
            Hints.Side side = hints != null && hints.side != Hints.Side.ANY ? hints.side : a.side == b.side ? a.side : Hints.Side.ANY;
            return new Ask(true, side, a.place, b.place);
        }
        return null;
    }

    /** Drop the current question without saying anything (a new request replaced it). */
    public void abandon() { clear(); }

    public void start(List<PassedFinder.Candidate> found, Hints h) {
        cands = found; hints = h; asked.clear(); questions = 0; reasked = false; clear();
        if (found.isEmpty()) { out.say("지나친 가게를 못 찾았어요.", false); return; }
        if (found.size() >= 2 && found.get(0).score - found.get(1).score < AMBIGUOUS) { if (askPairs) askPair(0, 1); else keepBoth(0, 1); }
        else askSingle(0);
    }

    /** The driver's reply, or null when the answer window passed without one. */
    public void answer(String text) { answer(text, false); }

    /**
     * @param clipped the transcript is a bare "응" over much longer speech ({@link Hints#clipped}); it is treated as
     *                heard-but-unclear rather than as a yes.
     */
    public void answer(String text, boolean clipped) {
        if (!awaitingAnswer()) return;
        if (text != null && Hints.answer(text) == Hints.Answer.CANCEL) { clear(); out.closed(); return; }
        if (clipped) { boolean pair = first >= 0; if (!reask(text)) { if (pair) group(first, second); else group(single, -1); } return; }
        if (first >= 0) answerPair(text); else answerSingle(text);
    }

    /** Heard something but could not tell what it meant: ask once more, briefly, instead of giving up. */
    private boolean reask(String text) {
        if (text == null || reasked || questions >= MAX_ASK) return false;
        reasked = true; questions++;
        // For a pair "응" is no answer at all, so the second try asks for the order words the screen shows.
        // "아까" / "방금" rather than "먼저" / "나중": in the age test they were heard 100% vs 57% / 43%.
        out.say(first >= 0 ? "아까 본 가게예요, 방금 본 가게예요?" : "응이나 아니, 아니면 이름으로 말해 주세요.", true);
        return true;
    }

    private void answerPair(String text) {
        int a = first, b = second;
        if (text != null) {
            boolean ra = Hints.rejects(text, cands.get(a).place), rb = Hints.rejects(text, cands.get(b).place);
            if (ra != rb) { done(ra ? b : a); return; } // "반달 말고" picks the other one
            Place pa = cands.get(a).place, pb = cands.get(b).place;
            boolean ma = Hints.mentions(text, pa, pb), mb = Hints.mentions(text, pb, pa);
            if (ma != mb && !ra && !rb) { done(ma ? a : b); return; }
        }
        switch (text == null ? Hints.Answer.NONE : Hints.answer(text)) {
            case EARLIER: done(a); break;
            case LATER: done(b); break;
            case BOTH: saveBoth(a, b, ""); break;
            case UNSURE: saveBoth(a, b, "그럼 "); break;
            case NO: case NEXT: { int next = nextUnasked(); clear(); if (next >= 0 && questions < MAX_ASK) askSingle(next); else giveUp(); break; }
            case YES: case NONE: if (!reask(text)) group(a, b); break; // "응" alone does not say which one
            default: group(a, b);
        }
    }

    private void answerSingle(String text) {
        int cur = single;
        if (text != null) {
            for (int i = 0; i < cands.size(); i++)
                if (i != cur && Hints.mentions(text, cands.get(i).place) && !Hints.rejects(text, cands.get(i).place)
                        && !Hints.mentions(text, cands.get(cur).place)) { done(i); return; }
        }
        switch (text == null ? Hints.Answer.NONE : Hints.answer(text)) {
            case YES: done(cur); break;
            case EARLIER: case LATER: {
                int next = byOrder(cur, Hints.answer(text) == Hints.Answer.EARLIER);
                if (next < 0) next = nextUnasked();
                clear(); if (next >= 0 && questions < MAX_ASK) askSingle(next); else giveUp();
                break;
            }
            case NO: case NEXT: { int next = nextUnasked(); clear(); if (next >= 0 && questions < MAX_ASK) askSingle(next); else giveUp(); break; }
            case NONE: if (!reask(text)) group(cur, -1); break;
            default: group(cur, -1); // "모르겠어" included: keep it to check after parking
        }
    }

    private void askSingle(int i) {
        single = i; asked.add(i); questions++;
        PassedFinder.Candidate c = cands.get(i);
        String kind = c.place.shortCategory();
        String said = Brief.kind(c.place);
        // "오른쪽 빨간 간판 꽃살집 맞죠?" when drivers said so before, so the driver hears why it was picked.
        String sign = hints != null && hints.color != null && hints.color.equals(c.sign) ? Hints.colorWord(c.sign) + " 간판 " : "";
        out.say(sideWord(c.side) + " " + sign + c.place.name + (said.isEmpty() || c.place.name.contains(said) || c.place.name.contains(kind) ? "" : ", " + said) + " 맞죠?", true);
    }

    /** Offers two candidates, earlier-passed first. */
    private void askPair(int i, int j) {
        PassedFinder.Candidate x = cands.get(i), y = cands.get(j);
        if (y.passedMsBeforeSpeech > x.passedMsBeforeSpeech) { int t = i; i = j; j = t; PassedFinder.Candidate u = x; x = y; y = u; }
        first = i; second = j; asked.add(i); asked.add(j); questions++;
        // Names in the order the driver saw them; the screen labels them "아까 본 가게" / "방금 본 가게".
        String q = x.place.name + with(x.place.name) + " " + y.place.name + " 중 어디예요?";
        if (hints != null && hints.color != null) q = Hints.colorWord(hints.color) + " 간판이면 " + q; // their words back: it was heard
        if (passenger && !toldPassenger) { toldPassenger = true; q += " 옆에서 화면으로 골라 주셔도 돼요."; }
        out.say(q, true);
    }

    /** Both, each as its own save, named so the driver hears what was kept: "그럼 꽃살집, 우둔살집 둘 다 찜했어요." */
    private void saveBoth(int a, int b, String lead) {
        clear();
        out.save(list(a), true); out.save(list(b), true);
        out.say(lead + cands.get(a).place.name + ", " + cands.get(b).place.name + " 둘 다 찜했어요.", false);
    }

    /** A close call while driving: save both, unconfirmed, and say so in one sentence. */
    private void keepBoth(int i, int j) {
        clear();
        List<PassedFinder.Candidate> picks = list(i); picks.add(cands.get(j));
        out.save(picks, false);
        out.say(twoPlaces(cands.get(i), cands.get(j)) + " 두 곳 담아 둘게요. 차 세우고 골라 주세요.", false);
    }

    /** "오른쪽 고기집", or just the kind when the two are on different sides. */
    private String twoPlaces(PassedFinder.Candidate x, PassedFinder.Candidate y) {
        Hints.Side side = hints != null && hints.side != Hints.Side.ANY ? hints.side : x.side == y.side ? x.side : Hints.Side.ANY;
        return (side == Hints.Side.ANY ? "" : sideWord(side) + " ") + Hints.spokenKind(hints);
    }

    private void done(int i) {
        clear();
        PassedFinder.Candidate c = cands.get(i);
        String sentence = out.savedSentence(c); // before saving, so "찜했던 곳" can see the earlier save
        out.save(list(i), true); out.picked(c, hints); out.say(sentence, false);
    }

    private void giveUp() { clear(); out.say("알겠어요, 안 할게요.", false); }

    /** No usable answer: keep the best guesses together so the driver can pick one after parking. */
    private void group(int a, int b) {
        clear();
        List<PassedFinder.Candidate> picks = list(a);
        if (b >= 0) picks.add(cands.get(b));
        for (int i = 0; i < cands.size() && picks.size() < GROUP; i++) if (!picks.contains(cands.get(i))) picks.add(cands.get(i));
        out.save(picks, false);
        out.say(picks.size() > 1 ? "후보로 담아 둘게요." : "일단 담아 둘게요.", false); // the list explains the rest after parking
    }

    /** The unasked candidate nearest in passing time on the requested side of the current one. */
    private int byOrder(int cur, boolean earlier) {
        long t = cands.get(cur).passedMsBeforeSpeech; int best = -1; long gap = Long.MAX_VALUE;
        for (int i = 0; i < cands.size(); i++) {
            if (asked.contains(i)) continue;
            long d = cands.get(i).passedMsBeforeSpeech - t; // positive = passed before the current one
            if ((earlier ? d > 0 : d < 0) && Math.abs(d) < gap) { gap = Math.abs(d); best = i; }
        }
        return best;
    }

    private int nextUnasked() { for (int i = 0; i < cands.size(); i++) if (!asked.contains(i)) return i; return -1; }
    private List<PassedFinder.Candidate> list(int i) { List<PassedFinder.Candidate> l = new ArrayList<>(); l.add(cands.get(i)); return l; }
    private void clear() { single = first = second = -1; }

    static String sideWord(Hints.Side s) { return s == Hints.Side.LEFT ? "왼쪽" : "오른쪽"; }

    /** 이에요/예요 after the last syllable. */
    static String copula(String word) { return subject(word).equals("이") ? "이에요" : "예요"; }

    /** 은/는 after the last syllable. */
    static String topic(String word) { return subject(word).equals("이") ? "은" : "는"; }

    /** 과/와 after the last syllable. */
    static String with(String word) { return subject(word).equals("이") ? "과" : "와"; }

    /** 이/가 after the last syllable. */
    static String subject(String word) {
        char ch = word.isEmpty() ? '가' : word.charAt(word.length() - 1);
        return ch >= 0xAC00 && ch <= 0xD7A3 && (ch - 0xAC00) % 28 != 0 ? "이" : "가";
    }
}
