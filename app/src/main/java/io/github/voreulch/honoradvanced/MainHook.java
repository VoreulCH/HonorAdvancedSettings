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
            Diag.setEssential(true); // 读不到模块 provider，只留挂载结果/警告
            enter("system_server", pkg, lpparam);
            Cfg.startPreload(true); // observer: downgrade switch without reboot
            InstallerHook.initSystemServer(cl);
            MiscHook.initSystemServer(cl); // thermal normalization (high-temp bypass)
            return;
        }
        if ("com.android.packageinstaller".equals(pkg)) {
            enter("installer", pkg, lpparam);
            Cfg.startPreload(true); // observer: "next install" picks up changes
            InstallerHook.init(cl, lpparam.appInfo == null ? null : lpparam.appInfo.sourceDir);
            return;
        }
        if ("com.hihonor.powergenie".equals(pkg) || "com.hihonor.iaware".equals(pkg)) {
            // PowerGenie's own thermal engine (reads the HAL directly, so the
            // system_server status normalisation can never reach it). Its
            // services actually run inside the com.hihonor.iaware process
            // (dumpsys: powergenie ServiceRecord processName=com.hihonor.iaware),
            // hence both packages are scoped and routed here.
            enter("powergenie", pkg, lpparam);
            MiscHook.initPowerGenie(cl);
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
