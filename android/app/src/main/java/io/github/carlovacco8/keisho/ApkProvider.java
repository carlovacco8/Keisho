// Keisho — Android port: hands the downloaded update to Android's installer.
// SPDX-License-Identifier: GPL-3.0-or-later
//
// A one-file content provider (no AndroidX here): it serves only
// cache/keisho-update.apk, read-only, to the app the URI is granted to.
// Android's own package installer then installs it in its own process, so
// the update completes even though installing it ends Keisho.

package io.github.carlovacco8.keisho;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

public class ApkProvider extends ContentProvider {
    static final String FILE_NAME = "keisho-update.apk";

    static Uri uri(android.content.Context context) {
        return Uri.parse("content://" + context.getPackageName() + ".apk/" + FILE_NAME);
    }

    private File apk() {
        return new File(getContext().getCacheDir(), FILE_NAME);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!FILE_NAME.equals(uri.getLastPathSegment()) || !"r".equals(mode)) {
            throw new FileNotFoundException(uri.toString());
        }
        return ParcelFileDescriptor.open(apk(), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        return "application/vnd.android.package-archive";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        MatrixCursor c = new MatrixCursor(new String[] { OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE });
        c.addRow(new Object[] { FILE_NAME, apk().length() });
        return c;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        throw new UnsupportedOperationException();
    }
}
