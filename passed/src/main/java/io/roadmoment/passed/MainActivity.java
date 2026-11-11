package io.roadmoment.passed;

import static io.roadmoment.passed.Ui.*;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.car.Car;
import android.car.drivingstate.CarUxRestrictionsManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.*;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;

/**
 * Two layouts. While driving (CarUxRestrictions requires distraction optimisation) only glanceable
 * screens: home, "wait for a safe moment", the open question. Parked: saved places, picking unanswered
 * candidates, settings. Speech is the main interface either way; when the app is in the background the
 * driver hears questions and nothing is drawn over other apps.
 */
public final class MainActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private FrameLayout frame;
    private TextView statusPill;
    private Saved saved;
    private String shownKey = "";
    private ScrollView scroll; // parked page, kept so a rebuild does not jump back to the top
    private int listY; // its scroll position, kept while a question or offer screen is shown
    private String listView = "recent"; // saved list: "recent", "kind" or "area"
    private boolean allPending;
    private Car car;
    private volatile boolean uxKnown, uxRestricted;
    private List<DemoRoute.Stop> demoStops;
    private TextView demoNow, demoPassed, demoNext, demoAsk; private ProgressBar demoBar; // live demo progress
    private static boolean previewDriving; // debug: show the driving layout while parked

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            String key = screenKey();
            if (!key.equals(shownKey)) { shownKey = key; build(); }
            if (statusPill != null) updatePill(statusPill);
            updateDemo();
            handler.postDelayed(this, 300);
        }
    };

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        saved = new Saved(this);
        listView = getSharedPreferences("ui", MODE_PRIVATE).getString("list", "recent");
        frame = new FrameLayout(this); frame.setBackgroundColor(BG);
        setContentView(frame);
        watchDrivingRestrictions();
        debugInject(getIntent());
    }

    /** Platform driving state. Without the car service (e.g. a phone) fall back to our own speed estimate. */
    private void watchDrivingRestrictions() {
        try {
            car = Car.createCar(this, handler, 2000, (c, ready) -> {
                if (!ready) { uxKnown = false; return; }
                CarUxRestrictionsManager ux = (CarUxRestrictionsManager) c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE);
                if (ux == null) return;
                uxRestricted = ux.getCurrentCarUxRestrictions().isRequiresDistractionOptimization(); uxKnown = true;
                ux.registerListener(r -> { uxRestricted = r.isRequiresDistractionOptimization(); uxKnown = true; });
            });
        } catch (Throwable e) { uxKnown = false; }
    }

    private boolean driving() { return previewDriving || (uxKnown ? uxRestricted : Status.driving); }

    private boolean wide() { return getResources().getConfiguration().screenWidthDp >= 900; }

    @Override public void onConfigurationChanged(Configuration c) { super.onConfigurationChanged(c); shownKey = ""; } // split screen / rotation

    /** Rebuild only when something the driver should see changes, so nothing flickers or moves on its own. */
    private String screenKey() {
        Dialog.Ask a = Status.ask;
        StringBuilder k = new StringBuilder().append(driving()).append(wide()).append(Status.running)
                .append('|').append(a == null ? "" : a.pair + a.first.id + (a.second == null ? "" : a.second.id))
                .append('|').append(Status.hold).append('|').append(Status.offer).append(Status.choices).append(Status.offerWhy).append(Status.passenger).append(Status.demoDriving).append('|').append(listView).append(allPending);
        if (!driving()) for (Saved.Item i : saved.all()) k.append(i.id).append(i.confirmed).append(i.alternatives.size());
        else { List<Saved.Item> all = saved.all(); k.append(all.isEmpty() ? "" : all.get(0).id); }
        return k.toString();
    }

    private void build() {
        boolean back = scroll == null; // coming back from a question, an offer or the driving screen
        if (scroll != null) listY = scroll.getScrollY();
        demoNow = demoPassed = demoNext = demoAsk = null; demoBar = null;
        frame.removeAllViews();
        scroll = null;
        int pad = dp(this, wide() ? 32 : 20);
        LinearLayout page = column(this, 24); page.setPadding(pad, dp(this, 24), pad, dp(this, 24));
        page.addView(header());
        View body;
        if (Status.hold != null) body = holdScreen();
        else if (Status.ask != null) body = Status.ask.pair ? askPair(Status.ask) : askOne(Status.ask);
        else if (Status.choices != null) body = choicesScreen(Status.choices);
        else if (Status.offer != null) body = offerScreen(Status.offer);
        else if (driving()) body = drivingHome();
        else body = null;
        if (body != null) {
            LinearLayout centre = new LinearLayout(this); centre.setGravity(Gravity.CENTER_VERTICAL);
            centre.addView(body, fill());
            page.addView(centre, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
            frame.addView(page);
            return;
        }
        // Parked: the full app, scrollable.
        page.addView(parkedHome());
        page.addView(savedSection());
        page.addView(settingsSection());
        View demo = demoTools(); page.addView(demo);
        if (BuildConfig.DEBUG) page.addView(debugTools());
        // A flick travels a third of the stock distance, so a quick swipe stops in the middle of the list.
        ScrollView s = new ScrollView(this) { @Override public void fling(int velocityY) { super.fling(velocityY / 3); } };
        s.addView(page); frame.addView(s);
        // Back from a question during the demo drive: straight to the demo card, ready for the next shop.
        final int y = listY;
        s.post(() -> s.scrollTo(0, back && Status.demoDriving ? Math.max(0, demo.getTop() - dp(this, 24)) : y)); scroll = s;
    }

    // ---- shared pieces -------------------------------------------------------------------

    private View header() {
        LinearLayout h = row(this, 12);
        TextView logo = text(this, "방", H2, BG, true); logo.setGravity(Gravity.CENTER);
        logo.setBackground(box(this, ACCENT, 8, 0)); h.addView(logo, new LinearLayout.LayoutParams(dp(this, 32), dp(this, 32)));
        h.addView(text(this, "방금그집", H2, TEXT, true), grow());
        statusPill = pill(this, "", OK_FG, OK_BG); updatePill(statusPill); h.addView(statusPill, wrap());
        return h;
    }

    private void updatePill(TextView p) {
        String s; int fg, bg;
        if (!Status.running) { s = "꺼져 있어요"; fg = TEXT2; bg = SURFACE2; }
        else if (Status.micBlocked) { s = "통화·음성비서 중이라 잠시 쉬어요"; fg = TEXT2; bg = SURFACE2; }
        else if (Status.hold != null) { s = "잠시 기다리는 중"; fg = WAIT_FG; bg = WAIT_BG; }
        else if (Status.ask != null || Status.offer != null || Status.choices != null) { s = "대답 듣는 중"; fg = ASK_FG; bg = ASK_BG; }
        else if (Status.fixes == 0) { s = "위치를 기다리는 중"; fg = TEXT2; bg = SURFACE2; }
        else if (Status.driving || Status.listenWhileParked) { s = "듣고 있어요"; fg = OK_FG; bg = OK_BG; }
        else { s = "정차 중 · 듣지 않아요"; fg = TEXT2; bg = SURFACE2; }
        if (!s.equals(p.getText().toString())) { p.setText(s); p.setTextColor(fg); p.setBackground(box(this, bg, 999, 0)); }
    }

    private TextView bigButton() {
        TextView b = button(this, "방금 그거!", true, v -> {
            if (!Status.running) { toggle(); return; }
            send(new Intent(this, PassedService.class).setAction(PassedService.ARM));
        });
        size(b, H1); b.setMinHeight(dp(this, 140)); b.setBackground(box(this, ACCENT, 20, 0));
        return b;
    }

    // ---- driving screens -------------------------------------------------------------------

    private View drivingHome() {
        LinearLayout body = wide() ? row(this, 24) : column(this, 16);
        LinearLayout left = column(this, 8); left.setGravity(Gravity.CENTER_VERTICAL);
        left.addView(text(this, "“방금 그거 뭐야?”", H1, TEXT, true));
        left.addView(text(this, "지나친 가게를 말로 찜해요", BODY_L, TEXT2, false));
        LinearLayout right = column(this, 12); right.setGravity(Gravity.CENTER_VERTICAL);
        right.addView(bigButton(), fill());
        List<Saved.Item> all = saved.all();
        if (!all.isEmpty()) {
            LinearLayout last = row(this, 12); last.setBackground(box(this, SURFACE, 16, 0)); last.setPadding(dp(this, 16), dp(this, 12), dp(this, 16), dp(this, 12));
            last.addView(text(this, all.get(0).name, BODY_L, TEXT, true), weight(1));
            java.util.Calendar midnight = java.util.Calendar.getInstance();
            midnight.set(java.util.Calendar.HOUR_OF_DAY, 0); midnight.set(java.util.Calendar.MINUTE, 0); midnight.set(java.util.Calendar.SECOND, 0);
            int today = saved.countSince(midnight.getTimeInMillis());
            if (today > 0) last.addView(text(this, "오늘 " + today + "곳", BODY, TEXT2, false));
            right.addView(last, fill());
        }
        if (wide()) { body.addView(left, weight(1)); body.addView(right, weight(1)); }
        else { body.addView(left); body.addView(right, fill()); }
        return body;
    }

    private View holdScreen() {
        LinearLayout c = column(this, 20); c.setGravity(Gravity.CENTER);
        c.addView(text(this, "안전해지면 물어볼게요", H1, TEXT, true), wrap());
        c.addView(pill(this, Status.hold, TEXT, SURFACE2), wrap());
        return c;
    }

    private View askOne(Dialog.Ask a) {
        LinearLayout card = card(this, SURFACE, wide() ? 28 : 20); card.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(text(this, sideWord(a.side) + " →", H2, ACCENT, true));
        TextView name = text(this, a.first.name, DISPLAY, TEXT, true); name.setMaxLines(2);
        card.addView(name);
        String kind = Brief.kind(a.first);
        if (!kind.isEmpty()) card.addView(text(this, kind, H2, TEXT2, false));
        LinearLayout hints = row(this, 10);
        if (Status.passenger) { // someone in the front seat can answer by touch
            hints.addView(button(this, "맞아요", true, v -> answer("응")), weight(1));
            hints.addView(button(this, "아니에요", false, v -> answer("아니")), weight(1));
            card.addView(hints, fill());
            card.addView(text(this, "조수석에서 눌러도 돼요", BODY, TEXT2, false));
            return card;
        }
        hints.addView(chip(this, "“응”")); hints.addView(chip(this, "“아니, 그 옆”"));
        card.addView(hints);
        card.addView(text(this, "대답이 없으면 나중에 골라요", BODY, TEXT2, false));
        return card;
    }

    private View askPair(Dialog.Ask a) {
        LinearLayout body = wide() ? row(this, 16) : column(this, 12);
        LinearLayout.LayoutParams lp = wide() ? new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1) : fill();
        body.addView(pairCard("아까 본 가게", a.first, "“아까 거”", "아까 거"), lp);
        body.addView(pairCard("방금 본 가게", a.second, "“방금 거”", "방금 거"), wide() ? new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1) : fill());
        if (!Status.passenger) return body;
        LinearLayout c = column(this, 12);
        c.addView(text(this, "조수석에서 눌러 골라도 돼요", BODY_L, TEXT2, true));
        c.addView(body, fill());
        c.addView(button(this, "둘 다 찜하기", false, v -> answer("둘 다")), fill());
        return c;
    }

    /** The whole card is one large target: a single tap is the same as saying the order word. */
    private View pairCard(String label, Place p, String hint, String answer) {
        LinearLayout card = card(this, SURFACE, 24); card.setMinimumHeight(dp(this, 160));
        card.addView(text(this, label, BODY_L, TEXT2, true));
        TextView name = text(this, p.name, wide() ? 36 : 28, TEXT, true); name.setMaxLines(2);
        card.addView(name);
        card.addView(chip(this, hint), wrap());
        card.setClickable(true);
        card.setOnClickListener(v -> answer(answer));
        return card;
    }

    /** A tap counts as the spoken answer. */
    private void answer(String said) {
        send(new Intent(this, PassedService.class).setAction(PassedService.TEXT).putExtra(PassedService.EXTRA_TEXT, said));
    }

    private View offerScreen(String name) {
        LinearLayout card = card(this, SURFACE, 24); card.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(text(this, Status.offerWhy == null ? "찜한 곳 근처예요" : Status.offerWhy, H2, ACCENT, true));
        card.addView(text(this, name, DISPLAY, TEXT, true));
        LinearLayout hints = row(this, 10);
        if (Status.passenger) {
            hints.addView(button(this, "들를게요", true, v -> answer("응")), weight(1));
            hints.addView(button(this, "괜찮아요", false, v -> answer("아니")), weight(1));
            card.addView(hints, fill());
            return card;
        }
        hints.addView(chip(this, "“응” 들를게")); hints.addView(chip(this, "“아니”"));
        card.addView(hints);
        return card;
    }

    /** Saves read out after "오늘 찜한 거 뭐야": the driver says one, or a passenger taps it. */
    private View choicesScreen(List<String> names) {
        LinearLayout card = card(this, SURFACE, 24); card.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(text(this, Status.offerWhy == null ? "찜한 곳" : Status.offerWhy, H2, ACCENT, true));
        LinearLayout list = column(this, 10);
        // While driving only a passenger taps; the driver hears the names and answers by voice.
        for (String n : names) list.addView(Status.passenger || !driving() ? button(this, n, false, v -> answer(n)) : text(this, n, H2, TEXT, true), fill());
        card.addView(list, fill());
        LinearLayout hints = row(this, 10);
        hints.addView(chip(this, "이름을 말하면 안내해요")); hints.addView(chip(this, "“됐어”"));
        card.addView(hints);
        return card;
    }

    static String sideWord(Hints.Side s) { return s == Hints.Side.LEFT ? "왼쪽" : s == Hints.Side.RIGHT ? "오른쪽" : "지나온 길"; }

    // ---- parked screens --------------------------------------------------------------------

    private View parkedHome() {
        LinearLayout c = column(this, 12);
        if (!Status.running) {
            LinearLayout off = card(this, SURFACE, 20);
            off.addView(text(this, "아직 꺼져 있어요", H2, TEXT, true));
            off.addView(text(this, "켜 두면 주행 중에만 들어요. 녹음 파일은 만들지 않아요.", BODY, TEXT2, false));
            off.addView(button(this, "켜기", true, v -> toggle()), wrap());
            c.addView(off, fill());
        } else {
            c.addView(drivingHome());
            c.addView(button(this, "끄기", false, v -> toggle()), wrap());
        }
        return c;
    }

    private static final String[][] LIST_VIEWS = { { "recent", "최근순" }, { "kind", "업종별" }, { "area", "지역별" } };

    /** Places still to pick come first, apart; confirmed places below, by date or grouped by kind or district. */
    private View savedSection() {
        LinearLayout c = column(this, 24);
        List<Saved.Item> pending = new ArrayList<>(), kept = new ArrayList<>();
        for (Saved.Item i : saved.all()) (i.alternatives.isEmpty() ? kept : pending).add(i);
        if (!pending.isEmpty()) c.addView(pendingSection(pending), fill());
        LinearLayout list = column(this, 12);
        list.addView(text(this, kept.isEmpty() ? "찜한 곳" : "찜한 곳 " + kept.size(), H2, TEXT, true));
        if (kept.isEmpty()) {
            list.addView(text(this, pending.isEmpty() ? "아직 찜한 곳이 없어요. 달리다 간판이 궁금하면 “방금 그거 뭐야?”라고 말해 보세요."
                    : "위 후보에서 고르면 여기로 옮겨져요.", BODY_L, TEXT2, false));
            c.addView(list, fill());
            return c;
        }
        if (kept.size() > 2) {
            LinearLayout tabs = row(this, 8);
            for (String[] v : LIST_VIEWS) tabs.addView(button(this, v[1], v[0].equals(listView), x -> {
                listView = v[0]; getSharedPreferences("ui", MODE_PRIVATE).edit().putString("list", listView).apply();
            }), wrap());
            list.addView(tabs, wrap());
        }
        if (listView.equals("recent") || kept.size() <= 2) {
            for (Saved.Item i : kept) list.addView(placeCard(i), fill());
        } else {
            boolean byKind = listView.equals("kind");
            String other = byKind ? "기타" : "주소 없음";
            Map<String, List<Saved.Item>> groups = new LinkedHashMap<>();
            for (Saved.Item i : kept) {
                String k = byKind ? Saved.kind(i) : Saved.district(i);
                groups.computeIfAbsent(k.isEmpty() ? other : k, x -> new ArrayList<>()).add(i);
            }
            // Biggest groups first, the catch-all last; within a group newest first (saved order).
            List<String> keys = new ArrayList<>(groups.keySet());
            keys.sort((p, q) -> p.equals(other) != q.equals(other) ? (p.equals(other) ? 1 : -1) : groups.get(q).size() - groups.get(p).size());
            for (String k : keys) {
                TextView h = text(this, k + "  " + groups.get(k).size(), BODY_L, ACCENT, true);
                h.setPadding(0, dp(this, 8), 0, 0);
                list.addView(h);
                for (Saved.Item i : groups.get(k)) list.addView(placeCard(i), fill());
            }
        }
        c.addView(list, fill());
        return c;
    }

    /** Saved while driving without a pick. Two at a time so they do not bury the confirmed list. */
    private View pendingSection(List<Saved.Item> pending) {
        LinearLayout c = column(this, 12);
        c.addView(text(this, "골라 주세요 " + pending.size(), H2, ACCENT, true));
        c.addView(text(this, "운전 중이라 후보를 같이 담아 둔 곳이에요. 맞는 곳을 누르면 찜한 곳으로 옮겨져요.", BODY, TEXT2, false));
        int shown = allPending ? pending.size() : Math.min(2, pending.size());
        for (int k = 0; k < shown; k++) c.addView(pickCard(pending.get(k)), fill());
        if (pending.size() > 2) {
            LinearLayout more = row(this, 8);
            more.addView(button(this, allPending ? "접기" : "나머지 " + (pending.size() - 2) + "건 보기", false, v -> allPending = !allPending));
            TextView clear = button(this, "후보 모두 지우기", false, v -> carDialog(new AlertDialog.Builder(this).setTitle("고르지 않은 후보 " + pending.size() + "건을 지울까요?")
                    .setView(dialogText("찜한 곳은 그대로 남아요."))
                    .setPositiveButton("지우기", (d, w) -> { for (Saved.Item i : pending) saved.remove(i.id); shownKey = ""; })
                    .setNegativeButton("취소", null)));
            clear.setTextColor(TEXT2);
            more.addView(clear);
            c.addView(more, wrap());
        }
        return c;
    }

    /** Unanswered while driving: pick now that the car is parked. */
    private View pickCard(Saved.Item i) {
        LinearLayout card = card(this, PICK_BG, 20);
        LinearLayout top = row(this, 12);
        top.addView(text(this, "어디였나요?", H2, ACCENT, true), grow());
        top.addView(text(this, ago(i.savedAt), BODY, PICK_TEXT, false));
        card.addView(top, fill());
        LinearLayout opts = wide() ? row(this, 12) : column(this, 8);
        for (Saved.Item o : Saved.options(i)) {
            LinearLayout b = column(this, 2); b.setBackground(box(this, PICK_ITEM, 12, 0)); b.setPadding(dp(this, 16), dp(this, 12), dp(this, 16), dp(this, 12));
            b.setMinimumHeight(dp(this, 64)); b.setClickable(true);
            b.addView(text(this, o.name, BODY_L, TEXT, true));
            String label = Saved.label(o);
            if (!label.isEmpty()) b.addView(text(this, label, BODY, PICK_TEXT, false));
            b.setOnClickListener(v -> { saved.resolve(i.id, o.id); shownKey = ""; });
            opts.addView(b, wide() ? weight(1) : fill());
        }
        TextView none = button(this, "모두 아니에요", false, v -> { saved.remove(i.id); shownKey = ""; });
        none.setBackground(box(this, 0, 12, PICK_LINE)); none.setTextColor(PICK_TEXT);
        opts.addView(none, wide() ? weight(1) : fill());
        card.addView(opts, fill());
        return card;
    }

    private View placeCard(Saved.Item i) {
        LinearLayout card = wide() ? row(this, 12) : column(this, 12);
        card.setBackground(box(this, SURFACE, 16, 0)); card.setPadding(dp(this, 20), dp(this, 16), dp(this, 16), dp(this, 16));
        LinearLayout info = column(this, 4);
        info.addView(text(this, i.name + (i.confirmed ? "" : " (미확인)"), H2, TEXT, true));
        String area = Brief.area(i.address);
        info.addView(text(this, join(Saved.label(i), area, ago(i.savedAt)), BODY, TEXT2, false));
        card.addView(info, wide() ? grow() : fill());
        LinearLayout actions = row(this, 8);
        actions.addView(button(this, "길 안내", true, v -> {
            if (!Status.running) { toast("먼저 켜 주세요"); return; }
            send(new Intent(this, PassedService.class).setAction(PassedService.WAYPOINT).putExtra(PassedService.EXTRA_ID, i.id));
        }));
        if (!i.phone.isEmpty()) actions.addView(button(this, "전화", false, v -> open(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + i.phone)))));
        if (!i.url.isEmpty()) actions.addView(button(this, "영업시간·리뷰", false, v -> page(i.url)));
        actions.addView(button(this, "삭제", false, v -> { saved.remove(i.id); shownKey = ""; }));
        card.addView(actions, wrap());
        return card;
    }

    private View settingsSection() {
        LinearLayout c = card(this, SURFACE, 20);
        c.addView(text(this, "설정", H2, TEXT, true));
        LinearLayout reaction = row(this, 12);
        reaction.addView(text(this, String.format(Locale.KOREA, "내 반응 시간 %.1f초", Status.reactionMs / 1000.0), BODY_L, TEXT, true), weight(1));
        reaction.addView(button(this, "초기화", false, v -> {
            saved.reactionSamples(new java.util.ArrayList<>());
            send(new Intent(this, PassedService.class).setAction(PassedService.RESET_REACTION));
            Status.reactionMs = Reaction.DEFAULT_MS; toast("기본값(2.5초)으로 돌렸어요");
        }));
        c.addView(reaction, fill());
        c.addView(text(this, "개인정보", BODY_L, TEXT, true));
        c.addView(text(this, "녹음 파일 없음 · 말은 차 안에서 글자로 바꾸고 ‘방금’·‘아까’ 같은 말이 없으면 바로 지워요 · 경로는 최근 2분만 메모리에 둬요", BODY, TEXT2, false));
        c.addView(text(this, "카카오로 보내는 것: 찾는 지점 좌표 · 기기에 남는 것: 찜한 곳, 반응 시간 숫자", BODY, TEXT2, false));
        TextView clear = button(this, "찜 전체 삭제", false, v -> carDialog(new AlertDialog.Builder(this).setTitle("찜한 곳을 모두 지울까요?")
                .setView(dialogText("지운 뒤에는 되돌릴 수 없어요."))
                .setPositiveButton("모두 지우기", (d, w) -> { for (Saved.Item i : saved.all()) saved.remove(i.id); shownKey = ""; })
                .setNegativeButton("취소", null)));
        clear.setBackground(box(this, 0, 12, DANGER)); clear.setTextColor(DANGER);
        c.addView(clear, wrap());
        c.addView(text(this, "버전 " + BuildConfig.VERSION_NAME, CAPTION, TEXT3, false));
        return c;
    }


    /**
     * Demo mode, in every build, parked screen only: lets a reviewer try the app on an emulator with no route file
     * and no microphone. Drives the bundled demo route inside the app and takes typed sentences on the speech path.
     */
    private View demoTools() {
        LinearLayout c = card(this, SURFACE, 20);
        c.addView(text(this, "시연 모드", BODY_L, TEXT2, true));
        c.addView(text(this, "에뮬레이터에서 경로 파일이나 마이크 없이 해 볼 수 있어요. ① 켜기 → ② 데모 길 달리기 → ③ \"방금 지나감\"이 뜨면 \"방금 … 뭐야\" 버튼 누르기(방금 지난 가게로 바뀌어요) → ④ 질문에 \"맞아요\" 또는 \"응\"",
                BODY, TEXT3, false));
        // Matches assets/demo_places.json along the route, so a reviewer knows when to ask.
        c.addView(text(this, "지나는 순서: 10초 왼쪽 카페 · 16초 오른쪽 빵집 · 25초 오른쪽 국숫집 · 43초 왼쪽 카페 · 61초 오른쪽 치킨집 · 75초 오른쪽 카페",
                BODY, TEXT3, false));
        if (Status.demoDriving) { // live progress, updated in place every tick so typing below is not lost
            demoNow = text(this, "", BODY_L, TEXT, true); c.addView(demoNow);
            demoBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); demoBar.setMax(DemoRoute.SECONDS);
            demoBar.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT)); c.addView(demoBar, fill());
            demoPassed = text(this, "", BODY_L, ACCENT, true); c.addView(demoPassed);
            demoNext = text(this, "", BODY, TEXT3, false); c.addView(demoNext);
        } else c.addView(text(this, "장소: " + Status.source, BODY, TEXT3, false));
        c.addView(button(this, Status.demoDriving ? "데모 길 멈추기" : "데모 길 달리기 (96초)", false, v -> {
            if (!Status.running) { toast("먼저 켜 주세요"); return; }
            send(new Intent(this, PassedService.class).setAction(PassedService.DEMO_DRIVE)); shownKey = "";
        }), wrap());
        Switch parked = new Switch(this); parked.setText("정차 중에도 듣기"); parked.setTextColor(TEXT); size(parked, BODY_L);
        parked.setChecked(Status.listenWhileParked); parked.setOnCheckedChangeListener((v, on) -> Status.listenWhileParked = on); c.addView(parked);
        LinearLayout quick = row(this, 8);
        demoAsk = button(this, "방금 그 카페 뭐야", false, v -> typed(((TextView) v).getText().toString())); // follows the shop just passed
        quick.addView(demoAsk, wrap());
        for (String q : new String[]{"응", "아니", "지워"}) quick.addView(button(this, q, false, v -> typed(q)), wrap());
        c.addView(quick, fill());
        EditText typed = new EditText(this); typed.setHint("말 대신 입력: 방금 오른쪽 빵집 뭐야"); typed.setTextColor(TEXT); typed.setHintTextColor(TEXT3); c.addView(typed, fill());
        c.addView(button(this, "입력한 문장 보내기", false, v -> typed(typed.getText().toString())), wrap());
        updateDemo();
        return c;
    }

    private List<DemoRoute.Stop> demoStops() {
        if (demoStops == null) {
            try { demoStops = DemoRoute.stops(new DemoPlaces(this).all()); } catch (RuntimeException e) { demoStops = new ArrayList<>(); }
        }
        return demoStops;
    }

    /** "데모 길 12초 / 96초", what was just passed and what comes next, so a reviewer knows when and what to ask. */
    private void updateDemo() {
        if (demoNow == null || !Status.demoDriving) return;
        int sec = Status.demoSecond; List<DemoRoute.Stop> stops = demoStops();
        DemoRoute.Stop p = DemoRoute.passed(stops, sec), n = DemoRoute.next(stops, sec);
        show(demoNow, "데모 길 " + sec + "초 / " + DemoRoute.SECONDS + "초 · 동쪽으로 43 km/h");
        demoBar.setProgress(sec);
        show(demoPassed, p == null ? "방금 지나간 가게 없음 · 다음 가게를 기다려 주세요"
                : "방금 지나감: " + p.side() + " " + p.word + " · " + p.name + " (" + (sec - p.second) + "초 전) → 지금 물어보세요");
        show(demoNext, n == null ? "마지막 가게를 지났어요" : "다음: " + n.side() + " " + n.word + " (" + (n.second - sec) + "초 뒤)");
        if (demoAsk != null) show(demoAsk, p != null ? p.question() : "방금 그 카페 뭐야");
    }

    private static void show(TextView v, String s) { if (!s.contentEquals(v.getText())) v.setText(s); }

    private void typed(String sentence) {
        if (!Status.running) { toast("먼저 켜 주세요"); return; }
        send(new Intent(this, PassedService.class).setAction(PassedService.TEXT).putExtra(PassedService.EXTRA_TEXT, sentence));
    }

    /** Debug builds only: tuning readout and layout preview. */
    private View debugTools() {
        LinearLayout c = card(this, SURFACE, 20);
        c.addView(text(this, "개발자 도구 (디버그 빌드)", BODY_L, TEXT2, true));
        c.addView(text(this, String.format(Locale.KOREA, "%s · 속도 %.0f km/h · 위치 %d건 · 장소 %s · 차량 주행상태 %s",
                Status.service, Status.speedMps * 3.6, Status.fixes, Status.source, uxKnown ? (uxRestricted ? "주행" : "정차") : "알 수 없음"), BODY, TEXT3, false));
        Switch preview = new Switch(this); preview.setText("주행 화면 미리보기"); preview.setTextColor(TEXT);
        preview.setChecked(previewDriving); preview.setOnCheckedChangeListener((v, on) -> { previewDriving = on; shownKey = ""; }); c.addView(preview);
        return c;
    }

    // ---- debug entry from adb ------------------------------------------------------------

    /**
     * Debug builds only: lets adb drive the same path as speech, e.g.
     * adb shell am start -n io.roadmoment.passed/.MainActivity --es inject "방금 그 카페 뭐야"
     */
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); debugInject(intent); }

    private void debugInject(Intent intent) {
        if (!BuildConfig.DEBUG || intent == null) return;
        if (intent.hasExtra("parked")) Status.listenWhileParked = intent.getBooleanExtra("parked", false);
        if (intent.hasExtra("preview")) { previewDriving = intent.getBooleanExtra("preview", false); shownKey = ""; }
        // Layout previews only (the service overwrites these on its next question): pair question, hold.
        if (intent.hasExtra("previewpair")) {
            List<Saved.Item> all = saved.all();
            if (all.size() >= 2) Status.ask = new Dialog.Ask(true, Hints.Side.RIGHT, asPlace(all.get(1)), asPlace(all.get(0)));
        }
        if (intent.hasExtra("previewhold")) Status.hold = "곧 우회전 · 120m";
        if (intent.hasExtra("previewclear")) { Status.ask = null; Status.hold = null; }
        // Stand-ins for the car: a navigation destination "lat,lon,name" and the front passenger seat.
        if (intent.hasExtra("dest") && Status.running)
            send(new Intent(this, PassedService.class).setAction(PassedService.DEST).putExtra(PassedService.EXTRA_TEXT, intent.getStringExtra("dest")));
        if (intent.hasExtra("route") && Status.running)
            send(new Intent(this, PassedService.class).setAction(PassedService.ROUTE).putExtra(PassedService.EXTRA_TEXT, intent.getStringExtra("route")));
        if (intent.getBooleanExtra("cancelroute", false) && Status.running)
            send(new Intent(this, PassedService.class).setAction(PassedService.CANCEL_ROUTE));
        if (intent.hasExtra("say") && Status.running)
            send(new Intent(this, PassedService.class).setAction(PassedService.SAY).putExtra(PassedService.EXTRA_TEXT, intent.getStringExtra("say")));
        if (intent.hasExtra("passenger") && Status.running)
            send(new Intent(this, PassedService.class).setAction(PassedService.PASSENGER).putExtra(PassedService.EXTRA_TEXT, intent.getBooleanExtra("passenger", false)));
        String text = intent.getStringExtra("inject");
        if (text != null && Status.running)
            send(new Intent(this, PassedService.class).setAction(PassedService.TEXT).putExtra(PassedService.EXTRA_TEXT, text));
    }

    private static Place asPlace(Saved.Item i) { return new Place(i.id, i.name, i.category, i.lat, i.lon, i.url, i.address, i.phone); }

    // ---- start, permissions --------------------------------------------------------------

    static final String PLEOS_LOCATION = "pleos.car.permission.FUSED_LOCATION";

    private void toggle() {
        if (Status.running) { send(new Intent(this, PassedService.class).setAction(PassedService.STOP)); return; }
        List<String> missing = missingPermissions();
        if (missing.isEmpty()) { start(); return; }
        // Explain first: Pleos shows its car-location permission as "알 수 없는 작업", which people tend to refuse.
        guide(missing, false);
    }

    private List<String> missingPermissions() {
        // pleos.car.permission.FUSED_LOCATION is a runtime ("dangerous") permission on Pleos, like Android location.
        List<String> needed = new java.util.ArrayList<>(java.util.Arrays.asList(Manifest.permission.RECORD_AUDIO,
                Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, PLEOS_LOCATION));
        if (Build.VERSION.SDK_INT >= 33) needed.add(Manifest.permission.POST_NOTIFICATIONS);
        needed.removeIf(p -> checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED);
        return needed;
    }

    private void guide(List<String> missing, boolean retry) {
        StringBuilder m = new StringBuilder(retry ? "권한이 없으면 방금그집을 켤 수 없어요.\n\n" : "지나친 가게를 찾으려면 아래 권한이 필요해요.\n\n");
        m.append("• 마이크 — \"방금 그거 뭐야?\"를 듣기 위해 써요. 녹음 파일을 만들지 않아요. 말은 차 안에서 글자로 바꾸고, ‘방금’·‘아까’ 같은 말이 없으면 바로 지워요.\n\n");
        m.append("• 위치 — 지나온 길을 거꾸로 따라가 가게를 찾기 위해 써요. 최근 2분 경로만 차 안 메모리에 두고 저장하지 않아요. ");
        m.append("찾을 때는 그 지점 좌표만 카카오 장소 검색에 보내요.\n\n");
        m.append("• 알림 — 듣는 중임을 표시하고, 도착하면 찜한 곳을 알려 줘요.");
        if (missing.contains(PLEOS_LOCATION)) m.append("\n\n다음 화면의 ‘알 수 없는 작업’은 차량 위치 권한이에요. Pleos 시스템 표기라 이렇게 보여요. ‘허용’을 눌러 주세요.");
        carDialog(new AlertDialog.Builder(this).setTitle(retry ? "권한을 허용해 주세요" : "방금그집 시작 전에").setView(dialogText(m.toString()))
                .setPositiveButton("계속", (d, w) -> requestPermissions(missing.toArray(new String[0]), 1))
                .setNegativeButton("나중에", null));
    }

    private void start() {
        try { startForegroundService(new Intent(this, PassedService.class)); } catch (Exception e) { toast("시작 실패: " + e.getMessage()); }
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        List<String> missing = missingPermissions();
        missing.remove(Manifest.permission.POST_NOTIFICATIONS); // optional: the app still works without it
        if (missing.isEmpty()) { start(); return; }
        boolean blocked = false; // "don't ask again": the system will not show its dialog any more
        for (String p : missing) if (!shouldShowRequestPermissionRationale(p)) blocked = true;
        if (!blocked) { guide(missing, true); return; }
        carDialog(new AlertDialog.Builder(this).setTitle("설정에서 권한을 켜 주세요")
                .setView(dialogText("마이크와 위치(차량 위치 포함) 권한이 꺼져 있어요. 앱 설정의 권한에서 허용으로 바꾼 뒤 다시 켜 주세요."))
                .setPositiveButton("설정 열기", (d, w) -> open(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", getPackageName(), null))))
                .setNegativeButton("닫기", null));
    }

    /** Car displays: body text and buttons large enough to read and hit at arm's length. */
    private TextView dialogText(String s) {
        TextView v = text(this, s, BODY_L, TEXT, false); v.setPadding(dp(this, 28), dp(this, 12), dp(this, 28), dp(this, 4));
        return v;
    }

    private void carDialog(AlertDialog.Builder b) {
        AlertDialog d = b.show();
        for (int which : new int[] { AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE }) {
            Button bt = d.getButton(which);
            if (bt != null) { size(bt, BODY_L); bt.setMinHeight(dp(this, TOUCH)); bt.setMinWidth(dp(this, 110)); }
        }
    }

    // ---- small helpers -------------------------------------------------------------------

    private String ago(long t) { return DateUtils.getRelativeTimeSpanString(t, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString(); }

    private static String join(String... parts) {
        StringBuilder b = new StringBuilder();
        for (String p : parts) if (p != null && !p.isEmpty()) b.append(b.length() == 0 ? "" : " · ").append(p);
        return b.toString();
    }

    private void open(Intent i) { try { startActivity(i); } catch (Exception e) { toast("열 수 없어요"); } }
    /** Kakao's place page (hours, reviews, photos) inside the app: the car has no browser to hand the link to. */
    private void page(String url) {
        android.app.Dialog d = new android.app.Dialog(this, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen);
        android.webkit.WebView web = new android.webkit.WebView(this);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.setWebViewClient(new android.webkit.WebViewClient());
        web.loadUrl(url.replaceFirst("^http:", "https:"));
        LinearLayout box = column(this, 0);
        box.setBackgroundColor(BG);
        TextView close = button(this, "닫기", true, v -> d.dismiss());
        LinearLayout.LayoutParams lp = wrap(); lp.gravity = Gravity.END; lp.setMargins(0, dp(this, 12), dp(this, 24), dp(this, 12));
        box.addView(close, lp);
        box.addView(web, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        d.setContentView(box);
        d.setOnDismissListener(x -> web.destroy());
        d.show();
    }
    private void send(Intent i) { try { startService(i); } catch (Exception e) { toast(e.getMessage()); } }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    @Override protected void onResume() { super.onResume(); shownKey = ""; handler.post(refresh); }
    @Override protected void onPause() { handler.removeCallbacks(refresh); super.onPause(); }
    @Override protected void onDestroy() { if (car != null) try { car.disconnect(); } catch (Throwable ignored) {} super.onDestroy(); }
}
