package io.github.voreulch.honoradvanced;

import java.lang.ref.WeakReference;
import java.lang.reflect.Array;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;


import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Liquid Glass Restorer for MagicOS (LSPosed module).
 *
 * Root cause: after the OTA, TransparentModeManager caches mBlurType = FROSTED
 * at SystemUI init, the machine type fell to COMPAT, and the liquid material
 * bit was dropped from the style config — the control center renders glassType=1
 * (flat frosted) instead of 2 (liquid refraction + highlight).
 *
 * Strategy: AOT inlines small getters and bypasses method hooks, so we only
 * hook big methods that cannot be inlined (getTransparentModeState /
 * initBulrParams / constructors) and rewrite the FIELDS the renderers read.
 *
 * Tuning since v2.0.0: switches + DIY sliders come through Cfg (written by
 * SettingsActivity over CfgProvider; glass scopes freeze the snapshot for the
 * process lifetime). The baked DEFAULT_* constants ARE the approved recipe
 * (2026-10-09 calibrated, 2026-10-28 defaults re-tuned): radius .5 / mask
 * .65 / sat 1.2 / nmask .4 / refract 1.0 / edge 1.4 / rim 1.0 / tblur .6 /
 * thick 1.4 / disp .25; keyguard chain kbblur 2.0 / kveil .2 / kbbright .1 /
 * kedge 1.4; banner blur .5. NO system properties are read: the old
 * persist.sys.lgr.* tuning/debug namespace is retired. Full history:
 * lg/HANDOFF.md.
 *
 * Line gating: the glass master switch (glass) owns the system-level base
 * render (HIGH tier, LIQUID bits, texture/degree forcing); the system /
 * keyguard / gallery lines each add their own switch on top; glass_extreme
 * (DIY mode) swaps the fixed constants for the 14 percent sliders.
 *
 * Safety rules (learned the hard way):
 *   - No Class.forName(initialize=true) in handleLoadPackage — it ran the
 *     <clinit> of a system class during SystemUI incubation and boot-looped
 *     the phone (v2.3 incident).
 *   - No hardcoded resource IDs — an unverified ID flipped an unrelated
 *     boolean and randomly broke SystemUI.
 *   - Unverified hooks default OFF; enable only after reaching the desktop.
 *   - Every hook body is individually try/caught so one failure never
 *     crashes the host process.
 */
public final class GlassHook implements IXposedHookLoadPackage {

    // 注：以前这里有个 private static final String TAG = "HAS-Glass"，
    // 从没被用过（所有日志都走 Diag.*，带进程 scope 前缀），v2.6.10 删掉。

    // SystemUI apk classes (present only in com.android.systemui process)
    private static final String TMM_CLASS =
            "com.hihonor.systemui.magic.transparentmode.TransparentModeManager";
    private static final String BPC_CLASS =
            "com.hihonor.systemui.magic.blur.BlurParametersConfig";
    private static final String SHOTBLUR_CLASS =
            "com.android.magic.blur.ShotBlurView";
    // 锁屏大时钟的玻璃腐蚀表（classes2.dex）。getErode 返回的 float 会被
    // kge029.c 直接塞进 BlurParamEx.setSigma(...) 当腐蚀半径 —— 时钟玻璃
    // 有多"厚"就靠这一个值。
    private static final String KG_ERODE_CLASS =
            "com.hihonor.keyguardengine.clockplugin.blur.ClockErodeTable";
    // 锁屏小组件的宿主 View（classes2/classes4 都有引用）。
    // setChildBlur(View, boolean) 里的 view 就是小组件本体。
    private static final String KG_WIDGET_HOST_CLASS =
            "com.hihonor.keyguard.view.widget.KgAppWidgetHostView";
    // 锁屏壁纸/指纹的模糊参数构造器（返回 HnBlurParametersEx）
    private static final String KG_BLUR_UTILS_CLASS =
            "com.hihonor.keyguard.util.KeyguardBlurUtils";
    // 桌面卡片的玻璃入口（launcher 自己的类，cl 一定找得到）
    private static final String DESK_LIQUID_UTILS_CLASS =
            "com.hihonor.android.launcher.cardstyle.utils.LiquidGlassUtils";
    // uikit transparency framework (SystemUI + launcher)
    private static final String CFG_CLASS =
            "com.hihonor.uikit.transparency.configuration.TransparencyFeatureConfig";
    private static final String MACHINE_CLASS =
            "com.hihonor.uikit.transparency.configuration.MachineConfig";
    private static final String MACHINE_TYPE_CLASS =
            "com.hihonor.uikit.transparency.configuration.MachineType";
    private static final String STYLE_TYPE_CLASS =
            "com.hihonor.uikit.transparency.style.StyleType";
    private static final String UIMODE_WRAPPER_CLASS =
            "com.hihonor.uikit.hnmagicsystem.app.UiModeManagerEx";
    private static final String DEGREE_ALGO_CLASS =
            "com.hihonor.uikit.transparency.data.algorithm.degree.AbsHighGradeDegreeAlgorithm";
    private static final String COLOR_HELPER_CLASS =
            "com.hihonor.uikit.transparency.utils.ColorHelper";
    // framework classes (boot classpath)
    private static final String UIMODE_FW_CLASS =
            "com.hihonor.android.app.UiModeManagerEx";
    private static final String CONFIG_EX_CLASS =
            "com.hihonor.android.content.res.ConfigurationEx";

    private static final int BLUR_TYPE_MASK = 0xF0000;
    private static final int BLUR_TYPE_FROSTED = 0x10000;
    private static final int BLUR_TYPE_LIQUID = 0x20000;

    private static final String NBVM_CLASS =
            "com.android.magic.blur.NotificationBackgroundViewModel";
    // Baked-in values = the final calibrated recipe (2026-10-09/10, user
    // approved). No system properties are read: tuning = edit the constant
    // + rebuild.
    private static final int DEFAULT_DEGREE = 85;
    private static final float DEFAULT_RADIUS = 0.5f;
    private static final float DEFAULT_MASK = 0.65f;
    private static final float DEFAULT_SAT = 1.2f;
    private static final float DEFAULT_NTF_MASK = 0.4f;
    private static final float DEFAULT_REFRACT = 1.0f;
    private static final float DEFAULT_EDGE = 1.4f;
    private static final float DEFAULT_RIM = 1.0f;
    private static final float DEFAULT_TBLUR = 0.6f;

    private static final float DEFAULT_THICK = 1.4f;
    private static final float DEFAULT_DISP = 0.25f;
    private static final int DEFAULT_GSCENE = 16;
    private static final float DEFAULT_KBG = 1.0f;
    private static final float DEFAULT_KENG = 1.0f;
    private static final float DEFAULT_KICON = 1.0f;
    private static final float DEFAULT_KBBLUR = 2.0f;
    private static final float DEFAULT_KVEIL = 0.2f;
    private static final float DEFAULT_KBBRIGHT = 0.1f;
    private static final float DEFAULT_KEDGE = 1.4f;
    // heads-up banner (initBulrParams case=3): stock brightness is 0.0
    // with fully transparent ntf colors — the banner floats on the sharp
    // desktop and reads washed-out. Brightness is deliberately left to the
    // system (the stock value follows day/night mode and the heads-up text
    // auto-inverts against it; forcing a dark value made the banner dark and
    // the still-black text unreadable). Give the banner its own blur scale —
    // slightly softer than the tile pass so the banner doesn't feel thin,
    // without touching the finalized panel/tile look.
    private static final float DEFAULT_BANNER_BLUR = 0.5f;

    private static boolean sFlipLogged = false;
    private static boolean sTextureLogged = false;
    private static boolean sDegreeLogged = false;
    private static boolean sConfigLogged = false;
    private static int sStateCount = 0;
    private static int sRadiusCount = 0;
    private static int sMaskCount = 0;
    private static int sUikitCount = 0;
    private static int sPanelCount = 0;

    // look params — recomputed by refreshProps() from baked constants +
    // switch gates on every hook entry (pure memory reads, no caching needed)
    private static float sRadiusScale = DEFAULT_RADIUS;
    private static float sMaskScale = DEFAULT_MASK;
    private static float sSatScale = DEFAULT_SAT;
    private static float sNtfMask = DEFAULT_NTF_MASK;
    private static int sNtfCount = 0;
    private static float sRefractScale = DEFAULT_REFRACT;
    private static float sEdgeScale = DEFAULT_EDGE;
    private static int sEdgeCount = 0;
    private static int sRefractLogged = 0;
    private static float sThickScale = DEFAULT_THICK;
    private static float sDispScale = DEFAULT_DISP;
    private static float sRimScale = DEFAULT_RIM;
    private static int sRimCount = 0;
    private static boolean sFolderMode = false;
    private static int sFolderLogged = 0;
    private static boolean sBannerOn = false;
    // 三条线各自的实时开关状态：精细调节必须跟着走，否则把系统玻璃关掉后
    // 48 个滑条还在偷偷改参数 —— 那就不是"开关独立生效"了。
    private static boolean sSysOn = false;
    private static boolean sKgOn = false;
    private static int sBannerLogged = 0;
    private static float sTileBlurScale = DEFAULT_TBLUR;
    private static float sBannerBlur = DEFAULT_BANNER_BLUR;
    /**
     * initBulrParams 钩子是否已挂上。
     *
     * <p>为什么需要它：Vector/LSPosed 的注入时机和 SystemUI 的类加载存在竞态 ——
     * 本轮实测同一个 APK 连着重启两次 SystemUI，一次 `com.hihonor.systemui.
     * magic.blur.BlurParametersConfig` 找得到（精细调节全部生效、日志里有
     * `fine: scope=…`），另一次找不到（只剩 ShotBlurView 那条 panel 日志）。
     * 表现就是用户说的"同样设置，有时生效有时不生效"。
     *
     * <p>所以：findClass 失败时**不置位**，留待后续在 setBlurParams 回调里
     * （那里能拿到真实的 app ClassLoader）补挂一次。
     *
     * <p>⚠️ 必须严格保证只挂一次 —— hookAllMethods 挂两遍会让同一回调执行两次，
     * 精细调节的乘法会被叠乘（0.6 变 0.36），比"没挂上"更难排查。
     */
    private static final java.util.concurrent.atomic.AtomicBoolean sBpcHooked =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * ShotBlurView.setBlurParams 钩子是否已挂上（防止重复挂载导致参数叠乘）。
     * 详见 {@link #hookPanelVeil} 里的说明。
     */
    private static final java.util.concurrent.atomic.AtomicBoolean sPanelVeilHooked =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * TransparentModeManager 钩子是否已挂上。
     * 危险点：{@code getTransparentBlurRadius} 是"读返回值再乘 sRadiusScale"，
     * 挂几次就乘几次方。
     */
    private static final java.util.concurrent.atomic.AtomicBoolean sTmmHooked =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * EdgeLightParamEx 钩子是否已挂上。
     * 危险点：{@code setColor} 同样是"读原 alpha 再乘 rimK"，重复挂会让边缘光越拖越亮。
     */
    private static final java.util.concurrent.atomic.AtomicBoolean sRimHooked =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** 锁屏时钟腐蚀钩子（ClockErodeTable.getErode）是否已挂上。 */
    private static final java.util.concurrent.atomic.AtomicBoolean sKgClockHooked =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private static int sKgClockLogged = 0;
    /** 锁屏元素玻璃参数接管（applyBlur 正式版）是否已挂上。 */
    private static final java.util.concurrent.atomic.AtomicBoolean sKgElemHooked =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private static int sKgElemLogged = 0;
    private static int sKgOpticsLogged = 0;
    /** applyBlur 里 refreshProps 的节流时间戳（applyBlur 调用很密，不能每次都刷）。 */
    private static long sKgLastRefresh = 0L;
    /** 强制液态玻璃的日志计数。 */
    private static int sForceLiquidLogged = 0;
    /** 桌面卡片玻璃钩子是否已挂上。 */
    private static final java.util.concurrent.atomic.AtomicBoolean sDeskHooked =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private static int sDeskLogged = 0;
    /**
     * 已处理过的 {@code kge028.b} 参数包实例。
     *
     * <p>**为什么必须有这个**：{@code applyBlur(provider, state, bVar)} 的第三个参数
     * 是调用方传进来的。如果调用方**复用同一个实例**（先填值、再传给多个元素），
     * 我们每收到一次就乘一次，同一个 thickness 会被乘成 2×2×2… 指数爆炸；
     * 反过来如果每个元素都 new 一个，那每个都要处理。
     *
     * <p>用弱引用表兜住两种情况：同一个实例只处理一次；新实例照常处理，
     * 且对象被回收后条目自动消失，不会泄漏。
     */
    private static final java.util.Map<Object, Boolean> sKgBVars =
            java.util.Collections.synchronizedMap(
                    new java.util.WeakHashMap<Object, Boolean>());
    /** 锁屏小组件（KgAppWidgetHostView）是否已挂上。 */
    private static final java.util.concurrent.atomic.AtomicBoolean sKgWidgetHooked =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private static int sKgWidgetLogged = 0;
    /** 锁屏壁纸/指纹模糊参数（KeyguardBlurUtils）是否已挂上。 */
    private static final java.util.concurrent.atomic.AtomicBoolean sKgBlurParamsHooked =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private static int sKgBlurLogged = 0;
    /**
     * keyguard 延迟钩子（bg bitmap / icon fallback / panel veil）是否已装。
     * 用 AtomicBoolean 而不是 boolean：{@code initBulrParams} 的回调可能在
     * 多个线程上跑（不同控件的构造线程不同），普通 boolean 的"先查后写"不是原子操作，
     * 两条线程同时进来会重复挂 —— 而 bg/icon 那两个钩子会真的重复处理壁纸 bitmap。
     */
    private static final java.util.concurrent.atomic.AtomicBoolean sKgHooksInstalled =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private static boolean sFrostGateWarned;
    private static int sRecipeLogged = 0;
    private static int sTextInvertLogged = 0;
    private static boolean sKeyguardBg = false;
    private static boolean sKgEngineNotify = false;
    private static boolean sKgIconFallback = false;
    private static float sKgBlurRadius = 0.0f;
    private static float sKgVeilScale = 1.0f;
    private static float sKgBrightness = 0.1f;
    private static float sKgEdgeScale = 1.0f;
    // gscene = scene bitmask replacing the photo page toolbar's stock MATTE
    // (16384) scene, e.g. 16 Immersive (main-page look), 80 Immersive|
    // GradientBlur, 144 Immersive|UniformBlur. 0 = stock.
    private static int sGalleryScene = 0;
    // gbar = clear photo-page top/bottom bar backgrounds (the black strips).
    private static boolean sGalleryBar = false;
    private static int sGalSceneLogged = 0;
    // toolbars already planted with clearBg=true (WeakHashMap dedup) — plant
    // once per toolbar instance; later style passes only need the scene swap
    private static final java.util.WeakHashMap<View, Boolean> sGalPlanted =
            new java.util.WeakHashMap<>();
    private static int sGalBottomBarId = 0;
    private static int sGalTopBarId = 0;
    private static int sGalSplitBarId = 0;
    private static int sBtnSceneLogged = 0;
    private static int sKgShadeLogged = 0;
    // keyguard-card edge-light gate: initBulrParams(z=true, case=2) registers
    // the config here; turnOnViewBlur(view, config) then raises sKgCardNow for
    // the duration of the call so the EdgeLightParamEx constructor (built
    // synchronously inside turnOnViewBlur) can pick the keyguard-only scale.
    // volatile：由 turnOnViewBlur 的钩子（通知背景 ViewModel 的线程）写，
    // 由 EdgeLightParamEx 构造器的钩子（控件构造线程）读 —— 是真正的跨线程标志。
    private static volatile boolean sKgCardNow = false;
    // WeakHashMap 本身不是线程安全的：initBulrParams 里 add、turnOnViewBlur 里
    // contains，两张卡片可能在不同线程构建。包一层同步视图（只做 add/contains）。
    private static final Set<Object> sKgConfigs = Collections.synchronizedSet(
            Collections.newSetFromMap(new WeakHashMap<Object, Boolean>()));
    private static Bitmap sSynthBmp;
    private static WeakReference<Drawable> sSynthSrcRef;
    private static WeakReference<Bitmap> sStockBmpRef;
    private static int sSynthW;
    private static int sSynthH;
    private static float sSynthRadius = -1.0f;
    private static int sSynthVeilA = -1;
    private static int sSynthSatPct = -1;
    private static int sSynthGen;
    private static int sKgBgAppliedGen = -1;
    private static int sKgBgLogged = 0;
    private static int sKgBgServed = 0;
    // logMiss 会从位图合成、图标兜底等多条路径进来，可能是不同线程。
    private static final java.util.concurrent.ConcurrentHashMap<String, Integer> sMissCounts =
            new java.util.concurrent.ConcurrentHashMap<String, Integer>();
    private static int sKgIconLogged = 0;
    private static volatile Handler sKgHandler;
    // trackIconTarget 在锁屏布局时写、reapplyIconTargets 遍历重放：写少读多，
    // 用写时复制列表，遍历期间新增不会抛 ConcurrentModificationException。
    private static final java.util.List<IconTarget> sIconTargets =
            new java.util.concurrent.CopyOnWriteArrayList<IconTarget>();
    private static int sTileBlurLogged = 0;
    // 精细调节的"实际改了什么"日志：每进程只打前 6 次，避免刷屏。
    // 没有这条日志时，用户报"滑条拖了没反应"根本无法定位是
    // ①值没读到 ②scope 判错 ③字段名不存在 ④开关把整条线关了。
    private static int sFineLogged = 0;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        ClassLoader cl = lpparam.classLoader;
        // 进程横幅由 MainHook.enter() 统一打（带真实版本号）；这里早先硬编码了
        // "glass v2.2.9"，每次发版都会变成过期信息，删掉只留一处。
        //
        // v2.6.9: 但实测那个横幅**到不了 logcat**（走的是另一条通道），于是排查时
        // 永远在猜"手机上跑的是哪一版代码、GlassHook 到底注入了没"。这里用 note
        // （不受诊断开关控制）补一行，每进程固定一条，grep `glass v` 就能确认。
        try {
            Diag.note("glass v" + BuildConfig.VERSION_NAME
                    + "(" + BuildConfig.VERSION_CODE + ") 注入 " + lpparam.packageName);
        } catch (Throwable ignored) {
        }

        hookTransparentMode(cl);
        hookBlurParams(cl);
        hookPanelVeil(cl);
        hookKgClockBlur(cl);
        hookKgElements(cl);
        hookKgWidget(cl);
        hookKgBlurParams(cl);
        hookEdgeRim(cl);
        hookDegreeAlgorithm(cl);
        hookConfigEx(cl);
        hookFwDataSource(cl);
        hookMaterialBits(cl);
        hookMachineConfig(cl);
        hookStyleType(cl);
        if ("com.android.systemui".equals(lpparam.packageName)) {
            // keyguard app hooks are deferred to the BlurParametersConfig
            // initBulrParams callback: the OTA left lpparam.classLoader with
            // framework classes only, while that callback exposes the real
            // app class loader（kg-deferred 块里用局部变量接住后立刻用掉）。
            hookNtfCardGate(cl);
            hookTextColor();
        }
        if ("com.hihonor.android.launcher".equals(lpparam.packageName)) {
            // 桌面是独立进程，只挂它自己那套玻璃参数
            hookLauncherGlass(cl);
        }
        if ("com.hihonor.photos".equals(lpparam.packageName)) {
            hookGalleryScene(cl);
            hookGalleryButtons(cl);
        }
    }

    /**
     * v2.5.23: real photo-page structure (from uiautomator on the live page):
     * photo_browser_top_view / photo_browser_bottom_view are plain
     * FrameLayouts whose XML background is color/photo_browser_toolbar_
     * background (#f2000000 at night) — the source of the solid black bars —
     * and the bottom one hosts android:id/split_action_bar with five
     * android.widget.Button children (share/favorite/AI-edit/info/delete).
     * gbar nulls both bar backgrounds so the photo shows through.
     * v2.5.47: clear EVERY instance of these ids, not just the first. The
     * album path stacks a second photo_browse_root in the window (the first
     * one stays attached underneath); findViewById returns the stale one, so
     * the freshly-opened browser kept its white XML/stock backgrounds — the
     * album-path white-bars bug. Walk the whole tree and null them all.
     */
    private static void clearGalleryBars(View root) {
        if (root == null) {
            return;
        }
        if (sGalTopBarId == 0) {
            android.content.res.Resources res = root.getResources();
            sGalTopBarId = res.getIdentifier(
                    "photo_browser_top_view", "id", "com.hihonor.photos");
            sGalBottomBarId = res.getIdentifier(
                    "photo_browser_bottom_view", "id", "com.hihonor.photos");
            if (sGalSplitBarId == 0) {
                sGalSplitBarId = res.getIdentifier(
                        "split_action_bar", "id", "android");
            }
        }
        clearAllById(root, sGalTopBarId);
        clearAllById(root, sGalBottomBarId);
        clearAllById(root, sGalSplitBarId);
    }

    private static void clearAllById(View v, int id) {
        if (id == 0) {
            return;
        }
        if (v.getId() == id && v.getBackground() != null) {
            v.setBackground(null);
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                clearAllById(g.getChildAt(i), id);
            }
        }
    }

    /**
     * The photo page toolbar is the ONLY surface in the whole gallery styled
     * with SceneType.MATTE (frosted) — every other page uses Immersive, which
     * renders the liquid-glass treatment. Stock
     * PhotoBrowserBarManager.setToolbarStyle routes through
     * ToolbarStyleHelper.setToolbarViewStyleWithScenes(toolbar, style, MATTE).
     * v2.5.28: patch BEFORE the stock call instead of after. An after-the-fact
     * re-issue let MATTE land first (white flash on open) and its effect-strip
     * branch left the bar bare on re-open. The before-hook re-plants the
     * clearBackground=true TransparencyBundle (stock's super plants false,
     * which keeps dark LayerDrawables) and swaps the scenes arg to gscene, so
     * the stock chain itself natively builds the immersive liquid pipeline —
     * identical code path on every open. MATTE is photo-page only (all other
     * helper callers pass Immersive), so the 0x4000 bit check scopes the swap.
     * MaterialManager.setTransparencyBundle is a varargs method — compiled
     * signature (Bundle, boolean, View[]) — resolve it up front and invoke
     * with an explicit View[] (v2.5.26 lesson).
     */
    private static void hookGalleryScene(ClassLoader cl) {
        try {
            final Class<?> helper = XposedHelpers.findClass(
                    "com.hihonor.uikit.hntranstoolbar.helper.ToolbarStyleHelper", cl);
            final Class<?> materialMgr = XposedHelpers.findClass(
                    "com.hihonor.uikit.transparency.manager.MaterialManager", cl);
            final java.lang.reflect.Method setBundle = XposedHelpers.findMethodExact(
                    materialMgr, "setTransparencyBundle",
                    android.os.Bundle.class, boolean.class, android.view.View[].class);
            // v2.5.34: the 15s setToolbarStyle short-circuit (v2.5.31) killed
            // the stock open/pull-down transition — the photo page snapped
            // straight to the final transparent state. Let the stock chain run
            // every time so the anim-factor style call plays its transition.
            XposedBridge.hookAllMethods(helper, "setToolbarViewStyleWithScenes", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (!galOn()) return;
                        refreshProps();
                        if (sGalleryScene == 0 || param.args.length < 3
                                || !(param.args[0] instanceof android.view.View)
                                || !(param.args[2] instanceof Integer)) {
                            return;
                        }
                        int scenes = (Integer) param.args[2];
                        boolean matte = (scenes & 0x4000) != 0;
                        android.view.View tb0 = (android.view.View) param.args[0];
                        if (!matte) {
                            return;
                        }
                        if (sGalPlanted.put(tb0, Boolean.TRUE) == null) {
                            android.os.Bundle bundle = new android.os.Bundle();
                            bundle.putBoolean("setClearBackground", true);
                            setBundle.invoke(null, bundle, true,
                                    new android.view.View[]{tb0});
                        }
                        param.args[2] = sGalleryScene;
                        // v2.5.29: the bars' XML white backgrounds paint for
                        // ~0.5s on cold launch before the delayed gbar pass —
                        // clear them here, before the stock styling and the
                        // first frame
                        if (sGalleryBar) {
                            clearGalleryBars((View) tb0.getRootView());
                        }
                        if (sGalSceneLogged < 6) {
                            sGalSceneLogged++;
                            log("gscene: swapped " + scenes + " -> " + sGalleryScene);
                        }
                    } catch (Throwable t) {
                        log("gscene swap failed: " + t);
                    }
                }
            });
            // v2.5.26/30: stock's setToolbarStyle plants clearBg=false on the
            // HwToolbar AFTER the style call (and again on gesture re-runs);
            // HnMaterialOpt reads that flag and keeps the dark LayerDrawable.
            // Flip the value in place so the stock chain itself renders the
            // liquid pipeline. DetailActionMode / PhotoPreviewSelectMode plant
            // their false bundles on action items, not HwToolbar, so they stay
            // stock.
            final Class<?> hwToolbarCls = XposedHelpers.findClass(
                    "com.hihonor.android.widget.HwToolbar", cl);
            XposedBridge.hookAllMethods(materialMgr, "setTransparencyBundle", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (!galOn()) return;
                        refreshProps();
                        if (sGalleryScene == 0 || param.args.length < 3
                                || !(param.args[0] instanceof android.os.Bundle)
                                || !(param.args[2] instanceof android.view.View[])) {
                            return;
                        }
                        android.os.Bundle b = (android.os.Bundle) param.args[0];
                        if (!b.containsKey("setClearBackground")
                                || b.getBoolean("setClearBackground")) {
                            return;
                        }
                        for (android.view.View v : (android.view.View[]) param.args[2]) {
                            if (hwToolbarCls.isInstance(v)) {
                                b.putBoolean("setClearBackground", true);
                                if (sGalSceneLogged < 6) {
                                    sGalSceneLogged++;
                                    log("gscene: flipped stock clearBg bundle to true");
                                }
                                return;
                            }
                        }
                    } catch (Throwable t) {
                        log("gscene flip failed: " + t);
                    }
                }
            });
            log("ok: gallery photo-page scene override installed (gscene, default off)");
        } catch (Throwable t) {
            log("skip: gallery scene hooks (" + t.getClass().getSimpleName() + ")");
        }
    }

    /**
     * v2.5.38: the photo page's top-bar buttons (HnBlurRoundButton — back,
     * menu, plugin buttons) init liquid (Immersive 0x10), but ~8ms after the
     * page opens the gallery re-styles every one of them with the MATTE
     * scene (0x4000), and AbsViewStyle strips BLUR/LIQUID/HIGH_LIGHT for
     * MATTE — that reset is the frosted look. Swap the scene arg to gscene
     * (same bitmask the toolbar swap uses) so the stock chain keeps the
     * liquid pipeline. Gated on gscene for a stock escape hatch.
     * v2.5.39: the open-time reset goes through the button's own
     * setViewStyleWithScenes (hook below), but the pull-down-exit gesture
     * re-frosts through other paths. Scene bits land on an AbsViewStyle in
     * exactly two places — the constructor (initScenes) and
     * setScenes(StyleType,int) (re-style of an existing style, e.g. via
     * ViewStyleMediator reuse or ToolbarTransparentStyle's own mediator).
     * Guard both funnels for HnBlurRoundButton targets: strip the MATTE bit
     * and OR in gscene, keeping every other scene bit (Dark etc.) intact.
     */
    private static void hookGalleryButtons(ClassLoader cl) {
        try {
            final Class<?> btn = XposedHelpers.findClass(
                    "com.hihonor.uikit.hnblurroundbutton.HnBlurRoundButton", cl);
            XposedBridge.hookAllMethods(btn, "setViewStyleWithScenes", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (!galOn()) return;
                        refreshProps();
                        if (sGalleryScene == 0 || param.args.length < 2
                                || !(param.args[1] instanceof Integer)) {
                            return;
                        }
                        int scenes = (Integer) param.args[1];
                        if ((scenes & 0x4000) == 0) {
                            return;
                        }
                        param.args[1] = sGalleryScene;
                        logBtnSwap("entry", scenes);
                    } catch (Throwable t) {
                        log("btn swap failed: " + t);
                    }
                }
            });
            final Class<?> avs = XposedHelpers.findClass(
                    "com.hihonor.uikit.transparency.style.AbsViewStyle", cl);
            // funnel 1: constructor — signature (View, StyleType, StyleConfig,
            // int scenes); scenes is arg 3 (verified via v2.5.37 diag B)
            XposedBridge.hookAllConstructors(avs, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (!galOn()) return;
                        refreshProps();
                        if (sGalleryScene == 0 || param.args.length < 4
                                || !btn.isInstance(param.args[0])
                                || !(param.args[3] instanceof Integer)) {
                            return;
                        }
                        int scenes = (Integer) param.args[3];
                        if ((scenes & 0x4000) == 0) {
                            return;
                        }
                        param.args[3] = (scenes & ~0x4000) | sGalleryScene;
                        logBtnSwap("ctor", scenes);
                    } catch (Throwable t) {
                        log("btn ctor swap failed: " + t);
                    }
                }
            });
            // funnel 2: setScenes(StyleType, int) — runtime re-style of an
            // already-built style
            XposedBridge.hookAllMethods(avs, "setScenes", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (!galOn()) return;
                        refreshProps();
                        if (sGalleryScene == 0 || param.args.length < 2
                                || !(param.args[1] instanceof Integer)) {
                            return;
                        }
                        Object target;
                        try {
                            target = XposedHelpers.getObjectField(param.thisObject,
                                    "targetView");
                        } catch (Throwable t) {
                            return;
                        }
                        if (!btn.isInstance(target)) {
                            return;
                        }
                        int scenes = (Integer) param.args[1];
                        if ((scenes & 0x4000) == 0) {
                            return;
                        }
                        param.args[1] = (scenes & ~0x4000) | sGalleryScene;
                        logBtnSwap("scenes", scenes);
                    } catch (Throwable t) {
                        log("btn scenes swap failed: " + t);
                    }
                }
            });
            log("ok: gallery button scene override installed (entry+ctor+setScenes)");
        } catch (Throwable t) {
            log("skip: gallery button scene hooks (" + t.getClass().getSimpleName() + ")");
        }
    }

    /**
     * 锁屏大时钟的玻璃腐蚀强度（v2.6.12）。
     *
     * <p>链路（溯源结论，之前一直以为锁屏时钟调不了是找错了入口）：
     * <pre>
     *   kge029.c:
     *     blurParamEx.setSigma(new ClockErodeTable().getErode(
     *         时钟样式, 字号, 单条/双条 ? 2 : 1, 字体));
     * </pre>
     * {@code getErode} 按「样式+字号+双条+字体」查表返回 0.45~2.49 的腐蚀半径，
     * 用户完全没法调。这里改它的返回值。
     *
     * <p>为什么不是 hook {@code BlurParamEx.setSigma}：那个方法控制中心和桌面卡片
     * 也在用，改了会连坐；getErode 只在时钟这条链上出现，精确得多。
     */
    private static void hookKgClockBlur(ClassLoader cl) {
        if (sKgClockHooked.get()) return;
        Class<?> et;
        try {
            et = XposedHelpers.findClass(KG_ERODE_CLASS, cl);
        } catch (Throwable t) {
            Diag.note("skip: ClockErodeTable missing (" + t.getClass().getSimpleName() + ")");
            return;   // 不置位，留待 kg-deferred 用 app CL 重试
        }
        if (!sKgClockHooked.compareAndSet(false, true)) return;
        try {
            XposedBridge.hookAllMethods(et, "getErode", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!sKgOn) return;
                    Object r = param.getResult();
                    if (!(r instanceof Float)) return;
                    float k = fineF(Cfg.F_KG_ERODE);
                    if (Math.abs(k - 1f) <= 0.001f) return;
                    float v = (Float) r;
                    // 这个值是 sigma（腐蚀半径，像素级）。乘过头画面会糊成一坨，
                    // 所以留个 0.05 的地板（0 会让腐蚀消失、时钟变硬边）。
                    float nv = Math.max(0.05f, v * k);
                    param.setResult(Float.valueOf(nv));
                    if (sKgClockLogged < 4) {
                        sKgClockLogged++;
                        log("kg-clock: 时钟腐蚀 " + v + " -> " + nv + " (x" + k + ")");
                    }
                }
            });
            Diag.note("ok: 锁屏时钟腐蚀钩子已挂上（ClockErodeTable.getErode）");
        } catch (Throwable t) {
            sKgClockHooked.set(false);   // 允许下次重试
            Diag.note("skip: ClockErodeTable hook failed (" + t.getClass().getSimpleName() + ")");
        }
    }

    /**
     * 锁屏按钮 / 图标 / 文字的玻璃浓度（v2.6.14）。
     *
     * <p>链路：所有锁屏元素的玻璃参数都过
     * {@code IBlurStrategy.applyBlur(IViewDataProvider, State, kge028.b)}，
     * 第三个参数是通用渲染参数包。按 {@code provider.getViewType().getFirst()}
     * 的字符串分三组，分别乘 blurRadius / saturation / brightness。
     *
     * <p>⚠️ 类名 {@code kge029.c} / {@code kge029.d} 是 R8 混淆名，OTA 后会变。
     * 找不到就静默跳过 —— 宁可不生效，也不要因为硬编码类名把整个模块拖崩。
     */
    private static void hookKgElements(ClassLoader cl) {
        if (!sKgElemHooked.compareAndSet(false, true)) return;
        String[] targets = {"kge029.c", "kge029.d"};
        int ok = 0;
        for (String name : targets) {
            try {
                Class<?> k = XposedHelpers.findClass(name, cl);
                XposedBridge.hookAllMethods(k, "applyBlur", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        // ⚠ 必须自己 refreshProps：sKgOn 是在 refreshProps() 里赋值的，
                        // 而锁屏底部按钮的 View 往往在别的钩子触发之前就构造完了。
                        // v2.6.17 实测：不调这一句，按钮组永远不会被处理。
                        // 但要节流 —— applyBlur 调用很密，每次都刷会把 Diag 的
                        // 每秒 50 条配额吃光，连自己的 kg-opt 日志都打不出来。
                        if (!Cfg.isOn(Cfg.K_GLASS)) return;
                        long now = android.os.SystemClock.uptimeMillis();
                        if (now - sKgLastRefresh > 1000L) {
                            sKgLastRefresh = now;
                            refreshProps();
                        }
                        if (!sKgOn) return;
                        if (param.args.length < 3) return;
                        try {
                            Object provider = param.args[0];
                            Object pair = XposedHelpers.callMethod(provider, "getViewType");
                            Object first = XposedHelpers.callMethod(pair, "getFirst");
                            String vt = first == null ? null : first.toString();
                            Object bVar = param.args[2];
                            String[] row = kgRowFor(vt);
                            if (row == null) return;

                            // 同一个参数包实例只处理一次 —— 否则调用方复用实例时
                            // 会被我们反复乘成指数（见 sKgBVars 的注释）。
                            if (sKgBVars.put(bVar, Boolean.TRUE) != null) return;

                            // ① 本组的玻璃浓度（模糊半径 / 饱和 / 亮度）
                            float k = fineF(row[1]);
                            if (Math.abs(k - 1f) > 0.001f) {
                                scaleKgField(bVar, "f501a", k);
                                scaleKgField(bVar, "b", k);
                                scaleKgField(bVar, "f502c", k);
                                if (sKgElemLogged < 12) {
                                    sKgElemLogged++;
                                    log("kg-elem: " + vt + " 浓度 x" + k);
                                }
                            }

                            // ② 本组独立的光学四项（深度 / 色散 / 厚度 / 边缘光）
                            applyKgOptics(bVar, row, vt);
                        } catch (Throwable ignored) {
                        }
                    }
                });
                ok++;
                Diag.note("ok: 锁屏元素玻璃参数已接管 " + name);
            } catch (Throwable t) {
                Diag.note("skip: " + name + " missing (" + t.getClass().getSimpleName() + ")");
            }
        }
        if (ok == 0) sKgElemHooked.set(false);   // 一个都没挂上，允许下次重试
    }

    /** 参数包里的字段是 Float 对象，乘完写回。字段不存在就静默跳过。 */
    private static void scaleKgField(Object bVar, String field, float k) {
        try {
            Object cur = XposedHelpers.getObjectField(bVar, field);
            if (!(cur instanceof Float)) return;
            XposedHelpers.setObjectField(bVar, field,
                    Float.valueOf(((Float) cur) * k));
        } catch (Throwable ignored) {
        }
    }

    /**
     * 本组的玻璃光学四项（v2.6.17）：深度 / 色散 / 厚度 / 边缘光强度。
     *
     * <p>对应 {@code kge028.b} 的 {@code k / l / m / p}，会被 {@code kge029.c}
     * 原样塞进 {@code GlassParamEx} 和 {@code EdgeLightParamEx}。
     * **三组各自独立** —— 按钮组调厚度不会影响图标和文字。
     */
    private static void applyKgOptics(Object bVar, String[] row, String vt) {
        float kd = fineF(row[2]);
        float ki = fineF(row[3]);
        float kt = fineF(row[4]);
        float ke = fineF(row[5]);
        boolean d = Math.abs(kd - 1f) > 0.001f;
        boolean i = Math.abs(ki - 1f) > 0.001f;
        boolean t = Math.abs(kt - 1f) > 0.001f;
        boolean e = Math.abs(ke - 1f) > 0.001f;
        if (!d && !i && !t && !e) return;
        if (d) scaleKgField(bVar, "k", kd);       // depth
        if (i) scaleKgField(bVar, "l", ki);       // dispersion
        // 厚度特殊处理：把改前改后都记下来，用来确认"没有反复乘"
        // （0.62 -> 1.24 是对的；如果下一次变成 2.48 就说明实例被复用了）
        Object thkBefore = null;
        if (t) {
            try {
                thkBefore = XposedHelpers.getObjectField(bVar, "m");
            } catch (Throwable ignored) {
            }
            scaleKgField(bVar, "m", kt);
        }
        if (e) scaleKgEdgeColor(bVar, "p", ke);   // 边缘光 alpha
        if (sKgOpticsLogged < 10) {
            sKgOpticsLogged++;
            String thk = "";
            if (t && thkBefore instanceof Float) {
                Object after = null;
                try {
                    after = XposedHelpers.getObjectField(bVar, "m");
                } catch (Throwable ignored) {
                }
                thk = " thickness " + thkBefore + "->" + after;
            }
            log("kg-opt: " + vt + " depth x" + kd + " disp x" + ki
                    + " thick x" + kt + " edge x" + ke + thk);
        }
    }

    /** 边缘光颜色是 int（ARGB），只缩放 alpha 通道，颜色本身不动。 */
    private static void scaleKgEdgeColor(Object bVar, String field, float k) {
        try {
            int c = XposedHelpers.getIntField(bVar, field);
            int a = (c >>> 24) & 0xFF;
            if (a == 0) return;   // 原厂就是全透明，乘多少还是 0
            int na = Math.max(0, Math.min(255, Math.round(a * k)));
            XposedHelpers.setIntField(bVar, field, (na << 24) | (c & 0xFFFFFF));
        } catch (Throwable ignored) {
        }
    }

    /**
     * 锁屏小组件（天气卡 / 闹钟 / 步数那种）的透明度（v2.6.15）。
     *
     * <p>它**不走** applyBlur 那条链，走的是：
     * <pre>
     *   AppWidgetHelper.updateAppWidgetColorAndBlur(list, color, blur)
     *     -> KgAppWidgetHostView.setChildBlur(View view, boolean z)
     *        -> HnVisualEffectable.setTransparentStatus(open, degree, liquidMode)
     * </pre>
     * {@code degree} 是真正的透明度，但它在 {@code HnVisualEffectable} 接口上
     * （实现类不在反编译范围），只能退一步：拦 {@code setChildBlur} 的出口，
     * 系统把参数都设完之后，给那个 View 直接设最终 alpha。
     *
     * <p>代价写清楚：**整块小组件（含里面的文字）一起变透**，没法只调玻璃底。
     * 语义是"相对系统默认" —— 100% 完全不动，50% 半透，200% 不透明。
     */
    private static void hookKgWidget(ClassLoader cl) {
        if (!sKgWidgetHooked.compareAndSet(false, true)) return;
        try {
            Class<?> k = XposedHelpers.findClass(KG_WIDGET_HOST_CLASS, cl);
            XposedBridge.hookAllMethods(k, "setChildBlur", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!sKgOn) return;
                    if (param.args.length < 1 || !(param.args[0] instanceof android.view.View)) {
                        return;
                    }
                    float kk = fineF(Cfg.F_KG_WIDGET);
                    if (Math.abs(kk - 1f) <= 0.001f) return;
                    android.view.View v = (android.view.View) param.args[0];
                    v.setAlpha(Math.max(0f, Math.min(1f, kk)));
                    if (sKgWidgetLogged < 8) {
                        sKgWidgetLogged++;
                        log("kg-widget: alpha " + Math.max(0f, Math.min(1f, kk))
                                + " -> " + v.getClass().getSimpleName());
                    }
                }
            });
            Diag.note("ok: 锁屏小组件钩子已挂上（KgAppWidgetHostView.setChildBlur）");
        } catch (Throwable t) {
            sKgWidgetHooked.set(false);   // 允许下次重试
            Diag.note("skip: KgAppWidgetHostView missing (" + t.getClass().getSimpleName() + ")");
        }
    }

    /**
     * 锁屏壁纸玻璃 / 指纹（v2.6.16）。
     *
     * <p>两个方法都返回 {@code HnBlurParametersEx}，字段是 public 的：
     * {@code radiusX / radiusY / saturation / scaleX / scaleY / maskColor /
     * brightnessParams / multiMaskColors / edgeTreatment / gradientFactor}。
     * 我们只动 radiusX/Y 和 saturation（纯 float，改起来最安全）。
     *
     * <p>原厂公式（都是写死的算术）：
     * <pre>
     *   getHnBlurParametersEx:  radius = (通透度 × -23)/100 + 32 ; saturation = 1.3 固定
     *   getFPHnBlurParameters:  saturation = 通透度&lt;40 ? 1.3 : 1.2
     * </pre>
     * 也就是说「锁屏壁纸糊多厉害」跟通透度绑死、「饱和」干脆是常数 —— 用户没法调。
     */
    private static void hookKgBlurParams(ClassLoader cl) {
        if (!sKgBlurParamsHooked.compareAndSet(false, true)) return;
        try {
            Class<?> k = XposedHelpers.findClass(KG_BLUR_UTILS_CLASS, cl);
            int ok = 0;

            try {
                XposedBridge.hookAllMethods(k, "getHnBlurParametersEx", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!sKgOn) return;
                        Object p = param.getResult();
                        if (p == null) return;
                        float kr = fineF(Cfg.F_KG_BG_RADIUS);
                        float ks = fineF(Cfg.F_KG_BG_SAT);
                        boolean dr = Math.abs(kr - 1f) > 0.001f;
                        boolean ds = Math.abs(ks - 1f) > 0.001f;
                        if (!dr && !ds) return;
                        try {
                            if (dr) {
                                float r = XposedHelpers.getFloatField(p, "radiusX");
                                // 半径是像素级，太低会没有模糊，给个 0.5 地板
                                float nr = Math.max(0.5f, r * kr);
                                XposedHelpers.setFloatField(p, "radiusX", nr);
                                XposedHelpers.setFloatField(p, "radiusY", nr);
                            }
                            if (ds) {
                                float s = XposedHelpers.getFloatField(p, "saturation");
                                XposedHelpers.setFloatField(p, "saturation", Math.max(0f, s * ks));
                            }
                            if (sKgBlurLogged < 6) {
                                sKgBlurLogged++;
                                log("kg-bg: 锁屏壁纸 blur x" + kr + " sat x" + ks);
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                });
                ok++;
            } catch (Throwable t) {
                Diag.note("skip: getHnBlurParametersEx (" + t.getClass().getSimpleName() + ")");
            }

            try {
                XposedBridge.hookAllMethods(k, "getFPHnBlurParameters", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!sKgOn) return;
                        Object p = param.getResult();
                        if (p == null) return;
                        float ks = fineF(Cfg.F_KG_FP_SAT);
                        if (Math.abs(ks - 1f) <= 0.001f) return;
                        try {
                            float s = XposedHelpers.getFloatField(p, "saturation");
                            XposedHelpers.setFloatField(p, "saturation", Math.max(0f, s * ks));
                            if (sKgBlurLogged < 8) {
                                sKgBlurLogged++;
                                log("kg-bg: 指纹 sat x" + ks);
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                });
                ok++;
            } catch (Throwable t) {
                Diag.note("skip: getFPHnBlurParameters (" + t.getClass().getSimpleName() + ")");
            }

            if (ok > 0) {
                Diag.note("ok: 锁屏壁纸/指纹模糊参数已接管（" + ok + " 个）");
            } else {
                sKgBlurParamsHooked.set(false);
            }
        } catch (Throwable t) {
            sKgBlurParamsHooked.set(false);
            Diag.note("skip: KeyguardBlurUtils missing (" + t.getClass().getSimpleName() + ")");
        }
    }

    /**
     * 三组的分组表：{组名, 浓度键, 深度, 色散, 厚度, 边缘光}。
     * viewType 的实测值见 Cfg 里 F_KG_* 的注释。
     */
    private static final String[][] KG_ROWS = {
            {"btn", Cfg.F_KG_BTN, Cfg.F_KG_BTN_DEP, Cfg.F_KG_BTN_DIS,
                    Cfg.F_KG_BTN_THK, Cfg.F_KG_BTN_EDGE},
            {"icon", Cfg.F_KG_ICON, Cfg.F_KG_ICON_DEP, Cfg.F_KG_ICON_DIS,
                    Cfg.F_KG_ICON_THK, Cfg.F_KG_ICON_EDGE},
            {"txt", Cfg.F_KG_TXT, Cfg.F_KG_TXT_DEP, Cfg.F_KG_TXT_DIS,
                    Cfg.F_KG_TXT_THK, Cfg.F_KG_TXT_EDGE},
    };

    /**
     * 桌面（launcher）的卡片玻璃（v2.6.19）。
     *
     * <p>踩过的坑：一开始想 hook framework 里的
     * {@code HnLiquidGlassParamsEx$BlurParamEx.setRadius}，结果在 launcher 进程里
     * **findClass 直接失败**（`HnLiquidGlassParamsEx missing`）——那是个 framework 类，
     * launcher 的 PathClassLoader 拿不到。
     *
     * <p>改成 hook launcher 自己的入口：
     * <pre>
     *   LiquidGlassUtils.setLiquidGlassParams(View, TransparentBlurParams, Object shape)
     * </pre>
     * 第二个参数是 {@code TransparentConfigurator$TransparentBlurParams}（Kotlin data class，
     * 有 getter/setter）：{@code blurRadius(int) / saturation(float) / brightness(float) /
     * maskColor(int)}。在 before 里改它，后面 {@code setRadius(p.getBlurRadius())} 读到的
     * 就是我们的值。
     *
     * <p>注意桌面这条链 {@code setGlassParamEx(null)}、{@code setHighLightParamEx(null)} ——
     * 系统根本没给桌面卡片配折射和高光，所以只能调模糊/饱和/亮度。
     */
    private static void hookLauncherGlass(ClassLoader cl) {
        if (!sDeskHooked.compareAndSet(false, true)) return;
        try {
            Class<?> lg = XposedHelpers.findClass(DESK_LIQUID_UTILS_CLASS, cl);
            XposedBridge.hookAllMethods(lg, "setLiquidGlassParams", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    // 开关门控：总开关 + 桌面开关，两个都开才动
                    if (!Cfg.isOn(Cfg.K_GLASS) || !Cfg.isOn(Cfg.K_DESK)) return;
                    if (param.args.length < 2 || param.args[1] == null) return;
                    Object bp = param.args[1];
                    try {
                        float kr = fineF(Cfg.F_DESK_RADIUS);
                        if (Math.abs(kr - 1f) > 0.001f) {
                            Object cur = XposedHelpers.callMethod(bp, "getBlurRadius");
                            if (cur instanceof Integer) {
                                int nv = Math.max(1, Math.round(((Integer) cur) * kr));
                                XposedHelpers.callMethod(bp, "setBlurRadius", nv);
                                if (sDeskLogged < 8) {
                                    sDeskLogged++;
                                    log("desk: 卡片模糊 " + cur + " -> " + nv + " (x" + kr + ")");
                                }
                            }
                        }
                        float ks = fineF(Cfg.F_DESK_SAT);
                        if (Math.abs(ks - 1f) > 0.001f) {
                            Object cur = XposedHelpers.callMethod(bp, "getSaturation");
                            if (cur instanceof Float) {
                                XposedHelpers.callMethod(bp, "setSaturation",
                                        Math.max(0f, ((Float) cur) * ks));
                                if (sDeskLogged < 10) {
                                    sDeskLogged++;
                                    log("desk: 卡片饱和 x" + ks);
                                }
                            }
                        }
                        float kb = fineF(Cfg.F_DESK_BRIGHT);
                        if (Math.abs(kb - 1f) > 0.001f) {
                            Object cur = XposedHelpers.callMethod(bp, "getBrightness");
                            if (cur instanceof Float) {
                                XposedHelpers.callMethod(bp, "setBrightness", ((Float) cur) * kb);
                                if (sDeskLogged < 12) {
                                    sDeskLogged++;
                                    log("desk: 卡片亮度 x" + kb);
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
            Diag.note("ok: 桌面卡片玻璃已接管（setLiquidGlassParams）");
        } catch (Throwable t) {
            sDeskHooked.set(false);
            Diag.note("skip: LiquidGlassUtils missing (" + t.getClass().getSimpleName() + ")");
        }
    }

    /** viewType 字符串 → 本组那一行参数键；不认识就返回 null（完全不动它）。 */
    private static String[] kgRowFor(String vt) {
        if (vt == null) return null;
        String group;
        switch (vt) {
            case "bottom_tool":
            case "flashlight_tool":
            case "flashlight_tool_backplane":
            case "button_frame_backplane":
                group = "btn";
                break;
            case "lock_icon_view":
            case "festival_frame_layout":
                group = "icon";
                break;
            case "indication_view":
            case "charging_status_view":
            case "magazine_simple_title_view":
                group = "txt";
                break;
            default:
                return null;
        }
        for (String[] row : KG_ROWS) {
            if (group.equals(row[0])) return row;
        }
        return null;
    }

    private static void logBtnSwap(String funnel, int from) {
        if (sBtnSceneLogged < 10) {
            sBtnSceneLogged++;
            log("btn[" + funnel + "]: matte " + from + " -> " + sGalleryScene);
        }
    }

    /**
     * v2.5.16: raise sKgCardNow around turnOnViewBlur(view, config) when the
     * config was built by the keyguard branch (registered in sKgConfigs by
     * the initBulrParams hook). The EdgeLightParamEx constructor hook reads
     * the flag to apply the keyguard-only kedge width. before-hook re-sets
     * the flag every call, so an exception in one card cannot leave the
     * flag stuck for unrelated surfaces.
     */
    private static void hookNtfCardGate(ClassLoader cl) {
        try {
            Class<?> nbvm = XposedHelpers.findClass(NBVM_CLASS, cl);
            XposedBridge.hookAllMethods(nbvm, "turnOnViewBlur", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    sKgCardNow = param.args.length >= 2
                            && sKgConfigs.contains(param.args[1]);
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    sKgCardNow = false;
                }
            });
            log("ok: NotificationBackgroundViewModel kg-edge gate installed");
        } catch (Throwable t) {
            log("skip: NotificationBackgroundViewModel missing ("
                    + t.getClass().getSimpleName() + ")");
        }
    }

    /** glass master only — gates the system-level base render config
     *  (HIGH machine tier, LIQUID material bits, texture/degree forcing)
     *  that the keyguard and gallery lines depend on. Keeping these under
     *  the per-line system switch would break keyguard/gallery glass when
     *  the user turns the system line off. */
    private static boolean glassOn() {
        return Cfg.isOn(Cfg.K_GLASS);
    }

    /** per-line switches: glass master AND the line's own switch. */
    private static boolean sysOn() {
        return Cfg.isOn(Cfg.K_GLASS) && Cfg.isOn(Cfg.K_SYSTEM);
    }

    private static boolean kgOn() {
        return Cfg.isOn(Cfg.K_GLASS) && Cfg.isOn(Cfg.K_KEYGUARD);
    }

    private static boolean galOn() {
        return Cfg.isOn(Cfg.K_GLASS) && Cfg.isOn(Cfg.K_GALLERY);
    }

    /** 某个 scope 对应的线是否开着（a=系统 k=锁屏 b=横幅）。 */
    private static boolean lineOn(String scope) {
        if ("k".equals(scope)) return sKgOn;
        if ("b".equals(scope)) return sBannerOn;
        return sSysOn;
    }

    /** 精细调节：百分比(0-200, 默认 100) -> 乘数。 */
    private static float fineF(String key) {
        return Cfg.getInt(key, Cfg.fnDefPct(key)) / 100f;
    }

    // 精细调节映射表：{BlurParametersConfig 字段名, Cfg 键, 适用范围}
    //
    // 适用范围 n/k/b/a —— 这三个 case 复用同一批字段名，原生在 initBulrParams 里
    // 给它们赋了完全不同的值（通知卡亮 0.08、横幅 0.0；通知卡饱和 1.2、横幅 1.5~1.8），
    // 所以要分开调，不能一个乘数刷到底：
    //   n = 普通通知卡（case=2, z=false）
    //   k = 锁屏通知卡（case=2, z=true）
    //   b = 通知横幅（case=3，来通知时顶部弹出的那条）
    //   a = 全部（瓦片/滑块/按钮/光学/模糊这些不和别人共用字段的）
    private static final String[][] FN_ALPHA = {
            {"liauidGlassMaskColor", Cfg.F_NTF_MASK, "n"},
            {"liauidGlassMaskColor", Cfg.F_KL_MASK, "k"},
            {"liauidGlassMaskColor", Cfg.F_BN_MASK, "b"},
            {"liauidGlassNtfOverlayColor", Cfg.F_NTF_OVL, "n"},
            {"liauidGlassNtfOverlayColor", Cfg.F_KL_OVL, "k"},
            {"liauidGlassNtfOverlayColor", Cfg.F_BN_OVL, "b"},
            {"controlNtfMaskColor", Cfg.F_NTFB_MASK, "n"},
            {"controlNtfverlayColor", Cfg.F_NTFB_OVL, "n"},
            {"controlMaskColor", Cfg.F_CC_MASK, "a"},
            {"controlOverlayColor", Cfg.F_CC_OVL, "a"},
            {"buttonMaskColor", Cfg.F_BTN_MASK, "a"},
            {"buttonOverlayColor", Cfg.F_BTN_OVL, "a"},
            {"sliderMaskColor", Cfg.F_SL_MASK, "a"},
            {"sliderOverlayColor", Cfg.F_SL_OVL, "a"},
    };

    private static final String[][] FN_FLOAT = {
            {"liauidGlassNtfbrightness", Cfg.F_NTF_BRIGHT, "n"},
            {"liauidGlassNtfbrightness", Cfg.F_KL_BRIGHT, "k"},
            {"liauidGlassNtfbrightness", Cfg.F_BN_BRIGHT, "b"},
            {"liauidGlassNtfSaturation", Cfg.F_NTF_SAT, "n"},
            {"liauidGlassNtfSaturation", Cfg.F_KL_SAT, "k"},
            {"liauidGlassNtfSaturation", Cfg.F_BN_SAT, "b"},
            {"controlNtfbrightness", Cfg.F_NTFB_BRIGHT, "a"},
            {"controlbrightness", Cfg.F_CC_BRIGHT, "a"},
            {"controlSaturation", Cfg.F_CC_SAT, "a"},
            {"buttonbrightness", Cfg.F_BTN_BRIGHT, "a"},
            {"buttonSaturation", Cfg.F_BTN_SAT, "a"},
            {"sliderbrightness", Cfg.F_SL_BRIGHT, "a"},
            {"sliderSaturation", Cfg.F_SL_SAT, "a"},
            {"notificationCornerRadius", Cfg.F_NTF_CORNER, "n"},
            {"notificationCornerRadius", Cfg.F_KL_CORNER, "k"},
            {"controlCornerRadius", Cfg.F_CC_CORNER, "a"},
            {"buttonCornerRadius", Cfg.F_BTN_CORNER, "a"},
            {"sliderCornerRadius", Cfg.F_SL_CORNER, "a"},
            {"refraction", Cfg.F_OPT_REFRACT, "a"},
            {"depth", Cfg.F_OPT_DEPTH, "a"},
            {"thickness", Cfg.F_OPT_THICK, "a"},
            {"dispersion", Cfg.F_OPT_DISP, "a"},
            // 注意：blurRadius / dynamicCardBlurRadius 这里**不能**写 "a"。
            // case=3（横幅）也有这两个字段，而横幅段（下面 1100 行附近）已经
            // 用 sBannerBlur 和 F_BN_BLUR 各乘过一次 —— 再叠一个 "a" 的
            // F_BLUR_CARD 就是三重大小相乘，用户拖"卡片模糊"会连带横幅一起变，
            // 而且成倍放大。所以卡片这两项只对 n/k 生效，横幅归横幅页独占。
            {"blurRadius", Cfg.F_BLUR_CARD, "n"},
            {"blurRadius", Cfg.F_BLUR_CARD, "k"},
            {"blurRadius", Cfg.F_BN_BLUR, "b"},
            {"dynamicCardBlurRadius", Cfg.F_BLUR_DYNAMIC, "n"},
            {"dynamicCardBlurRadius", Cfg.F_BLUR_DYNAMIC, "k"},
            {"scale", Cfg.F_BLUR_SCALE, "a"},
    };

    /** 精细调节是否有任何一项不是出厂值（用于跳过整块，零开销）。 */
    private static boolean fineAny() {
        // 三条线全关时没人会读这些值，直接跳过 —— 跟下面逐项 lineOn() 门控
        // 等价，只是省掉一轮 48 次查表。
        if (!sSysOn && !sKgOn && !sBannerOn) return false;
        for (String k : Cfg.FN_KEYS) {
            // 三种键（百分比项、开关项、角度）判定方式其实完全一样：
            // 当前值 != 出厂默认值。以前这里按类型写了三个分支，但三个分支
            // 的表达式一字不差，纯属历史遗留的复制粘贴，合成一行。
            if (Cfg.getInt(k, Cfg.fnDefPct(k)) != Cfg.fnDefPct(k)) return true;
        }
        return false;
    }

    private static void refreshProps() {
        // 系统线关掉时全部回中性值（x1.0 / 不覆盖）。
        // v2.5.3: DIY（glass_extreme + 14 根滑条）已整体移除 —— 它想解决的问题
        // 现在由「分区域强度」（tune_*，按控件分类粗调）和「精细调节」（fn_*，
        // 逐字段微调）两层完全覆盖，而且粒度更细。这里只剩 baked 标准配方 +
        // 各线开关的门控；两层滑条都在 hookBlurParams 里对字段做乘法。
        boolean sys = sysOn();
        sRadiusScale = sys ? DEFAULT_RADIUS : 1.0f;
        sMaskScale = sys ? DEFAULT_MASK : 1.0f;
        sSatScale = sys ? DEFAULT_SAT : 1.0f;
        sNtfMask = sys ? DEFAULT_NTF_MASK : 0.0f;
        sRefractScale = sys ? DEFAULT_REFRACT : 1.0f;
        sEdgeScale = sys ? DEFAULT_EDGE : 1.0f;
        sThickScale = sys ? DEFAULT_THICK : 1.0f;
        sDispScale = sys ? DEFAULT_DISP : 1.0f;
        sRimScale = sys ? DEFAULT_RIM : 1.0f;
        sFolderMode = sys;
        sTileBlurScale = sys ? DEFAULT_TBLUR : 1.0f;
        sBannerBlur = sys ? DEFAULT_BANNER_BLUR : 1.0f;
        sBannerOn = sys && Cfg.isOn(Cfg.K_BANNER);
        sSysOn = sys;
        // keyguard line: buttons (kbg/keng/kicon) + cards (kbblur/kveil/
        // kbbright/kedge) merged under one switch; off = neutral.
        boolean kg = kgOn();
        sKgOn = kg;
        sKeyguardBg = kg && DEFAULT_KBG > 0.5f;
        sKgEngineNotify = kg && DEFAULT_KENG > 0.5f;
        sKgIconFallback = kg && DEFAULT_KICON > 0.5f;
        sKgBlurRadius = kg ? DEFAULT_KBBLUR : 0.0f;
        sKgVeilScale = kg ? DEFAULT_KVEIL : 0.0f;
        sKgBrightness = kg ? DEFAULT_KBBRIGHT : 0.0f;
        sKgEdgeScale = kg ? DEFAULT_KEDGE : 1.0f;
        // gallery line: scene 0 = stock path everywhere
        boolean gal = galOn();
        sGalleryScene = gal ? DEFAULT_GSCENE : 0;
        sGalleryBar = gal;


        // 配方来源：排查"玻璃发亮/发灰"先看这一行。
        // standard = baked 标准配方 + 两层滑条；off = 系统综合关闭走原生玻璃。
        //
        // ⚠ v2.6.9：以前这里用 Diag.note()，但实测 **note 的输出进不了 logcat**
        // （Vector 的日志桥在模块 handleLoadPackage 早期还没就绪，XposedBridge.log
        // 那一刻的输出全丢）。结果就是：启动期的版本横幅、hook 挂载结果、这一行
        // recipe 全都看不到，排查时只能靠猜"跑的是哪一版、钩子挂上没有"。
        // 现在改成 log()（运行时通道，实测能进 logcat），并把版本号和两个关键
        // 钩子的挂载状态直接写进这一行。
        if (sRecipeLogged < 3) {
            sRecipeLogged++;
            String line = "recipe: v" + BuildConfig.VERSION_NAME
                    + "(" + BuildConfig.VERSION_CODE + ")"
                    + " bpc=" + (sBpcHooked.get() ? "on" : "OFF")
                    + " veil=" + (sPanelVeilHooked.get() ? "on" : "OFF")
                    + " " + (sys ? "standard" : "off")
                    + " mask=" + sMaskScale + " nmask=" + sNtfMask
                    + " tblur=" + sTileBlurScale + " sat=" + sSatScale
                    + " radius=" + sRadiusScale;
            if (Cfg.ready()) {
                log(line);
            } else {
                log(line + "  [Cfg 未就绪！本次按 baked 标准配方，"
                        + "用户的开关/滑条这一轮不生效]");
            }
        }
    }

    /**
     * Core fix. getTransparentModeState() is a big method (string building,
     * multiple calls) so AOT never inlines it — every caller goes through
     * its entry point and our hook fires. After it caches mBlurType we
     * overwrite the field to LIQUID; all later isLiquidBlurType() checks
     * (inlined or not) read the field and see liquid.
     */
    private static void hookTransparentMode(ClassLoader cl) {
        // 幂等：getTransparentBlurRadius 的钩子是**读结果再乘**，
        // 重复挂 N 次就是 sRadiusScale 的 N 次方。Vector 实测会对同一进程
        // 多次回调 handleLoadPackage，所以这里必须挡一道。
        if (sTmmHooked.get()) return;
        Class<?> tmm;
        try {
            tmm = XposedHelpers.findClass(TMM_CLASS, cl);
        } catch (Throwable t) {
            Diag.note("skip: TransparentModeManager missing (" + t.getClass().getSimpleName() + ")");
            return;
        }
        if (!sTmmHooked.compareAndSet(false, true)) return;
        try {

            XposedBridge.hookAllMethods(tmm, "getTransparentModeState", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!glassOn()) return;
                    Object reason = param.args.length > 1 ? param.args[1] : "?";
                    try {
                        int oldType = XposedHelpers.getIntField(param.thisObject, "mBlurType");
                        XposedHelpers.setIntField(param.thisObject, "mBlurType", BLUR_TYPE_LIQUID);
                        if (sStateCount < 6) {
                            sStateCount++;
                            log("state: reason=" + reason + " on=" + param.getResult()
                                    + " mBlurType=" + oldType + " -> " + BLUR_TYPE_LIQUID);
                        }
                    } catch (Throwable t) {
                        log("state field fix failed: " + t);
                    }
                }
            });

            XposedBridge.hookAllMethods(tmm, "isLiquidBlurType", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!glassOn()) return;
                    try {
                        boolean on = XposedHelpers.getBooleanField(param.thisObject, "mIsTransparentModeOn");
                        if (on && !((Boolean) param.getResult())) {
                            param.setResult(true);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
            XposedBridge.hookAllMethods(tmm, "isFrostedBlurType", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!glassOn()) return;
                    // 与 isLiquidBlurType 保持对称：只有透明模式真的开着才把
                    // FROSTED 判成 false。早先这里是无条件 false —— 用户关掉系统
                    // 的透明模式后，液态和磨砂会同时返回 false，宿主拿到"两个都不是"
                    // 的状态会退化成不模糊。字段读不到时保持原值（catch 兜底）。
                    try {
                        if (!XposedHelpers.getBooleanField(
                                param.thisObject, "mIsTransparentModeOn")) {
                            return;
                        }
                    } catch (Throwable t) {
                        // 字段读不到就保持原值（不压磨砂）。这条路以前是硬编码
                        // false、不依赖任何字段；现在多了个依赖，一旦宿主改字段名
                        // 就会静默退化 —— 所以留一条一次性日志，别让它悄悄失效。
                        if (!sFrostGateWarned) {
                            sFrostGateWarned = true;
                            log("frost-type gate: mIsTransparentModeOn 读不到 ("
                                    + t.getClass().getSimpleName() + ")，磨砂判定未接管");
                        }
                        return;
                    }
                    param.setResult(false);
                }
            });

            XposedBridge.hookAllMethods(tmm, "getTransparentBlurRadius", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object r = param.getResult();
                    if (!(r instanceof Float)) {
                        return;
                    }
                    if (!sysOn()) return;
                    refreshProps();
                    if (sRadiusScale >= 0.999f) {
                        return;
                    }
                    float orig = (Float) r;
                    float scaled = Math.max(1.0f, orig * sRadiusScale);
                    if (sRadiusCount < 6) {
                        sRadiusCount++;
                        log("radius: case=" + (param.args.length > 0 ? param.args[0] : "?")
                                + " " + orig + " -> " + scaled);
                    }
                    if (scaled != orig) {
                        param.setResult(scaled);
                    }
                }
            });

            log("ok: TransparentModeManager LIQUID state fix installed");
        } catch (Throwable t) {
            sTmmHooked.set(false);   // 挂到一半失败，允许下次重试
            Diag.note("skip: TransparentModeManager hook failed (" + t.getClass().getSimpleName() + ")");
        }
    }

    /**
     * Control-center widgets (liquid cards / round buttons / sliders) build
     * their glass params here. withTransparency() is a big method, so the
     * hook always fires; we then rewrite the BlurParam fields the renderers
     * read. currentTransparency is reset so the setters re-apply absolute
     * values on the next call (prevents scale compounding).
     */
    private static void hookDegreeAlgorithm(ClassLoader cl) {
        try {
            Class<?> algo = XposedHelpers.findClass(DEGREE_ALGO_CLASS, cl);
            final Class<?> colorHelper = XposedHelpers.findClass(COLOR_HELPER_CLASS, cl);

            XposedBridge.hookAllMethods(algo, "withTransparency", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!sysOn() || galOn()) return;
                    refreshProps();
                    if (sRadiusScale >= 0.999f && sMaskScale >= 0.999f) {
                        return;
                    }
                    try {
                        XposedHelpers.setIntField(param.thisObject, "currentTransparency", -1);
                        Object repo = XposedHelpers.getObjectField(param.thisObject, "mHighGradeParamsRepo");
                        if (repo == null) {
                            return;
                        }
                        scaleBlurParam(repo, "lightBlurParam", colorHelper);
                        scaleBlurParam(repo, "darkBlurParam", colorHelper);
                        if (sUikitCount < 6) {
                            sUikitCount++;
                            log("uikit: repo=" + repo.getClass().getSimpleName()
                                    + " radius x" + sRadiusScale + " mask x" + sMaskScale);
                        }
                    } catch (Throwable t) {
                        log("uikit scale failed: " + t);
                    }
                }
            });

            log("ok: uikit degree algorithm scaling installed");
        } catch (Throwable t) {
            log("skip: uikit degree algorithm missing (" + t.getClass().getSimpleName() + ")");
        }
    }

    private static void scaleBlurParam(Object repo, String field, Class<?> colorHelper) {
        try {
            Object p = XposedHelpers.getObjectField(repo, field);
            if (p == null) {
                return;
            }
            if (sRadiusScale < 0.999f) {
                float r = XposedHelpers.getFloatField(p, "radius");
                XposedHelpers.setFloatField(p, "radius", Math.max(0.5f, r * sRadiusScale));
            }
            if (sMaskScale < 0.999f) {
                int[] alphas = (int[]) XposedHelpers.getObjectField(p, "alphas");
                int[] rgbs = (int[]) XposedHelpers.getObjectField(p, "RGBs");
                if (alphas == null || rgbs == null || alphas.length != rgbs.length) {
                    return;
                }
                int[] na = new int[alphas.length];
                for (int i = 0; i < alphas.length; i++) {
                    na[i] = Math.max(0, Math.min(255, Math.round(alphas[i] * sMaskScale)));
                }
                XposedHelpers.setObjectField(p, "alphas", na);
                Object mask = XposedHelpers.callStaticMethod(colorHelper, "composeColors", rgbs, na);
                XposedHelpers.setObjectField(p, "maskColors", mask);
            }
        } catch (Throwable ignored) {
        }
    }

    /** Scale the alpha of every mask/overlay color the glass renderer uses. */
    private static void hookBlurParams(ClassLoader cl) {
        // 已挂上就别再挂 —— 重复挂载会让回调执行两次、所有乘法叠乘。
        if (sBpcHooked.get()) return;
        Class<?> bpc;
        try {
            bpc = XposedHelpers.findClass(BPC_CLASS, cl);
        } catch (Throwable t) {
            // 注入竞态：这次 classLoader 里还没有 app dex 类。**不置位**，
            // 留待 setBlurParams 回调里用真实 app CL 再补一次。
            // 用 note（不受诊断开关控制）—— 挂载结果发生在 Cfg 落地之前，
            // 那一刻 diag 开关还读不到，用 log() 会被整条吞掉，
            // 于是"hook 到底挂上没有"永远是个谜。
            Diag.note("skip: BlurParametersConfig missing (" + t.getClass().getSimpleName()
                    + ")，将在 setBlurParams 回调里补挂");
            return;
        }
        // findClass 成功后才抢占；CAS 失败说明别的线程已经挂上了。
        if (!sBpcHooked.compareAndSet(false, true)) return;
        try {

            XposedBridge.hookAllMethods(bpc, "initBulrParams", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!Cfg.isOn(Cfg.K_GLASS)) return;
                    refreshProps();
                    Object o = param.thisObject;
                    // deferred keyguard hooks: lpparam.classLoader is empty
                    // (DexPathList[[]]); the BlurParams config object's CL is
                    // the real app CL (KeyguardWallpaper/ViewBlur/... hooks).
                    //
                    // ⚠ 必须先拿到 CL 再置位：早先写成 `if (CAS) { sAppCl = ...;
                    // log(sAppCl.getClass()...) }`，万一 getClassLoader() 返回 null，
                    // 那一行 NPE 会冒到外层 catch，而标志**已经置成 true** ——
                    // 三个 keyguard 钩子（bg / icon / panelVeil）就永远不会再试了。
                    if (!sKgHooksInstalled.get()) {
                        ClassLoader acl = null;
                        try {
                            acl = o.getClass().getClassLoader();
                        } catch (Throwable ignored) {
                        }
                        if (acl != null && sKgHooksInstalled.compareAndSet(false, true)) {
                            log("kg-deferred: app CL via obj = " + acl.getClass().getName());
                            try { hookKeyguardBgBitmap(acl); } catch (Throwable t) { log("kg-deferred bg fail: " + t); }
                            try { hookKeyguardIconFallback(acl); } catch (Throwable t) { log("kg-deferred icon fail: " + t); }
                            try { hookPanelVeil(acl); } catch (Throwable t) { log("kg-deferred panelveil fail: " + t); }
                            try { hookKgClockBlur(acl); } catch (Throwable t) { log("kg-deferred clock fail: " + t); }
                        }
                    }
                    if (sKgBlurRadius > 0.01f && param.args.length >= 3
                            && Boolean.TRUE.equals(param.args[2])
                            && param.args[0] instanceof Integer && (Integer) param.args[0] == 2) {
                        // register this config as a keyguard-card recipe so
                        // turnOnViewBlur can raise the kg edge-light gate
                        sKgConfigs.add(o);
                        // v2.5.13 kbshade: with the light synthesis bitmap
                        // active (kbblur>0), the keyguard card switches from
                        // the z=true thick_1 recipe to the SHADE recipe
                        // (z=false branch of the same stock code): light ntf
                        // colors + brightness — the exact params the
                        // pull-down shade cards render with (stock 0.08;
                        // v2.5.14 raises to 0.12 because the keyguard card
                        // floats over SHARP wallpaper while shade cards float
                        // over the already-dimmed panel sheet, so the same
                        // veil reads darker here). Paired with the
                        // panel-recipe bitmap (blur + sat + veil) this
                        // replicates the shade look on the lock screen. Only
                        // when kbblur>0: on the stock bokeh this recipe has
                        // no structure to refract and reads flat (v2.5.10
                        // lesson). Runs BEFORE the mask scaling so the light
                        // colors get the same x mask treatment as the shade.
                        try {
                            int kMask = resolveStockColor(o, "ntf_background_color_transparent", 0x00000000);
                            int kOverlay = resolveStockColor(o, "card_background_overlay_color_transparent", 0x00000000);
                            // v2.5.15: kbbright knob. Pixel A/B
                            // showed the keyguard card face sits 28-42 levels
                            // BELOW its sharp-wallpaper backdrop while shade
                            // cards sit +20 ABOVE their dimmed panel — the
                            // "sunk decal" vs "floating glass" difference. The
                            // veil alone can't close that gap (0.12 already
                            // near the readable ceiling), so expose the
                            // brightness directly.
                            // keyguard card transparency now flows entirely
                            // through this framework-layer recipe: scale the
                            // stock card colors' alpha down (normal 0.6 =
                            // slightly clearer, extreme 0.15 = near-clear).
                            // The old veil/blur route needed KeyguardWallpaper
                            // (systemui app dex — unhookable since the OTA
                            // emptied lpparam.classLoader), so this is the
                            // only lever that reaches the lock screen.
                            XposedHelpers.setFloatField(o, "liauidGlassNtfbrightness", sKgBrightness);
                            XposedHelpers.setIntField(o, "liauidGlassMaskColor", kMask);
                            XposedHelpers.setIntField(o, "liauidGlassNtfOverlayColor", kOverlay);
                            XposedHelpers.setObjectField(o, "multiMaskColors", new int[]{kMask, kOverlay});
                            if (sKgShadeLogged < 4) {
                                sKgShadeLogged++;
                                log("kbshade: shade recipe on keyguard card (mask=0x"
                                        + Integer.toHexString(kMask) + " overlay=0x"
                                        + Integer.toHexString(kOverlay) + " brightness=" + sKgBrightness + ")");
                            }
                        } catch (Throwable t) {
                            log("kbshade failed: " + t);
                        }
                    }
                    // ---- 按控件类型分别缩放（v2.5.0）----
                    // 原生 initBulrParams 给四类控件赋了不同的亮度/饱和度/遮罩资源，
                    // 旧版只有一个 sMaskScale 一把刷到全部控件上；现在每类还有自己的
                    // 强度权重，想单独压暗通知卡不会连带滑块和按钮一起变。
                    if (sMaskScale < 0.999f) {
                        try {
                            // 通知卡（含瓦片内的通知卡、动态卡片渐变）
                            float ntf = sMaskScale;
                            scaleAlphaField(o, "liauidGlassMaskColor", ntf);
                            scaleAlphaField(o, "liauidGlassNtfOverlayColor", ntf);
                            scaleAlphaField(o, "controlNtfMaskColor", ntf);
                            scaleAlphaField(o, "controlNtfverlayColor", ntf);
                            scaleAlphaArray(o, "multiMaskColors", ntf);
                            scaleAlphaArray(o, "dynamicCardGradientMaskColor", ntf);
                            scaleAlphaArray(o, "dynamicCardGradientMaskColorOther", ntf);
                            // 控制中心瓦片
                            float cc = sMaskScale;
                            scaleAlphaField(o, "controlMaskColor", cc);
                            scaleAlphaField(o, "controlOverlayColor", cc);
                            scaleAlphaArray(o, "multiControlMaskColors", cc);
                            // 滑块
                            float sl = sMaskScale;
                            scaleAlphaField(o, "sliderMaskColor", sl);
                            scaleAlphaField(o, "sliderOverlayColor", sl);
                            scaleAlphaArray(o, "multiSliderMaskColors", sl);
                            // 圆形按钮
                            float bt = sMaskScale;
                            scaleAlphaField(o, "buttonMaskColor", bt);
                            scaleAlphaField(o, "buttonOverlayColor", bt);
                            scaleAlphaArray(o, "multiButtonMaskColors", bt);
                            if (sMaskCount < 3) {
                                sMaskCount++;
                                log("mask: initBulrParams case="
                                        + (param.args.length > 0 ? param.args[0] : "?")
                                        + " alpha x" + sMaskScale);
                            }
                        } catch (Throwable t) {
                            log("mask scale failed: " + t);
                        }
                    }
                    // ⚠ v2.6.10：这个块以前**不分 case** 一把刷 —— 它把 controlNtfMaskColor /
                    // controlNtfverlayColor 绝对覆盖成 0x66 灰，连磨砂模式（case=1）和
                    // 横幅（case=3）都被改。而这两个字段在原生里每个 case 的底色都不一样
                    // （case=1 用 no_transparent 不透明色、case=2 用 transparent、
                    //   case=3 用 headsup 那套），一刀切成 0x66 等于把别人的配方毁了。
                    // 现在只对「普通通知卡」(case=2 且 z=false) 生效 —— 锁屏卡走 kbshade
                    // 自己的配方，横幅走 banner 块，都各有各的写法。
                    boolean plainNtfCard = param.args.length > 2
                            && param.args[0] instanceof Integer
                            && (Integer) param.args[0] == 2
                            && !Boolean.TRUE.equals(param.args[2]);
                    if (sNtfMask > 0.001f && plainNtfCard) {
                        // absolute override AFTER the generic scale so the
                        // card tint is exactly nmask, independent of mask prop
                        try {
                            // nmask 是「精确指定通知卡 tint」，同样要受 tune_ntf 影响，
                            // 否则用户把通知卡强度调到 50% 后 nmask 会把它顶回去。
                            // 乘完必须 clamp：0.4×2.0=0.8 还好，1.0×2.0 就溢出。
                            float nm = sNtfMask;
                            int a1 = Math.min(255, Math.round(nm * 255.0f));
                            int a2 = Math.min(255, Math.round(nm * 0.5f * 255.0f));
                            int tint = (a1 << 24) | 0x2E2E2E;
                            int overlay = (a2 << 24) | 0x2E2E2E;
                            XposedHelpers.setIntField(o, "controlNtfMaskColor", tint);
                            XposedHelpers.setIntField(o, "controlNtfverlayColor", overlay);
                            if (sNtfCount < 3) {
                                sNtfCount++;
                                log("ntf-tint: card mask=0x" + Integer.toHexString(tint)
                                        + " overlay=0x" + Integer.toHexString(overlay)
                                        + " (case=" + (param.args.length > 0 ? param.args[0] : "?") + ")");
                            }
                        } catch (Throwable t) {
                            log("ntf tint failed: " + t);
                        }
                    }
                    // ---- 精细调节（v2.5.2）----
                    // 放在 nmask 绝对覆盖之后：nmask 决定卡片底色，这里再按单项微调，
                    // 两者相乘。全部为 100%/默认值时整块跳过，零开销。
                    if (fineAny()) {
                        try {
                            // 本次调用落在哪一套原生配方上：
                            //   case=2 且 z=false -> n  普通通知卡 / 控制中心瓦片
                            //   case=2 且 z=true  -> k  锁屏通知卡
                            //   case=3            -> b  通知横幅
                            //   case=1/4/5        -> null 一律不碰
                            //
                            // 以前这里默认 scope="n"，等于把 case=1/4/5 也当成
                            // 普通通知卡处理。但原生给它们赋的是完全不同的数值
                            // （case=1 磨砂：controlSaturation=2.8、controlbrightness=0.0；
                            //   case=2 液态：1.1 / 0.12；case=5 的 blurRadius 直接 130），
                            // 把"通知卡遮罩 60%"这类微调套上去是改错了对象 ——
                            // 症状就是"我只调了通知卡，磨砂模式跟着一起变"。
                            String scope = null;
                            if (param.args.length > 2 && param.args[0] instanceof Integer) {
                                int cs = (Integer) param.args[0];
                                if (cs == 3) {
                                    scope = "b";
                                } else if (cs == 2) {
                                    scope = Boolean.TRUE.equals(param.args[2]) ? "k" : "n";
                                }
                            }
                            // scope == null 时整块跳过，连 "a" 全局项也不作用 ——
                            // 那些字段在 case=1/4/5 里是另一套数值体系，乘上去
                            // 只会跑偏（磨砂模式的饱和度基数 2.8，×2 直接爆色）。
                            if (scope != null) {
                                // 记录本轮回调实际动了哪些字段（限流 6 次），
                                // 排查"滑条没反应"时一眼看出卡在哪一步。
                                StringBuilder applied = sFineLogged < 6
                                        ? new StringBuilder() : null;
                                // 按线门控：全局项跟系统线，锁屏项跟锁屏线，
                                // 横幅项跟横幅开关。某一线的开关关掉时，它那几项
                                // 必须原样不动。
                                for (String[] m : FN_ALPHA) {
                                    if (!"a".equals(m[2]) && !scope.equals(m[2])) continue;
                                    if (!lineOn(m[2])) continue;
                                    float k = fineF(m[1]);
                                    if (Math.abs(k - 1f) > 0.001f) {
                                        scaleAlphaField(o, m[0], k);
                                        if (applied != null) {
                                            applied.append(' ').append(m[1]).append('x').append(k);
                                        }
                                    }
                                }
                                for (String[] m : FN_FLOAT) {
                                    if (!"a".equals(m[2]) && !scope.equals(m[2])) continue;
                                    if (!lineOn(m[2])) continue;
                                    float k = fineF(m[1]);
                                    if (Math.abs(k - 1f) > 0.001f) {
                                        scaleFloatField(o, m[0], k);
                                        if (applied != null) {
                                            applied.append(' ').append(m[1]).append('x').append(k);
                                        }
                                    }
                                }
                                // 高光开关都属系统线：系统线关掉时必须原样不动。
                                // 用 if 包裹而不是 return —— return 会跳出整个
                                // 精细块，将来若把横幅/锁屏逻辑挪到后面会被误伤。
                                if (sSysOn) {
                                    if (Cfg.getInt(Cfg.F_HL_ON, Cfg.fnDefPct(Cfg.F_HL_ON)) != 0) {
                                        XposedHelpers.setBooleanField(o, "isNeedHighLight", false);
                                        if (applied != null) applied.append(" hl=off");
                                    }
                                    if (Cfg.getInt(Cfg.F_HL_CC, Cfg.fnDefPct(Cfg.F_HL_CC)) != 0) {
                                        XposedHelpers.setBooleanField(o, "isControlNeedHighLight", false);
                                        if (applied != null) applied.append(" hlcc=off");
                                    }
                                    if (Cfg.getInt(Cfg.F_HL_BTN, Cfg.fnDefPct(Cfg.F_HL_BTN)) != 0) {
                                        XposedHelpers.setBooleanField(o, "isButtonNeedHighLight", false);
                                        if (applied != null) applied.append(" hlbtn=off");
                                    }
                                    if (Cfg.getInt(Cfg.F_HL_SL, Cfg.fnDefPct(Cfg.F_HL_SL)) != 0) {
                                        XposedHelpers.setBooleanField(o, "sliderNeedHighLight", false);
                                        if (applied != null) applied.append(" hlsl=off");
                                    }
                                }
                                // 统一在所有改动做完之后打一条，别漏掉高光那几项。
                                if (applied != null && applied.length() > 0) {
                                    sFineLogged++;
                                    log("fine: scope=" + scope + applied);
                                }
                            }
                        } catch (Throwable t) {
                            log("fine tune failed: " + t);
                        }
                    }
                    if (sBannerOn && param.args.length > 0
                            && param.args[0] instanceof Integer && (Integer) param.args[0] == 3) {
                        // heads-up banner: absolute overrides (after the
                        // generic scale), stock values logged for
                        // calibration. Banner blur is handled HERE and the
                        // generic tblur pass below skips case=3. Panel
                        // shade cards are z-branches of case=2, so this
                        // never touches the finalized shade look.
                        try {
                            // brightness left to the system — the stock value
                            // follows day/night mode and the heads-up text
                            // auto-inverts against it; forcing a dark value
                            // made the banner dark and the (still-black) text
                            // unreadable. Keep only the blur (transparency).
                            float br = XposedHelpers.getFloatField(o, "blurRadius");
                            float nbr = Math.max(1.0f, br * sBannerBlur);
                            XposedHelpers.setFloatField(o, "blurRadius", nbr);
                            float dbr = XposedHelpers.getFloatField(o, "dynamicCardBlurRadius");
                            if (dbr > 0.01f) {
                                XposedHelpers.setFloatField(o, "dynamicCardBlurRadius",
                                        Math.max(1.0f, dbr * sBannerBlur));
                            }
                            if (sBannerLogged < 6) {
                                sBannerLogged++;
                                log("banner: case=3 blur " + br + " -> " + nbr
                                        + " z=" + (param.args.length > 2 ? param.args[2] : "?"));
                            }
                        } catch (Throwable t) {
                            log("banner trim failed: " + t);
                        }
                    }
                    // v2.5.12/13: keyguard cards (case=2, z=true) stay on the
                    // STOCK recipe (thick_1 colors x mask, brightness -0.15,
                    // saturation 1.2) when kbblur==0 — the v2.3 look. When
                    // kbblur>0 the kbshade block above already switched them
                    // to the shade recipe; nothing more to do here.
                    try {
                        scaleFloatField(o, "refraction", sRefractScale);
                        scaleFloatField(o, "depth", sRefractScale);
                        scaleFloatField(o, "thickness", sThickScale);
                        scaleFloatField(o, "dispersion", sDispScale);
                        if (sRefractLogged < 3) {
                            sRefractLogged++;
                            log("refract: x" + sRefractScale + " thick x" + sThickScale
                                    + " disp x" + sDispScale
                                    + " (refraction=" + XposedHelpers.getFloatField(o, "refraction")
                                    + " thickness=" + XposedHelpers.getFloatField(o, "thickness")
                                    + " dispersion=" + XposedHelpers.getFloatField(o, "dispersion") + ")");
                        }
                    } catch (Throwable t) {
                        log("refract scale failed: " + t);
                    }
                    if (sFolderMode && param.args.length > 0 && param.args[0] instanceof Integer
                            && (Integer) param.args[0] >= 2) {
                        // launcher folder recipe (LiquidLargeOnHighGeneral):
                        // refraction 0.3 / depth 0.54 / thickness 0.62 /
                        // dispersion 0.2 — wide soft refraction band with a
                        // restrained chromatic fringe, no glow. Absolute
                        // values (after any scaling) so tiles render exactly
                        // like desktop folders. Cases >=2 are the liquid
                        // branches (2 = tiles, 3 = ntf banner).
                        // thickness honors the thick prop as a multiplier on
                        // the folder base (0.62) — widens the edge refraction
                        // band without touching refraction/dispersion.
                        //
                        // ⚠ v2.6.7：这里是**绝对值覆盖**，会把上面精细调节
                        // 给 refraction/depth/thickness/dispersion 乘的系数
                        // 直接抹掉 —— 而 sFolderMode = sys 默认是开的，等于
                        // 「折射率 / 深度 / 厚度 / 色散」四根滑条一直 100% 失效。
                        // 修法：folder 基准值自己也乘一次精细系数（跟下面
                        // sThickScale 的处理方式保持一致）。
                        try {
                            XposedHelpers.setFloatField(o, "refraction",
                                    0.3f * fineF(Cfg.F_OPT_REFRACT));
                            XposedHelpers.setFloatField(o, "depth",
                                    0.54f * fineF(Cfg.F_OPT_DEPTH));
                            XposedHelpers.setFloatField(o, "thickness",
                                    0.62f * sThickScale * fineF(Cfg.F_OPT_THICK));
                            XposedHelpers.setFloatField(o, "dispersion",
                                    0.2f * fineF(Cfg.F_OPT_DISP));
                            if (sFolderLogged < 3) {
                                sFolderLogged++;
                                log("folder: liquid optics -> 0.3/0.54/"
                                        + (0.62f * sThickScale) + "/0.2 (case="
                                        + param.args[0] + ", thick x" + sThickScale + ")");
                            }
                        } catch (Throwable t) {
                            log("folder optics failed: " + t);
                        }
                    }
                    if (Math.abs(sTileBlurScale - 1.0f) > 0.001f
                            && !(param.args.length > 0 && param.args[0] instanceof Integer
                                    && (Integer) param.args[0] == 3)) {
                        // second blur pass: every tile / card re-blurs the
                        // panel bitmap with blurRadius — the residual frosted
                        // look. Independent of the background radius prop.
                        // case=3 (banner) is scaled in the banner block
                        // above with its own DEFAULT_BANNER_BLUR.
                        try {
                            float br = XposedHelpers.getFloatField(o, "blurRadius");
                            float nbr = Math.max(1.0f, br * sTileBlurScale);
                            XposedHelpers.setFloatField(o, "blurRadius", nbr);
                            float dbr = XposedHelpers.getFloatField(o, "dynamicCardBlurRadius");
                            if (dbr > 0.01f) {
                                XposedHelpers.setFloatField(o, "dynamicCardBlurRadius",
                                        Math.max(1.0f, dbr * sTileBlurScale));
                            }
                            if (sTileBlurLogged < 3) {
                                sTileBlurLogged++;
                                log("tblur: case=" + (param.args.length > 0 ? param.args[0] : "?")
                                        + " blurRadius " + br + " -> " + nbr
                                        + " dynamicCard " + dbr + " x" + sTileBlurScale);
                            }
                        } catch (Throwable t) {
                            log("tblur scale failed: " + t);
                        }
                    }
                    // ---- 强制液态玻璃（v2.6.19）----
                    // 必须放在**最后**：前面 kbshade / folder / banner 那些块
                    // 都会重写 glassType，放前面等于白改。
                    if (sSysOn) {
                        try {
                            if (Cfg.getInt(Cfg.F_FORCE_LIQUID, 0) != 0) {
                                int gt = XposedHelpers.getIntField(o, "glassType");
                                if (gt != 2) {
                                    XposedHelpers.setIntField(o, "glassType", 2);
                                    if (sForceLiquidLogged < 8) {
                                        sForceLiquidLogged++;
                                        log("force-liquid: glassType " + gt + " -> 2 (case="
                                                + (param.args.length > 0 ? param.args[0] : "?") + ")");
                                    }
                                }
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                }
            });

            Diag.note("ok: BlurParametersConfig 精细调节钩子已挂上");
        } catch (Throwable t) {
            // 挂到一半失败：回滚标志，让 setBlurParams 回调还能再试一次。
            // （hookAllMethods 要么全成要么抛异常，不会留下半个钩子。）
            sBpcHooked.set(false);
            log("skip: BlurParametersConfig hook failed (" + t.getClass().getSimpleName() + ")");
        }
    }

    private static Boolean sWallpaperDark;

    private static boolean isWallpaperDark(Context ctx) {
        if (sWallpaperDark == null) {
            try {
                android.app.WallpaperManager wm = android.app.WallpaperManager.getInstance(ctx);
                android.graphics.drawable.Drawable wd = wm.getDrawable();
                if (wd instanceof android.graphics.drawable.BitmapDrawable) {
                    android.graphics.Bitmap bmp = ((android.graphics.drawable.BitmapDrawable) wd).getBitmap();
                    double total = 0;
                    int cnt = 0;
                    int w = bmp.getWidth(), h = bmp.getHeight();
                    for (int x = 0; x < w; x += Math.max(1, w / 32)) {
                        for (int y = 0; y < h; y += Math.max(1, h / 32)) {
                            int p = bmp.getPixel(x, y);
                            total += (0.299 * ((p >> 16) & 0xFF)
                                    + 0.587 * ((p >> 8) & 0xFF)
                                    + 0.114 * (p & 0xFF)) / 255.0;
                            cnt++;
                        }
                    }
                    sWallpaperDark = cnt > 0 && (total / cnt) < 0.35;
                } else {
                    sWallpaperDark = false;
                }
            } catch (Throwable t) {
                sWallpaperDark = false;
            }
        }
        return sWallpaperDark;
    }

    /**
     * Extreme-glass only: with a dark wallpaper the transparent heads-up
     * banner lets the wallpaper show through, but the stock text color
     * (set by the notifying app's RemoteViews, usually black) stays dark
     * and becomes unreadable. When the extreme switch is ON and the
     * wallpaper is dark, invert dark text set through notification
     * inflation paths to near-white.
     */
    private static void hookTextColor() {
        try {
            XposedBridge.hookAllMethods(android.widget.TextView.class, "setTextColor", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    // v2.5.3: 原来这里门控 DIY 模式，DIY 移除后改跟系统线 ——
                    // 精细调节同样能把玻璃调得很透，深色文字仍需反白兜底。
                    if (!sysOn()) return;
                    if (!(param.args[0] instanceof Integer)) return;
                    int c = (Integer) param.args[0];
                    double lum = (0.299 * ((c >> 16) & 0xFF)
                            + 0.587 * ((c >> 8) & 0xFF)
                            + 0.114 * (c & 0xFF)) / 255.0;
                    if (lum > 0.35) return; // already light text
                    StackTraceElement[] st = Thread.currentThread().getStackTrace();
                    boolean notif = false;
                    for (int i = 0; i < st.length && i < 25; i++) {
                        String cn = st[i].getClassName();
                        if (cn.contains("notification") || cn.contains("headsup")
                                || cn.contains("HeadsUp") || cn.contains("NotifInflater")) {
                            notif = true;
                            break;
                        }
                    }
                    if (!notif) return;
                    Object v = param.thisObject;
                    if (v instanceof android.widget.TextView) {
                        if (isWallpaperDark(((android.widget.TextView) v).getContext())) {
                            param.args[0] = 0xFFF0F0F0;
                            if (sTextInvertLogged < 10) {
                                sTextInvertLogged++;
                                log("text-invert dark->light on " + v.getClass().getSimpleName());
                            }
                        }
                    }
                }
            });
            log("ok: text-color invert installed");
        } catch (Throwable t) {
            log("skip: text-color fail: " + t);
        }
    }

    private static void scaleAlphaField(Object obj, String field, float scale) throws Throwable {
        int c = XposedHelpers.getIntField(obj, field);
        XposedHelpers.setIntField(obj, field, scaleAlpha(c, scale));
    }

    private static void scaleFloatField(Object obj, String field, float scale) throws Throwable {
        float v = XposedHelpers.getFloatField(obj, field);
        XposedHelpers.setFloatField(obj, field, v * scale);
    }

    private static void scaleAlphaArray(Object obj, String field, float scale) throws Throwable {
        Object arr = XposedHelpers.getObjectField(obj, field);
        if (arr == null) {
            return;
        }
        int n = Array.getLength(arr);
        for (int i = 0; i < n; i++) {
            Object v = Array.get(arr, i);
            if (v instanceof Integer) {
                Array.set(arr, i, scaleAlpha((Integer) v, scale));
            }
        }
    }

    private static int scaleAlpha(int color, float scale) {
        int a = (color >>> 24) & 0xFF;
        // 必须钳到 0..255。DIY 的 rim / kedge 上限是 2.0（100%），255×2=510 直接
        // <<24 会截断成 0x65 —— 边框从"完全不透明"翻转成"40% 透明"，效果完全反了。
        // 同一个文件里 scaleBlurParam / veilA 都做了钳制，这里早先漏了。
        int na = Math.max(0, Math.min(255, Math.round(a * scale)));
        return (na << 24) | (color & 0x00FFFFFF);
    }

    /** Resolve a stock SystemUI color resource by name; fallback if missing. */
    private static int resolveStockColor(Object cfg, String name, int fallback) {
        try {
            Object ctx = XposedHelpers.getObjectField(cfg, "mContext");
            Object res = XposedHelpers.callMethod(ctx, "getResources");
            String pkg = (String) XposedHelpers.callMethod(ctx, "getPackageName");
            int id = (Integer) XposedHelpers.callMethod(res, "getIdentifier", name, "color", pkg);
            if (id == 0) {
                return fallback;
            }
            return (Integer) XposedHelpers.callMethod(ctx, "getColor", id);
        } catch (Throwable t) {
            return fallback;
        }
    }

    /** Swap FROSTED blur-type bit to LIQUID in the extended Configuration. */
    private static void hookConfigEx(ClassLoader cl) {
        try {
            Class<?> cex = XposedHelpers.findClass(CONFIG_EX_CLASS, cl);
            XposedBridge.hookAllMethods(cex, "getTransparentMode", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!glassOn()) return;
                    Object r = param.getResult();
                    if (!(r instanceof Integer)) {
                        return;
                    }
                    int orig = (Integer) r;
                    if ((orig & BLUR_TYPE_MASK) == BLUR_TYPE_FROSTED) {
                        param.setResult((orig & ~BLUR_TYPE_MASK) | BLUR_TYPE_LIQUID);
                        if (!sConfigLogged) {
                            sConfigLogged = true;
                            log("ok: config transparentMode 0x"
                                    + Integer.toHexString(orig) + " -> 0x"
                                    + Integer.toHexString((orig & ~BLUR_TYPE_MASK) | BLUR_TYPE_LIQUID)
                                    + " (FROSTED->LIQUID bit)");
                        }
                    }
                }
            });
            log("ok: ConfigurationEx.getTransparentMode FROSTED->LIQUID installed");
        } catch (Throwable t) {
            log("skip: ConfigurationEx missing (" + t.getClass().getSimpleName() + ")");
        }
    }

    /** Hook the framework-side getters everything reads from (incl. via reflection). */
    private static void hookFwDataSource(ClassLoader cl) {
        try {
            Class<?> fw = XposedHelpers.findClass(UIMODE_FW_CLASS, cl);
            final int minDegree = DEFAULT_DEGREE;

            XposedBridge.hookAllMethods(fw, "getTransparentTexture", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!glassOn()) return;
                    int orig = (Integer) param.getResult();
                    if (orig != 2) {
                        param.setResult(2);
                        if (!sTextureLogged) {
                            sTextureLogged = true;
                            log("ok: framework texture " + orig + " -> 2 (LIQUID)");
                        }
                    }
                }
            });

            XposedBridge.hookAllMethods(fw, "getTransparentDegree", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!glassOn()) return;
                    int orig = (Integer) param.getResult();
                    if (orig < minDegree) {
                        param.setResult(minDegree);
                        if (!sDegreeLogged) {
                            sDegreeLogged = true;
                            log("ok: framework degree " + orig + " -> " + minDegree);
                        }
                    }
                }
            });

            log("ok: framework data-source hooks installed (minDegree=" + minDegree + ")");
        } catch (Throwable t) {
            log("skip: framework UiModeManagerEx missing ("
                    + t.getClass().getSimpleName() + ")");
        }
    }

    private static void hookMaterialBits(ClassLoader cl) {
        try {
            Class<?> cfg = XposedHelpers.findClass(CFG_CLASS, cl);
            XposedBridge.hookAllMethods(cfg, "getConfigTransparentModeMaterial", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (glassOn()) {
                        param.setResult(3);
                    }
                }
            });
            log("ok: getConfigTransparentModeMaterial -> 3 (LIQUID effect bit restored)");
        } catch (Throwable t) {
            log("skip: TransparencyFeatureConfig missing (" + t.getClass().getSimpleName() + ")");
        }
    }

    private static void hookMachineConfig(ClassLoader cl) {
        try {
            Class<?> mc = XposedHelpers.findClass(MACHINE_CLASS, cl);

            XposedBridge.hookAllMethods(mc, "getInstance", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!glassOn()) return;
                    Object inst = param.getResult();
                    if (inst == null) {
                        return;
                    }
                    try {
                        XposedHelpers.setIntField(inst, "transparencyMaterialLevel", 0);
                        XposedHelpers.setBooleanField(inst, "isSupportTransConfig", true);
                        XposedHelpers.setObjectField(inst, "machineType", null);
                    } catch (Throwable t) {
                        log("machine field fix failed: " + t);
                    }
                }
            });

            XposedBridge.hookAllMethods(mc, "isHighMachine", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (glassOn()) {
                        param.setResult(true);
                    }
                }
            });
            XposedBridge.hookAllMethods(mc, "isCompatMachine", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (glassOn()) {
                        param.setResult(false);
                    }
                }
            });

            try {
                final Class<?> mt = XposedHelpers.findClass(MACHINE_TYPE_CLASS, cl);
                final Object high = XposedHelpers.getStaticObjectField(mt, "HIGH");
                XposedBridge.hookAllMethods(mc, "getMachineType", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                    if (!glassOn()) return;
                    if (param.getResult() != high) {
                            param.setResult(high);
                        }
                    }
                });
            } catch (Throwable t) {
                log("getMachineType hook skipped: " + t);
            }

            log("ok: MachineConfig forced to HIGH tier");
        } catch (Throwable t) {
            log("skip: MachineConfig missing (" + t.getClass().getSimpleName() + ")");
        }
    }

    private static void hookStyleType(ClassLoader cl) {
        try {
            final Class<?> styleType = XposedHelpers.findClass(STYLE_TYPE_CLASS, cl);
            final Class<?> wrapper = XposedHelpers.findClass(UIMODE_WRAPPER_CLASS, cl);
            final Object liquid = XposedHelpers.getStaticObjectField(styleType, "LIQUID_GLASS_TRANSPARENT");
            final Object frosted = XposedHelpers.getStaticObjectField(styleType, "FROSTED_GLASS_TRANSPARENCY");

            XposedBridge.hookAllMethods(styleType, "getMaterialType", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!glassOn()) return;
                    if (param.getResult() != frosted) {
                        return;
                    }
                    try {
                        Object mode = XposedHelpers.callStaticMethod(wrapper, "getTransparentMode");
                        if (mode instanceof Integer && ((Integer) mode).intValue() == 1) {
                            param.setResult(liquid);
                            if (!sFlipLogged) {
                                sFlipLogged = true;
                                log("ok: material type flipped FROSTED -> LIQUID (transparency on)");
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
            log("ok: StyleType.getMaterialType FROSTED->LIQUID guard installed");
        } catch (Throwable t) {
            log("skip: StyleType missing (" + t.getClass().getSimpleName() + ")");
        }
    }

    private static void log(String msg) {
        Diag.log(msg);
    }

    /**
     * The panel background = wallpaper screenshot blurred by
     * SurfaceControlEx.screenshot() with HnStaticBlurParamsEx(radius, mask).
     * The mask (window_blur_view_mask_color_transparent, #9e1a1a1a) is the
     * dark veil covering 62% of the blurred wallpaper — the single biggest
     * opacity lever for the overall panel. Scale its alpha before the params
     * object is built. initBlurPara() re-invokes this before every capture,
     * so live tuning applies on the next pull-down.
     */
    private static void hookPanelVeil(ClassLoader cl) {
        // 幂等：这个函数会被调用两次 —— handleLoadPackage 一次、
        // initBulrParams 首次回调（kg-deferred 分支）再一次，而 LSPosed/Vector
        // 还可能对同一进程多次回调 handleLoadPackage。
        // 没这道门时的实测症状：日志里同时出现 `sat=1.44` 和 `sat=1.728`
        // （DEFAULT_SAT=1.2，1.2² 和 1.2³）—— 同一个 setBlurParams 被挂了
        // 两三次，饱和度和遮罩 alpha 被叠乘，画面比设定值透/艳得多，而且
        // 用户调滑条时变化量是非线性的（拖 10% 实际变 20%）。
        if (sPanelVeilHooked.get()) return;
        Class<?> sbv;
        try {
            sbv = XposedHelpers.findClass(SHOTBLUR_CLASS, cl);
        } catch (Throwable t) {
            Diag.note("skip: ShotBlurView missing (" + t.getClass().getSimpleName() + ")");
            return;   // 不置位，留待下一次（可能是 app CL）重试
        }
        if (!sPanelVeilHooked.compareAndSet(false, true)) return;
        try {

            XposedBridge.hookAllMethods(sbv, "setBlurParams", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length < 2 || !(param.args[1] instanceof Integer)) {
                        return;
                    }
                    // 补挂 initBulrParams（见 hookBlurParams 里的注入竞态说明）。
                    // ⚠ 必须放在 `if (!sysOn()) return;` **之前**：钩子挂载是基础设施，
                    // 不该被功能开关挡住 —— 否则用户关着系统线时这个补挂永远不会发生，
                    // 等他重新打开（需重启 SystemUI）新进程里照样挂不上，形成死循环。
                    if (!sBpcHooked.get()) {
                        try {
                            ClassLoader acl = param.thisObject.getClass().getClassLoader();
                            if (acl != null) hookBlurParams(acl);
                        } catch (Throwable ignored) {
                        }
                    }
                    if (!sysOn()) return;
                    refreshProps();
                    if (param.args.length >= 1 && param.args[0] instanceof Float
                            && Math.abs(sSatScale - 1.0f) > 0.001f) {
                        param.args[0] = (Float) param.args[0] * sSatScale;
                    }
                    int orig = (Integer) param.args[1];
                    if (sPanelCount < 4) {
                        sPanelCount++;
                        // 这是全模块唯一稳定可见的日志点（下拉时才打，避开启动期
                        // 的日志洪峰和每秒 50 条限流），所以把版本号和 initBulrParams
                        // 钩子的挂载状态都挂在这一行上 —— 排查时第一眼就能确认
                        // "手机上跑的是哪一版、精细调节的入口挂上没有"。
                        log("panel: v" + BuildConfig.VERSION_NAME
                                + "(" + BuildConfig.VERSION_CODE + ")"
                                + " bpc=" + (sBpcHooked.get() ? "on" : "OFF")
                                + " clock=" + (sKgClockHooked.get() ? "on" : "OFF")
                                + " sat=" + param.args[0]
                                + " mask=0x" + Integer.toHexString(orig)
                                + " x" + sMaskScale);
                    }
                    if (sMaskScale >= 0.999f) {
                        return;
                    }
                    param.args[1] = scaleAlpha(orig, sMaskScale);
                }
            });

            Diag.note("ok: ShotBlurView 面板遮罩钩子已挂上");
        } catch (Throwable t) {
            sPanelVeilHooked.set(false);
            log("skip: ShotBlurView hook failed (" + t.getClass().getSimpleName() + ")");
        }
    }

    /**
     * Apple-style bright rim: every glass surface (control-center tiles in
     * ControlCenterViewModel, volume panel in HnViewBlurControllerImpl,
     * notification cards) builds its edge light through the same 2-arg
     * constructor EdgeLightParamEx(angle=250, thickness=6.0f). Constructors
     * are never inlined away, so scaling the thickness argument here covers
     * all of them. Color is already opaque white, width is the only lever.
     */
    private static void hookEdgeRim(ClassLoader cl) {
        // 幂等：setColor 的钩子是**读原 alpha 再乘 rimK**，重复挂两次就是乘两次
        // （0x4D=77 → 77×1.4=108 → 108×1.4=151，边缘光越拖越亮）。
        // 构造器那个钩子有"只缩放原厂 6.0"的保护，天然幂等；真正危险的是 setColor。
        if (sRimHooked.get()) return;
        Class<?> el;
        try {
            el = XposedHelpers.findClass(
                    "com.hihonor.android.graphics.material.atomiceffect.EdgeLightParamEx", cl);
        } catch (Throwable t) {
            Diag.note("skip: EdgeLightParamEx missing (" + t.getClass().getSimpleName() + ")");
            return;
        }
        if (!sRimHooked.compareAndSet(false, true)) return;
        try {

            XposedBridge.hookAllConstructors(el, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length != 2 || !(param.args[1] instanceof Float)) {
                        return;
                    }
                    if (!Cfg.isOn(Cfg.K_GLASS)) return;
                    refreshProps();
                    // v2.6.10：这条线上有三项可调（角度 / 厚度 / 亮度），以前只挡了
                    // 总开关 —— 用户把「系统液态玻璃」关掉之后，边缘光的角度和精细
                    // 权重**照样在改**，跟"关掉=恢复系统默认"的承诺不符。
                    // 锁屏卡（sKgCardNow）归锁屏线管，其余归系统线管。
                    boolean lineOk = sKgCardNow ? sKgOn : sSysOn;
                    if (!lineOk) return;
                    // v2.5.2 边缘光角度（原厂固定 250，光永远从同一方向来）。
                    // 放在厚度逻辑之前：厚度那边有"scale≈1 就 return"的早退，
                    // 否则只调角度会被直接跳过。
                    try {
                        // 默认值必须走 fnDefPct（烧录入口），不能硬编码 250 ——
                        // 否则烧录把默认角度改成别的值时，这里永远取到 250。
                        int defAngle = Cfg.fnDefPct(Cfg.F_HL_ANGLE);
                        int wantAngle = Cfg.getInt(Cfg.F_HL_ANGLE, defAngle);
                        if (wantAngle != defAngle && param.args[0] instanceof Integer) {
                            param.args[0] = wantAngle;
                        }
                    } catch (Throwable ignored) {
                    }
                    // v2.5.16: kedge (keyguard-only multiplier). The wide
                    // refraction band the lock screen needs (x1.4) washed out
                    // the shade cards when applied globally — the user reads
                    // the shade as "a bit too bright". sKgCardNow is raised by
                    // the turnOnViewBlur gate around keyguard-card recipes.
                    // v2.5.2: 再乘一项精细权重（边缘光宽度）。
                    float scale = (sKgCardNow ? sKgEdgeScale : sEdgeScale)
                            * fineF(Cfg.F_HL_THICK);
                    if (Math.abs(scale - 1.0f) <= 0.001f) {
                        return;
                    }
                    float orig = (Float) param.args[1];
                    // some callers re-construct from an already-scaled param
                    // (copy pattern) — only scale the stock constant to
                    // avoid compounding (6.0 -> 7.8 -> 10.1 observed)
                    if (Math.abs(orig - 6.0f) > 0.01f) {
                        return;
                    }
                    param.args[1] = orig * scale;
                    if (sEdgeCount < 3) {
                        sEdgeCount++;
                        log("edge-rim: thickness " + orig + " -> " + (orig * scale)
                                + (sKgCardNow ? " (keyguard)" : ""));
                    }
                }
            });

            // folder-style rim: launcher folders use white@40-90% alpha while
            // SystemUI uses OPAQUE white (EDGE_COLOR=-1) — the source of the
            // "glowing border" complaint. Scale the alpha of setColor(int).
            // NOTE: LegacyBridge ignores hookMethod(single Method) — must
            // use hookAllMethods (verified via edge-rim logs).
            XposedBridge.hookAllMethods(el, "setColor", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length < 1 || !(param.args[0] instanceof Integer)) {
                        return;
                    }
                    if (!Cfg.isOn(Cfg.K_GLASS)) return;
                    refreshProps();
                    // 跟构造器那边同一个道理：系统线/锁屏线关掉时边缘光亮度必须原样。
                    boolean lineOk = sKgCardNow ? sKgOn : sSysOn;
                    if (!lineOk) return;
                    int c = (Integer) param.args[0];
                    int alpha = (c >>> 24) & 0xFF;
                    if (sRimCount < 4) {
                        sRimCount++;
                        log("rim: setColor arg=0x" + Integer.toHexString(c)
                                + " alpha=" + alpha + " scale=" + sRimScale);
                    }
                    // 注意这里是 abs 而不是 >=：sRimScale 的 DIY 上限是 2.0，
                    // 用 >=0.999 会把「放大」整段跳过（滑条拖过 50% 就没反应）。
                    // 同一个方法上面 5 行的 sEdgeScale 用的就是 abs，这里早先是笔误。
                    // v2.5.2：边缘光亮度再叠一层精细权重。
                    float rimK = sRimScale * fineF(Cfg.F_HL_ALPHA);
                    if (Math.abs(rimK - 1.0f) <= 0.001f || alpha == 0) {
                        return;
                    }
                    // 配合上面的 abs：现在 scale 可以 >1，alpha*scale 会超过 255，
                    // 不钳制会让 <<24 溢出到颜色位（边框反而变透明）。
                    int na = Math.max(0, Math.min(255, Math.round(alpha * rimK)));
                    param.args[0] = (na << 24) | (c & 0xFFFFFF);
                }
            });

            log("ok: EdgeLightParamEx rim-width installed");
        } catch (Throwable t) {
            sRimHooked.set(false);   // 允许下次重试
            Diag.note("skip: EdgeLightParamEx hook failed (" + t.getClass().getSimpleName() + ")");
        }
    }

    /**
     * v2.5 lockscreen fix (THE one). v2.4.2 diagnostics proved the whole
     * chain is alive on a static-photo wallpaper: glassType=2, liquid=true,
     * HnLiquidGlassMaterial applied to toolbox icons / cards / button
     * frames — but KeyguardWallpaper.getCurrentBlurBitmapForBg() returns
     * NULL every single call, so the material has no background to sample
     * and renders flat. Stock only produces that bitmap when the wallpaper
     * drawable is a BokehDrawable (magazine / dynamic); a plain photo
     * starves every consumer:
     *   - ViewBlur.blurIcon -> HnMaterialOpt.setBitmap(bmp, .25f, .25f)
     *   - KeyguardWallpaper -> blurWallpaperBitmap(ctx, bmp) (cards/panel)
     *   - setBluredTextBackground paths (footer text readability)
     *
     * Fix: AFTER-hook getCurrentBlurBitmapForBg; when the stock result is
     * null, synthesize it with the exact stock recipe (BokehDrawable.
     * initBlurTextBitmap): wallpaper drawable -> center-crop to screen ->
     * ViewBlur.blurBitmap(bmp, 1.0f, transparentRadius/4, maskColor).
     * Cached against the drawable identity + screen size; regenerated only
     * when the wallpaper actually changes.
     *
     * Gated by the keyguard glass switch. Pure addition — when stock
     * returns a bitmap (dynamic wallpaper) this hook does nothing.
     * No early init: fires only while the keyguard is already laid out.
     */
    private static void hookKeyguardBgBitmap(ClassLoader cl) {
        final Class<?> kgw;
        try {
            // loadClass (no <clinit>) — findClass forces initialization and
            // the OTA reshuffled KeyguardWallpaper's static deps, throwing
            // ExceptionInInitializerError; we only need the Class to hook a
            // method on it, never to run its static block.
            kgw = cl.loadClass("com.hihonor.keyguard.wallpaper.KeyguardWallpaper");
        } catch (Throwable t) {
            log("skip: kg-bg KeyguardWallpaper missing: " + t + " cl=" + cl.getClass().getName());
            return;
        }

        // hook 1: on-demand. Two jobs:
        //  a) stock returned a valid bitmap (BokehDrawable ready): copy it once
        //     into a hard reference so it survives AOD screen-off recycling,
        //     and fire the re-apply so views built during the null window
        //     (first ~2s after SystemUI start) get refreshed.
        //  b) stock returned null (early boot / post-recycle wake): serve the
        //     retained copy; if none yet, fall back to src-based synthesis.
        XposedBridge.hookAllMethods(kgw, "getCurrentBlurBitmapForBg", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!kgOn()) return;
                refreshProps();
                Bitmap stock = (Bitmap) param.getResult();
                if (sKeyguardBg && sKgBlurRadius > 0.01f) {
                    // v2.5.7 kbblur: the stock bokeh is baked far too blurry
                    // (plus a dark mask) for liquid cards — serve a light,
                    // tunable synthesis from the sharp wallpaper instead.
                    // Music keyguard keeps its stock look.
                    boolean music = false;
                    try {
                        music = XposedHelpers.getBooleanField(param.thisObject, "mInMusicWallpaper");
                    } catch (Throwable ignored) {
                    }
                    if (!music) {
                        try {
                            Bitmap light = ensureLightBitmap(param.thisObject, cl);
                            if (light != null && !light.isRecycled()) {
                                param.setResult(light);
                                return;
                            }
                        } catch (Throwable t) {
                            log("kg-bg light failed: " + t);
                        }
                    }
                    // synthesis unavailable -> fall through to stock behavior
                }
                if (stock != null && !stock.isRecycled()) {
                    if (sKeyguardBg) {
                        Bitmap serve = captureStockBitmap(stock, param.thisObject, cl);
                        if (serve != null && !serve.isRecycled()) {
                            param.setResult(serve);
                        }
                    }
                    return;
                }
                if (!sKeyguardBg) {
                    return;
                }
                try {
                    // music wallpaper intentionally returns null (isUseBlurNull)
                    if (XposedHelpers.getBooleanField(param.thisObject, "mInMusicWallpaper")) {
                        return;
                    }
                } catch (Throwable ignored) {
                }
                try {
                    if (sSynthBmp != null && !sSynthBmp.isRecycled()) {
                        param.setResult(sSynthBmp);
                        if (sKgBgServed < 10) {
                            sKgBgServed++;
                            log("kg-bg: cache-served (gen=" + sSynthGen + ")");
                        }
                    }
                } catch (Throwable t) {
                    log("kg-bg failed: " + t);
                }
            }
        });

        // hook 2: wallpaper-ready fallback. If the stock BokehDrawable path
        // never produces a bitmap, build one via src-based synthesis when
        // processWallpaperChange finishes assigning the wallpaper, then push
        // it through the stock re-apply sequence.
        XposedBridge.hookAllMethods(kgw, "processWallpaperChange", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!kgOn()) return;
                refreshProps();
                if (!sKeyguardBg) {
                    return;
                }
                try {
                    // light recipe when kbblur>0 (ensureLightBitmap itself
                    // fires the re-apply on re-synthesis; fireKgReapply
                    // dedupes by gen), otherwise re-apply the retained
                    // stock capture if we have one
                    Bitmap bmp = sKgBlurRadius > 0.01f
                            ? ensureLightBitmap(param.thisObject, cl)
                            : sSynthBmp;
                    if (bmp != null && !bmp.isRecycled()) {
                        fireKgReapply(bmp, param.thisObject, cl);
                    }
                } catch (Throwable t) {
                    log("kg-bg reapply failed: " + t);
                }
            }
        });
        log("ok: keyguard bg-bitmap synthesis installed (default off)");
    }

    /**
     * v2.5.4: switch toolbox icons (flashlight/camera) from the dead
     * HnMaterial route to the working ViewEx route.
     *
     * ToolBoxView calls the 3-arg ViewBlur.blurIcon(view, i, z), which builds
     * an HnLiquidGlassMaterial + GlassParamEx + edge light and hands the
     * captured blur bitmap to HnMaterialOpt.setBitmap. v2.5.3 diagnostics
     * proved all of that lands on the view — yet the framework Material
     * renderer never draws the glass body (only the thin ring survives).
     * The ViewEx system (setBluredTextBackground + BlurMode.BitmapIconBlur)
     * renders the same bitmap correctly — that is exactly what made the
     * v2.5.2 notification cards glassy.
     *
     * The stock itself keeps a ViewEx fallback for blurIcon in its catch
     * block: blurIcon(view, i, null, false, z). We take that overload but
     * pass z=true so updateBlurParams applies the stock readability recipe
     * (radius ~39px by transparency_value, saturation 3.0, brightness +0.4
     * for light backgrounds). Pure stock code paths; if the reflective call
     * throws we leave the original 3-arg invocation untouched.
     *
     * Gated by the keyguard glass switch. No field writes, no extra class
     * init (ViewBlur is only touched when the keyguard is already laying out
     * icons).
     */
    private static void hookKeyguardIconFallback(ClassLoader cl) {
        final Class<?> vb;
        try {
            vb = XposedHelpers.findClass("com.hihonor.keyguard.support.ViewBlur", cl);
        } catch (Throwable t) {
            log("skip: kg-icon ViewBlur missing (" + t.getClass().getSimpleName() + ")");
            return;
        }
        XposedBridge.hookAllMethods(vb, "blurIcon", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args.length != 3 || !(param.args[0] instanceof View)) {
                    return;
                }
                if (!kgOn()) return;
                refreshProps();
                if (!sKgIconFallback) {
                    return;
                }
                View v = (View) param.args[0];
                int i = (Integer) param.args[1];
                boolean z = (Boolean) param.args[2];
                trackIconTarget(v, i, z);
                try {
                    XposedHelpers.callStaticMethod(vb, "blurIcon",
                            new Class[]{View.class, int.class, Object.class, boolean.class, boolean.class},
                            v, Integer.valueOf(i), null, Boolean.TRUE, Boolean.valueOf(z));
                    if (sKgIconLogged < 10) {
                        sKgIconLogged++;
                        log("kg-icon: redirected to ViewEx route (z=" + z + ")");
                    }
                    param.setResult(null);
                } catch (Throwable t) {
                    log("kg-icon redirect failed: " + t.getClass().getSimpleName());
                }
            }
        });
        log("ok: keyguard icon ViewEx fallback installed (default off)");
    }

    private static final class IconTarget {
        final WeakReference<View> ref;
        final int blurVal;
        final boolean liquid;

        IconTarget(View v, int i, boolean z) {
            ref = new WeakReference<View>(v);
            blurVal = i;
            liquid = z;
        }
    }

    private static void trackIconTarget(View v, int i, boolean z) {
        for (int k = sIconTargets.size() - 1; k >= 0; k--) {
            View t = sIconTargets.get(k).ref.get();
            if (t == v) {
                return;
            }
            if (t == null) {
                sIconTargets.remove(k);
            }
        }
        if (sIconTargets.size() < 16) {
            sIconTargets.add(new IconTarget(v, i, z));
        }
    }

    /**
     * v2.5.5: startup race fix. ToolBoxView calls blurIcon ~1-2s after
     * SystemUI start, but the stock blur bitmap only appears ~1.4-2.4s in,
     * and stock never calls blurIcon again afterwards — icons blurred during
     * the null window stay flat forever (v2.5.4 run2: both waves at +1.3s/
     * +2.0s, capture at +2.4s, screenshot flat; run1: second wave landed
     * after capture, screenshot glassy). Re-run the ViewEx route on every
     * tracked icon view once a bitmap generation becomes available.
     */
    private static void reapplyIconTargets(ClassLoader cl) {
        if (!sKgIconFallback || sIconTargets.isEmpty()) {
            return;
        }
        int n = 0;
        try {
            Class<?> vb = XposedHelpers.findClass("com.hihonor.keyguard.support.ViewBlur", cl);
            for (int k = sIconTargets.size() - 1; k >= 0; k--) {
                IconTarget t = sIconTargets.get(k);
                View v = t.ref.get();
                if (v == null) {
                    sIconTargets.remove(k);
                    continue;
                }
                try {
                    XposedHelpers.callStaticMethod(vb, "blurIcon",
                            new Class[]{View.class, int.class, Object.class, boolean.class, boolean.class},
                            v, Integer.valueOf(t.blurVal), null, Boolean.TRUE, Boolean.valueOf(t.liquid));
                    n++;
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            log("kg-icon re-apply failed (" + t.getClass().getSimpleName() + ")");
            return;
        }
        if (n > 0) {
            log("kg-icon: re-applied to " + n + " view(s) (gen=" + sSynthGen + ")");
        }
    }

    /**
     * v2.5.12: returns the hard-ref copy to SERVE (replacing the stock result)
     * so the bitmap survives AOD screen-off recycling, and every consumer
     * (cards via blurWallpaperBitmap, buttons via the engine) sees the same
     * pixels. The copy is pixel-identical to the stock bokeh — NO veil, NO
     * re-tint: the v2.3 look is the stock bokeh under the stock keyguard card
     * recipe (thick_1 colors x mask prop), with folder refraction / tblur /
     * saturation applied by the generic initBulrParams hooks. Any darkening
     * must come from the card's own mask color, never from baking a flat veil
     * into the shared bitmap — a flat veil leaves the refraction band nothing
     * to bend and reads as plain fog. Cache key: stock instance.
     */
    private static Bitmap captureStockBitmap(Bitmap stock, Object kgwInst, ClassLoader cl) {
        try {
            if (sStockBmpRef != null && sStockBmpRef.get() == stock
                    && sSynthBmp != null && !sSynthBmp.isRecycled()) {
                return sSynthBmp;
            }
            Bitmap copy = stock.copy(Bitmap.Config.ARGB_8888, false);
            if (copy == null) {
                logMiss("stock copy failed");
                return null;
            }
            sSynthBmp = copy;
            sStockBmpRef = new WeakReference<Bitmap>(stock);
            sSynthSrcRef = null;
            sSynthGen++;
            if (sKgBgLogged < 6) {
                sKgBgLogged++;
                log("kg-bg: captured stock bitmap " + stock.getWidth() + "x" + stock.getHeight()
                        + " gen=" + sSynthGen);
            }
            fireKgReapply(copy, kgwInst, cl);
            return copy;
        } catch (Throwable t) {
            log("kg-bg capture failed: " + t);
            return null;
        }
    }

    private static void fireKgReapply(final Bitmap bmp, Object kgwInst, final ClassLoader cl) {
        if (bmp == null || sSynthGen == sKgBgAppliedGen) {
            return;
        }
        sKgBgAppliedGen = sSynthGen;
        Context ctx = null;
        try {
            ctx = (Context) XposedHelpers.getObjectField(kgwInst, "mContext");
        } catch (Throwable ignored) {
        }
        final Context fCtx = ctx;
        if (sKgHandler == null) {
            sKgHandler = new Handler(Looper.getMainLooper());
        }
        sKgHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    Class<?> kum = XposedHelpers.findClass(
                            "com.android.keyguard.KeyguardUpdateMonitor", cl);
                    XposedHelpers.callMethod(
                            XposedHelpers.callStaticMethod(kum, "getInstance"),
                            "blurBitmapChanged");
                } catch (Throwable t) {
                    log("kg-bg: blurBitmapChanged failed (" + t.getClass().getSimpleName() + ")");
                }
                try {
                    if (fCtx != null) {
                        Class<?> dep = XposedHelpers.findClass(
                                "com.hihonor.systemui.magic.HwDependency", cl);
                        Class<?> ctrlIf = XposedHelpers.findClass(
                                "com.hihonor.systemui.magic.blur.HnViewBlurController", cl);
                        Object ctrl = XposedHelpers.callStaticMethod(dep, "get", ctrlIf);
                        if (ctrl != null) {
                            XposedHelpers.callMethod(ctrl, "blurWallpaperBitmap", fCtx, bmp);
                        }
                    }
                } catch (Throwable t) {
                    log("kg-bg: blurWallpaperBitmap failed (" + t.getClass().getSimpleName() + ")");
                }
                // keyguardengine elements (toolbox buttons etc.) hold their own
                // state; the ONLY stock trigger that pushes a new blur bitmap
                // into them is a real wallpaper change. Late-ready bitmaps never
                // reach them, so buttons stay flat. Forward through the stock
                // onWallpaperChanged path: sets state + notifyStateChanged(16)
                // -> ViewRenderManager re-runs BlurManager on every registered
                // provider -> GlassEffectStrategy re-fetches the bitmap (through
                // our hooked getCurrentBlurBitmapForBg) and re-applies material.
                try {
                    if (sKgEngineNotify) {
                        Class<?> eng = XposedHelpers.findClass(
                                "com.hihonor.keyguard.colorpick.KeyguardBusinessDataProvider", cl);
                        Object inst = XposedHelpers.callStaticMethod(eng, "getInstance");
                        if (inst != null) {
                            XposedHelpers.callMethod(inst, "onWallpaperChanged", bmp);
                            log("kg-bg: engine onWallpaperChanged ok (gen=" + sSynthGen + ")");
                        }
                    }
                } catch (Throwable t) {
                    log("kg-bg: engine notify failed (" + t.getClass().getSimpleName() + ")");
                }
                reapplyIconTargets(cl);
                log("kg-bg: re-apply fired (gen=" + sSynthGen + ")");
            }
        });
    }

    /**
     * v2.5.7: light-synthesis bitmap served when kbblur>0. Built from the
     * SHARP wallpaper drawable (not the stock bokeh): quarter-res, gaussian
     * radius = kbblur in quarter-res space, no baked veil (v2.5.12 removed
     * the v2.5.9 veil — darkening belongs to the card's own mask color, a
     * baked veil flattens the glass). Cached against (src, w, h, radius);
     * any change re-synthesizes and re-applies.
     */
    private static Bitmap ensureLightBitmap(Object kgwInst, ClassLoader cl) {
        // getCurrentBlurBitmapForBg() itself reads mCurrentColorPickAndBlurWallpaper
        // (the BokehDrawable); mCurrentBackgroundWallpaper is null for most
        // user-set static wallpapers — reading it starved every synthesis.
        Drawable src = null;
        try {
            src = (Drawable) XposedHelpers.getObjectField(kgwInst, "mCurrentColorPickAndBlurWallpaper");
        } catch (Throwable ignored) {
        }
        if (src == null) {
            try {
                src = (Drawable) XposedHelpers.getObjectField(kgwInst, "mCurrentBackgroundWallpaper");
            } catch (Throwable ignored) {
            }
        }
        if (src == null) {
            logMiss("light src=null");
            return null;
        }
        Context ctx = null;
        try {
            ctx = (Context) XposedHelpers.getObjectField(kgwInst, "mContext");
        } catch (Throwable ignored) {
        }
        if (ctx == null) {
            logMiss("light ctx=null");
            return null;
        }
        android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        int w = dm.widthPixels;
        int h = dm.heightPixels;
        if (w <= 0 || h <= 0) {
            logMiss("light metrics " + w + "x" + h);
            return null;
        }
        // v2.5.13: replicate the shade panel bitmap recipe — saturation =
        // baked panel sat, veil = #1A1A1A at alpha 158 x baked panel mask
        // (43% at 0.7), the exact formula the panel's setBlurParams(1.2,
        // #9e1a1a1a x mask) bakes into its screenshot. Uses the baked
        // DEFAULT_ constants, not the live system-line values, so turning
        // the system line off cannot change the keyguard recipe. The veil
        // only works here because the bitmap underneath is lightly blurred
        // and structured — the refraction band still has content to bend
        // (unlike the stock bokeh, where any veil reads as dead fog).
        final int veilA = Math.max(0, Math.min(255,
                Math.round(158.0f * DEFAULT_MASK * sKgVeilScale)));
        final int veilColor = (veilA << 24) | 0x001A1A1A;
        final int satPct = Math.round(DEFAULT_SAT * 100.0f);
        if (sSynthBmp != null && !sSynthBmp.isRecycled()
                && sSynthSrcRef != null && sSynthSrcRef.get() == src
                && sSynthW == w && sSynthH == h
                && sSynthRadius == sKgBlurRadius
                && sSynthVeilA == veilA && sSynthSatPct == satPct) {
            return sSynthBmp;
        }
        Bitmap srcBmp = bokehGetBitmap(src);
        if (srcBmp == null) {
            srcBmp = drawableToBitmap(src);
        }
        if (srcBmp == null || srcBmp.isRecycled() || srcBmp.getWidth() <= 0) {
            logMiss("light src not drawable->bitmap (" + src.getClass().getName() + ")");
            return null;
        }
        // v2.5.14: crop with the DRAWN wallpaper geometry. The lock screen
        // draws this bitmap at getBokehScaleValueForLockScreen() zoom (often
        // 1.12x) around its center; a plain center-crop misregisters the
        // card content by that zoom and the glass reads as a pasted decal
        // instead of refracting what is behind it. Use the drawable's own
        // crop methods — the same ones the stock initBlurTextBitmap uses —
        // so the served bitmap is pixel-registered with the on-screen
        // wallpaper. Both return quarter-res output directly.
        Bitmap quarter = null;
        String cropPath = "fallback-crop";
        try {
            float kgScale = (Float) XposedHelpers.callMethod(src, "getBokehScaleValueForLockScreen");
            if (kgScale > 1.001f) {
                Bitmap q = (Bitmap) XposedHelpers.callMethod(
                        src, "scaleCropAndMatchScreenOnce", srcBmp, kgScale);
                if (q != null && !q.isRecycled() && q.getWidth() > 0) {
                    quarter = q;
                    cropPath = "scaleCrop x" + kgScale;
                }
            } else {
                Context dctx = (Context) XposedHelpers.getObjectField(src, "mContext");
                Bitmap q = dctx != null
                        ? (Bitmap) XposedHelpers.callMethod(src, "centerCropMatchScreen", dctx, srcBmp)
                        : null;
                if (q != null && !q.isRecycled() && q.getWidth() > 0) {
                    quarter = q;
                    cropPath = "centerCrop (scale=" + kgScale + ")";
                }
            }
        } catch (Throwable t) {
            logMiss("stock crop threw " + t.getClass().getSimpleName());
        }
        if (quarter == null) {
            Bitmap cropped = centerCropToScreen(srcBmp, w, h);
            if (cropped == null) {
                logMiss("light crop failed");
                return null;
            }
            quarter = Bitmap.createScaledBitmap(cropped,
                    Math.max(1, w / 4), Math.max(1, h / 4), true);
            if (cropped != quarter) {
                cropped.recycle();
            }
        }
        Bitmap blurred = null;
        try {
            int radius = Math.max(1, (int) (sKgBlurRadius + 0.5f));
            try {
                Class<?> vb = XposedHelpers.findClass(
                        "com.hihonor.keyguard.support.ViewBlur", cl);
                // stock blurBitmap(bitmap, saturation, radius, maskColor) —
                // same HnStaticBlurParamsEx machinery as the panel blur, so
                // sat + veil land exactly like the panel's own bitmap
                Bitmap out = (Bitmap) XposedHelpers.callStaticMethod(vb, "blurBitmap",
                        quarter, DEFAULT_SAT, radius, veilColor);
                // blurBitmap returns the INPUT on engine failure — only
                // accept a genuinely different blurred output
                if (out != null && out != quarter && !out.isRecycled()) {
                    blurred = out;
                }
            } catch (Throwable t) {
                logMiss("light stock blur threw " + t.getClass().getSimpleName());
            }
            if (blurred == null) {
                blurred = fastBlurFallback(quarter, radius);
                if (blurred != null && (veilA > 0 || Math.abs(DEFAULT_SAT - 1.0f) > 0.001f)) {
                    try {
                        Bitmap m = blurred.copy(Bitmap.Config.ARGB_8888, true);
                        if (m != null) {
                            Canvas cv = new Canvas(m);
                            if (Math.abs(DEFAULT_SAT - 1.0f) > 0.001f) {
                                Paint pt = new Paint();
                                ColorMatrix cm = new ColorMatrix();
                                cm.setSaturation(DEFAULT_SAT);
                                pt.setColorFilter(new ColorMatrixColorFilter(cm));
                                cv.drawBitmap(blurred, 0, 0, pt);
                            }
                            if (veilA > 0) {
                                cv.drawColor(veilColor);
                            }
                            blurred.recycle();
                            blurred = m;
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        } finally {
            if (quarter != null && quarter != blurred) {
                quarter.recycle();
            }
        }
        if (blurred == null || blurred.isRecycled()) {
            logMiss("light blur failed");
            return null;
        }
        sSynthBmp = blurred;
        sStockBmpRef = null;
        sSynthSrcRef = new WeakReference<Drawable>(src);
        sSynthW = w;
        sSynthH = h;
        sSynthRadius = sKgBlurRadius;
        sSynthVeilA = veilA;
        sSynthSatPct = satPct;
        sSynthGen++;
        if (sKgBgLogged < 6) {
            sKgBgLogged++;
            log("kg-bg: light synthesized " + blurred.getWidth() + "x" + blurred.getHeight()
                    + " r=" + (int) sKgBlurRadius + " sat=" + DEFAULT_SAT
                    + " veil=" + veilA + " gen=" + sSynthGen
                    + " [" + cropPath + ", src " + srcBmp.getWidth() + "x" + srcBmp.getHeight() + "]");
        }
        fireKgReapply(blurred, kgwInst, cl);
        return blurred;
    }

    private static void logMiss(String why) {
        Integer c = sMissCounts.get(why);
        int n = c == null ? 0 : c.intValue();
        if (n < 30) {
            sMissCounts.put(why, n + 1);
            log("kg-bg miss[" + n + "]: " + why);
        }
    }

    private static Bitmap bokehGetBitmap(Drawable d) {
        try {
            Object b = XposedHelpers.callMethod(d, "getBitmap");
            if (b instanceof Bitmap && !((Bitmap) b).isRecycled()) {
                return (Bitmap) b;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Bitmap drawableToBitmap(Drawable d) {
        if (d instanceof android.graphics.drawable.BitmapDrawable) {
            Bitmap b = ((android.graphics.drawable.BitmapDrawable) d).getBitmap();
            if (b != null && !b.isRecycled()) {
                return b;
            }
        }
        int dw = d.getIntrinsicWidth();
        int dh = d.getIntrinsicHeight();
        if (dw <= 0 || dh <= 0) {
            return null;
        }
        try {
            Bitmap out = Bitmap.createBitmap(dw, dh, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(out);
            d.setBounds(0, 0, dw, dh);
            d.draw(c);
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Bitmap centerCropToScreen(Bitmap src, int w, int h) {
        try {
            float scale = Math.max((float) w / src.getWidth(), (float) h / src.getHeight());
            int sw = Math.round(w / scale);
            int sh = Math.round(h / scale);
            int x = (src.getWidth() - sw) / 2;
            int y = (src.getHeight() - sh) / 2;
            Bitmap region = Bitmap.createBitmap(src, x, y, sw, sh);
            if (region == src) {
                region = Bitmap.createBitmap(src);
            }
            return Bitmap.createScaledBitmap(region, w, h, true);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Bitmap fastBlurFallback(Bitmap src, int radius) {
        try {
            // down/up-scale re-sampling approximates a gaussian for the
            // sample-bitmap purpose (glass reads it at 0.25 scale anyway)
            int ratio = Math.max(2, radius / 6);
            Bitmap small = Bitmap.createScaledBitmap(src,
                    Math.max(1, src.getWidth() / ratio), Math.max(1, src.getHeight() / ratio), true);
            return Bitmap.createScaledBitmap(small, src.getWidth(), src.getHeight(), true);
        } catch (Throwable t) {
            return null;
        }
    }

}
