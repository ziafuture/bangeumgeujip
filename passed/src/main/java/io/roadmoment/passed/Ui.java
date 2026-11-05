package io.roadmoment.passed;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Design tokens and view builders shared by the screens (see docs/ui-review.md). Numbers in the code are
 * half the mockup's pixel values and pass through {@link #K}: the Pleos 2560x1440 display runs at 160 dpi
 * (1 dp = 1 px), so K = 2 reproduces the mockups, whose sizes were chosen for in-car reading distance.
 */
final class Ui {
    private Ui() {}

    // Night palette. Every text/background pair used here is at least 4.5:1, outlines at least 3:1.
    static final int BG = 0xFF0E1116, SURFACE = 0xFF171C24, SURFACE2 = 0xFF232A35, TEXT = 0xFFF2F4F7, TEXT2 = 0xFFA9B4C2,
            TEXT3 = 0xFF8B97A6, ACCENT = 0xFFFFB547, OUTLINE = 0xFF6B7686, LINE = 0xFF2A3240,
            OK_FG = 0xFF7BE39A, OK_BG = 0xFF17321F, ASK_FG = 0xFF7CC4FF, ASK_BG = 0xFF1B2A3F, WAIT_FG = 0xFFFFB547, WAIT_BG = 0xFF33280F,
            PICK_BG = 0xFF2A2416, PICK_ITEM = 0xFF3A3120, PICK_TEXT = 0xFFCDBE9C, PICK_LINE = 0xFF9A8758, DANGER = 0xFFFF9A9A;

    // Type scale (sp): display 40, h1 28, h2 20, body-l 16, body 14, caption 13.
    static final int DISPLAY = 40, H1 = 28, H2 = 20, BODY_L = 16, BODY = 14, CAPTION = 13;
    static final int TOUCH = 48; // dp, smallest touch target

    static final float K = 2f;

    static int dp(Context c, float v) { return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v * K, c.getResources().getDisplayMetrics())); }

    static void size(TextView v, float sp) { v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp * K); }

    static GradientDrawable box(Context c, int color, float radiusDp, int strokeColor) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color); g.setCornerRadius(dp(c, radiusDp));
        if (strokeColor != 0) g.setStroke(dp(c, 1), strokeColor);
        return g;
    }

    static TextView text(Context c, String s, int sp, int color, boolean bold) {
        TextView v = new TextView(c);
        v.setText(s); size(v, sp); v.setTextColor(color);
        v.setLineSpacing(0, 1.25f);
        if (bold) v.setTypeface(Typeface.DEFAULT_BOLD);
        return v;
    }

    /** Status pill: one meaning per color (green listening, blue answering, amber waiting, grey paused). */
    static TextView pill(Context c, String s, int fg, int bg) {
        TextView v = text(c, s, BODY, fg, true);
        v.setGravity(Gravity.CENTER_VERTICAL);
        v.setMinHeight(dp(c, 32)); v.setPadding(dp(c, 14), 0, dp(c, 14), 0);
        v.setBackground(box(c, bg, 999, 0));
        return v;
    }

    /** Spoken-answer hint: outlined, clearly not a button. */
    static TextView chip(Context c, String s) {
        TextView v = text(c, s, BODY_L, TEXT, false);
        v.setGravity(Gravity.CENTER_VERTICAL);
        v.setMinHeight(dp(c, 36)); v.setPadding(dp(c, 14), 0, dp(c, 14), 0);
        v.setBackground(box(c, Color.TRANSPARENT, 999, OUTLINE));
        return v;
    }

    static TextView button(Context c, String s, boolean primary, View.OnClickListener click) {
        TextView v = text(c, s, BODY_L, primary ? BG : TEXT, true);
        v.setGravity(Gravity.CENTER);
        v.setMinHeight(dp(c, TOUCH)); v.setMinWidth(dp(c, TOUCH)); v.setPadding(dp(c, 18), 0, dp(c, 18), 0);
        v.setBackground(box(c, primary ? ACCENT : SURFACE2, 12, 0));
        v.setClickable(true); v.setFocusable(true); v.setOnClickListener(click);
        return v;
    }

    static LinearLayout column(Context c, int gapDp) {
        LinearLayout l = new LinearLayout(c); l.setOrientation(LinearLayout.VERTICAL);
        l.setShowDividers(LinearLayout.SHOW_DIVIDER_MIDDLE); l.setDividerDrawable(gap(c, gapDp));
        return l;
    }

    static LinearLayout row(Context c, int gapDp) {
        LinearLayout l = new LinearLayout(c); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER_VERTICAL);
        l.setShowDividers(LinearLayout.SHOW_DIVIDER_MIDDLE); l.setDividerDrawable(gap(c, gapDp));
        return l;
    }

    private static GradientDrawable gap(Context c, int dp) {
        GradientDrawable g = new GradientDrawable(); g.setSize(dp(c, dp), dp(c, dp)); g.setColor(Color.TRANSPARENT); return g;
    }

    static LinearLayout card(Context c, int color, int padDp) {
        LinearLayout l = column(c, 8);
        l.setBackground(box(c, color, 16, 0)); l.setPadding(dp(c, padDp), dp(c, padDp), dp(c, padDp), dp(c, padDp));
        return l;
    }

    static LinearLayout.LayoutParams weight(float w) { return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, w); }
    /** Takes the spare width but keeps its own height, so a big title in a row of small text is not cut off. */
    static LinearLayout.LayoutParams grow() { return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1); }
    static LinearLayout.LayoutParams wrap() { return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT); }
    static LinearLayout.LayoutParams fill() { return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT); }
}
