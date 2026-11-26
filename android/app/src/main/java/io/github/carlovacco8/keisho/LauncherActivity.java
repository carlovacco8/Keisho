// Keisho — Android port: the launcher (game carousel and settings).
// SPDX-License-Identifier: GPL-3.0-or-later
//
// A native Android front end in the style of a console menu: tabs at the top
// (Giochi / Video / Comandi / Audio / Sistema), a carousel of game cards with
// their pictures, and Play / Search / Quit at the bottom. It works with touch
// and with a TV remote or pad. Settings are written straight into keisho.ini;
// the touch-control options live in SharedPreferences. Games start in
// KeishoActivity, which runs in its own process.

package io.github.carlovacco8.keisho;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class LauncherActivity extends Activity {
    static final String PREFS = "keisho";
    private static final int REQUEST_FOLDER = 31;
    private static final String[] TABS = { "Giochi", "Video", "Comandi", "Audio", "Sistema" };

    private final Handler mMain = new Handler(Looper.getMainLooper());
    private GameDb mDb;
    private Artwork mArtwork;
    private Ini mIni;
    private SharedPreferences mPrefs;

    private final List<GameDb.Game> mAll = new ArrayList<>();
    private final List<GameDb.Game> mShown = new ArrayList<>();
    private int mArchives;
    private int mSelected;
    private int mTab;
    private String mFilter = "";
    private String mPickingKey;

    private TextView mCounts;
    private final List<TextView> mTabViews = new ArrayList<>();
    private FrameLayout mContent;
    private HorizontalScrollView mCarousel;
    private LinearLayout mStrip;
    private TextView mPosition;
    private final List<View> mCards = new ArrayList<>();
    private int mCardW;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemBars();
        if (showLastCrash()) {
            return;   // the launcher starts when the report is closed
        }
        startLauncher();
    }

    /**
     * After a crash, show its report before anything else runs, so even a
     * crash on every start can be read (and photographed) on the device.
     */
    private boolean showLastCrash() {
        final File crash = new File(getFilesDir(), KeishoApp.CRASH_FILE);
        if (!crash.isFile()) {
            return false;
        }
        String text;
        try {
            text = new String(java.nio.file.Files.readAllBytes(crash.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            text = "(rapporto illeggibile: " + e + ")";
        }
        crash.delete();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(0xFF15171C);
        int pad = dp(16);
        box.setPadding(pad, pad, pad, pad);
        TextView title = new TextView(this);
        title.setText("Keisho si è chiuso per un errore. Fai uno screenshot e mandalo:");
        title.setTextColor(0xFFFFC857);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        box.addView(title);
        ScrollView scroll = new ScrollView(this);
        TextView body = new TextView(this);
        body.setText(text);
        body.setTextColor(Color.WHITE);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        body.setTypeface(android.graphics.Typeface.MONOSPACE);
        body.setTextIsSelectable(true);
        scroll.addView(body);
        box.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        TextView go = new TextView(this);
        go.setText("CONTINUA");
        go.setTextColor(0xFF15171C);
        go.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        go.setGravity(Gravity.CENTER);
        go.setPadding(pad, pad / 2, pad, pad / 2);
        go.setBackgroundColor(0xFFFFC857);
        go.setFocusable(true);
        go.setOnClickListener(v -> startLauncher());
        box.addView(go, new LinearLayout.LayoutParams(-1, -2));
        setContentView(box);
        go.requestFocus();
        return true;
    }

    private void startLauncher() {
        Setup.prepare(this);
        mPrefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        mArtwork = new Artwork(this);
        setContentView(buildRoot());
        selectTab(0);
        requestStorageAccess();
        new Updater(this).checkAsync(false);
        new Thread(() -> {
            GameDb db = GameDb.load(this);
            mMain.post(() -> {
                mDb = db;
                rescan();
            });
        }, "keisho-gamedb").start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemBars();
        mIni = new Ini(Setup.iniFile(this));
        if (mDb != null) {
            rescan();
        }
    }

    private void hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    private int dp(float v) {
        return Ui.dp(this, v);
    }

    // ------------------------------------------------------------------
    // Layout
    // ------------------------------------------------------------------

    private View buildRoot() {
        FrameLayout root = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] { Ui.BG_TOP, Ui.BG_BOTTOM });
        root.setBackground(bg);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(18), dp(12), dp(18), dp(12));
        root.addView(column, new FrameLayout.LayoutParams(-1, -1));

        column.addView(buildTopBar(), new LinearLayout.LayoutParams(-1, dp(64)));
        mContent = new FrameLayout(this);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, 0, 1);
        cp.topMargin = dp(10);
        cp.bottomMargin = dp(10);
        column.addView(mContent, cp);
        column.addView(buildBottomBar(), new LinearLayout.LayoutParams(-1, dp(68)));
        return root;
    }

    private View buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackground(Ui.rounded(0xCC1A1D24, dp(18), Ui.STROKE, dp(1)));
        bar.setPadding(dp(22), 0, dp(14), 0);

        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.VERTICAL);
        LinearLayout titleLine = new LinearLayout(this);
        titleLine.setGravity(Gravity.BOTTOM);
        titleLine.addView(Ui.text(this, "KEISHO", 22, Ui.ACCENT, true));
        TextView ver = Ui.text(this, "  build " + versionCode(), 11, Ui.TEXT_DIM, false);
        titleLine.addView(ver);
        brand.addView(titleLine);
        mCounts = Ui.text(this, "", 10, Ui.TEXT_DIM, false);
        mCounts.setLetterSpacing(0.05f);
        mCounts.setPadding(0, dp(4), 0, 0);
        brand.addView(mCounts);
        bar.addView(brand, new LinearLayout.LayoutParams(dp(170), -2));

        LinearLayout tabs = new LinearLayout(this);
        tabs.setGravity(Gravity.CENTER);
        for (int i = 0; i < TABS.length; i++) {
            final int index = i;
            TextView t = Ui.text(this, TABS[i], 19, Ui.TEXT_DIM, false);
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(18), dp(7), dp(18), dp(7));
            t.setFocusable(true);
            t.setClickable(true);
            Ui.enterClicks(t);
            t.setOnClickListener(v -> selectTab(index));
            mTabViews.add(t);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.leftMargin = dp(4);
            tabs.addView(t, lp);
        }
        bar.addView(tabs, new LinearLayout.LayoutParams(0, -1, 1));
        bar.addView(new View(this), new LinearLayout.LayoutParams(dp(150), 1));
        return bar;
    }

    private View buildBottomBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackground(Ui.rounded(0xCC1A1D24, dp(20), Ui.STROKE, dp(1)));
        bar.setPadding(dp(14), dp(8), dp(14), dp(8));

        TextView play = Ui.pill(this, "▶   GIOCA", Ui.ACCENT, 0xFF15171C, v -> playSelected());
        play.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        bar.addView(play, new LinearLayout.LayoutParams(dp(220), -1));

        bar.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));

        EditText search = new EditText(this);
        search.setHint("CERCA");
        search.setHintTextColor(0xFF9A9EA8);
        search.setTextColor(0xFF15171C);
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        search.setGravity(Gravity.CENTER);
        search.setSingleLine(true);
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        search.setBackground(Ui.focusable(this, Ui.PILL_WHITE, dp(30)));
        search.setPadding(dp(20), 0, dp(20), 0);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                mFilter = s.toString().trim().toLowerCase(Locale.ROOT);
                if (mTab != 0) {
                    selectTab(0);
                }
                applyFilter();
            }
        });
        bar.addView(search, new LinearLayout.LayoutParams(dp(300), -1));

        bar.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));

        TextView quit = Ui.pill(this, "ESCI", Ui.RED, Color.WHITE, v -> finishAffinity());
        bar.addView(quit, new LinearLayout.LayoutParams(dp(150), -1));
        return bar;
    }

    private void selectTab(int index) {
        mTab = index;
        for (int i = 0; i < mTabViews.size(); i++) {
            TextView t = mTabViews.get(i);
            boolean on = i == index;
            t.setBackground(Ui.focusable(this, on ? Ui.PILL_WHITE : Color.TRANSPARENT, dp(30)));
            t.setTextColor(on ? 0xFF15171C : Ui.TEXT_DIM);
        }
        mContent.removeAllViews();
        mIni = new Ini(Setup.iniFile(this));
        switch (index) {
            case 0: mContent.addView(buildGamesPage()); break;
            case 1: mContent.addView(scrollPage(buildVideoRows())); break;
            case 2: mContent.addView(scrollPage(buildInputRows())); break;
            case 3: mContent.addView(scrollPage(buildAudioRows())); break;
            default: mContent.addView(scrollPage(buildSystemRows())); break;
        }
    }

    private View scrollPage(List<View> rows) {
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(true);
        scroll.setFillViewport(true);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(80), dp(4), dp(80), dp(4));
        for (View r : rows) {
            list.addView(r);
        }
        scroll.addView(list, new ViewGroup.LayoutParams(-1, -2));
        return scroll;
    }

    // ------------------------------------------------------------------
    // Games: the carousel
    // ------------------------------------------------------------------

    private View buildGamesPage() {
        FrameLayout page = new FrameLayout(this);
        mCarousel = new HorizontalScrollView(this);
        mCarousel.setHorizontalScrollBarEnabled(false);
        mCarousel.setFillViewport(true);
        mStrip = new LinearLayout(this);
        mStrip.setOrientation(LinearLayout.HORIZONTAL);
        mStrip.setGravity(Gravity.CENTER_VERTICAL);
        mCarousel.addView(mStrip, new ViewGroup.LayoutParams(-2, -1));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -1);
        lp.bottomMargin = dp(22);
        page.addView(mCarousel, lp);

        mPosition = Ui.text(this, "", 12, Ui.TEXT_DIM, false);
        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        page.addView(mPosition, pp);

        mCarousel.setOnScrollChangeListener((v, x, y, ox, oy) -> {
            mMain.removeCallbacks(mSnap);
            mMain.postDelayed(mSnap, 140);
        });
        page.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - t != ob - ot) {
                mMain.post(this::fillCarousel);
            }
        });
        mMain.post(this::fillCarousel);
        return page;
    }

    private final Runnable mSnap = () -> {
        if (mCarousel == null || mCards.isEmpty() || mCarousel.isPressed()) {
            return;
        }
        int centre = mCarousel.getScrollX() + mCarousel.getWidth() / 2;
        int best = 0;
        int bestDist = Integer.MAX_VALUE;
        for (int i = 0; i < mCards.size(); i++) {
            View c = mCards.get(i);
            int d = Math.abs(c.getLeft() + c.getWidth() / 2 - centre);
            if (d < bestDist) {
                bestDist = d;
                best = i;
            }
        }
        setSelected(best, true);
    };

    private void fillCarousel() {
        if (mStrip == null || mCarousel.getHeight() == 0) {
            return;
        }
        mStrip.removeAllViews();
        mCards.clear();
        int h = mCarousel.getHeight();
        int cardH = (int) (h * 0.86f);
        mCardW = (int) (cardH * 0.66f);
        int side = Math.max(0, mCarousel.getWidth() / 2 - mCardW / 2);
        mStrip.addView(new View(this), new LinearLayout.LayoutParams(side, 1));

        if (mShown.isEmpty()) {
            mStrip.removeAllViews();
            TextView empty = Ui.text(this, emptyMessage(), 16, Ui.TEXT_DIM, false);
            empty.setGravity(Gravity.CENTER);
            empty.setLineSpacing(0, 1.3f);
            mStrip.addView(empty, new LinearLayout.LayoutParams(mCarousel.getWidth(), -1));
            mPosition.setText("");
            return;
        }
        for (int i = 0; i < mShown.size(); i++) {
            View card = buildCard(mShown.get(i), i, cardH);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(mCardW, cardH);
            lp.leftMargin = i == 0 ? 0 : dp(26);
            mStrip.addView(card, lp);
            mCards.add(card);
        }
        mStrip.addView(new View(this), new LinearLayout.LayoutParams(side, 1));
        mSelected = Math.min(mSelected, mShown.size() - 1);
        mStrip.post(() -> setSelected(mSelected, false));
    }

    private String emptyMessage() {
        if (mDb == null) {
            return "Caricamento…";
        }
        if (!mFilter.isEmpty()) {
            return "Nessun gioco corrisponde a \"" + mFilter + "\".";
        }
        StringBuilder s = new StringBuilder("Nessun gioco trovato.\n\nMetti le ROM (zip, nomi di MAME) in:\n");
        for (String key : new String[] { "model2_rom_dir", "model3_rom_dir", "model1_rom_dir" }) {
            s.append(mIni.get(key, "")).append('\n');
        }
        s.append("\nPuoi cambiare le cartelle in Sistema.");
        if (!hasStorageAccess()) {
            s.append("\n\nKeisho non ha ancora l'accesso ai file: concedilo in Sistema.");
        }
        return s.toString();
    }

    private View buildCard(GameDb.Game game, int index, int cardH) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setClipToOutline(true);
        card.setFocusable(true);
        card.setClickable(true);
        card.setPadding(dp(2), dp(2), dp(2), dp(2));

        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable placeholder = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] { 0xFF3A3424, 0xFF201E19 });
        image.setBackground(placeholder);
        card.addView(image, new LinearLayout.LayoutParams(-1, 0, 1));
        mArtwork.request(game.name, (name, bmp) -> {
            if (name.equals(game.name)) {
                image.setImageBitmap(bmp);
            }
        });

        LinearLayout caption = new LinearLayout(this);
        caption.setOrientation(LinearLayout.VERTICAL);
        caption.setBackgroundColor(0xFF15171C);
        caption.setPadding(dp(12), dp(9), dp(12), dp(10));
        TextView title = Ui.text(this, game.title.toUpperCase(Locale.ROOT), 14, Ui.TEXT, true);
        title.setMaxLines(2);
        title.setLetterSpacing(0.03f);
        caption.addView(title);
        TextView sub = Ui.text(this, game.subtitle(), 11, Ui.TEXT_DIM, false);
        sub.setPadding(0, dp(5), 0, 0);
        sub.setTag("sub");
        caption.addView(sub);
        card.addView(caption, new LinearLayout.LayoutParams(-1, -2));

        card.setOnClickListener(v -> {
            if (mSelected == index) {
                playSelected();
            } else {
                setSelected(index, true);
            }
        });
        card.setOnFocusChangeListener((v, has) -> {
            if (has && mSelected != index) {
                setSelected(index, true);
            }
        });
        Ui.enterClicks(card);
        return card;
    }

    private void setSelected(int index, boolean animate) {
        if (mCards.isEmpty()) {
            return;
        }
        index = Math.max(0, Math.min(index, mCards.size() - 1));
        mSelected = index;
        for (int i = 0; i < mCards.size(); i++) {
            View c = mCards.get(i);
            boolean on = i == index;
            c.setBackground(Ui.rounded(0xFF15171C, dp(16), on ? Ui.ACCENT : 0xFF3B404B, dp(on ? 3 : 1.5f)));
            View sub = c.findViewWithTag("sub");
            if (sub instanceof TextView) {
                ((TextView) sub).setTextColor(on ? Ui.ACCENT : Ui.TEXT_DIM);
            }
            float scale = on ? 1f : 0.86f;
            float alpha = on ? 1f : 0.55f;
            if (animate) {
                c.animate().scaleX(scale).scaleY(scale).alpha(alpha).setDuration(160).start();
            } else {
                c.setScaleX(scale);
                c.setScaleY(scale);
                c.setAlpha(alpha);
            }
        }
        View sel = mCards.get(index);
        int target = sel.getLeft() + sel.getWidth() / 2 - mCarousel.getWidth() / 2;
        if (Math.abs(target - mCarousel.getScrollX()) > 2) {
            if (animate) {
                mCarousel.smoothScrollTo(target, 0);
            } else {
                mCarousel.scrollTo(target, 0);
            }
        }
        mPosition.setText((index + 1) + " / " + mCards.size());
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (mTab == 0 && event.getAction() == KeyEvent.ACTION_DOWN && !mCards.isEmpty()
                && getCurrentFocus() != null && mCards.contains(getCurrentFocus())) {
            int code = event.getKeyCode();
            if (code == KeyEvent.KEYCODE_DPAD_LEFT && mSelected > 0) {
                mCards.get(mSelected - 1).requestFocus();
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_RIGHT && mSelected < mCards.size() - 1) {
                mCards.get(mSelected + 1).requestFocus();
                return true;
            }
        }
        if (event.getAction() == KeyEvent.ACTION_UP && event.getKeyCode() == KeyEvent.KEYCODE_BACK
                && mTab != 0) {
            selectTab(0);
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    // ------------------------------------------------------------------
    // Game list
    // ------------------------------------------------------------------

    private List<File> romFolders() {
        List<File> dirs = new ArrayList<>();
        for (String key : new String[] { "model2_rom_dir", "model3_rom_dir", "model1_rom_dir" }) {
            String path = mIni.get(key, "");
            if (!path.isEmpty()) {
                dirs.add(new File(path));
            }
        }
        return dirs;
    }

    private void rescan() {
        List<File> folders = romFolders();
        new Thread(() -> {
            GameDb.Scan scan = mDb.scan(folders);
            mMain.post(() -> {
                String current = mSelected < mShown.size() ? mShown.get(mSelected).name : null;
                mAll.clear();
                mAll.addAll(scan.games);
                mArchives = scan.archives;
                mCounts.setText(mAll.size() + " GIOCHI · " + mArchives + " ROM");
                applyFilter();
                if (current != null) {
                    for (int i = 0; i < mShown.size(); i++) {
                        if (mShown.get(i).name.equals(current)) {
                            mSelected = i;
                        }
                    }
                }
            });
        }, "keisho-scan").start();
    }

    private void applyFilter() {
        mShown.clear();
        for (GameDb.Game g : mAll) {
            if (mFilter.isEmpty() || g.title.toLowerCase(Locale.ROOT).contains(mFilter)
                    || g.name.contains(mFilter)) {
                mShown.add(g);
            }
        }
        mSelected = 0;
        if (mTab == 0) {
            fillCarousel();
        }
    }

    private void playSelected() {
        if (mTab != 0 || mShown.isEmpty()) {
            selectTab(0);
            return;
        }
        final GameDb.Game g = mShown.get(Math.min(mSelected, mShown.size() - 1));
        final File auto = autoStateFile(g.name);
        if (!auto.isFile()) {
            launch(g, null);
            return;
        }
        String when = java.text.DateFormat.getDateTimeInstance(
                java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(new java.util.Date(auto.lastModified()));
        new AlertDialog.Builder(this)
                .setTitle(g.title)
                .setMessage("C'è una partita lasciata a metà (" + when + ").")
                .setPositiveButton("▶  RIPRENDI", (d, w) -> launch(g, auto))
                .setNegativeButton("NUOVA PARTITA", (d, w) -> launch(g, null))
                .setNeutralButton("Annulla", null)
                .show();
    }

    private void launch(GameDb.Game g, File state) {
        Intent intent = new Intent(this, KeishoActivity.class);
        intent.putExtra("ROM", g.archive.getAbsolutePath());
        if (state != null) {
            intent.putExtra(KeishoActivity.EXTRA_STATE, state.getAbsolutePath());
        }
        startActivity(intent);
    }

    /** Where Keisho writes the game's "auto" slot: <saves>/states/<set>.auto.kstate. */
    private File autoStateFile(String set) {
        mIni = new Ini(Setup.iniFile(this));   // the game may have changed nvram_dir
        String saves = mIni.get("nvram_dir", "").trim();
        File savesDir = saves.isEmpty() ? new File(Setup.filesDir(this), "saves") : new File(saves);
        if (!savesDir.isAbsolute()) {
            savesDir = new File(Setup.filesDir(this), saves);
        }
        File f = new File(new File(savesDir, "states"), set + ".auto.kstate");
        if (!f.isFile() && saves.isEmpty()) {
            // Without keisho.ini beside it Keisho uses its XDG data folder under HOME.
            File xdg = new File(Setup.filesDir(this), ".local/share/keisho/saves/states/" + set + ".auto.kstate");
            if (xdg.isFile()) {
                return xdg;
            }
        }
        return f;
    }

    // ------------------------------------------------------------------
    // Settings pages
    // ------------------------------------------------------------------

    private int indexOf(String[] values, String value, int fallback) {
        for (int i = 0; i < values.length; i++) {
            if (values[i].equalsIgnoreCase(value)) {
                return i;
            }
        }
        return fallback;
    }

    private List<View> buildVideoRows() {
        List<View> rows = new ArrayList<>();
        final String[] scales = { "1", "2", "3", "4" };
        rows.add(Ui.row(this, "Risoluzione interna", "Più alta è più nitida, ma più pesante",
                Ui.segmented(this, new String[] { "1x", "2x", "3x", "4x" },
                        indexOf(scales, mIni.get("render_scale", "1"), 0),
                        i -> mIni.set("video", "render_scale", scales[i]))));
        final String[] aspects = { "4:3", "16:9", "stretch" };
        rows.add(Ui.row(this, "Proporzioni", "16:9 allarga il 3D dove il gioco lo permette",
                Ui.segmented(this, new String[] { "4:3", "16:9", "Riempi" },
                        indexOf(aspects, mIni.get("aspect_mode", "4:3"), 0),
                        i -> mIni.set("video", "aspect_mode", aspects[i]))));
        rows.add(Ui.row(this, "Filtro CRT", "Linee e maschera come un monitor arcade",
                new Ui.Toggle(this, mIni.getBool("crt_enabled", false),
                        on -> mIni.setBool("video", "crt_enabled", on))));
        rows.add(Ui.row(this, "V-Sync", "Evita lo strappo dell'immagine",
                new Ui.Toggle(this, mIni.getBool("vsync", false),
                        on -> mIni.setBool("video", "vsync", on))));
        rows.add(Ui.row(this, "Mostra FPS", null,
                new Ui.Toggle(this, mIni.getBool("show_fps", false),
                        on -> mIni.setBool("video", "show_fps", on))));
        final String[] backends = { "opengl", "software" };
        rows.add(Ui.row(this, "Renderer", "Software è più fedele ma molto più lento",
                Ui.segmented(this, new String[] { "OpenGL ES", "Software" },
                        indexOf(backends, mIni.get("graphics_backend", "opengl"), 0),
                        i -> mIni.set("video", "graphics_backend", backends[i]))));
        return rows;
    }

    private List<View> buildInputRows() {
        List<View> rows = new ArrayList<>();
        rows.add(Ui.row(this, "Comandi touch", "Automatico: si nascondono quando colleghi un controller",
                Ui.segmented(this, new String[] { "Automatico", "Sempre", "Mai" },
                        mPrefs.getInt("touch_mode", 0),
                        i -> mPrefs.edit().putInt("touch_mode", i).apply())));
        final int[] alphas = { 35, 55, 75, 100 };
        rows.add(Ui.row(this, "Trasparenza", null,
                Ui.segmented(this, new String[] { "35%", "55%", "75%", "100%" },
                        indexOfInt(alphas, mPrefs.getInt("touch_alpha", 75), 2),
                        i -> mPrefs.edit().putInt("touch_alpha", alphas[i]).apply())));
        final int[] sizes = { 85, 100, 120 };
        rows.add(Ui.row(this, "Dimensione", null,
                Ui.segmented(this, new String[] { "Piccoli", "Medi", "Grandi" },
                        indexOfInt(sizes, mPrefs.getInt("touch_size", 100), 1),
                        i -> mPrefs.edit().putInt("touch_size", sizes[i]).apply())));
        rows.add(Ui.row(this, "Controller", "Il primo controller collegato è il giocatore 1. "
                + "Durante il gioco: Indietro apre il menu di Keisho.", null));
        return rows;
    }

    private static int indexOfInt(int[] values, int value, int fallback) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] == value) {
                return i;
            }
        }
        return fallback;
    }

    private List<View> buildAudioRows() {
        List<View> rows = new ArrayList<>();
        final String[] levels = { "0", "25", "50", "75", "100" };
        rows.add(Ui.row(this, "Volume musica", "Per i giochi con la scheda musicale",
                Ui.segmented(this, new String[] { "0", "25", "50", "75", "100" },
                        indexOf(levels, mIni.get("music_volume", "100"), 4),
                        i -> mIni.set("audio", "music_volume", levels[i]))));
        rows.add(Ui.row(this, "Surround", "Altoparlanti posteriori nei cabinati a quattro casse",
                new Ui.Toggle(this, mIni.getBool("surround", false),
                        on -> mIni.setBool("audio", "surround", on))));
        return rows;
    }

    private List<View> buildSystemRows() {
        List<View> rows = new ArrayList<>();
        if (!hasStorageAccess()) {
            rows.add(Ui.row(this, "Accesso ai file", "Serve per leggere le ROM dalle tue cartelle",
                    Ui.pill(this, "CONCEDI", Ui.ACCENT, 0xFF15171C, v -> requestStorageAccess())));
        }
        String[][] dirs = {
                { "model2_rom_dir", "Cartella Model 2" },
                { "model3_rom_dir", "Cartella Model 3" },
                { "model1_rom_dir", "Cartella Model 1" },
        };
        for (String[] d : dirs) {
            final String key = d[0];
            rows.add(Ui.row(this, d[1], mIni.get(key, "(non impostata)"),
                    Ui.pill(this, "SCEGLI", Ui.CARD_HI, Ui.TEXT, v -> pickFolder(key))));
        }
        rows.add(Ui.row(this, "Aggiornamenti", "Build " + versionCode() + " installata",
                Ui.pill(this, "CONTROLLA", Ui.GREEN, Color.WHITE, v -> new Updater(this).checkAsync(true))));
        rows.add(Ui.row(this, "Licenze e crediti", "Keisho di Daniel Martin (dmanlfc), GPL-3.0",
                Ui.pill(this, "APRI", Ui.ACCENT, 0xFF15171C, v -> showLicenses())));
        return rows;
    }

    private void showLicenses() {
        new AlertDialog.Builder(this)
                .setTitle("Licenze e crediti")
                .setMessage("Keisho — emulatore Sega Model 1, 2 e 3\n"
                        + "© 2025+ Daniel Martin (dmanlfc) e collaboratori\n"
                        + "Licenza GNU GPL 3.0 o successiva, con termini aggiuntivi (vedi LICENSE e NOTICE).\n"
                        + "Codice: github.com/Keisho-Emu/Keisho\n\n"
                        + "Port Android: github.com/carlovacco8/keisho (branch android), stessa licenza.\n\n"
                        + "Contiene SDL3 (zlib), Dear ImGui (MIT), Musashi (MIT), ymfm (BSD-3), "
                        + "miniz (MIT), pugixml (MIT), LZMA SDK (pubblico dominio), HarfBuzz (MIT), "
                        + "SheenBidi (Apache-2.0), font Noto (OFL) e codice derivato da MAME (BSD-3).\n\n"
                        + "Immagini dei giochi: ArcadeItalia ArcadeDB.\n"
                        + "Nessuna ROM è inclusa in questa app.")
                .setPositiveButton("OK", null)
                .show();
    }

    // ------------------------------------------------------------------
    // ROM folders
    // ------------------------------------------------------------------

    private void pickFolder(String key) {
        mPickingKey = key;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        String current = mIni.get(key, "");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && current.startsWith(
                Environment.getExternalStorageDirectory().getAbsolutePath())) {
            String rel = current.substring(Environment.getExternalStorageDirectory().getAbsolutePath().length());
            if (rel.startsWith("/")) {
                rel = rel.substring(1);
            }
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, DocumentsContract.buildDocumentUri(
                    "com.android.externalstorage.documents", "primary:" + rel));
        }
        if (intent.resolveActivity(getPackageManager()) != null) {
            try {
                startActivityForResult(intent, REQUEST_FOLDER);
                return;
            } catch (Exception ignored) {
                // Fall through to typing the path.
            }
        }
        typeFolder(key, current);
    }

    /** TV boxes without a folder chooser: type the path instead. */
    private void typeFolder(String key, String current) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(current.isEmpty() ? Setup.findRomsRoot().getAbsolutePath() : current);
        new AlertDialog.Builder(this)
                .setTitle("Percorso della cartella")
                .setMessage("Esempio: /storage/emulated/0/ROMs/model2")
                .setView(input)
                .setPositiveButton("OK", (d, w) -> setFolder(key, input.getText().toString().trim()))
                .setNegativeButton("Annulla", null)
                .show();
    }

    private void setFolder(String key, String path) {
        if (path.isEmpty()) {
            return;
        }
        mIni.set("paths", key, path);
        if (!new File(path).isDirectory()) {
            Toast.makeText(this, "Attenzione: la cartella non esiste o non è leggibile", Toast.LENGTH_LONG).show();
        }
        selectTab(mTab);
        rescan();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_FOLDER || mPickingKey == null) {
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }
        if (resultCode == RESULT_OK && data != null && data.getData() != null) {
            String path = pathFromTreeUri(data.getData());
            if (path == null) {
                Toast.makeText(this, "Scegli una cartella nella memoria del dispositivo", Toast.LENGTH_LONG).show();
            } else {
                setFolder(mPickingKey, path);
            }
        }
        mPickingKey = null;
    }

    static String pathFromTreeUri(Uri tree) {
        try {
            if (!"com.android.externalstorage.documents".equals(tree.getAuthority())) {
                return null;
            }
            String id = DocumentsContract.getTreeDocumentId(tree);
            int colon = id.indexOf(':');
            if (colon < 0) {
                return null;
            }
            String volume = id.substring(0, colon);
            String rest = id.substring(colon + 1);
            File base = "primary".equalsIgnoreCase(volume)
                    ? Environment.getExternalStorageDirectory()
                    : new File("/storage/" + volume);
            return rest.isEmpty() ? base.getAbsolutePath() : new File(base, rest).getAbsolutePath();
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Permissions, version
    // ------------------------------------------------------------------

    private boolean hasStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestStorageAccess() {
        if (hasStorageAccess()) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Toast.makeText(this, "Consenti a Keisho l'accesso a tutti i file, poi torna indietro",
                    Toast.LENGTH_LONG).show();
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception first) {
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                } catch (Exception ignored) {
                    // Nothing more we can do.
                }
            }
        } else {
            requestPermissions(new String[] { Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE }, 1);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (mDb != null) {
            rescan();
        }
    }

    @SuppressWarnings("deprecation")
    private long versionCode() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? pi.getLongVersionCode() : pi.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }
}
