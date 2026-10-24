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
    public static final String K_GLASS_EXTREME = "glass_extreme"; // DIY mode master: 14 percent sliders replace the baked-in look
    public static final String K_PURIFY = "purify";       // installer purify master
    public static final String K_INFOCARD = "infocard";   // package info card
    public static final String K_DOWNGRADE = "downgrade"; // allow downgrade installs
    public static final String[] KEYS = {
            K_GLASS, K_SYSTEM, K_KEYGUARD, K_GALLERY, K_GLASS_EXTREME,
            K_PURIFY, K_INFOCARD, K_DOWNGRADE,
    };

    // ===== DIY slider keys (percent 0-100, hooks read them when
    // K_GLASS_EXTREME / DIY mode is on) =====
    public static final String D_MASK = "diy_mask";       // 通知栏+工具栏遮罩
    public static final String D_TBLUR = "diy_tblur";     // 通知栏+工具栏模糊
    public static final String D_BMASK = "diy_bmask";     // 通知横幅遮罩
    public static final String D_BBLUR = "diy_bblur";     // 通知横幅模糊
    public static final String D_KBLUR = "diy_kblur";     // 锁屏通知模糊
    public static final String D_WMASK = "diy_wmask";     // 锁屏组件+按钮遮罩
    public static final String D_RADIUS = "diy_radius";   // 圆角
    public static final String D_SAT = "diy_sat";         // 饱和度
    public static final String D_EDGE = "diy_edge";       // 边缘光
    public static final String D_REFRACT = "diy_refract"; // 折射
    public static final String D_RIM = "diy_rim";         // 边框
    public static final String D_THICK = "diy_thick";     // 厚度
    public static final String D_DISP = "diy_disp";       // 色散
    public static final String D_KBBRIGHT = "diy_kbbright"; // 锁屏通知亮度
    public static final String[] DIY_KEYS = {
            D_MASK, D_TBLUR, D_BMASK, D_BBLUR, D_KBLUR, D_WMASK,
            D_RADIUS, D_SAT, D_EDGE, D_REFRACT, D_RIM, D_THICK, D_DISP,
            D_KBBRIGHT,
    };

    /** default percent for each DIY slider (matches the old EXTREME values) */
    public static int diyDefPct(String key) {
        if (D_MASK.equals(key)) return 50;      // 0.5  of 1.0
        if (D_TBLUR.equals(key)) return 10;     // 0.1  of 1.0
        if (D_BMASK.equals(key)) return 15;     // 0.15 of 1.0
        if (D_BBLUR.equals(key)) return 10;     // 0.1  of 1.0
        if (D_KBLUR.equals(key)) return 50;     // 1.0  of 2.0
        if (D_WMASK.equals(key)) return 8;      // 0.08 of 1.0
        if (D_RADIUS.equals(key)) return 50;    // 0.5  of 1.0
        if (D_SAT.equals(key)) return 60;       // 1.2  of 2.0
        if (D_EDGE.equals(key)) return 70;      // 1.4  of 2.0
        if (D_REFRACT.equals(key)) return 50;   // 1.0  of 2.0
        if (D_RIM.equals(key)) return 50;       // 1.0  of 2.0
        if (D_THICK.equals(key)) return 70;     // 1.4  of 2.0
        if (D_DISP.equals(key)) return 50;      // 0.25 of 0.5
        if (D_KBBRIGHT.equals(key)) return 0;   // 0    of 0.3
        return 50;
    }

    /** null = preload not landed yet (default ON); set once, then frozen
     *  for glass scopes / observer-refreshed for installer scopes.
     *  Values are stored as int: switches use 0/1, DIY sliders use 0-100. */
    private static volatile Map<String, int[]> sSnapshot = null;
    private static boolean sStarted = false;

    private Cfg() {
    }

    /** hook side: pure memory read — no locks, no binder, no blocking. */
    public static boolean isOn(String key) {
        Map<String, int[]> m = sSnapshot;
        if (m == null) {
            return true; // preload not landed yet: default ON
        }
        int[] v = m.get(key);
        return v == null ? true : v[0] != 0;
    }

    /** hook side: DIY slider read (percent), falls back to the default pct. */
    public static int getInt(String key, int def) {
        Map<String, int[]> m = sSnapshot;
        if (m == null) {
            return def;
        }
        int[] v = m.get(key);
        return v == null ? def : v[0];
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
        Map<String, int[]> m = new HashMap<String, int[]>();
        try {
            Cursor c = ctx.getContentResolver().query(
                    CfgProvider.URI, null, null, null, null);
            if (c == null) {
                return false;
            }
            try {
                while (c.moveToNext()) {
                    m.put(c.getString(0), new int[]{c.getInt(1)});
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
        Map<String, int[]> old = sSnapshot;
        if (old == null || !old.equals(m)) {
            XposedBridge.log("[HAS] Cfg via provider: " + m);
        }
        sSnapshot = m;
        return true;
    }
}
