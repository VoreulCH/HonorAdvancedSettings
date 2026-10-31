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
     * 已排队/已上报的**包名**集合（不是全局布尔）。
     *
     * <p>一个进程可以承载多个作用域包名 —— iaware 进程里 LSPosed 会分别按
     * {@code com.hihonor.powergenie} 和 {@code com.hihonor.iaware} 各回调一次。
     * 早先用单一 sDone 标志时，先完成的那条会把后一条挡在门外，probe 里就只剩
     * 一个包名，界面于是随机显示"温控服务未生效"。按包名去重才稳。
     */
    private static final java.util.HashSet<String> sQueued = new java.util.HashSet<String>();

    /** 40 × 500ms = 20s：足够覆盖开机时 provider 冷启动的窗口。 */
    private static final int MAX_TRIES = 40;

    private Probe() {
    }

    public static void report(final String pkg) {
        synchronized (sQueued) {
            if (!sQueued.add(pkg)) {
                return; // 同包名重复回调（Vector 会这样）：只报一次
            }
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                Throwable last = null;
                boolean contextSeen = false;
                for (int i = 0; i < MAX_TRIES; i++) {
                    Context c = null;
                    try {
                        c = AndroidAppHelper.currentApplication();
                    } catch (Throwable ignored) {
                    }
                    if (c != null) {
                        contextSeen = true;
                        try {
                            ContentValues v = new ContentValues();
                            v.put(CfgProvider.COL_PKG, pkg);
                            v.put(CfgProvider.COL_VER, BuildConfig.VERSION_CODE);
                            Uri r = c.getContentResolver().insert(CfgProvider.URI_PROBE, v);
                            if (r != null) {
                                Diag.log("probe reported: " + pkg + " @"
                                        + BuildConfig.VERSION_NAME
                                        + (i > 0 ? " (try " + (i + 1) + ")" : ""));
                                return;
                            }
                            // 返回 null = provider 明确拒绝（包名不在 Cfg.SCOPES 白名单里），
                            // 重试多少次都一样，立刻报出来省得界面莫名"未生效"
                            Diag.err("probe rejected by provider: " + pkg
                                    + " (Cfg.SCOPES 里缺这个包名?)", null);
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
                        + (contextSeen ? " (provider unreachable in 20s)" : " (Application not ready in 20s)"),
                        last);
            }
        }, "HAS-Probe-" + shortName(pkg));
        t.setDaemon(true);
        t.start();
    }

    private static String shortName(String pkg) {
        int i = pkg.lastIndexOf('.');
        return i >= 0 && i < pkg.length() - 1 ? pkg.substring(i + 1) : pkg;
    }
}
