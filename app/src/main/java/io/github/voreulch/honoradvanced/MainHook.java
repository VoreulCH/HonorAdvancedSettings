package io.github.voreulch.honoradvanced;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 荣耀高级设置 — entry dispatcher.
 * Routes each scoped process to its hook set:
 *   android / packageinstaller        -> InstallerHook (荣耀安装器净化)
 *   systemui / launcher / photos      -> GlassHook (荣耀液态玻璃恢复, Cfg snapshot-gated switches)
 */
public final class MainHook implements IXposedHookLoadPackage {

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        String pkg = lpparam.packageName;
        ClassLoader cl = lpparam.classLoader;

        if ("android".equals(pkg)) {
            XposedBridge.log("[HAS] v2.2.9 in system_server");
            Cfg.startPreload(true); // observer: downgrade switch without reboot
            InstallerHook.initSystemServer(cl);
            MiscHook.initSystemServer(cl); // thermal normalization (high-temp bypass)
            return;
        }
        if ("com.android.packageinstaller".equals(pkg)) {
            XposedBridge.log("[HAS] v2.2.9 in packageinstaller");
            Cfg.startPreload(true); // observer: "next install" picks up changes
            InstallerHook.init(cl, lpparam.appInfo.sourceDir);
            return;
        }
        if ("com.hihonor.powergenie".equals(pkg) || "com.hihonor.iaware".equals(pkg)) {
            // PowerGenie's own thermal engine (reads the HAL directly, so the
            // system_server status normalisation can never reach it). Its
            // services actually run inside the com.hihonor.iaware process
            // (dumpsys: powergenie ServiceRecord processName=com.hihonor.iaware),
            // hence both packages are scoped and routed here.
            XposedBridge.log("[HAS] v2.2.9 in " + pkg + " (powergenie thermal)");
            MiscHook.initPowerGenie(cl);
            return;
        }
        if ("io.github.voreulch.honoradvanced".equals(pkg)) {
            return; // module's own app process: no hooks
        }
        // glass targets — hooks stay mounted, every callback gates at
        // runtime on the frozen config snapshot (loaded once by the
        // background preload thread; restart the scope to apply changes)
        Cfg.startPreload(false);
        if ("com.android.systemui".equals(pkg)) {
            MiscHook.initSystemUI(cl); // misc line: notification-ducking blocker
        }
        new GlassHook().handleLoadPackage(lpparam);
    }
}
