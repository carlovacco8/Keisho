// Keisho — Android port: crash report.
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Any uncaught Java exception, in the launcher or in the game process, is
// written to files/last_crash.txt before the app closes. On the next start
// that report is shown, with how Android says the app last ended (native
// crashes included) and the end of keisho.log / android.log, so a crash on a
// phone or a TV can be read and photographed without a PC.

package io.github.carlovacco8.keisho;

import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.Application;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

public class KeishoApp extends Application {
    static final String CRASH_FILE = "last_crash.txt";

    @Override
    public void onCreate() {
        super.onCreate();
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                StringWriter text = new StringWriter();
                PrintWriter out = new PrintWriter(text);
                out.println("Keisho build " + versionName());
                out.println("Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + "), "
                        + Build.MANUFACTURER + " " + Build.MODEL);
                out.println("Thread: " + thread.getName());
                out.println();
                error.printStackTrace(out);
                out.flush();
                try (FileOutputStream f = new FileOutputStream(new File(getFilesDir(), CRASH_FILE))) {
                    f.write(text.toString().getBytes(StandardCharsets.UTF_8));
                }
            } catch (Throwable ignored) {
                // Nothing more can be done here.
            }
            if (previous != null) {
                previous.uncaughtException(thread, error);
            } else {
                android.os.Process.killProcess(android.os.Process.myPid());
                System.exit(10);
            }
        });
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    /** Shows the report of the last abnormal exit, if there is one. */
    static void showLastCrash(android.app.Activity activity) {
        String text = lastCrashReport(activity);
        if (text == null) {
            return;
        }
        new AlertDialog.Builder(activity)
                .setTitle("Keisho si è chiuso per un errore: fai uno screenshot")
                .setMessage(text)
                .setPositiveButton("OK", null)
                .show();
    }

    private static String lastCrashReport(Context context) {
        StringBuilder report = new StringBuilder();
        File crash = new File(context.getFilesDir(), CRASH_FILE);
        if (crash.isFile()) {
            report.append(tail(crash, 120)).append('\n');
            crash.delete();
        }
        boolean bad = report.length() > 0;
        SharedPreferences seen = context.getSharedPreferences("crash_report", MODE_PRIVATE);
        long last = seen.getLong("last_exit", 0);
        long newest = last;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                ActivityManager am = (ActivityManager) context.getSystemService(ACTIVITY_SERVICE);
                for (ApplicationExitInfo x : am.getHistoricalProcessExitReasons(null, 0, 8)) {
                    if (x.getTimestamp() <= last) {
                        continue;
                    }
                    newest = Math.max(newest, x.getTimestamp());
                    int r = x.getReason();
                    if (r != ApplicationExitInfo.REASON_CRASH && r != ApplicationExitInfo.REASON_CRASH_NATIVE
                            && r != ApplicationExitInfo.REASON_ANR
                            && r != ApplicationExitInfo.REASON_INITIALIZATION_FAILURE
                            && r != ApplicationExitInfo.REASON_LOW_MEMORY) {
                        continue;
                    }
                    bad = true;
                    report.append("== ").append(java.text.DateFormat.getDateTimeInstance()
                                    .format(new java.util.Date(x.getTimestamp())))
                            .append("  processo ").append(x.getProcessName())
                            .append("\nmotivo ").append(r).append(", stato ").append(x.getStatus())
                            .append("\n").append(x.getDescription()).append("\n\n");
                }
            } catch (Throwable e) {
                report.append("(registro uscite non disponibile: ").append(e).append(")\n");
            }
        }
        seen.edit().putLong("last_exit", newest).apply();
        if (!bad) {
            return null;
        }
        File ext = Setup.filesDir(context);
        report.append("== keisho.log ==\n").append(tail(new File(ext, "keisho.log"), 30));
        report.append("\n== android.log ==\n").append(tail(new File(ext, "android.log"), 20));
        return report.toString();
    }

    private static String tail(File f, int lines) {
        if (!f.isFile()) {
            return "(" + f.getName() + " non trovato)\n";
        }
        try {
            java.util.List<String> all = java.nio.file.Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
            StringBuilder b = new StringBuilder();
            for (String l : all.subList(Math.max(0, all.size() - lines), all.size())) {
                b.append(l).append('\n');
            }
            return b.toString();
        } catch (Exception e) {
            return "(" + f.getName() + " illeggibile: " + e + ")\n";
        }
    }
}
