// Keisho — Android port: self-update from the fork's "android-latest" release.
// SPDX-License-Identifier: GPL-3.0-or-later
//
// At start-up the app fetches version.json from the release. When it names a
// newer build, a dialog lists what changed; "Aggiorna" downloads the APK and
// opens it in Android's own installer screen (through ApkProvider), which asks
// for confirmation and replaces the app (both builds are signed with the same
// key). A copy is left in Download as a fallback.

package io.github.carlovacco8.keisho;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

final class Updater {
    private static final String TAG = "KeishoUpdate";
    static final String VERSION_URL =
            "https://github.com/carlovacco8/keisho/releases/download/android-latest/version.json";

    private final Activity mActivity;
    private final Handler mMain = new Handler(Looper.getMainLooper());

    Updater(Activity activity) {
        mActivity = activity;
    }

    /**
     * Checks in the background; shows the dialog only if a newer build exists.
     * manual: also say so when there is nothing new, or the check failed.
     */
    void checkAsync(boolean manual) {
        new Thread(() -> {
            try {
                JSONObject info = new JSONObject(new String(
                        fetch(VERSION_URL + "?t=" + System.currentTimeMillis()),
                        StandardCharsets.UTF_8));
                long remote = info.optLong("versionCode", 0);
                long local = installedVersionCode();
                Log.i(TAG, "installed " + local + ", available " + remote);
                if (remote > local) {
                    mMain.post(() -> offer(info));
                } else if (manual) {
                    mMain.post(() -> Toast.makeText(mActivity,
                            "Hai già l'ultima versione (build " + local + ")", Toast.LENGTH_LONG).show());
                }
            } catch (Exception e) {
                // Offline, rate-limited or no release yet: just play.
                Log.i(TAG, "update check skipped: " + e);
                if (manual) {
                    mMain.post(() -> Toast.makeText(mActivity,
                            "Controllo non riuscito: sei connesso a internet?", Toast.LENGTH_LONG).show());
                }
            }
        }, "keisho-update-check").start();
    }

    private long installedVersionCode() throws Exception {
        PackageInfo pi = mActivity.getPackageManager()
                .getPackageInfo(mActivity.getPackageName(), 0);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return pi.getLongVersionCode();
        }
        return pi.versionCode;
    }

    private void offer(JSONObject info) {
        if (mActivity.isFinishing()) {
            return;
        }
        String name = info.optString("versionName", "");
        String date = info.optString("date", "");
        String changes = info.optString("changes", "");
        String message = "È disponibile una nuova versione di TRI-ARCADE"
                + (name.isEmpty() ? "" : " (" + name + (date.isEmpty() ? "" : ", " + date) + ")")
                + ".\n\nCosa cambia:\n" + changes;
        new AlertDialog.Builder(mActivity)
                .setTitle("Aggiornamento disponibile")
                .setMessage(message)
                .setPositiveButton("Aggiorna", (d, w) -> download(info.optString("apk")))
                .setNegativeButton("Più tardi", null)
                .show();
    }

    // ------------------------------------------------------------------

    @SuppressWarnings("deprecation")
    private void download(String apkUrl) {
        if (apkUrl == null || apkUrl.isEmpty()) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !mActivity.getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(mActivity,
                    "Consenti a TRI-ARCADE di installare app, poi scegli di nuovo Aggiorna",
                    Toast.LENGTH_LONG).show();
            try {
                mActivity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + mActivity.getPackageName())));
            } catch (Exception e) {
                Log.w(TAG, "no unknown-sources settings screen", e);
            }
            return;
        }

        ProgressDialog progress = new ProgressDialog(mActivity);
        progress.setTitle("Download aggiornamento");
        progress.setMessage("Scarico la nuova versione…");
        progress.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        progress.setIndeterminate(true);
        progress.setCancelable(false);
        progress.show();

        new Thread(() -> {
            File apk = new File(mActivity.getCacheDir(), ApkProvider.FILE_NAME);
            try {
                HttpURLConnection c = open(apkUrl);
                long total = c.getContentLengthLong();
                try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(apk)) {
                    byte[] buffer = new byte[64 * 1024];
                    long done = 0;
                    int n;
                    int lastPercent = -1;
                    while ((n = in.read(buffer)) > 0) {
                        out.write(buffer, 0, n);
                        done += n;
                        if (total > 0) {
                            int percent = (int) (done * 100 / total);
                            if (percent != lastPercent) {
                                lastPercent = percent;
                                mMain.post(() -> {
                                    progress.setIndeterminate(false);
                                    progress.setMax(100);
                                    progress.setProgress(percent);
                                });
                            }
                        }
                    }
                } finally {
                    c.disconnect();
                }
                mMain.post(() -> {
                    progress.dismiss();
                    install(apk);
                });
            } catch (Exception e) {
                Log.e(TAG, "download failed", e);
                mMain.post(() -> {
                    progress.dismiss();
                    Toast.makeText(mActivity, "Download non riuscito: " + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                });
            }
        }, "keisho-update-download").start();
    }

    /**
     * Hands the APK to Android's own installer screen. It installs in its own
     * process, so the update finishes even though it has to close Keisho (an
     * install session committed from Keisho itself could stop with it on some
     * phones). A copy also goes to Download, to install by hand if need be.
     */
    private void install(File apk) {
        copyToDownloads(apk);
        try {
            Intent view = new Intent(Intent.ACTION_VIEW);
            view.setDataAndType(ApkProvider.uri(mActivity), "application/vnd.android.package-archive");
            view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            mActivity.startActivity(view);
        } catch (RuntimeException e) {
            Log.e(TAG, "no installer for the update", e);
            Toast.makeText(mActivity, "Apri Download/TRI-ARCADE.apk per installare l'aggiornamento",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void copyToDownloads(File apk) {
        try {
            File dir = android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS);
            java.nio.file.Files.copy(apk.toPath(), new File(dir, "TRI-ARCADE.apk").toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Throwable e) {
            Log.w(TAG, "could not copy the update to Download: " + e);
        }
    }

    // ------------------------------------------------------------------

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(20000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "TRI-ARCADE");
        int code = c.getResponseCode();
        if (code != HttpURLConnection.HTTP_OK) {
            c.disconnect();
            throw new IOException("HTTP " + code);
        }
        return c;
    }

    private static byte[] fetch(String url) throws IOException {
        HttpURLConnection c = open(url);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[16 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) {
                bytes.write(buffer, 0, n);
            }
            return bytes.toByteArray();
        } finally {
            c.disconnect();
        }
    }
}
