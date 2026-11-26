// Keisho — Android port: on-screen controls drawn over the game.
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Drives the virtual SDL gamepad created in android_glue.cpp, so the game
// sees an ordinary pad: left stick = stick / steering, RT = gas, LT = brake,
// LB / RB = gear down / up, A B X Y = buttons 1-4 (VR view buttons on racers),
// Start = start, Back = coin. Save / load / menu / FPS / quit are Keisho's
// keyboard shortcuts (F6 F7 F10 F8 F9). Touches that miss every control pass
// through to the game, which takes them as the mouse (aim and fire on gun
// games).
//
// SPOSTA turns on layout editing: every control can then be dragged anywhere,
// FATTO keeps the layout (per kind of game: driving, gun, 3 or 4 buttons) and
// PREDEFINITI puts the controls back where they started.

package io.github.carlovacco8.keisho;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.SparseIntArray;
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

    private enum Kind { STICK, ROUND, PILL }
    private enum Action { PAD_BUTTON, TRIGGER, KEY, STICK, EDIT, RESET }

    private static final class Control {
        Kind kind;
        Action action;
        int code;           // button, axis or Android key code
        String label;
        String sub;
        float fx, fy;       // centre, as fractions of the view
        float sizeDp;       // radius (round / stick) or height (pill)
        float widthDp;      // pill width
        boolean danger;
        boolean editOnly;   // shown only while editing the layout
        float defFx, defFy; // where build() put it
        // runtime
        float cx, cy, r, w, h;
        boolean pressed;
        float knobX, knobY;
    }

    private final List<Control> mControls = new ArrayList<>();
    private final SparseIntArray mPointerControl = new SparseIntArray();
    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mSubText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mRect = new RectF();
    private final float mScale;
    private final int mAlpha;
    private final String mHint;
    private String mProfile = "default";   // which saved layout this game uses
    private boolean mEditing;
    private Control mEditButton;
    private float mDragDx, mDragDy;

    TouchOverlay(Context context, GameDb.Game game, float scale, int alphaPercent) {
        super(context);
        mScale = scale;
        mAlpha = Math.max(20, Math.min(255, alphaPercent * 255 / 100));
        mFill.setStyle(Paint.Style.FILL);
        mStroke.setStyle(Paint.Style.STROKE);
        mText.setColor(0xFFFFFFFF);
        mText.setTextAlign(Paint.Align.CENTER);
        mText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        mSubText.setColor(0xFFE0E0E0);
        mSubText.setTextAlign(Paint.Align.CENTER);
        mHint = build(game);
        loadLayout();
    }

    // ------------------------------------------------------------------
    // Saved layouts
    // ------------------------------------------------------------------

    private SharedPreferences layoutPrefs() {
        return getContext().getSharedPreferences("touch_layout", Context.MODE_PRIVATE);
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
        mEditButton.label = on ? "FATTO" : "SPOSTA";
        if (!on) {
            saveLayout();
        }
        invalidate();
    }

    // ------------------------------------------------------------------
    // Layout per kind of game
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

    private Control pill(Action action, int code, String label, float fx, float fy) {
        return add(Kind.PILL, action, code, label, null, fx, fy, 40, 118);
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

        // Shared: top corners and the bottom row.
        pill(Action.KEY, KeyEvent.KEYCODE_F8, "FPS", 0.085f, 0.085f);
        pill(Action.KEY, KeyEvent.KEYCODE_F10, "MENU", 0.085f, 0.205f);
        mEditButton = pill(Action.EDIT, 0, "SPOSTA", 0.085f, 0.325f);
        pill(Action.RESET, 0, "PREDEFINITI", 0.5f, 0.085f).editOnly = true;
        Control exit = pill(Action.KEY, KeyEvent.KEYCODE_F9, "ESCI", 0.915f, 0.085f);
        exit.danger = true;
        pill(Action.PAD_BUTTON, BTN_BACK, "COIN", 0.31f, 0.925f);
        pill(Action.KEY, KeyEvent.KEYCODE_F6, "SALVA", 0.437f, 0.925f);
        pill(Action.KEY, KeyEvent.KEYCODE_F7, "CARICA", 0.563f, 0.925f);
        pill(Action.PAD_BUTTON, BTN_START, "START", 0.69f, 0.925f);

        mProfile = driving ? "drive" : gun ? "gun" : "btn" + buttons;
        if (gun) {
            return "Tocca lo schermo per mirare e sparare";
        }

        add(Kind.STICK, Action.STICK, 0, null, null, 0.15f, 0.56f, 72, 0);

        if (driving) {
            pill(Action.PAD_BUTTON, BTN_A, "VISTA", 0.915f, 0.205f);
            round(Action.TRIGGER, AXIS_RT, "A", "GAS", 0.80f, 0.72f, 50);
            round(Action.TRIGGER, AXIS_LT, "B", "FRENO", 0.905f, 0.52f, 46);
            round(Action.PAD_BUTTON, BTN_LB, "–", "MARCIA", 0.09f, 0.86f, 34);
            round(Action.PAD_BUTTON, BTN_RB, "+", "MARCIA", 0.21f, 0.86f, 34);
        } else {
            round(Action.PAD_BUTTON, BTN_A, "1", null, 0.80f, 0.74f, 44);
            round(Action.PAD_BUTTON, BTN_B, "2", null, 0.90f, 0.56f, 44);
            round(Action.PAD_BUTTON, BTN_X, "3", null, 0.70f, 0.56f, 44);
            if (buttons >= 4) {
                round(Action.PAD_BUTTON, BTN_Y, "4", null, 0.80f, 0.38f, 44);
            }
        }
        return null;
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        place(w, h);
    }

    private void place(int w, int h) {
        float density = getResources().getDisplayMetrics().density * mScale;
        for (Control c : mControls) {
            c.cx = c.fx * w;
            c.cy = c.fy * h;
            if (c.kind == Kind.PILL) {
                c.w = c.widthDp * density;
                c.h = c.sizeDp * density;
            } else {
                c.r = c.sizeDp * density;
            }
            c.knobX = c.cx;
            c.knobY = c.cy;
        }
        mText.setTextSize(15 * density);
        mSubText.setTextSize(10 * density);
    }

    // ------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas canvas) {
        float density = getResources().getDisplayMetrics().density;
        mStroke.setStrokeWidth(2f * density);
        if (mEditing) {
            canvas.drawColor(0x55000000);
        }
        for (Control c : mControls) {
            if (c.editOnly && !mEditing) {
                continue;
            }
            int fill = c.danger ? 0xFFC8473C : (c.pressed ? 0xFF4A505C : 0xFF1C1F26);
            mFill.setColor(fill);
            mFill.setAlpha(c.danger ? mAlpha : Math.min(255, mAlpha * 200 / 255));
            mStroke.setColor(0xFFFFFFFF);
            mStroke.setAlpha(c.danger ? 0 : mAlpha);
            mText.setAlpha(mAlpha);
            mSubText.setAlpha(mAlpha);
            switch (c.kind) {
                case PILL: {
                    mRect.set(c.cx - c.w / 2, c.cy - c.h / 2, c.cx + c.w / 2, c.cy + c.h / 2);
                    canvas.drawRoundRect(mRect, c.h / 2, c.h / 2, mFill);
                    if (!c.danger) {
                        mStroke.setAlpha(mAlpha / 3);
                        canvas.drawRoundRect(mRect, c.h / 2, c.h / 2, mStroke);
                    }
                    canvas.drawText(c.label, c.cx, c.cy - (mText.descent() + mText.ascent()) / 2, mText);
                    break;
                }
                case ROUND: {
                    canvas.drawCircle(c.cx, c.cy, c.r, mFill);
                    canvas.drawCircle(c.cx, c.cy, c.r, mStroke);
                    if (c.sub != null) {
                        float big = mText.getTextSize();
                        mText.setTextSize(big * 1.6f);
                        canvas.drawText(c.label, c.cx, c.cy - c.r * 0.08f, mText);
                        mText.setTextSize(big);
                        canvas.drawText(c.sub, c.cx, c.cy + c.r * 0.5f, mSubText);
                    } else {
                        float big = mText.getTextSize();
                        mText.setTextSize(big * 1.6f);
                        canvas.drawText(c.label, c.cx, c.cy - (mText.descent() + mText.ascent()) / 2, mText);
                        mText.setTextSize(big);
                    }
                    break;
                }
                case STICK: {
                    mFill.setColor(0xFF0E1014);
                    mFill.setAlpha(mAlpha * 180 / 255);
                    canvas.drawCircle(c.cx, c.cy, c.r * 1.12f, mFill);
                    canvas.drawCircle(c.cx, c.cy, c.r, mStroke);
                    mFill.setColor(0xFFC9CCD3);
                    mFill.setAlpha(mAlpha);
                    canvas.drawCircle(c.knobX, c.knobY, c.r * 0.45f, mFill);
                    break;
                }
            }
        }
        if (mEditing) {
            mSubText.setAlpha(255);
            canvas.drawText("Trascina i comandi dove vuoi, poi tocca FATTO",
                    getWidth() / 2f, getHeight() * 0.20f, mSubText);
        } else if (mHint != null) {
            mSubText.setAlpha(mAlpha);
            canvas.drawText(mHint, getWidth() / 2f, getHeight() * 0.80f, mSubText);
        }
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
                float reach = c.kind == Kind.STICK ? c.r * 1.6f : c.r * 1.15f;
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
                    performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
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

    private void press(Control c, float x, float y) {
        if (c.action == Action.EDIT) {
            setEditing(true);
            performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            return;
        }
        if (c.action == Action.RESET) {
            return;
        }
        c.pressed = true;
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
        }
        performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
        invalidate();
    }

    private void release(Control c) {
        c.pressed = false;
        switch (c.action) {
            case EDIT:
            case RESET:
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
        }
        invalidate();
    }

    private void moveStick(Control c, float x, float y) {
        float dx = x - c.cx, dy = y - c.cy;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len > c.r) {
            dx = dx / len * c.r;
            dy = dy / len * c.r;
        }
        c.knobX = c.cx + dx;
        c.knobY = c.cy + dy;
        float vx = dx / c.r, vy = dy / c.r;
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
            release(mControls.get(mPointerControl.valueAt(i)));
        }
        mPointerControl.clear();
    }
}
