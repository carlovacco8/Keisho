// Keisho — Android port: the game list, from Keisho's own games.xml.
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.carlovacco8.keisho;

import android.content.Context;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class GameDb {

    static final class Game {
        String name;          // MAME set name, = the archive's file name
        String title = "";
        String year = "";
        String board = "";    // e.g. model2a
        String parent = "";
        final Set<String> inputs = new HashSet<>();
        File archive;         // filled for games found on disk

        /** "Model 2A" from "model2a"; "Model 3 Step 1.5" etc. stay readable. */
        String boardLabel() {
            if (board.isEmpty()) {
                return "";
            }
            String b = board.toLowerCase(Locale.ROOT);
            for (String family : new String[] { "model1", "model2", "model3" }) {
                if (b.startsWith(family)) {
                    String rest = b.substring(family.length()).replace('_', ' ').trim();
                    return "Model " + family.charAt(5) + (rest.isEmpty() ? "" : rest.toUpperCase(Locale.ROOT));
                }
            }
            return board;
        }

        String subtitle() {
            StringBuilder s = new StringBuilder();
            String label = boardLabel();
            if (!label.isEmpty()) {
                s.append(label);
            }
            if (!year.isEmpty()) {
                if (s.length() > 0) {
                    s.append(" · ");
                }
                s.append(year);
            }
            return s.toString();
        }

        boolean has(String input) {
            return inputs.contains(input);
        }

        boolean isDriving() {
            return has("vehicle") || has("harley") || has("magtruck") || has("ski");
        }

        boolean isGun() {
            return has("gun1") || has("analog_gun1");
        }
    }

    private final Map<String, Game> mGames = new HashMap<>();

    static GameDb load(Context context) {
        GameDb db = new GameDb();
        try (InputStream in = context.getAssets().open("games.xml")) {
            XmlPullParser p = Xml.newPullParser();
            p.setInput(in, "UTF-8");
            Game current = null;
            String text = null;
            for (int e = p.getEventType(); e != XmlPullParser.END_DOCUMENT; e = p.next()) {
                if (e == XmlPullParser.START_TAG) {
                    String tag = p.getName();
                    if ("game".equals(tag)) {
                        current = new Game();
                        current.name = attr(p, "name");
                        current.board = attr(p, "board");
                        current.parent = attr(p, "parent");
                    } else if (current != null && "input".equals(tag)) {
                        String type = attr(p, "type");
                        if (!type.isEmpty()) {
                            current.inputs.add(type);
                        }
                    }
                    text = null;
                } else if (e == XmlPullParser.TEXT) {
                    text = p.getText();
                } else if (e == XmlPullParser.END_TAG) {
                    String tag = p.getName();
                    if (current != null) {
                        if ("title".equals(tag) && text != null && current.title.isEmpty()) {
                            current.title = text.trim();
                        } else if ("year".equals(tag) && text != null && current.year.isEmpty()) {
                            current.year = text.trim();
                        } else if ("game".equals(tag)) {
                            if (current.name != null && !current.name.isEmpty()) {
                                db.mGames.put(current.name, current);
                            }
                            current = null;
                        }
                    }
                    text = null;
                }
            }
        } catch (Exception ignored) {
            // An unreadable database just means an empty list.
        }
        return db;
    }

    private static String attr(XmlPullParser p, String name) {
        String v = p.getAttributeValue(null, name);
        return v == null ? "" : v;
    }

    Game get(String name) {
        return mGames.get(name);
    }

    /** Result of a folder scan: known games found, plus every archive seen. */
    static final class Scan {
        final List<Game> games = new ArrayList<>();
        int archives;
    }

    Scan scan(List<File> folders) {
        Scan scan = new Scan();
        Set<String> seen = new HashSet<>();
        for (File folder : folders) {
            File[] files = folder == null ? null : folder.listFiles();
            if (files == null) {
                continue;
            }
            for (File f : files) {
                String n = f.getName();
                String lower = n.toLowerCase(Locale.ROOT);
                if (!f.isFile() || !(lower.endsWith(".zip") || lower.endsWith(".7z"))) {
                    continue;
                }
                scan.archives++;
                String stem = n.substring(0, n.lastIndexOf('.'));
                Game known = mGames.get(stem);
                if (known == null || seen.contains(stem)) {
                    continue;   // device/BIOS sets, or a duplicate in a second folder
                }
                seen.add(stem);
                Game g = copyOf(known);
                g.archive = f;
                scan.games.add(g);
            }
        }
        Collections.sort(scan.games, (a, b) -> a.title.compareToIgnoreCase(b.title));
        return scan;
    }

    private static Game copyOf(Game src) {
        Game g = new Game();
        g.name = src.name;
        g.title = src.title.isEmpty() ? src.name : src.title;
        g.year = src.year;
        g.board = src.board;
        g.parent = src.parent;
        g.inputs.addAll(src.inputs);
        return g;
    }
}
