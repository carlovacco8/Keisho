// Keisho — Android port: the activity that hosts the emulator.
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Keisho itself is the unmodified desktop program built as libmain.so; SDL's
// activity runs its main(). This class only prepares the ground:
//   * copies games.xml and the translations out of the APK, and writes a
//     first keisho.ini pointing at ES-DE style ROM folders;
//   * asks for "all files" access so ROMs can be read by plain path;
//   * turns a launch Intent (ES-DE, file manager) into Keisho's command line.

package io.github.carlovacco8.keisho;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import org.libsdl.app.SDLActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class KeishoActivity extends SDLActivity {
    private static final String TAG = "Keisho";

    private File mFilesDir;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        mFilesDir = getExternalFilesDir(null);
        if (mFilesDir == null) {
            mFilesDir = getFilesDir();
        }
        try {
            copyAssets();
            writeDefaultConfig();
        } catch (IOException e) {
            Log.e(TAG, "could not prepare data files", e);
        }
        super.onCreate(savedInstanceState);
        requestStorageAccess();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Keisho keeps program-wide state in statics; start the next game in a
        // fresh process rather than reusing the old one.
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

        String rom = resolveRom(getIntent());
        if (rom != null) {
            Log.i(TAG, "launching " + rom);
            args.add(rom);
        }
        return args.toArray(new String[0]);
    }

    // ------------------------------------------------------------------
    // Data files
    // ------------------------------------------------------------------

    private void copyAssets() throws IOException {
        AssetManager assets = getAssets();
        copyAsset(assets, "games.xml", new File(mFilesDir, "games.xml"));
        copyAssetDir(assets, "lang", new File(mFilesDir, "lang"));
    }

    private void copyAssetDir(AssetManager assets, String dir, File target) throws IOException {
        String[] entries = assets.list(dir);
        if (entries == null || entries.length == 0) {
            return;
        }
        if (!target.isDirectory() && !target.mkdirs()) {
            throw new IOException("mkdir " + target);
        }
        for (String name : entries) {
            String child = dir + "/" + name;
            String[] sub = assets.list(child);
            if (sub != null && sub.length > 0) {
                copyAssetDir(assets, child, new File(target, name));
            } else {
                copyAsset(assets, child, new File(target, name));
            }
        }
    }

    private static void copyAsset(AssetManager assets, String name, File target) throws IOException {
        try (InputStream in = assets.open(name); OutputStream out = new FileOutputStream(target)) {
            copy(in, out);
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int n;
        while ((n = in.read(buffer)) > 0) {
            out.write(buffer, 0, n);
        }
    }

    /** Writes a starting keisho.ini once; Keisho then owns and updates it. */
    private void writeDefaultConfig() throws IOException {
        File ini = new File(mFilesDir, "keisho.ini");
        if (ini.exists()) {
            return;
        }
        File roms = findRomsRoot();
        String text = "# Keisho settings (Android defaults; edit freely)\n"
                + "\n[video]\n"
                + "fullscreen = true\n"
                + "\n[paths]\n"
                + "model1_rom_dir = " + new File(roms, "model1").getAbsolutePath() + "\n"
                + "model2_rom_dir = " + new File(roms, "model2").getAbsolutePath() + "\n"
                + "model3_rom_dir = " + new File(roms, "model3").getAbsolutePath() + "\n";
        try (OutputStream out = new FileOutputStream(ini)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** ES-DE's ROM folder in internal storage, whatever its capitalisation. */
    private static File findRomsRoot() {
        File storage = Environment.getExternalStorageDirectory();
        for (String name : new String[] { "ROMs", "Roms", "roms" }) {
            File candidate = new File(storage, name);
            if (candidate.isDirectory()) {
                return candidate;
            }
        }
        return new File(storage, "ROMs");
    }

    // ------------------------------------------------------------------
    // Storage permission
    // ------------------------------------------------------------------

    private void requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                return;
            }
            Toast.makeText(this,
                    "Keisho: consenti l'accesso a tutti i file, poi riapri l'app",
                    Toast.LENGTH_LONG).show();
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } catch (Exception first) {
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                } catch (Exception second) {
                    Log.w(TAG, "no all-files-access settings screen", second);
                }
            }
        } else if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {
                    Manifest.permission.READ_EXTERNAL_STORAGE,
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
            copy(in, out);
            return target.getAbsolutePath();
        } catch (IOException e) {
            Log.e(TAG, "could not copy " + uri, e);
            return null;
        }
    }
}
