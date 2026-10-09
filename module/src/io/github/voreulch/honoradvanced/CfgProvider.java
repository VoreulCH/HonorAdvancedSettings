package io.github.voreulch.honoradvanced;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

/**
 * Serves switch values to hooked processes. On this device SELinux MCS
 * blocks direct reads of the module's config.xml and Vector lacks
 * LSPosed's prefs serving, so hooks fetch the values over the standard
 * binder channel instead. Read-only, no permission required.
 */
public final class CfgProvider extends ContentProvider {

    public static final String AUTHORITY = "io.github.voreulch.honoradvanced.cfg";
    public static final Uri URI =
            Uri.parse("content://" + AUTHORITY + "/switches");

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        MatrixCursor cursor = new MatrixCursor(
                new String[]{"key", "val"}, Cfg.KEYS.length);
        Context context = getContext();
        if (context != null) {
            SharedPreferences prefs = context.getSharedPreferences(
                    Cfg.PREFS_FILE, Context.MODE_PRIVATE);
            for (String key : Cfg.KEYS) {
                cursor.addRow(new Object[]{key, migrated(prefs, key) ? 1 : 0});
            }
        }
        return cursor;
    }

    /**
     * v1.0.x prefs carried different keys (master/keyguard/kgcard/skipcheck/
     * nonet/hideextras). Serve the migrated view without writing: new key
     * wins if present, else derive from the legacy key, else default ON.
     */
    private static boolean migrated(SharedPreferences p, String key) {
        if (p.contains(key)) {
            return p.getBoolean(key, true);
        }
        if (Cfg.K_GLASS.equals(key)) {
            return p.getBoolean("master", true);
        }
        if (Cfg.K_KEYGUARD.equals(key)) {
            return p.getBoolean("keyguard", true) || p.getBoolean("kgcard", true);
        }
        if (Cfg.K_GALLERY.equals(key)) {
            return p.getBoolean("gallery", true);
        }
        if (Cfg.K_INFOCARD.equals(key)) {
            return p.getBoolean("infocard", true);
        }
        if (Cfg.K_DOWNGRADE.equals(key)) {
            return p.getBoolean("downgrade", true);
        }
        return true; // K_SYSTEM / K_PURIFY had no legacy counterpart
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.dir/vnd.voreulch.has.switches";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection,
                      String[] selectionArgs) {
        return 0;
    }
}
