// Keisho — Android port: on-screen controls drawn over the game.
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Laid out and drawn as M2EA's: STATS and SETTINGS top left, EXIT and VIEW
// top right, the stick at the left with GEAR- / GEAR+ under it, B BRAKE and
// A GAS at the right, and COIN / SAVE / SLOT / LOAD / START along the foot.
//
// Drives the virtual SDL gamepad created in android_glue.cpp, so the game
// sees an ordinary pad: left stick = stick / steering, RT = gas, LT = brake,
// LB / RB = gear down / up, A B X Y = buttons 1-4 (VR view buttons on racers),
// Start = start, Back = coin. SAVE / LOAD / SETTINGS / STATS are Keisho's
// keyboard shortcuts (F6 F7 F10 F8); EXIT is Esc, which goes back to the game
// list (or closes Keisho when it was started on a game by ES-DE, as there is
// no list to go back to); SLOT picks the slot SAVE and
// LOAD use. Touches that miss every control pass through to the game, which
// takes them as the mouse (aim and fire on gun games).
//
// Holding SETTINGS turns on layout editing: every control can then be
// dragged anywhere, FATTO keeps the layout (per kind of game: driving, gun,
// 3 or 4 buttons) and PREDEFINITI puts the controls back where they started.
// The whole overlay hides while Keisho's settings are open.

package io.github.carlovacco8.keisho;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.util.SparseIntArray;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import org.libsdl.app.SDLActivity;

import java.util.ArrayList;
import java.util.List;

final class TouchOverlay extends View {
    // SDL_GamepadButton / SDL_GamepadAxis values.
    static final int BTN_A = 0, BTN_B = 1, BTN_X = 2, BTN_Y = 3, BTN_BACK = 4, BTN_START = 6;
    static final int BTN_LB = 9, BTN_RB = 10;
    static final int AXIS_LX = 0, AXIS_LY = 1, AXIS_LT = 4, AXIS_RT = 5;

    private static final long HOLD_FOR_EDIT_MS = 650;
    private static final int SLOTS = 4;

    private enum Kind { STICK, ROUND, PILL }
    private enum Action { PAD_BUTTON, TRIGGER, KEY, STICK, EDIT, RESET, SETTINGS, SLOT }
    private enum Look { DARK, LIGHT, RED, SMALL }
    private enum Icon { NONE, SAVE, SLOT, LOAD }

    private static final class Control {
        Kind kind;
        Action action;
        Look look = Look.DARK;
        Icon icon = Icon.NONE;
        int code;           // button, axis or Android key code
        String label;
        String sub;
        float fx, fy;       // centre, as fractions of the view
        float sizeDp;       // radius (round / stick) or height (pill)
        float widthDp;      // pill width
        boolean editOnly;   // shown only while editing the layout
        float defFx, defFy; // where build() put it
        // runtime
        float cx, cy, r, w, h;
        boolean pressed;
        long downAt;
        boolean consumed;   // a hold already did something: the release does nothing
        float knobX, knobY;
    }

    private final List<Control> mControls = new ArrayList<>();
    private final SparseIntArray mPointerControl = new SparseIntArray();
    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mSubText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mRect = new RectF();
    private final Path mPath = new Path();
    private final float mScale;
    private final int mAlpha;
    private final String mHint;
    private String mProfile = "default";   // which saved layout this game uses
    private boolean mEditing;
    private Control mEditButton;
    private Control mSlotButton;
    private int mSlot;
    private float mDragDx, mDragDy;
    private float mDensity = 1f;

    TouchOverlay(Context context, GameDb.Game game, float scale, int alphaPercent) {
        super(context);
        mScale = scale;
        mAlpha = Math.max(20, Math.min(255, alphaPercent * 255 / 100));
        mFill.setStyle(Paint.Style.FILL);
        mStroke.setStyle(Paint.Style.STROKE);
        mText.setColor(0xFFFFFFFF);
        mText.setTextAlign(Paint.Align.CENTER);
        mText.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        mSubText.setColor(0xFFFFFFFF);
        mSubText.setTextAlign(Paint.Align.CENTER);
        mSubText.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        mHint = build(game);
        loadLayout();
        mSlot = Math.max(1, Math.min(SLOTS, layoutPrefs().getInt("slot", 1)));
        mSlotButton.label = "SLOT " + mSlot;
        KeishoActivity.setSlot(mSlot);
    }

    // ------------------------------------------------------------------
    // Saved layouts
    // ------------------------------------------------------------------

    private SharedPreferences layoutPrefs() {
        // "touch_layout2": the M2EA layout; layouts saved for the old one
        // numbered the controls differently.
        return getContext().getSharedPreferences("touch_layout2", Context.MODE_PRIVATE);
    }

    private void loadLayout() {
        SharedPreferences p = layoutPrefs();
        for (int i = 0; i < mControls.size(); i++) {
            Control c = mControls.get(i);
            String key = mProfile + "." + i;
            if (p.contains(key + ".x") && p.contains(key + ".y")) {
                c.fx = clamp01(p.getFloat(key + ".x", c.fx));
                c.fy = clamp01(p.getFloat(key + ".y", c.fy));
            }
        }
    }

    private void saveLayout() {
        SharedPreferences.Editor e = layoutPrefs().edit();
        for (int i = 0; i < mControls.size(); i++) {
            Control c = mControls.get(i);
            e.putFloat(mProfile + "." + i + ".x", c.fx);
            e.putFloat(mProfile + "." + i + ".y", c.fy);
        }
        e.apply();
    }

    private void resetLayout() {
        SharedPreferences.Editor e = layoutPrefs().edit();
        for (int i = 0; i < mControls.size(); i++) {
            Control c = mControls.get(i);
            c.fx = c.defFx;
            c.fy = c.defFy;
            e.remove(mProfile + "." + i + ".x");
            e.remove(mProfile + "." + i + ".y");
        }
        e.apply();
        place(getWidth(), getHeight());
        invalidate();
    }

    private static float clamp01(float v) {
        return Math.max(0.02f, Math.min(0.98f, v));
    }

    private void setEditing(boolean on) {
        if (on == mEditing) {
            return;
        }
        releaseAll();
        mEditing = on;
        if (!on) {
            saveLayout();
        }
        invalidate();
    }

    // ------------------------------------------------------------------
    // Layout per kind of game (positions measured on M2EA's)
    // ------------------------------------------------------------------

    private Control add(Kind kind, Action action, int code, String label, String sub,
                        float fx, float fy, float sizeDp, float widthDp) {
        Control c = new Control();
        c.kind = kind;
        c.action = action;
        c.code = code;
        c.label = label;
        c.sub = sub;
        c.fx = fx;
        c.fy = fy;
        c.defFx = fx;
        c.defFy = fy;
        c.sizeDp = sizeDp;
        c.widthDp = widthDp;
        mControls.add(c);
        return c;
    }

    private Control pill(Action action, int code, String label, float fx, float fy, float w, float h) {
        return add(Kind.PILL, action, code, label, null, fx, fy, h, w);
    }

    /** The small see-through buttons along the foot. */
    private Control foot(Action action, int code, String label, Icon icon, float fx, float w) {
        Control c = add(Kind.PILL, action, code, label, null, fx, 0.934f, 32, w);
        c.look = Look.SMALL;
        c.icon = icon;
        return c;
    }

    private Control round(Action action, int code, String label, String sub, float fx, float fy, float r) {
        return add(Kind.ROUND, action, code, label, sub, fx, fy, r, 0);
    }

    private String build(GameDb.Game game) {
        boolean driving = game != null && game.isDriving();
        boolean gun = game != null && game.isGun() && !driving;
        int buttons = 3;
        if (game != null && (game.has("fighting") || game.has("vr4") || game.has("spikeout"))) {
            buttons = 4;
        }
        if (game != null && game.has("soccer")) {
            buttons = 3;
        }

        // Shared: the corners and the foot.
        pill(Action.KEY, KeyEvent.KEYCODE_F8, "STATS", 0.070f, 0.080f, 98, 46);
        pill(Action.SETTINGS, KeyEvent.KEYCODE_F10, "SETTINGS", 0.086f, 0.220f, 120, 48);
        pill(Action.KEY, KeyEvent.KEYCODE_ESCAPE, "EXIT", 0.912f, 0.080f, 90, 46).look = Look.RED;
        mEditButton = pill(Action.EDIT, 0, "FATTO", 0.40f, 0.080f, 110, 44);
        mEditButton.editOnly = true;
        mEditButton.look = Look.LIGHT;
        pill(Action.RESET, 0, "PREDEFINITI", 0.60f, 0.080f, 140, 44).editOnly = true;
        foot(Action.PAD_BUTTON, BTN_BACK, "COIN", Icon.NONE, 0.270f, 95);
        foot(Action.KEY, KeyEvent.KEYCODE_F6, "SAVE", Icon.SAVE, 0.367f, 95);
        mSlotButton = foot(Action.SLOT, 0, "SLOT 1", Icon.SLOT, 0.500f, 100);
        foot(Action.KEY, KeyEvent.KEYCODE_F7, "LOAD", Icon.LOAD, 0.633f, 95);
        foot(Action.PAD_BUTTON, BTN_START, "START", Icon.NONE, 0.731f, 95);

        mProfile = driving ? "drive" : gun ? "gun" : "btn" + buttons;
        if (gun) {
            return "Tocca lo schermo per mirare e sparare";
        }

        add(Kind.STICK, Action.STICK, 0, null, null, 0.119f, 0.580f, 74, 0);

        if (driving) {
            Control view = pill(Action.PAD_BUTTON, BTN_A, "VIEW", 0.912f, 0.217f, 90, 46);
            view.look = Look.DARK;
            round(Action.TRIGGER, AXIS_RT, "A", "GAS", 0.766f, 0.746f, 42).look = Look.LIGHT;
            round(Action.TRIGGER, AXIS_LT, "B", "BRAKE", 0.865f, 0.541f, 42);
            pill(Action.PAD_BUTTON, BTN_LB, "GEAR-", 0.066f, 0.855f, 82, 46);
            pill(Action.PAD_BUTTON, BTN_RB, "GEAR+", 0.184f, 0.855f, 82, 46);
        } else {
            round(Action.PAD_BUTTON, BTN_A, "1", null, 0.790f, 0.740f, 40).look = Look.LIGHT;
            round(Action.PAD_BUTTON, BTN_B, "2", null, 0.890f, 0.560f, 40);
            round(Action.PAD_BUTTON, BTN_X, "3", null, 0.690f, 0.560f, 40);
            if (buttons >= 4) {
                round(Action.PAD_BUTTON, BTN_Y, "4", null, 0.790f, 0.380f, 40);
            }
        }
        return null;
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        place(w, h);
    }

    private void place(int w, int h) {
        mDensity = getResources().getDisplayMetrics().density * mScale;
        for (Control c : mControls) {
            c.cx = c.fx * w;
            c.cy = c.fy * h;
            if (c.kind == Kind.PILL) {
                c.w = c.widthDp * mDensity;
                c.h = c.sizeDp * mDensity;
            } else {
                c.r = c.sizeDp * mDensity;
            }
            c.knobX = c.cx;
            c.knobY = c.cy;
        }
    }

    // ------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------

    /** a (0..255) scaled by the user's transparency setting. */
    private int alpha(int a) {
        return Math.max(0, Math.min(255, a * mAlpha / 255));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final float d = mDensity;
        if (mEditing) {
            canvas.drawColor(0x66000000);
        }
        for (Control c : mControls) {
            if (c.editOnly && !mEditing) {
                continue;
            }
            switch (c.kind) {
                case PILL:
                    drawPill(canvas, c, d);
                    break;
                case ROUND:
                    drawRound(canvas, c, d);
                    break;
                case STICK:
                    drawStick(canvas, c, d);
                    break;
            }
        }
        if (mEditing) {
            mSubText.setTextSize(13 * d);
            mSubText.setAlpha(255);
            canvas.drawText("Trascina i comandi dove vuoi, poi tocca FATTO",
                    getWidth() / 2f, getHeight() * 0.20f, mSubText);
        } else if (mHint != null) {
            mSubText.setTextSize(11 * d);
            mSubText.setAlpha(alpha(200));
            canvas.drawText(mHint, getWidth() / 2f, getHeight() * 0.80f, mSubText);
        }
    }

    private void drawPill(Canvas canvas, Control c, float d) {
        mRect.set(c.cx - c.w / 2, c.cy - c.h / 2, c.cx + c.w / 2, c.cy + c.h / 2);
        float radius = c.h / 2;
        int fill;
        int fillAlpha;
        int edge = 0;
        int edgeAlpha = 0;
        int ink = 0xFFFFFFFF;
        switch (c.look) {
            case RED:
                fill = c.pressed ? 0xFFE0594D : 0xFFC8473C;
                fillAlpha = 255;
                break;
            case LIGHT:
                fill = c.pressed ? 0xFFFFFFFF : 0xFFE6E8EC;
                fillAlpha = 255;
                ink = 0xFF121418;
                break;
            case SMALL:
                fill = c.pressed ? 0xFF4A505C : 0xFF1C1F26;
                fillAlpha = 150;
                edge = 0xFFFFFFFF;
                edgeAlpha = 90;
                break;
            default:
                fill = c.pressed ? 0xFF3A3F49 : 0xFF1F232A;
                fillAlpha = 235;
                edge = 0xFFFFFFFF;
                edgeAlpha = 70;
                break;
        }
        mFill.setColor(fill);
        mFill.setAlpha(alpha(fillAlpha));
        canvas.drawRoundRect(mRect, radius, radius, mFill);
        if (edge != 0) {
            mStroke.setColor(edge);
            mStroke.setAlpha(alpha(edgeAlpha));
            mStroke.setStrokeWidth(1.2f * d);
            canvas.drawRoundRect(mRect, radius, radius, mStroke);
        }

        Paint text = c.look == Look.SMALL ? mSubText : mText;
        text.setTextSize((c.look == Look.SMALL ? 11.5f : 15f) * d);
        text.setColor(ink);
        text.setAlpha(alpha(c.look == Look.SMALL ? 220 : 255));
        float baseline = c.cy - (text.descent() + text.ascent()) / 2;
        if (c.icon == Icon.NONE) {
            canvas.drawText(c.label, c.cx, baseline, text);
            text.setColor(0xFFFFFFFF);
            return;
        }
        // Icon then label, centred together.
        float icon = 10 * d;
        float gap = 7 * d;
        float tw = text.measureText(c.label);
        float x0 = c.cx - (icon + gap + tw) / 2;
        drawIcon(canvas, c.icon, x0, c.cy, icon, d, alpha(220));
        text.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(c.label, x0 + icon + gap, baseline, text);
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(0xFFFFFFFF);
    }

    /** M2EA's little marks: a tray with an arrow down (save) or up (load), lines for the slot. */
    private void drawIcon(Canvas canvas, Icon icon, float x, float cy, float s, float d, int a) {
        mStroke.setColor(0xFFFFFFFF);
        mStroke.setAlpha(a);
        mStroke.setStrokeWidth(1.4f * d);
        float top = cy - s / 2, bottom = cy + s / 2;
        mRect.set(x, top, x + s, bottom);
        canvas.drawRoundRect(mRect, 1.5f * d, 1.5f * d, mStroke);
        float mx = x + s / 2;
        switch (icon) {
            case SAVE:
                mPath.reset();
                mPath.moveTo(mx - s * 0.22f, cy - s * 0.05f);
                mPath.lineTo(mx, cy + s * 0.2f);
                mPath.lineTo(mx + s * 0.22f, cy - s * 0.05f);
                canvas.drawPath(mPath, mStroke);
                canvas.drawLine(mx, top + s * 0.18f, mx, cy + s * 0.2f, mStroke);
                break;
            case LOAD:
                mPath.reset();
                mPath.moveTo(mx - s * 0.22f, cy + s * 0.02f);
                mPath.lineTo(mx, cy - s * 0.22f);
                mPath.lineTo(mx + s * 0.22f, cy + s * 0.02f);
                canvas.drawPath(mPath, mStroke);
                canvas.drawLine(mx, cy - s * 0.22f, mx, bottom - s * 0.18f, mStroke);
                break;
            case SLOT:
                for (int i = 0; i < 3; i++) {
                    float y = top + s * (0.3f + 0.2f * i);
                    canvas.drawLine(x + s * 0.25f, y, x + s * 0.75f, y, mStroke);
                }
                break;
            default:
                break;
        }
    }

    private void drawRound(Canvas canvas, Control c, float d) {
        boolean light = c.look == Look.LIGHT;
        mFill.setColor(light ? (c.pressed ? 0xFFC4C8CF : 0xFFA4A9B1) : (c.pressed ? 0xFF3A3F49 : 0xFF1A1D23));
        mFill.setAlpha(alpha(light ? 190 : 225));
        canvas.drawCircle(c.cx, c.cy, c.r, mFill);
        mStroke.setColor(0xFFFFFFFF);
        mStroke.setAlpha(alpha(light ? 120 : 200));
        mStroke.setStrokeWidth(1.6f * d);
        canvas.drawCircle(c.cx, c.cy, c.r, mStroke);

        mText.setColor(0xFFFFFFFF);
        mText.setAlpha(alpha(255));
        if (c.sub != null) {
            mText.setTextSize(c.r * 0.72f);
            canvas.drawText(c.label, c.cx, c.cy + c.r * 0.08f, mText);
            mSubText.setTextSize(c.r * 0.28f);
            mSubText.setAlpha(alpha(230));
            canvas.drawText(c.sub, c.cx, c.cy + c.r * 0.52f, mSubText);
        } else {
            mText.setTextSize(c.r * 0.8f);
            canvas.drawText(c.label, c.cx, c.cy - (mText.descent() + mText.ascent()) / 2, mText);
        }
    }

    private void drawStick(Canvas canvas, Control c, float d) {
        // A dark well with a light rim, and a light knob.
        mFill.setColor(0xFF15171C);
        mFill.setAlpha(alpha(200));
        canvas.drawCircle(c.cx, c.cy, c.r, mFill);
        mStroke.setColor(0xFFE4E6EA);
        mStroke.setAlpha(alpha(230));
        mStroke.setStrokeWidth(3.2f * d);
        canvas.drawCircle(c.cx, c.cy, c.r - 1.6f * d, mStroke);
        mFill.setColor(c.pressed ? 0xFFE6E8EC : 0xFFC9CCD3);
        mFill.setAlpha(alpha(255));
        canvas.drawCircle(c.knobX, c.knobY, c.r * 0.37f, mFill);
    }

    // ------------------------------------------------------------------
    // Touch
    // ------------------------------------------------------------------

    private int hit(float x, float y) {
        for (int i = 0; i < mControls.size(); i++) {
            Control c = mControls.get(i);
            if (c.editOnly && !mEditing) {
                continue;
            }
            if (c.kind == Kind.PILL) {
                float slop = c.h * 0.2f;
                if (Math.abs(x - c.cx) <= c.w / 2 + slop && Math.abs(y - c.cy) <= c.h / 2 + slop) {
                    return i;
                }
            } else {
                float reach = c.kind == Kind.STICK ? c.r * 1.5f : c.r * 1.15f;
                float dx = x - c.cx, dy = y - c.cy;
                if (dx * dx + dy * dy <= reach * reach) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** Layout editing: one finger drags one control; nothing reaches the game. */
    private boolean onEditTouch(MotionEvent e) {
        int index = e.getActionIndex();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                int ci = hit(e.getX(index), e.getY(index));
                mPointerControl.clear();
                if (ci < 0) {
                    return true;
                }
                Control c = mControls.get(ci);
                if (c.action == Action.EDIT) {
                    setEditing(false);
                    return true;
                }
                if (c.action == Action.RESET) {
                    resetLayout();
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                    return true;
                }
                mPointerControl.put(e.getPointerId(index), ci);
                mDragDx = c.cx - e.getX(index);
                mDragDy = c.cy - e.getY(index);
                c.pressed = true;
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                int ci = mPointerControl.get(e.getPointerId(0), -1);
                if (ci >= 0 && getWidth() > 0 && getHeight() > 0) {
                    Control c = mControls.get(ci);
                    c.fx = clamp01((e.getX(0) + mDragDx) / getWidth());
                    c.fy = clamp01((e.getY(0) + mDragDy) / getHeight());
                    c.cx = c.fx * getWidth();
                    c.cy = c.fy * getHeight();
                    c.knobX = c.cx;
                    c.knobY = c.cy;
                    invalidate();
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                for (Control c : mControls) {
                    c.pressed = false;
                }
                mPointerControl.clear();
                invalidate();
                return true;
            }
            default:
                return true;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (mEditing) {
            return onEditTouch(e);
        }
        int action = e.getActionMasked();
        int index = e.getActionIndex();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int ci = hit(e.getX(index), e.getY(index));
                if (ci < 0) {
                    // Not ours: the first finger goes on to the game (gun aim).
                    return action == MotionEvent.ACTION_POINTER_DOWN;
                }
                mPointerControl.put(e.getPointerId(index), ci);
                press(mControls.get(ci), e.getX(index), e.getY(index));
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                for (int p = 0; p < e.getPointerCount(); p++) {
                    int ci = mPointerControl.get(e.getPointerId(p), -1);
                    if (ci >= 0 && mControls.get(ci).kind == Kind.STICK) {
                        moveStick(mControls.get(ci), e.getX(p), e.getY(p));
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                int id = e.getPointerId(index);
                int ci = mPointerControl.get(id, -1);
                mPointerControl.delete(id);
                if (ci >= 0) {
                    release(mControls.get(ci));
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL: {
                for (int i = 0; i < mPointerControl.size(); i++) {
                    release(mControls.get(mPointerControl.valueAt(i)));
                }
                mPointerControl.clear();
                return true;
            }
            default:
                return true;
        }
    }

    private void press(final Control c, float x, float y) {
        if (c.action == Action.EDIT || c.action == Action.RESET) {
            return;
        }
        c.pressed = true;
        c.consumed = false;
        c.downAt = SystemClock.uptimeMillis();
        switch (c.action) {
            case STICK:
                moveStick(c, x, y);
                return;
            case PAD_BUTTON:
                KeishoActivity.touchButton(c.code, true);
                break;
            case TRIGGER:
                KeishoActivity.touchAxis(c.code, 1f);
                break;
            case KEY:
                SDLActivity.onNativeKeyDown(c.code);
                break;
            case SETTINGS: {
                // A tap opens the settings (on release); a hold moves the controls.
                final long at = c.downAt;
                postDelayed(() -> {
                    if (c.pressed && c.downAt == at && !c.consumed) {
                        c.consumed = true;
                        c.pressed = false;
                        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                        setEditing(true);
                    }
                }, HOLD_FOR_EDIT_MS);
                break;
            }
            case SLOT:
                mSlot = mSlot % SLOTS + 1;
                c.label = "SLOT " + mSlot;
                layoutPrefs().edit().putInt("slot", mSlot).apply();
                KeishoActivity.setSlot(mSlot);
                break;
            default:
                break;
        }
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        invalidate();
    }

    private void release(Control c) {
        boolean wasPressed = c.pressed;
        c.pressed = false;
        switch (c.action) {
            case EDIT:
            case RESET:
            case SLOT:
                break;
            case STICK:
                c.knobX = c.cx;
                c.knobY = c.cy;
                KeishoActivity.touchAxis(AXIS_LX, 0f);
                KeishoActivity.touchAxis(AXIS_LY, 0f);
                break;
            case PAD_BUTTON:
                KeishoActivity.touchButton(c.code, false);
                break;
            case TRIGGER:
                KeishoActivity.touchAxis(c.code, 0f);
                break;
            case KEY:
                SDLActivity.onNativeKeyUp(c.code);
                break;
            case SETTINGS:
                if (wasPressed && !c.consumed) {
                    c.consumed = true;
                    SDLActivity.onNativeKeyDown(c.code);
                    SDLActivity.onNativeKeyUp(c.code);
                }
                break;
        }
        invalidate();
    }

    private void moveStick(Control c, float x, float y) {
        float dx = x - c.cx, dy = y - c.cy;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        float reach = c.r * 0.8f;
        if (len > reach) {
            dx = dx / len * reach;
            dy = dy / len * reach;
        }
        c.knobX = c.cx + dx;
        c.knobY = c.cy + dy;
        float vx = dx / reach, vy = dy / reach;
        // A small dead zone keeps a resting thumb from steering.
        if (Math.abs(vx) < 0.08f) vx = 0;
        if (Math.abs(vy) < 0.08f) vy = 0;
        KeishoActivity.touchAxis(AXIS_LX, vx);
        KeishoActivity.touchAxis(AXIS_LY, vy);
        invalidate();
    }

    /** Lets go of everything, e.g. when the overlay is hidden mid-press. */
    void releaseAll() {
        for (int i = 0; i < mPointerControl.size(); i++) {
            Control c = mControls.get(mPointerControl.valueAt(i));
            if (c.action == Action.SETTINGS) {
                c.consumed = true;  // hidden mid-tap: do not open the settings on top
            }
            release(c);
        }
        mPointerControl.clear();
    }
}
