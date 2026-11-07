package io.roadmoment.passed;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.media.*;
import android.os.*;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import ai.pleos.playground.location.fused.FusedLocation;
import ai.pleos.playground.location.fused.listener.FusedLocationListener;
import ai.pleos.playground.location.fused.listener.ProximityAlertListener;
import ai.pleos.playground.location.fused.model.LocationData;
import ai.pleos.playground.location.fused.model.ProximityAlertEvent;
import ai.pleos.playground.location.fused.model.ProximityAlertRequest;
import ai.pleos.playground.location.fused.model.ProximityTransition;
import ai.pleos.playground.navi.constants.NavigationStatus;
import ai.pleos.playground.navi.constants.RouteOption;
import ai.pleos.playground.navi.constants.WaypointIndex;
import ai.pleos.playground.navi.data.DestinationArrivedInfo;
import ai.pleos.playground.navi.data.DestinationInfo;
import ai.pleos.playground.navi.data.RouteStartInfo;
import ai.pleos.playground.navi.data.RequestWaypointInfo;
import ai.pleos.playground.navi.data.RouteInfo;
import ai.pleos.playground.navi.helper.NaviHelper;
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener;
import ai.pleos.playground.navi.data.CautionPoint;
import ai.pleos.playground.navi.data.TBTInfo;
import ai.pleos.playground.vehicle.Vehicle;
import ai.pleos.playground.vehicle.api.model.area.SeatArea;
import ai.pleos.playground.vehicle.constant.area.SeatValue;
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener;
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue;
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener;

/**
 * Keeps a 2-minute location trail in memory and listens while driving. Flow:
 * utterance -> on-device STT -> "방금..." request -> place search around the recent path -> rank -> one voice confirmation -> save.
 * Non-request transcripts are dropped immediately.
 */
public final class PassedService extends Service implements Dialog.Out {
    public static final String STOP = "io.roadmoment.passed.STOP", ARM = "io.roadmoment.passed.ARM",
            TEXT = "io.roadmoment.passed.TEXT", EXTRA_TEXT = "text", WAYPOINT = "io.roadmoment.passed.WAYPOINT", EXTRA_ID = "id",
            RESET_REACTION = "io.roadmoment.passed.RESET_REACTION",
            DEST = "io.roadmoment.passed.DEST", PASSENGER = "io.roadmoment.passed.PASSENGER", ROUTE = "io.roadmoment.passed.ROUTE", // debug stand-ins for the car
            DEMO_DRIVE = "io.roadmoment.passed.DEMO_DRIVE"; // demo mode: drive the bundled route without a GPX file
    static final long MOVING_GRACE_MS = 180000; // short stops (lights, traffic) keep listening
    static final double MOVING_MPS = 2.0;
    private static final int NOTIFICATION = 21, ARRIVAL = 22;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Trail trail = new Trail(120000);
    private volatile boolean active;
    private Thread worker;
    private AudioRecord recorder;
    private AudioManager audio;
    private AudioManager.AudioRecordingCallback recordingCallback;
    private Segmenter segmenter;
    private Recognizer stt;
    private Voice voice;
    private Dialog dialog;
    private PlaceSource places;
    private Saved saved;
    private SignColors signs;
    private Hints lastHints; // the request being answered, for what savedSentence may add
    private FusedLocation location;
    private NaviHelper navi;
    private boolean naviReady;
    private volatile NavigationStatus naviStatus = NavigationStatus.INACTIVE;
    private long lastMovingAt, startedAt, answerToken;
    private Saved.Item offered; // place offered as a stop by a proximity alert, a route start or "아까 찜한 데 어디였지"
    private List<Saved.Item> choices; // saves read out after "오늘 찜한 거 뭐야", waiting for "꽃살집" / "두 번째"
    private long lastSpokeAt; private int thanked;
    private final Set<String> remarked = new HashSet<>(); // kinds already remarked on this run ("요즘 고기집 자주…")
    static final long THANKS_MS = 15000; // "고마워" this soon after the app spoke is said to the app
    private String lastDest;    // destination already checked for saves nearby
    private Hints maybe; private long maybeOnset; // "뭐야" heard without "방금": asked "방금 지나친 가게요?"
    private final Set<String> alerted = new HashSet<>();
    private final Safety safety = new Safety();
    private Reaction reaction;
    private Vehicle vehicle;
    static final long ASK_WAIT_MS = 30000; // give up asking if no safe moment comes
    static final long UNDO_MS = 10000;      // "지워" right after a save removes it
    private String lastSavedId; private long lastSavedAt;

    @Override public int onStartCommand(Intent intent, int flags, int id) {
        String action = intent == null ? null : intent.getAction();
        if (STOP.equals(action)) { shutdown(); return START_NOT_STICKY; }
        if (worker != null) { handle(intent); return START_NOT_STICKY; }
        try {
            startAsForeground();
            saved = new Saved(this);
            signs = new SignColors(this);
            String key = BuildConfig.KAKAO_REST_KEY;
            places = key.isEmpty() ? new DemoPlaces(this) : new KakaoPlaces(key);
            Status.source = places.label();
            dialog = new Dialog(this);
            reaction = new Reaction(saved.reactionSamples());
            Status.reactionMs = reaction.peakMs();
            voice = new Voice(getApplicationContext());
            stt = new Recognizer(getApplicationContext(), new Recognizer.Listener() {
                @Override public void onText(String text, long onset, int voicedMs) { heard(text, onset, voicedMs); }
                @Override public void onStatus(String s) { Status.speech = s; }
            });
            segmenter = new Segmenter(new Segmenter.Sink() {
                @Override public void utterance(byte[] pcm, long onset, boolean manual, int voicedMs) { stt.submit(pcm, onset, voicedMs); }
                @Override public void silence() { main.post(PassedService.this::noAnswer); }
            });
            startLocation(); startNavi(); startVehicle(0); stt.start();
            active = true; startedAt = System.currentTimeMillis();
            worker = new Thread(this::capture, "passed-mic"); worker.start();
            Status.running = true; Status.service = "실행 중";
            handle(intent);
        } catch (Exception e) {
            Status.service = "시작 실패: " + e.getClass().getSimpleName() + " " + e.getMessage(); shutdown();
        }
        return START_NOT_STICKY;
    }

    private void handle(Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        long now = SystemClock.elapsedRealtime();
        switch (intent.getAction()) {
            case ARM: segmenter.arm(now, 6); Status.speech = "말씀하세요 (6초)"; break;
            case TEXT: heard(intent.getStringExtra(EXTRA_TEXT), now, 0); break; // typed test input, same path as speech
            case DEMO_DRIVE: if (Status.demoDriving) stopDemoDrive(); else startDemoDrive(); break;
            case DEST: {
                String[] d = intent.getStringExtra(EXTRA_TEXT).split(",", 3);
                destination(Double.parseDouble(d[0]), Double.parseDouble(d[1]), d.length > 2 ? d[2] : "");
                break;
            }
            case ROUTE: { // asks the real navigation for a route, so its destination callback runs
                String[] d = intent.getStringExtra(EXTRA_TEXT).split(",", 3);
                try { navi.requestRoute(new RouteInfo(Double.parseDouble(d[0]), Double.parseDouble(d[1]), null, null, d.length > 2 ? d[2] : "목적지", "", "", "", RouteOption.RECOMMENDED, "", false)); }
                catch (Exception | LinkageError e) { Status.service = "경로 요청 실패: " + e.getMessage(); }
                break;
            }
            case PASSENGER: passenger(intent.getBooleanExtra(EXTRA_TEXT, false)); break;
            case RESET_REACTION: reaction = new Reaction(null); Status.reactionMs = reaction.peakMs(); break;
            case WAYPOINT:
                for (Saved.Item i : saved.all()) if (i.id.equals(intent.getStringExtra(EXTRA_ID))) addStop(i);
                break;
        }
    }

    // ---- speech results -------------------------------------------------------------------

    /**
     * The segmenter ends an utterance after 0.8 s of quiet, so a slow or hesitant driver's "저기… 오른쪽 고깃집 뭐야"
     * or "방금… 그거 뭐였지" arrives in two pieces. A piece that meant nothing alone, or a bare "방금", is held and
     * joined to the next one when that starts within {@link #JOIN_GAP_MS} of it ending.
     */
    static final long JOIN_GAP_MS = 2000;
    /** How long a bare "방금" waits for the rest; longer while more speech is still being cut or recognised. */
    static final long JOIN_WAIT_MS = 1500, JOIN_WAIT_MAX_MS = 5000;
    private String held; private long heldOnset, heldEnd; private Runnable heldRun;

    private void heard(String raw, long rawOnset, int voicedMs) { main.post(() -> {
        if (raw == null || raw.trim().isEmpty()) return;
        if (BuildConfig.DEBUG) android.util.Log.d("BangeumHeard", raw + " (" + voicedMs + "ms, threshold " + Math.round(segmenter.thresholdDb()) + " dB, now " + Math.round(Status.db) + " dB)"); // tuning aid; release builds never log speech
        String text = raw; long onset = rawOnset;
        if (held != null && rawOnset - heldEnd < JOIN_GAP_MS) {
            text = held + " " + raw; onset = heldOnset;
            if (BuildConfig.DEBUG) android.util.Log.d("BangeumHeard", "joined: " + text);
        }
        if (heldRun != null) main.removeCallbacks(heldRun);
        held = null; heldRun = null;
        heardNow(text, onset, voicedMs);
    }); }

    private void hold(String text, long onset, int voicedMs) {
        held = text; heldOnset = onset; heldEnd = onset + Math.max(voicedMs, 0);
    }

    private void heardNow(String text, long onset, int voicedMs) {
        Hints hints = Hints.parse(text);
        // A fresh "방금…" request wins over a pending "들를까요?" offer: the shop just passed matters more.
        if (offered != null && !(hints.request && Hints.answer(text) != Hints.Answer.YES && !Chat.go(text))) {
            Saved.Item item = offered; offered = null; ++answerToken;
            if (accepts(text)) addStop(item); else Earcon.close(); // declining needs no words
            Status.heard = text; return;
        }
        offered = null;
        if (choices != null) {
            List<Saved.Item> list = choices; choices = null; Status.choices = null; ++answerToken; Status.heard = text;
            int k = Hints.answer(text) == Hints.Answer.CANCEL ? -1 : Chat.pick(text, list);
            if (k >= 0) { addStop(list.get(k)); return; }
            // Anything but another question or a new search ends it quietly.
            if (!Chat.recall(text) && !(hints.request && hints.specific())) { Earcon.close(); return; }
        }
        if (maybe != null) {
            Hints asked = maybe; long at = maybeOnset; maybe = null; ++answerToken;
            if (!hints.request) { // a full request instead of an answer is handled as usual below
                Status.heard = text;
                Hints.Answer a = Hints.answer(text); // "응, 방금 거" reads as LATER but still means yes
                if (a == Hints.Answer.YES || a == Hints.Answer.LATER || a == Hints.Answer.EARLIER) lookup(asked, at); else Earcon.close();
                return;
            }
        }
        Hints.Answer answer = Hints.answer(text);
        // "지워" / "취소" right after a save takes it back.
        if (!dialog.awaitingAnswer() && lastSavedId != null && SystemClock.elapsedRealtime() - lastSavedAt < UNDO_MS
                && (answer == Hints.Answer.CANCEL || text.contains("지워"))) {
            saved.remove(lastSavedId); lastSavedId = null; Status.heard = text; speak("지웠어요.", false); return;
        }
        // A new request with a place word ("방금 왼쪽 카페") replaces the question instead of answering it.
        boolean newRequest = hints.request && hints.specific() && answer != Hints.Answer.CANCEL;
        if (dialog.awaitingAnswer() && newRequest) dialog.abandon();
        if (dialog.awaitingAnswer()) { ++answerToken; Status.heard = text; dialog.answer(text, Hints.clipped(text, voicedMs)); return; }
        // "고마워" right after the app spoke is a reply to it; any other time it is probably for a passenger.
        if (Chat.thanks(text) && !hints.request && SystemClock.elapsedRealtime() - lastSpokeAt < THANKS_MS) {
            Status.heard = text; speak(Chat.welcome(thanked++), false); return;
        }
        if (talk(text)) return;
        // With a passenger, "그거 뭐야" is usually said to them: only full requests start a search.
        if (!hints.request && hints.maybe && offered == null && !Status.passenger) {
            // Search from when the question was spoken, not from the answer.
            maybe = hints; maybeOnset = onset; Status.heard = text; say("방금 지나친 가게요?", true); return;
        }
        // "와 저기 맛있겠다" alone in the car: the driver is reacting to a shop, so offer to keep it.
        if (!hints.request && Chat.interest(text) && !Status.passenger) {
            maybe = hints; maybeOnset = onset; Status.heard = text; say("방금 그 가게, 찜해 둘까요?", true); return;
        }
        if (!hints.request) {
            Status.heard = "(호출어 없음 · 폐기)";
            // "됐어" or "응" is not the start of a request; joined it would turn the next request into a cancel.
            if (Hints.answer(text) == Hints.Answer.NONE) hold(text, onset, voicedMs);
            return;
        }
        Status.heard = text;
        // "방금" or "아까 그" alone: the rest may be coming after a pause.
        if (!hints.specific() && hints.compact.length() <= 4) {
            hold(text, onset, voicedMs);
            final long since = SystemClock.elapsedRealtime();
            heldRun = new Runnable() { @Override public void run() {
                if (!active) return;
                boolean more = segmenter.capturing() || !stt.idle();
                if (more && SystemClock.elapsedRealtime() - since < JOIN_WAIT_MAX_MS) { main.postDelayed(this, 300); return; }
                held = null; heldRun = null; lookup(hints, onset);
            } };
            main.postDelayed(heldRun, JOIN_WAIT_MS);
            return;
        }
        lookup(hints, onset);
    }

    /** "응", "가자", "그래 들르자" to an offered stop. */
    private static boolean accepts(String text) {
        Hints.Answer a = Hints.answer(text);
        return a == Hints.Answer.YES || Chat.go(text) && a != Hints.Answer.NO && a != Hints.Answer.CANCEL;
    }

    /**
     * Questions about earlier saves ("아까 찜한 데 어디였지?", "이번 주에 찜한 고깃집 뭐 있어?"). One place is offered
     * as a stop, several are read out to pick from. "찜한 꽃살집으로 가자" goes straight there.
     */
    private boolean talk(String text) {
        if (!Chat.recall(text)) return false;
        List<Saved.Item> all = saved.all();
        Saved.Item named = Chat.go(text) ? Chat.named(text, all) : null;
        if (named != null) { Status.heard = text; addStop(named); return true; }
        Status.heard = text;
        long now = System.currentTimeMillis();
        Hints h = Hints.parse(text);
        Chat.Period p = Chat.period(text, now, java.util.TimeZone.getDefault());
        List<Saved.Item> found = Chat.find(all, p, h);
        String reply = Chat.answer(found, Chat.pending(all, p), p, h);
        if (BuildConfig.DEBUG) android.util.Log.d("BangeumHeard", "recall " + p.word + " kinds=" + h.kinds + " found=" + found.size());
        String why = (p.word.isEmpty() ? "" : p.word + " ") + "찜한 " + (h.kinds.isEmpty() ? "곳" : Hints.spokenKind(h));
        if (found.size() == 1) { offered = found.get(0); Status.offerWhy = why; say(reply, true); }
        else if (found.size() > 1) {
            choices = new java.util.ArrayList<>(found.subList(0, Math.min(Chat.READ_OUT, found.size())));
            Status.offerWhy = why; say(reply, true);
        } else say(reply, false);
        return true;
    }

    private void lookup(Hints hints, long onset) {
        lastHints = hints;
        double[] area = PassedFinder.searchArea(trail, onset);
        if (area == null) { say("조금 더 달린 뒤 불러 주세요.", false); return; }
        Status.speech = "찾는 중 · " + places.label();
        places.nearby(area[0], area[1], (int)area[2], hints, new PlaceSource.Callback() {
            @Override public void onResult(List<Place> found) { main.post(() -> {
                List<PassedFinder.Candidate> ranked = PassedFinder.rank(trail, onset, found, hints, reaction.peakMs(), signs.known(found));
                if (BuildConfig.DEBUG) android.util.Log.d("BangeumHeard", "kinds=" + hints.kinds + " side=" + hints.side + " found=" + found.size() + " ranked=" + ranked.size()
                        + (ranked.isEmpty() ? "" : " top=" + ranked.get(0).place.name + " " + ranked.get(0).passedMsBeforeSpeech + "ms " + Math.round(ranked.get(0).offsetM) + "m"));
                Status.speech = "후보 " + ranked.size() + "곳";
                dialog.start(ranked, hints);
            }); }
            @Override public void onError(String message) { main.post(() -> {
                Status.speech = "장소 검색 실패: " + message;
                say("지금은 못 찾아요.", false);
            }); }
        });
    }

    // ---- Dialog.Out ------------------------------------------------------------------------

    @Override public void say(String text, boolean thenListen) {
        if (thenListen) askWhenSafe(text, SystemClock.elapsedRealtime()); else speak(text, false);
    }

    /** Questions wait for a calm moment: no turn signal, no turn just ahead, no school zone or crossing. */
    private void askWhenSafe(String text, long since) {
        long now = SystemClock.elapsedRealtime();
        String why = safety.busy(now);
        if (why == null) { speak(text, true); return; }
        final long token = ++answerToken;
        if (now - since > ASK_WAIT_MS) { // never got a safe moment: no question at all
            maybe = null; choices = null; Status.choices = null;
            if (offered != null) offered = null;
            else if (dialog.awaitingAnswer()) dialog.answer(null);
            return;
        }
        Status.speech = "안전해지면 물어볼게요 · " + why;
        Status.hold = why;
        main.postDelayed(() -> { if (token == answerToken) askWhenSafe(text, since); }, 1000);
    }

    private void speak(String text, boolean thenListen) {
        Status.lastSaid = text;
        Status.hold = null;
        Status.ask = dialog.awaitingAnswer() ? dialog.showing() : null;
        Status.offer = offered == null ? null : offered.name;
        if (choices != null) { List<String> names = new java.util.ArrayList<>(); for (Saved.Item i : choices) names.add(i.name); Status.choices = names; }
        else Status.choices = null;
        if (offered == null && choices == null) Status.offerWhy = null;
        Voice v = voice; if (v == null) return;
        v.say(text, () -> {
            lastSpokeAt = SystemClock.elapsedRealtime();
            if (!thenListen) { Status.speech = "듣는 중"; return; }
            if (!active) return;
            Earcon.open();
            final long token = ++answerToken;
            // Open the mic just after the cue so the beep is not part of the answer.
            // With a passenger the two often talk it over before answering.
            int window = Status.passenger ? 8 : 5;
            main.postDelayed(() -> { if (token == answerToken) segmenter.arm(SystemClock.elapsedRealtime(), window); }, 200);
            Status.speech = "대답을 기다리는 중";
            // Safety net only: silence is reported by the segmenter as soon as the window closes.
            main.postDelayed(() -> { if (token == answerToken) noAnswer(); }, 20000);
        });
    }

    /** The answer window closed with nothing said, or recognition never came back. */
    private void noAnswer() {
        ++answerToken;
        Earcon.close();
        maybe = null;
        if (choices != null) { choices = null; Status.choices = null; Status.speech = "듣는 중"; }
        if (offered != null) { offered = null; Status.offer = null; Status.speech = "듣는 중"; }
        else if (dialog.awaitingAnswer()) dialog.answer(null);
    }

    @Override public void closed() { ++answerToken; Earcon.close(); Status.speech = "듣는 중"; Status.ask = null; Status.hold = null; }

    @Override public String savedSentence(PassedFinder.Candidate c) {
        Saved.Item before = saved.find(c.place.id);
        long now = System.currentTimeMillis();
        String s = Brief.saved(c.place, before == null ? 0 : before.savedAt, now);
        String remark = before == null ? Chat.streak(saved.all(), c.place, now) : null;
        if (lastHints != null && lastHints.color != null && !lastHints.color.equals(c.sign))
            s += " " + Hints.colorWord(lastHints.color) + " 간판으로 기억해 둘게요."; // the driver hears it was learned
        return remark != null && remarked.add(Hints.kindOf(c.place.category, c.place.name)) ? s + " " + remark : s;
    }

    @Override public void picked(PassedFinder.Candidate c, Hints h) {
        if (h != null && h.color != null) signs.add(c.place.id, h.color);
    }

    @Override public void save(List<PassedFinder.Candidate> picks, boolean confirmed) {
        List<Place> alternatives = new java.util.ArrayList<>();
        for (int i = 1; i < picks.size(); i++) alternatives.add(picks.get(i).place);
        Place p = picks.get(0).place;
        saved.add(p, confirmed, alternatives, System.currentTimeMillis());
        lastSavedId = p.id; lastSavedAt = SystemClock.elapsedRealtime();
        if (confirmed) watch(p.id, p.name, p.lat, p.lon); // no reminders for a place the driver did not confirm
        if (confirmed && picks.size() == 1) { // learn this driver's delay from passing to speaking
            reaction.add(picks.get(0).passedMsBeforeSpeech);
            saved.reactionSamples(reaction.samples());
            Status.reactionMs = reaction.peakMs();
        }
    }

    // ---- location, proximity, navigation ----------------------------------------------------

    private void startLocation() {
        try {
            location = new FusedLocation(getApplicationContext());
            location.initialize();
            location.registerFusedLocationCallback(new FusedLocationListener() {
                @Override public void onChanged(LocationData d) {
                    long nowElapsed = SystemClock.elapsedRealtime();
                    Long ts = d.getTimestampMillis();
                    // Map the fix's wall-clock time onto the elapsed clock used for speech onsets.
                    long at = ts == null ? nowElapsed : nowElapsed - Math.max(0, System.currentTimeMillis() - ts);
                    if (!Status.demoDriving) fix(d.getLatitude(), d.getLongitude(), at, nowElapsed);
                }
                @Override public void onError(Exception e) { Status.service = "위치 오류: " + e.getMessage(); }
            });
            main.postDelayed(() -> { int n = 0; for (Saved.Item i : saved.all()) if (i.confirmed && n++ < 20) watch(i.id, i.name, i.lat, i.lon); }, 2000);
        } catch (Exception | LinkageError e) { Status.service = "위치 사용 불가: " + e.getClass().getSimpleName(); }
    }

    private void fix(double lat, double lon, long at, long nowElapsed) {
        trail.add(lat, lon, at);
        Status.fixes++;
        double v = trail.speed(nowElapsed - 10000, nowElapsed);
        Status.speedMps = v;
        if (v >= MOVING_MPS) lastMovingAt = nowElapsed;
    }

    // Demo mode: the same straight eastbound run as passed/demo/demo-route.gpx (43 km/h, 96 s), fed to the trail
    // in place of real fixes, so a reviewer can try the app on an emulator without loading a route file.
    private static final double DEMO_LAT = 37.556, DEMO_LON = 126.922, DEMO_STEP_LON = 0.0001361;
    private static final int DEMO_SECONDS = 96;
    private int demoSecond;
    private final Runnable demoTick = new Runnable() {
        @Override public void run() {
            if (!Status.demoDriving) return;
            if (demoSecond > DEMO_SECONDS) { stopDemoDrive(); return; }
            long now = SystemClock.elapsedRealtime();
            fix(DEMO_LAT, DEMO_LON + DEMO_STEP_LON * demoSecond++, now, now);
            main.postDelayed(this, 1000);
        }
    };

    private void startDemoDrive() {
        demoSecond = 0; Status.demoDriving = true; trail.clear(); // a jump from the real position would read as a huge speed
        main.removeCallbacks(demoTick); main.post(demoTick);
    }

    private void stopDemoDrive() {
        Status.demoDriving = false; main.removeCallbacks(demoTick);
    }

    private void watch(String id, String name, double lat, double lon) {
        if (location == null) return;
        try {
            location.addProximityAlert(new ProximityAlertRequest(lat, lon, 400f, java.util.EnumSet.of(ProximityTransition.ENTER)), new ProximityAlertListener() {
                @Override public void onRegistered(String alertId) {}
                @Override public void onTransition(ProximityAlertEvent e) { main.post(() -> nearSaved(id, name)); }
                @Override public void onError(Exception e) {}
            });
        } catch (Exception | LinkageError ignored) {}
    }

    private void nearSaved(String id, String name) {
        // Once per place per run, and never on top of an ongoing question.
        if (voice == null || voice.speaking || dialog.awaitingAnswer() || offered != null || choices != null || maybe != null || heldRun != null || !alerted.add(id)) return;
        for (Saved.Item i : saved.all()) if (i.id.equals(id)) offered = i;
        if (offered == null) return;
        Status.offerWhy = "찜한 곳 근처예요";
        say("찜한 " + name + ", 근처예요. 들를까요?", true);
    }

    private void startNavi() {
        try {
            navi = new NaviHelper(getApplicationContext());
            navi.initialize(() -> { naviReady = true; return kotlin.Unit.INSTANCE; }, e -> { naviReady = false; return kotlin.Unit.INSTANCE; });
            navi.addListener(new NaviHelperEventListener() {
                @Override public void onDestinationArrived(DestinationArrivedInfo info) { main.post(() -> arrived()); }
                @Override public void onRouteStarted(RouteStartInfo info) {
                    if (BuildConfig.DEBUG) android.util.Log.d("BangeumHeard", "route started");
                    askDestination();
                }
                @Override public void onDestinationChanged() { askDestination(); }
                @Override public void onDestinationInfo(DestinationInfo d) {
                    if (BuildConfig.DEBUG) android.util.Log.d("BangeumHeard", "destination " + (d == null ? "null" : d.getName() + " " + d.getLatitude() + "," + d.getLongitude()));
                    if (d != null) main.post(() -> destination(d.getLatitude(), d.getLongitude(), d.getName()));
                }
                @Override public void onNavigationStatus(NavigationStatus status) { naviStatus = status; }
                @Override public void onTBTInfo(List<TBTInfo> list) {
                    safety.nextTurn(list == null || list.isEmpty() ? -1 : list.get(0).getDistance(), SystemClock.elapsedRealtime());
                }
                @Override public void onCautionPointInfo(List<CautionPoint> list) {
                    CautionPoint near = null; // nearest point that needs attention
                    if (list != null) for (CautionPoint c : list)
                        if (c.getType() != null && Safety.ATTENTION.contains(c.getType().name())
                                && (near == null || c.getRemainDistance() < near.getRemainDistance())) near = c;
                    safety.caution(near == null ? null : near.getType().name(), near == null ? -1 : near.getRemainDistance(), SystemClock.elapsedRealtime());
                }
            });
        } catch (Exception | LinkageError e) { navi = null; }
    }

    private void askDestination() {
        try { if (navi != null) navi.getDestinationInfo(); }
        catch (Exception | LinkageError e) { if (BuildConfig.DEBUG) android.util.Log.d("BangeumHeard", "destination failed " + e); }
    }

    /**
     * A route was set: offer the nearest confirmed save within walking distance of the destination as a stop,
     * once per destination. Waits for a quieter moment if a question is already open.
     */
    private void destination(double lat, double lon, String name) {
        if (lat == 0 && lon == 0) return; // navigation reports this with no route or mid re-route
        String key = destKey(lat, lon);
        if (key.equals(lastDest)) return;
        List<Saved.Item> near = Saved.nearDestination(saved.all(), lat, lon);
        if (near.isEmpty()) { lastDest = key; return; }
        if (dialog.awaitingAnswer() || offered != null || choices != null || maybe != null || heldRun != null || (voice != null && voice.speaking)) {
            main.postDelayed(() -> destination(lat, lon, name), 5000); return;
        }
        lastDest = key;
        Saved.Item first = near.get(0);
        alerted.add(first.id); // no second offer for it when the car gets close
        offered = first; Status.offerWhy = "목적지 근처 찜한 곳";
        say(Brief.nearDestination(first.name, first.savedAt, near.size(), System.currentTimeMillis()), true);
    }

    private static String destKey(double lat, double lon) { return Math.round(lat * 1e4) + "," + Math.round(lon * 1e4); }

    private void passenger(boolean on) {
        if (Status.passenger == on) return;
        Status.passenger = on; dialog.passenger(on);
        if (BuildConfig.DEBUG) android.util.Log.d("BangeumHeard", "passenger=" + on);
    }

    private void arrived() {
        int n = saved.countSince(startedAt);
        String nearby = null; double best = 500; // a confirmed save within 500 m of where we stopped
        Trail.Fix here = trail.at(SystemClock.elapsedRealtime() - 1000);
        if (here != null) for (Saved.Item i : saved.all()) {
            if (!i.confirmed || i.savedAt >= startedAt) continue;
            double d = Geo.distance(here, new Trail.Fix(i.lat, i.lon, 0));
            if (d < best) { best = d; nearby = i.name; }
        }
        String text = Brief.arrival(n, nearby);
        if (text == null) return;
        speak(text, false);
        if (n == 0) return;
        NotificationManager m = getSystemService(NotificationManager.class);
        PendingIntent open = PendingIntent.getActivity(this, 2, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        m.notify(ARRIVAL, new Notification.Builder(this, "listen").setSmallIcon(android.R.drawable.star_on)
                .setContentTitle("이번 주행에서 찜한 곳 " + n + "개").setContentText("눌러서 확인하세요").setContentIntent(open).setAutoCancel(true).build());
    }

    /** Turn signal and front-seat occupancy from the Vehicle SDK. The service binds asynchronously, so registration is retried briefly. */
    private void startVehicle(int attempt) {
        try {
            if (vehicle == null) { vehicle = new Vehicle(getApplicationContext()); vehicle.initialize(); }
            vehicle.getTurnSignal().registerTurnSignalState(new TurnSignalStateListener() {
                @Override public void onTurnSignalStateUpdated(TurnSignalValue v) { safety.turnSignal(v != TurnSignalValue.OFF, SystemClock.elapsedRealtime()); }
                @Override public void onFailed(Exception e) {}
            });
            try { // front passenger seat; cars without the sensor just behave as if driving alone
                vehicle.getSeat().registerSeatOccupancy(new SeatArea(SeatValue.ROW_1_RIGHT), new SeatOccupancyListener() {
                    @Override public void onSeatOccupancyUpdated(Boolean on) { main.post(() -> passenger(Boolean.TRUE.equals(on))); }
                    @Override public void onFailed(Exception e) {}
                });
            } catch (Exception | LinkageError ignored) {}
        } catch (Exception | LinkageError e) {
            if (attempt < 5) main.postDelayed(() -> startVehicle(attempt + 1), 1000);
            else Status.service = "차량 신호 사용 불가: " + e.getClass().getSimpleName();
        }
    }

    /** Adds a waypoint while a route is being guided; otherwise starts guidance to the place. */
    private void addStop(Saved.Item i) {
        if (navi == null || !naviReady) { say("내비게이션에 연결되지 않았어요.", false); return; }
        try {
            if (naviStatus == NavigationStatus.ROUTE_DRIVE) {
                navi.addWaypoint(new RequestWaypointInfo(i.lat, i.lon, i.name, WaypointIndex.FIRST, i.id, "", "", ""));
                say(i.name + ", 경유지로 추가했어요.", false);
            } else {
                lastDest = destKey(i.lat, i.lon); // heading to a save: no "목적지 근처" offer on top
                navi.requestRoute(new RouteInfo(i.lat, i.lon, null, null, i.name, i.id, "", "", RouteOption.RECOMMENDED, "", false));
                say(i.name + ", 길 안내를 시작할게요.", false);
            }
        } catch (Exception | LinkageError e) { say("길 안내를 요청하지 못했어요.", false); }
    }

    // ---- microphone ------------------------------------------------------------------------

    private boolean listening(long now) {
        Status.driving = lastMovingAt != 0 && now - lastMovingAt < MOVING_GRACE_MS;
        Voice v = voice;
        return v != null && !Status.micBlocked && !v.speaking && (Status.driving || Status.listenWhileParked);
    }

    private void capture() {
        try {
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                throw new SecurityException("마이크 권한이 없습니다");
            int min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (min <= 0) throw new IllegalStateException("16kHz PCM 미지원");
            recorder = new AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(Math.max(min * 2, 6400)).build();
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("마이크 초기화 실패");
            audio = getSystemService(AudioManager.class);
            final int session = recorder.getAudioSessionId();
            recordingCallback = new AudioManager.AudioRecordingCallback() {
                @Override public void onRecordingConfigChanged(List<AudioRecordingConfiguration> configs) {
                    // A call or the system voice assistant (Gleo) takes the mic: Android silences our stream, so we pause and say so.
                    for (AudioRecordingConfiguration c : configs)
                        if (c.getClientAudioSessionId() == session) Status.micBlocked = Build.VERSION.SDK_INT >= 29 && c.isClientSilenced();
                }
            };
            audio.registerAudioRecordingCallback(recordingCallback, main);
            recorder.startRecording();
            short[] buffer = new short[1600]; // 100 ms
            while (active) {
                int n = recorder.read(buffer, 0, buffer.length, AudioRecord.READ_BLOCKING);
                if (n <= 0) { if (active) throw new IllegalStateException("PCM read=" + n); break; }
                double sum = 0; for (int i = 0; i < n; i++) sum += (double)buffer[i] * buffer[i];
                double db = 20 * Math.log10(Math.max(Math.sqrt(sum / n) / 32768.0, 0.000001));
                Status.db = db;
                long now = SystemClock.elapsedRealtime();
                segmenter.accept(buffer, n, db, now, listening(now));
            }
        } catch (Exception e) {
            Status.service = "마이크 오류: " + e.getClass().getSimpleName() + " " + e.getMessage();
        } finally {
            if (audio != null && recordingCallback != null) audio.unregisterAudioRecordingCallback(recordingCallback);
            if (recorder != null) { try { recorder.stop(); } catch (Exception ignored) {} recorder.release(); }
            main.post(this::shutdown);
        }
    }

    private void startAsForeground() {
        NotificationManager m = getSystemService(NotificationManager.class);
        m.createNotificationChannel(new NotificationChannel("listen", "방금그집", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, PassedService.class).setAction(STOP), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, "listen").setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("방금그집 · 주행 중 듣는 중").setContentText("녹음 파일 없음 · 끄려면 ‘끄기’")
                .setContentIntent(open).addAction(new Notification.Action.Builder(null, "끄기", stop).build()).setOngoing(true).build();
        if (Build.VERSION.SDK_INT >= 30)
            startForeground(NOTIFICATION, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE | ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        else startForeground(NOTIFICATION, n);
    }

    private void shutdown() {
        stopDemoDrive();
        active = false; Status.running = false; Status.driving = false; Status.service = Status.service.contains("오류") ? Status.service : "중지됨";
        if (stt != null) stt.close();
        if (voice != null) voice.close();
        try { if (location != null) location.release(); } catch (Exception | LinkageError ignored) {}
        try { if (navi != null) navi.release(); } catch (Exception | LinkageError ignored) {}
        try { if (vehicle != null) vehicle.release(); } catch (Exception | LinkageError ignored) {}
        vehicle = null;
        stt = null; voice = null; location = null; navi = null; worker = null;
        Status.ask = null; Status.hold = null; Status.offer = null; Status.choices = null;
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
    }

    @Override public void onDestroy() { active = false; super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}
