// Keisho — Android port: crash report.
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Any uncaught Java exception, in the launcher or in the game process, is
// written to files/last_crash.txt before the app closes. The launcher shows
// it on the next start (CrashReport), so a crash on a phone or a TV can be
// read and photographed without a PC.

package io.github.carlovacco8.keisho;

import android.app.Application;
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
}
