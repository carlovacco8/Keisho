// Keisho — Android port: the activity that runs a game.
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Keisho itself is the desktop program built as libmain.so; SDL's activity
// runs its main(). This class prepares the ground and adds the Android parts:
//   * games.xml, translations and keisho.ini in the app's files folder;
//   * the game to run, from the launcher, ES-DE or a file manager;
//   * on-screen touch controls on phones (TouchOverlay + a virtual pad);
//   * Android's folder chooser for Keisho's own "Browse..." buttons;
//   * android.log, a life-cycle diagnostic log.
// It runs in its own process (":game"), ended when the game closes, so every
// game starts fresh while the launcher stays where it was.

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

    // Implemented in libmain.so (src/osd/android_glue.cpp).
    private static native void nativeFolderPicked(String path);
    private static native void nativeLog(String text);
    private static native boolean nativeTouchAttach();
    private static native void nativeTouchDetach();
    private static native void nativeTouchAxis(int axis, float value);
    private static native void nativeTouchButton(int button, boolean down);

    private final Handler mMain = new Handler(Looper.getMainLooper());
    private File mFilesDir;
    private String mRom;
    private TouchOverlay mOverlay;
    private int mTouchMode;   // 0 automatic, 1 always, 2 never
    private boolean mPadAttached;

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
        SharedPreferences prefs = getSharedPreferences(LauncherActivity.PREFS, Context.MODE_MULTI_PROCESS);
        mTouchMode = prefs.getInt("touch_mode", 0);
        if (mTouchMode == 2 || mLayout == null) {
            return;
        }
        GameDb.Game game = null;
        if (mRom != null) {
            String n = new File(mRom).getName();
            int dot = n.lastIndexOf('.');
            game = GameDb.load(this).get(dot > 0 ? n.substring(0, dot) : n);
        }
        mOverlay = new TouchOverlay(this, game, prefs.getInt("touch_size", 100) / 100f,
                prefs.getInt("touch_alpha", 75));
        mLayout.addView(mOverlay, new ViewGroup.LayoutParams(-1, -1));
        InputManager im = (InputManager) getSystemService(Context.INPUT_SERVICE);
        im.registerInputDeviceListener(this, mMain);
        updateTouchVisibility();
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
        boolean show = mTouchMode == 1 || !hasRealController();
        mOverlay.setVisibility(show ? android.view.View.VISIBLE : android.view.View.GONE);
        if (show) {
            attachPadWhenReady();
        } else {
            mOverlay.releaseAll();
            if (mPadAttached) {
                try {
                    nativeTouchDetach();
                } catch (UnsatisfiedLinkError ignored) {
                    // Not loaded.
                }
                mPadAttached = false;
            }
        }
    }

    /** Keisho opens SDL's gamepad support a moment after start; retry until then. */
    private void attachPadWhenReady() {
        if (mPadAttached) {
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
            path = LauncherActivity.pathFromTreeUri(data.getData());
            if (path == null) {
                Toast.makeText(this, "Cartella non supportata: scegline una nella memoria del dispositivo",
                        Toast.LENGTH_LONG).show();
                path = "";
            }
        }
        lifecycle("folder chosen: " + (path.isEmpty() ? "(none)" : path));
        nativeFolderPicked(path);
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
        // This process only ever runs one game: end it with the game.
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
