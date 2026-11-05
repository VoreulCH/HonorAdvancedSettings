package io.github.voreulch.honoradvanced;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 荣耀高级设置 — entry dispatcher.
 *
 * <p>Routes each scoped process to its hook set:
 * <ul>
 *   <li>android (system_server) → InstallerHook + MiscHook（热控归一化）</li>
 *   <li>packageinstaller → InstallerHook</li>
 *   <li>powergenie / iaware → MiscHook（PowerGenie 自有温控引擎）</li>
 *   <li>systemui / launcher / photos … → GlassHook（+ systemui 的 MiscHook）</li>
 * </ul>
 *
 * <p>v2.3.0: every scope announces itself through {@link Diag} — one always-on
 * banner line (装机验证靠它), plus a full environment snapshot when the user
 * turned diagnostics on in the About page.
 */
public final class MainHook implements IXposedHookLoadPackage {

    /** 玻璃线的宿主（与 res/values/arrays.xml 的 xposed_scope 对应）。 */
    private static final java.util.HashSet<String> GLASS_TARGETS = new java.util.HashSet<>(
            java.util.Arrays.asList(
                    "com.android.systemui",
                    "com.hihonor.android.launcher",
                    "com.hihonor.desktop.systemui",
                    "com.hihonor.photos"));

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        String pkg = lpparam.packageName;
        ClassLoader cl = lpparam.classLoader;

        if ("io.github.voreulch.honoradvanced".equals(pkg)) {
            return; // module's own app process: no hooks
        }

        if ("android".equals(pkg)) {
            // system_server 无法走 provider 自证（会挂住），这里改用系统属性留个痕迹：
            // 有 root 时写 persist.sys.has.sysprobe，之后 adb shell getprop 就能
            // 直接确认它到底有没有被注入，不用再靠猜。
            markSystemServerInjected();
            Diag.setEssential(true); // 读不到模块 provider，只留挂载结果/警告
            enter("system_server", pkg, lpparam);
            Cfg.startPreload(true); // observer: downgrade switch without reboot
            InstallerHook.initSystemServer(cl);
            MiscHook.initSystemServer(cl); // thermal normalization (high-temp bypass)
            return;
        }
        if ("com.android.packageinstaller".equals(pkg)) {
            // 这个进程的净化 hook 每次挂载都会打一行 Diag.log，但 Diag 默认只在
            // 诊断开关打开时输出 —— 用户反馈"按钮偶现"时，日志里只有版本行，
            // 根本判断不了那次到底挂没挂上。打开 essential，让它常开。
            Diag.setEssential(true);
            enter("installer", pkg, lpparam);
            Cfg.startPreload(true); // observer: "next install" picks up changes
            InstallerHook.init(cl, lpparam.appInfo == null ? null : lpparam.appInfo.sourceDir);
            return;
        }
        // 反诈组件：securityserver 是 uid=system 的系统应用，采集与上报都在这个进程里。
        if ("com.hihonor.securityserver".equals(pkg)) {
            // 这个进程读不到模块 provider 时要能看见原因（warn 默认被过滤）
            Diag.setEssential(true);
            enter("securityserver", pkg, lpparam);
            // ⚠ 必须自己 startPreload：这个分支在下面那行全局的
            // Cfg.startPreload(false) **之前**就 return 了，不补的话 sSnapshot
            // 永远是 null，而 isOn 对 opt-in 键在未就绪时返回 false ——
            // 结果是钩子全挂上了、但每次回调都在 `if (!Cfg.isOn(K_NOFRAUD)) return`
            // 那一行提前退出，**一个都拦不住**，日志里还看不出异常。
            // false = 玻璃类 scope：快照冻结，改开关要重启本进程才生效。
            Cfg.startPreload(false);
            MiscHook.initSecurityServer(cl,
                    lpparam.appInfo != null ? lpparam.appInfo.sourceDir : null,
                    // securityserver 是**多 split** 应用（实测 8 个 APK：
                    // HnSecurityServer / BioInfoSync / HnTrustCircleManager /
                    // HnAntiTheftDetectionService / HnLocalFaceIdAuth /
                    // HnUniKeystoreService / HnUnifiedAuthService / HnDeviceGroupAuth）。
                    // 只把 sourceDir（主包）交给 DexKit 扫会漏掉分片里的类 ——
                    // 实测 HnUnifiedAuthService.apk 里就有 fraud_detect_* / fraudcall_*
                    // 这些反诈相关字符串。分片里的类同样由主 ClassLoader 加载，
                    // 只要 apkPath 传对就能扫到。
                    lpparam.appInfo != null ? lpparam.appInfo.splitSourceDirs : null);
            return;
        }

        // 感知服务本体：它自己不但是数据源，还有 41 处 fraud 逻辑
        // （诈骗电话 / 诈骗应用 / 行为事件集）和独立上报通道。只拦 securityserver
        // 等于只关了一个水龙头，这里补上第二个。
        // ⚠ 它是可更新应用，类名随 OTA 变 → MiscHook 里全部用动态锚点。
        if ("com.hihonor.awareness".equals(pkg)) {
            Diag.setEssential(true);   // 首次接入，必须能看见挂载结果
            enter("awareness", pkg, lpparam);
            Cfg.startPreload(false);
            MiscHook.initAwareness(cl,
                    lpparam.appInfo != null ? lpparam.appInfo.sourceDir : null,
                    lpparam.appInfo != null ? lpparam.appInfo.splitSourceDirs : null);
            return;
        }

        if ("com.hihonor.powergenie".equals(pkg) || "com.hihonor.iaware".equals(pkg)) {
            // PowerGenie's own thermal engine (reads the HAL directly, so the
            // system_server status normalisation can never reach it). Its
            // services actually run inside the com.hihonor.iaware process
            // (dumpsys: powergenie ServiceRecord processName=com.hihonor.iaware),
            // hence both packages are scoped and routed here.
            enter("powergenie", pkg, lpparam);
            // ⚠ 这个分支**一直没有 startPreload**。
            // 后果和 修的反诈分支一模一样：
            //   sSnapshot 永远是 null → isOn(K_NOCLOUD) 对 opt-in 键返回 false
            //   → 云控屏蔽（读/收 + 落盘两层同键）的每一处门控都在
            //     `if (!Cfg.isOn(Cfg.K_NOCLOUD)) return` / `isOn(Cfg.K_NOWRITE)` 那一行提前退出
            //     → **云控屏蔽从头到尾没生效过**。
            // 日志里只看得见"云控拦截至 23 处"（挂载计数），看不到任何一条
            // 实际拦截记录 —— 正好把这件事掩盖了。
            // 走 true：用户在设置里改开关后，下一次配置读取立即按新值判定。
            Cfg.startPreload(true);
            MiscHook.initPowerGenie(cl, lpparam.appInfo != null ? lpparam.appInfo.sourceDir : null);
            return;
        }

        // 玻璃线只认这四个宿主。别的一律不碰 —— 同一个 uid 1000 进程里
        // 还挂着 com.hihonor.game.kitserver 之类的包名（LSPosed 会为每个包名
        // 各回调一次），对它们跑玻璃钩子只会白刷几十条 ClassNotFound，
        // 还会把诊断日志的 scope 前缀覆盖成别人的名字。
        if (!GLASS_TARGETS.contains(pkg)) {
            return;
        }

        // glass targets — hooks stay mounted, every callback gates at
        // runtime on the frozen config snapshot (loaded once by the
        // background preload thread; restart the scope to apply changes)
        Cfg.startPreload(false);
        if ("com.android.systemui".equals(pkg)) {
            enter("systemui", pkg, lpparam);
            MiscHook.initSystemUI(cl); // misc line: notification-ducking blocker
            BannerContrastHook.init(cl); // 222: heads-up text contrast by real background
        } else if ("com.hihonor.android.launcher".equals(pkg)) {
            enter("launcher", pkg, lpparam);
        } else if ("com.hihonor.photos".equals(pkg)) {
            enter("photos", pkg, lpparam);
        } else {
            enter("desktop", pkg, lpparam);
        }
        new GlassHook().handleLoadPackage(lpparam);
    }

    /** 每个 scope 的公共开场：横幅恒定输出，环境快照只在诊断开关打开时输出。 */
    /**
     * 给 system_server 留一个「已被注入」的痕迹。
     *
     * 它不能像其他进程那样往模块 provider 里 insert（会挂住），所以改用系统属性。
     * 有 root 能写成功，之后 adb shell getprop persist.sys.has.sysprobe 可查；
     * 没 root 静默跳过，不影响功能。
     */
    private static void markSystemServerInjected() {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            java.lang.reflect.Method set = sp.getMethod("set", String.class, String.class);
            // 属性值加本次开机的 boot_id。persist 属性重启后不会消失，
            // 旧值只有版本号时 UI 会把"取消 android 作用域后重启"误判成已生效。
            String boot = "";
            try {
                java.io.BufferedReader r = new java.io.BufferedReader(
                        new java.io.FileReader("/proc/sys/kernel/random/boot_id"));
                try {
                    String line = r.readLine();
                    boot = line == null ? "" : line;
                } finally {
                    r.close();
                }
            } catch (Throwable ignored) {
            }
            boot = boot == null ? "" : boot.trim();
            // 框架代次：软重启不改变 boot_id，必须另给一个"这次 system_server
            // 真的重新加载了模块"的凭证。用 pid + startAt + 墙钟时间，三者任一
            // 变化都说明是新进程；宿主上报时带上它，首页按 epoch 是否一致判新鲜。
            long startAt = procStartAt();
            String epoch = android.os.Process.myPid() + ":" + startAt + ":"
                    + System.currentTimeMillis();
            // 217：写后必须回读。写不进去 / 读回不一致 = 这次不能算已注入，
            // epoch_state 写 FAIL，首页据此判红；绝不允许"写了就当成功"。
            set.invoke(null, "persist.sys.has.framework_epoch", epoch);
            String epochBack = getProp("persist.sys.has.framework_epoch");
            if (epochBack == null || !epoch.equals(epochBack)) {
                set.invoke(null, "persist.sys.has.epoch_state", "FAIL:epoch_readback");
                Diag.err("epoch readback mismatch expected=" + epoch
                        + " got=" + epochBack, null);
                return;
            }
            // 新代次已确认，才清掉软重启 pending；清不掉就继续 pending=1，
            // 首页不会把旧记录当新鲜（宁可黄，也不假绿）。
            set.invoke(null, "persist.sys.has.epoch_pending", "0");
            String pending = getProp("persist.sys.has.epoch_pending");
            if (pending == null || !"0".equals(pending)) {
                set.invoke(null, "persist.sys.has.epoch_state", "FAIL:pending_clear");
                Diag.err("epoch pending clear failed got=" + pending, null);
                return;
            }
            set.invoke(null, "persist.sys.has.epoch_state", "OK");
            String probe = io.github.voreulch.honoradvanced.BuildConfig.VERSION_NAME
                    + "(" + io.github.voreulch.honoradvanced.BuildConfig.VERSION_CODE + ")|"
                    + boot + "|" + epoch;
            set.invoke(null, "persist.sys.has.sysprobe", probe);
            String probeBack = getProp("persist.sys.has.sysprobe");
            if (probeBack == null || !probe.equals(probeBack)) {
                set.invoke(null, "persist.sys.has.epoch_state", "FAIL:sysprobe_readback");
                Diag.err("sysprobe readback mismatch expected=" + probe
                        + " got=" + probeBack, null);
            }
        } catch (Throwable t) {
            try {
                Class<?> sp2 = Class.forName("android.os.SystemProperties");
                java.lang.reflect.Method set2 = sp2.getMethod("set", String.class, String.class);
                set2.invoke(null, "persist.sys.has.epoch_state", "FAIL:exception");
            } catch (Throwable ignored) {
            }
            Diag.err("epoch write/readback exception", t);
        }
    }

    private static String getProp(String key) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Object v = sp.getMethod("get", String.class, String.class)
                    .invoke(null, key, "");
            return v == null ? "" : String.valueOf(v).trim();
        } catch (Throwable t) {
            return null;
        }
    }

    /** /proc/self/stat 第 22 列（开机后的 clock ticks）；读不到返回 0。 */
    private static long procStartAt() {
        java.io.BufferedReader r = null;
        try {
            r = new java.io.BufferedReader(new java.io.FileReader("/proc/self/stat"));
            String line = r.readLine();
            if (line == null) return 0L;
            int close = line.lastIndexOf(')');
            if (close < 0 || close + 2 >= line.length()) return 0L;
            String[] rest = line.substring(close + 2).trim().split("\\s+");
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

    private static void enter(String scope, String pkg, XC_LoadPackage.LoadPackageParam lpparam) {
        Diag.setScope(scope);
        Diag.banner(pkg);
        Diag.env(pkg,
                lpparam.appInfo == null ? null : lpparam.appInfo.sourceDir,
                lpparam.classLoader);
        // 向模块界面自证"这个作用域已经跑起来了"（无 root、行为级验证）。
        // system_server 除外：它 binder 到普通 app 的 provider 会挂住。
        if (!"android".equals(pkg)) {
            Probe.report(pkg);
        }
    }
}
