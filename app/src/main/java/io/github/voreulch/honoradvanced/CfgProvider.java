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

    /**
     * 作用域自证通道（v2.3.0）。
     *
     * <p>每个被 hook 的进程启动时 insert 一行 "{pkg, ver}"，写入发生在**模块 app
     * 进程**（provider 天然跑在自己的进程里），hook 侧只出一次 binder 调用。
     * 于是模块界面不用 root、不用读 LSPosed 数据库，就能知道"这个作用域是不是
     * 真的跑起来了"——而且它验证的是**实际生效**，不是"勾选过"：
     * 勾了但进程没重启时，这里会诚实地显示未生效。
     */
    public static final Uri URI_PROBE =
            Uri.parse("content://" + AUTHORITY + "/probe");

    /** 上报存这里（与开关分开，避免污染开关快照）。 */
    public static final String PREFS_PROBE = "probe";

    public static final String COL_PKG = "pkg";
    public static final String COL_VER = "ver";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        if (URI_PROBE.equals(uri)) {
            return probeCursor();
        }
        MatrixCursor cursor = new MatrixCursor(
                new String[]{"key", "val"},
                Cfg.KEYS.length + Cfg.TUNE_KEYS.length + Cfg.FN_KEYS.length);
        Context context = getContext();
        if (context != null) {
            SharedPreferences prefs = context.getSharedPreferences(
                    Cfg.PREFS_FILE, Context.MODE_PRIVATE);
            for (String key : Cfg.KEYS) {
                cursor.addRow(new Object[]{key, migrated(prefs, key) ? 1 : 0});
            }
            for (String key : Cfg.TUNE_KEYS) {
                cursor.addRow(new Object[]{key, prefs.getInt(key, Cfg.tuneDefPct(key))});
            }
            for (String key : Cfg.FN_KEYS) {
                cursor.addRow(new Object[]{key, prefs.getInt(key, Cfg.fnDefPct(key))});
            }
        }
        return cursor;
    }

    /** 已上报的作用域（pkg / 模块版本 / 上报时间）。 */
    private Cursor probeCursor() {
        MatrixCursor c = new MatrixCursor(
                new String[]{COL_PKG, COL_VER, "at"}, Cfg.SCOPES.length);
        Context ctx = getContext();
        if (ctx == null) {
            return c;
        }
        SharedPreferences p = ctx.getSharedPreferences(PREFS_PROBE, Context.MODE_PRIVATE);
        for (String pkg : Cfg.SCOPES) {
            String raw = p.getString(pkg, null);
            if (raw == null) {
                continue;
            }
            int ver = 0;
            long at = 0L;
            int bar = raw.indexOf('|');
            if (bar > 0) {
                try {
                    ver = Integer.parseInt(raw.substring(0, bar));
                } catch (Throwable ignored) {
                }
                try {
                    at = Long.parseLong(raw.substring(bar + 1));
                } catch (Throwable ignored) {
                }
            }
            c.addRow(new Object[]{pkg, ver, at});
        }
        return c;
    }

    /** 只有声明过的宿主包名能上报，别的 app 塞不进来。 */
    private static boolean isKnownScope(String pkg) {
        for (String s : Cfg.SCOPES) {
            if (s.equals(pkg)) {
                return true;
            }
        }
        return false;
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
        // 4 个 opt-in 开关（DIY 总闸 / 不压低音量 / 高温旁路 / 诊断日志）统一由
        // Cfg.isOptIn 判定，别再在这里各写一个 if —— 那是三份重复逻辑的来源。
        if (Cfg.isOptIn(key)) {
            return false;
        }
        return true; // K_SYSTEM / K_PURIFY had no legacy counterpart
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.dir/vnd.voreulch.has.switches";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        if (!URI_PROBE.equals(uri) || values == null) {
            return null;
        }
        String pkg = values.getAsString(COL_PKG);
        if (pkg == null || !isKnownScope(pkg)) {
            return null;
        }
        Integer ver = values.getAsInteger(COL_VER);
        Context ctx = getContext();
        if (ctx == null) {
            return null;
        }
        boolean ok = ctx.getSharedPreferences(PREFS_PROBE, Context.MODE_PRIVATE)
                .edit()
                .putString(pkg, (ver == null ? 0 : ver) + "|" + System.currentTimeMillis())
                .commit();
        return ok ? uri : null;
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
