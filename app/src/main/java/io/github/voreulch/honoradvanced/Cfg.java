package io.github.voreulch.honoradvanced;

import android.app.AndroidAppHelper;
import android.content.Context;
import android.database.ContentObserver;
import android.database.Cursor;
import android.os.Handler;
import android.os.HandlerThread;

import java.util.HashMap;
import java.util.Map;

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
    public static final String K_NODUCK = "noduck";       // misc: block notification audio ducking
    public static final String K_HOTTEMP = "hottemp";     // misc: suppress the system high-temperature popups
    public static final String K_DIAG = "diag";           // about: verbose diagnostics into the LSPosed log
    public static final String[] KEYS = {
            K_GLASS, K_SYSTEM, K_KEYGUARD, K_GALLERY, K_GLASS_EXTREME,
            K_PURIFY, K_INFOCARD, K_DOWNGRADE, K_NODUCK, K_HOTTEMP, K_DIAG,
    };

    /**
     * 作用域清单（与 res/values/arrays.xml 的 xposed_scope 一致）。
     * 两处用途：CfgProvider 校验 probe 上报来源；UI 判断某个开关依赖的进程有没有跑起来。
     */
    public static final String[] SCOPES = {
            "com.android.systemui",
            "com.hihonor.android.launcher",
            "com.hihonor.desktop.systemui",
            "com.hihonor.photos",
            "com.android.packageinstaller",
            "com.hihonor.powergenie",
            "com.hihonor.iaware",
            "android",
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

    /**
     * Cross-process gate for the high-temperature bypass. system_server can
     * never read the module prefs/provider (SELinux), so SettingsActivity
     * mirrors this one switch into a system property with root; every hook
     * (SystemUI *and* system_server) reads the property live, so toggling
     * takes effect immediately — no scope restart, no reboot.
     * Missing/unreadable -> "0" (OFF): a root-level safety bypass must never
     * be silently active on a fresh install.
     */
    public static final String PROP_HOTTEMP = "persist.sys.has.hottemp";

    // Cached reader: the thermal hooks fire on EVERY HAL temperature callback
    // (several per second) and on EVERY status query, so a Class.forName +
    // getMethod per call would be real overhead inside system_server and
    // PowerGenie. Resolve the Method once, then re-read the property at most
    // once per second (a toggle therefore lands within ~1s, still "instant").
    private static volatile java.lang.reflect.Method sPropGet;
    private static volatile long sPropAt;
    private static volatile boolean sPropVal = true;

    public static boolean hottempOn() {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - sPropAt < 1000L) {
            return sPropVal;
        }
        try {
            java.lang.reflect.Method m = sPropGet;
            if (m == null) {
                Class<?> sp = Class.forName("android.os.SystemProperties");
                m = sp.getMethod("get", String.class, String.class);
                sPropGet = m;
            }
            sPropVal = !"0".equals(m.invoke(null, PROP_HOTTEMP, "0"));
        } catch (Throwable t) {
            // keep the last known value and back off instead of retrying the
            // (possibly failing) reflection on every single callback
        }
        sPropAt = now;
        return sPropVal;
    }

    /**
     * DIY 滑条的出厂百分比 —— **必须与 GlassHook 的 DEFAULT_* 常量等价**。
     *
     * <p>历史教训（v2.3.12）：旧的默认值是 v1.x「EXTREME 极端玻璃」时代的遗留，
     * 比后来的标准配方薄得多（tblur 10% vs 60%、mask 50% vs 65%、bmask 15% vs 40%）。
     * 于是用户一打开 DIY 开关，玻璃立刻变亮、白底上看不清字，还以为是 bug ——
     * 其实是"切到了另一套更弱的配方"。现在的语义：**打开 DIY 观感不变**，
     * 用户再在标准配方的基础上逐项微调。
     *
     * <p>改这里之前先对一遍 {@code GlassHook.DEFAULT_*}：percent × max 必须等于常量。
     * 对应关系写在每行注释里。
     *
     * <p>取值必须是 5 的倍数 —— 设置页的滑条按 5% 吸附（steps=19），不是 5 的倍数
     * 一拖就跳档。旧的 KBBRIGHT 默认 0（33.3% 才精确等于 0.1）因此取 35，误差 5%
     * 在亮度上看不出来。
     */
    public static int diyDefPct(String key) {
        if (D_MASK.equals(key)) return 65;      // 0.65 × max 1.0  = DEFAULT_MASK
        if (D_TBLUR.equals(key)) return 60;     // 0.60 × max 1.0  = DEFAULT_TBLUR
        if (D_BMASK.equals(key)) return 40;     // 0.40 × max 1.0  = DEFAULT_NTF_MASK
        if (D_BBLUR.equals(key)) return 50;     // 0.50 × max 1.0  = DEFAULT_BANNER_BLUR
        if (D_KBLUR.equals(key)) return 100;    // 2.00 × max 2.0  = DEFAULT_KBBLUR
        if (D_WMASK.equals(key)) return 20;     // 0.20 × max 1.0  = DEFAULT_KVEIL
        if (D_RADIUS.equals(key)) return 50;    // 0.50 × max 1.0  = DEFAULT_RADIUS
        if (D_SAT.equals(key)) return 60;       // 1.20 × max 2.0  = DEFAULT_SAT
        if (D_EDGE.equals(key)) return 70;      // 1.40 × max 2.0  = DEFAULT_EDGE
        if (D_REFRACT.equals(key)) return 50;   // 1.00 × max 2.0  = DEFAULT_REFRACT
        if (D_RIM.equals(key)) return 50;       // 1.00 × max 2.0  = DEFAULT_RIM
        if (D_THICK.equals(key)) return 70;     // 1.40 × max 2.0  = DEFAULT_THICK
        if (D_DISP.equals(key)) return 50;      // 0.25 × max 0.5  = DEFAULT_DISP
        if (D_KBBRIGHT.equals(key)) return 35;  // 0.105 × max 0.3 ≈ DEFAULT_KBBRIGHT(0.1)
        return 50;
    }

    /**
     * 旧的出厂百分比（v2.3.11 及以前）。只用于一次性迁移：老用户 prefs 里
     * **仍等于旧默认**的键说明他没动过，可以安全升级到新默认；不等于旧默认的
     * 说明是他自己拖过的，一律保留。
     */
    public static int diyLegacyPct(String key) {
        if (D_MASK.equals(key)) return 50;
        if (D_TBLUR.equals(key)) return 10;
        if (D_BMASK.equals(key)) return 15;
        if (D_BBLUR.equals(key)) return 10;
        if (D_KBLUR.equals(key)) return 50;
        if (D_WMASK.equals(key)) return 8;
        if (D_RADIUS.equals(key)) return 50;
        if (D_SAT.equals(key)) return 60;
        if (D_EDGE.equals(key)) return 70;
        if (D_REFRACT.equals(key)) return 50;
        if (D_RIM.equals(key)) return 50;
        if (D_THICK.equals(key)) return 70;
        if (D_DISP.equals(key)) return 50;
        if (D_KBBRIGHT.equals(key)) return 0;
        return 50;
    }

    /** 迁移过「DIY 默认 = 标准配方」这一版的标记键。 */
    public static final String K_DIY_ALIGNED = "diy_aligned";

    /** null = preload not landed yet (default ON); set once, then frozen
     *  for glass scopes / observer-refreshed for installer scopes.
     *  Values are stored as int: switches use 0/1, DIY sliders use 0-100. */
    private static volatile Map<String, int[]> sSnapshot = null;
    private static boolean sStarted = false;

    private Cfg() {
    }

    /**
     * 未就绪 / 快照里缺键时**必须按关闭处理**的开关（opt-in）。
     *
     * 这些不是"功能开关"，而是**模式选择器 / 高风险旁路**：默认 ON 会让 hook
     * 走上另一套完全不同的配方，而不是"更完整的功能"。
     *
     * GLASS_EXTREME 就是实例（v2.3.11 白玻璃事故）：它是 DIY 模式总闸，一旦误判为
     * ON，refreshProps() 会去读 diy_* 滑条；而滑条在未就绪时只能返回 diyDefPct()
     * 的出厂百分比，比 baked 标准配方弱得多（tblur 0.1 vs 0.6、nmask 0.15 vs 0.4），
     * 玻璃直接退化成"几乎不糊、几乎不遮"的亮片 —— 白色背景下文字就看不清了。
     * 这个误判只在 SystemUI 启动那一刻 Cfg 快照还没从 provider 落地时发生，所以
     * 表现为"开机后发亮，重启一两次 SystemUI 又自己好了"的偶发 bug。
     *
     * <p><b>这是唯一事实来源</b>：{@link CfgProvider#migrated}（provider 对缺失键的
     * 默认值）和 SettingsActivity.migratePrefs()（首次启动补全）都必须调它。
     * 三处各写一份 if 的写法在 v2.3.12 之前就存在，加新开关时漏改一处就会出现
     * "UI 显示关闭、hook 实际开启"的鬼故事 —— 别再拆开写。
     */
    public static boolean isOptIn(String key) {
        return K_GLASS_EXTREME.equals(key) || K_NODUCK.equals(key)
                || K_HOTTEMP.equals(key) || K_DIAG.equals(key);
    }

    /** 快照是否已从 provider 落地。未落地时 hook 只能用 baked 默认值。 */
    public static boolean ready() {
        return sSnapshot != null;
    }

    /** hook side: pure memory read — no locks, no binder, no blocking. */
    public static boolean isOn(String key) {
        Map<String, int[]> m = sSnapshot;
        if (m == null) {
            return !isOptIn(key); // preload not landed yet: opt-in keys stay OFF
        }
        int[] v = m.get(key);
        return v == null ? !isOptIn(key) : v[0] != 0;
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
     * 诊断用：把当前快照拼成一行。未就绪时明确说"未就绪"，绝不假装有值 ——
     * 反馈日志里最常见的问题就是分不清"开关是关的"还是"根本没读到"。
     */
    public static String dump() {
        Map<String, int[]> m = sSnapshot;
        if (m == null) {
            return "(快照未就绪，hook 侧按默认值运行)";
        }
        StringBuilder sb = new StringBuilder();
        for (String k : KEYS) {
            int[] v = m.get(k);
            sb.append(k).append('=').append(v == null ? "?" : v[0]).append(' ');
        }
        for (String k : DIY_KEYS) {
            int[] v = m.get(k);
            sb.append(k).append('=').append(v == null ? "?" : v[0]).append(' ');
        }
        return sb.toString().trim();
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
                    // system_server 读不到模块 provider 是**预期**的（SELinux），
                // 记成 E 级错误只会在排查时误导人 —— 降为警告并写明原因
                Diag.warn("Cfg preload gave up; staying default-ON"
                        + " (system_server 属预期；其他进程说明 provider 不可达)");
                    return;
                }
                if (!watchChanges) {
                    Diag.log("Cfg frozen for process lifetime (diag still live)");
                    // v2.3.0: even frozen scopes keep ONE live value — the diagnostics
                    // switch. It has to take effect the moment the user flips it
                    // (asking them to restart SystemUI just to turn logging on
                    // would be absurd), and refreshing a single key cannot disturb
                    // the baked-in glass parameters.
                    registerObserver(false);
                    return;
                }
                registerObserver(true);
            }
        }, "HAS-CfgPreload");
        t.setDaemon(true);
        t.start();
    }

    /**
     * @param full true  = 刷新整份快照（installer/system_server 线）
     *             false = 只刷新诊断开关（玻璃线：快照保持冻结）
     */
    private static void registerObserver(final boolean full) {
        try {
            Context ctx = AndroidAppHelper.currentApplication();
            if (ctx == null) {
                Diag.warn("Cfg: no context, observer skipped (snapshot stays at initial load)");
                return;
            }
            HandlerThread ht = new HandlerThread("HAS-CfgObs");
            ht.start();
            ctx.getContentResolver().registerContentObserver(
                    CfgProvider.URI, true, new ContentObserver(new Handler(ht.getLooper())) {
                @Override
                public void onChange(boolean selfChange) {
                    // this thread holds no system locks — safe to do binder work
                    if (full) {
                        loadOnce();
                    } else {
                        refreshDiag();
                    }
                }
            });
            Diag.log("Cfg watching provider changes (" + (full ? "full" : "diag only") + ")");
        } catch (Throwable t) {
            Diag.err("Cfg observer failed", t);
        }
    }

    // ==================== 诊断开关的实时通道 (v2.3.0) ====================

    /** -1 = 还没从 provider 收到过实时值（此时退回快照里的那份）。 */
    private static volatile int sDiagLive = -1;

    /** 诊断开关是否打开。无 root：走 prefs→provider→observer 这条链。 */
    public static boolean diagOn() {
        int live = sDiagLive;
        if (live >= 0) {
            return live != 0;
        }
        Map<String, int[]> m = sSnapshot;
        if (m != null) {
            int[] v = m.get(K_DIAG);
            return v != null && v[0] != 0;
        }
        return false;
    }

    /** 只取 diag 一个键，不碰冻结的玻璃快照。 */
    private static void refreshDiag() {
        Context ctx = null;
        try {
            ctx = AndroidAppHelper.currentApplication();
        } catch (Throwable ignored) {
        }
        if (ctx == null) {
            return;
        }
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(
                    CfgProvider.URI, null, null, null, null);
            if (c == null) {
                return;
            }
            int ki = c.getColumnIndex("key");
            int vi = c.getColumnIndex("val");
            if (ki < 0 || vi < 0) {
                return;
            }
            while (c.moveToNext()) {
                if (K_DIAG.equals(c.getString(ki))) {
                    sDiagLive = c.getInt(vi);
                    break;
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (c != null) {
                try {
                    c.close();
                } catch (Throwable ignored) {
                }
            }
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
            Diag.state("Cfg via provider", m);
        }
        sSnapshot = m;
        return true;
    }
}
