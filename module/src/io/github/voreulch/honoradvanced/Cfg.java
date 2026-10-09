package io.github.voreulch.honoradvanced;

import android.app.AndroidAppHelper;
import android.content.Context;
import android.database.ContentObserver;
import android.database.Cursor;
import android.os.Handler;
import android.os.HandlerThread;

import java.util.HashMap;
import java.util.Map;

import de.robv.android.xposed.XposedBridge;

/**
 * Switch store read by hooks. SettingsActivity writes shared_prefs/config.xml
 * (no root). On this device Vector lacks LSPosed's prefs serving and SELinux
 * MCS blocks direct cross-app reads, so values travel over the module's
 * exported CfgProvider.
 *
 * CRITICAL (v1.2.1, after the 2026-10-10 keyguard deadlock): hook callbacks
 * must NEVER do binder work. The old 2s-TTL design ran provider queries
 * synchronously inside synchronized isOn() — when a glass hook callback
 * landed on a SystemUI binder thread holding the Dependency lock, the
 * cross-process query (which first has to unfreeze the module app) held
 * that lock long enough to blow the stock keyguard lazy-init race into an
 * AB-BA deadlock ("cannot wake screen"). Now:
 *   - isOn() reads a volatile snapshot only; unconfirmed = default ON.
 *   - A single daemon thread preloads the snapshot once the provider is
 *     reachable. Glass scopes freeze it for the process lifetime — switch
 *     changes there apply after the scoped process restarts (which the
 *     settings UI already states).
 *   - Installer scopes (packageinstaller, system_server) additionally
 *     register a ContentObserver; SettingsActivity notifies the URI on
 *     every toggle, so changes propagate instantly without polling and
 *     without a process restart ("applies to the next install").
 */
public final class Cfg {

    public static final String PREFS_FILE = "config";

    // prefs keys shared with SettingsActivity
    public static final String K_GLASS = "glass";         // liquid glass master (incl. system-level base render: HIGH tier, LIQUID bits)
    public static final String K_SYSTEM = "system";       // status-bar notifications + control-center look tuning
    public static final String K_KEYGUARD = "keyguard";   // lockscreen buttons + cards (merged)
    public static final String K_GALLERY = "gallery";     // gallery photo page
    public static final String K_PURIFY = "purify";       // installer purify master
    public static final String K_INFOCARD = "infocard";   // package info card
    public static final String K_DOWNGRADE = "downgrade"; // allow downgrade installs

    public static final String[] KEYS = {
            K_GLASS, K_SYSTEM, K_KEYGUARD, K_GALLERY,
            K_PURIFY, K_INFOCARD, K_DOWNGRADE,
    };

    /** null = preload not landed yet (default ON); set once, then frozen
     *  for glass scopes / observer-refreshed for installer scopes. */
    private static volatile Map<String, Boolean> sSnapshot = null;
    private static boolean sStarted = false;

    private Cfg() {
    }

    /** hook side: pure memory read — no locks, no binder, no blocking. */
    public static boolean isOn(String key) {
        Map<String, Boolean> m = sSnapshot;
        if (m == null) {
            return true; // standalone modules' default-ON behavior
        }
        Boolean v = m.get(key);
        return v == null || v.booleanValue();
    }

    /**
     * Call once per scoped process from handleLoadPackage.
     *
     * @param watchChanges true for installer scopes: after the initial load,
     *                     a ContentObserver refreshes the snapshot whenever
     *                     SettingsActivity toggles a switch; false for glass
     *                     scopes: snapshot frozen until the process restarts.
     */
    public static synchronized void startPreload(final boolean watchChanges) {
        if (sStarted) {
            return;
        }
        sStarted = true;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                // early phase: app/binder may not be up yet — retry briefly
                for (int i = 0; i < 30; i++) {
                    if (loadOnce()) {
                        break;
                    }
                    if (!sleep(500L)) {
                        return;
                    }
                }
                if (sSnapshot == null) {
                    XposedBridge.log("[HAS] Cfg preload gave up; staying "
                            + "default-ON (no further provider queries)");
                    return;
                }
                if (!watchChanges) {
                    XposedBridge.log("[HAS] Cfg frozen for process lifetime");
                    return;
                }
                registerObserver();
            }
        }, "HAS-CfgPreload");
        t.setDaemon(true);
        t.start();
    }

    private static void registerObserver() {
        try {
            Context ctx = AndroidAppHelper.currentApplication();
            if (ctx == null) {
                XposedBridge.log("[HAS] Cfg: no context, observer skipped "
                        + "(snapshot stays at initial load)");
                return;
            }
            HandlerThread ht = new HandlerThread("HAS-CfgObs");
            ht.start();
            ctx.getContentResolver().registerContentObserver(
                    CfgProvider.URI, true, new ContentObserver(new Handler(ht.getLooper())) {
                @Override
                public void onChange(boolean selfChange) {
                    loadOnce(); // this thread holds no system locks
                }
            });
            XposedBridge.log("[HAS] Cfg watching provider changes");
        } catch (Throwable t) {
            XposedBridge.log("[HAS] Cfg observer failed: " + t);
        }
    }

    private static boolean sleep(long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            return false;
        }
    }

    /** one provider query; true on success. Never called from a hook callback. */
    private static boolean loadOnce() {
        Context ctx = null;
        try {
            ctx = AndroidAppHelper.currentApplication();
        } catch (Throwable ignored) {
        }
        if (ctx == null) {
            return false;
        }
        Map<String, Boolean> m = new HashMap<String, Boolean>();
        try {
            Cursor c = ctx.getContentResolver().query(
                    CfgProvider.URI, null, null, null, null);
            if (c == null) {
                return false;
            }
            try {
                while (c.moveToNext()) {
                    m.put(c.getString(0), c.getInt(1) != 0);
                }
            } finally {
                try {
                    c.close();
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            return false; // provider unreachable this round — retry happens in the loop
        }
        if (m.isEmpty()) {
            return false;
        }
        Map<String, Boolean> old = sSnapshot;
        if (old == null || !old.equals(m)) {
            XposedBridge.log("[HAS] Cfg via provider: " + m);
        }
        sSnapshot = m;
        return true;
    }
}
