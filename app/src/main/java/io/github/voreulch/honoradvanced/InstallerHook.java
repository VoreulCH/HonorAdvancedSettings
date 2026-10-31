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
 * 荣耀安装器净化 — adaptive set (v2.0.0).
 * The purify master switch gates every hook; info-card and downgrade hooks
 * also honor their own switches. All values are read through CfgProvider
 * (default ON). Toggling takes effect on the next install, no restart needed.
 *
 * Every hook point resolves ADAPTIVELY at load time: classes via DexKit
 * string anchors (survive class renames) with per-build name fallbacks,
 * methods via parameter-type signature scanning (survive method renames —
 * signatures anchor on stable entity full names like DivideButtonModule /
 * CleanSpeedUpModule / HnControlResult). The pass-result delivery tries the
 * 38.301 recipe (dd1(HnControlResult) via type) then the 14.0.22.x recipe
 * (static factory + single-arg callback). Known builds: 14.0.38.301 and
 * 14.0.22.326; future OTA reshuffles resolve as long as the anchored
 * entities and callback shapes hold. Glass line hooks live in GlassHook.
 */
public final class InstallerHook {
    private static final String TAG = "[HAS-Purify] ";
    private static String sApk;
    private static boolean purifyOn() {
        return Cfg.isOn(Cfg.K_PURIFY);
    }

    /** 14.0.38.301 set — active whenever the master is on, no compat switch. */
    private static boolean defaultPurify() {
        return purifyOn();
    }

    private static boolean on(String key) {
        return purifyOn() && Cfg.isOn(key);
    }

    public static void init(ClassLoader cl, String apkPath) {
        sApk = apkPath;
        HookEnv.cl = cl;
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
                            if (on(Cfg.K_DOWNGRADE)) param.setResult(true);
                        }
                    });
                    n++;
                }
            }
            XposedBridge.log(TAG + "system_server isDowngradePermitted hooked: " + n);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "system_server downgrade hook failed: " + t);
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
                            Object sp = param.args[0];
                            if (sp != null) {
                                int flags = XposedHelpers.getIntField(sp, "installFlags");
                                XposedHelpers.setIntField(sp, "installFlags", flags | 0x80);
                            }
                        }
                    });
            XposedBridge.log(TAG + "hook createSession(allow downgrade) ok");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "hook createSession failed: " + t);
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
            XposedBridge.log(TAG + "hook Socket.connect ok");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "hook Socket.connect failed: " + t);
        }
    }

    // ==================== risk-install dialog ====================

    /**
     * "风险提示" install popup — the only risk-tip dialog in the installer:
     * `com.android.packageinstaller.fragment.InstallRiskTipDialog` (the
     * class name survives every known build; 38.301 builds it from
     * PIA.R0(12), 14.0.22.x/32.303 from the static factory i("")). It shows
     * the stock `risk_warning` / `check_risk_poptip_high_risk` text with a
     * "继续安装" button and otherwise blocks the install flow.
     *
     * When purify is on we answer it the same way a user tapping "继续安装"
     * would: hook the fragment's onStart (the dialog is already attached at
     * that point) and click the positive button. This reuses the stock
     * continuation for every build (PIA.D0 / paging Q1-R1 / dr0.l), so a
     * future installer reshuffle cannot strand the flow. Capped at 10 per
     * process in case a build re-opens the dialog in a loop.
     *
     * Pure addition: purify off → stock dialog, no interception.
     */
    private static int sRiskAutoCount = 0;

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
                    try {
                        if (sRiskAutoCount >= 10) return;
                        Object d = XposedHelpers.callMethod(param.thisObject, "getDialog");
                        if (!(d instanceof android.app.AlertDialog)) return;
                        android.widget.Button ok = ((android.app.AlertDialog) d)
                                .getButton(android.content.DialogInterface.BUTTON_POSITIVE);
                        if (ok == null) return;
                        sRiskAutoCount++;
                        XposedBridge.log(TAG + "[adapt] risk install dialog auto-continue ("
                                + sRiskAutoCount + ")");
                        ok.performClick();
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + "risk dialog auto-continue error: " + t);
                    }
                }
            });
            if (unhooks == null || unhooks.isEmpty()) {
                XposedBridge.log(TAG + "risk-tip dialog has no onStart override, skip");
                return;
            }
            XposedBridge.log(TAG + "[adapt] risk-tip dialog auto-continue hooked");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "risk-tip dialog hook skipped: " + t);
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
        XposedBridge.log(TAG + "[anon] " + msg);
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
     *   4. +400ms: still showing -> restore alpha 1.0, so the user gets a
     *      visible, tappable dialog instead of an invisible window that eats
     *      touches. This is the hard floor: no path may leave alpha 0 behind.
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
                XposedBridge.log(TAG + "[adapt] anonymous-source onStart no anchor, fallback only");
                return;
            }
            XposedBridge.log(TAG + "[adapt] anonymous-source zero-popup installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "anonymous-source zero-popup hook skipped: " + t);
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
                        XposedBridge.log(TAG + "anonymous-source fallback error: " + t);
                    }
                }
            });
            if (unhooks == null || unhooks.isEmpty()) {
                XposedBridge.log(TAG + "[adapt] anonymous-source no anchor (onCreateDialog missing)");
                return;
            }
            XposedBridge.log(TAG + "[adapt] anonymous-source fallback installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "anonymous-source fallback hook skipped: " + t);
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
            // second path to the security-result card: this hook holds the
            // activity directly, so it also covers builds where the delivery
            // callback can't be unwrapped to the PIA
            scheduleHideSecurityCard(activity);
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
            XposedBridge.log(TAG + "[dg] card pkg=" + pkgName
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
            View v = card.findViewById(idSuggest);
            if (v != null) {
                v.setVisibility(View.GONE);
            }
        }
        if (idBtn != 0) {
            View v = card.findViewById(idBtn);
            if (v != null) {
                v.setVisibility(View.GONE);
            }
        }
        if (idDes != 0) {
            TextView tv = (TextView) card.findViewById(idDes);
            if (tv != null) {
                tv.setOnClickListener(null);
                tv.setText(buildInfoText(newInfo, oldInfo, pkgName));
            }
        }
        addAuthorLink(card, activity);
        XposedBridge.log(TAG + "info card filled for " + pkgName);
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
                    XposedBridge.log(TAG + "open repo error: " + t);
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
                XposedBridge.log(TAG + "[adapt] dexkit anchor hit: " + c.getName());
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
            Method m = null;
            for (Class<?> c : cs) {
                for (Method mm : c.getDeclaredMethods()) {
                    Class<?>[] ps = mm.getParameterTypes();
                    if (ps.length == 2 && ps[0].isInterface() && ps[1] == boolean.class
                            && mm.getReturnType() == void.class
                            && carriesResultDelivery(ps[0])) { m = mm; break; }
                }
                if (m != null) break;
                for (Method mm : c.getDeclaredMethods()) {
                    Class<?>[] ps = mm.getParameterTypes();
                    if (ps.length == 1 && ps[0].isInterface()
                            && mm.getReturnType() == void.class
                            && carriesResultDelivery(ps[0])) { m = mm; break; }
                }
                if (m != null) break;
            }
            if (m == null) { XposedBridge.log(TAG + "security-check method not resolved, skip"); return; }
            final Method target = m;
            XposedBridge.hookMethod(target, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!defaultPurify()) return;
                    Object callback = param.args[0];
                    if (callback == null) { param.setResult(null); return; }
                    // two pass recipes, tried in order — each resolves every
                    // collaborator by TYPE, never by obfuscated name
                    if (!deliverPass383(param, callback)) {
                        deliverPass326(param, callback);
                    }
                    param.setResult(null);
                }
            });
            XposedBridge.log(TAG + "[adapt] security-check hooked: " + target);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "security-check adaptive hook failed: " + t);
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
    private static boolean deliverPass383(XC_MethodHook.MethodHookParam param, Object callback) {
        try {
            Class<?> hcr = HookEnv.cl.loadClass(
                    "com.hihonor.packageinstaller.hncust.checkapk.HnControlResult");
            Object r = buildPassResult(param.thisObject, hcr);
            if (r == null) return false;
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
                for (java.lang.reflect.Field f : param.thisObject.getClass().getDeclaredFields()) {
                    if (f.getType() == ps[1]) {
                        f.setAccessible(true);
                        Object v = f.get(param.thisObject);
                        if (v != null) { v2 = v; break; }
                    }
                }
                dm.setAccessible(true);
                dm.invoke(callback, pass, v2);
                XposedBridge.log(TAG + "[adapt] security check skipped (383 recipe)");
                scheduleHideSecurityCard(callback);
                return true;
            }
            return false;
        } catch (Throwable t) {
            XposedBridge.log(TAG + "383 recipe error: " + t);
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
                    if (safeIntField(cand, "e") == 1 && safeBoolField(cand, "b")) {
                        XposedBridge.log(TAG + "[adapt] using controller default pass result");
                        return cand;
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        // hand-built fallback: pinned semantic fields, set individually so a
        // missing one degrades instead of killing the recipe
        try {
            Object r = XposedHelpers.newInstance(hcr);
            try { XposedHelpers.setBooleanField(r, "s", true); } catch (Throwable ignored) { }
            try { XposedHelpers.setBooleanField(r, "b", true); } catch (Throwable ignored) { }
            try { XposedHelpers.setIntField(r, "e", 1); } catch (Throwable ignored) { }
            return r;
        } catch (Throwable t) {
            return null;
        }
    }

    private static int safeIntField(Object o, String name) {
        try {
            return o.getClass().getField(name).getInt(o);
        } catch (Throwable t) {
            return -1;
        }
    }

    private static boolean safeBoolField(Object o, String name) {
        try {
            return o.getClass().getField(name).getBoolean(o);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 14.0.22.x recipe: static result factory (boolean) on the controller,
     *  single-arg delivery on the callback, posted to the main thread. */
    private static boolean deliverPass326(XC_MethodHook.MethodHookParam param, Object callback) {
        try {
            Class<?> c = param.thisObject.getClass();
            boolean isHn = false;
            try {
                isHn = (Boolean) XposedHelpers.callMethod(param.thisObject, "d");
            } catch (Throwable t) {
                for (Method mm : c.getDeclaredMethods()) {
                    if (mm.getParameterTypes().length == 0 && mm.getReturnType() == boolean.class) {
                        mm.setAccessible(true);
                        isHn = (Boolean) mm.invoke(param.thisObject);
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
                            XposedBridge.log(TAG + "326 deliver error: " + t);
                        }
                    }
                    XposedBridge.log(TAG + "[adapt] security check skipped (326 recipe, " + ds.length + " channel(s))");
                    scheduleHideSecurityCard(cb);
                }
            });
            return true;
        } catch (Throwable t) {
            XposedBridge.log(TAG + "326 recipe error: " + t);
            return false;
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
            if (m == null) { XposedBridge.log(TAG + "fingerprint method not resolved, skip"); return; }
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!defaultPurify()) return;
                    try {
                        Object cb = param.args[param.args.length - 1];
                        if (cb != null) {
                            cb.getClass().getMethod("b").invoke(cb);
                            cb.getClass().getMethod("a", boolean.class).invoke(cb, true);
                            XposedBridge.log(TAG + "fingerprint auth bypassed");
                        }
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + "fingerprint error: " + t);
                    }
                    param.setResult(null);
                }
            });
            XposedBridge.log(TAG + "[adapt] fingerprint hooked: " + m);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "fingerprint adaptive hook failed: " + t);
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
            if (m == null) { XposedBridge.log(TAG + "ad method not resolved, skip"); return; }
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (defaultPurify()) param.setResult(null);
                }
            });
            XposedBridge.log(TAG + "[adapt] ad hooked: " + m);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "ad adaptive hook failed: " + t);
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
            if (m == null) { XposedBridge.log(TAG + "diversion method not resolved, skip"); return; }
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (defaultPurify()) param.setResult(false);
                }
            });
            XposedBridge.log(TAG + "[adapt] diversion hooked: " + m);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "diversion adaptive hook failed: " + t);
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
            if (m == null) { XposedBridge.log(TAG + "clean-cache method not resolved, skip"); return; }
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (defaultPurify()) param.setResult(true);
                }
            });
            XposedBridge.log(TAG + "[adapt] clean-cache hooked: " + m);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "clean-cache adaptive hook failed: " + t);
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
                XposedBridge.log(TAG + "[adapt] card-tips gate hooked: " + m);
            } else {
                XposedBridge.log(TAG + "card-tips gate not resolved, skip");
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
                XposedBridge.log(TAG + "[adapt] preinstall-tip hooked: " + k);
            } else if (!sPreinstallAbsent) {
                sPreinstallAbsent = true;
                // whole-app signature scan: 14.0.39.301 dropped the
                // (PIA, RbiTempBean) preinstall card entirely — behavioural
                // gap, not a resolver miss. Keep the hook for older builds.
                XposedBridge.log(TAG + "[adapt] preinstall-tip absent on this build (expected on 39.301)");
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "card-tips adaptive hook failed: " + t);
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
            if (m == null) { XposedBridge.log(TAG + "info-card method not resolved, skip"); return; }
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!defaultPurify() || !on(Cfg.K_INFOCARD)) return;
                    try {
                        fillInfoCard((View) param.args[0], (Activity) param.args[2], 0);
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + "info card error: " + t);
                    }
                    param.setResult(null);
                }
            });
            XposedBridge.log(TAG + "[adapt] info-card hooked: " + m);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "info-card adaptive hook failed: " + t);
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
            if (m == null) { XposedBridge.log(TAG + "staging method not resolved, skip"); return; }
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
                            XposedBridge.log(TAG + "staging fast path (skip network waits)");
                        }
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + "staging fast path error: " + t);
                    }
                    param.setResult(null);
                }
            });
            XposedBridge.log(TAG + "[adapt] staging fast-path hooked: " + m);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "staging adaptive hook failed: " + t);
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
    private static void scheduleHideSecurityCard(Object callbackOrActivity) {
        try {
            final Activity act = findActivity(callbackOrActivity);
            if (act == null) {
                XposedBridge.log(TAG + "[adapt] hide card: no activity reached from callback");
                return;
            }
            final int resid = act.getResources().getIdentifier(
                    "app_install_check_security_result", "id", act.getPackageName());
            if (resid == 0) return;
            Runnable hide = new Runnable() {
                int tries = 0;
                @Override
                public void run() {
                    try {
                        View v = act.findViewById(resid);
                        if (v == null) {                    // stub not inflated yet
                            if (++tries < 10) {
                                new Handler(Looper.getMainLooper()).postDelayed(this, 300);
                            }
                            return;
                        }
                        if (v.getVisibility() == View.VISIBLE) {
                            v.setVisibility(View.GONE);
                            XposedBridge.log(TAG + "[adapt] security result card hidden");
                        }
                    } catch (Throwable ignored) {
                    }
                }
            };
            // immediate: the delivery call rendered the card synchronously —
            // hiding on the next main-loop tick beats it to the first frame
            new Handler(Looper.getMainLooper()).post(hide);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "hide card error: " + t);
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
