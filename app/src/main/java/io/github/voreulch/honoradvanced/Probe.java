package io.github.voreulch.honoradvanced;

import android.app.AndroidAppHelper;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;

/**
 * 作用域自证上报（v2.3.0）。
 *
 * <p>被 hook 的进程在启动时向模块自己的 {@link CfgProvider} insert 一行，
 * 落盘发生在模块 app 进程里。这样模块界面**不需要 root、不需要读 LSPosed 的
 * 数据库**，就能知道某个作用域是不是真的加载了模块 —— 而且验证的是"跑起来了"，
 * 不是"勾选过"：勾了却没重启进程时，界面会诚实地显示未生效。
 *
 * <p>线程与时机：handleLoadPackage 阶段 {@code currentApplication()} 往往还是
 * null（进程刚 fork），所以在一个 daemon 线程里轮询等 Application 就绪。
 * binder 调用只发生在这条后台线程上，绝不进 hook 回调 —— 这是 Cfg 头注释里
 * 那次 keyguard 死锁换来的铁律。
 *
 * <p><b>为什么要重试</b>（2026-10-31 开机遇到的真问题）：重启手机时各作用域在
 * 开机第 10 秒就加载了，而模块 app 的 provider 那一刻还没被系统拉起，insert 抛
 * {@code IllegalArgumentException: Unknown URL}。早先一次失败就 return，整轮开机
 * 都收不到上报，界面于是显示"模块未生效"——纯误报。现在失败后每 500ms 重试，
 * 最多 20 秒，覆盖开机窗口期。
 *
 * <p>system_server 不调用这里：它一 binder 到普通 app 的 provider 就会挂住
 * （SELinux + 权限检查），历史上已经踩过一次。
 */
public final class Probe {

    /**
     * 已排队/已上报的键：**包名 + 进程 PID**（不是全局布尔，也不是只按包名）。
     *
     * <p>两个修正：
     * <ol>
     *   <li>只按包名去重会把同包名的多个进程（awareness 主进程 + :dp.ofdb）
     *       当成重复回调，先到的进程失败后，后到的直接被挡 —— 表现为界面
     *       永久显示"感知服务未生效"，实际钩子已经挂上。</li>
     *   <li>失败后必须把键移除，让 Vector 的重复回调还能再试一次；不移除就是
     *       一次超时判死刑。</li>
     * </ol>
     */
    private static final java.util.HashSet<String> sQueued = new java.util.HashSet<String>();

    /**
     * 120 × 500ms = 60s。
     *
     * <p>实测开机窗口：本机 powergenie/iaware 在 2026-11-03 00:42 的日志里
     * 分别等到 try 30 / try 32（约 15-16 秒）才成功；旧上限 40 次（20 秒）
     * 只差几秒就会放弃。用户设备（WKL/AAP）开机更慢，20 秒必然不够，
     * 这正是"作用域勾了仍提示失效"的直接根因。
     */
    private static final int MAX_TRIES = 120;

    private Probe() {
    }

    public static void report(final String pkg) {
        final String key = pkg + "#" + android.os.Process.myPid();
        synchronized (sQueued) {
            if (!sQueued.add(key)) {
                return; // 同进程同包名重复回调（Vector 会这样）：只报一次
            }
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                Throwable last = null;
                boolean contextSeen = false;
                Context lastCtx = null;
                for (int i = 0; i < MAX_TRIES; i++) {
                    Context c = appContext();
                    if (c != null) {
                        contextSeen = true;
                        lastCtx = c;
                        try {
                            if (push(c, pkg)) {
                                Diag.log("probe reported: " + pkg + " @"
                                        + BuildConfig.VERSION_NAME + " boot=" + bootId()
                                        + (i > 0 ? " (try " + (i + 1) + ")" : ""));
                                // 成功后开始每分钟心跳，UI 判活靠"本次开机 + 最近心跳"。
                                startHeartbeat(pkg, c);
                                return;
                            }
                            // 返回 null = provider 明确拒绝（包名不在 Cfg.SCOPES 白名单里），
                            // 重试多少次都一样，立刻报出来省得界面莫名"未生效"
                            Diag.err("probe rejected by provider: " + pkg
                                    + " (Cfg.SCOPES 里缺这个包名?)", null);
                            synchronized (sQueued) {
                                sQueued.remove(key);
                            }
                            return;
                        } catch (Throwable t) {
                            // provider 还没起来（开机窗口期）——记下来继续重试
                            last = t;
                        }
                    }
                    try {
                        Thread.sleep(500L);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                Diag.err("probe gave up: " + pkg
                        + (contextSeen ? " (provider unreachable in 60s)" : " (Application not ready in 60s)"),
                        last);
                if (lastCtx != null) {
                    // 首次 60 秒窗口失败（典型：开机未解锁，CE 存储/provider 不可用）。
                    // 不能就此判死：不释放占位，转入每分钟一次的慢重试；
                    // 解锁后 provider 可达时会自动上报，无需重启进程。
                    startSlowRetry(pkg, lastCtx);
                    return;
                }
                // 连 Context 都没拿到：释放占位，让 Vector 的后续回调还能再试。
                synchronized (sQueued) {
                    sQueued.remove(key);
                }
            }
        }, "HAS-Probe-" + shortName(pkg));
        t.setDaemon(true);
        t.start();
    }

    /**
     * 慢重试：60 秒一次，直到 provider 可达并上报成功。
     *
     * <p>provider 明确拒绝（返回 null，包名不在白名单 / 版本校验不过）才停；
     * 临时异常（开机未解锁、provider 未起）继续等，不烧电。
     */
    private static void startSlowRetry(final String pkg, final Context c) {
        Diag.note("probe slow-retry queued: " + pkg
                + "（首次上报失败，60s/次等 provider 可用；解锁后自动重试）");
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                int tries = 0;
                while (true) {
                    try {
                        Thread.sleep(60_000L);
                    } catch (InterruptedException e) {
                        return;
                    }
                    tries++;
                    try {
                        if (push(c, pkg)) {
                            Diag.note("probe slow-retry ok: " + pkg + " @"
                                    + BuildConfig.VERSION_NAME + " boot=" + bootId()
                                    + " (try " + tries + ")");
                            startHeartbeat(pkg, c);
                            return;
                        }
                        Diag.log("probe slow-retry rejected: " + pkg);
                        return;
                    } catch (Throwable ignored) {
                        // provider 仍不可达（未解锁/未起）：继续等下一个周期。
                    }
                }
            }
        }, "HAS-ProbeSlow-" + shortName(pkg));
        t.setDaemon(true);
        t.start();
    }

    /** 本次开机的 boot_id（每次重启变化，用来判"记录是不是这次开机写的"）。 */
    private static volatile String sBootId;

    private static String bootId() {
        String b = sBootId;
        if (b != null) return b;
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.FileReader("/proc/sys/kernel/random/boot_id"));
            try {
                b = r.readLine();
            } finally {
                r.close();
            }
        } catch (Throwable t) {
            b = "";
        }
        sBootId = b == null ? "" : b.trim();
        return sBootId;
    }

    /**
     * 进程启动时刻：/proc/self/stat 第 22 列（starttime，开机后的 clock ticks）。
     *
     * <p>以前用隐藏 API Process.getStartElapsedTime()，但这台设备上恒为 0，
     * 等于留了个永远为 0 的字段。改成直接读 proc；读不到返回 0，调用方不写该列。
     */
    private static long processStartAt() {
        java.io.BufferedReader r = null;
        try {
            r = new java.io.BufferedReader(new java.io.FileReader("/proc/self/stat"));
            String line = r.readLine();
            if (line == null) return 0L;
            int close = line.lastIndexOf(')');
            if (close < 0 || close + 2 >= line.length()) return 0L;
            String[] rest = line.substring(close + 2).trim().split("\\s+");
            // rest[0] = state（第 3 列）→ starttime（第 22 列）= rest[19]
            if (rest.length <= 19) return 0L;
            return Long.parseLong(rest[19]);
        } catch (Throwable ignored) {
            return 0L;
        } finally {
            if (r != null) {
                try { r.close(); } catch (Throwable ignored) { }
            }
        }
    }

    private static boolean push(Context c, String pkg) {
        ContentValues v = new ContentValues();
        v.put(CfgProvider.COL_PKG, pkg);
        v.put(CfgProvider.COL_VER, BuildConfig.VERSION_CODE);
        v.put(CfgProvider.COL_PID, android.os.Process.myPid());
        v.put(CfgProvider.COL_BOOT, bootId());
        long startAt = processStartAt();
        if (startAt > 0) v.put(CfgProvider.COL_START, startAt);
        v.put(CfgProvider.COL_BEAT, System.currentTimeMillis());
        Uri r = c.getContentResolver().insert(CfgProvider.URI_PROBE, v);
        return r != null;
    }

    /** 每分钟一次心跳；provider 拒绝（身份/版本校验失败）就停，临时不可达则继续。 */
    private static void startHeartbeat(final String pkg, final Context c) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    try {
                        Thread.sleep(60_000L);
                    } catch (InterruptedException e) {
                        return;
                    }
                    try {
                        if (!push(c, pkg)) {
                            Diag.log("probe heartbeat rejected: " + pkg);
                            return;
                        }
                    } catch (Throwable ignored) {
                        // provider 暂时不可达：下个周期再试
                    }
                }
            }
        }, "HAS-ProbeBeat-" + shortName(pkg));
        t.setDaemon(true);
        t.start();
    }

    /** 取当前进程的 Application；AndroidAppHelper 拿不到时走 ActivityThread 兜底。 */
    private static Context appContext() {
        try {
            Context c = AndroidAppHelper.currentApplication();
            if (c != null) return c;
        } catch (Throwable ignored) {
        }
        try {
            Object at = Class.forName("android.app.ActivityThread")
                    .getMethod("currentActivityThread").invoke(null);
            if (at != null) {
                Object app = at.getClass().getMethod("getApplication").invoke(at);
                if (app instanceof Context) return (Context) app;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String shortName(String pkg) {
        int i = pkg.lastIndexOf('.');
        return i >= 0 && i < pkg.length() - 1 ? pkg.substring(i + 1) : pkg;
    }
}
