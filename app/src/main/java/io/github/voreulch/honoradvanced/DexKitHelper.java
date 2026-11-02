package io.github.voreulch.honoradvanced;

import de.robv.android.xposed.XposedBridge;

/**
 * DexKit helper — runtime dex search to locate obfuscated classes by stable
 * features (strings / method signatures) instead of hard-coded names.
 *
 * Used by InstallerHook so it survives installer OTA obfuscation reshuffles
 * (38.301 → next version): the hook points stay valid as long as the class
 * keeps its behavioural signature (string constants, method param types).
 *
 * The DexKitBridge is created per-apk and cached for the process lifetime;
 * DexKit's native lib is bundled in the APK (libdexkit.so).
 *
 * NOTE: query rules must be authored from a decompiled look at the target
 * installer — without the source we can't know which strings/params are
 * stable. So this helper is the toolkit; InstallerHook migration happens
 * piece by piece once we pull + jadx the live installer APK.
 */
public final class DexKitHelper {

    private DexKitHelper() {}

    private static org.luckypray.dexkit.DexKitBridge sBridge;
    private static String sApk;

    /** Lazily create (and cache) a DexKitBridge for the given target apk. */
    private static org.luckypray.dexkit.DexKitBridge bridge(String apkPath) {
        if (sBridge != null && apkPath.equals(sApk)) return sBridge;
        if (sBridge != null) {
            try { sBridge.close(); } catch (Throwable ignored) {}
            sBridge = null;
        }
        try {
            System.loadLibrary("dexkit");
            sBridge = org.luckypray.dexkit.DexKitBridge.create(apkPath);
            sApk = apkPath;
            Diag.log("DexKit bridge ready for " + apkPath);
        } catch (Throwable t) {
            Diag.err("DexKit bridge init failed", t);
        }
        return sBridge;
    }

    /**
     * 释放 DexKit 内部的 dex 索引（v3.1.1）。
     *
     * <p>DexKitBridge 会为整个 apk 建立索引，3.8MB 的 HAware dex 解析后常驻几十 MB。
     * 在 `com.hihonor.iaware` 这种系统进程里长期占着不划算 —— 我们的查询是一次性的
     * （挂完 hook 就不再需要），所以用完就放。
     *
     * <p>⚠ 只有在确认**不需要重试**时才调；还在等重试就释放，下次又得重新解析一遍。
     */
    public static void release() {
        try {
            if (sBridge != null) sBridge.close();
        } catch (Throwable ignored) {
        }
        sBridge = null;
        sApk = null;
    }

    /**
     * 按字符串找**所有**匹配的方法（v3.1.0）。
     *
     * <p>为什么不用返回单个的那个：同一个方法名在 dex 里**常有重载**，
     * 实测三个锚点都踩到了 ——
     * <pre>
     *   com.hihonor.powergenie.core.display.d.o   ()AtomicBoolean  和  (String)Z
     *   com.hihonor.thermal.core.c1.l             ([I)V  和  (Map,String,String)V
     *   v0.i.q                                    ()I  和  ()V
     * </pre>
     * "只取第一个"很容易挑到不是真正入口的那个，白 hook；更糟的是挑到返回
     * {@code AtomicBoolean} 的版本再 setResult(null)，调用方会直接 NPE。
     *
     * <p>所以这里把**全部**匹配都返回，由调用方逐个挂、并按返回类型决定怎么阻断。
     */
    public static java.util.List<java.lang.reflect.Method> findMethodsByStrings(
            String apkPath, ClassLoader cl, String... strings) {
        java.util.List<java.lang.reflect.Method> out = new java.util.ArrayList<>();
        org.luckypray.dexkit.DexKitBridge b = bridge(apkPath);
        if (b == null) return out;
        try {
            org.luckypray.dexkit.query.FindMethod query =
                    org.luckypray.dexkit.query.FindMethod.create()
                            .matcher(org.luckypray.dexkit.query.matchers.MethodMatcher.create()
                                    .usingStrings(strings));
            java.util.List<org.luckypray.dexkit.result.MethodData> list = b.findMethod(query);
            if (list == null) return out;
            java.util.HashSet<String> seen = new java.util.HashSet<>();
            for (org.luckypray.dexkit.result.MethodData md : list) {
                try {
                    java.lang.reflect.Method m = md.getMethodInstance(cl);
                    if (m == null) continue;
                    // 内部类里的方法不挂（多为匿名 Runnable，挂错地方没意义）
                    if (m.getDeclaringClass().getName().contains("$")) continue;
                    StringBuilder sig = new StringBuilder(m.getDeclaringClass().getName());
                    sig.append('#').append(m.getName()).append('(');
                    for (Class<?> pt : m.getParameterTypes()) sig.append(pt.getSimpleName()).append(',');
                    sig.append(')');
                    if (!seen.add(sig.toString())) continue;
                    out.add(m);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            Diag.err("DexKit findMethods(strings) failed", t);
        }
        return out;
    }

    /**
     * Find a class whose methods reference ALL the given string constants.
     * Returns null if not found / ambiguous / DexKit unavailable, so callers
     * can fall back to the hard-coded name.
     */
    /**
    /**
     * Find a class whose methods reference ALL the given string constants.
     * Returns null if not found / ambiguous / DexKit unavailable, so callers
     * can fall back to the hard-coded name.
     */
    public static Class<?> findClassByStrings(String apkPath, ClassLoader cl, String... strings) {
        org.luckypray.dexkit.DexKitBridge b = bridge(apkPath);
        if (b == null) return null;
        try {
            org.luckypray.dexkit.query.FindClass query = org.luckypray.dexkit.query.FindClass.create()
                    .matcher(org.luckypray.dexkit.query.matchers.ClassMatcher.create()
                            .usingStrings(strings));
            org.luckypray.dexkit.result.ClassData cd = b.findClass(query).singleOrNull();
            if (cd == null) return null;
            Class<?> found = cd.getInstance(cl);
            // usingStrings can match an inner class (Foo$Bar) that reuses the
            // outer class's string constants — never a hook target here, so
            // return null and let the caller fall back to its hard-coded name.
            if (found != null && found.getName().contains("$")) return null;
            return found;
        } catch (Throwable t) {
            Diag.err("DexKit findClass(strings) failed", t);
            return null;
        }
    }

    /**
     * 按**类名正则**找类（v3.2.43）。
     *
     * <p>为什么需要这个而不继续用 {@link #findClassByStrings}：
     * {@code MethodMatcher.usingStrings} 只匹配**方法体内 const-string 引用的字面量**。
     * 而 {@code queryFraudCallingLatestRecordBy} 这类是**方法名**、
     * {@code FraudCallDetectStandardData} 这类是**类名** —— 它们不在 const-string
     * 里，所以按字符串扫一定是 0 命中（实测 awareness 里 46MB dex、2ms 返回空，就是这个原因）。
     *
     * <p>按类名正则匹配才是这类目标的正确工具：只要 OTA 后业务类名还带
     * {@code Fraud} 字样就能命中，不需要知道混淆后的完整包路径。
     *
     * @param nameRegex 注意是**全限定类名**的正则，例如 {@code ".*[Ff]raud.*"}
     */
    public static java.util.List<Class<?>> findClassesByNameRegex(
            String apkPath, ClassLoader cl, String nameRegex) {
        java.util.List<Class<?>> out = new java.util.ArrayList<>();
        org.luckypray.dexkit.DexKitBridge b = bridge(apkPath);
        if (b == null) return out;
        try {
            org.luckypray.dexkit.query.FindClass query = org.luckypray.dexkit.query.FindClass.create()
                    .matcher(org.luckypray.dexkit.query.matchers.ClassMatcher.create()
                            .className(nameRegex));
            java.util.List<org.luckypray.dexkit.result.ClassData> list = b.findClass(query);
            if (list == null) return out;
            for (org.luckypray.dexkit.result.ClassData cd : list) {
                try {
                    Class<?> c = cd.getInstance(cl);
                    // 内部类（Foo$Bar）不作为 hook 目标：多为匿名/辅助类，
                    // 挂上去既没意义又容易连带破坏外部类的状态
                    if (c != null && !c.getName().contains("$")) {
                        out.add(c);
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            Diag.err("DexKit findClassesByNameRegex failed", t);
        }
        return out;
    }
}
