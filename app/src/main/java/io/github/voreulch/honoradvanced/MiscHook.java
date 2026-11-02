package io.github.voreulch.honoradvanced;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 杂项 hooks — SystemUI line (v2.0.1).
 *
 * 屏蔽通知回避 (block notification ducking): notification sounds are played
 * by SystemUI, which first requests audio focus with
 * AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK — MediaFocusControl then ducks (attenuates
 * the running media player via VolumeShaper 1.0 -> 0.2). We hook
 * AudioManager.requestAudioFocus INSIDE SystemUI and answer FAILED for
 * notification-family MAY_DUCK requests: the ringtone itself plays regardless
 * of the focus result (focus is only a coordination hint), while
 * MediaFocusControl never hears about it and the music never gets ducked —
 * notification sound and music play together.
 *
 * Why SystemUI and not system_server: the notification sound's focus request
 * originates here (dumpsys: callingPack=com.android.systemui), and in this
 * process the module's Cfg snapshot works normally (same channel as the
 * glass switches). In system_server the config is unreadable (SELinux hides
 * app data from it, ENOENT; the provider preload hangs there — a latent
 * 1.4.x issue unmasked by isOnStrict), so hooks there can never see the
 * switch state. Alarms (USAGE_ALARM) and exclusive requests never match and
 * keep working normally.
 *
 * Signature-agnostic: every requestAudioFocus overload of the framework
 * AudioManager is hooked; args are scanned at call time for the focus-gain
 * int (or AudioFocusRequest wrapper) plus the AudioAttributes / legacy
 * stream markers of the notification family.
 */
public final class MiscHook {

    private static final String TAG = "[HAS-Misc] ";
    private static final int AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK = 3;
    // AudioManager.AUDIOFOCUS_REQUEST_FAILED = 0 (GRANTED = 1)
    private static final int AUDIOFOCUS_REQUEST_FAILED = 0;
    // android.media.AudioAttributes usage constants (AOSP values — the
    // notification family is 5..10; 2 is VOICE_COMMUNICATION, NOT one of us)
    private static final int USAGE_NOTIFICATION = 5;
    private static final int USAGE_NOTIFICATION_RINGTONE = 6;
    private static final int USAGE_NOTIFICATION_COMMUNICATION_REQUEST = 7;
    private static final int USAGE_NOTIFICATION_COMMUNICATION_INSTANT = 8;
    private static final int USAGE_NOTIFICATION_COMMUNICATION_DELAYED = 9;
    private static final int USAGE_NOTIFICATION_EVENT = 10;
    // legacy stream types (pre-attributes request path)
    private static final int STREAM_RING = 2;
    private static final int STREAM_NOTIFICATION = 5;

    private MiscHook() {
    }

    // ==================== PowerGenie thermal engine (com.hihonor.powergenie) ====================

    /**
     * 压制 PowerGenie 自有温控（2026-10-31, round 3). PowerGenie (HAware.apk)
     * runs its OWN thermal engine that reads the HAL directly — it never calls
     * the Java PowerManager/ThermalService API, so the system_server status
     * normalisation cannot reach it. Verified against the current HAware dump
     * (dexdump, 2026-10-31): the engine ships UNOBFUSCATED in
     * `com.hihonor.powergenie.core.thermal.ThermalManager` (stable across
     * builds, unlike the policy classes a1/b1 and the v0.* analyzer):
     *
     *   handleThermalEvent(g)        single dispatcher — calls
     *                                handleThermalAppAction / AppLevel /
     *                                BgFrz / Media (the four effect handlers)
     *   handleThermalAppAction(g)    -> p0.o(int) + f0.handleStateChanged(...)
     *                                   (per-app thermal actions)
     *   handleThermalBgFrz(g)        -> p0.t(boolean) (background freeze)
     *   handleThermalMedia(g)        -> p0.o(int) (media/video limits)
     *   handleThermalAppLevel(g)     -> mCurThermalLevelMap.put(...)
     *   getCurThermalLevel(String,int) -> mCurThermalLevelMap.get(key)
     *
     * Blocking the dispatcher makes all four policies no-ops in one place;
     * the getter is additionally pinned to level 0 so any consumer that reads
     * the cached level sees the coolest value. Temperature reporting itself is
     * NOT faked (charging/battery logic keeps its real numbers).
     *
     * SAFETY: com.hihonor.powergenie is a normal app process — a hook failure
     * can never touch the boot path. Every callback is try/catch'd and gated
     * by the same system-property switch as the rest of the thermal bypass
     * (Cfg.hottempOn(), readable from any process, no binder).
     */
    public static void initPowerGenie(ClassLoader cl, String apkPath) {
        // ⚠ 必须先跑：下面那行 `if (sPgDone || sPgTries >= 3) return;` 是给
        // ThermalManager 那部分用的闸门。早先把 hookThermalExtra 放在函数末尾，
        // 结果 ThermalManager 连挂 3 次失败后整个函数直接 return，
        // thermal-extra 永远拿不到执行机会 —— sExtraDone 的重试逻辑等于白写。
        hookThermalExtra(apkPath, cl);
        hookCloudPolicy(cl, apkPath);
        // v3.2.34：**读取侧拦截**。三个 Receiver 只拦住"热重载通知"，
        // 文件本身是别的进程写的，拦不住；所以还要让 PowerGenie 读不到它们。
        // 伪造"文件不存在"→ 它回退到自己的原厂内置配置，不用我们覆盖任何内容。
        hookCotaFileAccess();
        // Idempotent, but ONLY after a successful hook pass: the first
        // handleLoadPackage in the iaware process can arrive before HAware's
        // dex is reachable from that classloader (observed CNFE at boot,
        // 2026-10-31), and the follow-up callback for the powergenie package
        // must still be allowed to try. Bounded to 3 attempts.
        if (sPgDone || sPgTries >= 3) return;
        sPgTries++;
        final Class<?> tm;
        try {
            tm = cl.loadClass("com.hihonor.powergenie.core.thermal.ThermalManager");
        } catch (Throwable t) {
            Diag.warn("powergenie: class not in this CL yet (try " + sPgTries + ")");
            return; // do NOT mark done — a later callback may carry the real CL
        }
        sPgDone = true;
        int n = 0;
        try {
            for (final Method m : tm.getDeclaredMethods()) {
                final String nm = m.getName();
                final boolean dispatcher = "handleThermalEvent".equals(nm);
                final boolean level = "getCurThermalLevel".equals(nm);
                if (!dispatcher && !level) continue;
                if (dispatcher && m.getReturnType() != void.class) continue;
                if (level && m.getReturnType() != int.class) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            boolean on = Cfg.hottempOn();
                            if (!sTgFirstSeen) {
                                sTgFirstSeen = true;
                                Diag.note("powergenie: 首次回调命中 " + nm
                                        + " | 开关=" + on
                                        + (on ? " → 已拦截" : " → 开关关闭，放行"));
                            }
                            if (!on) return;
                            if (dispatcher) {
                                param.setResult(null);
                                if (sPgBlocked < 5) {
                                    sPgBlocked++;
                                    Diag.log("powergenie: thermal policy suppressed (" + sPgBlocked + ")");
                                }
                            } else {
                                param.setResult(0); // coolest level
                            }
                        } catch (Throwable t) {
                            Diag.err("powergenie gate error", t);
                        }
                    }
                });
                n++;
                Diag.log("powergenie: hooked " + nm);
            }
        } catch (Throwable t) {
            Diag.err("powergenie: ThermalManager missing", t);
        }
        Diag.mount("PowerGenie.ThermalManager(handleThermalEvent/getCurThermalLevel)", n, 2);
    }

    private static int sPgBlocked = 0;
    private static int sExtraBlocked = 0;
    private static boolean sCloudDone = false;
    /**
     * v3.2.36：已成功挂载的云控 hook 标识。
     *
     * <p>为什么需要它：{@code sCloudDone} 只在**三个 Receiver 全挂上**时才置 true。
     * 第一次 handleLoadPackage 时 HAware 的 dex 可能还没就绪（多包名共进程的
     * ClassLoader 时序），三个全失败 → sCloudDone 保持 false → 下次回调再跑一遍
     * {@code hookCloudPolicy}，把**已经挂上的方法再挂一次**。
     *
     * <p>重复挂载的后果：同一个方法会触发 N 次回调。目前所有回调都是
     * {@code setResult(...)}（幂等），所以没暴露出问题，但计数会翻倍、
     * 日志会重复，属于必须清掉的隐患。
     *
     * <p>策略：挂之前先抢占，成功保留、失败移除（允许下次重试）。
     */
    private static final java.util.Set<String> sCloudHooked =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
    private static boolean sFraudDone = false;
    private static int sFraudBlocked = 0;
    // 温控/云控的"首次回调"判据：证明钩子真被调用到、且开关读到的是开
    private static boolean sThermalFirstSeen = false;
    private static boolean sCloudFirstSeen = false;
    /** 第一次真的拦下一条云控时打一条（区别于"挂钩成功"）。 */
    private static boolean sCloudFirstBlocked = false;
    /** 是否已经报告过"检测到读云端配置"（避免刷屏）。 */
    private static boolean sCloudStreamSeen = false;
    private static boolean sTgFirstSeen = false;
    /** 第一次真正进到拦截回调时打一条，用来证明"钩子确实被调用到了"。 */
    private static boolean sFraudFirstSeen = false;
    private static boolean sFraudFirstOn = false;

    /**
     * 屏蔽反诈组件的数据采集与上报（v3.2.0）。
     *
     * <p>主体是 {@code com.hihonor.securityserver}（uid=system 的系统应用）。
     * 它的类名全被混淆了，dex 里只留下三条可读线索：
     * <pre>
     *   " ant antifraud detect state switch, enable "     开关状态
     *   "(start register Antifraud Detect Observer"       注册检测观察者
     *   " fraud_cloud_security_check "                    云控安全校验
     * </pre>
     * 所以用 **DexKit 字符串锚点**定位（和温控那边同一套办法），
     * 挂到"引用了这几条字符串的方法"上，把它们的返回值 / 执行流程掐掉：
     * <ul>
     *   <li>注册观察者 -> 检测根本不启动，后续采集自然没有</li>
     *   <li>开关状态 -> 一律返回 false（对宿主表现为"功能关闭"）</li>
     *   <li>云控校验 -> 不通过，不去拿下发规则</li>
     * </ul>
     *
     * <p><b>为什么挡"采集"而不是挡"发送"</b>：发送走的是系统统一的诊断通道
     * （HiView / HwDdmp），全系统共用 —— 在那儿拦会把崩溃上报、系统诊断一起干掉，
     * 属于杀敌一千自损八百。挡在源头最干净：数据压根没被收集。
     */
    /** 反诈组件的入口（securityserver 进程调用）。 */
    public static void initSecurityServer(ClassLoader cl, String apkPath, String[] splitPaths) {
        hookAntiFraud(cl, apkPath, splitPaths);
    }

    /**
     * 感知服务本体的**精准**反诈拦截（v3.2.42）。
     *
     * <p>为什么需要单独一条：实测 {@code com.hihonor.awareness} 自己有 41 处
     * fraud 相关逻辑（诈骗电话识别 / 诈骗应用检测 / 行为事件集）和独立上报通道
     * （293 处 Upload、41 处 hiview，上报域名 {@code metrics-drcn.dt.hihonorcloud.com}）。
     * 只拦 securityserver 等于只关了一个水龙头。
     *
     * <p><b>为什么是"精准"而不是全拦</b>：awareness 同时是人脸活体检测
     * （{@code FaceFraudDetector} / {@code VisionFaceFraudService} / 深伪检测）的载体，
     * 那是**人脸识别的安全防线**，拦了会让"照片/视频能否骗过人脸"这件事失去保护。
     * 所以类名里带 {@code face} 的一律跳过。
     *
     * <p><b>全部用动态锚点</b>：awareness 是**可更新应用**（装在 /data/app），
     * 类名、方法名、字符串都会随 OTA 变。锚点选的是业务语义字符串
     * （{@code queryFraudCallingLatestRecordBy} / {@code fraudRiskAppData} …），
     * 不是混淆后的类名 —— 这样升级后只要业务还是这套，就能继续命中。
     *
     * <p>保守边界：只处理 {@code boolean / int / void}。返回对象的不动，
     * 避免调用方 NPE（这类查询方法常被链式调用）。
     */
    public static void initAwareness(ClassLoader cl, String apkPath, String[] splitPaths) {
        if (sAwarenessDone) return;
        if (apkPath == null) return;
        final String[] apkPaths = allApkPaths(apkPath, splitPaths);
        try {
            // v3.2.43：改成**按类名正则**扫。
            //
            // 上一版用 `queryFraudCallingLatestRecordBy` 这些当锚点，结果 0 命中，
            // 46MB 的 dex 两毫秒就返回空。原因是它们**是方法名 / 类名**，
            // 而 DexKit 的 usingStrings 只匹配方法体里 const-string 的字面量。
            //
            // v3.2.44：正则扫在这个 46MB 的 APK 上**同样 0 命中**（DexKit 对它不可靠），
            // 所以改成"真实类名 + 失败静默跳过"。类名是从 dex 里直接提取的
            // （不是猜的），实测**没有混淆**：都在
            // `com.hihonor.awareness.server.aisecurity.opencapacity.data` 下。
            //
            // 为什么只挑两个 Processor 而不是那 20 多个 Fraud*Data：
            // `*Data` / `*StandardData` 全是 POJO（数据载体），hook 它们没有任何意义；
            // 真正把 fraud 行为写进数据平台再往外送的是这两个 Processor。
            int n = 0;
            java.util.Set<String> seen = new java.util.HashSet<String>();
            java.util.List<java.lang.reflect.Method> candidates = new java.util.ArrayList<>();
            // 先试 DexKit 正则（万一某个版本它能工作，就自动覆盖更多）。
            //
            // v3.2.45：**只试一次**。这个 APK 有 46MB，实测 DexKit 在它上面
            // 稳定返回空（bridge ready 但索引没建起来）。如果按"n==0 就重试"
            // 的逻辑走，每次 handleLoadPackage 都会重新解析 46MB dex，
            // 那是实打实的启动卡顿。所以用一个独立标记把 DexKit 这条路钉死。
            if (!sAwarenessDexTried) {
                sAwarenessDexTried = true;
                candidates.addAll(findFraudMethods(apkPaths, cl));
            }
            // 再补上确定存在的两个 Processor（OTA 改名时 forName 失败，静默跳过）
            for (String fixed : new String[]{
                    "com.hihonor.awareness.dataplatform.storage.processor.impl."
                            + "StandardEventDataOffFraudBehaviorProcessor",
                    "com.hihonor.awareness.dataplatform.storage.processor.impl."
                            + "StandardEventDataOlFraudBehaviorProcessor",
            }) {
                try {
                    Class<?> c = cl.loadClass(fixed);
                    for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                        if (m != null) {
                            candidates.add(m);
                        }
                    }
                } catch (Throwable ignored) {
                    // 类不在了（OTA 改名）—— 跳过，不影响其它 hook
                }
            }
            for (java.lang.reflect.Method m : candidates) {
                final String cn = m.getDeclaringClass().getName();
                // 人脸活体检测不碰：那是人脸识别的安全防线
                if (cn.toLowerCase(java.util.Locale.US).contains("face")) {
                    continue;
                }
                final Class<?> rt = m.getReturnType();
                final boolean isBool = rt == boolean.class;
                final boolean isInt = rt == int.class;
                final boolean isVoid = rt == void.class;
                if (!isBool && !isInt && !isVoid) {
                    continue;
                }
                String sig = cn + "#" + m.getName() + "/" + m.getParameterTypes().length;
                if (!seen.add(sig)) {
                    continue;
                }
                try {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                if (!Cfg.isOn(Cfg.K_NOFRAUD)) return;
                                if (isVoid) {
                                    param.setResult(null);
                                } else if (isBool) {
                                    param.setResult(Boolean.FALSE);
                                } else {
                                    param.setResult(Integer.valueOf(0));
                                }
                                if (sAwarenessHitLog < 12) {
                                    sAwarenessHitLog++;
                                    Diag.note("nofraud: awareness 已拦 "
                                            + cn + "." + m.getName());
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    });
                    n++;
                    Diag.note("ok: nofraud awareness <- " + cn + "." + m.getName());
                } catch (Throwable ignored) {
                }
            }
            sAwarenessDone = n > 0;
            Diag.note("nofraud awareness: 共接管 " + n + " 处"
                    + (n > 0 ? "（人脸活体已排除）"
                            : " ⚠ 一个都没命中，锚点可能随 OTA 失效"));
        } catch (Throwable t) {
            Diag.note("skip: nofraud awareness hook 失败 ("
                    + t.getClass().getSimpleName() + ")");
        }
    }

    private static volatile boolean sAwarenessDone = false;
    /** DexKit 在 awareness 的 46MB dex 上只尝试一次（见 initAwareness 里的说明）。 */
    private static volatile boolean sAwarenessDexTried = false;
    private static volatile int sAwarenessHitLog = 0;

    /**
     * 把所有"类名含 fraud"的类的方法收集起来（v3.2.43）。
     *
     * <p>排除 `face`：{@code FaceFraudDetector} / {@code VisionFaceFraudService}
     * 是人脸活体检测（防照片/视频攻击），属于人脸识别的安全防线，
     * 跟"反诈数据上报"是两回事，拦了纯属自损。
     */
    private static java.util.List<java.lang.reflect.Method> findFraudMethods(
            String[] apks, ClassLoader cl) {
        java.util.List<java.lang.reflect.Method> out = new java.util.ArrayList<>();
        for (String ap : apks) {
            try {
                for (Class<?> c : DexKitHelper.findClassesByNameRegex(ap, cl, ".*[Ff]raud.*")) {
                    if (c.getName().toLowerCase(java.util.Locale.US).contains("face")) {
                        continue;
                    }
                    try {
                        java.lang.reflect.Method[] ms = c.getDeclaredMethods();
                        for (java.lang.reflect.Method m : ms) {
                            if (m != null) {
                                out.add(m);
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    /** 主包 + 所有 split 包去重后的列表（v3.2.37）。 */
    private static String[] allApkPaths(String main, String[] splits) {
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<String>();
        if (main != null && main.length() > 0) {
            set.add(main);
        }
        if (splits != null) {
            for (String s : splits) {
                if (s != null && s.length() > 0) {
                    set.add(s);
                }
            }
        }
        return set.toArray(new String[0]);
    }

    /**
     * 在所有 APK（主包 + split）里按锚点找方法。
     *
     * <p>为什么需要：securityserver 是多 split 应用，反诈相关字符串实测分布在
     * 主包和 {@code HnUnifiedAuthService.apk} 里。只扫主包会漏掉分片里的类。
     * 分片的类同样由主 ClassLoader 加载，所以拿到 Method 后照样能 hook。
     */
    private static java.util.List<java.lang.reflect.Method> findInAllApks(
            String[] apks, ClassLoader cl, String... anchors) {
        java.util.List<java.lang.reflect.Method> out = new java.util.ArrayList<java.lang.reflect.Method>();
        java.util.Set<String> seen = new java.util.HashSet<String>();
        for (String ap : apks) {
            try {
                java.util.List<java.lang.reflect.Method> ms =
                        DexKitHelper.findMethodsByStrings(ap, cl, anchors);
                if (ms == null) {
                    continue;
                }
                for (java.lang.reflect.Method m : ms) {
                    if (m == null) {
                        continue;
                    }
                    String k = m.getDeclaringClass().getName() + "#" + m.getName()
                            + "/" + m.getParameterTypes().length;
                    if (seen.add(k)) {
                        out.add(m);
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    private static void hookAntiFraud(ClassLoader cl, String apkPath, String[] splitPaths) {
        // v3.2.45：**总闸 / 断链 / 门面必须放在 sFraudDone 检查之前**。
        //
        // 这是个真实的顺序 bug：这三层各自有幂等标记（失败会复位以便重试），
        // 但如果它们写在 `if (sFraudDone) return;` 之后，一旦逐点那部分先挂满
        // 把 sFraudDone 置成 true，这三个就再也不会被调用 ——
        // 其中任何一个曾经失败过，就永久失效，而且日志里看不出来。
        hookFraudSettings();
        hookAwarenessBind();
        hookAwarenessClientWhole(cl);

        if (sFraudDone) return;
        if (apkPath == null) return;
        final String[] apkPaths = allApkPaths(apkPath, splitPaths);

        // v3.2.48：作用域**两个**进程 —— 反诈服务（securityserver）+ 感知服务（awareness）。
        // 后者是 v3.2.42 补的：实测 awareness 自己有 41 处 fraud 逻辑和独立上报通道，
        // 只拦 securityserver 等于只关了一个水龙头。awareness 那一侧走
        // MainHook → initAwareness，不经过本函数。

        int n = 0;
        int nCtl = 0;     // SafetyCtrlService（反诈云控校验）
        int nAware = 0;   // awareness 发送入口
        int nRecv = 0;    // 云参数接收器
        String[][] anchors = {
                {"antifraud", "antifraud detect state switch"},       // 开关/观察者注册
                {"fraud_cloud", "fraud_cloud_security_check"},        // 云控校验
                // v3.2.51：删掉了 fence_reg / fence_id / fence_hot 三组锚点。
                // 它们实机**从未命中过一次**（加进去之后挂载数一直停在 8，一点没涨）——
                // 原因是那些字符串睡在字段初始化和静态常量里，不在方法体内，
                // 而 DexKit 的 usingStrings 只匹配方法内的 const-string。
                // 留着只会让每次进程启动多扫三遍 dex，属于纯负担。
                // 围栏这条线现在由「断链 + 门面类」两层覆盖，不需要字符串锚点。
        };
        for (String[] pair : anchors) {
            final String tag = pair[0];
            final String[] keys = java.util.Arrays.copyOfRange(pair, 1, pair.length);
            try {
                java.util.List<java.lang.reflect.Method> ms =
                        findInAllApks(apkPaths, cl, keys);
                for (final java.lang.reflect.Method m : ms) {
                    final Class<?> rt = m.getReturnType();
                    try {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                try {
                                    boolean on = Cfg.isOn(Cfg.K_NOFRAUD);
                                    if (!sFraudFirstSeen) {
                                        // 关键时刻：钩子被真正调用了。
                                        // 这里打印的 on 才是"开关到底生效没生效"的真相。
                                        sFraudFirstSeen = true;
                                        sFraudFirstOn = on;
                                        Diag.note("nofraud: 首次回调命中 " + tag
                                                + " | 开关=" + on
                                                + (on ? " → 已拦截" : " → 开关关闭，放行"));
                                    }
                                    if (!on) return;
                                    // 按返回类型给"关闭/失败"的语义
                                    if (rt == boolean.class) param.setResult(Boolean.FALSE);
                                    else if (rt == void.class) param.setResult(null);
                                    else if (rt == int.class) param.setResult(Integer.valueOf(0));
                                    else return;   // 返回对象的不碰，防 NPE
                                    if (sFraudBlocked < 8) {
                                        sFraudBlocked++;
                                        Diag.log("nofraud: 已阻断 " + tag + " -> "
                                                + m.getDeclaringClass().getSimpleName()
                                                + "." + m.getName());
                                    }
                                } catch (Throwable t) {
                                    Diag.err("nofraud gate error", t);
                                }
                            }
                        });
                        n++;
                        nCtl++;
                        sFraudHookedNames.add(m.getDeclaringClass().getSimpleName()
                                + "." + m.getName());
                        Diag.note("ok: nofraud " + tag + " <- "
                                + m.getDeclaringClass().getName() + "." + m.getName()
                                + " ret=" + rt.getSimpleName());
                    } catch (Throwable ignored) {
                    }
                }
            } catch (Throwable t) {
                Diag.err("nofraud hook failed: " + tag, t);
            }
        }
        // 3) 感知框架（com.hihonor.awareness）的围栏注册。
        //
        //    第 3 轮深挖发现：反诈真正拿数据靠的是这套"围栏"，它有多达 30 种：
        //      SMSNotifyFence / ClipboardFence / NotificationFence  内容类
        //      ScanCodePayFence / QrCollectFence / TrafficQrCodeScan 支付与扫码
        //      GeoLocationFence / FingerprintFence / UserAccountFence 位置·生物·账号
        //      ApkInstallOrUninstallFence / FrequentAppFence         装了什么·常用什么
        //      HealthCodePageFence / EyeGazeFence / UserSleepFence   页面·视线·作息
        //
        //    ⚠ 这个框架是**全系统共用**的（智慧助手、情景智能都靠它），所以绝不能
        //    在全局挡。好在 hookAntiFraud 只跑在 securityserver 进程里 —— 这里挡掉的
        //    只是"反诈自己注册的围栏"，其它 app 的感知能力完全不受影响。
        try {
            Class<?> ac = XposedHelpers.findClass("com.hihonor.awareness.client.AwarenessClient", cl);
            int before = n;
            for (java.lang.reflect.Method m : ac.getDeclaredMethods()) {
                String nm = m.getName();
                // 拦截范围（第 4 遍复查补全）。
                //
                // ⚠ 只拦 addFence 是**不够的**：addFence 只是"订阅事件"，
                // 真正把数据取出来的是 collect 系列。DexKit 扫出来的完整清单：
                //   addFence / addSelfRestoreFence / checkFence     订阅
                //   addCollect / collect / scanCollect /
                //   scanPayCollect / metroCollect / cellularCollect  ← 真正取数据
                //   addJob / addAppPredictJob / addNearByCellJob     定时任务
                //   *Snapshot（get/check/getJob）                    快照读取
                // 这四类一起挡，反诈才真的拿不到东西。
                // 拦截范围：**只拦语义明确的"注册/采集/发送"**。
                //
                // 刻意排除两类（v3.2.5 第 4 轮复查）：
                //   · *Snapshot       —— checkSnapshot 语义可能是"验证快照有效"，
                //                        返回 false 等于"无效"，会触发宿主禁用逻辑，
                //                        而不是"停止采集"。拦错语义比不拦更危险。
                //   · addSelfRestore* —— 自恢复机制，拦了会让状态卡住无法自愈。
                //
                // 保留的都是"往服务端注册 / 从服务端取数 / 发送请求"，拦掉 = 不注册不发。
                boolean target =
                        nm.startsWith("addFence")                       // 注册围栏
                        || nm.startsWith("checkFence")                  // 检查围栏
                        || nm.startsWith("addCollect") || "collect".equals(nm)  // 注册采集 / 采集
                        || nm.startsWith("scanCollect") || nm.startsWith("scanPayCollect")
                        || nm.startsWith("metroCollect") || nm.startsWith("cellularCollect")
                        || nm.startsWith("addJob") || nm.startsWith("addAppPredict")
                        || nm.startsWith("addNearByCell")
                        // beginDispatch 是所有请求的**起点**（dispatch* 只是分发），
                        // 漏了它等于没堵住总入口
                        || nm.startsWith("dispatch") || "beginDispatch".equals(nm);
                if (!target) continue;
                final String tag2 = nm;
                try {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                if (!Cfg.isOn(Cfg.K_NOFRAUD)) return;
                                Class<?> rt = m.getReturnType();
                                if (rt == boolean.class) param.setResult(Boolean.FALSE);
                                else if (rt == void.class) param.setResult(null);
                                else if (rt == int.class) param.setResult(Integer.valueOf(0));
                                else return;
                                if (sFraudBlocked < 24) {
                                    sFraudBlocked++;
                                    Diag.log("nofraud: 已阻断 " + tag2);
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    });
                    n++;
                    nAware++;
                    sFraudHookedNames.add("awareness." + nm);
                    Diag.note("ok: awareness <- " + nm);
                } catch (Throwable ignored) {
                }
            }
            if (n > before) {
                Diag.note("ok: awareness 已拦截 " + (n - before) + " 处（仅 securityserver 进程）");
            }
        } catch (Throwable t) {
            Diag.note("skip: AwarenessClient (" + t.getClass().getSimpleName() + ")");
        }

        // 4) 静态注册的「云参数」接收器（第 4 轮深挖发现）。
        //
        // dumpys 里 securityserver 挂着两个 Receiver：
        //   .scenerecommend.CloudParaReceiver            场景推荐云参数
        //   .facerecognition.receiver.CloudParaReceiver  人脸识别云参数
        // 反诈的规则/名单大概率也从这里下发 —— 不拦的话，就算本地采集被堵，
        // 云端仍能推新规则让它换个姿势采。
        String[] cloudRecv = {
                "com.hihonor.securityserver.scenerecommend.CloudParaReceiver",
                "com.hihonor.securityserver.facerecognition.receiver.CloudParaReceiver",
        };
        for (final String rn : cloudRecv) {
            try {
                Class<?> rc = XposedHelpers.findClass(rn, cl);
                XposedBridge.hookAllMethods(rc, "onReceive", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            if (!Cfg.isOn(Cfg.K_NOFRAUD)) return;
                            param.setResult(null);
                            if (sFraudBlocked < 24) {
                                sFraudBlocked++;
                                Diag.log("nofraud: 已拦截云参数 "
                                        + rn.substring(rn.lastIndexOf('.') + 1));
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                });
                n++;
                nRecv++;
                sFraudHookedNames.add(rn.substring(rn.lastIndexOf('.') + 1) + ".onReceive");
                Diag.note("ok: nofraud 云参数接收器 <- "
                        + rn.substring(rn.lastIndexOf('.') + 1));
            } catch (Throwable t) {
                Diag.note("skip: " + rn.substring(rn.lastIndexOf('.') + 1)
                        + " (" + t.getClass().getSimpleName() + ")");
            }
        }

        // ⚠ 判定不能只看总数。早先是 `sFraudDone = n > 0` —— 只要挂上 1 处就永不重试，
        // 于是 DexKit 首次查询没就绪时（只挂上 2 个静态的），另外 6 处永远没机会挂，
        // 日志还显示"共接管 2 处"，看着像成功了。
        //
        // 现在按**类别**判定：三类里任何一类为 0 就认为没挂全，留待下次 handleLoadPackage 重试。
        //
        // 另外这里**故意不读** Cfg.isOn：startPreload 是后台异步加载，挂载这一刻
        // 快照必然还没就绪，读出来一定是 false，反而会误导人。真正的判据在第一次回调里。
        boolean complete = nCtl > 0 && nAware > 0 && nRecv > 0;
        sFraudDone = complete;
        Diag.note("nofraud: 共接管 " + n + " 处（控制=" + nCtl
                + " 感知=" + nAware + " 云参数=" + nRecv + "）"
                + (complete ? "" : " ⚠ 有类别未命中，下次重试"));
        // v3.2.37 正反双例自检。
        //
        // 正例：三类（云控校验 / 感知围栏 / 云参数接收）都有命中 —— 说明拦截点齐全。
        // 反例：把**每一个被挂的方法名**打出来，供人工核对"挂的是不是都跟反诈有关"。
        //   之前只报总数，看不出这几处到底挂在什么方法上 —— 万一 DexKit 锚点
        //   命中了通用网络/上报代码，会把非反诈功能一起掐掉，而总数依旧是"8 处"。
        Diag.note("nofraud 自检：" + (complete ? "✓ 三类齐全" : "⚠ 类别不全")
                + "｜挂钩清单：" + (sFraudHookedNames.isEmpty()
                        ? "(空)" : android.text.TextUtils.join("，", sFraudHookedNames)));
        if (!complete) {
            Diag.note("nofraud ⚠ 提示：类别不全时可能只拦住了部分链路，"
                    + "下一次 handleLoadPackage 会重试；若长期不全请把本行日志发出来");
        }
    }

    /**
     * **总闸拦截**（v3.2.38）—— 不再逐点堵，直接在 Settings 这一层把所有
     * 反诈相关开关读成 0。
     *
     * <p>反编译实证（{@code SafetyCtrlService$e.onChange}）：
     * <pre>
     *   Settings.Global.getInt(resolver, "fraud_cloud_security_check", 0)
     *     → SafetyCtrlService.y(v)          // 保存云控校验结果
     *   Log.i(..., "antifraud detect state switch, enable " + SafetyCtrlService.u())
     * </pre>
     * 也就是说反诈的"开/关"是从 {@link android.provider.Settings} 读出来的。
     * 在这个读口把值强制成 0，等于告诉整个安全服务"反诈检测是关的" ——
     * 一处覆盖所有分支，不用再去追那 40 多个方法。
     *
     * <p>为什么比拦 {@code awareness.dispatch*} 更"整体"：
     * dispatch 是数据已经采集完、准备分发时才拦；Settings 是最上游的开关，
     * 检测压根不会启动，连采集都不会发生。
     *
     * <p>只处理 key 里含 fraud / antifraud 的项，其它 Settings 一概不碰 ——
     * 这是"不误伤"的边界。
     */
    private static void hookFraudSettings() {
        if (!sFraudSettingsHooked.compareAndSet(false, true)) return;
        try {
            XC_MethodHook gate = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (!Cfg.isOn(Cfg.K_NOFRAUD)) return;
                        // getInt(ContentResolver, String key, int def) / getInt(ContentResolver, String, int, int user)
                        if (param.args.length < 2 || !(param.args[1] instanceof String)) return;
                        String key = (String) param.args[1];
                        if (!isFraudSettingKey(key)) return;
                        param.setResult(Integer.valueOf(0));
                        if (sFraudSettingsLog < 8) {
                            sFraudSettingsLog++;
                            Diag.note("nofraud: 总闸拦截 -> Settings key 强制 0："
                                    + key + "（反诈检测视为关闭）");
                        }
                    } catch (Throwable ignored) {
                    }
                }
            };
            int n = 0;
            for (Class<?> sc : new Class<?>[]{
                    android.provider.Settings.Global.class,
                    android.provider.Settings.Secure.class,
                    android.provider.Settings.System.class}) {
                try {
                    XposedBridge.hookAllMethods(sc, "getInt", gate);
                    n++;
                } catch (Throwable ignored) {
                }
                // 有的实现走 getString 再自己 parse
                try {
                    XposedBridge.hookAllMethods(sc, "getString", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                if (!Cfg.isOn(Cfg.K_NOFRAUD)) return;
                                if (param.args.length < 2 || !(param.args[1] instanceof String)) return;
                                String key = (String) param.args[1];
                                if (!isFraudSettingKey(key)) return;
                                param.setResult("0");
                            } catch (Throwable ignored) {
                            }
                        }
                    });
                } catch (Throwable ignored) {
                }
            }
            Diag.note("ok: nofraud 总闸拦截已挂上（Settings.getInt/getString，"
                    + n + " 个 provider）");
        } catch (Throwable t) {
            sFraudSettingsHooked.set(false);
            Diag.note("skip: nofraud 总闸拦截失败 (" + t.getClass().getSimpleName() + ")");
        }
    }

    /**
     * **断链**（v3.2.39）—— 不让 securityserver 连上感知服务。
     *
     * <p>这是"整体拦截"里最省事的一刀：反诈的所有数据都来自
     * {@code com.hihonor.awareness} 的围栏。围栏要工作，客户端必须先
     * {@code bindService} 连上那个服务。**绑定直接返回失败**，后面
     * 注册围栏、接收事件、上报数据整条链子自然全断，不用再逐个方法点去堵。
     *
     * <p>比拦 {@code awareness.dispatch*} 更靠上游：
     * <ul>
     *   <li>dispatch 拦的是"数据已经采集完、准备分发"</li>
     *   <li>这里拦的是"连不上服务" —— **采集根本没开始**</li>
     * </ul>
     *
     * <p>代价（用户已确认接受）：securityserver 进程里其它依赖感知服务的
     * 功能（统一认证的环境风控、异地登录判断）会一起失灵。查找手机
     * （{@code HnAntiTheftDetectionService}）实测对 awareness 零依赖，不受影响。
     *
     * <p>作用域：只在 securityserver 进程里生效（Xposed 作用域限制），
     * YOYO / 情景智能在它们自己的进程里，完全不受影响。
     */
    private static void hookAwarenessBind() {
        if (!sAwarenessBindHooked.compareAndSet(false, true)) return;
        try {
            XC_MethodHook deny = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (!Cfg.isOn(Cfg.K_NOFRAUD)) return;
                        if (param.args == null || param.args.length == 0) return;
                        Object a0 = param.args[0];
                        if (!(a0 instanceof android.content.Intent)) return;
                        android.content.Intent it = (android.content.Intent) a0;
                        String pkg = it.getPackage();
                        android.content.ComponentName cn = it.getComponent();
                        String cls = cn == null ? null : cn.getClassName();
                        boolean hit = (pkg != null && pkg.contains("awareness"))
                                || (cls != null && cls.contains("awareness"));
                        if (!hit) return;
                        param.setResult(Boolean.FALSE);
                        if (!sAwarenessBindLogged) {
                            sAwarenessBindLogged = true;
                            Diag.note("nofraud: 已拒绝绑定感知服务 -> "
                                    + (pkg != null ? pkg : cls)
                                    + "（围栏注册/采集/上报整链断开）");
                        }
                    } catch (Throwable ignored) {
                    }
                }
            };
            int n = 0;
            Class<?> impl = Class.forName("android.app.ContextImpl");
            for (String mn : new String[]{"bindService", "bindServiceAsUser",
                    "bindServiceInstance"}) {
                try {
                    XposedBridge.hookAllMethods(impl, mn, deny);
                    n++;
                } catch (Throwable ignored) {
                }
            }
            Diag.note("ok: nofraud 断链拦截已挂上（ContextImpl " + n + " 个 bind 入口）");
        } catch (Throwable t) {
            sAwarenessBindHooked.set(false);
            Diag.note("skip: nofraud 断链拦截失败 (" + t.getClass().getSimpleName() + ")");
        }
    }

    /**
     * **门面类整体拦截**（v3.2.41）—— 把 awareness 客户端的每个入口一次性全掐掉。
     *
     * <p>为什么不用字符串锚点：实测 {@code REGISTER_FENCE_BY_INTENT} /
     * {@code AWARENESS_FENCE_IDENTIFIER} / 各种围栏名这些字符串**出现在字段初始化和
     * 静态常量里，不在方法体内**，DexKit 的 {@code usingStrings} 按方法扫一个都命中不了
     * （加进去之后挂载数还是 8，一点没涨）。
     *
     * <p>所以直接按**类**来：{@code AwarenessClient} 是围栏能力的唯一门面，注册、查询、
     * 注销全从它走。把它的 boolean/int/void 方法全部按"失败/空"处理，等于整条能力下线。
     *
     * <p>保守边界：**只处理 boolean / int / void** 三种返回类型。
     * 返回对象的方法一律不动 —— 给 null 会让调用方 NPE（比如链式调用
     * {@code client.getXxx().doYyy()}），这类崩溃比"少拦一个方法"严重得多。
     */
    private static void hookAwarenessClientWhole(ClassLoader cl) {
        if (!sAwarenessClientHooked.compareAndSet(false, true)) return;
        try {
            Class<?> ac = cl.loadClass("com.hihonor.awareness.client.AwarenessClient");
            int n = 0;
            for (final java.lang.reflect.Method m : ac.getDeclaredMethods()) {
                Class<?> rt = m.getReturnType();
                final boolean isBool = rt == boolean.class;
                final boolean isInt = rt == int.class;
                final boolean isVoid = rt == void.class;
                if (!isBool && !isInt && !isVoid) {
                    continue;
                }
                try {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                if (!Cfg.isOn(Cfg.K_NOFRAUD)) return;
                                if (isVoid) {
                                    param.setResult(null);
                                } else if (isBool) {
                                    param.setResult(Boolean.FALSE);
                                } else {
                                    param.setResult(Integer.valueOf(0));
                                }
                                if (sAwarenessClientLog < 10) {
                                    sAwarenessClientLog++;
                                    Diag.note("nofraud: 已掐 AwarenessClient."
                                            + m.getName() + "() → 空/失败");
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    });
                    n++;
                } catch (Throwable ignored) {
                }
            }
            if (n > 0) {
                Diag.note("ok: nofraud AwarenessClient 整体拦截已挂上（" + n + " 个入口）");
            } else {
                Diag.note("skip: nofraud AwarenessClient 无可挂方法");
            }
        } catch (Throwable t) {
            sAwarenessClientHooked.set(false);
            Diag.note("skip: nofraud AwarenessClient 整体拦截失败 ("
                    + t.getClass().getSimpleName() + ")");
        }
    }

    private static final java.util.concurrent.atomic.AtomicBoolean sAwarenessClientHooked =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private static volatile int sAwarenessClientLog = 0;

    private static final java.util.concurrent.atomic.AtomicBoolean sAwarenessBindHooked =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private static volatile boolean sAwarenessBindLogged = false;


    /** 只认名字里带 fraud 的 key —— 别的 Settings（亮度、超时…）一概不碰。 */
    private static boolean isFraudSettingKey(String key) {
        if (key == null || key.length() < 5) return false;
        String k = key.toLowerCase(java.util.Locale.US);
        return k.contains("fraud");
    }

    private static final java.util.concurrent.atomic.AtomicBoolean sFraudSettingsHooked =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private static volatile int sFraudSettingsLog = 0;

    /** 记录每一个被成功 hook 的反诈方法，用于反例核对。 */
    private static final java.util.List<String> sFraudHookedNames =
            java.util.Collections.synchronizedList(new java.util.ArrayList<String>());
    private static int sCloudBlocked = 0;

    /**
     * 屏蔽**性能/温控策略的云控下发**（v3.2.0）。
     *
     * <p>来源：反编译 HAware 时挖到的
     * {@code com.hihonor.powergenie.integration.eventhub.bcreceiver.ThermalCfgCotaUpdateReceiver}
     * —— "Cota" 就是云侧配置（Cloud OTA Config）更新。系统收到云端的温控策略包后，
     * 这个 Receiver 负责把它落进本地策略库，之后 PowerGenie 就按云端那套降频/限帧。
     *
     * <p><b>三层拦截（v3.2.36 起）</b>：
     * <ol>
     *   <li><b>通知层</b>：三个 {@code *CotaUpdateReceiver.onReceive} 直接 setResult，
     *       等于"收到了但不当回事"，不会触发配置热重载</li>
     *   <li><b>应用层</b>：{@code CloudConfigUpdateAgent.updateCloudConfigFile}（void）跳过</li>
     *   <li><b>读取层</b>：见 {@link #hookCotaFileAccess} —— 对 cpuconfig / pg_config /
     *       thermal_config 这三个路径，exists/isFile/length/canRead 一律返回"不存在"，
     *       FileInputStream / FileReader / RandomAccessFile 的构造抛 FileNotFoundException，
     *       NIO 的 Files / FileChannel 同样挡掉</li>
     * </ol>
     *
     * <p>⚠ <b>文件本身是拦不住的</b>：写入方是 hnouc（OTA 组件），运行在别的进程里，
     * 不在我们的作用域内。所以本开关的实际语义是"**写了也读不到**"——
     * PowerGenie 拿到的永远是它自己的原厂内置配置。
     * 想连带把已经落盘的文件清掉，用界面上的「清理云端配置残留（ROOT）」。
     *
     * <p>⚠ 这也是为什么它和「屏蔽高温限制」是两个开关：那个管的是**设备已经热了之后**
     * 别降频；这个管的是**云端别再把新的降频策略推下来**。两者互补。
     */
    private static void hookCloudPolicy(ClassLoader cl, String apkPath) {
        if (sCloudDone) return;
        int n = 0;
        int nRecv = 0;    // COTA Receiver（下发入口，最关键）
        int nRead = 0;    // 读取入口
        // 1) 三条 COTA 下发通道全拦。反编译 HAware 后确认云端配置按类型分开落地：
        //      CpuCfgCotaUpdateReceiver   -> /data/cota/para/cpuconfig      CPU 频率/核数策略
        //      PgConfigCotaUpdateReceiver -> /data/cota/para/pg_config      PowerGenie 通用策略
        //      ThermalCfgCotaUpdateReceiver -> /data/cota/para/thermal_config 温控策略
        //    只拦温控那一个是不够的 —— CPU 配置单独走 CpuCfg 通道，那份才是真正
        //    定「什么温度压到多少频率」的东西。
        String[] recv = {
                "com.hihonor.powergenie.integration.eventhub.bcreceiver.CpuCfgCotaUpdateReceiver",
                "com.hihonor.powergenie.integration.eventhub.bcreceiver.PgConfigCotaUpdateReceiver",
                "com.hihonor.powergenie.integration.eventhub.bcreceiver.ThermalCfgCotaUpdateReceiver",
        };
        for (final String rn : recv) {
            // v3.2.36：已挂过的跳过（失败重试时不要重复挂）
            if (!sCloudHooked.add("R:" + rn)) {
                nRecv++;   // 计入本次统计，但不重复 hook
                continue;
            }
            try {
                Class<?> rec = XposedHelpers.findClass(rn, cl);
                XposedBridge.hookAllMethods(rec, "onReceive", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            boolean on = Cfg.isOn(Cfg.K_NOCLOUD);
                            if (!sCloudFirstSeen) {
                                sCloudFirstSeen = true;
                                Diag.note("nocloud: 首次回调命中 " + rn.substring(rn.lastIndexOf('.') + 1)
                                        + " | 开关=" + on
                                        + (on ? " → 已拦截" : " → 开关关闭，放行"));
                            }
                            if (!on) return;
                            param.setResult(null);
                            if (!sCloudFirstBlocked) {
                                sCloudFirstBlocked = true;
                                Diag.note("nocloud: 首次拦截 "
                                        + rn.substring(rn.lastIndexOf('.') + 1) + "（真的拦下了）");
                            }
                            if (sCloudBlocked < 8) {
                                sCloudBlocked++;
                                Diag.log("nocloud: 已拦截 "
                                        + rn.substring(rn.lastIndexOf('.') + 1)
                                        + " (" + sCloudBlocked + ")");
                            }
                        } catch (Throwable t) {
                            Diag.err("nocloud gate error", t);
                        }
                    }
                });
                n++;
                nRecv++;
                Diag.note("ok: " + rn.substring(rn.lastIndexOf('.') + 1) + " 云控下发已接管");
            } catch (Throwable t) {
                sCloudHooked.remove("R:" + rn);   // 没挂上，允许下次重试
                Diag.note("skip: " + rn.substring(rn.lastIndexOf('.') + 1)
                        + " (" + t.getClass().getSimpleName() + ")");
            }
        }

        // 1b) 读取入口也要堵：即便 Receiver 被拦，进程启动时若已经读过
        //     /data/cota/para/ 下的旧配置，照样会按云端那套跑。
        if (apkPath != null) {
            // 锚点用**文件路径**而不是日志文案（v3.2.5 第 5 轮）。
            //
            // 反编译后发现读 /data/cota/para/ 的方法有 8 个，分布在三处：
            //   powergenie.core.f0.q
            //   powergenie.core.policy.b.B / b.C / b.D / b.M / b.t
            //   powergenie.core.policy.l.d / l.e
            //   powergenie.modules.networkpolicy.n.h
            //   thermal.core.b.v
            // 之前只按"日志文案"找，只盖住 3 个，其余照旧读云端配置。
            // 换成路径当锚点，一处覆盖全部。
            String[] readAnchors = {
                    "/data/cota/para/",              // ← 统一入口，覆盖所有 Cota 配置文件读取
                    "pg_config_list.xml",
                    "thermal_config_cloud.xml",
                    "Load CPU config file from cota dir",
                    "use new cpu policy config file from cota",
                    "onCotaConfigChanged",
                    // ⚠ PolicyProvider：除了读文件，云控配置还通过 ContentProvider
                    //   对外暴露（content://com.hihonor.powergenie/cotaconfig）。
                    //   PolicyProvider.c 里就是 "get cotaconfig cursor"。
                    //   它走的是数据库/内存，不碰 /data/cota/para/，上面那些路径锚点
                    //   一个都盖不到 —— 第 6 轮复查才发现。
                    "get cotaconfig cursor",
                    "cotaconfig",
            };
            for (String a2 : readAnchors) {
                try {
                    java.util.List<java.lang.reflect.Method> ms =
                            DexKitHelper.findMethodsByStrings(apkPath, cl, a2);
                    for (final java.lang.reflect.Method m : ms) {
                        // v3.2.36：已挂过的跳过。sCloudDone 只在三个 Receiver **全成功**
                        // 时才置位，失败重试会把同一批方法重复 hook —— 回调执行多次，
                        // 计数翻倍。所有回调都是 setResult（幂等）所以没暴露，但必须清掉。
                        String sigKey = "M:" + m.getDeclaringClass().getName() + "#"
                                + m.getName() + "/" + m.getParameterTypes().length;
                        if (!sCloudHooked.add(sigKey)) {
                            continue;
                        }
                        final String nm = m.getName();
                        if (nm.startsWith("on") && nm.endsWith("Receive")) continue;
                        final Class<?> rt = m.getReturnType();
                        try {
                            XposedBridge.hookMethod(m, new XC_MethodHook() {
                                @Override
                                protected void beforeHookedMethod(MethodHookParam param) {
                                    try {
                                        if (!Cfg.isOn(Cfg.K_NOCLOUD)) return;
                                        if (rt == boolean.class) param.setResult(Boolean.FALSE);
                                        else if (rt == void.class) param.setResult(null);
                                        else if (rt == int.class) param.setResult(Integer.valueOf(0));
                                        else if (java.io.InputStream.class.isAssignableFrom(rt)) {
                                            // ⚠ 返回流的方法**不改返回值，只记录**（v3.2.5 第 6 轮）。
                                            //
                                            // 之前给空流，本想"让解析失败退回默认策略"。但反编译核查
                                            // powergenie.core.f0.q 的方法体：**move-exception 出现 0 次**
                                            // —— 它根本没有 try/catch。空流解析失败会把异常抛上去，
                                            // powergenie 是常驻系统进程，崩了温控直接失效，
                                            // 比"没拦住云控"严重得多。
                                            //
                                            // 所以这里降级为观察：读到配置照常返回，只在日志里点名，
                                            // 让用户/我们知道"这台机器上有云端配置"。真正阻断靠
                                            // 上面那三个 Receiver —— 新配置压根不会落地。
                                            if (!sCloudStreamSeen) {
                                                sCloudStreamSeen = true;
                                                Diag.note("nocloud: 检测到读云端配置 "
                                                        + m.getDeclaringClass().getSimpleName()
                                                        + "." + nm + "（只观察，不改返回值）");
                                            }
                                            return;
                                        }
                                        else return;
                                        if (!sCloudFirstBlocked) {
                                            sCloudFirstBlocked = true;
                                            Diag.note("nocloud: 首次拦截读取入口 "
                                                    + m.getDeclaringClass().getSimpleName()
                                                    + "." + nm + " ret=" + rt.getSimpleName());
                                        }
                                        if (sCloudBlocked < 12) {
                                            sCloudBlocked++;
                                            Diag.log("nocloud: 已阻断读取入口 " + nm);
                                        }
                                    } catch (Throwable ignored) {
                                    }
                                }
                            });
                            n++;
                            nRead++;
                            Diag.note("ok: 云控读取入口 <- "
                                    + m.getDeclaringClass().getSimpleName()
                                    + "." + nm + " ret=" + rt.getSimpleName());
                        } catch (Throwable ignored) {
                            sCloudHooked.remove(sigKey);   // 没挂上，允许下次重试
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        }

        // 2) DexKit 兜底：按字符串锚点再找一遍（类名随 OTA 变了也能命中）
        if (apkPath != null) {
            for (String anchor2 : new String[]{"ThermalCfgCotaUpdate", "thermal_cota", "cota_thermal"}) {
                try {
                    java.util.List<java.lang.reflect.Method> ms =
                            DexKitHelper.findMethodsByStrings(apkPath, cl, anchor2);
                    for (final java.lang.reflect.Method m : ms) {
                        if (m.getName().startsWith("on")) {
                            // 已经按类名挂过的跳过（同方法重复挂会触发两次）
                            continue;
                        }
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                try {
                                    if (!Cfg.isOn(Cfg.K_NOCLOUD)) return;
                                    param.setResult(null);
                                } catch (Throwable ignored) {
                                }
                            }
                        });
                        n++;
                        Diag.note("ok: 云控兜底锚点 " + anchor2 + " -> "
                                + m.getDeclaringClass().getSimpleName() + "." + m.getName());
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        // ⚠ 和反诈一样：不能只看总数。三个 Receiver 是真正阻断下发的关键，
        // 只要有一类没挂上就必须重试 —— 否则"挂了 18 个读取入口、3 个 Receiver 全失败"
        // 也算成功，云端照样能推。
        boolean complete = nRecv >= 3;
        sCloudDone = complete;
        Diag.note("nocloud: 共接管 " + n + " 处（下发=" + nRecv
                + " 读取=" + nRead + "）"
                + (complete ? "" : " ⚠ Receiver 未挂全，下次重试"));
    }
    /**
     * 让 PowerGenie **看不到**云控文件（v3.2.34）。
     *
     * <p>为什么不在解析层返回空流：反编译核查 {@code powergenie.core.f0.q} 的方法体，
     * {@code move-exception} 出现 0 次 —— 它根本没有 try/catch。给空流会让解析失败
     * 抛到顶层，PowerGenie 是常驻系统进程，崩了温控直接失效，比"没拦住云控"严重得多。
     *
     * <p>为什么伪造"不存在"是对的：这三个文件本来就可能不存在，**每一条读取路径都
     * 必须处理这种情况**，否则原厂设备早就崩了。所以把 {@code exists/isFile/length}
     * 改成"没有"，调用方会自然回退到**它自己的原厂内置配置** —— 我们不需要覆盖任何
     * 配置内容，也就不会出现"把别家机型的参数套到这台机器上"的问题。
     *
     * <p>只对这三个路径生效，且只改返回值、不抛异常，对 PowerGenie 其它文件操作零影响。
     */
    private static void hookCotaFileAccess() {
        if (!sCotaHideHooked.compareAndSet(false, true)) return;
        try {
            XC_MethodHook hide = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (!Cfg.isOn(Cfg.K_NOCLOUD)) return;
                        Object o = param.thisObject;
                        if (!(o instanceof java.io.File)) return;
                        String p = ((java.io.File) o).getPath();
                        if (!isCotaPerfFile(p)) return;
                        String nm = param.method.getName();
                        if ("length".equals(nm)) {
                            param.setResult(Long.valueOf(0L));
                        } else if ("exists".equals(nm) || "isFile".equals(nm)
                                || "canRead".equals(nm)) {
                            param.setResult(Boolean.FALSE);
                        }
                        if (!sCotaHideLogged) {
                            sCotaHideLogged = true;
                            Diag.note("nocloud: 已把云控文件伪装成不存在 -> " + p
                                    + "（PowerGenie 将回退到原厂内置配置）");
                        }
                    } catch (Throwable ignored) {
                    }
                }
            };
            int n = 0;
            for (String m : new String[]{"exists", "isFile", "length", "canRead"}) {
                try {
                    XposedBridge.hookAllMethods(java.io.File.class, m, hide);
                    n++;
                } catch (Throwable ignored) {
                }
            }
            // ⚠ v3.2.35：只拦 exists/isFile/length 是**不够的**。
            // 那 20 个读取入口里如果有直接 `new FileInputStream(file)` 的，
            // 上面四个探测点一个都不会被调用 —— 拦截形同虚设。
            // 所以再补构造器：命中这三个路径就抛 FileNotFoundException。
            //
            // 为什么敢抛：这三个文件**本来就可能不存在**，任何读取代码都必须
            // 处理 FileNotFoundException（否则原厂设备早就崩了），这是合法分支，
            // 不是"我们制造的新异常"。
            int nCtor = hookCotaStreamCtors();
            Diag.note("ok: 云控文件读取拦截已挂上（" + n + " 个 File 探测点 / "
                    + nCtor + " 个流构造点）");
            // 挂完立刻自证一次：有测试文件时能直接看出 hook 到底生效没
            if (Cfg.isOn(Cfg.K_NOCLOUD)) {
                selfCheckCotaHide();
            } else {
                Diag.note("nocloud 自检跳过：开关关着，拦截本就不该生效");
            }
        } catch (Throwable t) {
            sCotaHideHooked.set(false);   // 允许下次重试
            Diag.note("skip: 云控文件读取拦截失败 (" + t.getClass().getSimpleName() + ")");
        }
    }

    /**
     * 拦截三种最常见的"直接打开文件"方式。命中的路径一律当**文件不存在**处理。
     *
     * <p>返回成功挂载的数量。任何一种挂不上都不影响其它几种。
     */
    private static int hookCotaStreamCtors() {
        int n = 0;
        // 1) new FileInputStream(String) / (File)
        try {
            XC_MethodHook fis = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (!Cfg.isOn(Cfg.K_NOCLOUD)) return;
                    String p = cotaPathOf(param);
                    if (p == null) return;
                    noteCotaHideHit(p);
                    throw new java.io.FileNotFoundException(
                            p + " (hidden by HonorAdvancedSettings)");
                }
            };
            XposedBridge.hookAllConstructors(java.io.FileInputStream.class, fis);
            n++;
        } catch (Throwable ignored) {
        }
        // 2) new FileReader(String) / (File)
        try {
            XC_MethodHook fr = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (!Cfg.isOn(Cfg.K_NOCLOUD)) return;
                    String p = cotaPathOf(param);
                    if (p == null) return;
                    noteCotaHideHit(p);
                    throw new java.io.FileNotFoundException(
                            p + " (hidden by HonorAdvancedSettings)");
                }
            };
            XposedBridge.hookAllConstructors(java.io.FileReader.class, fr);
            n++;
        } catch (Throwable ignored) {
        }
        // 3) new RandomAccessFile(String, String) / (File, String)
        try {
            XC_MethodHook raf = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (!Cfg.isOn(Cfg.K_NOCLOUD)) return;
                    String p = cotaPathOf(param);
                    if (p == null) return;
                    noteCotaHideHit(p);
                    throw new java.io.FileNotFoundException(
                            p + " (hidden by HonorAdvancedSettings)");
                }
            };
            XposedBridge.hookAllConstructors(java.io.RandomAccessFile.class, raf);
            n++;
        } catch (Throwable ignored) {
        }
        // 4) java.nio.file.Files / FileChannel（v3.2.36 补）
        //    实测 PowerGenie 那 20 个读取入口都返回 InputStream（走 FileInputStream），
        //    但 OTA 换实现是常事 —— 真改成 NIO 的话上面三种一个都盖不到，这里兜底。
        try {
            XC_MethodHook nio = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (!Cfg.isOn(Cfg.K_NOCLOUD)) return;
                    String p = null;
                    try {
                        if (param.args != null && param.args.length > 0
                                && param.args[0] instanceof java.nio.file.Path) {
                            p = param.args[0].toString();
                        }
                    } catch (Throwable ignored) {
                    }
                    if (!isCotaPerfFile(p)) return;
                    noteCotaHideHit(p);
                    throw new java.nio.file.NoSuchFileException(p);
                }
            };
            int nioPoints = 0;
            for (String mn : new String[]{"newInputStream", "newBufferedReader",
                    "lines", "readAllBytes"}) {
                try {
                    XposedBridge.hookAllMethods(java.nio.file.Files.class, mn, nio);
                    nioPoints++;
                } catch (Throwable ignored) {
                }
            }
            try {
                XposedBridge.hookAllMethods(java.nio.channels.FileChannel.class, "open", nio);
                nioPoints++;
            } catch (Throwable ignored) {
            }
            if (nioPoints > 0) {
                n++;
                Diag.note("ok: 云控 NIO 兜底已挂上（" + nioPoints + " 个）");
            }
        } catch (Throwable ignored) {
        }
        return n;
    }

    /** 从构造参数里取出路径并校验是否是那三个云控文件；不是则返回 null。 */
    private static String cotaPathOf(XC_MethodHook.MethodHookParam param) {
        try {
            if (param.args == null || param.args.length == 0) return null;
            Object a0 = param.args[0];
            String p = null;
            if (a0 instanceof String) {
                p = (String) a0;
            } else if (a0 instanceof java.io.File) {
                p = ((java.io.File) a0).getPath();
            }
            return isCotaPerfFile(p) ? p : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void noteCotaHideHit(String p) {
        if (sCotaHideLogged) return;
        sCotaHideLogged = true;
        Diag.note("nocloud: 已把云控文件伪装成不存在 -> " + p
                + "（PowerGenie 将回退到原厂内置配置）");
    }

    /**
     * **自证**：证明 File hook 真的起作用了，而不是"挂上了但没生效"。
     *
     * <p>做法：用 {@code File.list()} 看目录里到底有没有这个文件（list 不受我们
     * 的 exists hook 影响），再调 {@code new File(path).exists()} 看返回值。
     * <ul>
     *   <li>物理存在 + exists() 返回 false → hook 生效 ✓</li>
     *   <li>物理存在 + exists() 返回 true → hook 没生效 ⚠</li>
     *   <li>物理不存在 → 无法区分（跳过，不打日志）</li>
     * </ul>
     * 这是唯一能在"云控文件没来过"的情况下证明拦截层可用的手段。
     */
    private static void selfCheckCotaHide() {
        try {
            // 【反例优先】先证明"没误伤" —— 目录本身不在这三个路径里，
            // 它必须仍然可见。如果这里返回 false，说明 hook 匹配写得太宽，
            // 会把 PowerGenie 其它 cota 文件（无障碍、权限、胶囊…）一起挡掉，
            // 那是比"没拦住云控"严重得多的故障。
            boolean dirVisible = new java.io.File("/data/cota/para").exists();
            Diag.note("nocloud 自检：/data/cota/para 目录 "
                    + (dirVisible ? "仍可见 ✓（未误伤其它 cota 文件）"
                                  : "⚠ 被误伤！匹配范围过宽"));

            for (String name : new String[]{"cpuconfig", "pg_config", "thermal_config"}) {
                String path = "/data/cota/para/" + name;
                java.io.File dir = new java.io.File("/data/cota/para");
                String[] names = dir.list();
                if (names == null) return;
                boolean physically = false;
                for (String s : names) {
                    if (s != null && s.equals(name)) {
                        physically = true;
                        break;
                    }
                }
                if (!physically) continue;   // 没有真文件，没法做对照实验
                boolean seen = new java.io.File(path).exists();
                Diag.note("nocloud 自检 [" + name + "]：文件实际存在=true，exists()=" + seen
                        + (seen ? " → ⚠ 拦截未生效（hook 没命中）"
                                : " → ✓ 拦截已生效（PowerGenie 看不到它）"));
                return;
            }
        } catch (Throwable ignored) {
        }
    }

    /** 只认这三份性能/温控云控配置，别的 cota 文件（无障碍、权限、胶囊）一概不碰。 */
    private static boolean isCotaPerfFile(String p) {
        if (p == null || p.length() < 24) return false;
        if (!p.startsWith("/data/cota/para/")) return false;
        return p.endsWith("/cpuconfig") || p.endsWith("/pg_config")
                || p.endsWith("/thermal_config");
    }

    private static final java.util.concurrent.atomic.AtomicBoolean sCotaHideHooked =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private static volatile boolean sCotaHideLogged = false;

    private static boolean sExtraDone = false;

    /**
     * 游戏/温控的**真正执行端**（v3.1.0）。
     *
     * <p>背景：`ThermalManager.handleThermalEvent` 只挡住"通知类"策略（弹窗、后台冻结）。
     * 真正让游戏掉帧、降画质、锁亮度的是另一条链 —— 这些类**类名可读、方法名被 R8
     * 压成单字母**（`c1.l` / `f1.o0` / `display.d.o`），按名字 hook 既写不出、OTA 一变就废。
     *
     * <p>所以改用 DexKit 的字符串锚点定位（见 {@link DexKitHelper#findMethodsByStrings}）。
     * 反编译 HAware(3.8MB dex) 得到的映射：
     * <pre>
     *   setScheduleFPS              com.hihonor.thermal.core.c1.l          帧率下发
     *   Game picture quality change com.hihonor.thermal.core.f1.o0         画质降级
     *   Disable local brightness    powergenie.core.display.d.o + display.m.p   亮度禁用
     *   exit local brightness       DisplayCtrlManager.checkPkgLocalBrightness* 亮度退出
     *   charge separation switch    v0.i.q / v0.i.r                        充电分离
     *   sendFpsResult               PanguResGovernor.T                     帧率上报
     * </pre>
     */
    private static void hookThermalExtra(String apkPath, ClassLoader cl) {
        if (sExtraDone) return;
        if (apkPath == null || cl == null) return;

        int n = 0;
        int nFps = 0;      // 帧率链路（最关键）
        int nOther = 0;    // 画质 / 亮度
        nFps += hookByString(apkPath, cl, "fps", "setScheduleFPS");
        n += hookByString(apkPath, cl, "quality", "Game picture quality change");
        nOther += hookByString(apkPath, cl, "quality", "Game picture quality change");
        n += hookByString(apkPath, cl, "brightness-off", "Disable local brightness");
        nOther += hookByString(apkPath, cl, "brightness-off", "Disable local brightness");
        n += hookByString(apkPath, cl, "brightness-exit", "exit local brightness");
        nOther += hookByString(apkPath, cl, "brightness-exit", "exit local brightness");
        // ⚠ 充电分离**只观察不阻断**（v3.1.0 第 5 遍复查结论）。
        // 它不是"性能限制"，而是**电池保护**：游戏时绕过电池、由充电器直接供电，
        // 避免边充边放导致电池发热。阻断它 = 电池边充边放 = 机身更热
        // = 反而更早触发温控降频。挡它属于帮倒忙。
        n += hookByString(apkPath, cl, "charge-sep(observe)", "charge separation switch", false);
        n += hookByString(apkPath, cl, "fps-report", "sendFpsResult");
        nFps += hookByString(apkPath, cl, "fps-report", "sendFpsResult");
        // checkFpsAsync：PanguResGovernor 里的**异步帧率检查**线程。
        // 只挡上报（sendFpsResult）不够 —— 检查逻辑本身仍会按实际帧率
        // 生成"需要限帧"的结论并下发。两个都要挡。
        n += hookByString(apkPath, cl, "fps-check", "checkFpsAsync");
        nFps += hookByString(apkPath, cl, "fps-check", "checkFpsAsync");
        // 一个都没挂上（DexKit 还没就绪 / apk 路径不对）就别把 sExtraDone 锁死，
        // 留给下一次 handleLoadPackage 重试
        // ⚠ 同反诈/云控的坑：不能只看总数。帧率链路（fps / fps-report / fps-check）
        // 是游戏掉帧的主因，它没挂上就等于白干；画质/亮度可以少一两处。
        boolean complete = nFps >= 2 && nOther >= 1;
        sExtraDone = complete;
        Diag.note("thermal-extra: 共挂载 " + n + " 处（帧率=" + nFps
                + " 画质亮度=" + nOther + "）"
                + (complete ? "" : " ⚠ 帧率链路未挂全，下次重试"));
        // 挂完就把 dex 索引放掉：Hook 已经拿到 Method 对象，不再需要 bridge。
        // 没挂上则保留（还要重试）。
        if (sExtraDone) DexKitHelper.release();
    }

    /**
     * 按锚点字符串把**所有**匹配的方法都挂上。
     *
     * <p>关键点（都是踩过的）：
     * <ul>
     *   <li>同一方法名常有重载（`display.d.o` 有 `()AtomicBoolean` 和 `(String)Z`），
     *       只取第一个容易挑错、白 hook</li>
     *   <li>返回值只给**原始类型**的默认值：void→null、boolean→false、int/long→0</li>
     *   <li>返回**对象**的方法（如 `()AtomicBoolean`）**不阻断** —— 给 null 会让
     *       调用方直接 NPE，崩了比不生效更糟。这类只挂上去记日志，便于诊断</li>
     * </ul>
     */
    private static int hookByString(String apkPath, ClassLoader cl,
                                    final String tag, String anchor) {
        return hookByString(apkPath, cl, tag, anchor, true);
    }

    /**
     * @param block true = 阻断（setResult 掉）；false = 只挂上去记日志，不影响原逻辑
     */
    private static int hookByString(String apkPath, ClassLoader cl,
                                    final String tag, String anchor, final boolean block) {
        int hooked = 0;
        try {
            java.util.List<java.lang.reflect.Method> methods =
                    DexKitHelper.findMethodsByStrings(apkPath, cl, anchor);
            if (methods.isEmpty()) {
                Diag.warn("thermal-extra: " + tag + " 未找到（锚点 " + anchor + "）");
                return 0;
            }
            for (final java.lang.reflect.Method m : methods) {
                final Class<?> rt = m.getReturnType();
                final boolean blockable = block && (rt == void.class || rt == boolean.class
                        || rt == int.class || rt == long.class);
                try {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                boolean on = Cfg.hottempOn();
                                if (!sThermalFirstSeen) {
                                    sThermalFirstSeen = true;
                                    Diag.note("thermal: 首次回调命中 " + tag
                                            + " | 开关=" + on
                                            + (on ? " → 已拦截" : " → 开关关闭，放行"));
                                }
                                if (!on) return;
                                if (!blockable) return;   // 只观察
                                if (rt == void.class) param.setResult(null);
                                else if (rt == boolean.class) param.setResult(Boolean.FALSE);
                                else if (rt == int.class) param.setResult(Integer.valueOf(0));
                                else if (rt == long.class) param.setResult(Long.valueOf(0L));
                                else return;   // 返回对象：不阻断，防 NPE
                                if (sExtraBlocked < 12) {
                                    sExtraBlocked++;
                                    Diag.log("thermal-extra: blocked " + tag + " -> "
                                            + m.getDeclaringClass().getSimpleName()
                                            + "." + m.getName());
                                }
                            } catch (Throwable t) {
                                Diag.err("thermal-extra gate error", t);
                            }
                        }
                    });
                    hooked++;
                    Diag.note("ok: thermal-extra " + tag + " <- "
                            + m.getDeclaringClass().getName() + "." + m.getName()
                            + " ret=" + rt.getSimpleName()
                            + (blockable ? "" : "（只观察不阻断）"));
                } catch (Throwable t) {
                    Diag.err("thermal-extra hookMethod failed: " + tag, t);
                }
            }
        } catch (Throwable t) {
            Diag.err("thermal-extra hook failed: " + tag, t);
        }
        return hooked;
    }


    private static boolean sPgDone = false;
    private static int sPgTries = 0;
    private static boolean sAmHooked = false;

    // ==================== thermal normalization (system_server) ====================

    /**
     * 高温不降级 (2026-10-31, round 2): the user wants the phone to behave
     * EXACTLY as normal while hot — not merely hide the popup. This hooks the
     * Java thermal pipeline at its source inside system_server:
     *
     *   ThermalManagerService.setStatusLocked(int)   -> force status 0 (NONE)
     *       every Java consumer (SystemUI PowerUI, GameKit, apps' thermal
     *       listeners, PowerManager.getCurrentThermalStatus) then sees a
     *       normal device: no warnings, no per-app throttling decisions
     *   ThermalManagerService$2.getCurrentThermalStatus() -> 0 (belt+braces)
     *   ThermalManagerService$2.getCurrentThermalStatusForDevice() -> 0
     *   ThermalManagerService.shutdownIfNeeded(Temperature) -> skipped
     *       (user decision 2026-10-31: the emergency thermal shutdown is part
     *        of the restriction being removed; verified that this method reads
     *        the RAW HAL Temperature — it is NOT driven by mStatus, so it is
     *        the only place the shutdown lives and must be hooked here)
     *   PowerManagerService$BinderService.setMaxBrightnessFromThermal()
     *       -> skipped: Honor's brightness cap at high temperature
     *
     * NOT touched (below Xposed reach, still protecting the SoC):
     *   - MTK kernel/HAL throttling (ThermalDaemon / libPowerHal) — native.
     *   - Vendor thermal HAL shutdown (if the HAL itself cuts power).
     *
     * BOOT SAFETY (explicit requirement): hooks only add an argument rewrite
     * or a skip on already-running service methods — no class init is forced,
     * nothing runs before the service starts, every callback is try/catch'd
     * (an exception inside a hook is swallowed by Xposed and the original
     * method continues), every target is resolved by NAME with a shape check,
     * and a per-process guard makes repeated handleLoadPackage calls (Vector
     * does that) idempotent. Worst case = a hook never installs and stock
     * thermal behaviour stays.
     *
     * COST: every callback reads Cfg.hottempOn(), which is a cached boolean
     * (one property read per second per process, resolved Method, no binder,
     * no allocation). Per-call overhead is a volatile read + uptimeMillis.
     */
    public static void initSystemServer(ClassLoader cl) {
        hookThermalNormalize(cl);
        hookSecureFlag(cl);
    }

    // ==================== 安全组件：屏蔽截图/录屏限制（v3.2.0） ====================

    /**
     * 去掉窗口的 FLAG_SECURE，让原本禁止截图/录屏的页面可以被正常截图。
     *
     * <p>FLAG_SECURE(0x2000) 是 app 在 WindowManager.LayoutParams 上设的位，
     * 一旦置上：截屏黑屏、录屏黑块、最近任务里也变成纯色。银行、支付、部分阅读类
     * app 都靠它防截屏。这里在 **system_server 的窗口添加/更新入口**把它清掉，
     * 一次覆盖所有 app —— 不需要给每个 app 单独挂作用域。
     *
     * <p>⚠ 副作用（必须让用户知道）：清了之后这些页面也能被截图，包括别人拿你手机时。
     * 所以做成默认关闭、单独开关。
     *
     * <p>⚠ 只动 flags，**不改 SurfaceControl 的 secure 属性**。有些 app 走的是
     * SurfaceView.setSecure(true) 这条路，那属于真正的硬件层保护，这里不碰
     * （碰了会黑屏）。
     */
    private static void hookSecureFlag(ClassLoader cl) {
        if (sSecureHooked) return;
        try {
            Class<?> wms = XposedHelpers.findClass("com.android.server.wm.WindowManagerService", cl);
            final int FLAG_SECURE = 0x00002000;

            // addWindow 和 relayoutWindow 都会收到 WindowManager.LayoutParams 作为参数，
            // 清掉这个位即可。两个都挂：有的窗口是 add 时带的，有的是后续 relayout 才设上。
            int n = 0;
            int nAdd = 0;      // addWindow：窗口创建时带的 FLAG_SECURE
            int nRelayout = 0; // relayoutWindow：窗口后续改 flags 走这里
            for (java.lang.reflect.Method m : wms.getDeclaredMethods()) {
                final String nm = m.getName();
                if (!"addWindow".equals(nm) && !"relayoutWindow".equals(nm)) continue;
                final int idx = indexOfLayoutParams(m);
                if (idx < 0) continue;
                try {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                // ⚠ 首调判据必须放在开关判断**之前**。
                                // 放后面的话，开关一关就永远看不到这条日志，
                                // 排查时分不清"钩子没被调用"和"开关关着"。
                                if (!sSecureFirstSeen) {
                                    sSecureFirstSeen = true;
                                    boolean on0 = Cfg.nosecureOn();
                                    Diag.note("nosecure: 首次回调命中 " + nm
                                            + " | 开关=" + on0
                                            + (on0 ? " → 开始清除" : " → 开关关闭，放行"));
                                }
                                if (!Cfg.nosecureOn()) return;
                                if (param.args.length <= idx) return;
                                Object a = param.args[idx];
                                if (!(a instanceof android.view.WindowManager.LayoutParams)) return;
                                android.view.WindowManager.LayoutParams lp =
                                        (android.view.WindowManager.LayoutParams) a;
                                if ((lp.flags & FLAG_SECURE) != 0) {
                                    lp.flags &= ~FLAG_SECURE;
                                    if (!sSecureFirstBlocked) {
                                        sSecureFirstBlocked = true;
                                        Diag.note("nosecure: 首次拦截 —— 清掉了窗口 \""
                                                + lp.getTitle() + "\" 的 FLAG_SECURE");
                                    }
                                    if (sSecureLogged < 6) {
                                        sSecureLogged++;
                                        Diag.log("nosecure: 已清除 FLAG_SECURE ("
                                                + lp.getTitle() + ")");
                                    }
                                }
                            } catch (Throwable t) {
                                Diag.err("nosecure gate error", t);
                            }
                        }
                    });
                    n++;
                    if ("addWindow".equals(nm)) nAdd++;
                    else nRelayout++;
                } catch (Throwable ignored) {
                }
            }
            // ⚠ 两个入口都要挂上才算完整：
            //   addWindow     -- 窗口创建时带的 FLAG_SECURE
            //   relayoutWindow -- 窗口后续 setFlags 时走这里
            // 只挂一个也能"看起来成功"（n>0），但另一条路会漏 —— 用户表现就是
            // "有的页面能截图、有的还是黑"。所以按类别判定。
            // 第 3 条写入路径：setWindowFlags(int flags, int mask)
            //
            // Window.setFlags / addFlags / clearFlags 走的是 IWindowSession.setFlags
            // -> WMS.setWindowFlags -> WindowState.mAttrs.flags = flags，
            // **参数是 int，不经过 LayoutParams**，上面两个 hook 一个都盖不到。
            //
            // 通常改完 flags 会顺带 relayout（那时能兜住），但只改 flags 不 relayout
            // 的情况就会漏。补一条，彻底封死。
            int nSetFlags = 0;
            for (java.lang.reflect.Method m : wms.getDeclaredMethods()) {
                if (!"setWindowFlags".equals(m.getName())) continue;
                Class<?>[] pt = m.getParameterTypes();
                // 找 flags 参数：第一个 int（mask 是第二个 int，不能弄反）
                int flagsIdx = -1;
                for (int i = 0; i < pt.length; i++) {
                    if (pt[i] == int.class) { flagsIdx = i; break; }
                }
                if (flagsIdx < 0) continue;
                final int fi = flagsIdx;
                try {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                if (!sSecureFirstSeen) {
                                    sSecureFirstSeen = true;
                                    boolean on0 = Cfg.nosecureOn();
                                    Diag.note("nosecure: 首次回调命中 setWindowFlags | 开关=" + on0);
                                }
                                if (!Cfg.nosecureOn()) return;
                                if (param.args.length <= fi) return;
                                Object o = param.args[fi];
                                if (!(o instanceof Integer)) return;
                                int f = (Integer) o;
                                if ((f & FLAG_SECURE) != 0) {
                                    param.args[fi] = f & ~FLAG_SECURE;
                                    if (!sSecureFirstBlocked) {
                                        sSecureFirstBlocked = true;
                                        Diag.note("nosecure: 首次拦截 —— setWindowFlags 清掉 FLAG_SECURE");
                                    }
                                }
                            } catch (Throwable t) {
                                Diag.err("nosecure setFlags error", t);
                            }
                        }
                    });
                    nSetFlags++;
                } catch (Throwable ignored) {
                }
            }
            n += nSetFlags;

            // 判定：addWindow + relayoutWindow 必须都有（这两条是 LayoutParams 通道，
            // 覆盖 99% 的场景）。setWindowFlags 是**可选**的第三条 —— 实测这台设备的
            // WMS 根本没有这个方法（Honor 可能改了签名），不能因为它缺席就判定失败，
            // 否则会一直挂着"未挂全"的误报。
            boolean complete = nAdd > 0 && nRelayout > 0;
            sSecureHooked = complete;
            Diag.note("ok: 截图限制钩子 -> " + n + " 处（addWindow=" + nAdd
                    + " relayout=" + nRelayout
                    + (nSetFlags > 0 ? " setWindowFlags=" + nSetFlags : " setWindowFlags=本机无此方法")
                    + "）"
                    + (complete ? "" : " ⚠ LayoutParams 通道未挂全，下次重试"));
        } catch (Throwable t) {
            Diag.note("skip: WindowManagerService 找不到 (" + t.getClass().getSimpleName() + ")");
        }
    }

    /** 找 WindowManager.LayoutParams 在参数列表里的下标，没有返回 -1。 */
    private static int indexOfLayoutParams(java.lang.reflect.Method m) {
        Class<?>[] pts = m.getParameterTypes();
        for (int i = 0; i < pts.length; i++) {
            if (android.view.WindowManager.LayoutParams.class.isAssignableFrom(pts[i])) return i;
        }
        return -1;
    }

    private static boolean sSecureHooked = false;
    private static int sSecureLogged = 0;
    /** 首次回调命中（证明钩子被调用、开关读到） */
    private static boolean sSecureFirstSeen = false;
    /** 首次真的清掉 FLAG_SECURE（证明拦截生效） */
    private static boolean sSecureFirstBlocked = false;

    private static boolean sSysThermalDone = false;

    private static void hookThermalNormalize(ClassLoader cl) {
        if (sSysThermalDone) return; // idempotent across duplicate load callbacks
        sSysThermalDone = true;
        int ok = 0;
        try {
            Class<?> tms = cl.loadClass("com.android.server.power.thermal.ThermalManagerService");
            for (final Method m : tms.getDeclaredMethods()) {
                final String nm = m.getName();
                if (m.getReturnType() != void.class) continue;
                Class<?>[] ps = m.getParameterTypes();
                if ("setStatusLocked".equals(nm) && ps.length == 1 && ps[0] == int.class) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                if (Cfg.hottempOn()) param.args[0] = 0;
                            } catch (Throwable t) {
                                Diag.err("thermal setStatus gate error", t);
                            }
                        }
                    });
                    ok++;
                    Diag.log("thermal: status normalized (setStatusLocked -> NONE)");
                } else if ("shutdownIfNeeded".equals(nm) && ps.length == 1
                        && "android.os.Temperature".equals(ps[0].getName())) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                if (!Cfg.hottempOn()) return;
                                param.setResult(null);
                                if (sShutdownBlocked < 3) {
                                    sShutdownBlocked++;
                                    Diag.log("thermal: emergency shutdown suppressed (" + sShutdownBlocked + ")");
                                }
                            } catch (Throwable t) {
                                Diag.err("thermal shutdown gate error", t);
                            }
                        }
                    });
                    ok++;
                    Diag.log("thermal: shutdownIfNeeded suppressed");
                }
            }
        } catch (Throwable t) {
            Diag.err("thermal: ThermalManagerService missing", t);
        }
        try {
            Class<?> bs = cl.loadClass("com.android.server.power.thermal.ThermalManagerService$2");
            for (final Method m : bs.getDeclaredMethods()) {
                final String nm = m.getName();
                boolean global = "getCurrentThermalStatus".equals(nm);
                boolean perDev = "getCurrentThermalStatusForDevice".equals(nm);
                if (!global && !perDev) continue;
                if (m.getReturnType() != int.class) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            if (Cfg.hottempOn()) param.setResult(0);
                        } catch (Throwable t) {
                            Diag.err("thermal getter gate error", t);
                        }
                    }
                });
                ok++;
                Diag.log("thermal: " + nm + " -> NONE");
            }
        } catch (Throwable t) {
            Diag.err("thermal: ThermalManagerService$2 missing", t);
        }
        try {
            Class<?> pm = cl.loadClass("com.android.server.power.PowerManagerService$BinderService");
            for (final Method m : pm.getDeclaredMethods()) {
                if (!"setMaxBrightnessFromThermal".equals(m.getName())) continue;
                if (m.getReturnType() != void.class) continue; // never fake a return value
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            if (Cfg.hottempOn()) param.setResult(null);
                        } catch (Throwable t) {
                            Diag.err("thermal brightness gate error", t);
                        }
                    }
                });
                ok++;
                Diag.log("thermal: thermal brightness cap skipped");
            }
        } catch (Throwable t) {
            Diag.err("thermal: PowerManagerService$BinderService missing", t);
        }
        Diag.mount("system_server thermal(setStatus/shutdown/getters/brightness)", ok, 5);
    }

    private static int sShutdownBlocked = 0;
    /** 通知回避：首次回调命中（证明钩子真被调用到、开关读到了） */
    private static boolean sDuckFirstSeen = false;
    /** 通知回避：首次真的拦下一条 MAY_DUCK 请求 */
    private static boolean sDuckFirstBlocked = false;

    /** called from MainHook for the SystemUI process only. */
    public static void initSystemUI(ClassLoader cl) {
        hookHighTempWarning(cl); // misc line: suppress the high-temperature popups
        if (sAmHooked) return;   // Vector can call handleLoadPackage twice per
        sAmHooked = true;        // process; never mount the same methods twice
        try {
            Class<?> am = cl.loadClass("android.media.AudioManager");
            int n = 0;
            for (final Method m : am.getDeclaredMethods()) {
                if (!m.getName().startsWith("requestAudioFocus")) continue;
                final String name = m.getName();
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            boolean on = Cfg.isOn(Cfg.K_NODUCK);
                            if (!sDuckFirstSeen) {
                                // 首调判据必须放在开关判断之前 —— 否则开关一关
                                // 就永远看不到这条，排查时分不清"钩子没被调用"
                                // 和"开关关着"（截图那边踩过这个坑）。
                                sDuckFirstSeen = true;
                                Diag.note("noduck: 首次回调命中 " + name
                                        + " | 开关=" + on
                                        + (on ? " → 开始拦截" : " → 开关关闭，放行"));
                            }
                            if (!on) return;
                            if (isNotificationDuckRequest(expandArgs(param.args))) {
                                if (!sDuckFirstBlocked) {
                                    sDuckFirstBlocked = true;
                                    Diag.note("noduck: 首次拦截 —— 拒绝了 MAY_DUCK 请求（"
                                            + name + "），音乐不会再被压低");
                                }
                                // answer FAILED: the notification player does
                                // not depend on the focus result, it keeps
                                // playing — but the music never gets ducked
                                param.setResult(AUDIOFOCUS_REQUEST_FAILED);
                                Diag.log("notification duck request rejected (" + name + ")");
                            }
                        } catch (Throwable t) {
                            Diag.err("noduck gate error", t);
                        }
                    }
                });
                n++;
            }
            // 期望值写 1 而不是 0：这台设备的 AudioManager 至少有 2 个
            // requestAudioFocus 重载（旧的三参 + 新的 AudioFocusRequest）。
            // 写 0 等于"一个都没挂上也只当提示"，写 1 才能把"宿主改名导致全丢"
            // 变成明确的失败信号。
            Diag.mount("AudioManager.requestAudioFocus*", n, 1);
        } catch (Throwable t) {
            Diag.err("hook AudioManager failed", t);
        }
    }

    // ==================== high-temperature popup suppression ====================

    /**
     * 屏蔽高温提示 (2026-10-31 field report: at high temperature the system
     * pops a modal "高温提示/建议暂停使用" dialog — screenshot evidence — and
     * while it is up every app is effectively unusable).
     *
     * Source chain (verified against the current SystemUI dex + the
     * 2026-10 decompile): system_server sends
     * `hihonor.intent.action.THERMAL_TO_SYSTEMUI` with
     *   high_temp_warning=1 -> PowerNotificationWarnings.responseHighTempBroadcast()
     *                          (the popup in the screenshot; body text =
     *                          string/popupwindow_high02, button = high03)
     *   high_temp_warning=2 -> showHighTempPreWarningDialog (D8 synthetic,
     *                          name contains "HighTempPreWarning")
     *   shutdown=1          -> thermal shutdown countdown dialog — LEFT ALONE
     *                          (a real shutdown is announced by the system;
     *                          hiding that notice would only confuse)
     *
     * Only these two informational dialogs are suppressed. The kernel/HAL
     * thermal throttling (screen dimming, CPU limits) and the emergency
     * shutdown itself are untouched — this hook removes the modal UI, not
     * the hardware protection.
     *
     * BOOT SAFETY: mounted in the SystemUI scope only. A mistake here can
     * never reach system_server or the boot sequence — worst case SystemUI
     * restarts itself (no boot-animation hang). Every hook is independently
     * try/catch'd, resolution is by method NAME (no obfuscated index baked
     * in), and the callbacks only read the Cfg snapshot (no binder I/O).
     */
    private static int sHotTempBlocked = 0;
    private static boolean sHotTempResolved = false;  // idempotent: Vector may
    private static boolean sHotTempMissLogged = false; // call handleLoadPackage twice

    private static void hookHighTempWarning(ClassLoader cl) {
        if (sHotTempResolved) return; // already hooked in this process
        final Class<?> pnw;
        try {
            pnw = cl.loadClass("com.android.systemui.power.PowerNotificationWarnings");
        } catch (Throwable t) {
            if (!sHotTempMissLogged) {
                sHotTempMissLogged = true;
                Diag.warn("high-temp: PowerNotificationWarnings not in this CL yet");
            }
            return;
        }
        sHotTempResolved = true;
        XC_MethodHook blocker = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    if (!Cfg.hottempOn()) return;
                    param.setResult(null);
                    if (sHotTempBlocked < 5) {
                        sHotTempBlocked++;
                        Diag.log("high-temp popup suppressed (" + sHotTempBlocked + ")");
                    }
                } catch (Throwable t) {
                    Diag.err("high-temp gate error", t);
                }
            }
        };
        int hooked = 0;
        try {
            for (Method m : pnw.getDeclaredMethods()) {
                String nm = m.getName();
                boolean main = nm.equals("responseHighTempBroadcast");
                boolean pre = nm.contains("HighTempPreWarning");
                if (!main && !pre) continue;
                try {
                    XposedBridge.hookMethod(m, blocker);
                    hooked++;
                    Diag.log("hooked high-temp popup: " + nm);
                } catch (Throwable t) {
                    Diag.err("high-temp hook failed: " + nm, t);
                }
            }
        } catch (Throwable t) {
            Diag.err("high-temp scan failed", t);
        }
        Diag.mount("PowerNotificationWarnings(high-temp popups)", hooked, 0);
    }

    /** flatten AudioFocusRequest objects into their gain int + attributes,
     *  so the signature-agnostic arg scan can see through the wrapper. */
    private static Object[] expandArgs(Object[] args) {
        if (args == null) return null;
        java.util.ArrayList<Object> out = new java.util.ArrayList<>(args.length + 4);
        for (Object a : args) {
            out.add(a);
            if (a != null && "android.media.AudioFocusRequest".equals(a.getClass().getName())) {
                try {
                    out.add(a.getClass().getMethod("getFocusGain").invoke(a));
                    out.add(a.getClass().getMethod("getAudioAttributes").invoke(a));
                } catch (Throwable ignored) {
                }
            }
        }
        return out.toArray();
    }

    /**
     * true when the intercepted focus request wants MAY_DUCK and comes from
     * the notification family. Scans the args of any overload:
     *   - an int arg equal to MAY_DUCK marks the focus gain
     *   - an AudioAttributes arg with a notification usage, or a legacy
     *     STREAM_NOTIFICATION / STREAM_RING int, marks the source
     * Alarms (USAGE_ALARM) and exclusive requests never match — calls and
     * alarms keep pausing/ducking media exactly as before.
     */
    private static boolean isNotificationDuckRequest(Object[] args) {
        if (args == null) return false;
        boolean mayDuck = false;
        boolean notifSource = false;
        for (Object a : args) {
            if (a instanceof Integer) {
                int v = (Integer) a;
                if (v == AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK) {
                    mayDuck = true;
                } else if (v == STREAM_RING || v == STREAM_NOTIFICATION) {
                    // legacy request path: the stream type marks the source
                    notifSource = true;
                }
            } else if (a != null && "android.media.AudioAttributes".equals(a.getClass().getName())) {
                try {
                    int usage = (Integer) a.getClass().getMethod("getUsage").invoke(a);
                    if (usage == USAGE_NOTIFICATION
                            || usage == USAGE_NOTIFICATION_RINGTONE
                            || usage == USAGE_NOTIFICATION_COMMUNICATION_REQUEST
                            || usage == USAGE_NOTIFICATION_COMMUNICATION_INSTANT
                            || usage == USAGE_NOTIFICATION_COMMUNICATION_DELAYED
                            || usage == USAGE_NOTIFICATION_EVENT) {
                        notifSource = true;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return mayDuck && notifSource;
    }
}
