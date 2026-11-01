package io.github.voreulch.honoradvanced;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

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
                            if (!Cfg.hottempOn()) return;
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
        n += hookByString(apkPath, cl, "fps", "setScheduleFPS");
        n += hookByString(apkPath, cl, "quality", "Game picture quality change");
        n += hookByString(apkPath, cl, "brightness-off", "Disable local brightness");
        n += hookByString(apkPath, cl, "brightness-exit", "exit local brightness");
        // ⚠ 充电分离**只观察不阻断**（v3.1.0 第 5 遍复查结论）。
        // 它不是"性能限制"，而是**电池保护**：游戏时绕过电池、由充电器直接供电，
        // 避免边充边放导致电池发热。阻断它 = 电池边充边放 = 机身更热
        // = 反而更早触发温控降频。挡它属于帮倒忙。
        n += hookByString(apkPath, cl, "charge-sep(observe)", "charge separation switch", false);
        n += hookByString(apkPath, cl, "fps-report", "sendFpsResult");
        // checkFpsAsync：PanguResGovernor 里的**异步帧率检查**线程。
        // 只挡上报（sendFpsResult）不够 —— 检查逻辑本身仍会按实际帧率
        // 生成"需要限帧"的结论并下发。两个都要挡。
        n += hookByString(apkPath, cl, "fps-check", "checkFpsAsync");
        // 一个都没挂上（DexKit 还没就绪 / apk 路径不对）就别把 sExtraDone 锁死，
        // 留给下一次 handleLoadPackage 重试
        sExtraDone = n > 0;
        Diag.note("thermal-extra: 共挂载 " + n + " 处" + (n == 0 ? "（下次重试）" : ""));
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
                                if (!Cfg.hottempOn()) return;
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
    }

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
                            if (!Cfg.isOn(Cfg.K_NODUCK)) return;
                            if (isNotificationDuckRequest(expandArgs(param.args))) {
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
            Diag.mount("AudioManager.requestAudioFocus*", n, 0);
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
