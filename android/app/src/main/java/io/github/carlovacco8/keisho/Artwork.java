// Keisho — Android port: game pictures for the launcher's cards.
// SPDX-License-Identifier: GPL-3.0-or-later
//
// The same source Keisho's own (desktop) scraper uses: ArcadeItalia's
// ArcadeDB. One in-game screenshot per set, cached in files/artwork/ next to
// Keisho's artwork, so each picture is downloaded once.

package io.github.carlovacco8.keisho;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class Artwork {
    interface Callback {
        void onBitmap(String name, Bitmap bitmap);
    }

    private static final String META =
            "https://adb.arcadeitalia.net/service_scraper.php?ajax=query_mame&lang=en&game_name=";
    private static final String[] FIELDS = { "url_image_ingame", "url_image_title", "url_image_flyer" };

    private final File mDir;
    private final ExecutorService mPool = Executors.newFixedThreadPool(3);
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> mCache = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    private final Set<String> mPending = new HashSet<>();

    Artwork(Context context) {
        mDir = new File(Setup.filesDir(context), "artwork");
        //noinspection ResultOfMethodCallIgnored
        mDir.mkdirs();
    }

    void request(String name, Callback callback) {
        Bitmap cached = mCache.get(name);
        if (cached != null) {
            callback.onBitmap(name, cached);
            return;
        }
        synchronized (mPending) {
            if (!mPending.add(name)) {
                return;
            }
        }
        mPool.execute(() -> {
            Bitmap bmp = load(name);
            synchronized (mPending) {
                mPending.remove(name);
            }
            if (bmp != null) {
                mCache.put(name, bmp);
                mMain.post(() -> callback.onBitmap(name, bmp));
            }
        });
    }

    private Bitmap load(String name) {
        File png = new File(mDir, name + ".card.png");
        File missing = new File(mDir, name + ".card.none");
        if (!png.exists() && !missing.exists()) {
            fetch(name, png, missing);
        }
        if (!png.exists()) {
            return null;
        }
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeFile(png.getAbsolutePath(), o);
    }

    private static void fetch(String name, File png, File missing) {
        try {
            String body = new String(get(META + name), StandardCharsets.UTF_8);
            for (String field : FIELDS) {
                Matcher m = Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
                if (!m.find()) {
                    continue;
                }
                String url = m.group(1).replace("\\/", "/").replace("resize=0", "resize=480");
                byte[] image = get(url);
                if (image.length > 64) {
                    try (FileOutputStream out = new FileOutputStream(png)) {
                        out.write(image);
                    }
                    return;
                }
            }
            //noinspection ResultOfMethodCallIgnored
            missing.createNewFile();   // not in the database: do not ask again
        } catch (IOException ignored) {
            // Offline: try again next time.
        }
    }

    private static byte[] get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", "Keisho-Android");
        try {
            if (c.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + c.getResponseCode());
            }
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[32 * 1024];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    bytes.write(buffer, 0, n);
                }
                return bytes.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }
}
