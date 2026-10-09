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
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 荣耀安装器净化 — merged from HonorInstallerPurify 4.0.
 * The purify master switch gates every hook (skip-check / no-network /
 * ad-hide follow the master; no separate switches). The info-card and
 * downgrade hooks additionally honor their own switches. All values are
 * read through the module's exported CfgProvider (default ON = the
 * standalone module's behavior). Toggling takes effect on the next
 * install, no restart needed.
 */
public final class InstallerHook {
    private static final String TAG = "[HAS-Purify] ";
    private static final String K_INFOCARD = Cfg.K_INFOCARD;
    private static final String K_DOWNGRADE = Cfg.K_DOWNGRADE;

    private static boolean purifyOn() {
        return Cfg.isOn(Cfg.K_PURIFY);
    }

    private static boolean on(String key) {
        return purifyOn() && Cfg.isOn(key);
    }

    public static void init(ClassLoader cl) {
        hookSecurityCheck(cl);
        hookFingerprintAuth(cl);
        hookDiversionWait(cl);
        hookNetwork(cl);
        hookSafeModeBanner(cl);
        hookSafeModeCardInfo(cl);
        hookAdRecommend(cl);
        hookSafeResultBanner(cl);
        hookCleanCacheCard(cl);
        hookAllowDowngrade(cl);
    }

    public static void initSystemServer(ClassLoader cl) {
        hookDowngradeInSystemServer(cl);
    }

    private static void hookDiversionWait(ClassLoader cl) {
        try {
            final Class<?> respClz = cl.loadClass("com.hihonor.packageinstaller.entity.resp.DiversionResp");
            XposedHelpers.findAndHookMethod("g82", cl, "f",
                    String.class, String.class, String.class, String.class, long.class, String.class,
                    int.class, long.class, int.class, int.class, "g82$d", Runnable.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (!purifyOn()) return;
                            try {
                                Object dVar = param.args[10];
                                Method m = dVar.getClass().getMethod("a", respClz);
                                m.setAccessible(true);
                                m.invoke(dVar, new Object[]{null});
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + "diversion error: " + t);
                            }
                            param.setResult(null);
                        }
                    });
            XposedBridge.log(TAG + "hook g82.f ok");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "hook g82.f failed: " + t);
        }
    }

    private static void hookCleanCacheCard(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod("z62", cl, "E",
                    "com.hihonor.packageinstaller.entity.resp.SafeModeCards",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (purifyOn()) param.setResult(true);
                        }
                    });
            XposedBridge.log(TAG + "hook z62.E ok");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "hook z62.E failed: " + t);
        }
    }

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

    private static void hookSafeModeBanner(ClassLoader cl) {
        // r12.j decides whether a CardTipsRecord tip (e.g. the safe-mode
        // reminder) shows. With the info card ON we force-show and the
        // content gets replaced by r12.q; with it OFF we force-hide so the
        // stock safe-mode reminder disappears too — plain OFF keeps stock.
        try {
            XposedHelpers.findAndHookMethod("r12", cl, "j",
                    "com.hihonor.packageinstaller.utils.CardTipsRecord", String.class, boolean.class, String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!purifyOn()) return;
                            param.setResult(on(K_INFOCARD));
                        }
                    });
            XposedBridge.log(TAG + "hook r12.j (card tips gate) ok");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "hook r12.j failed: " + t);
        }
        try {
            XposedHelpers.findAndHookMethod("r12", cl, "s",
                    "com.android.packageinstaller.PackageInstallerActivity", "com.hihonor.packageinstaller.rbi.RbiTempBean",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (on(K_INFOCARD)) param.setResult(null);
                        }
                    });
            XposedBridge.log(TAG + "hook r12.s ok");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "hook r12.s failed: " + t);
        }
    }

    private static void hookSafeModeCardInfo(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod("r12", cl, "q",
                    View.class, String.class, Activity.class, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!on(K_INFOCARD)) return;
                            try {
                                fillInfoCard((View) param.args[0], (Activity) param.args[2], 0);
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + "info card error: " + t);
                            }
                            param.setResult(null);
                        }
                    });
            XposedBridge.log(TAG + "hook r12.q ok");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "hook r12.q failed: " + t);
        }
    }

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
        try {
            newInfo = (PackageInfo) XposedHelpers.getObjectField(activity, "t0");
            pkgName = (String) XposedHelpers.getObjectField(activity, "x0");
        } catch (Throwable ignored) {
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

    private static void hookAdRecommend(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod("com.hihonor.packageinstaller.presenter.AbstractAdBusinessPresenter", cl, "l",
                    "kt1", "com.hihonor.packageinstaller.rbi.RbiTempBean", "ef2", boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (purifyOn()) param.setResult(null);
                        }
                    });
            XposedBridge.log(TAG + "hook ad presenter l ok");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "hook ad presenter l failed: " + t);
        }
    }

    private static void hookSafeResultBanner(ClassLoader cl) {
        hideAfter(cl, "e2", "W0");
        hideAfter(cl, "k2", "Y0");
    }

    private static void hideAfter(ClassLoader cl, String methodName, final String fieldName) {
        try {
            XposedHelpers.findAndHookMethod("com.android.packageinstaller.PackageInstallerActivity", cl, methodName,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!purifyOn()) return;
                            try {
                                Object v = XposedHelpers.getObjectField(param.thisObject, fieldName);
                                if (v instanceof View) {
                                    ((View) v).setVisibility(View.GONE);
                                }
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + "hide " + fieldName + " error: " + t);
                            }
                        }
                    });
            XposedBridge.log(TAG + "hook " + methodName + " ok");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "hook " + methodName + " failed: " + t);
        }
    }

    private static void hookSecurityCheck(final ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod("ly1", cl, "h", boolean.class, "sy1",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!purifyOn()) return;
                            try {
                                final Object callback = param.args[1];
                                if (callback == null) {
                                    param.setResult(null);
                                    return;
                                }
                                boolean isHn = (Boolean) XposedHelpers.callMethod(param.thisObject, "d");
                                Class<?> ly1Class = param.thisObject.getClass();
                                final Object passResult = XposedHelpers.callStaticMethod(ly1Class, "a", isHn);
                                final Class<?> oy1Class = cl.loadClass("oy1");
                                final Class<?> jz1Class = cl.loadClass("jz1");
                                final Object jz1Proxy = Proxy.newProxyInstance(cl, new Class<?>[]{jz1Class},
                                        new InvocationHandler() {
                                            @Override
                                            public Object invoke(Object proxy, Method method, Object[] args) {
                                                String name = method.getName();
                                                if ("hashCode".equals(name)) {
                                                    return System.identityHashCode(proxy);
                                                }
                                                if ("equals".equals(name)) {
                                                    return proxy == (args != null && args.length > 0 ? args[0] : null);
                                                }
                                                if ("toString".equals(name)) {
                                                    return "Jz1Noop";
                                                }
                                                return null;
                                            }
                                        });
                                final Method deliver = callback.getClass().getMethod("b", oy1Class, jz1Class);
                                new Handler(Looper.getMainLooper()).post(new Runnable() {
                                    @Override
                                    public void run() {
                                        try {
                                            deliver.invoke(callback, passResult, jz1Proxy);
                                            XposedBridge.log(TAG + "security check skipped");
                                        } catch (Throwable t) {
                                            XposedBridge.log(TAG + "deliver result error: " + t);
                                        }
                                    }
                                });
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + "check skip error: " + t);
                            }
                            param.setResult(null);
                        }
                    });
            XposedBridge.log(TAG + "hook ly1.h ok");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "hook ly1.h failed: " + t);
        }
    }

    private static void hookFingerprintAuth(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod("qf2", cl, "g", Context.class, "qf2$a",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!purifyOn()) return;
                            try {
                                Object cb = param.args[1];
                                if (cb != null) {
                                    cb.getClass().getMethod("c").invoke(cb);
                                    cb.getClass().getMethod("a", boolean.class).invoke(cb, true);
                                    XposedBridge.log(TAG + "fingerprint auth bypassed");
                                }
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + "fingerprint bypass error: " + t);
                            }
                            param.setResult(null);
                        }
                    });
            XposedBridge.log(TAG + "hook qf2.g ok");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "hook qf2.g failed: " + t);
        }
    }
}
