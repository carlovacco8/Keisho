// Keisho — Android port: shared start-up chores (both processes).
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.carlovacco8.keisho;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

final class Setup {
    private static final String TAG = "Keisho";

    private Setup() {}

    /** Android/data/<package>/files: keisho.ini, saves, logs, artwork. */
    static File filesDir(Context context) {
        File dir = context.getExternalFilesDir(null);
        return dir != null ? dir : context.getFilesDir();
    }

    static File iniFile(Context context) {
        return new File(filesDir(context), "keisho.ini");
    }

    /** Copies games.xml and the translations out of the APK; writes a first ini. */
    static void prepare(Context context) {
        File dir = filesDir(context);
        try {
            AssetManager assets = context.getAssets();
            copyAsset(assets, "games.xml", new File(dir, "games.xml"));
            copyAssetDir(assets, "lang", new File(dir, "lang"));
            copyCovers(assets, new File(new File(dir, "artwork"), "covers"));
            writeDefaultConfig(context);
        } catch (IOException e) {
            Log.e(TAG, "could not prepare data files", e);
        }
    }

    private static void copyAssetDir(AssetManager assets, String dir, File target) throws IOException {
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

    /** The bundled game covers, into Keisho's artwork folder; only those missing or changed. */
    private static void copyCovers(AssetManager assets, File target) throws IOException {
        String[] names = assets.list("covers");
        if (names == null || names.length == 0) {
            return;
        }
        if (!target.isDirectory() && !target.mkdirs()) {
            throw new IOException("mkdir " + target);
        }
        for (String name : names) {
            File out = new File(target, name);
            if (out.isFile()) {
                try (android.content.res.AssetFileDescriptor fd = assets.openFd("covers/" + name)) {
                    if (fd.getLength() == out.length()) {
                        continue;
                    }
                } catch (IOException compressed) {
                    // Stored compressed: no length to compare, so copy it again.
                }
            }
            copyAsset(assets, "covers/" + name, out);
        }
    }

    private static void copyAsset(AssetManager assets, String name, File target) throws IOException {
        try (InputStream in = assets.open(name); OutputStream out = new FileOutputStream(target)) {
            copy(in, out);
        }
    }

    static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int n;
        while ((n = in.read(buffer)) > 0) {
            out.write(buffer, 0, n);
        }
    }

    private static void writeDefaultConfig(Context context) throws IOException {
        File ini = iniFile(context);
        if (ini.exists()) {
            return;
        }
        File roms = findRomsRoot();
        String text = "# M2 NEXUS settings (Android defaults; edit freely)\n"
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
    static File findRomsRoot() {
        File storage = Environment.getExternalStorageDirectory();
        for (String name : new String[] { "ROMs", "Roms", "roms" }) {
            File candidate = new File(storage, name);
            if (candidate.isDirectory()) {
                return candidate;
            }
        }
        return new File(storage, "ROMs");
    }
}
