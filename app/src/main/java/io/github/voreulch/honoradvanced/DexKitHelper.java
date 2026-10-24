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
            XposedBridge.log("[HAS] DexKit bridge ready for " + apkPath);
        } catch (Throwable t) {
            XposedBridge.log("[HAS] DexKit bridge init failed: " + t);
        }
        return sBridge;
    }

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
            XposedBridge.log("[HAS] DexKit findClass(strings) failed: " + t);
            return null;
        }
    }
}
