package io.github.voreulch.honoradvanced;

import android.app.AndroidAppHelper;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Process;

import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import de.robv.android.xposed.XposedBridge;

/**
 * 诊断日志（v2.3.0）。
 *
 * <p>目标：反馈问题时打开一个开关，就能从 LSPosed 模块日志里拿到完整的运行画像 ——
 * 模块/框架/宿主版本、进程身份、开关快照、每一个钩子的挂载结果、关键运行事件、
 * 异常堆栈 —— 不用再让用户复述环境，也不用让它自己回忆"哪个作用域没勾"。
 *
 * <p>开关（跨进程、即时生效，**不需要 root**）：
 * <ul>
 *   <li>关于页写模块 prefs → {@link CfgProvider} → 各进程的 ContentObserver，
 *       见 {@link Cfg#diagOn()}</li>
 *   <li>system_server 读不到模块 provider（SELinux），改用 {@link #setEssential}
 *       精简常开：只输出挂载结果与警告</li>
 * </ul>
 *
 * <p>关闭时只保留两类输出，避免污染 LSPosed 日志：
 * <ol>
 *   <li>每个进程一行的加载横幅（装机验证靠它确认模块进了哪个进程）</li>
 *   <li>错误 —— 一行精简；开启诊断后才附完整堆栈</li>
 * </ol>
 *
 * <p>开销：开关是 1s TTL 的 volatile 读 + uptimeMillis，关闭时每次调用只有几次内存
 * 访问。温度回调每秒数次也不会成为负担。
 */
public final class Diag {

    private static final String TAG = "[HAS-Diag]";
    private static final String BANNER_TAG = "[HAS]";

    private Diag() {
    }

    // ==================== 开关 ====================

    private static volatile boolean sOn = false;
    private static volatile long sOnAt = 0L;
    private static volatile Method sPropGet;

    /** true = 诊断日志开启。1s 内重复调用直接返回缓存值。 */
    public static boolean on() {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - sOnAt < 1000L) {
            return sOn;
        }
        boolean v = false;
        try {
            // 用 diagOn() 而不是 isOn()——后者在快照未就绪时默认 true，
            // 诊断日志绝不能在还没确认的时候自己打开。
            v = Cfg.diagOn();
        } catch (Throwable ignored) {
        }
        sOn = v;
        sOnAt = now;
        return v;
    }

    /**
     * system_server 专用：它读不到模块 provider（SELinux），Cfg 快照永远是空的，
     * 诊断开关在那儿等于没有。这个模式下只保留低体积、高价值的输出
     * （挂载结果 + 警告），且每个进程只在启动时写一遍。
     */
    private static volatile boolean sEssential = false;

    public static void setEssential(boolean essential) {
        sEssential = essential;
    }

    /** 给本进程一个短标识（systemui / android / packageinstaller / iaware），
     *  日志里一眼看出是哪条线。 */
    private static volatile String sScope = null;

    public static void setScope(String scope) {
        sScope = scope;
    }

    // ==================== 基础输出 ====================

    private static String head(String level) {
        String s = sScope;
        return TAG + level + (s == null ? "" : "[" + s + "]") + " ";
    }

    /**
     * 诊断信息：仅在开关打开时输出。
     *
     * <p>压缩策略（v2.3.0，用户要求）：同一条消息连续重复时**不再逐条刷**，
     * 而是累计到 [FLUSH_MS] 窗口结束（或下一条不同消息到来）时补一行
     * {@code 原消息   *N}，N 是这轮出现的总次数。玻璃线的高频回调
     * （每个图标、每次半径计算）因此从每秒几十行压成一行。
     *
     * <p>另有一道每秒条数上限，挡住"内容都不同但量很大"的情况，
     * 被丢掉的条数在下一秒开头汇总一行。
     */
    private static final int MAX_PER_SEC = 50;
    private static final long FLUSH_MS = 1500L;

    private static final Object LOG_LOCK = new Object();
    private static String sPendingMsg = null;
    private static int sPendingCount = 0;
    private static long sPendingAt = 0L;
    private static long sSecAt = 0L;
    private static int sSecCount = 0;
    private static int sDropped = 0;

    public static void log(String msg) {
        if (msg == null || !on()) {
            return;
        }
        long now = android.os.SystemClock.uptimeMillis();
        synchronized (LOG_LOCK) {
            // 同一条消息：累计次数，窗口满了才补一行
            if (msg.equals(sPendingMsg)) {
                sPendingCount++;
                if (now - sPendingAt >= FLUSH_MS) {
                    flushPending();
                    sPendingMsg = msg;
                    sPendingCount = 1;
                    sPendingAt = now;
                }
                return;
            }
            if (sPendingMsg != null) {
                flushPending();
            }
            long sec = now / 1000L;
            if (sec != sSecAt) {
                if (sDropped > 0) {
                    XposedBridge.log(head("D") + "（另有 " + sDropped + " 条因限流省略）");
                }
                sSecAt = sec;
                sSecCount = 0;
                sDropped = 0;
            }
            if (sSecCount >= MAX_PER_SEC) {
                sDropped++;
                return;
            }
            sSecCount++;
            XposedBridge.log(head("D") + msg);
            sPendingMsg = msg;
            sPendingCount = 1;
            sPendingAt = now;
        }
    }

    /** 把累计的重复次数补成一行（N 含首次）。 */
    private static void flushPending() {
        int extra = sPendingCount - 1;
        if (extra > 0) {
            if (sPendingMsg.length() <= 80) {
                XposedBridge.log(head("D") + sPendingMsg + "   *" + sPendingCount);
            } else {
                XposedBridge.log(head("D") + "  ↑ 上一条重复 " + extra + " 次（共 " + sPendingCount + " 次）");
            }
        }
        sPendingMsg = null;
        sPendingCount = 0;
    }

    /** 运行事件（与 log 同义，语义上标记"发生了什么"，方便排查时 grep）。 */
    public static void event(String msg) {
        log(msg);
    }

    public static void warn(String msg) {
        if (!on() && !sEssential) {
            return;
        }
        XposedBridge.log(head("W") + msg);
    }

    /**
     * 错误：**始终输出一行**（诊断关闭时也要能看出哪一步没装上），
     * 诊断开启时附完整堆栈。
     */
    public static void err(String msg, Throwable t) {
        StringBuilder sb = new StringBuilder(head("E")).append(msg);
        if (t != null) {
            sb.append(" : ").append(t.getClass().getSimpleName());
            String m = t.getMessage();
            if (m != null && !m.isEmpty()) {
                sb.append(": ").append(m);
            }
        }
        try {
            XposedBridge.log(sb.toString());
        } catch (Throwable ignored) {
        }
        if (t != null && on()) {
            try {
                XposedBridge.log(android.util.Log.getStackTraceString(t));
            } catch (Throwable ignored) {
            }
        }
    }

    // ==================== 加载横幅 / 环境快照 ====================

    /** 每个进程一行，**不受开关控制**：装机验证靠它确认模块是否进了这个进程。 */
    public static void banner(String pkg) {
        try {
            XposedBridge.log(BANNER_TAG + " 药师 v" + BuildConfig.VERSION_NAME
                    + " (" + BuildConfig.VERSION_CODE + ") in " + pkg);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 打印一次完整环境快照（仅在诊断开启时）。
     * 放在各 scope 的入口调用一次 —— 之后同一进程不再重复。
     */
    public static void env(String pkg, String appDir, ClassLoader cl) {
        if (!on()) {
            return;
        }
        log("================ 诊断快照 ================");
        log("模块      : 药师 " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")");
        log("框架      : Xposed API " + frameworkApi() + " / " + frameworkClass());
        log("进程      : " + pkg + "  pid=" + Process.myPid() + "  uid=" + Process.myUid()
                + "  cmd=" + cmdline());
        log("宿主      : " + hostInfo(pkg, appDir));
        log("系统      : Android " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")"
                + "  " + Build.MANUFACTURER + " " + Build.MODEL);
        log("ROM       : " + Build.DISPLAY + "  patch=" + Build.VERSION.SECURITY_PATCH);
        log("ClassLoader: " + (cl == null ? "null" : cl.getClass().getName()));
        log("属性      : " + Cfg.PROP_HOTTEMP + "=" + prop(Cfg.PROP_HOTTEMP));
        log("诊断通道  : cfg.diagOn=" + Cfg.diagOn());
        log("开关快照  : " + Cfg.dump());
        log("=========================================");
    }

    private static String frameworkApi() {
        try {
            return String.valueOf(XposedBridge.getXposedVersion());
        } catch (Throwable t) {
            return "?";
        }
    }

    private static String frameworkClass() {
        try {
            Class<?> c = XposedBridge.class;
            return c.getProtectionDomain() != null && c.getProtectionDomain().getCodeSource() != null
                    ? String.valueOf(c.getProtectionDomain().getCodeSource().getLocation())
                    : "unknown";
        } catch (Throwable t) {
            return "unknown";
        }
    }

    /** 读 /proc/self/cmdline —— 进程真实名字（system_server 与包名不同，靠它区分）。 */
    private static String cmdline() {
        try (InputStream in = new FileInputStream("/proc/self/cmdline")) {
            byte[] b = new byte[256];
            int n = in.read(b);
            if (n <= 0) {
                return "?";
            }
            int end = 0;
            while (end < n && b[end] != 0) {
                end++;
            }
            return new String(b, 0, end, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return "?";
        }
    }

    private static volatile Context sCtx;

    /**
     * 只缓存**成功**的结果。
     *
     * <p>早先这里有个 sCtxTried：第一次拿不到就记成"永远没有"。但 env() 是在
     * handleLoadPackage 里调的，那时 Application 往往还没建好，于是宿主版本
     * 永远显示 n/a —— 恰好是排查时最想看到的那一项。
     */
    private static Context ctx() {
        Context c = sCtx;
        if (c != null) {
            return c;
        }
        try {
            c = AndroidAppHelper.currentApplication();
        } catch (Throwable ignored) {
        }
        if (c == null) {
            // system_server 没有 Application：借 ActivityThread 的 system context
            try {
                Class<?> at = Class.forName("android.app.ActivityThread");
                Object th = at.getMethod("currentActivityThread").invoke(null);
                if (th != null) {
                    c = (Context) at.getMethod("getSystemContext").invoke(th);
                }
            } catch (Throwable ignored) {
            }
        }
        if (c != null) {
            sCtx = c;
        }
        return c;
    }

    private static String hostInfo(String pkg, String appDir) {
        StringBuilder sb = new StringBuilder(pkg);
        sb.append("  apk=").append(appDir == null ? "?" : appDir);
        try {
            Context c = ctx();
            if (c != null) {
                PackageInfo pi = c.getPackageManager().getPackageInfo(pkg, 0);
                sb.append("  ver=").append(pi.versionName)
                        .append('(').append(pi.getLongVersionCode()).append(')');
            } else {
                sb.append("  ver=n/a(no context)");
            }
        } catch (Throwable t) {
            sb.append("  ver=n/a(").append(t.getClass().getSimpleName()).append(')');
        }
        return sb.toString();
    }

    // ==================== 钩子挂载点 ====================

    /**
     * 记录一组钩子的挂载结果。
     *
     * @param target 目标（类#方法 / 类），人类可读
     * @param hooked 实际挂上的数量
     * @param expect 预期数量（0 = 不确定，只要 hooked>0 就算正常）
     */
    public static void mount(String target, int hooked, int expect) {
        if (!on() && !sEssential) {
            return;
        }
        if (hooked <= 0) {
            warn("MOUNT " + target + " -> 0/" + (expect > 0 ? expect : 1)
                    + "  ✗ 未命中（宿主版本可能已变）");
        } else if (expect > 0 && hooked < expect) {
            warn("MOUNT " + target + " -> " + hooked + "/" + expect + "  ⚠ 部分命中");
        } else {
            log("MOUNT " + target + " -> " + hooked + (expect > 0 ? "/" + expect : "") + "  ✓");
        }
    }

    /** 开关/组件状态一行。 */
    public static void state(String name, Object value) {
        if (!on()) {
            return;
        }
        XposedBridge.log(head("S") + name + " = " + value);
    }

    // ==================== 属性读取 ====================

    private static Method propMethod() throws Exception {
        Method m = sPropGet;
        if (m == null) {
            m = Class.forName("android.os.SystemProperties")
                    .getMethod("get", String.class, String.class);
            sPropGet = m;
        }
        return m;
    }

    /** 读系统属性；失败返回 "?"（不会抛）。 */
    public static String prop(String name) {
        try {
            Object v = propMethod().invoke(null, name, "");
            return v == null ? "" : String.valueOf(v);
        } catch (Throwable t) {
            return "?";
        }
    }
}
