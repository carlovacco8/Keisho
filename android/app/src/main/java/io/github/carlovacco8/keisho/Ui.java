// Keisho — Android port: look of the launcher (colours, pills, rows).
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.carlovacco8.keisho;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

final class Ui {
    static final int BG_TOP     = 0xFF181B22;
    static final int BG_BOTTOM  = 0xFF0D0F13;
    static final int CARD       = 0xFF1D2028;
    static final int CARD_HI    = 0xFF262A33;
    static final int STROKE     = 0xFF323743;
    static final int ACCENT     = 0xFFF0A532;
    static final int TEXT       = 0xFFE9EAEE;
    static final int TEXT_DIM   = 0xFF8D929D;
    static final int RED        = 0xFFC8473C;
    static final int GREEN      = 0xFF2FA55A;
    static final int PILL_WHITE = 0xFFF1F1F3;

    private Ui() {}

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics()));
    }

    static GradientDrawable rounded(int fill, float radiusPx, int strokeColor, int strokePx) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(radiusPx);
        if (strokePx > 0) {
            d.setStroke(strokePx, strokeColor);
        }
        return d;
    }

    /** Background with an orange outline when focused by remote / pad. */
    static Drawable focusable(Context c, int fill, float radius) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[] { android.R.attr.state_focused },
                rounded(fill, radius, ACCENT, dp(c, 2.5f)));
        s.addState(new int[] {}, rounded(fill, radius, 0, 0));
        return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), s, null);
    }

    static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        if (bold) {
            t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        }
        return t;
    }

    /** A pill button. */
    static TextView pill(Context c, String label, int fill, int textColor, View.OnClickListener click) {
        TextView t = text(c, label, 15, textColor, true);
        t.setGravity(Gravity.CENTER);
        t.setLetterSpacing(0.06f);
        t.setPadding(dp(c, 22), dp(c, 10), dp(c, 22), dp(c, 10));
        t.setBackground(focusable(c, fill, dp(c, 30)));
        t.setFocusable(true);
        t.setClickable(true);
        t.setOnClickListener(click);
        enterClicks(t);
        return t;
    }

    /** The remote's centre button and a pad's A also activate a view. */
    static void enterClicks(View v) {
        v.setOnKeyListener((view, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_UP
                    && (keyCode == KeyEvent.KEYCODE_BUTTON_A || keyCode == KeyEvent.KEYCODE_ENTER)) {
                view.performClick();
                return true;
            }
            return false;
        });
    }

    /** A settings row: title and optional description on the left, control on the right. */
    static LinearLayout row(Context c, String title, String description, View control) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(rounded(CARD, dp(c, 14), STROKE, dp(c, 1)));
        row.setPadding(dp(c, 22), dp(c, 14), dp(c, 16), dp(c, 14));

        LinearLayout labels = new LinearLayout(c);
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView t = text(c, title.toUpperCase(), 16, TEXT, true);
        t.setLetterSpacing(0.04f);
        labels.addView(t);
        if (description != null && !description.isEmpty()) {
            TextView d = text(c, description, 12, TEXT_DIM, false);
            d.setPadding(0, dp(c, 5), 0, 0);
            labels.addView(d);
        }
        row.addView(labels, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        if (control != null) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = dp(c, 12);
            row.addView(control, lp);
        }
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rp.bottomMargin = dp(c, 10);
        row.setLayoutParams(rp);
        return row;
    }

    interface Choice {
        void chosen(int index);
    }

    /** Segmented pills; the chosen one is orange. */
    static LinearLayout segmented(Context c, String[] labels, int selected, Choice choice) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.HORIZONTAL);
        TextView[] items = new TextView[labels.length];
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            TextView t = text(c, labels[i], 15, TEXT_DIM, true);
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(c, 18), dp(c, 9), dp(c, 18), dp(c, 9));
            t.setMinWidth(dp(c, 58));
            t.setFocusable(true);
            t.setClickable(true);
            items[i] = t;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = i == 0 ? 0 : dp(c, 8);
            box.addView(t, lp);
            enterClicks(t);
            t.setOnClickListener(v -> {
                styleSegments(c, items, index);
                choice.chosen(index);
            });
        }
        styleSegments(c, items, selected);
        return box;
    }

    private static void styleSegments(Context c, TextView[] items, int selected) {
        for (int i = 0; i < items.length; i++) {
            boolean on = i == selected;
            items[i].setBackground(focusable(c, on ? ACCENT : CARD_HI, dp(c, 30)));
            items[i].setTextColor(on ? 0xFF15171C : TEXT_DIM);
        }
    }

    /** An on/off switch drawn like the reference design. */
    static final class Toggle extends View {
        interface Listener {
            void changed(boolean on);
        }

        private boolean mOn;
        private final Listener mListener;
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF mRect = new RectF();

        Toggle(Context c, boolean on, Listener listener) {
            super(c);
            mOn = on;
            mListener = listener;
            setFocusable(true);
            setClickable(true);
            setBackground(focusable(c, Color.TRANSPARENT, dp(c, 30)));
            setOnClickListener(v -> {
                mOn = !mOn;
                invalidate();
                mListener.changed(mOn);
            });
            enterClicks(this);
        }

        @Override
        protected void onMeasure(int w, int h) {
            setMeasuredDimension(dp(getContext(), 120), dp(getContext(), 50));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float pad = dp(getContext(), 4);
            mRect.set(pad, pad, getWidth() - pad, getHeight() - pad);
            float r = mRect.height() / 2f;
            mPaint.setStyle(Paint.Style.FILL);
            mPaint.setColor(mOn ? ACCENT : CARD_HI);
            canvas.drawRoundRect(mRect, r, r, mPaint);
            float knobR = r - dp(getContext(), 5);
            float cx = mOn ? mRect.right - r : mRect.left + r;
            mPaint.setColor(mOn ? 0xFF15171C : TEXT_DIM);
            canvas.drawCircle(cx, mRect.centerY(), knobR, mPaint);
        }
    }
}
