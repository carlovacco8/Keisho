// Keisho — Android port: the activity that runs a game.
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Keisho itself is the desktop program built as libmain.so; SDL's activity
// runs its main(). This class prepares the ground and adds the Android parts:
//   * games.xml, translations and keisho.ini in the app's files folder;
//   * the game to run, from ES-DE or a file manager; started from its icon
//     it shows Keisho's own game list (as M2EA does: one native activity,
//     the launcher drawn by the emulator itself);
//   * on-screen touch controls on phones (TouchOverlay + a virtual pad),
//     rebuilt for each game Keisho reports through onGameChanged();
//   * the self-update check;
//   * Android's folder chooser for Keisho's own "Browse..." buttons;
//   * android.log, a life-cycle diagnostic log.

package io.github.carlovacco8.keisho;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.hardware.input.InputManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.util.Log;
import android.view.InputDevice;
import android.view.ViewGroup;
import android.widget.Toast;

import org.libsdl.app.SDLActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

public class KeishoActivity extends SDLActivity implements InputManager.InputDeviceListener {
    private static final String TAG = "Keisho";
    private static final int REQUEST_FOLDER = 4711;
    /** Intent extra: a save state to resume from (the launcher's "Riprendi"). */
    static final String EXTRA_STATE = "STATE";

    // Implemented in libmain.so (src/osd/android_glue.cpp).
    private static native void nativeFolderPicked(String path);
    private static native void nativeLog(String text);
    private static native boolean nativeTouchAttach();
    private static native void nativeTouchDetach();
    private static native void nativeTouchAxis(int axis, float value);
    private static native void nativeTouchButton(int button, boolean down);
    private static native void nativeSetSlot(int slot);

    private final Handler mMain = new Handler(Looper.getMainLooper());
    private File mFilesDir;
    private String mRom;
    private TouchOverlay mOverlay;
    static final String PREFS = "keisho";
    private int mTouchMode;   // 0 automatic, 1 always, 2 never
    private boolean mTouchEnabled;
    private GameDb mGameDb;
    private String mCurrentSet = "";
    private boolean mPadAttached;
    private boolean mMenuOpen;   // Keisho's settings are open: the controls step aside

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Setup.prepare(this);
        mFilesDir = Setup.filesDir(this);
        mRom = resolveRom(getIntent());
        super.onCreate(savedInstanceState);
        if (!hasStorageAccess()) {
            requestStorageAccess();
        }
        setUpTouchControls();
        KeishoApp.showLastCrash(this);
        if (mRom == null) {
            new Updater(this).checkAsync(false);
        }
    }

    // ------------------------------------------------------------------
    // Touch controls
    // ------------------------------------------------------------------

    static void touchAxis(int axis, float value) {
        try {
            nativeTouchAxis(axis, value);
        } catch (UnsatisfiedLinkError ignored) {
            // Native side not loaded (yet).
        }
    }

    /** The save slot SAVE and LOAD use (the SLOT button). */
    static void setSlot(int slot) {
        try {
            nativeSetSlot(slot);
        } catch (UnsatisfiedLinkError ignored) {
            // Native side not loaded (yet).
        }
    }

    static void touchButton(int button, boolean down) {
        try {
            nativeTouchButton(button, down);
        } catch (UnsatisfiedLinkError ignored) {
            // Native side not loaded (yet).
        }
    }

    private void setUpTouchControls() {
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)) {
            return;   // TVs: remote and pads only
        }
        SharedPreferences prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        mTouchMode = prefs.getInt("touch_mode", 0);
        if (mTouchMode == 2 || mLayout == null) {
            return;
        }
        mTouchEnabled = true;
        InputManager im = (InputManager) getSystemService(Context.INPUT_SERVICE);
        im.registerInputDeviceListener(this, mMain);
    }

    /**
     * Called by Keisho (SDL thread) when a game starts or the game list comes
     * back (""): the on-screen controls are rebuilt for that game, and taken
     * away over the list, which is used by touching it directly.
     */
    public void onGameChanged(final String set) {
        runOnUiThread(() -> applyGame(set == null ? "" : set));
    }

    /**
     * Called by Keisho (SDL thread) when its settings open or close: the
     * on-screen controls hide while they are open, so the settings can be
     * touched freely (as on M2EA, where the game waits paused beside them).
     */
    public void onMenuChanged(final boolean open) {
        runOnUiThread(() -> {
            mMenuOpen = open;
            updateTouchVisibility();
        });
    }

    private void applyGame(String set) {
        if (!mTouchEnabled || set.equals(mCurrentSet) && (mOverlay != null || set.isEmpty())) {
            mCurrentSet = set;
            return;
        }
        mCurrentSet = set;
        if (mOverlay != null) {
            mOverlay.releaseAll();
            mLayout.removeView(mOverlay);
            mOverlay = null;
        }
        if (set.isEmpty()) {
            detachPad();
            return;
        }
        if (mGameDb == null) {
            mGameDb = GameDb.load(this);
        }
        SharedPreferences prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        mOverlay = new TouchOverlay(this, mGameDb.get(set), prefs.getInt("touch_size", 100) / 100f,
                prefs.getInt("touch_alpha", 75));
        mLayout.addView(mOverlay, new ViewGroup.LayoutParams(-1, -1));
        updateTouchVisibility();
    }

    private void detachPad() {
        if (mPadAttached) {
            try {
                nativeTouchDetach();
            } catch (UnsatisfiedLinkError ignored) {
                // Not loaded.
            }
            mPadAttached = false;
        }
    }

    private static boolean hasRealController() {
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice d = InputDevice.getDevice(id);
            if (d == null || d.isVirtual()) {
                continue;
            }
            int s = d.getSources();
            if ((s & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                    || (s & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) {
                return true;
            }
        }
        return false;
    }

    private void updateTouchVisibility() {
        if (mOverlay == null) {
            return;
        }
        boolean wanted = mTouchMode == 1 || !hasRealController();
        if (!wanted) {
            mOverlay.setVisibility(android.view.View.GONE);
            mOverlay.releaseAll();
            detachPad();
            return;
        }
        attachPadWhenReady();
        // Over the settings the controls are only in the way: hidden, but the
        // virtual pad stays, so nothing is re-detected when they come back.
        if (mMenuOpen) {
            mOverlay.releaseAll();
            mOverlay.setVisibility(android.view.View.GONE);
        } else {
            mOverlay.setVisibility(android.view.View.VISIBLE);
        }
    }

    /** Keisho opens SDL's gamepad support a moment after start; retry until then. */
    private void attachPadWhenReady() {
        if (mPadAttached || mOverlay == null) {
            return;
        }
        boolean ok;
        try {
            ok = nativeTouchAttach();
        } catch (UnsatisfiedLinkError e) {
            ok = false;
        }
        if (ok) {
            mPadAttached = true;
        } else if (!isFinishing()) {
            mMain.postDelayed(this::attachPadWhenReady, 400);
        }
    }

    @Override
    public void onInputDeviceAdded(int deviceId) {
        updateTouchVisibility();
    }

    @Override
    public void onInputDeviceRemoved(int deviceId) {
        updateTouchVisibility();
    }

    @Override
    public void onInputDeviceChanged(int deviceId) {
        // Nothing to do.
    }

    // ------------------------------------------------------------------
    // Life-cycle log (android.log, beside keisho.log)
    // ------------------------------------------------------------------

    private static void lifecycle(String text) {
        Log.i(TAG, text);
        try {
            nativeLog(text);
        } catch (UnsatisfiedLinkError e) {
            // libmain.so not loaded yet (very early in onCreate).
        }
    }

    @Override
    protected void onPause() {
        lifecycle("onPause");
        super.onPause();
        exportLogs();
    }

    /**
     * Copies keisho.log and android.log to Keisho/log in the device's own
     * storage, where a file manager can open them (the app's folder under
     * Android/data cannot be browsed on recent Android). Done whenever the
     * app leaves the screen, so the copy covers the session just played.
     */
    private void exportLogs() {
        final File from = Setup.filesDir(this);
        new Thread(() -> {
            try {
                File to = new File(Environment.getExternalStorageDirectory(), "Keisho/log");
                if (!to.isDirectory() && !to.mkdirs()) {
                    return;
                }
                for (String name : new String[] { "keisho.log", "android.log" }) {
                    File src = new File(from, name);
                    if (src.isFile()) {
                        java.nio.file.Files.copy(src.toPath(), new File(to, name).toPath(),
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            } catch (Throwable e) {
                Log.w(TAG, "could not copy the logs: " + e);
            }
        }, "log-export").start();
    }

    @Override
    protected void onResume() {
        lifecycle("onResume");
        super.onResume();
    }

    @Override
    protected void onStop() {
        lifecycle("onStop");
        super.onStop();
    }

    @Override
    protected void onStart() {
        lifecycle("onStart");
        super.onStart();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        lifecycle("onWindowFocusChanged " + hasFocus);
        super.onWindowFocusChanged(hasFocus);
    }

    @Override
    public void onTrimMemory(int level) {
        lifecycle("onTrimMemory " + level);
        super.onTrimMemory(level);
    }

    // ------------------------------------------------------------------
    // System folder chooser for the overlay's "Browse..." buttons
    // ------------------------------------------------------------------

    /** Called from native code (any thread). False when no chooser exists. */
    public boolean openFolderPicker(String initial) {
        final Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && initial != null) {
            Uri start = documentUriForPath(initial);
            if (start != null) {
                intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, start);
            }
        }
        if (intent.resolveActivity(getPackageManager()) == null) {
            return false;
        }
        runOnUiThread(() -> {
            try {
                startActivityForResult(intent, REQUEST_FOLDER);
            } catch (Exception e) {
                Log.w(TAG, "folder chooser failed", e);
                nativeFolderPicked(null);
            }
        });
        return true;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_FOLDER) {
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }
        String path = "";
        if (resultCode == RESULT_OK && data != null && data.getData() != null) {
            path = pathFromTreeUri(data.getData());
            if (path == null) {
                Toast.makeText(this, "Cartella non supportata: scegline una nella memoria del dispositivo",
                        Toast.LENGTH_LONG).show();
                path = "";
            }
        }
        lifecycle("folder chosen: " + (path.isEmpty() ? "(none)" : path));
        nativeFolderPicked(path);
    }

    /** A folder chosen with Android's chooser, as a file path (internal storage or an SD card). */
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

    private static Uri documentUriForPath(String path) {
        String root = Environment.getExternalStorageDirectory().getAbsolutePath();
        if (!path.startsWith(root)) {
            return null;
        }
        String rel = path.substring(root.length());
        if (rel.startsWith("/")) {
            rel = rel.substring(1);
        }
        return DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:" + rel);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Leaving Keisho ends the process, so the next start is a clean one.
        if (isFinishing()) {
            System.exit(0);
        }
    }

    @Override
    protected String[] getLibraries() {
        return new String[] { "SDL3", "main" };
    }

    @Override
    protected String[] getArguments() {
        List<String> args = new ArrayList<>();
        args.add("--log-file");
        args.add(new File(mFilesDir, "keisho.log").getAbsolutePath());
        if (mRom != null) {
            Log.i(TAG, "launching " + mRom);
            String state = getIntent() != null ? getIntent().getStringExtra(EXTRA_STATE) : null;
            if (state != null && new File(state).isFile()) {
                Log.i(TAG, "resuming from " + state);
                args.add("--load-state");
                args.add(state);
            }
            args.add(mRom);
        }
        return args.toArray(new String[0]);
    }

    // ------------------------------------------------------------------
    // Storage permission (when started straight from ES-DE)
    // ------------------------------------------------------------------

    private boolean hasStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Toast.makeText(this, "Keisho: consenti l'accesso a tutti i file, poi riapri il gioco",
                    Toast.LENGTH_LONG).show();
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception first) {
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                } catch (Exception ignored) {
                    Log.w(TAG, "no all-files-access settings screen");
                }
            }
        } else {
            requestPermissions(new String[] { Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE }, 1);
        }
    }

    // ------------------------------------------------------------------
    // Launch intent -> ROM path
    // ------------------------------------------------------------------

    /**
     * ES-DE can pass the game as a path extra (%EXTRA_ROM%=%ROM%) or as a
     * content Uri (%DATA%=%ROMPROVIDER%). A path is best: Keisho then also
     * finds parent sets in the same folder.
     */
    private String resolveRom(Intent intent) {
        if (intent == null) {
            return null;
        }
        for (String key : new String[] { "ROM", "rom", "path" }) {
            String value = intent.getStringExtra(key);
            if (value != null && !value.isEmpty()) {
                return value;
            }
        }
        Uri uri = intent.getData();
        if (uri == null) {
            return null;
        }
        if (ContentResolver.SCHEME_FILE.equals(uri.getScheme())) {
            return uri.getPath();
        }
        if (ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) {
            String path = pathFromDocumentUri(uri);
            if (path != null && new File(path).canRead()) {
                return path;
            }
            return copyToCache(uri);
        }
        return null;
    }

    private static String pathFromDocumentUri(Uri uri) {
        try {
            if (!"com.android.externalstorage.documents".equals(uri.getAuthority())) {
                return null;
            }
            String id = DocumentsContract.getDocumentId(uri);
            int colon = id.indexOf(':');
            if (colon < 0) {
                return null;
            }
            String volume = id.substring(0, colon);
            String rest = id.substring(colon + 1);
            if ("primary".equalsIgnoreCase(volume)) {
                return new File(Environment.getExternalStorageDirectory(), rest).getAbsolutePath();
            }
            return "/storage/" + volume + "/" + rest;
        } catch (Exception e) {
            return null;
        }
    }

    /** Last resort for Uris with no file path: copy the archive locally. */
    private String copyToCache(Uri uri) {
        String name = "game.zip";
        try (Cursor cursor = getContentResolver().query(uri,
                new String[] { OpenableColumns.DISPLAY_NAME }, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                name = new File(cursor.getString(0)).getName();
            }
        } catch (Exception e) {
            Log.w(TAG, "no display name for " + uri, e);
        }
        File dir = new File(getCacheDir(), "roms");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            return null;
        }
        File target = new File(dir, name);
        try (InputStream in = getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(target)) {
            if (in == null) {
                return null;
            }
            Setup.copy(in, out);
            return target.getAbsolutePath();
        } catch (IOException e) {
            Log.e(TAG, "could not copy " + uri, e);
            return null;
        }
    }
}
