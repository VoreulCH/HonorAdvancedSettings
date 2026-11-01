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
    public static void initSecurityServer(ClassLoader cl, String apkPath) {
        hookAntiFraud(cl, apkPath);
    }

    private static void hookAntiFraud(ClassLoader cl, String apkPath) {
        if (sFraudDone) return;
        if (apkPath == null) return;

        // 不需要额外的进程白名单校验：Xposed 的钩子**只在勾进作用域的进程里生效**，
        // 而作用域声明（res/values/arrays.xml 的 xposed_scope）只列了 securityserver。
        // 所以 awareness 那套围栏在这里被拦，YOYO / 情景智能自己的进程完全不受影响。

        int n = 0;
        int nCtl = 0;     // SafetyCtrlService（反诈云控校验）
        int nAware = 0;   // awareness 发送入口
        int nRecv = 0;    // 云参数接收器
        String[][] anchors = {
                {"antifraud", "antifraud detect state switch"},       // 开关/观察者注册
                {"fraud_cloud", "fraud_cloud_security_check"},        // 云控校验
        };
        for (String[] pair : anchors) {
            final String tag = pair[0];
            try {
                java.util.List<java.lang.reflect.Method> ms =
                        DexKitHelper.findMethodsByStrings(apkPath, cl, pair[1]);
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
    }
    private static int sCloudBlocked = 0;

    /**
     * 屏蔽**性能/温控策略的云控下发**（v3.2.0）。
     *
     * <p>来源：反编译 HAware 时挖到的
     * {@code com.hihonor.powergenie.integration.eventhub.bcreceiver.ThermalCfgCotaUpdateReceiver}
     * —— "Cota" 就是云侧配置（Cloud OTA Config）更新。系统收到云端的温控策略包后，
     * 这个 Receiver 负责把它落进本地策略库，之后 PowerGenie 就按云端那套降频/限帧。
     *
     * <p>阻断点选在 **onReceive**：直接 setResult 掉，等于"收到了但不当回事"，
     * 本地策略保持出厂状态。比去改配置库安全得多（不碰数据，不会写坏）。
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
