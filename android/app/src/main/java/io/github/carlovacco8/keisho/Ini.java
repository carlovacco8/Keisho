// Keisho — Android port: minimal editor for keisho.ini.
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Keys are unique across sections in Keisho's file, so a value is found by key
// and replaced in place; a missing key is added under its [section]. Comments,
// order and every key the launcher does not touch are kept as they are, so
// Keisho's own settings overlay and this launcher can share the file.

package io.github.carlovacco8.keisho;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

final class Ini {
    private final File mFile;
    private final List<String> mLines = new ArrayList<>();

    Ini(File file) {
        mFile = file;
        try {
            if (file.exists()) {
                mLines.addAll(Files.readAllLines(file.toPath(), StandardCharsets.UTF_8));
            }
        } catch (IOException ignored) {
            // A missing or unreadable file reads as empty; defaults apply.
        }
    }

    private static String keyOf(String line) {
        String t = line.trim();
        if (t.isEmpty() || t.startsWith("#") || t.startsWith(";") || t.startsWith("[")) {
            return null;
        }
        int eq = t.indexOf('=');
        return eq < 0 ? null : t.substring(0, eq).trim();
    }

    String get(String key, String fallback) {
        for (String line : mLines) {
            if (key.equals(keyOf(line))) {
                String value = line.substring(line.indexOf('=') + 1).trim();
                return value.isEmpty() ? fallback : value;
            }
        }
        return fallback;
    }

    boolean getBool(String key, boolean fallback) {
        String v = get(key, null);
        if (v == null) {
            return fallback;
        }
        v = v.toLowerCase();
        return v.equals("true") || v.equals("yes") || v.equals("on") || v.equals("1");
    }

    int getInt(String key, int fallback) {
        try {
            return Integer.parseInt(get(key, String.valueOf(fallback)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    void set(String section, String key, String value) {
        for (int i = 0; i < mLines.size(); i++) {
            if (key.equals(keyOf(mLines.get(i)))) {
                mLines.set(i, key + " = " + value);
                save();
                return;
            }
        }
        String header = "[" + section + "]";
        int insert = -1;
        for (int i = 0; i < mLines.size(); i++) {
            if (mLines.get(i).trim().equalsIgnoreCase(header)) {
                insert = i + 1;
                // After the section's last non-empty line.
                while (insert < mLines.size() && !mLines.get(insert).trim().startsWith("[")) {
                    insert++;
                }
                while (insert > i + 1 && mLines.get(insert - 1).trim().isEmpty()) {
                    insert--;
                }
                break;
            }
        }
        if (insert < 0) {
            mLines.add("");
            mLines.add(header);
            mLines.add(key + " = " + value);
        } else {
            mLines.add(insert, key + " = " + value);
        }
        save();
    }

    void setBool(String section, String key, boolean value) {
        set(section, key, value ? "true" : "false");
    }

    private void save() {
        try {
            Files.write(mFile.toPath(), mLines, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // Best effort; the next change tries again.
        }
    }
}
