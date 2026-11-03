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
            // 先数 dex，**没有 dex 的分片直接跳过**，绝不把空包交给
            // DexKitBridge.create。securityserver 会传 8 个 split，其中资源/配置
            // split 没有 classes.dex；旧实现会对这些空包建索引，native 层可能
            // SIGABRT（tombstone 里 libdexkit 的多次崩溃即此类）。
            int zipDex = countDexEntries(apkPath);
            if (zipDex <= 0) {
                Diag.note("DexKit skip: no classes.dex in " + apkPath);
                return null;
            }
            System.loadLibrary("dexkit");
            sBridge = org.luckypray.dexkit.DexKitBridge.create(apkPath);
            // 只有 APK 本身确实有多个 dex 时才走全量读取；
            // installer/powergenie 只有一个 dex，不能让它们冷启动白付 I/O。
            if (sBridge != null && sBridge.getDexNum() == 1 && zipDex > 1) {
                byte[][] all = readAllDexBytes(apkPath);
                if (all != null && all.length > 1) {
                    try { sBridge.close(); } catch (Throwable ignored) { }
                    sBridge = org.luckypray.dexkit.DexKitBridge.create(all);
                    Diag.note("DexKit multi-dex fallback: " + all.length + " dex");
                }
            }
            sApk = apkPath;
            Diag.log("DexKit bridge ready for " + apkPath
                    + " dexNum=" + (sBridge == null ? -1 : sBridge.getDexNum())
                    + " zipDex=" + zipDex);
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
     * 按**类名包含关键字**找类。
     *
     * <p>为什么需要这个而不继续用 {@link #findClassByStrings}：
     * {@code MethodMatcher.usingStrings} 只匹配**方法体内 const-string 引用的字面量**。
     * 而 {@code queryFraudCallingLatestRecordBy} 这类是**方法名**、
     * {@code FraudCallDetectStandardData} 这类是**类名** —— 它们不在 const-string
     * 里，所以按字符串扫一定是 0 命中。
     *
     * <p>按类名 Contains 匹配：只要 OTA 后业务类名还带 {@code Fraud}/{@code Subject}
     * 字样就能命中。结果必须再由调用方校验“继承 baseplugin.ej.f + insert 形状”，
     * 不能直接 hook 所有 POJO。
     */
    public static java.util.List<Class<?>> findClassesByNameContains(
            String apkPath, ClassLoader cl, String keyword) {
        java.util.List<Class<?>> out = new java.util.ArrayList<>();
        org.luckypray.dexkit.DexKitBridge b = bridge(apkPath);
        if (b == null) return out;
        try {
            org.luckypray.dexkit.query.FindClass query = org.luckypray.dexkit.query.FindClass.create()
                    .matcher(org.luckypray.dexkit.query.matchers.ClassMatcher.create()
                            // SimilarRegex 只支持 ^ / $ 锚点，传 ".*[Ff]raud.*"
                            // 仍会 0 命中。这里只用 Contains 查关键字 "Fraud"/"Subject"，
                            // 结果再由调用方做“继承 baseplugin.ej.f + insert 形状”双重校验。
                            .className(keyword,
                                    org.luckypray.dexkit.query.enums.StringMatchType.Contains));
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
            Diag.err("DexKit findClassesByNameContains failed", t);
        }
        return out;
    }

    /** 数 APK 内有几个 classes*.dex，决定是否需要多 dex 兜底。 */
    private static int countDexEntries(String apkPath) {
        java.util.zip.ZipFile zf = null;
        int n = 0;
        try {
            zf = new java.util.zip.ZipFile(apkPath);
            java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                java.util.zip.ZipEntry e = en.nextElement();
                if (e != null && e.getName().endsWith(".dex") && e.getSize() > 0) n++;
            }
        } catch (Throwable ignored) {
        } finally {
            if (zf != null) try { zf.close(); } catch (Throwable ignored) { }
        }
        return n;
    }

    /** 读取 APK 内全部 classes*.dex，供多 dex 入口兜底。 */
    private static byte[][] readAllDexBytes(String apkPath) {
        java.util.ArrayList<byte[]> list = new java.util.ArrayList<>();
        java.util.zip.ZipFile zf = null;
        try {
            zf = new java.util.zip.ZipFile(apkPath);
            java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                java.util.zip.ZipEntry e = en.nextElement();
                if (e == null || !e.getName().endsWith(".dex") || e.getSize() <= 0) continue;
                java.io.InputStream in = null;
                try {
                    in = zf.getInputStream(e);
                    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(
                            (int) Math.min(Integer.MAX_VALUE, e.getSize()));
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                    list.add(bos.toByteArray());
                } finally {
                    if (in != null) try { in.close(); } catch (Throwable ignored) { }
                }
            }
        } catch (Throwable t) {
            Diag.err("DexKit readAllDex failed", t);
        } finally {
            if (zf != null) try { zf.close(); } catch (Throwable ignored) { }
        }
        return list.isEmpty() ? null : list.toArray(new byte[0][]);
    }

    /** canary：DexKit 实际索引了几个 dex。 */
    public static int dexNum(String apkPath) {
        org.luckypray.dexkit.DexKitBridge b = bridge(apkPath);
        if (b == null) return -1;
        try {
            return b.getDexNum();
        } catch (Throwable t) {
            return -1;
        }
    }

    /** canary：按精确全限定名找一个类（Equals），确认多 dex 是否都索引到。 */
    public static boolean hasClassExact(String apkPath, String className) {
        org.luckypray.dexkit.DexKitBridge b = bridge(apkPath);
        if (b == null) return false;
        try {
            org.luckypray.dexkit.query.FindClass query = org.luckypray.dexkit.query.FindClass.create()
                    .matcher(org.luckypray.dexkit.query.matchers.ClassMatcher.create()
                            .className(className,
                                    org.luckypray.dexkit.query.enums.StringMatchType.Equals));
            java.util.List<org.luckypray.dexkit.result.ClassData> list = b.findClass(query);
            return list != null && !list.isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }
}
