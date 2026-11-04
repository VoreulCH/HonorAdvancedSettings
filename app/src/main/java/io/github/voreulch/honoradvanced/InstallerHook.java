package io.github.voreulch.honoradvanced;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 荣耀安装器净化 — adaptive set .
 * The purify master switch gates every hook; info-card and downgrade hooks
 * also honor their own switches. All values are read through CfgProvider
 * (K_PURIFY is opt-in; default OFF since 3.2.0). Toggling takes effect on
 * the next install, no restart needed.
 *
 * Every hook point resolves ADAPTIVELY at load time: classes via DexKit
 * string anchors (survive class renames) with per-build name fallbacks,
 * methods via parameter-type signature scanning (survive method renames —
 * signatures anchor on stable entity full names like DivideButtonModule /
 * CleanSpeedUpModule / HnControlResult). The pass-result delivery tries the
 * 38.301 recipe (dd1(HnControlResult) via type) then the 14.0.22.x recipe
 * (static factory + single-arg callback). Known builds: 14.0.22.326/327,
 * 14.0.32.303, 14.0.38.301, 14.0.39.301; future OTA reshuffles resolve as
 * long as the anchored
 * entities and callback shapes hold. Glass line hooks live in GlassHook.
 */
public final class InstallerHook {
    private static final String TAG = "[HAS-Purify] ";
    private static String sApk;
    private static boolean purifyOn() {
        // 重启作用域后第一次安装会漏拦 —— installer 进程刚起，
        // Cfg 快照还没从 provider 回来，而 K_PURIFY 是 opt-in（未就绪 = 关），
        // 于是这一单的广告、推荐卡片全部放行。安装是用户主动触发的操作，
        // 等 1.2 秒完全无感（系统本来就要解析 APK），但漏拦一次用户会记很久。
        // 只在这一条线上等；玻璃线千万别照抄，那边是渲染线程。
        if (!Cfg.ready()) {
            Cfg.awaitReady(1200L);
        }
        return Cfg.isOn(Cfg.K_PURIFY);
    }

    /** 14.0.38.301 set — active whenever the master is on, no compat switch. */
    private static boolean defaultPurify() {
        return purifyOn();
    }

    private static boolean on(String key) {
        return purifyOn() && Cfg.isOn(key);
    }

    /**
     * installer 进程是否已经初始化过。
     *
     * <p>Vector 对同一进程可能调用两次 handleLoadPackage，没有这个标记的话
     * 所有 hook 会被挂两遍 —— 每个回调执行两次，自动点弹窗会连点、
     * 计数会翻倍。MiscHook 那边有 sAmHooked，这边之前漏了。
     */
    private static final java.util.concurrent.atomic.AtomicBoolean sInitDone =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** 安装器版本，用于“某钩子在本版本不存在”的日志；惰性读取，失败记 unknown。 */
    private static volatile String sInstallerVer = null;

    private static String ver() {
        String v = sInstallerVer;
        if (v != null) return v;
        v = "unknown";
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object thread = XposedHelpers.callStaticMethod(at, "currentActivityThread");
            android.content.Context sys =
                    (android.content.Context) XposedHelpers.callMethod(thread, "getSystemContext");
            PackageInfo pi = sys.getPackageManager()
                    .getPackageInfo("com.android.packageinstaller", 0);
            if (pi.versionName != null && pi.versionName.length() > 0) v = pi.versionName;
        } catch (Throwable ignored) {
        }
        // unknown 可能是 init 阶段 Application 还没就绪：不要缓存死，
        // 让后续安装时的日志还能再取一次真实版本。
        if (!"unknown".equals(v)) sInstallerVer = v;
        return v;
    }

    /** 类/方法/字段在本版本不存在时的统一日志，不改变控制流。 */
    private static void absent(String feature) {
        Diag.log("[adapt] " + feature + ": not present in " + ver() + ", skipped");
    }

    public static void init(ClassLoader cl, String apkPath) {
        if (!sInitDone.compareAndSet(false, true)) {
            Diag.log("installer init skipped (already done)");
            return;
        }
        sApk = apkPath;
        HookEnv.cl = cl;
        Diag.log("[adapt] hook set " + BuildConfig.VERSION_CODE + "/"
                + BuildConfig.VERSION_NAME + " loaded, installer ver=" + ver());
        // generic hooks (all installer versions)
        hookNetwork(cl);
        hookAllowDowngrade(cl);
        // risk-install dialog (the only "risk tip" popup in the installer;
        // class name is stable on 38.301 / 32.303 / 14.0.22.x)
        hookRiskTipDialog(cl);
        // pre-check "anonymous source" warning (no install source package):
        // the actual popup both 2026-10-29 field reports hit.
        // primary = zero-popup suppression, fallback = auto-click "继续"
        hookAnonymousSourceSuppress(cl);
        hookAnonymousSourceDialog(cl);
        // adaptive set — resolves per build (38.301 / 14.0.22.x / future
        // OTA reshuffles) via signature scanning, see "adaptive resolution"
        hookSecurityCheckCompatV2(cl);
        hookFingerprintAuthCompatV2(cl);
        hookDiversionWaitCompatV2(cl);
        hookCleanCacheCardCompatV2(cl);
        hookSafeModeBannerCompatV2(cl);
        hookSafeModeCardInfoCompatV2(cl);
        hookAdRecommendCompatV2(cl);
        // 推荐应用卡片走的是 PackageInstallerActivity.f(AssemblyVO)，
        // 和上面的广告 presenter 完全无关 —— 两条都要堵。
        hookWelfareRecommend(cl);
        hookWelfareAdapter(cl);
        hookDiversionPresenter(cl);
        hookOfficialInstallButton(cl);
        hookPurifyRequests(cl);
        hookStagingFastPathCompatV2(cl);
    }

    public static void initSystemServer(ClassLoader cl) {
        hookDowngradeInSystemServer(cl);
    }

    // ==================== generic hooks (all versions) ====================

    private static void hookDowngradeInSystemServer(ClassLoader cl) {
        try {
            Class<?> utils = cl.loadClass("com.android.server.pm.PackageManagerServiceUtils");
            int n = 0;
            for (final Method m : utils.getDeclaredMethods()) {
                if (m.getName().equals("isDowngradePermitted")) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            // ⚠ 不能用 on(Cfg.K_DOWNGRADE)：这个回调挂在 system_server，
                            // 它读不到模块 provider（SELinux），永远返回 false ——
                            // 等于系统层校验从来没放行过降级。改读系统属性。
                            if (Cfg.downgradeOn()) param.setResult(true);
                        }
                    });
                    n++;
                }
            }
            Diag.log("system_server isDowngradePermitted hooked: " + n);
        } catch (Throwable t) {
            Diag.log("system_server downgrade hook failed: " + t);
        }
    }

    private static void hookAllowDowngrade(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(android.content.pm.PackageInstaller.class, "createSession",
                    android.content.pm.PackageInstaller.SessionParams.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!on(Cfg.K_DOWNGRADE)) return;
                            if (param.args.length < 1) return;
                            Object sp = param.args[0];
                            if (sp != null) {
                                int flags = XposedHelpers.getIntField(sp, "installFlags");
                                XposedHelpers.setIntField(sp, "installFlags", flags | 0x80);
                            }
                        }
                    });
            Diag.log("hook createSession(allow downgrade) ok");
        } catch (Throwable t) {
            Diag.log("hook createSession failed: " + t);
        }
    }

    private static void hookNetwork(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod("java.net.Socket", cl, "connect", java.net.SocketAddress.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (purifyOn()) {
                                param.setThrowable(new java.net.SocketException("blocked by HonorAdvancedSettings"));
                            }
                        }
                    });
            Diag.log("hook Socket.connect ok");
        } catch (Throwable t) {
            Diag.log("hook Socket.connect failed: " + t);
        }
    }

    // ==================== risk-install dialog ====================

    /**
     * "风险提示" install popup — the only risk-tip dialog in the installer:
     * `com.android.packageinstaller.fragment.InstallRiskTipDialog` (the
     * class name survives every known build; 38.301 builds it from
     * PIA.R0(12), 14.0.22.x/32.303 from the static factory i("")). It shows
     * the stock `risk_warning` / `check_risk_poptip_high_risk` text with a
     * "继续安装" button and otherwise blocks the install flow. All four
     * known builds (22.326/327, 32.303, 38.301, 39.301) override onStart;
     * the base-class fallback is only for future builds that stop doing so.
     *
     * When purify is on we answer it the same way a user tapping "继续安装"
     * would: hook the fragment's onStart (the dialog is already attached at
     * that point) and click the positive button. This reuses the stock
     * continuation for every build (PIA.D0 / paging Q1-R1 / dr0.l), so a
     * future installer reshuffle cannot strand the flow. Capped at 3 per
     * process (老问题回归要求：上限 3 次，防 build 循环重开弹窗)。
     *
     * Pure addition: purify off → stock dialog, no interception.
     */
    private static int sRiskAutoCount = 0;
    /** 子类 onStart 与基类 onStart 可能同时回调，按实例去重，确保只点一次。 */
    private static final java.util.WeakHashMap<Object, Boolean> sRiskHandled =
            new java.util.WeakHashMap<>();

    private static void hookRiskTipDialog(ClassLoader cl) {
        try {
            final Class<?> dlg = cl.loadClass(
                    "com.android.packageinstaller.fragment.InstallRiskTipDialog");
            java.util.Set<XC_MethodHook.Unhook> unhooks =
                    XposedBridge.hookAllMethods(dlg, "onStart", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    // DEFENSIVE: only act when the callback came from the
                    // dialog class itself. Whether hookAllMethods also visits
                    // superclass overrides is implementation-dependent; this
                    // guard makes sure exactly one onStart path clicks the
                    // positive button.
                    if (param.method.getDeclaringClass() != dlg) return;
                    if (!defaultPurify()) return;
                    if (sRiskHandled.put(param.thisObject, Boolean.TRUE) != null) return;
                    riskAutoContinue(param, "child");
                }
            });
            if (unhooks == null || unhooks.isEmpty()) {
                Diag.log("risk-tip dialog has no onStart override, base fallback only");
            } else {
                Diag.log("[adapt] risk-tip dialog auto-continue hooked");
            }

            // 32.303 / 38.301 / 39.301 的 InstallRiskTipDialog 都重写了
            // onStart（39.301 在 InstallRiskTipDialog.java:146）。基类 AppErrorDialog
            // 兜底只用于未来版本子类漏挂/被内联；sRiskHandled 保证子类/基类两条路径
            // 对同一实例只点一次。
            try {
                final Class<?> base = cl.loadClass(
                        "com.android.packageinstaller.fragment.AppErrorDialog");
                java.util.Set<XC_MethodHook.Unhook> baseUnhooks =
                        XposedBridge.hookAllMethods(base, "onStart", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!dlg.isInstance(param.thisObject)) return;
                        if (!defaultPurify()) return;
                        if (sRiskHandled.put(param.thisObject, Boolean.TRUE) != null) return;
                        riskAutoContinue(param, "base");
                    }
                });
                if (baseUnhooks == null || baseUnhooks.isEmpty()) {
                    Diag.log("risk-tip base AppErrorDialog has no onStart, skip");
                } else {
                    Diag.log("[adapt] risk-tip base fallback installed");
                }
            } catch (Throwable t) {
                absent("risk-tip base AppErrorDialog");
            }
        } catch (Throwable t) {
            absent("risk-tip dialog InstallRiskTipDialog");
        }
    }

    /** 子类/基类两条路径共用；sRiskHandled 已由调用方抢占。 */
    private static void riskAutoContinue(XC_MethodHook.MethodHookParam param, String path) {
        try {
            if (sRiskAutoCount >= 3) return;
            Object d = XposedHelpers.callMethod(param.thisObject, "getDialog");
            if (!(d instanceof android.app.AlertDialog)) return;
            android.widget.Button ok = ((android.app.AlertDialog) d)
                    .getButton(android.content.DialogInterface.BUTTON_POSITIVE);
            if (ok == null) return;
            sRiskAutoCount++;
            Diag.log("[adapt] risk install dialog auto-continue (" + path + ","
                    + sRiskAutoCount + ")");
            ok.performClick();
        } catch (Throwable t) {
            Diag.log("risk dialog auto-continue error: " + t);
        }
    }

    // ==================== anonymous-source dialog ====================

    /**
     * Pre-check "anonymous source" warning — the popup actually seen in the
     * 2026-10-29 field reports. Shown when PackageInstallerActivity has NO
     * install-source package (`this.I == null` / `i0 == null` / `o0 == null`),
     * e.g. an APK delivered by a PC tool / USB copy. Class name is stable on
     * all four known builds; the text is R.string.anonymous_source_warning,
     * buttons are R.string.anonymous_source_continue ("继续") / cancel.
     *
     * PRIMARY (zero popup): hook framework DialogFragment.onStart, filtered to
     * AnonymousSourceDialog. After super.onStart() the dialog window and its
     * positive button exist; hide the window (alpha 0) before show() and tap
     * the stock positive listener so the installer continuation runs.
     *
     * v2.1.1 failure fix ("install button dead", field 2026-10-30): the stock
     * AlertController does NOT run the listener synchronously — performClick()
     * only posts mButtonPositiveMessage to the main queue, and the dialog is
     * dismissed by a *second* posted message. The old code called
     * dismissAllowingStateLoss() inside the same onStart stack right after the
     * click; the dialog window went away (wm_dim_removed at the same second),
     * but the queued listener landed after the fragment was torn down, so
     * `getActivity()` was null, q0(false) never ran, the PIA stayed in
     * "waiting for the anonymous-source answer" state and its 继续安装 button
     * ignored every tap. Fix: never dismiss inside onStart; verify the dialog
     * actually closed, and if it did not, call the stock listener directly
     * (AlertController.mButtonPositiveMessage.obj), then dismiss from the main
     * loop. Worst case (everything fails) alpha is restored to 1 so the user
     * gets a normal, tappable dialog — never an invisible window.
     *
     * FALLBACK: onCreateDialog schedules the same ladder after 200ms when the
     * primary never owned the fragment (onStart hook missing). A
     * WeakHashMap<fragment,Boolean> keeps exactly one owner per fragment.
     */
    private static int sAnonAutoCount = 0;
    private static int sAnonSuppressed = 0;
    private static int sAnonNoAnchor = 0;
    private static final java.util.WeakHashMap<Object, Boolean> sAnonHandled =
            new java.util.WeakHashMap<>();
    private static final Handler sAnonMain = new Handler(Looper.getMainLooper());

    private static void anonLog(String msg) {
        Diag.log("[anon] " + msg);
    }

    private static void anonNoAnchor(String why) {
        if (sAnonNoAnchor >= 5) return;
        sAnonNoAnchor++;
        anonLog("anonymous-source no anchor (" + why + ")");
    }

    /** Make the dialog window invisible before show(), or visible again if
     *  the primary path cannot finish; prevents an invisible residual window
     *  from swallowing touches when every hook path fails. */
    private static void setAnonWindowAlpha(Object frag, float alpha) {
        try {
            Object d = XposedHelpers.callMethod(frag, "getDialog");
            if (!(d instanceof android.app.AlertDialog)) return;
            android.view.Window w = ((android.app.AlertDialog) d).getWindow();
            if (w == null) return;
            android.view.WindowManager.LayoutParams lp = w.getAttributes();
            lp.alpha = alpha;
            w.setAttributes(lp);
        } catch (Throwable ignored) {
        }
    }

    /** true when the dialog is gone (dismissed, activity finished or the
     *  fragment torn down) — i.e. the stock continuation had a chance to run. */
    private static boolean anonGone(Object frag) {
        try {
            Object act = XposedHelpers.callMethod(frag, "getActivity");
            if (act == null) return true;
            Object d = XposedHelpers.callMethod(frag, "getDialog");
            if (!(d instanceof android.app.Dialog)) return true;
            return !((android.app.Dialog) d).isShowing();
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * Call the stock positive-button listener directly. AlertController keeps
     * the app listener in mButtonPositiveMessage (its obj), and a real tap
     * runs it from a posted ButtonHandler message. When performClick did not
     * land (message lost behind the onStart stack / dialog teardown), this is
     * the exact continuation the installer expects ("继续" → PIA.q0(false) on
     * 38.301, g3/w2 variants on the other builds). Returns true when called.
     */
    private static boolean anonInvokePositive(android.app.Dialog dialog) {
        try {
            Object alert = XposedHelpers.getObjectField(dialog, "mAlert");
            if (alert == null) return false;
            Object msg = XposedHelpers.getObjectField(alert, "mButtonPositiveMessage");
            if (msg == null) return false;
            Object listener = XposedHelpers.getObjectField(msg, "obj");
            if (!(listener instanceof android.content.DialogInterface.OnClickListener)) {
                return false;
            }
            ((android.content.DialogInterface.OnClickListener) listener)
                    .onClick(dialog, android.content.DialogInterface.BUTTON_POSITIVE);
            return true;
        } catch (Throwable t) {
            anonLog("direct listener error: " + t);
            return false;
        }
    }

    /**
     * Tap the stock "继续" and make sure the installer continuation really
     * ran. Nothing here runs inside the onStart stack:
     *   1. performClick() — cheap stock tap (queues the app listener)
     *   2. +120ms: dialog still showing -> invoke the stock listener directly
     *   3. +100ms after a direct call: dismiss from the main loop, like a tap
     *   4. +420ms (120ms verify + 300ms self-heal): still showing -> restore
     *      alpha 1.0, so the user gets a visible, tappable dialog instead of
     *      an invisible window that eats touches. This is the hard floor:
     *      no path may leave alpha 0 behind.
     */
    private static void anonContinue(final Object frag, final android.app.Dialog dialog,
                                     final android.widget.Button ok, final String path) {
        try {
            boolean clicked = ok.performClick();
            anonLog(path + " click result=" + clicked + " enabled=" + ok.isEnabled());
        } catch (Throwable t) {
            anonLog(path + " click failed: " + t);
        }
        sAnonMain.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    if (anonGone(frag)) {
                        anonLog(path + " ok: dialog closed by stock path");
                        return;
                    }
                    boolean invoked = anonInvokePositive(dialog);
                    anonLog(path + " direct listener=" + invoked);
                    if (invoked) {
                        sAnonMain.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    if (dialog.isShowing()) dialog.dismiss();
                                } catch (Throwable t) {
                                    anonLog(path + " dismiss failed: " + t);
                                }
                            }
                        }, 100L);
                    }
                    sAnonMain.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                if (anonGone(frag)) return;
                                setAnonWindowAlpha(frag, 1.0f);
                                anonLog(path + " self-heal: dialog visible again");
                            } catch (Throwable t) {
                                anonLog(path + " self-heal failed: " + t);
                            }
                        }
                    }, 300L);
                } catch (Throwable t) {
                    setAnonWindowAlpha(frag, 1.0f);
                    anonLog(path + " verify failed: " + t);
                }
            }
        }, 120L);
    }

    /** PRIMARY: suppress the dialog before its first draw. */
    private static void hookAnonymousSourceSuppress(final ClassLoader cl) {
        try {
            final Class<?> anon = cl.loadClass(
                    "com.android.packageinstaller.fragment.AnonymousSourceDialog");
            java.util.Set<XC_MethodHook.Unhook> unhooks =
                    XposedBridge.hookAllMethods(android.app.DialogFragment.class, "onStart",
                            new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!anon.isInstance(param.thisObject)) return;
                    if (!defaultPurify()) return;
                    if (sAnonHandled.containsKey(param.thisObject)) return;
                    // make the window transparent before super.onStart()
                    // calls Dialog.show(); keeps zero-frame even if WindowManager
                    // latches a traversal before our after-hook runs
                    setAnonWindowAlpha(param.thisObject, 0.0f);
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!anon.isInstance(param.thisObject)) return;
                    if (!defaultPurify()) {
                        // before-hook may already have made the window
                        // transparent; restore it if the switch flipped mid-call
                        setAnonWindowAlpha(param.thisObject, 1.0f);
                        return;
                    }
                    final Object frag = param.thisObject;
                    if (sAnonHandled.containsKey(frag)) return;
                    if (sAnonSuppressed >= 10) {
                        setAnonWindowAlpha(frag, 1.0f);
                        return;
                    }
                    try {
                        Object d = XposedHelpers.callMethod(frag, "getDialog");
                        if (!(d instanceof android.app.AlertDialog)) {
                            setAnonWindowAlpha(frag, 1.0f);
                            return;
                        }
                        android.widget.Button ok = ((android.app.AlertDialog) d)
                                .getButton(android.content.DialogInterface.BUTTON_POSITIVE);
                        if (ok == null) {
                            setAnonWindowAlpha(frag, 1.0f);
                            return; // fallback will handle
                        }
                        sAnonHandled.put(frag, Boolean.TRUE);
                        sAnonSuppressed++;
                        anonLog("anonymous-source suppressed (" + sAnonSuppressed + ")");
                        // NO dismiss here: inside the onStart stack it tears the
                        // fragment down before AlertController can run the queued
                        // stock listener (that stranded the install flow). The
                        // ladder clicks, verifies, then dismisses from the main
                        // loop and always restores alpha on the failure path.
                        anonContinue(frag, (android.app.Dialog) d, ok, "[anon]");
                    } catch (Throwable t) {
                        setAnonWindowAlpha(frag, 1.0f);
                        anonLog("anonymous-source suppress failed: " + t);
                    }
                }
            });
            if (unhooks == null || unhooks.isEmpty()) {
                Diag.log("[adapt] anonymous-source onStart no anchor, fallback only");
                return;
            }
            Diag.log("[adapt] anonymous-source zero-popup installed");
        } catch (Throwable t) {
            absent("anonymous-source dialog");
        }
    }

    /** FALLBACK: onCreateDialog auto-click if the primary did not handle. */
    private static void hookAnonymousSourceDialog(ClassLoader cl) {
        try {
            final Class<?> dlg = cl.loadClass(
                    "com.android.packageinstaller.fragment.AnonymousSourceDialog");
            java.util.Set<XC_MethodHook.Unhook> unhooks =
                    XposedBridge.hookAllMethods(dlg, "onCreateDialog", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!defaultPurify()) return;
                    try {
                        Object d = param.getResult();
                        if (!(d instanceof android.app.AlertDialog)) return;
                        final Object frag = param.thisObject;
                        final Handler h = new Handler(Looper.getMainLooper());
                        // 200ms head start for the primary onStart path. When
                        // the primary owns the fragment it also owns visibility
                        // (its ladder restores alpha if the dialog survives), so
                        // this fallback must not touch it — a blind dismiss here
                        // is what used to kill the queued stock listener.
                        h.postDelayed(new Runnable() {
                            int tries = 0;

                            @Override
                            public void run() {
                                try {
                                    if (sAnonHandled.containsKey(frag)) return;
                                    if (anonGone(frag)) return;
                                    if (sAnonAutoCount >= 10) {
                                        anonNoAnchor("fallback cap reached");
                                        return;
                                    }
                                    Object dlg = XposedHelpers.callMethod(frag, "getDialog");
                                    if (!(dlg instanceof android.app.AlertDialog)) {
                                        if (++tries <= 3) {
                                            h.postDelayed(this, 50L);
                                        } else {
                                            anonNoAnchor("dialog missing");
                                        }
                                        return;
                                    }
                                    android.widget.Button ok = ((android.app.AlertDialog) dlg)
                                            .getButton(android.content.DialogInterface.BUTTON_POSITIVE);
                                    if (ok == null) {
                                        if (++tries <= 3) {
                                            h.postDelayed(this, 50L);
                                        } else {
                                            anonNoAnchor("button missing");
                                        }
                                        return;
                                    }
                                    sAnonHandled.put(frag, Boolean.TRUE);
                                    sAnonAutoCount++;
                                    anonLog("anonymous-source auto-continue (" + sAnonAutoCount + ")");
                                    anonContinue(frag, (android.app.Dialog) dlg, ok, "[anon-fb]");
                                } catch (Throwable t) {
                                    setAnonWindowAlpha(frag, 1.0f);
                                    anonLog("anonymous-source fallback failed: " + t);
                                    anonNoAnchor("exception");
                                }
                            }
                        }, 200L);
                    } catch (Throwable t) {
                        Diag.log("anonymous-source fallback error: " + t);
                    }
                }
            });
            if (unhooks == null || unhooks.isEmpty()) {
                Diag.log("[adapt] anonymous-source no anchor (onCreateDialog missing)");
                return;
            }
            Diag.log("[adapt] anonymous-source fallback installed");
        } catch (Throwable t) {
            absent("anonymous-source fallback");
        }
    }

    // ==================== info-card helpers (used by V2) ====================

    /** bounded diagnostics for the v2.1.1 false "降级安装" report: at most
     *  3 lines per installer process, prefix [dg], one per card fill. */
    private static int sDgLogged = 0;

    private static void fillInfoCard(final View card, final Activity activity, final int attempt) {
        if (card == null || activity == null) {
            return;
        }
        if (!"com.android.packageinstaller.PackageInstallerActivity".equals(activity.getClass().getName())) {
            card.setVisibility(View.GONE);
            return;
        }
        if (attempt == 0) {
            // 只藏安全结果卡；不做全页视图遍历，避免误伤按钮。
            scheduleHideSecurityCard(activity);
            hideSimilarAppEntry(activity);
        }
        PackageInfo newInfo = null;
        String pkgName = null;
        // the PackageInfo field name reshuffles per build (old line t0,
        // 14.0.22 q0, 14.0.38 P, next OTA anything) — the TYPE never does;
        // scan the activity's declared fields for the first PackageInfo.
        try {
            for (java.lang.reflect.Field f : activity.getClass().getDeclaredFields()) {
                if (f.getType() == PackageInfo.class) {
                    f.setAccessible(true);
                    Object v = f.get(activity);
                    if (v != null) { newInfo = (PackageInfo) v; break; }
                }
            }
        } catch (Throwable ignored) {
        }
        // known names as a second chance (inherited / shadowed fields)
        if (newInfo == null) {
            String[] names = {"t0", "q0", "P"};
            for (String n : names) {
                try {
                    Object v = XposedHelpers.getObjectField(activity, n);
                    if (v instanceof PackageInfo) { newInfo = (PackageInfo) v; break; }
                } catch (Throwable ignored) {
                }
            }
        }
        if (newInfo == null || newInfo.applicationInfo == null) {
            if (attempt < 10) {
                card.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        fillInfoCard(card, activity, attempt + 1);
                    }
                }, 300);
            } else {
                card.setVisibility(View.GONE);
            }
            return;
        }
        // The parsed APK is the only authoritative target package. Never guess
        // the PIA's obfuscated fields here: on some builds x0/u0 hold the
        // *install source* package (file manager / browser), so the card
        // compared that installed app's version against the new APK and painted
        // a false "↓ 降级安装" tag on brand-new apps (field reports 2026-10-30:
        // 文件管理 11.x vs 药师 2.0.2 / 荣耀工具箱 0.9, Edge 155.x vs DeepSeek
        // 2.7.1). The factory installer never compared versions for that label
        // anyway — the tag was ours.
        pkgName = newInfo.packageName;

        PackageManager pm = activity.getPackageManager();
        PackageInfo oldInfo = null;
        try {
            oldInfo = pm.getPackageInfo(pkgName, 0);
        } catch (Throwable ignored) {
        }
        if (sDgLogged < 3) {
            sDgLogged++;
            long oldVc = oldInfo != null ? oldInfo.getLongVersionCode() : -1L;
            long newVc = newInfo.getLongVersionCode();
            Diag.log("[dg] card pkg=" + pkgName
                    + " old=" + (oldInfo == null ? "none" : oldVc)
                    + " new=" + newVc
                    + " downgrade=" + (oldInfo != null && newVc < oldVc));
        }

        ApplicationInfo ai = newInfo.applicationInfo;
        String label = pkgName;
        try {
            CharSequence l = ai.loadLabel(pm);
            if (l != null && l.length() > 0) {
                label = l.toString();
            }
        } catch (Throwable ignored) {
        }

        android.content.res.Resources res = activity.getResources();
        String rp = activity.getPackageName();
        int idTitle = res.getIdentifier("tv_card_title", "id", rp);
        int idDes = res.getIdentifier("tv_card_des", "id", rp);
        int idSuggest = res.getIdentifier("tv_card_suggest", "id", rp);
        int idBtn = res.getIdentifier("btn_safe_mode_open", "id", rp);
        int idIcon = res.getIdentifier("protection_img", "id", rp);

        card.setVisibility(View.VISIBLE);
        card.setTag("purify_info_card");
        card.setOnClickListener(null);
        card.setClickable(false);

        if (idTitle != 0) {
            TextView tv = (TextView) card.findViewById(idTitle);
            if (tv != null) {
                tv.setText(label);
            }
        }
        if (idIcon != 0) {
            View iv = card.findViewById(idIcon);
            if (iv instanceof ImageView) {
                try {
                    ((ImageView) iv).setImageDrawable(ai.loadIcon(pm));
                } catch (Throwable ignored) {
                }
            }
        }
        if (idSuggest != 0) {
            hideGuarded(activity, card.findViewById(idSuggest), "card-suggest");
        }
        if (idBtn != 0) {
            hideGuarded(activity, card.findViewById(idBtn), "safe-mode-entry");
        }
        if (idDes != 0) {
            TextView tv = (TextView) card.findViewById(idDes);
            if (tv != null) {
                tv.setOnClickListener(null);
                tv.setText(buildInfoText(newInfo, oldInfo, pkgName));
            }
        }
        addAuthorLink(card, activity);
        Diag.log("info card filled for " + pkgName);
    }

    private static void addAuthorLink(View card, final Activity activity) {
        if (card.findViewWithTag("purify_author") != null) {
            return;
        }
        android.content.res.Resources res = activity.getResources();
        int idInner = res.getIdentifier("card_inner_layout", "id", activity.getPackageName());
        android.view.ViewGroup parent = null;
        boolean relative = false;
        if (idInner != 0) {
            View inner = card.findViewById(idInner);
            if (inner instanceof android.widget.RelativeLayout) {
                parent = (android.view.ViewGroup) inner;
                relative = true;
            }
        }
        if (parent == null && card instanceof android.view.ViewGroup) {
            parent = (android.view.ViewGroup) card;
        }
        if (parent == null) {
            return;
        }
        TextView tv = new TextView(activity);
        tv.setTag("purify_author");
        tv.setText("by VoreulCH");
        tv.setTextSize(12);
        tv.setTextColor(android.graphics.Color.parseColor("#4C8DFF"));
        int mt = (int) (11 * res.getDisplayMetrics().density + 0.5f);
        if (relative) {
            android.widget.RelativeLayout.LayoutParams lp =
                    new android.widget.RelativeLayout.LayoutParams(-2, -2);
            lp.addRule(android.widget.RelativeLayout.ALIGN_PARENT_TOP);
            lp.addRule(android.widget.RelativeLayout.ALIGN_PARENT_END);
            lp.topMargin = mt;
            tv.setLayoutParams(lp);
        } else {
            android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                    -2, -2, android.view.Gravity.TOP | android.view.Gravity.END);
            lp.topMargin = mt;
            tv.setLayoutParams(lp);
        }
        tv.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    activity.startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://github.com/VoreulCH")));
                } catch (Throwable t) {
                    Diag.log("open repo error: " + t);
                }
            }
        });
        parent.addView(tv);
    }

    private static CharSequence buildInfoText(PackageInfo newInfo, PackageInfo oldInfo, String pkgName) {
        ApplicationInfo ai = newInfo.applicationInfo;
        StringBuilder sb = new StringBuilder();
        sb.append("版本：");
        if (oldInfo != null) {
            sb.append(verText(oldInfo)).append(" → ");
        }
        sb.append(verText(newInfo));
        int markPos = -1;
        if (oldInfo != null && newInfo.getLongVersionCode() < oldInfo.getLongVersionCode()) {
            markPos = sb.length();
            sb.append("  ↓ 降级安装");
        }

        long newSize = fileLen(ai.sourceDir) + splitLen(ai);
        sb.append("\n大小：");
        long oldSize = oldInfo != null && oldInfo.applicationInfo != null
                ? fileLen(oldInfo.applicationInfo.sourceDir) + splitLen(oldInfo.applicationInfo) : 0;
        if (oldSize > 0 && newSize > 0) {
            long diff = newSize - oldSize;
            sb.append(fmtSize(oldSize)).append(" → ").append(fmtSize(newSize));
            if (diff == 0) {
                sb.append("（不变）");
            } else {
                sb.append("（").append(diff > 0 ? "+" : "-").append(fmtSize(Math.abs(diff))).append("）");
            }
        } else {
            sb.append(fmtSize(newSize));
        }

        sb.append("\nSDK：API ");
        if (oldInfo != null && oldInfo.applicationInfo != null) {
            sb.append(oldInfo.applicationInfo.targetSdkVersion).append(" → ");
        }
        sb.append(ai.targetSdkVersion).append("（最低 ").append(ai.minSdkVersion > 0 ? ai.minSdkVersion : 1).append("）");

        sb.append("\n架构：").append(abiText(ai.sourceDir));
        sb.append("\n包名：").append(pkgName);
        if (markPos >= 0) {
            android.text.SpannableStringBuilder ssb = new android.text.SpannableStringBuilder(sb.toString());
            ssb.setSpan(new android.text.style.ForegroundColorSpan(android.graphics.Color.parseColor("#FF5252")),
                    markPos, sb.length(), 33);
            return ssb;
        }
        return sb.toString();
    }

    private static String verText(PackageInfo pi) {
        String vn = pi.versionName != null ? pi.versionName : "?";
        return vn + "（" + pi.getLongVersionCode() + "）";
    }

    private static String fmtSize(long bytes) {
        if (bytes <= 0) {
            return "未知";
        }
        if (bytes >= 1048576L) {
            return String.format(Locale.US, "%.1fMB", bytes / 1048576.0);
        }
        return String.format(Locale.US, "%.0fKB", bytes / 1024.0);
    }

    private static String abiText(String sourceDir) {
        LinkedHashSet<String> abis = new LinkedHashSet<String>();
        ZipFile zf = null;
        try {
            zf = new ZipFile(sourceDir);
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                String name = en.nextElement().getName();
                if (name.startsWith("lib/")) {
                    String[] parts = name.split("/");
                    if (parts.length >= 3 && parts[1].length() > 0) {
                        abis.add(parts[1]);
                    }
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (zf != null) {
                try {
                    zf.close();
                } catch (Throwable ignored) {
                }
            }
        }
        if (abis.isEmpty()) {
            return "无原生库";
        }
        StringBuilder sb = new StringBuilder();
        for (String a : abis) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(a);
        }
        return sb.toString();
    }

    private static long fileLen(String path) {
        if (path == null) {
            return 0;
        }
        try {
            return new File(path).length();
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static long splitLen(ApplicationInfo ai) {
        long total = 0;
        if (ai.splitSourceDirs != null) {
            for (String s : ai.splitSourceDirs) {
                total += fileLen(s);
            }
        }
        return total;
    }

    // ==================== adaptive resolution ====================
    // Obfuscated class AND method names reshuffle every installer build, but
    // parameter types anchored on stable full names (DivideButtonModule /
    // CleanSpeedUpModule / CardTipsRecord / HnControlResult / RbiTempBean /
    // framework classes) survive. Resolution order per hook point:
    //   1. class: DexKit string anchors (verified against 38.301) — survives
    //      any class rename as long as the anchor strings stay referenced
    //   2. class: hard-coded names of every known build (38.301 / 14.0.22.x)
    //   3. method: scan the class for a declared method whose parameter type
    //      list matches a known per-build signature ("?" = any obfuscated
    //      type) — the method NAME is never consulted
    // Every step fails soft (null / skip + log), so a rule mismatch can
    // never be worse than the previous hard-coded behaviour.

    /** memo for locateAll: several hook points share the same anchor set,
     *  and each DexKit query walks the whole dex — resolve each anchor set
     *  once per process. init() runs on a single thread, plain map is fine. */
    private static final java.util.HashMap<String, Class<?>[]> sLocateCache = new java.util.HashMap<>();

    /** collect hook-class candidates: the DexKit anchor hit (if any) plus
     *  every known per-build name that loads. Candidates are tried together
     *  with the signature specs, so a wrong DexKit hit can never strand a
     *  hook point — the right class still wins on signature. */
    private static Class<?>[] locateAll(String[] knownNames, String... anchors) {
        return locateAllUnion(knownNames, anchors);
    }

    /**
     * Same as locateAll, but with SEVERAL strict anchor sets. Each set is still
     * an AND match (the class must reference every string of that set); the
     * hits are only unioned. Needed for 14.0.22.326/327 and 14.0.39.301, where
     * the card-tips / info-card class no longer references "AdNetworkUtil"
     * (14.0.39.301 keeps the literal only in an unrelated class), so the old
     * {AdNetworkUtil, SafeModeShowUtils} pair can never match while
     * {SafeModeShowUtils} alone still does. Strict per-set matching keeps the
     * candidate pool as narrow as before; the method SHAPE scan still decides
     * which candidate actually gets hooked. Cost: one extra DexKit walk per
     * resolved set, cached per process like locateAll.
     */
    private static Class<?>[] locateAllUnion(String[] knownNames, String[]... anchorSets) {
        StringBuilder ck = new StringBuilder();
        for (String[] set : anchorSets) {
            for (String a : set) ck.append(a).append('|');
            ck.append('#');
        }
        ck.append("##");
        for (String n : knownNames) ck.append(n).append(',');
        String key = ck.toString();
        Class<?>[] cached = sLocateCache.get(key);
        if (cached != null) return cached;
        java.util.ArrayList<Class<?>> out = new java.util.ArrayList<>();
        for (String[] set : anchorSets) {
            Class<?> c = DexKitHelper.findClassByStrings(sApk, HookEnv.cl, set);
            if (c != null && !out.contains(c)) {
                out.add(c);
                Diag.log("[adapt] dexkit anchor hit: " + c.getName());
            }
        }
        for (String n : knownNames) {
            try {
                Class<?> k = HookEnv.cl.loadClass(n);
                if (!out.contains(k)) out.add(k);
            } catch (Throwable ignored) {
            }
        }
        Class<?>[] r = out.toArray(new Class<?>[0]);
        sLocateCache.put(key, r);
        return r;
    }

    /** first method matching the signature across ALL candidate classes. */
    private static Method resolveAny(Class<?>[] cs, String returnType, String... paramSpec) {
        for (Class<?> c : cs) {
            Method m = resolveByParams(c, returnType, paramSpec);
            if (m != null) return m;
        }
        return null;
    }

    /** scan a class for a declared method by parameter-type signature.
     *  paramSpec entries: full type name, primitive name ("int"/"long"/
     *  "boolean"/"void" for the return slot), or "?" (any single type).
     *  The method name is never consulted. */
    private static Method resolveByParams(Class<?> c, String returnType, String... paramSpec) {
        if (c == null) return null;
        for (Method m : c.getDeclaredMethods()) {
            Class<?>[] ps = m.getParameterTypes();
            if (ps.length != paramSpec.length) continue;
            if (!typeMatches(m.getReturnType(), returnType)) continue;
            boolean ok = true;
            for (int i = 0; i < ps.length; i++) {
                if (!typeMatches(ps[i], paramSpec[i])) { ok = false; break; }
            }
            if (ok) return m;
        }
        return null;
    }

    private static boolean typeMatches(Class<?> actual, String want) {
        if ("?".equals(want)) return true;
        if (want == null) return true;
        if ("int".equals(want)) return actual == int.class;
        if ("long".equals(want)) return actual == long.class;
        if ("boolean".equals(want)) return actual == boolean.class;
        if ("void".equals(want)) return actual == void.class;
        return want.equals(actual.getName());
    }

    /** the ClassLoader/apk of the hooked installer, stashed at init. */
    private static final class HookEnv {
        static ClassLoader cl;
    }

    // ==================== adaptive hook set (all known builds) ====================
    // Class names via DexKit anchors + per-build fallbacks, methods via
    // signature scanning — see the "adaptive resolution" block above.

    /** one-shot log flag: the preinstall-tip card has no counterpart on
     *  14.0.39.301 (whole-app signature scan), so an unresolved hook there
     *  is expected behaviour, not a broken anchor. */
    private static boolean sPreinstallAbsent = false;

    private static final java.util.HashSet<String> sSecDeliveryHooks =
            new java.util.HashSet<>();

    /** 每次安装只打一行 raw / 一行 after。 */
    private static volatile boolean sRawLogged = false;
    private static volatile boolean sAfterLogged = false;

    /** 观察回调上的结果下发方法；记录系统真正下发的结果，而不只是“下发过”。 */
    private static void hookSecurityDelivery(Object callback) {
        try {
            final Class<?> hcr = HookEnv.cl.loadClass(
                    "com.hihonor.packageinstaller.hncust.checkapk.HnControlResult");
            for (final Method dm : callback.getClass().getMethods()) {
                if (dm.getReturnType() != void.class) continue;
                boolean wraps = false;
                for (Class<?> pt : dm.getParameterTypes()) {
                    try {
                        pt.getConstructor(hcr);
                        wraps = true;
                        break;
                    } catch (Throwable ignored) {
                    }
                }
                if (!wraps) continue;
                String key = dm.getDeclaringClass().getName() + "#" + dm.getName()
                        + "#" + dm.getParameterTypes().length;
                synchronized (sSecDeliveryHooks) {
                    if (!sSecDeliveryHooks.add(key)) continue;
                }
                XposedBridge.hookMethod(dm, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Object raw = null;
                        for (Object a : param.args) {
                            raw = findHcr(a, hcr, 2);
                            if (raw != null) break;
                        }
                        if (raw == null) return;
                        // 已经是安全形态（我们补的 pass 或系统 pass）：不重复改写。
                        if (looksLikePass(raw)) return;
                        if (!sRawLogged) {
                            sRawLogged = true;
                            Diag.log("[adapt] security result raw=" + dumpAllFields(raw));
                        }
                        int[] rw = purifyControlResult(raw);
                        if (!sAfterLogged) {
                            sAfterLogged = true;
                            Diag.log("[adapt] security result after=" + dumpAllFields(raw)
                                    + " (rewritten=" + rw[0] + ", skipped=" + rw[1] + ")");
                        }
                    }
                });
            }
        } catch (Throwable t) {
            Diag.log("security delivery watch failed: " + t);
        }
    }

    private static boolean looksLikePass(Object r) {
        if (r == null) return false;
        HcrMap m = hcrMap(r.getClass());
        FieldVal allow = readField(r, m.allow);
        FieldVal icon = readField(r, m.icon);
        boolean okAllow = allow.ok && allow.v instanceof Boolean && (Boolean) allow.v;
        // 326/327 的 iconColor 同样是 int（字段名 g），三种布局都读 m.icon
        boolean okIcon = icon.ok && icon.v instanceof Integer && (Integer) icon.v == 1;
        return okAllow && okIcon;
    }

    /**
     * 把安全检测结果对象改写成“安全”形态。只动 HnControlResult 自身字段，
     * 不碰安装成功/失败、签名、降级、权限等其它对象。
     */
    /**
     * HnControlResult 的字段语义表。5 个安装器版本有 3 种布局：
     * 14.0.22.326/327、14.0.32.303、14.0.38/39.301。
     * 字段名缺失时对应槽位为 null，setter 会跳过并记入 unknown-fields。
     */
    private static final class HcrMap {
        String allow, control, tips, title, icon;
        String showRisk, showPwd, showCheck, checkWord, cloud, failed;
        String clear, appFilled, unreg, riskList, safeMode, perm, bit32;
        String mergeEnable, videoEnable, display, save;
        String mergeTips, safeModeRise, track, adv;
        String localCode, supTitle, supText, minorTitle, minorText, safeTitle;
    }

    private static volatile HcrMap sHcrMap;
    private static volatile String sHcrMapFor;

    private static HcrMap hcrMap(Class<?> hcr) {
        String v = ver();
        HcrMap m = sHcrMap;
        if (m != null && v.equals(sHcrMapFor)) return m;
        m = new HcrMap();
        boolean cInt = false;
        boolean hString = false;
        try { cInt = hcr.getDeclaredField("c").getType() == int.class; } catch (Throwable ignored) { }
        try { hString = hcr.getDeclaredField("H").getType() == String.class; } catch (Throwable ignored) { }
        m.allow = "b";
        m.control = "a";
        if (cInt) {
            // 14.0.22.326/327：c=agBtnType(int)，e/f/… 是文本，g/h 才是图标/来源
            m.tips = "e"; m.title = "f"; m.icon = "g";
            m.showRisk = "r"; m.showPwd = "s"; m.showCheck = "t"; m.checkWord = "u";
            m.cloud = "z"; m.failed = "A"; m.clear = "D"; m.appFilled = "E"; m.unreg = "F";
            m.riskList = "Q"; m.safeMode = "U"; m.bit32 = "N";
            m.localCode = "O"; m.supTitle = "L"; m.supText = "M";
            m.minorTitle = "W"; m.minorText = "a0"; m.safeTitle = "f0";
            m.safeModeRise = "g0"; m.track = "h0"; m.adv = "d0";
        } else if (hString) {
            // 14.0.32.303：H=localControlCode(String)，J=riskInfoList
            m.tips = "c"; m.title = "d"; m.icon = "e";
            m.showRisk = "p"; m.showPwd = "q"; m.showCheck = "r"; m.checkWord = "s";
            m.cloud = "x"; m.failed = "y"; m.clear = "z"; m.appFilled = "A"; m.unreg = "B";
            m.riskList = "J"; m.safeMode = "N"; m.bit32 = "G";
            m.mergeEnable = "W"; m.videoEnable = "b0"; m.display = "d0"; m.save = "e0";
            m.mergeTips = "a0"; m.safeModeRise = "T"; m.track = "U";
            m.localCode = "H"; m.supTitle = "E"; m.supText = "F";
            m.minorTitle = "P"; m.minorText = "Q"; m.safeTitle = "S";
        } else {
            // 14.0.38.301 / 14.0.39.301
            m.tips = "c"; m.title = "d"; m.icon = "e";
            m.showRisk = "o"; m.showPwd = "p"; m.showCheck = "q"; m.checkWord = "r";
            m.cloud = "s"; m.failed = "t"; m.clear = "u"; m.appFilled = "v"; m.unreg = "w";
            m.riskList = "H"; m.safeMode = "L"; m.perm = "a0"; m.bit32 = "B";
            m.mergeEnable = "U"; m.videoEnable = "W"; m.display = "Y"; m.save = "Z";
            m.mergeTips = "V"; m.safeModeRise = "R"; m.track = "S"; m.adv = "b0";
            m.localCode = "C"; m.supTitle = "z"; m.supText = "A";
            m.minorTitle = "N"; m.minorText = "O"; m.safeTitle = "Q";
        }
        sHcrMap = m;
        sHcrMapFor = v;
        return m;
    }

    private static final java.util.LinkedHashSet<String> sMissingFields =
            new java.util.LinkedHashSet<>();

    private static void miss(String n) {
        if (n != null && sMissingFields.size() < 48) sMissingFields.add(n);
    }

    private static int[] purifyControlResult(Object r) {
        int[] c = {0, 0};
        if (r == null) return c;
        sMissingFields.clear();
        HcrMap m = hcrMap(r.getClass());
        // 布尔：允许继续 / 非云检 / 非风险dialog / 非密码 / 无勾选 / 非32位风险 / 非安全模式 / 无权限控制
        c[0] += setBool(r, m.allow, true);
        c[0] += setBool(r, m.cloud, false);
        c[0] += setBool(r, m.failed, false);
        c[0] += setBool(r, m.showRisk, false);
        c[0] += setBool(r, m.showPwd, false);
        c[0] += setBool(r, m.showCheck, false);
        c[0] += setBool(r, m.bit32, false);
        c[0] += setBool(r, m.safeMode, false);
        c[0] += setBool(r, m.perm, false);
        // 整数：安全图标 / 清限制 / 无次版本 / 允许合并与视频安装 / 无展示组装
        c[0] += setInt(r, m.icon, 1);
        c[0] += setInt(r, m.clear, 0);
        c[0] += setInt(r, m.appFilled, -1);
        c[0] += setInt(r, m.unreg, -1);
        c[0] += setInt(r, m.mergeEnable, 1);
        c[0] += setInt(r, m.videoEnable, 1);
        c[0] += setInt(r, m.display, 0);
        c[0] += setInt(r, m.save, 0);
        // 文本：清风险编号、tips/title、勾选文案、监管/安全模式提示
        c[0] += setString(r, m.control, null);
        c[0] += setString(r, m.tips, null);
        c[0] += setString(r, m.title, null);
        c[0] += setString(r, m.checkWord, null);
        c[0] += setString(r, m.localCode, null);
        c[0] += setString(r, m.supTitle, null);
        c[0] += setString(r, m.supText, null);
        c[0] += setString(r, m.minorTitle, null);
        c[0] += setString(r, m.minorText, null);
        c[0] += setString(r, m.safeTitle, null);
        // 列表/对象：清风险列表、安全模式/跟踪/合并对象
        c[0] += setList(r, m.riskList, null);
        c[0] += setObj(r, m.mergeTips, null);
        c[0] += setObj(r, m.safeModeRise, null);
        c[0] += setObj(r, m.track, null);
        c[0] += setObj(r, m.adv, null);
        c[1] = sMissingFields.size();
        return c;
    }

    private static java.lang.reflect.Field findFieldObj(Object o, String name) {
        Class<?> c = o.getClass();
        while (c != null) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    private static int setBool(Object o, String n, boolean v) {
        try {
            java.lang.reflect.Field f = findFieldObj(o, n);
            if (f == null || f.getType() != boolean.class) { miss(n); return 0; }
            f.setAccessible(true);
            f.setBoolean(o, v);
            return 1;
        } catch (Throwable t) { miss(n); return 0; }
    }

    private static int setInt(Object o, String n, int v) {
        try {
            java.lang.reflect.Field f = findFieldObj(o, n);
            if (f == null || f.getType() != int.class) { miss(n); return 0; }
            f.setAccessible(true);
            f.setInt(o, v);
            return 1;
        } catch (Throwable t) { miss(n); return 0; }
    }

    private static int setString(Object o, String n, String v) {
        try {
            java.lang.reflect.Field f = findFieldObj(o, n);
            if (f == null || f.getType() != String.class) { miss(n); return 0; }
            f.setAccessible(true);
            f.set(o, v);
            return 1;
        } catch (Throwable t) { miss(n); return 0; }
    }

    private static int setList(Object o, String n, java.util.List<?> v) {
        try {
            java.lang.reflect.Field f = findFieldObj(o, n);
            if (f == null || !java.util.List.class.isAssignableFrom(f.getType())) { miss(n); return 0; }
            f.setAccessible(true);
            f.set(o, v);
            return 1;
        } catch (Throwable t) { miss(n); return 0; }
    }

    private static int setObj(Object o, String n, Object v) {
        try {
            java.lang.reflect.Field f = findFieldObj(o, n);
            if (f == null || f.getType().isPrimitive()) { miss(n); return 0; }
            f.setAccessible(true);
            f.set(o, v);
            return 1;
        } catch (Throwable t) { miss(n); return 0; }
    }

    /** 一次性打印 HnControlResult 全部声明字段（含本轮没读的 L/S/a0 等）。 */
    private static String dumpAllFields(Object o) {
        StringBuilder sb = new StringBuilder("HnControlResult{");
        if (o != null) {
            for (java.lang.reflect.Field f : o.getClass().getDeclaredFields()) {
                try {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                    f.setAccessible(true);
                    Object v = f.get(o);
                    String s = v == null ? "null" : String.valueOf(v);
                    if (s.length() > 200) s = s.substring(0, 200) + "...";
                    if (sb.length() > 22) sb.append("; ");
                    sb.append(f.getName()).append('=').append(s);
                } catch (Throwable ignored) {
                }
            }
        }
        return sb.append('}').toString();
    }

    /** 字段读取结果：ok=false 表示缺字段/反射异常；值本身可能是 null。 */
    private static final class FieldVal {
        final boolean ok;
        final Object v;
        FieldVal(boolean ok, Object v) { this.ok = ok; this.v = v; }
    }

    private static FieldVal readField(Object o, String name) {
        if (o == null) return new FieldVal(false, null);
        Class<?> c = o.getClass();
        while (c != null) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return new FieldVal(true, f.get(o));
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (Throwable t) {
                return new FieldVal(false, null);
            }
        }
        return new FieldVal(false, null);
    }

    /** 从下发参数（包装对象）里递归找 HnControlResult。 */
    private static Object findHcr(Object o, Class<?> hcr, int depth) {
        if (o == null) return null;
        if (hcr.isInstance(o)) return o;
        if (depth <= 0) return null;
        Class<?> c = o.getClass();
        if (c.isPrimitive() || o instanceof String || o instanceof android.os.IBinder) return null;
        for (java.lang.reflect.Field f : c.getDeclaredFields()) {
            try {
                if (f.getType() == hcr) {
                    f.setAccessible(true);
                    return f.get(o);
                }
                if (depth > 1 && !f.getType().isPrimitive() && !f.getType().isArray()) {
                    f.setAccessible(true);
                    Object r = findHcr(f.get(o), hcr, depth - 1);
                    if (r != null) return r;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static void hookSecurityCheckCompatV2(final ClassLoader cl) {
        try {
            // 38.301 ed1.b(cb, boolean) vs 14.0.22.x br0.j(cb): in both the
            // callback is the FIRST parameter and is an interface.
            // 14.0.22.327's facade br0 also has h(xv0) — another one-arg-void
            // method taking an AD-listener interface — so shape alone can't
            // pick the entry: post-check that the callback interface carries
            // a result-delivery method (a parameter type wrapping
            // HnControlResult). xv0's methods take no such type, so h() is
            // skipped and j() wins.
            // 38.301 ed1/bx1 · 14.0.22.x br0 · 14.0.39.301 md1 (md1.b(sd1,
            // boolean), marker iface sd1, delivery PIA$b.c(ld1, de1))
            Class<?>[] cs = locateAll(new String[]{"ed1", "br0", "bx1", "md1"},
                    "HnAppCheckJob", "insertRiskAppDb");
            // 14.0.22.322 线的入口不在 br0 类里：这一版 br0 还是**接口**
            // （326/327 起才变成带内嵌回调接口的类），真正对外的门面是
            // uq0.j(uq0.a)，uq0 内部再按机型选 yq0/zq0 两个 br0 实现。
            // 只有 br0 是接口（即 322 这一代）才追加 uq0/yq0/zq0，并放在
            // 最前面：hook 门面 j() 时 thisObject 是 uq0，deliverPass326
            // 能直接拿 uq0 自己的 static a(boolean) 造 xq0，再投给回调的
            // a(xq0)（受分页开关限制）和 c(xq0)（直接渲染）两个入口。
            // 326/327/38/39 的 br0 不是接口，候选顺序一个字节都不变。
            java.util.ArrayList<Class<?>> scan = new java.util.ArrayList<>();
            try {
                Class<?> br = HookEnv.cl.loadClass("br0");
                if (br.isInterface()) {
                    for (String n : new String[]{"uq0", "yq0", "zq0"}) {
                        try {
                            Class<?> k = HookEnv.cl.loadClass(n);
                            if (k != null && !scan.contains(k)) scan.add(k);
                        } catch (Throwable ignored) {
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            for (Class<?> c : cs) {
                if (!scan.contains(c)) scan.add(c);
            }
            Method m = null;
            for (Class<?> c : scan) {
                for (Method mm : c.getDeclaredMethods()) {
                    if (java.lang.reflect.Modifier.isAbstract(mm.getModifiers())) continue;
                    Class<?>[] ps = mm.getParameterTypes();
                    if (ps.length == 2 && ps[0].isInterface() && ps[1] == boolean.class
                            && mm.getReturnType() == void.class
                            && carriesResultDelivery(ps[0])) { m = mm; break; }
                }
                if (m != null) break;
                for (Method mm : c.getDeclaredMethods()) {
                    if (java.lang.reflect.Modifier.isAbstract(mm.getModifiers())) continue;
                    Class<?>[] ps = mm.getParameterTypes();
                    if (ps.length == 1 && ps[0].isInterface()
                            && mm.getReturnType() == void.class
                            && carriesResultDelivery(ps[0])) { m = mm; break; }
                }
                if (m != null) break;
            }
            if (m == null) { absent("security-check job"); return; }
            final Method target = m;
            XposedBridge.hookMethod(target, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!defaultPurify()) return;
                    if (param.args.length < 1) return;
                    final Object callback = param.args[0];
                    if (callback == null) { param.setResult(null); return; }
                    // 与原版一致：安全检测任务根本不执行，直接下发安全结果。
                    // 任务不跑 → 不会出现“安装包扫描中/检查应用安全状态”，页面秒开。
                    sRawLogged = false;
                    sAfterLogged = false;
                    hookSecurityDelivery(callback);
                    Object controller = param.thisObject;
                    boolean ok = deliverPass383(controller, callback)
                            || deliverPass326(controller, callback)
                            || deliverPassAny(controller, callback);
                    if (!ok) {
                        // 任何情况下都不让 HnAppCheckJob 执行（否则会读本地名单/缓存/扫描）。
                        // 连自建 HnControlResult 都交付不出去时，中止本次安装并明确报错，
                        // 不放原结果、不跑本地检测。
                        Diag.log("[adapt] FATAL synthetic pass undeliverable; abort install"
                                + " (never run local check)");
                        abortInstall(callback != null ? callback : controller);
                        param.setResult(null);
                        return;
                    }
                    Diag.log("[adapt] security check skipped at source (no request,"
                            + " no local list): true (synthetic-pass)");
                    scheduleHideSecurityCard(callback);
                    param.setResult(null);
                }
            });
            Diag.log("[adapt] security-check hooked: " + target);
        } catch (Throwable t) {
            absent("security-check hook");
        }
    }

    /** true when the callback interface exposes at least one void method
     *  with a parameter type that wraps HnControlResult (a public
     *  (HnControlResult) constructor) — i.e. it is a check-result channel. */
    private static boolean carriesResultDelivery(Class<?> iface) {
        try {
            // 38.301's kd1 is a MARKER interface (zero methods): the delivery
            // methods live on the impl class (PIA.b), which deliverPass*
            // already scans — a marker cannot be disqualified, so it passes.
            if (iface.getMethods().length == 0) return true;
            Class<?> hcr = HookEnv.cl.loadClass(
                    "com.hihonor.packageinstaller.hncust.checkapk.HnControlResult");
            for (Method im : iface.getMethods()) {
                if (im.getReturnType() != void.class) continue;
                for (Class<?> pt : im.getParameterTypes()) {
                    try {
                        pt.getConstructor(hcr);
                        return true;
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 38.301/32.303 recipe: a pass result delivered through the callback's
     *  two-arg method — the result wrapper located via its (HnControlResult)
     *  constructor, the second arg via a same-typed field of the controller.
     *  The result itself comes EITHER from the controller's own no-arg
     *  HnControlResult factory (the system's own "safe default" build —
     *  recognized by public e==1 (SAFE icon) and b==true (install button)),
     *  or, failing that, from a hand-built HnControlResult with the pinned
     *  semantic fields set individually (s missing on some builds no longer
     *  kills the whole recipe — the "virus warning" incident was e missing). */
    private static boolean deliverPass383(Object controller, Object callback) {
        try {
            Class<?> hcr = HookEnv.cl.loadClass(
                    "com.hihonor.packageinstaller.hncust.checkapk.HnControlResult");
            Object r = buildPassResult(controller, hcr);
            if (r == null) return false;
            int[] rw = purifyControlResult(r);
            if (!sAfterLogged) {
                sAfterLogged = true;
                Diag.log("[adapt] security result after=" + dumpAllFields(r)
                        + " (rewritten=" + rw[0] + ", skipped=" + rw[1] + ")");
                if (rw[1] > 0) {
                    Diag.log("[adapt] unknown-fields: " + sMissingFields
                            + " (pass delivered anyway)");
                }
            }
            for (Method dm : callback.getClass().getMethods()) {
                Class<?>[] ps = dm.getParameterTypes();
                if (ps.length != 2 || dm.getReturnType() != void.class) continue;
                java.lang.reflect.Constructor<?> ctor;
                try {
                    ctor = ps[0].getConstructor(hcr);
                } catch (Throwable t) {
                    continue;
                }
                Object pass = ctor.newInstance(r);
                Object v2 = null;
                for (java.lang.reflect.Field f : controller.getClass().getDeclaredFields()) {
                    if (f.getType() == ps[1]) {
                        f.setAccessible(true);
                        Object v = f.get(controller);
                        if (v != null) { v2 = v; break; }
                    }
                }
                dm.setAccessible(true);
                dm.invoke(callback, pass, v2);
                Diag.log("[adapt] security check skipped (383 recipe)");
                scheduleHideSecurityCard(callback);
                return true;
            }
            return false;
        } catch (Throwable t) {
            Diag.log("383 recipe error: " + t);
            return false;
        }
    }

    /** pass result for the 38.301/32.303 line: prefer the controller's own
     *  no-arg HnControlResult factory that yields e==1 && b==true (verified
     *  against 32.303: p() sets iconColor e=1 + button b=true; q() sets
     *  e=3 risk and does not match); fall back to a hand-built result. */
    private static Object buildPassResult(Object controller, Class<?> hcr) {
        try {
            for (Method fm : controller.getClass().getDeclaredMethods()) {
                if (fm.getParameterTypes().length != 0 || fm.getReturnType() != hcr) continue;
                try {
                    fm.setAccessible(true);
                    Object cand = fm.invoke(controller);
                    if (cand == null) continue;
                    if (looksLikePass(cand)) {
                        Diag.log("[adapt] using controller default pass result");
                        return cand;
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        // 自建兜底：按版本字段表逐槽写入安全值，不依赖宿主工厂反射成功
        try {
            Object r = XposedHelpers.newInstance(hcr);
            if (r == null) return null;
            int[] rw = purifyControlResult(r);
            if (looksLikePass(r)) {
                Diag.log("[adapt] hand-built pass result (written=" + rw[0]
                        + ", missing=" + rw[1] + ")");
                return r;
            }
            Diag.log("[adapt] hand-built pass result rejected: written=" + rw[0]
                    + ", missing=" + rw[1]);
            return null;
        } catch (Throwable t) {
            Diag.log("[adapt] hand-built pass result failed: " + t);
            return null;
        }
    }

    /** 14.0.22.x recipe: static result factory (boolean) on the controller,
     *  single-arg delivery on the callback, posted to the main thread. */
    private static boolean deliverPass326(Object controller, Object callback) {
        try {
            Class<?> c = controller.getClass();
            boolean isHn = false;
            try {
                isHn = (Boolean) XposedHelpers.callMethod(controller, "d");
            } catch (Throwable t) {
                for (Method mm : c.getDeclaredMethods()) {
                    if (mm.getParameterTypes().length == 0 && mm.getReturnType() == boolean.class) {
                        mm.setAccessible(true);
                        isHn = (Boolean) mm.invoke(controller);
                        break;
                    }
                }
            }
            Method factory = null;
            for (Method mm : c.getDeclaredMethods()) {
                Class<?>[] ps = mm.getParameterTypes();
                if (java.lang.reflect.Modifier.isStatic(mm.getModifiers())
                        && ps.length == 1 && ps[0] == boolean.class
                        && mm.getReturnType() != void.class && !mm.getReturnType().isPrimitive()) {
                    factory = mm;
                    break;
                }
            }
            if (factory == null) return false;
            factory.setAccessible(true);
            final Object passResult = factory.invoke(null, isHn);
            if (passResult == null) return false;
            // 326 的静态工厂返回的可能是包装对象，先取出内部 HnControlResult 再改写
            try {
                Class<?> hcr = HookEnv.cl.loadClass(
                        "com.hihonor.packageinstaller.hncust.checkapk.HnControlResult");
                Object inner = findHcr(passResult, hcr, 2);
                if (inner != null) {
                    int[] rw = purifyControlResult(inner);
                    if (!sAfterLogged) {
                        sAfterLogged = true;
                        Diag.log("[adapt] security result after=" + dumpAllFields(inner)
                                + " (rewritten=" + rw[0] + ", skipped=" + rw[1] + ")");
                    }
                }
            } catch (Throwable ignored) {
            }
            // Deliver through EVERY matching one-arg method. 14.0.22.327's
            // callback carries two result channels — a(er0), which the
            // activity gates behind a paging check our result fails, and
            // c(er0), the direct renderer. The gated channel swallows the
            // call silently, so firing all matches guarantees the direct
            // one runs; single-channel builds are unaffected.
            java.util.ArrayList<Method> delivers = new java.util.ArrayList<>();
            for (Method dm : callback.getClass().getMethods()) {
                Class<?>[] ps = dm.getParameterTypes();
                if (ps.length == 1 && ps[0] == passResult.getClass()
                        && dm.getReturnType() == void.class) {
                    dm.setAccessible(true);
                    delivers.add(dm);
                }
            }
            if (delivers.isEmpty()) return false;
            final Method[] ds = delivers.toArray(new Method[0]);
            final Object cb = callback;
            final Object pr = passResult;
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    for (Method d : ds) {
                        try {
                            d.invoke(cb, pr);
                        } catch (Throwable t) {
                            Diag.log("326 deliver error: " + t);
                        }
                    }
                    Diag.log("[adapt] security check skipped (326 recipe, " + ds.length + " channel(s))");
                    scheduleHideSecurityCard(cb);
                }
            });
            return true;
        } catch (Throwable t) {
            Diag.log("326 recipe error: " + t);
            return false;
        }
    }

    /**
     * 最终兜底：不依赖宿主的静态工厂/控制器，自己构造安全结果并按回调方法形状
     * 找 1 或 2 参数的投递口。只有 383/326 两条都失败才走这里。
     */
    private static boolean deliverPassAny(Object controller, Object callback) {
        try {
            Class<?> hcr = HookEnv.cl.loadClass(
                    "com.hihonor.packageinstaller.hncust.checkapk.HnControlResult");
            Object r = buildPassResult(controller, hcr);
            if (r == null) return false;
            for (Method dm : callback.getClass().getMethods()) {
                Class<?>[] ps = dm.getParameterTypes();
                if (dm.getReturnType() != void.class || ps.length < 1 || ps.length > 2) continue;
                java.lang.reflect.Constructor<?> ctor;
                try {
                    ctor = ps[0].getConstructor(hcr);
                } catch (Throwable ignored) {
                    continue;
                }
                Object pass = ctor.newInstance(r);
                if (ps.length == 1) {
                    dm.setAccessible(true);
                    dm.invoke(callback, pass);
                    Diag.log("[adapt] security check skipped (generic recipe)");
                    scheduleHideSecurityCard(callback);
                    return true;
                }
                Object v2 = null;
                for (java.lang.reflect.Field f : controller.getClass().getDeclaredFields()) {
                    if (f.getType() == ps[1]) {
                        f.setAccessible(true);
                        Object v = f.get(controller);
                        if (v != null) { v2 = v; break; }
                    }
                }
                dm.setAccessible(true);
                dm.invoke(callback, pass, v2);
                Diag.log("[adapt] security check skipped (generic 2-arg recipe)");
                scheduleHideSecurityCard(callback);
                return true;
            }
        } catch (Throwable t) {
            Diag.log("[adapt] generic pass delivery failed: " + t);
        }
        return false;
    }

    /** 自建结果也交付不出去时的明确中止：提示并关闭安装页，绝不跑本地检测。 */
    private static void abortInstall(Object start) {
        try {
            final Activity act = findActivity(start);
            if (act == null) {
                Diag.log("[adapt] abort install: no activity reached");
                return;
            }
            act.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        android.widget.Toast.makeText(act,
                                "安全结果注入失败，已中止本次安装",
                                android.widget.Toast.LENGTH_LONG).show();
                        act.finish();
                    } catch (Throwable ignored) {
                    }
                }
            });
            Diag.log("[adapt] abort install: " + act.getClass().getName());
        } catch (Throwable t) {
            Diag.log("[adapt] abort install error: " + t);
        }
    }

    private static void hookFingerprintAuthCompatV2(ClassLoader cl) {
        try {
            // 38.301 lt1.B0(Context, String, int, cb) vs 14.0.22.x
            // c01.d(Context, cb). The callback interface is isomorphic on
            // both builds (a(boolean) + b()), and it is always the LAST
            // parameter.
            // 38.301 lt1.B0 · 14.0.22.x c01.d · 14.0.39.301 vt1.C0(Context,
            // String, int, yt1) — same shape as the 38.301 overload
            Class<?>[] cs = locateAll(new String[]{"lt1", "c01", "vt1"},
                    "AppClearRestrictionUtils", "AppMarketInteractionUtil");
            Method m = resolveAny(cs, "void",
                    "android.content.Context", "java.lang.String", "int", "?");
            if (m == null) {
                m = resolveAny(cs, "void", "android.content.Context", "?");
            }
            if (m == null) { absent("fingerprint-auth entry"); return; }
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!defaultPurify()) return;
                    try {
                        Object cb = param.args[param.args.length - 1];
                        if (cb != null) {
                            cb.getClass().getMethod("b").invoke(cb);
                            cb.getClass().getMethod("a", boolean.class).invoke(cb, true);
                            Diag.log("fingerprint auth bypassed");
                        }
                    } catch (Throwable t) {
                        Diag.log("fingerprint error: " + t);
                    }
                    param.setResult(null);
                }
            });
            Diag.log("[adapt] fingerprint hooked: " + m);
        } catch (Throwable t) {
            absent("fingerprint-auth hook");
        }
    }

    private static void hookAdRecommendCompatV2(ClassLoader cl) {
        try {
            // 38.301: AbstractAdBusinessPresenter.g(?, RbiTempBean, ?,
            // boolean) — the presenter class name is NOT obfuscated; only the
            // method name and two param types are. 14.0.22.x: oo0.c(String,
            // String, ?, ?). Try the stable full name first, then oo0.
            Class<?> c = null;
            try {
                c = cl.loadClass("com.hihonor.packageinstaller.presenter.AbstractAdBusinessPresenter");
            } catch (Throwable ignored) {
            }
            Method m = null;
            if (c != null) {
                // 4 params: [1] == RbiTempBean, [3] == boolean, others any
                for (Method mm : c.getDeclaredMethods()) {
                    Class<?>[] ps = mm.getParameterTypes();
                    if (ps.length == 4
                            && "com.hihonor.packageinstaller.rbi.RbiTempBean".equals(ps[1].getName())
                            && ps[3] == boolean.class) {
                        m = mm;
                        break;
                    }
                    // 14.0.22.327: d(String, String, long, RbiTempBean)
                    // initConfig — same entity, different slot
                    if (ps.length == 4
                            && "com.hihonor.packageinstaller.rbi.RbiTempBean".equals(ps[3].getName())
                            && ps[0] == String.class && ps[1] == String.class) {
                        m = mm;
                        break;
                    }
                }
            }
            if (m == null) {
                Class<?> oo0 = null;
                try { oo0 = cl.loadClass("oo0"); } catch (Throwable ignored) {}
                if (oo0 != null) {
                    m = resolveByParams(oo0, "void",
                            "java.lang.String", "java.lang.String", "?", "?");
                }
            }
            // ---- 广告入口全屏蔽（，按 39.301 真实签名重写）----
            //
            // 精确解析 1.0.39.301 的 AbstractAdBusinessPresenter **主类**（不含 $ 子类），
            // 真实签名是这样的：
            //     g (Lu81; LRbiTempBean; Lav1; Z)V              ← 4 参，老代码能命中
            //     h (String,String,J,LRbiTempBean,I,Z,Lxb1;)V   ← 8 参，**老代码没拦**
            //     B (LAdApp; IIJI)V                             ← **老代码没拦**
            //     F (LAdApp; I S S J I S S S S I I I I I S I I I)V ← **老代码没拦**
            //
            // 也就是说广告数据有 4 个入口，老代码只堵了 1 个（g）。这就是
            // 「用过该应用的还喜欢」按钮偶现的真正原因 —— 不是每次都走 g。
            //
            // 新策略：**只要参数列表里出现广告数据类型的（Ad* / Rbi*），一律拦掉**。
            // 这样对所有版本免疫，也不依赖方法名。
            int adSinks = 0;
            for (Method mm : c.getDeclaredMethods()) {
                Class<?>[] ps = mm.getParameterTypes();
                if (ps.length == 0) continue;
                // ⚠ 不能直接 contains("Ad") —— AbstractAdBusinessPresenter 类名里
                // 本来就带 "Ad"（AdBusiness），会把 presenter 自己的正常方法
                // （r/t/u/v 等）也当成广告入口跳过掉，安装器功能会坏。
                // 只认**广告数据所在的包**：
                //   com.hihonor.packageinstaller.entity.resp.*   (AdApp / AdAssemblyInfo / AdRespV4 / AdReqRecord)
                //   com.hihonor.packageinstaller.rbi.*           (RbiTempBean)
                boolean hasAdData = false;
                for (Class<?> p : ps) {
                    String pn = p.getName();
                    boolean inAdPkg = pn.startsWith("com.hihonor.packageinstaller.entity.resp.")
                            || pn.startsWith("com.hihonor.packageinstaller.rbi.");
                    if (inAdPkg && (pn.contains("Ad") || pn.contains("Rbi"))) {
                        hasAdData = true;
                        break;
                    }
                }
                if (!hasAdData) continue;
                // 请求对象不能动（presenter 内部会直接读字段，置 null 会崩在方法体里）
                boolean isReq = false;
                for (Class<?> p : ps) {
                    String pn = p.getName();
                    if (pn.startsWith("com.hihonor.packageinstaller.")
                            && (pn.contains("Req") || pn.contains("Request"))) {
                        isReq = true;
                        break;
                    }
                }
                if (isReq) {
                    Diag.log("[adapt] ad req entry skipped: " + mm.getName());
                    continue;
                }
                try {
                    // 不再限制返回类型 —— 非 void 的在回调里按类型给安全值
                    XposedBridge.hookMethod(mm, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!defaultPurify()) return;
                            if (!(param.method instanceof Method)) return;
                            Class<?> rt = ((Method) param.method).getReturnType();
                            String nm = ((Method) param.method).getName();
                            // 按返回类型给"安全值"，别一律给 null：
                            //   · void   -> setResult(null) 直接跳过执行（最理想）
                            //   · 基本类型 -> 给 false / 0，不会 NPE
                            //   · 对象    -> **不拦**，返回 null 会让调用方 NPE
                            // 实测 1.0.23.301 的 k(Z) 返回 boolean，就属于第二种。
                            if (rt == void.class) {
                                param.setResult(null);
                                Diag.log("[adapt] ad entry blocked(void): " + nm);
                            } else if (rt == boolean.class) {
                                param.setResult(Boolean.FALSE);
                                Diag.log("[adapt] ad entry blocked(false): " + nm);
                            } else if (rt == int.class) {
                                param.setResult(Integer.valueOf(0));
                                Diag.log("[adapt] ad entry blocked(0): " + nm);
                            } else if (rt == long.class) {
                                param.setResult(Long.valueOf(0L));
                                Diag.log("[adapt] ad entry blocked(0L): " + nm);
                            } else {
                                Diag.log("[adapt] ad entry NOT blocked (non-primitive "
                                        + rt.getSimpleName() + "): " + nm);
                            }
                        }
                    });
                    adSinks++;
                    Diag.log("[adapt] ad entry hooked: " + mm.getName()
                            + " (" + ps.length + " params)");
                } catch (Throwable ignored) {
                }
            }
            if (adSinks == 0) {
                absent("ad presenter entries");
                return;
            }
            m = null;
            Diag.log("[adapt] ad hooked: " + adSinks + " entry(ies)");
        } catch (Throwable t) {
            absent("ad presenter hook");
        }
    }

    /**
     * 「推荐应用」按钮（权益 / 福利推荐）—— 和广告 presenter **不是同一条链路**。
     *
     * <p>39.301 的真实入口：
     * <pre>
     *   PackageInstallerActivity.f(AssemblyVO)      // 实现接口 km1
     *     -> this.q0 (PermissionAdapter).notifyItemInserted(2)
     * </pre>
     * 推荐卡片是**直接插进权限列表第 2 项**的，完全不经过
     * {@code AbstractAdBusinessPresenter}。
     *
     * <p>上一版按"广告数据类型"批量拦截，匹配的是 {@code entity.resp.Ad*} /
     * {@code rbi.Rbi*}；这里的数据类型叫 {@code AssemblyVO} —— 名字里既没有
     * "Ad" 也没有 "Rbi"，所以那几个入口一个都没命中。这就是"推荐应用还在"的原因。
     *
     * <p>关于「重启作用域第一次安装必现、平时偶现」：这个方法只要有推荐数据就会
     * 被回调，和拦截时机无关。偶现是因为云端不是每次都下发推荐位；第一次特别容易
     * 撞上，是因为新装流程必然走权限页。
     *
     * <p>拦法：返回 void，直接 {@code setResult(null)} 跳过整个方法体 —— 卡片不会
     * 被插入，安装流程也不受影响（调用方是接口回调，不读返回值）。
     */
    private static void hookWelfareRecommend(ClassLoader cl) {
        try {
            Class<?> act = cl.loadClass("com.android.packageinstaller.PackageInstallerActivity");
            int n = 0;
            for (Method mm : act.getDeclaredMethods()) {
                Class<?>[] ps = mm.getParameterTypes();
                if (ps.length != 1) {
                    continue;
                }
                // 精确类名：不同版本包名不变（实体类不会被混淆），比按方法名可靠
                if (!"com.hihonor.packageinstaller.entity.resp.AssemblyVO"
                        .equals(ps[0].getName())) {
                    continue;
                }
                if (mm.getReturnType() != void.class) {
                    continue;
                }
                XposedBridge.hookMethod(mm, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!defaultPurify()) return;
                        param.setResult(null);
                        Diag.log("[adapt] welfare-recommend blocked (AssemblyVO)");
                    }
                });
                n++;
                Diag.log("[adapt] welfare-recommend hooked: " + mm.getName());
            }
            if (n == 0) {
                // 老版本（14.0.22.x 之前）没有这个推荐位，这里命中 0 是正常的
                absent("welfare-recommend entry (AssemblyVO)");
            }
        } catch (Throwable t) {
            absent("welfare-recommend hook");
        }
    }

    /**
     * 把福利推荐项从列表里**整项摘掉**，而不是只拦数据入口。
     *
     * <p>39.301 的链路：
     * <pre>
     *   PackageInstallerActivity.f(AssemblyVO) -> PermissionAdapter.setWelfareRecommendShow
     *   PermissionAdapter.m() = 是否显示福利项 -> getItemCount()/getItemViewType() 都看它
     *   onBindViewHolder 里 setText(assemblyVO.getAssName())
     * </pre>
     * 所以把 adapter 的 `m()` 置 false，卡片就不会进入列表，也不留占位；
     * Activity.f 的旧拦截继续作为数据入口兜底。
     *
     * <p>兼容：按“Activity 字段里带 AssemblyVO 字段的 adapter”动态找类，找不到
     * `m()` 就跳过并记一行日志；不按版本写死类名。
     */
    private static void hookWelfareAdapter(ClassLoader cl) {
        try {
            Class<?> act = cl.loadClass("com.android.packageinstaller.PackageInstallerActivity");
            Class<?> assembly = cl.loadClass(
                    "com.hihonor.packageinstaller.entity.resp.AssemblyVO");
            LinkedHashSet<Class<?>> adapters = new LinkedHashSet<>();
            for (Field f : act.getDeclaredFields()) {
                Class<?> t = f.getType();
                if (t.isPrimitive() || t.isArray() || t == String.class) continue;
                try {
                    for (Field af : t.getDeclaredFields()) {
                        if (af.getType() == assembly) {
                            adapters.add(t);
                            break;
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            int hooked = 0;
            for (final Class<?> a : adapters) {
                Method m;
                try {
                    m = a.getDeclaredMethod("m");
                } catch (Throwable t) {
                    Diag.log("[adapt] welfare-adapter m() absent on " + a.getName());
                    continue;
                }
                if (m.getParameterTypes().length != 0 || m.getReturnType() != boolean.class) {
                    continue;
                }
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!defaultPurify()) return;
                        param.setResult(false);
                        if (sWelfareFalseLogs < 3) {
                            sWelfareFalseLogs++;
                            Diag.log("[adapt] welfare-adapter m() -> false ("
                                    + a.getName() + "), log#" + sWelfareFalseLogs);
                        }
                    }
                });
                hooked++;
                Diag.log("[adapt] welfare-adapter hooked: " + a.getName() + ".m()");
            }
            if (hooked == 0) {
                absent("welfare-adapter m()");
            }

            // 兜底：卡片若在某些版本仍被插入，拦它的点击回调。
            // 39.301 的内层 d 持有 AssemblyVO 字段，方法 a(int,String) 打开跳转页。
            int clicks = 0;
            for (Class<?> inner : act.getDeclaredClasses()) {
                boolean hasAssembly = false;
                try {
                    for (Field f : inner.getDeclaredFields()) {
                        if (f.getType() == assembly) {
                            hasAssembly = true;
                            break;
                        }
                    }
                } catch (Throwable ignored) {
                }
                if (!hasAssembly) continue;
                for (Method mm : inner.getDeclaredMethods()) {
                    Class<?>[] ps = mm.getParameterTypes();
                    if (ps.length == 2 && ps[0] == int.class && ps[1] == String.class
                            && mm.getReturnType() == void.class) {
                        XposedBridge.hookMethod(mm, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                if (!defaultPurify()) return;
                                param.setResult(null);
                                Diag.log("[adapt] welfare-click blocked: "
                                        + param.method.getDeclaringClass().getName()
                                        + "." + param.method.getName());
                            }
                        });
                        clicks++;
                    }
                }
            }
            if (clicks > 0) {
                Diag.log("[adapt] welfare-click fallback hooked: " + clicks);
            }
        } catch (Throwable t) {
            absent("welfare-adapter hook");
        }
    }

    /**
     * 只摘“用过该应用的还喜欢”这一项，不整份丢弃。
     *
     * <p>`yo1.b(DiversionResp)` 不只设 `te1.v`（还喜欢），还设 `te1.g`（福利卡）、
     * `te1.h`（安全检测卡）、`te1.i`（清理加速卡）、`te1.j`（分流按钮）、
     * `te1.k`（安全模式卡片）。在方法入口 setResult(null) 会把后面这些一起丢，
     * 属于过度拦截。
     *
     * <p>只 hook `DiversionResp.getInstalledPkgDiversionConfig()` 返回 null，
     * 让“还喜欢”数据为空，其余模块原样保留。该 getter 在部分旧版本不存在
     * （14.0.22.x 连 DiversionResp 都没有，14.0.32.303 只有 getAssemblyVO），
     * 此时什么都不做，绝不整份丢弃 —— 宁可少摘一个卡片，也不能误伤其它卡片。
     */
    private static void hookDiversionPresenter(ClassLoader cl) {
        try {
            Class<?> resp = cl.loadClass(
                    "com.hihonor.packageinstaller.entity.resp.DiversionResp");
            Method getter;
            try {
                getter = resp.getDeclaredMethod("getInstalledPkgDiversionConfig");
            } catch (Throwable t) {
                getter = null;
            }
            if (getter != null) {
                XposedBridge.hookMethod(getter, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!defaultPurify()) return;
                        param.setResult(null);
                        Diag.log("[adapt] installed-pkg diversion -> null (precise)");
                    }
                });
                Diag.log("[adapt] precise diversion removal hooked: "
                        + "DiversionResp.getInstalledPkgDiversionConfig()");
                return;
            }
            Diag.log("[adapt] precise getter unavailable, left untouched");
            return;
        } catch (Throwable t) {
            // 14.0.22.326/327 没有 DiversionResp 类：同样只记录，不整份丢弃。
            Diag.log("[adapt] precise getter unavailable, left untouched");
        }
    }

    /**
     * 把 `btn_offical_install`（截图里的“用过该应用的还喜欢”按钮）隐藏。
     *
     * <p>它是 Activity 底部第三个按钮 `n0`，由 `updateBottomButtonLayout` 在
     * 满足条件时调用 `hu1.K(n0,0,0)` 显示；只清云端文案会退回“官方安装”并
     * 继续占位，所以这里在视图层直接 GONE，并拦截后续任何把它设为可见的调用。
     */
    private static volatile int sOfficialBtnId = 0;
    private static volatile boolean sHidingOfficial = false;
    private static volatile int sWelfareFalseLogs = 0;
    private static volatile boolean sOfficialIdMissingLogged = false;

    private static void hookOfficialInstallButton(ClassLoader cl) {
        try {
            final Class<?> act = cl.loadClass(
                    "com.android.packageinstaller.PackageInstallerActivity");
            // 每次安装会话（新的 PackageInstallerActivity）重置风险自动点击计数。
            XposedBridge.hookAllMethods(act, "onCreate", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    sRiskAutoCount = 0;
                    Diag.log("[adapt] risk auto-continue counter reset (new session)");
                }
            });
            XposedHelpers.findAndHookMethod(act, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!defaultPurify()) return;
                    Activity a = (Activity) param.thisObject;
                    int id = a.getResources().getIdentifier(
                            "btn_offical_install", "id",
                            "com.android.packageinstaller");
                    if (id == 0) {
                        if (!sOfficialIdMissingLogged) {
                            sOfficialIdMissingLogged = true;
                            Diag.log("[adapt] official-install id not found "
                                    + "(btn_offical_install missing), hide skipped");
                        }
                        return;
                    }
                    sOfficialBtnId = id;
                    hideGuarded(a, a.findViewById(id), "official-install");
                }
            });
            XposedBridge.hookAllMethods(View.class, "setVisibility", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (sHidingOfficial || !defaultPurify() || sOfficialBtnId == 0) return;
                    Object o = param.thisObject;
                    if (!(o instanceof View) || param.args.length != 1) return;
                    View v = (View) o;
                    if (v.getId() != sOfficialBtnId) return;
                    if (((Integer) param.args[0]) == View.GONE) return;
                    param.setResult(null);
                    sHidingOfficial = true;
                    try {
                        v.setVisibility(View.GONE);
                    } finally {
                        sHidingOfficial = false;
                    }
                }
            });
            Diag.log("[adapt] official-install button hide installed");
        } catch (Throwable t) {
            absent("btn_offical_install hide");
        }
    }

    /**
     * 安装器请求级拦截。
     *
     * <p>在 `BaseCall.execute()` 入口按子类名 + 实际 URL 锚拦掉云请求，直接调
     * 它自己的 `buildResultForCallFailed(...)` 返回失败对象 —— 请求根本不会
     * 发出去，不依赖 Socket/HTTPS 是否被 hook 到。类名在旧版本会变
     * （14.0.22.x 是 SecurityDetectRequestCall/V2），所以 URL 锚是兜底，
     * 不依赖任何类名。
     *
     * <p>安全检测四类（SecurityDetect / DetectDelay / NoDetect / TencentNoDetect）
     * 与 HighRiskList 也在拦截表内：不再从云端拉检测结果和高风险名单，
     * 由 `hookSecurityCheckCompatV2` 下发“通过结果”保证安装继续。
     *
     * <p>全部受 K_PURIFY（安装器净化）总开关控制，关闭即恢复原行为。
     */
    /** 类名 -> 接口路径。日志按路径打，避免只看类名不知道掐的是哪条。 */
    private static final java.util.Map<String, String> PURIFY_BLOCKED_CALLS =
            new java.util.HashMap<String, String>() {{
                put("DiversionStrategyRequestCall",
                        "/api/installer/diversion/v1/strategy/query");
                put("AdDetailsV2RequestCall",
                        "/api/installer/business/v2/queryAdvertisement");
                put("AdDetailsV3RequestCall",
                        "/api/installer/business/v3/ad/query");
                put("AdDetailsV4RequestCall",
                        "/api/installer/business/v5/ad/query");
                // 广告族的请求入口：这不是"安全检测"，但同属净化范围。
                // 只补广告类，不碰其它网络请求（一刀切会误伤安装流程）。
                // 路径以实际反编译源码为准；点/曝光没有固定路径（URL 由调用方
                // 运行期传入），这里只登记类名，不再编造伪路径。
                put("AdDetailsRequestCall",
                        "/api/v1/installerapi/business/queryAdvertise");
                put("AdClickEventRequestCall", "");
                put("AdExposureEventRequestCall", "");
                put("AdFilterStrategyRequestCall",
                        "/api/v1/installerapi/business/queryFilterPolicy");
                put("AdUnshownReportCall",
                        "/api/installer/business/v4/ad/reportUnshown");
                put("SecurityStrategyCall",
                        "/api/installer/config/v1/offlinepolicy/query");
                put("HighRiskListRequestCall",
                        "/api/installer/config/v1/offlinelist/query");
                put("SecurityDetectV3RequestCall",
                        "/api/installer/detect/v3/securitydetect/scan");
                put("SecurityDetectV2RequestCall",
                        "/api/installerapi/appcheck/v3/securitydetect");
                put("SecurityDetectRequestCall",
                        "/api/v1/installerapi/appcheck/securitydetect");
                put("DetectDelayRequestCall",
                        "/api/installer/detect/v1/detectdelay/query");
                put("NoDetectRequestCall",
                        "/api/installer/detect/v1/nodetect/query");
                put("TencentNoDetectRequestCall",
                        "/api/installer/detect/*tencent*");
                put("WebInterceptStrategyCall",
                        "/api/installer/business/v1/webblocking/query");
                put("AbExpStrategyRequestCall",
                        "/api/installer/abtest/v1/commonAb/query");
                put("UninstallConfigRequestCall",
                        "/api/installer/uninstall/v1/config/query");
                put("CommonConfigRequestCall",
                        "/api/system/updater/v1/installer/commonconfig");
            }};

    /** 实际请求 URL；旧版本类名变了也拦得住。取不到返回 null。 */
    private static String callUrl(Object call) {
        try {
            Object retro = XposedHelpers.getObjectField(call, "mResultCall");
            if (retro == null) return null;
            Object req = XposedHelpers.callMethod(retro, "request");
            if (req == null) return null;
            Object url = XposedHelpers.callMethod(req, "url");
            return url == null ? null : url.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 安全检测 / 高风险名单 / 广告查询的 URL 锚：覆盖老路径与改名后的类。 */
    private static boolean securityAnchor(String url) {
        if (url == null) return false;
        String u = url.toLowerCase(Locale.US);
        return u.contains("/securitydetect")
                || u.contains("/detectdelay")
                || u.contains("/querydetectiondelay")
                || u.contains("/nodetect")
                || u.contains("/tencent")
                || u.contains("/offlinelist")
                // 广告查询（AdDetails 系列）；只认这几条明确路径，不扩大成整类广告流量。
                || u.contains("/queryadvertisement")
                // 新一代安装器的广告/策略路径（类名会被 OTA 改掉，锚兜住）。
                || u.contains("/business/queryadvertise")
                || u.contains("/business/queryfilterpolicy")
                || u.contains("/ad/reportunshown")
                || u.contains("/api/installer/detect/");
    }

    private static void hookPurifyRequests(ClassLoader cl) {
        try {
            Class<?> base = cl.loadClass(
                    "com.hihonor.packageinstaller.net.request.BaseCall");
            XposedBridge.hookAllMethods(base, "execute", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!defaultPurify()) return;
                    String cn = param.thisObject.getClass().getSimpleName();
                    String path = PURIFY_BLOCKED_CALLS.get(cn);
                    if (path == null) {
                        // 类名未登记：用实际 URL 兜底，覆盖旧版本改名后的检测类
                        String u = callUrl(param.thisObject);
                        if (!securityAnchor(u)) return;
                        path = u;
                    }
                    String url = callUrl(param.thisObject);
                    if (url == null) url = path;
                    try {
                        Object failed = XposedHelpers.callMethod(param.thisObject,
                                "buildResultForCallFailed", -1001,
                                "blocked by HonorAdvancedSettings purify");
                        param.setResult(failed);
                        Diag.log("[net] request blocked: "
                                + (url == null || url.length() == 0 ? "(url unavailable)" : url)
                                + " via " + cn + " (" + ver() + ")");
                    } catch (Throwable t) {
                        Diag.log("[net] request block failed for " + url + ": " + t);
                    }
                }
            });
            Diag.log("[net] purify request gate hooked (" + PURIFY_BLOCKED_CALLS.size()
                    + " blocked calls incl. security detect + high-risk list, url-anchor on, ver="
                    + ver() + ")");
        } catch (Throwable t) {
            absent("purify request gate / BaseCall.execute");
        }
    }

    private static void hookDiversionWaitCompatV2(ClassLoader cl) {
        try {
            // 38.301 xt1.O and 14.0.22.x nz0.g share the exact signature
            // (DivideButtonModule) -> boolean — one rule covers both.
            // 38.301 xt1.O · 14.0.22.x nz0.g · 14.0.39.301 hu1.P — the
            // (DivideButtonModule) -> boolean shape is unchanged
            Class<?>[] cs = locateAll(new String[]{"xt1", "nz0", "hc2", "pz0", "hu1"},
                    "WebInterceptStrategyUtil", "LinkTrafficUtils");
            Method m = resolveAny(cs, "boolean",
                    "com.hihonor.packageinstaller.entity.resp.DivideButtonModule");
            if (m == null) { absent("diversion wait entry"); return; }
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (defaultPurify()) param.setResult(false);
                }
            });
            Diag.log("[adapt] diversion hooked: " + m);
        } catch (Throwable t) {
            absent("diversion wait hook");
        }
    }

    private static void hookCleanCacheCardCompatV2(ClassLoader cl) {
        try {
            // 38.301 xt1.t and 14.0.22.x nz0.d share the exact signature
            // (CleanSpeedUpModule, boolean, boolean) -> boolean.
            // 14.0.39.301: hu1.u(CleanSpeedUpModule, boolean, boolean) ->
            // boolean — same shape as the 38.301 / 14.0.22.x rule
            Class<?>[] cs = locateAll(new String[]{"xt1", "nz0", "hc2", "pz0", "hu1"},
                    "WebInterceptStrategyUtil", "LinkTrafficUtils");
            Method m = resolveAny(cs, "boolean",
                    "com.hihonor.packageinstaller.entity.resp.CleanSpeedUpModule",
                    "boolean", "boolean");
            if (m == null) { absent("clean-cache entry"); return; }
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (defaultPurify()) param.setResult(true);
                }
            });
            Diag.log("[adapt] clean-cache hooked: " + m);
        } catch (Throwable t) {
            absent("clean-cache hook");
        }
    }

    private static void hookSafeModeBannerCompatV2(ClassLoader cl) {
        try {
            // card-tips gate: 38.301 lc1.D1 is (CardTipsRecord, String,
            // boolean, String) -> boolean; 14.0.22.x js0.f is the 3-arg
            // ancestor; 14.0.39.301 is tc1.C1 (same 4-arg shape). The class
            // no longer references "AdNetworkUtil" on 326/327/39.301, so the
            // strict {AdNetworkUtil, SafeModeShowUtils} set only covers the
            // old builds; the single-string set covers the new ones.
            Class<?>[] cs = locateAllUnion(new String[]{"lc1", "js0", "vz1", "tc1"},
                    new String[]{"AdNetworkUtil", "SafeModeShowUtils"},
                    new String[]{"SafeModeShowUtils"});
            Method m = resolveAny(cs, "boolean",
                    "com.hihonor.packageinstaller.utils.CardTipsRecord",
                    "java.lang.String", "boolean", "java.lang.String");
            if (m == null) {
                m = resolveAny(cs, "boolean",
                        "com.hihonor.packageinstaller.utils.CardTipsRecord",
                        "java.lang.String", "boolean");
            }
            if (m != null) {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!defaultPurify()) return;
                        param.setResult(on(Cfg.K_INFOCARD));
                    }
                });
                Diag.log("[adapt] card-tips gate hooked: " + m);
            } else {
                absent("card-tips gate");
            }
            // 14.0.22.x only: js0.k(PIA, RbiTempBean) preinstall-tip card —
            // absent on 38.301, resolveByParams simply misses and we move on
            Method k = resolveAny(cs, "void",
                    "com.android.packageinstaller.PackageInstallerActivity",
                    "com.hihonor.packageinstaller.rbi.RbiTempBean");
            if (k != null) {
                XposedBridge.hookMethod(k, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (defaultPurify() && on(Cfg.K_INFOCARD)) param.setResult(null);
                    }
                });
                Diag.log("[adapt] preinstall-tip hooked: " + k);
            } else if (!sPreinstallAbsent) {
                sPreinstallAbsent = true;
                // whole-app signature scan: 14.0.39.301 dropped the
                // (PIA, RbiTempBean) preinstall card entirely — behavioural
                // gap, not a resolver miss. Keep the hook for older builds.
                absent("preinstall-tip card");
            }
        } catch (Throwable t) {
            absent("card-tips hook");
        }
    }

    private static void hookSafeModeCardInfoCompatV2(ClassLoader cl) {
        try {
            // info-card fill: 38.301 lc1.u2 · 14.0.22.x js0.j ·
            // 14.0.39.301 tc1.s2 — all share (View, String, Activity,
            // boolean) -> void. Same anchor-set split as the card-tips gate.
            Class<?>[] cs = locateAllUnion(new String[]{"lc1", "js0", "vz1", "tc1"},
                    new String[]{"AdNetworkUtil", "SafeModeShowUtils"},
                    new String[]{"SafeModeShowUtils"});
            Method m = resolveAny(cs, "void",
                    "android.view.View", "java.lang.String",
                    "android.app.Activity", "boolean");
            if (m == null) { absent("info-card entry"); return; }
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!defaultPurify() || !on(Cfg.K_INFOCARD)) return;
                    try {
                        fillInfoCard((View) param.args[0], (Activity) param.args[2], 0);
                    } catch (Throwable t) {
                        Diag.log("info card error: " + t);
                    }
                    param.setResult(null);
                }
            });
            Diag.log("[adapt] info-card hooked: " + m);
        } catch (Throwable t) {
            absent("info-card hook");
        }
    }

    /**
     * Staging wait: InstallStaging's presenter po1.a (checkInstalledPkg) waits
     * for BOTH a Tencent no-detect strategy request and a diversion request
     * (hard-coded 2000ms timeout) before jumping to the installer page — with
     * networking blocked these just burn the timeout. Skip it and take the
     * stock timeout path p(-1) (jumpActivity, "extra_tencent_no_detect_result"
     * = -1) immediately.
     */
    private static void hookStagingFastPathCompatV2(ClassLoader cl) {
        try {
            // po1.a's 16-parameter shape is entirely framework types — the
            // most unique signature in the app; resolve by shape, not name
            // (param count verified against the 38.301 decompile).
            // 14.0.39.301: ap1.a keeps the 16-param shape and field "a" is
            // still a WeakReference to InstallStaging (lm1), so the body below
            // (get() -> isActive() -> p(-1)) is unchanged.
            Class<?>[] cs = locateAll(new String[]{"po1", "eu1", "ap1"},
                    "InstallStagingPresenter", "TencentNoDetectUtils");
            Method m = resolveAny(cs, "void",
                    "java.lang.String", "java.lang.String", "java.lang.String",
                    "java.lang.String", "java.lang.String", "long",
                    "java.lang.String", "java.lang.String", "java.lang.String",
                    "int", "int", "long", "int", "int",
                    "java.lang.String", "java.lang.String");
            if (m == null) { absent("staging presenter entry"); return; }
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!defaultPurify()) return;
                    try {
                        Object ref = XposedHelpers.getObjectField(param.thisObject, "a");
                        Object staging = XposedHelpers.callMethod(ref, "get");
                        if (staging != null
                                && (Boolean) XposedHelpers.callMethod(staging, "isActive")) {
                            XposedHelpers.callMethod(staging, "p", -1);
                            Diag.log("staging fast path (skip network waits)");
                        }
                    } catch (Throwable t) {
                        Diag.log("staging fast path error: " + t);
                    }
                    param.setResult(null);
                }
            });
            Diag.log("[adapt] staging fast-path hooked: " + m);
        } catch (Throwable t) {
            absent("staging presenter hook");
        }
    }

    /**
     * The delivered pass renders a "checked" section — a green "未发现风险"
     * card. Hiding it no longer hooks any per-build render method or view
     * field (those names rotate every obfuscation round): the card is the
     * ViewStub `view_stub_security` inflated as `app_install_check_security_
     * result` — a stable resource NAME on every known build (verified on
     * 38.301 and 32.303).
     *
     * Reaching the PIA activity from the delivery callback needs more than
     * the this$0 chain: on both known builds the callback's outer object is
     * the TOP-LEVEL detect controller (bx1/dd1), which is not an inner class
     * — the chain dies there. But that controller holds the activity in a
     * field (bx1.n), so findActivity walks the outer chain AND scans each
     * hop's fields for an Activity instance. The hide runs IMMEDIATELY after
     * delivery (the render happens synchronously inside the delivery call,
     * so the stub is already inflated by the time we look) and falls back to
     * a short poll (300ms x 10) for builds that render later.
     */
    /** 只隐藏“查找类似应用”这一个入口，按精确文本匹配，不碰按钮容器。 */
    private static void hideSimilarAppEntry(Activity act) {
        if (act == null) return;
        for (long d : new long[]{0L, 600L, 1500L}) {
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        java.util.ArrayList<android.widget.TextView> out =
                                new java.util.ArrayList<>();
                        collectTextViews(act.getWindow().getDecorView(), out);
                        for (android.widget.TextView tv : out) {
                            CharSequence cs = tv.getText();
                            if (cs != null && "查找类似应用".contentEquals(cs)
                                    && tv.getVisibility() == View.VISIBLE) {
                                hideGuarded(act, tv, "similar-app");
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }, d);
        }
    }

    /** 隐藏单个安全控件；400ms 后校验“安装/取消/信息卡”仍在且可见，不在就回滚。 */
    private static void hideGuarded(final Activity act, final View v, final String what) {
        if (act == null || v == null || v.getVisibility() != View.VISIBLE) return;
        v.setVisibility(View.GONE);
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!coreViewsReady(act)) return;
                    View card = act.getWindow().getDecorView()
                            .findViewWithTag("purify_info_card");
                    if (card == null || !card.isShown()) {
                        v.setVisibility(View.VISIBLE);
                        Diag.log("[adapt] rollback: info card missing (" + what + ")");
                    } else if (!coreViewsShown(act)) {
                        v.setVisibility(View.VISIBLE);
                        Diag.log("[adapt] rollback: core view missing (" + what + ")");
                    } else {
                        Diag.log("[adapt] purified: hidden " + what);
                    }
                } catch (Throwable ignored) {
                }
            }
        }, 400L);
    }

    private static boolean coreViewsReady(Activity act) {
        return hasVisibleText(act, "安装") || hasVisibleText(act, "继续安装")
                || hasVisibleText(act, "取消");
    }

    private static boolean coreViewsShown(Activity act) {
        boolean install = hasVisibleText(act, "安装") || hasVisibleText(act, "继续安装");
        boolean cancel = hasVisibleText(act, "取消");
        View card = act.getWindow().getDecorView().findViewWithTag("purify_info_card");
        return install && cancel && card != null && card.isShown();
    }

    private static boolean hasVisibleText(Activity act, String want) {
        java.util.ArrayList<android.widget.TextView> out = new java.util.ArrayList<>();
        collectTextViews(act.getWindow().getDecorView(), out);
        for (android.widget.TextView tv : out) {
            if (tv.getVisibility() != View.VISIBLE || !tv.isShown()) continue;
            CharSequence cs = tv.getText();
            if (cs != null && want.contentEquals(cs.toString().trim())) return true;
        }
        return false;
    }

    private static void collectTextViews(View v, java.util.List<android.widget.TextView> out) {
        if (v == null) return;
        if (v instanceof android.widget.TextView) out.add((android.widget.TextView) v);
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                collectTextViews(g.getChildAt(i), out);
            }
        }
    }

    /** 安全卡多遍补藏：0 / 600 / 1500 / 3000 ms，防止延迟渲染后再冒出来。 */
    private static void scheduleHideSecurityCard(Object callbackOrActivity) {
        try {
            final Activity act = findActivity(callbackOrActivity);
            if (act == null) {
                Diag.log("[adapt] hide card: no activity reached from callback");
                return;
            }
            final int resid = act.getResources().getIdentifier(
                    "app_install_check_security_result", "id", act.getPackageName());
            if (resid == 0) return;
            final long[] delays = {0L, 600L, 1500L, 3000L};
            for (long d : delays) {
                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            View v = act.findViewById(resid);
                            if (v != null && v.getVisibility() == View.VISIBLE) {
                                hideGuarded(act, v, "security-card");
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                }, d);
            }
        } catch (Throwable t) {
            Diag.log("hide card error: " + t);
        }
    }

    /** Walks the synthetic-outer chain; at every hop also scans the object's
     *  fields for an Activity instance — controllers are top-level classes
     *  that keep the PIA in a plain field, not in an inner-class reference. */
    private static Activity findActivity(Object start) {
        Object cur = start;
        for (int i = 0; i < 6 && cur != null; i++) {
            if (cur instanceof Activity) return (Activity) cur;
            try {
                for (java.lang.reflect.Field f : cur.getClass().getDeclaredFields()) {
                    if (!Activity.class.isAssignableFrom(f.getType())) continue;
                    f.setAccessible(true);
                    Object v = f.get(cur);
                    if (v instanceof Activity) return (Activity) v;
                }
            } catch (Throwable ignored) {
            }
            try {
                cur = XposedHelpers.getObjectField(cur, "this$0");
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }
}
