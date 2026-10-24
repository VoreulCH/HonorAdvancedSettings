package io.github.voreulch.honoradvanced;

import android.app.Activity;
import android.content.Context;
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
    private static final String K_INFOCARD = Cfg.K_INFOCARD;
    private static final String K_DOWNGRADE = Cfg.K_DOWNGRADE;

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
        hookHideCheckResultCompatV2(cl);
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
                            if (on(K_DOWNGRADE)) param.setResult(true);
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
                            if (!on(K_DOWNGRADE)) return;
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

    // ==================== info-card helpers (used by V2) ====================

    private static void fillInfoCard(final View card, final Activity activity, final int attempt) {
        if (card == null || activity == null) {
            return;
        }
        if (!"com.android.packageinstaller.PackageInstallerActivity".equals(activity.getClass().getName())) {
            card.setVisibility(View.GONE);
            return;
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
        try {
            pkgName = (String) XposedHelpers.getObjectField(activity, "x0");
        } catch (Throwable ignored) {
        }
        if (pkgName == null) {
            try {
                pkgName = (String) XposedHelpers.getObjectField(activity, "u0");
            } catch (Throwable ignored) {
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
        if (pkgName == null || pkgName.length() == 0) {
            pkgName = newInfo.packageName;
        }

        PackageManager pm = activity.getPackageManager();
        PackageInfo oldInfo = null;
        try {
            oldInfo = pm.getPackageInfo(pkgName, 0);
        } catch (Throwable ignored) {
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
        StringBuilder ck = new StringBuilder();
        for (String a : anchors) ck.append(a).append('|');
        ck.append('#');
        for (String n : knownNames) ck.append(n).append(',');
        String key = ck.toString();
        Class<?>[] cached = sLocateCache.get(key);
        if (cached != null) return cached;
        java.util.ArrayList<Class<?>> out = new java.util.ArrayList<>();
        Class<?> c = DexKitHelper.findClassByStrings(sApk, HookEnv.cl, anchors);
        if (c != null) {
            out.add(c);
            XposedBridge.log(TAG + "[adapt] dexkit anchor hit: " + c.getName());
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

    private static void hookSecurityCheckCompatV2(final ClassLoader cl) {
        try {
            // 38.301 ed1.b(cb, boolean) vs 14.0.22.x br0.j(cb): in both the
            // callback is the FIRST parameter and is an interface.
            Class<?>[] cs = locateAll(new String[]{"ed1", "br0"}, "HnAppCheckJob", "insertRiskAppDb");
            Method m = null;
            for (Class<?> c : cs) {
                for (Method mm : c.getDeclaredMethods()) {
                    Class<?>[] ps = mm.getParameterTypes();
                    if (ps.length == 2 && ps[0].isInterface() && ps[1] == boolean.class
                            && mm.getReturnType() == void.class) { m = mm; break; }
                }
                if (m != null) break;
                for (Method mm : c.getDeclaredMethods()) {
                    Class<?>[] ps = mm.getParameterTypes();
                    if (ps.length == 1 && ps[0].isInterface()
                            && mm.getReturnType() == void.class) { m = mm; break; }
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

    /** 38.301 recipe: new dd1(new HnControlResult{s,b,e}) delivered through
     *  the callback's two-arg method — dd1 located via its (HnControlResult)
     *  constructor, the second arg via a same-typed field of the controller. */
    private static boolean deliverPass383(XC_MethodHook.MethodHookParam param, Object callback) {
        try {
            Class<?> hcr = HookEnv.cl.loadClass(
                    "com.hihonor.packageinstaller.hncust.checkapk.HnControlResult");
            Object r = XposedHelpers.newInstance(hcr);
            // field names ARE obfuscated per build and carry semantics that
            // cannot be told apart reflectively — these three stay pinned to
            // the 38.301 line (s=cloud verified, b=install button, e=icon
            // color SAFE; the "virus warning" incident came from e missing).
            XposedHelpers.setBooleanField(r, "s", true);
            XposedHelpers.setBooleanField(r, "b", true);
            XposedHelpers.setIntField(r, "e", 1);
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
                return true;
            }
            return false;
        } catch (Throwable t) {
            XposedBridge.log(TAG + "383 recipe error: " + t);
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
            for (final Method dm : callback.getClass().getMethods()) {
                Class<?>[] ps = dm.getParameterTypes();
                if (ps.length == 1 && ps[0] == passResult.getClass()
                        && dm.getReturnType() == void.class) {
                    dm.setAccessible(true);
                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                dm.invoke(callback, passResult);
                                XposedBridge.log(TAG + "[adapt] security check skipped (326 recipe)");
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + "326 deliver error: " + t);
                            }
                        }
                    });
                    return true;
                }
            }
            return false;
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
            Class<?>[] cs = locateAll(new String[]{"lt1", "c01"},
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
            Class<?>[] cs = locateAll(new String[]{"xt1", "nz0"},
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
            Class<?>[] cs = locateAll(new String[]{"xt1", "nz0"},
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
            // ancestor. Try both signatures across all candidates.
            Class<?>[] cs = locateAll(new String[]{"lc1", "js0"},
                    "AdNetworkUtil", "AdCategoryStateListenerImpl");
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
                        param.setResult(on(K_INFOCARD));
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
                        if (defaultPurify() && on(K_INFOCARD)) param.setResult(null);
                    }
                });
                XposedBridge.log(TAG + "[adapt] preinstall-tip hooked: " + k);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "card-tips adaptive hook failed: " + t);
        }
    }

    private static void hookSafeModeCardInfoCompatV2(ClassLoader cl) {
        try {
            // info-card fill: 38.301 lc1.u2 and 14.0.22.x js0.j share the
            // exact signature (View, String, Activity, boolean) -> void.
            Class<?>[] cs = locateAll(new String[]{"lc1", "js0"},
                    "AdNetworkUtil", "AdCategoryStateListenerImpl");
            Method m = resolveAny(cs, "void",
                    "android.view.View", "java.lang.String",
                    "android.app.Activity", "boolean");
            if (m == null) { XposedBridge.log(TAG + "info-card method not resolved, skip"); return; }
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!defaultPurify() || !on(K_INFOCARD)) return;
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
            // po1.a's 18-parameter shape is entirely framework types — the
            // most unique signature in the app; resolve by shape, not name.
            Class<?>[] cs = locateAll(new String[]{"po1"},
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
     * card. Method and view-stub field names differ per build (38.301:
     * b1 + r0/t0; 14.0.22.x: l1/r1 + T0/V0) — register every known combo,
     * misses fail soft, exactly one ever matches a given build.
     */
    private static void hookHideCheckResultCompatV2(ClassLoader cl) {
        String[][] combos = {
                {"b1", "r0"}, {"b1", "t0"},
                {"l1", "T0"}, {"r1", "V0"},
        };
        int n = 0;
        for (final String[] combo : combos) {
            try {
                XposedHelpers.findAndHookMethod("com.android.packageinstaller.PackageInstallerActivity",
                        cl, combo[0], new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                if (!defaultPurify()) return;
                                try {
                                    Object v = XposedHelpers.getObjectField(param.thisObject, combo[1]);
                                    if (v instanceof View && ((View) v).getVisibility() == View.VISIBLE) {
                                        ((View) v).setVisibility(View.GONE);
                                    }
                                } catch (Throwable ignored) {
                                }
                            }
                        });
                n++;
            } catch (Throwable ignored) {
            }
        }
        XposedBridge.log(TAG + "[adapt] hide-result combos hooked: " + n);
    }
}
