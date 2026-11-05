package io.github.voreulch.honoradvanced;

import android.app.WallpaperManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 通知横幅文字自适应反色（222 / 3.2.92）。
 *
 * <p><b>为什么需要它</b>：SystemUI 的 HeadsUp 横幅背景是"实时模糊 + 半透明遮罩"
 * （{@code HunsStateProvider.processState()} → {@code initBulrParams(3, …)}），
 * 真实明暗取决于横幅背后的内容；而文字颜色只跟系统主题走（白天暗字、夜间亮字），
 * 两者互不感知。原厂 {@code NotificationViewWrapper.needsInversion()} 只处理
 * "夜间模式 + targetSdk<29 + 纯灰度实色 Drawable"，对模糊背景恒不触发
 * （{@code NotificationBackgroundView.getBackgroundColor()} 只认 ColorDrawable）。
 *
 * <p><b>主判据</b>：横幅布局稳定后截**整屏**（输出缩略到长边 192px），再把横幅矩形
 * 按比例映射到缩略图上取中位亮度。截整屏而不是按屏幕坐标裁横幅，是为了绕开
 * "sourceCrop 在旋转前后用哪套坐标"的歧义：输出位图始终是当前逻辑方向，
 * 行矩形也来自 {@code getLocationOnScreen()}，两者按比例映射即可，横屏不会截错区域。
 *
 * <p><b>无截图兜底</b>：半透明层一律合成在"卡片下方真实内容"（上次 {@code src=shot} 采样色，
 * 其次壁纸），不再合成到主题底色；预置阶段遮罩 α &lt; 0xCC 时直接不预置，等真实采样。
 * 依据写进日志（{@code preset_src=tint|mask|wallpaper|none}）。没有可靠依据就不改，绝不硬猜。
 *
 * <p><b>隐私</b>：截图像素只在后台线程内存里存在，{@code try/finally} 保证 recycle；
 * 日志只打聚合亮度与来源，不打印像素值或具体颜色。
 *
 * <p><b>恢复</b>：{@code setHeadsUp(false)}、行 {@code onDetachedFromWindow}、
 * 行变为非 VISIBLE、开关关闭时都写回原文字色 / 原图标 tint。
 */
public final class BannerContrastHook {

    /** 整屏缩略图长边上限：192px 足够分辨横幅区域，后台开销可忽略。 */
    private static final int SAMPLE_MAX_SIDE = 192;
    /** 布局稳定后再截屏，避开入场动画的缩放/透明帧。 */
    private static final long SAMPLE_DELAY_MS = 350L;
    /** 结果时效：从提交采样到回到主线程超过此值即丢弃（画面已变）。 */
    private static final long RESULT_MAX_AGE_MS = 1000L;
    /** 采样执行器的有界队列长度：满了丢本次提交，不再挤掉正在展示的旧任务。 */
    private static final int EXEC_QUEUE = 2;

    /**
     * 文字色判据：不做亮度门限二值化，直接比较白字/黑字在该背景色上的对比度并取更大者。
     * <p>两侧交点在背景亮度约 0.179，最优解最差也有 4.58:1，灰玻璃卡不会再被判成"浅底"。
     */

    /** 低于此对比度（WCAG AA 小字阈值 4.5）才替换文字颜色；横幅副标题/"刚刚"都是小字。 */
    private static final double CONTRAST_MIN = 4.5d;

    /** α 低于此值的像素视为无效（截屏失败时可能返回全透明位图）。 */
    private static final int PIXEL_MIN_ALPHA = 0x40;
    /** 遮罩 α ≥ 此值时认为"底色主要由遮罩决定"。 */
    private static final int MASK_OPAQUE_MIN_ALPHA = 0xCC;

    /** 调色板：与 notification_primary_text_color 的白天/夜间语义一致。 */
    private static final int TEXT_DARK = 0xFF000000;
    private static final int TEXT_LIGHT = 0xFFFFFFFF;

    /**
     * 本行当前判定，带来源标签：
     * <b>+1/+2</b> = 采样得出的亮字/暗字；<b>-1/-2</b> = setHeadsUp 同步预置的亮字/暗字。
     * 采样结果对负值（预置）无条件覆盖，避免"预置猜错后被 4.5 闸门钉死"。
     */
    private static final WeakHashMap<View, Integer> sMode = new WeakHashMap<>();
    /** 当前处于 heads-up 状态的行；通知栏复用后的行不在集合里，禁止重新上色。 */
    private static final java.util.Set<View> sHeadsUp = java.util.Collections.newSetFromMap(new WeakHashMap<View, Boolean>());
    /** 被改过的 TextView 原色（只在首次改色时记录，还原时写回）。 */
    private static final WeakHashMap<View, Integer> sOrigText = new WeakHashMap<>();
    /** 被改过的 ImageView 原 tint（原值可能为 null，另用 sTinted 记"改过"）。 */
    private static final WeakHashMap<View, ColorStateList> sOrigTint = new WeakHashMap<>();
    /** 文字带 ForegroundColorSpan 时保存原 CharSequence，还原时整段写回。 */
    private static final WeakHashMap<View, CharSequence> sOrigSpanText = new WeakHashMap<>();
    /**
     * 我们最后写进去的「剥掉 span」文本。
     *
     * <p>还原时只有当 {@code tv.getText()} 仍是它（说明期间没被系统 rebind 更新过）
     * 才写回 {@link #sOrigSpanText}；否则只还原颜色。否则横幅存活期间通知更新
     * （时钟每秒刷新、下载进度、已发送→已读）后，收起时会把最早那版文案写回，
     * 通知栏内容倒退。
     */
    private static final WeakHashMap<View, CharSequence> sAppliedText = new WeakHashMap<>();
    private static final java.util.Set<View> sTinted = java.util.Collections.newSetFromMap(new WeakHashMap<View, Boolean>());
    /** 每行上次采样到的卡片底色：rebind 后直接用它重刷，避免被荣耀刷回黑字。 */
    private static final WeakHashMap<View, Integer> sLastBg = new WeakHashMap<>();
    /** 每行上次触发采样的时间，做 1.2s 节流（布局/更新可能连续触发）。 */
    private static final WeakHashMap<View, Long> sShotAt = new WeakHashMap<>();
    /** 每行采样世代号：setHeadsUp / detach / 隐藏时递增，旧结果据此作废。 */
    private static final WeakHashMap<View, Integer> sGen = new WeakHashMap<>();

    /** 限频日志计数（每类最多 6 条，避免刷屏）。 */
    private static int sSkipLog = 0;
    private static int sBgZeroLog = 0;
    private static int sStaleLog = 0;
    private static int sDeleteLog = 0;
    private static int sNoPresetLog = 0;
    private static int sContentLog = 0;
    private static int sExecLog = 0;
    private static int sRotLog = 0;
    private static int sIconLog = 0;
    /** 本次 walk 的图标计数（主线程内使用，apply 前重置）。 */
    private static int sIconTinted = 0;
    private static int sIconSkipped = 0;
    /** 壁纸缓存是否已尝试过：成功/失败都只试一次，避免每条横幅重排一次全量解码（必修 3）。 */
    private static boolean sWallpaperTried = false;

    /**
     * 全局单线程 + 有界队列：同一时刻最多一个采样在飞；队列满时只丢本次提交。
     * 结果回到主线程前还会校验世代号与 {@link #RESULT_MAX_AGE_MS}。
     */
    private static final ExecutorService sExec =
            new java.util.concurrent.ThreadPoolExecutor(
                    1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
                    new java.util.concurrent.ArrayBlockingQueue<Runnable>(EXEC_QUEUE),
                    new java.util.concurrent.ThreadFactory() {
                        @Override
                        public Thread newThread(Runnable r) {
                            Thread t = new Thread(r, "HAS-HunContrast");
                            t.setDaemon(true);
                            return t;
                        }
                    },
                    new java.util.concurrent.RejectedExecutionHandler() {
                        @Override
                        public void rejectedExecution(Runnable r,
                                                      java.util.concurrent.ThreadPoolExecutor e) {
                            // 丢"本次提交"，保留队列里正在排队/展示中的旧任务，
                            // 避免把仍在展示那条横幅的唯一一次采样挤掉。
                            if (sDeleteLog < 6) {
                                sDeleteLog++;
                                Diag.note("hun-contrast: exec queue full, dropped newest");
                            }
                        }
                    });

    /** 结果统一回主线程用；不要用 {@code row.post}（后台线程调用时行为不同）。 */
    private static final Handler sMain = new Handler(Looper.getMainLooper());

    /**
     * detach 后延迟复查的计时器。
     *
     * <p>不用 {@code sMain.postDelayed}：本进程实测它只登记不执行；也不用
     * {@code row.postDelayed}：行已经 detach，若不再挂载就永远不跑。
     * 单线程调度器睡到点后走 {@code sMain.post}（这条路径已验证可用）。
     */
    private static final java.util.concurrent.ScheduledExecutorService sGate =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
                    new java.util.concurrent.ThreadFactory() {
                        @Override
                        public Thread newThread(Runnable r) {
                            Thread t = new Thread(r, "HAS-HunGate");
                            t.setDaemon(true);
                            return t;
                        }
                    });
    /** detach 复查窗口：荣耀在 setHeadsUp(true) 后十几毫秒就会 detach 再重挂载。 */
    private static final long DETACH_RECHECK_MS = 500L;

    private static int sLogCount = 0;
    private static boolean sInited = false;
    /** 本次选中/放弃的内容布局：private|public|children|none。 */
    private static String sContentSrc = "none";
    private static boolean sOverloadDumped = false;
    private static boolean sShotFailLogged = false;

    /** 壁纸平均亮度（0–1），-1 表示尚未采样。 */
    private static float sWallpaperLum = -1f;

    private BannerContrastHook() {
    }

    public static void init(ClassLoader cl) {
        if (!sInited) {
            sInited = true;
            try {
                final Class<?> row = cl.loadClass(
                        "com.android.systemui.statusbar.notification.row.ExpandableNotificationRow");
                java.util.Set<XC_MethodHook.Unhook> headsUpHooked =
                        XposedBridge.hookAllMethods(row, "setHeadsUp", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Object self = param.thisObject;
                            if (!(self instanceof View)) return;
                            View v = (View) self;
                            boolean z = param.args.length > 0 && Boolean.TRUE.equals(param.args[0]);
                            int gen = nextGen(v);
                            // 只清颜色，保留 sMode：滞回要跨 setHeadsUp 起作用（N6）。
                            restoreColors(v);
                            if (!z) {
                                sHeadsUp.remove(v); // 收起后回通知栏，禁止再染色（B）
                                return;
                            }
                            sHeadsUp.add(v);
                            if (!Cfg.isOn(Cfg.K_HUN_INVERT)) return;
                            // 同步预置：先给一个可读色，避免"先白后黑"的 350ms 空窗（M1）。
                            preset(v, gen);
                            schedule(v);
                        } catch (Throwable t) {
                            Diag.err("hun-contrast: hook callback failed", t);
                        }
                    }
                });
                // 行被移出窗口：写回原色，避免颜色跟着复用的 view 跑到下一条。
                XposedHelpers.findAndHookMethod(row, "onDetachedFromWindow", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object self = param.thisObject;
                        if (!(self instanceof View)) return;
                        final View v = (View) self;
                        nextGen(v); // detach 后在途结果一律作废（M2）
                        // 不立刻摘 sHeadsUp：荣耀在 setHeadsUp(true) 后十几毫秒就会 detach/重挂载，
                        // 立刻摘会把仍在展示的采样结果误判成 heads-up-gone；这里只还原颜色。
                        restoreColors(v);
                        // 兜底：若系统始终没走 setHeadsUp(false)，行会一直停在标记集合里，
                        // 被回收给通知栏普通行后 setVisibility / onLayout / onNotificationUpdated
                        // 三处门控全部放行 → 通知栏被染色（B 类回归）。
                        // 500ms 后仍是「不可见且未挂载」就摘掉；中间重挂载成功则保留。
                        final WeakReference<View> ref = new WeakReference<>(v);
                        try {
                            sGate.schedule(new Runnable() {
                                @Override
                                public void run() {
                                    sMain.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            try {
                                                View r = ref.get();
                                                if (r != null && !r.isShown()
                                                        && !r.isAttachedToWindow()) {
                                                    sHeadsUp.remove(r);
                                                }
                                            } catch (Throwable ignored) {
                                            }
                                        }
                                    });
                                }
                            }, DETACH_RECHECK_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
                        } catch (Throwable t) {
                            Diag.err("hun-contrast: detach recheck failed", t);
                        }
                    }
                });
                // 行被置为 GONE/INVISIBLE（未走 setHeadsUp(false) 的路径）也还原。
                // setVisibility 是全局钩子：没被我们改过的行直接返回，不参与任何清理（N5）。
                XposedBridge.hookAllMethods(View.class, "setVisibility", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object self = param.thisObject;
                        if (!(self instanceof View)) return;
                        // 先用弱表做零分配早退：绝大多数 View 从未被我们改过。
                        // sHeadsUp 里可能有"只有 heads-up、还没写 sMode"的行，也必须能清掉（必修 2）。
                        if (sMode.isEmpty() && sHeadsUp.isEmpty()) return;
                        if (!sHeadsUp.contains(self) && !sMode.containsKey(self)) return;
                        if (!self.getClass().getName().endsWith("ExpandableNotificationRow")) return;
                        if (param.args.length == 0 || !(param.args[0] instanceof Integer)) return;
                        if ((Integer) param.args[0] != View.VISIBLE) {
                            nextGen((View) self);
                            restoreColors((View) self);
                            // 不在这里摘 sHeadsUp：布局/重挂载会瞬时 INVISIBLE，
                        } else {
                            // 摘掉标记只能由 setHeadsUp(false) 或真正 detach 决定。
                            if (!sHeadsUp.contains(self)) return;
                            if (!Cfg.isOn(Cfg.K_HUN_INVERT)) return;
                            Integer bg = sLastBg.get(self);
                            if (bg != null && bg != 0) {
                                apply((View) self, bg, "reapply", rotationOf((View) self), false);
                            } else {
                                Integer g = sGen.get(self);
                                preset((View) self, g == null ? 0 : g);
                            }
                            maybeCapture((View) self);
                        }
                    }
                });
                // 横幅真正完成布局后直接采样：setHeadsUp 时 isShown() 还是 false，
                // 定时任务又可能丢失，布局点是唯一可靠的可视时机。
                XposedHelpers.findAndHookMethod(row, "onLayout",
                        boolean.class, int.class, int.class, int.class, int.class,
                        new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object self = param.thisObject;
                        if (!(self instanceof View)) return;
                        View v = (View) self;
                        if (!sHeadsUp.contains(v)) return;
                        if (!Cfg.isOn(Cfg.K_HUN_INVERT)) return;
                        maybeCapture(v);
                    }
                });
                // 展示期内容更新（同一条横幅换了内容/点了展开）：重新采样（N7）。
                java.util.Set<XC_MethodHook.Unhook> updated =
                        XposedBridge.hookAllMethods(row, "onNotificationUpdated", new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                Object self = param.thisObject;
                                if (!(self instanceof View)) return;
                                View v = (View) self;
                                // 只有当前确实是 heads-up 行才重采样（B）：收起后回通知栏的行不碰。
                                if (!sHeadsUp.contains(v)) return;
                                if (!Cfg.isOn(Cfg.K_HUN_INVERT)) return;
                                // 先用上次采样底色立即重刷（rebind 会把文字刷回原厂黑），
                                // 再排一次采样校正；重刷不受节流限制。
                                Integer bg = sLastBg.get(v);
                                if (bg != null && bg != 0) {
                                    apply(v, bg, "reapply", rotationOf(v), false);
                                }
                                schedule(v);
                            }
                        });
                if (updated == null || updated.isEmpty()) {
                    Diag.note("hun-contrast: onNotificationUpdated not found (no resample)");
                } else {
                    Diag.mount("BannerContrast.ExpandableNotificationRow.onNotificationUpdated",
                            updated.size(), updated.size());
                }
                Diag.mount("BannerContrast.ExpandableNotificationRow.setHeadsUp",
                        headsUpHooked == null ? 0 : headsUpHooked.size(),
                        headsUpHooked == null ? 0 : headsUpHooked.size());
            } catch (Throwable t) {
                Diag.err("hun-contrast: class missing", t);
            }
        }
    }

    /** 每次排期都递增世代号；旧世代的结果在 {@code apply} 前一律作废。 */
    private static int nextGen(View row) {
        int g = (sGen.containsKey(row) ? sGen.get(row) : 0) + 1;
        sGen.put(row, g);
        return g;
    }

    /** 350ms 兜底：布局触发是主路径，这里只防"布局早于 isShown"的极端情况。 */
    private static void schedule(final View row) {
        final WeakReference<View> ref = new WeakReference<>(row);
        row.postDelayed(new Runnable() {
            @Override
            public void run() {
                View v = ref.get();
                if (v != null) maybeCapture(v);
            }
        }, SAMPLE_DELAY_MS);
    }

    /** 行已真正可见时直接采样；每行 1.2s 内最多一次，避免连发消息时反复截屏。 */
    private static void maybeCapture(View row) {
        if (!row.isAttachedToWindow() || !row.isShown()) return;
        if (row.getWidth() <= 0 || row.getHeight() <= 0) return;
        long now = android.os.SystemClock.uptimeMillis();
        Long last = sShotAt.get(row);
        if (last != null && now - last < 1200L) return;
        sShotAt.put(row, now);
        Integer cur = sGen.get(row);
        capture(new WeakReference<>(row), cur == null ? 0 : cur);
    }

    // ==================== 采样 ====================

    /** 主线程：读取几何信息后丢给单线程执行器，绝不在 UI 线程截屏。 */
    private static void capture(WeakReference<View> ref, final int gen) {
        final View row = ref.get();
        if (row == null || !row.isAttachedToWindow() || !row.isShown()) {
            skipLog("not-attached");
            return;
        }
        if (row.getWidth() <= 0 || row.getHeight() <= 0) {
            skipLog("geometry");
            return;
        }
        if (!Cfg.isOn(Cfg.K_HUN_INVERT)) {
            restoreColors(row);
            return;
        }
        final int[] loc = new int[2];
        row.getLocationOnScreen(loc);
        final int screenW = row.getResources().getDisplayMetrics().widthPixels;
        final int screenH = row.getResources().getDisplayMetrics().heightPixels;
        final Rect rowRect = new Rect(loc[0], loc[1], loc[0] + row.getWidth(), loc[1] + row.getHeight());
        if (!rowRect.intersect(0, 0, screenW, screenH)) {
            skipLog("offscreen");
            return;
        }
        final int rotation = rotationOf(row);
        final View bgView = findBackgroundView(row);
        final boolean glass = Cfg.isOn(Cfg.K_GLASS) && Cfg.isOn(Cfg.K_SYSTEM);
        final android.content.Context ctx = row.getContext().getApplicationContext();
        final long submitAt = android.os.SystemClock.uptimeMillis();

        Runnable job = new Runnable() {
            @Override
            public void run() {
                Bitmap shot = null;
                int bg = 0;
                String src = "none";
                try {
                    shot = screenshot(ctx, screenW, screenH, rotation);
                    if (shot != null) {
                        Rect band = mapBand(shot, screenW, screenH, rowRect);
                        if (band != null) {
                            bg = medianColor(shot, band);
                            if (bg != 0) {
                                src = "shot";
                                // 记录真实卡片合成色，后续预置/兜底用它当"卡片下方内容"。
                                sLastCardGray = bg;
                            }
                        }
                    }
                } catch (Throwable t) {
                    Diag.err("hun-contrast: sample failed", t);
                } finally {
                    if (shot != null) {
                        try {
                            if (!shot.isRecycled()) shot.recycle();
                        } catch (Throwable ignored) {
                        }
                    }
                }
                if (bg == 0) {
                    int fb = fallbackBackground(bgView, glass, ctx);
                    if (fb != 0) {
                        bg = fb;
                        src = sFallbackSrc;
                    }
                }
                if (android.os.SystemClock.uptimeMillis() - submitAt > RESULT_MAX_AGE_MS) {
                    staleLog("expired");
                    return;
                }
                final int color = bg;
                final String where = src;
                sMain.post(new Runnable() {
                    @Override
                    public void run() {
                        // 采样期间可能又来了新消息（gen 会变）。只要这条仍是 heads-up，
                        // 采样结果对当前横幅依旧有效，不能因世代变化白白丢弃。
                        if (!sHeadsUp.contains(row)) {
                            staleLog("heads-up-gone");
                            return;
                        }
                        if (!row.isAttachedToWindow() || !row.isShown()) {
                            staleLog("detached");
                            restoreColors(row);
                            return;
                        }
                        if (!Cfg.isOn(Cfg.K_HUN_INVERT)) {
                            restoreColors(row);
                            return;
                        }
                        if (color == 0) {
                            // 采样与兜底都没拿到依据：清回上一帧 palette，绝不残留（N1）。
                            restoreColors(row);
                            if (sBgZeroLog < 24) {
                                sBgZeroLog++;
                                // 采样/兜底都没依据：保持原厂色，绝不凭主题猜成黑色。
                                Diag.note("hun-contrast: src=none (shot/fallback both empty), keep original");
                            }
                            return;
                        }
                        apply(row, color, where, rotation, false);
                    }
                });
            }
        };
        try {
            sExec.execute(job);
        } catch (Throwable t) {
            // 执行器拒绝/关闭时绝不能把异常抛回 SystemUI 主线程；只保留预置色。
            if (sExecLog < 6) {
                sExecLog++;
                Diag.note("hun-contrast: submit rejected, keep preset only");
            }
            Diag.err("hun-contrast: submit failed", t);
        }
    }

    /** 采样早退的统一限频日志（只打原因码，不打几何数值）。 */
    private static void skipLog(String reason) {
        if (sSkipLog >= 6) return;
        sSkipLog++;
        Diag.note("hun-contrast: skip sample, reason=" + reason);
    }

    private static void staleLog(String reason) {
        if (sStaleLog >= 6) return;
        sStaleLog++;
        Diag.note("hun-contrast: discard stale result, reason=" + reason);
    }

    private static int rotationOf(View v) {
        try {
            Display d = v.getDisplay();
            return d == null ? 0 : d.getRotation();
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /**
     * 调用荣耀扩展截图 API。优先 7 参 {@code (Rect;IIIIZI)}（SystemUI 现存调用原型），
     * 再退 4 参 {@code (Rect;III)}；首次调用把类是否存在、反射枚举到的全部重载打出，
     * 失败也打，避免"类能加载但重载不存在"时静默。
     */
    private static Bitmap screenshot(android.content.Context ctx, int screenW, int screenH, int rot) {
        final Rect full = new Rect(0, 0, screenW, screenH);
        int longSide = SAMPLE_MAX_SIDE;
        int outW;
        int outH;
        if (screenW >= screenH) {
            outW = longSide;
            outH = Math.max(32, (int) Math.round(longSide * (double) screenH / screenW));
        } else {
            outH = longSide;
            outW = Math.max(32, (int) Math.round(longSide * (double) screenW / screenH));
        }
        try {
            ClassLoader cl = ctx.getClassLoader();
            Class<?> c = Class.forName(
                    "com.hihonor.android.screenrecorder.activities.SurfaceControlEx", false, cl);
            Method m7 = null;
            Method m4 = null;
            if (!sOverloadDumped) {
                sOverloadDumped = true;
                StringBuilder sb = new StringBuilder();
                for (Method m : c.getDeclaredMethods()) {
                    if (!"screenshot".equals(m.getName())) continue;
                    if (sb.length() > 0) sb.append(" | ");
                    sb.append(sig(m));
                    Class<?>[] p = m.getParameterTypes();
                    if (p.length == 7 && p[0] == Rect.class
                            && p[1] == int.class && p[2] == int.class
                            && p[3] == int.class && p[4] == int.class
                            && p[5] == boolean.class && p[6] == int.class) {
                        m7 = m;
                    } else if (p.length == 4 && p[0] == Rect.class
                            && p[1] == int.class && p[2] == int.class && p[3] == int.class) {
                        m4 = m;
                    }
                }
                Diag.note("hun-contrast: SurfaceControlEx present, overloads=" + sb);
            } else {
                for (Method m : c.getDeclaredMethods()) {
                    if (!"screenshot".equals(m.getName())) continue;
                    Class<?>[] p = m.getParameterTypes();
                    if (p.length == 7 && p[0] == Rect.class
                            && p[1] == int.class && p[2] == int.class
                            && p[3] == int.class && p[4] == int.class
                            && p[5] == boolean.class && p[6] == int.class) {
                        m7 = m;
                    } else if (p.length == 4 && p[0] == Rect.class
                            && p[1] == int.class && p[2] == int.class && p[3] == int.class) {
                        m4 = m;
                    }
                }
            }
            if (m7 != null) {
                try {
                    m7.setAccessible(true);
                    // minLayer=0 / maxLayer=0 = 不限制图层；useIdentityTransform=false +
                    // rotation=当前方向 → 输出为当前逻辑方向的整屏缩略图。
                    Object o = m7.invoke(null, full, outW, outH, 0, 0, false, rot);
                    if (o instanceof Bitmap) return (Bitmap) o;
                } catch (Throwable t) {
                    if (!sShotFailLogged) {
                        sShotFailLogged = true;
                        Diag.note("hun-contrast: 7-arg shot failed " + sig(m7) + " : " + t);
                    }
                }
            }
            if (m4 != null) {
                try {
                    m4.setAccessible(true);
                    Object o = m4.invoke(null, full, outW, outH, rot);
                    if (o instanceof Bitmap) return (Bitmap) o;
                } catch (Throwable t) {
                    if (!sShotFailLogged) {
                        sShotFailLogged = true;
                        Diag.note("hun-contrast: 4-arg shot failed " + sig(m4) + " : " + t);
                    }
                }
            }
            if (m7 == null && m4 == null && !sShotFailLogged) {
                sShotFailLogged = true;
                Diag.note("hun-contrast: no usable screenshot overload (7-arg/4-arg absent)");
            }
        } catch (ClassNotFoundException e) {
            if (!sShotFailLogged) {
                sShotFailLogged = true;
                Diag.note("hun-contrast: SurfaceControlEx class not found (fallback only)");
            }
        } catch (Throwable t) {
            if (!sShotFailLogged) {
                sShotFailLogged = true;
                Diag.note("hun-contrast: shot unavailable (" + t + "), fallback only");
            }
        }
        return null;
    }

    private static String sig(Method m) {
        StringBuilder sb = new StringBuilder(m.getName()).append('(');
        Class<?>[] p = m.getParameterTypes();
        for (int i = 0; i < p.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(p[i].getSimpleName());
        }
        return sb.append(')').toString();
    }

    /** 把整屏缩略图上的横幅区域算出来（按逻辑屏幕尺寸等比映射）。 */
    private static Rect mapBand(Bitmap shot, int screenW, int screenH, Rect rowRect) {
        int bw = shot.getWidth();
        int bh = shot.getHeight();
        if (bw <= 0 || bh <= 0 || screenW <= 0 || screenH <= 0) return null;
        double sx = bw / (double) screenW;
        double sy = bh / (double) screenH;
        Rect r = new Rect(
                (int) Math.floor(rowRect.left * sx),
                (int) Math.floor(rowRect.top * sy),
                (int) Math.ceil(rowRect.right * sx),
                (int) Math.ceil(rowRect.bottom * sy));
        if (!r.intersect(0, 0, bw, bh) || r.width() < 2 || r.height() < 2) return null;
        return r;
    }

    /**
     * 取区域内像素的中位亮度并回推成不透明灰。
     *
     * <p>不取均值：横幅上的文字/图标像素会明显拉偏均值；中位数对少数派像素免疫。
     * 用"同亮度的灰"而不是中位 RGB，是为了让对比度计算与实际感知亮度一致。
     * α 过低的像素直接剔除，样本不足 1/4 视为截图无效（交给兜底）。
     */
    private static int medianColor(Bitmap bitmap, Rect region) {
        int w = region.width();
        int h = region.height();
        if (w <= 0 || h <= 0) return 0;
        int[] px = new int[w * h];
        try {
            bitmap.getPixels(px, 0, w, region.left, region.top, w, h);
        } catch (Throwable t) {
            // HardwareBitmap 不能直接 getPixels：复制一份软件位图再读。
            Bitmap copy = null;
            try {
                copy = bitmap.copy(Bitmap.Config.ARGB_8888, false);
                if (copy == null) return 0;
                copy.getPixels(px, 0, w, region.left, region.top, w, h);
            } catch (Throwable t2) {
                return 0;
            } finally {
                if (copy != null) {
                    try {
                        if (!copy.isRecycled()) copy.recycle();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        float[] lum = new float[px.length];
        int valid = 0;
        for (int i = 0; i < px.length; i++) {
            if (Color.alpha(px[i]) < PIXEL_MIN_ALPHA) continue;
            lum[valid++] = luminance(px[i]);
        }
        if (valid < px.length / 4) return 0;
        java.util.Arrays.sort(lum, 0, valid);
        float med = lum[valid / 2];
        return grayOf(med);
    }

    /** sRGB 相对亮度（WCAG 2.x 定义）。 */
    private static float luminance(int argb) {
        double r = linear(Color.red(argb) / 255.0);
        double g = linear(Color.green(argb) / 255.0);
        double b = linear(Color.blue(argb) / 255.0);
        return (float) (0.2126 * r + 0.7152 * g + 0.0722 * b);
    }

    private static double linear(double v) {
        return v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }

    /** 由相对亮度反推 sRGB 灰度值，再拼成不透明 ARGB。 */
    private static int grayOf(float lum) {
        double v = lum <= 0.0031308 ? lum * 12.92 : 1.055 * Math.pow(lum, 1.0 / 2.4) - 0.055;
        int g = (int) Math.round(Math.max(0.0, Math.min(1.0, v)) * 255.0);
        return Color.argb(255, g, g, g);
    }

    /** WCAG 对比度（1.0–21.0）：只取决于相对亮度。 */
    private static double contrast(int fg, int bg) {
        float a = luminance(fg);
        float b = luminance(bg);
        float hi = Math.max(a, b);
        float lo = Math.min(a, b);
        return (hi + 0.05) / (lo + 0.05);
    }

    /** 简单 SRC_OVER 合成（只在兜底路径用，不做完整 PorterDuff 矩阵）。 */
    private static int composite(int fg, int bg) {
        int a = Color.alpha(fg);
        if (a == 255) return fg | 0xFF000000;
        int r = (Color.red(fg) * a + Color.red(bg) * (255 - a)) / 255;
        int g = (Color.green(fg) * a + Color.green(bg) * (255 - a)) / 255;
        int b = (Color.blue(fg) * a + Color.blue(bg) * (255 - a)) / 255;
        return Color.argb(255, r, g, b);
    }

    // ==================== 兜底 ====================

    /** 上次兜底用的判据，仅用于日志。 */
    private static String sFallbackSrc = "none";
    /** 最近一次真实截图采样得到的卡片合成色；0 = 还没有（预置/兜底拿它当"下方内容"）。 */
    private static volatile int sLastCardGray = 0;
    /** 上次预置依据，仅用于日志。 */
    private static String sPresetSrc = "none";

    /**
     * 无截图时的保守判色。顺序：colorized tint → 遮罩 → 壁纸亮度。
     * 半透明层一律合成在"卡片下方真实内容"（上次采样色 / 壁纸）上，绝不用主题底色；
     * 没有可靠依据就返回 0（保持原厂），不猜。
     */
    private static int fallbackBackground(View bgView, boolean glass,
                                          android.content.Context ctx) {
        sFallbackSrc = "none";
        int backdrop = backdropGray();
        if (bgView != null) {
            try {
                boolean custom = XposedHelpers.getBooleanField(bgView, "mIsCustomColor");
                int tint = XposedHelpers.getIntField(bgView, "mTintColor");
                if (custom && Color.alpha(tint) > 0) {
                    sFallbackSrc = "tint";
                    return compositeMaybe(tint, backdrop);
                }
            } catch (Throwable ignored) {
            }
            try {
                Object mask = XposedHelpers.callStaticMethod(bgView.getClass(), "getMaskColor");
                if (mask instanceof Integer) {
                    int m = (Integer) mask;
                    if (Color.alpha(m) > 0) {
                        sFallbackSrc = "mask";
                        return compositeMaybe(m, backdrop);
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        if (!glass) {
            // 玻璃线未开：原厂卡是实底，没有截图依据就不改。
            return 0;
        }
        int wall = backdrop != 0 ? backdrop : wallpaperGray(ctx);
        if (wall != 0) {
            sFallbackSrc = "wallpaper";
            return wall;
        }
        sFallbackSrc = "none";
        return 0;
    }

    /**
     * 半透明前景合成到"真实下方内容"。
     * <p>α=255 时前景本身就是卡片色，与 backdrop 无关；α&lt;255 且没有 backdrop 时返回 0，
     * 宁可保持原厂也不拿主题底色硬猜（这就是灰玻璃卡被判成浅底、给黑字的根因）。
     */
    private static int compositeMaybe(int fg, int backdrop) {
        if (Color.alpha(fg) >= 255) return fg | 0xFF000000;
        if (backdrop == 0) return 0;
        return composite(fg, backdrop);
    }

    /** 卡片下方内容的灰度：上次真实采样色优先，其次壁纸；都没有返回 0。 */
    private static int backdropGray() {
        if (sLastCardGray != 0) return sLastCardGray;
        return cachedWallpaperGray();
    }

    /** 壁纸平均亮度（32×32 采样），结果进程内缓存；取不到返回 0。 */
    private static int wallpaperGray(android.content.Context ctx) {
        if (sWallpaperLum < 0f) {
            float lum = 0f;
            boolean ok = false;
            try {
                WallpaperManager wm = WallpaperManager.getInstance(ctx);
                Drawable d = wm.getDrawable();
                if (d instanceof BitmapDrawable) {
                    Bitmap bmp = ((BitmapDrawable) d).getBitmap();
                    double total = 0;
                    int cnt = 0;
                    int w = bmp.getWidth();
                    int h = bmp.getHeight();
                    int stepX = Math.max(1, w / 32);
                    int stepY = Math.max(1, h / 32);
                    for (int x = 0; x < w; x += stepX) {
                        for (int y = 0; y < h; y += stepY) {
                            int p = bmp.getPixel(x, y);
                            total += (0.299 * ((p >> 16) & 0xFF)
                                    + 0.587 * ((p >> 8) & 0xFF)
                                    + 0.114 * (p & 0xFF)) / 255.0;
                            cnt++;
                        }
                    }
                    if (cnt > 0) {
                        lum = (float) (total / cnt);
                        ok = true;
                    }
                }
            } catch (Throwable ignored) {
            }
            sWallpaperLum = ok ? lum : 0f;
            return ok ? grayOf(lum) : 0;
        }
        return sWallpaperLum > 0f ? grayOf(sWallpaperLum) : 0;
    }

    /** 在 row 子树里找 NotificationBackgroundView（HUN 背景遮罩就画在它身上）。 */
    private static View findBackgroundView(View root) {
        try {
            if (root.getClass().getName().endsWith("NotificationBackgroundView")) return root;
            if (!(root instanceof ViewGroup)) return null;
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                View c = g.getChildAt(i);
                if (c.getClass().getName().endsWith("NotificationBackgroundView")) return c;
                View deep = findBackgroundView(c);
                if (deep != null) return deep;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    // ==================== 着色 / 还原 ====================

    /**
     * @param preset true = setHeadsUp 时的同步预置；false = 截图/兜底回来的结果。
     *
     * <p>判色只做一件事：比较白字/黑字在该背景色上的对比度取更大者。
     * 预置（负数）只是首帧兜底，真实采样（正数）无条件覆盖它。
     */
    private static void apply(View row, int bgColor, String src, int rotation, boolean preset) {
        if (!Cfg.isOn(Cfg.K_HUN_INVERT)) {
            if (!preset) restoreColors(row);
            return;
        }
        if (!preset) {
            // 只有"仍在展示的 heads-up 行"才允许改色；收起回通知栏的行一律不碰（B）。
            if (!sHeadsUp.contains(row) || !row.isAttachedToWindow() || !row.isShown()) {
                restoreColors(row);
                return;
            }
            if (rotationOf(row) != rotation) {
                // 采样期间转屏：矩形映射已失效，丢弃本次结果（顺带 2）。
                if (sRotLog < 6) {
                    sRotLog++;
                    Diag.note("hun-contrast: rotation changed during sample, discarded");
                }
                return;
            }
        }
        try {
            float lum = luminance(bgColor);
            // 择优：只按背景色比较两侧对比度，不再走亮度门限/带内分支。
            double cLight = contrast(TEXT_LIGHT, bgColor);
            double cDark = contrast(TEXT_DARK, bgColor);
            boolean lightText = cLight >= cDark;
            Integer prev = sMode.get(row);
            // 防闪只认"上一次也是采样结论"；预置（负值）绝不阻断采样纠偏。
            if (prev != null && prev >= 0
                    && Math.abs(cLight - cDark) < 0.2d
                    && Math.min(cLight, cDark) >= CONTRAST_MIN) {
                lightText = Math.abs(prev) == 1;
            }
            int palette = lightText ? TEXT_LIGHT : TEXT_DARK;

            // 只有"真实截图采样成功"才染图标：预置、兜底回落、content 回退都不碰图标，
            // 避免彩色品牌图标被当单色 glyph 涂黑（白底横幅上会变成黑方块）。
            boolean withIcons = !preset && "shot".equals(src);
            sIconTinted = 0;
            sIconSkipped = 0;

            // 三套容器都尝试着色：荣耀可能把可见的那套藏在 public / children 里，
            // 只按"第一个 VISIBLE"猜会改到不可见副本（日志判白、屏幕仍黑）。
            int texts = 0;
            boolean any = false;
            View priv = headsUpChildOf(row, "mPrivateLayout");
            if (isVisible(priv)) {
                texts += walk(priv, palette, bgColor, withIcons);
                any = true;
            }
            View pub = headsUpChildOf(row, "mPublicLayout");
            if (isVisible(pub)) {
                texts += walk(pub, palette, bgColor, withIcons);
                any = true;
            }
            if (!any) {
                try {
                    Object ccObj = XposedHelpers.getObjectField(row, "mChildrenContainer");
                    if (ccObj instanceof View && isVisible((View) ccObj)) {
                        texts += walk((View) ccObj, palette, bgColor, withIcons);
                        any = true;
                        sContentSrc = "children";
                    }
                } catch (Throwable ignored) {
                }
            }
            if (!any) {
                // 布局尚未就绪或三种容器都不可见：直接放弃上色，绝不遍历整行（B）。
                if (sContentLog < 6) {
                    sContentLog++;
                    Diag.note("hun-contrast: apply skipped, content=null why=" + sContentSrc);
                }
                return;
            }
            if (sIconTinted + sIconSkipped > 0 && sIconLog < 24) {
                sIconLog++;
                // 只打数字：染了几个、跳过几个，便于确认彩色图标确实被跳过。
                Diag.note("hun-contrast: icons tint=" + sIconTinted + " skip=" + sIconSkipped);
            }
            // 只有真正落到控件上，才写"采样权威"（必修 2）：
            // 正数 = 采样判定，负数 = 同步预置（来源标签供下一轮采样判断可否无条件覆盖）。
            sMode.put(row, lightText ? (preset ? -1 : 1) : (preset ? -2 : 2));
            if (!preset) sLastBg.put(row, bgColor);

            if (sLogCount < 24) {
                sLogCount++;
                // 只打聚合亮度与来源，不打像素/颜色明细；preset_src 用来区分"预置依据"。
                Diag.log("hun-contrast: lum=" + String.format(java.util.Locale.US, "%.3f", lum)
                        + " text=" + (lightText ? "light" : "dark")
                        + " src=" + src + " rot=" + rotation
                        + " content=" + sContentSrc + " texts=" + texts
                        + (preset ? " preset=1" : "")
                        + " preset_src=" + sPresetSrc);
            }
        } catch (Throwable t) {
            Diag.err("hun-contrast: apply failed", t);
        }
    }

    /**
     * setHeadsUp(true) 时同步给一个可读初值（M1）。依据顺序与兜底一致：
     * 自定义 tint → 遮罩色 → 已缓存壁纸亮度（仅玻璃线）→ 都没有就保持原厂并打日志。
     * 主线程只读缓存；缓存为空时在后台补采一次（首条横幅仍用 tint/遮罩依据）。
     */
    private static void preset(View row, int gen) {
        Integer cur = sGen.get(row);
        if (cur == null || cur != gen) return;
        int bg = presetBackground(row);
        if (bg == 0) {
            if (sNoPresetLog < 24) {
                sNoPresetLog++;
                Diag.note("hun-contrast: preset unavailable, wait for sample, preset_src=" + sPresetSrc);
            }
            return;
        }
        apply(row, bg, "preset-" + sFallbackSrc, rotationOf(row), true);
    }

    private static int presetBackground(View row) {
        sFallbackSrc = "none";
        sPresetSrc = "none";
        boolean glass = Cfg.isOn(Cfg.K_GLASS) && Cfg.isOn(Cfg.K_SYSTEM);
        View bgView = findBackgroundView(row);
        // 预置的合成底只用"卡片下方真实内容"（上次采样色 / 壁纸），与白天/夜间无关。
        int backdrop = backdropGray();

        // 玻璃线首条横幅可能还没有壁纸缓存：在后台补一次，后续预置才有依据（E）。
        if (glass && sWallpaperLum <= 0f) seedWallpaper(row);

        if (bgView != null) {
            try {
                boolean custom = XposedHelpers.getBooleanField(bgView, "mIsCustomColor");
                int tint = XposedHelpers.getIntField(bgView, "mTintColor");
                int a = Color.alpha(tint);
                if (custom && a > 0) {
                    if (a < MASK_OPAQUE_MIN_ALPHA) {
                        // 半透明卡的真实观感取决于下方内容，主线程拿不到 → 不预置，
                        // 等 350ms 后的 src=shot（不拿主题底色猜，避免灰玻璃卡被判成浅底）。
                        sFallbackSrc = "tint(alpha=" + a + ")";
                        sPresetSrc = sFallbackSrc;
                        return 0;
                    }
                    sFallbackSrc = "tint";
                    sPresetSrc = "tint";
                    return compositeMaybe(tint, backdrop);
                }
            } catch (Throwable ignored) {
            }
            try {
                Object mask = XposedHelpers.callStaticMethod(bgView.getClass(), "getMaskColor");
                if (mask instanceof Integer) {
                    int maskColor = (Integer) mask;
                    int a = Color.alpha(maskColor);
                    if (a > 0) {
                        if (a < MASK_OPAQUE_MIN_ALPHA) {
                            sFallbackSrc = "mask(alpha=" + a + ")";
                            sPresetSrc = sFallbackSrc;
                            return 0;
                        }
                        sFallbackSrc = "mask";
                        sPresetSrc = "mask";
                        return compositeMaybe(maskColor, backdrop);
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        if (glass) {
            // 优先用上次真实采样到的卡色，没有才退壁纸；仍没有就不预置。
            int wall = backdropGray();
            if (wall != 0) {
                sFallbackSrc = sLastCardGray != 0 ? "last-card" : "wallpaper";
                sPresetSrc = sFallbackSrc;
                return wall;
            }
        }
        return 0;
    }

    /**
     * 后台补采壁纸亮度，避免主线程 I/O。
     * <p>用独立的一次性线程，不占 {@link #sExec}：壁纸解码可能几百 ms，
     * 混在截图执行器里会把本该 350ms 出结果的采样往后推，反而拉长错预置窗口（必修 3）。
     * <p>成败都只试一次：失败后 {@code sWallpaperLum} 停在 0，不再每条横幅重排。
     */
    private static void seedWallpaper(final View row) {
        if (sWallpaperTried) return;
        sWallpaperTried = true;
        final android.content.Context ctx = row.getContext().getApplicationContext();
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    wallpaperGray(ctx);
                } catch (Throwable ignored) {
                }
            }
        }, "HAS-HunWallpaper");
        t.setDaemon(true);
        try {
            t.start();
        } catch (Throwable t1) {
            Diag.err("hun-contrast: wallpaper seed failed", t1);
        }
    }

    /** 只读已缓存的壁纸亮度；未采样过返回 0（不在主线程解位图）。 */
    private static int cachedWallpaperGray() {
        return sWallpaperLum > 0f ? grayOf(sWallpaperLum) : 0;
    }

    private static boolean isVisible(View v) {
        return v != null && v.getVisibility() == View.VISIBLE;
    }

    private static View headsUpChildOf(View row, String layoutField) {
        try {
            Object layout = XposedHelpers.getObjectField(row, layoutField);
            if (layout == null) return null;
            Object child = XposedHelpers.callMethod(layout, "getHeadsUpChild");
            return child instanceof View ? (View) child : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int walk(View v, int palette, int bgColor, boolean withIcons) {
        if (v == null || v.getVisibility() != View.VISIBLE) return 0;
        if (v instanceof TextView) {
            TextView tv = (TextView) v;
            CharSequence cs = tv.getText();
            if (cs == null || cs.length() == 0) return 0;
            if (!tv.isShown()) return 0;
            int cur = tv.getCurrentTextColor();
            boolean hasFgSpan = false;
            if (cs instanceof android.text.Spanned) {
                android.text.style.ForegroundColorSpan[] fg = ((android.text.Spanned) cs).getSpans(
                        0, cs.length(), android.text.style.ForegroundColorSpan.class);
                if (fg.length > 0) {
                    // 实际渲染色来自 span，而不是 getCurrentTextColor()；判据必须用 span 色，
                    // 否则会出现"日志判白、屏幕仍旧黑"（荣耀横幅就是这种写法）。
                    hasFgSpan = true;
                    cur = fg[fg.length - 1].getForegroundColor();
                }
            }
            if (contrast(cur, bgColor) >= CONTRAST_MIN) return 0; // 已经可读
            if (!sOrigText.containsKey(tv)) sOrigText.put(tv, tv.getCurrentTextColor());
            if (hasFgSpan) {
                // 原文缓存要跟着内容走：横幅存活期间通知会被 rebind（时钟每秒刷新、
                // 下载进度、已发送→已读）。只在第一次记原文，收起时就会把最早那版
                // 文案写回通知栏，内容倒退。文本变了就覆盖。
                CharSequence orig = sOrigSpanText.get(tv);
                if (orig == null || !android.text.TextUtils.equals(orig, cs)) {
                    sOrigSpanText.put(tv, cs);
                }
                android.text.SpannableStringBuilder b = new android.text.SpannableStringBuilder(cs);
                for (android.text.style.ForegroundColorSpan s : b.getSpans(
                        0, b.length(), android.text.style.ForegroundColorSpan.class)) {
                    b.removeSpan(s);
                }
                tv.setText(b);
                // 记下"我们写进去的文本"：还原时用它判断系统有没有再改过内容。
                sAppliedText.put(tv, tv.getText());
            }
            tv.setTextColor(palette);
            return 1;
        }
        if (v instanceof ImageView) {
            if (withIcons) tintIcon((ImageView) v, palette);
            else sIconSkipped++;
            return 0;
        }
        int n = 0;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                n += walk(g.getChildAt(i), palette, bgColor, withIcons);
            }
        }
        return n;
    }

    /**
     * 只给"本来就是单色"的图标换 tint：判据只有"原本就带 ImageTintList"。
     * 大图（largeIcon / bigPicture）一律跳过；彩色品牌图标（自适应/位图）也跳过，绝不染色。
     */
    private static void tintIcon(ImageView iv, int palette) {
        int id = iv.getId();
        String name = "";
        try {
            if (id != View.NO_ID) name = iv.getResources().getResourceEntryName(id);
        } catch (Throwable ignored) {
        }
        // 大图（largeIcon / bigPicture）是照片，不参与 tint。
        if ("largeIcon".equals(name) || "bigPicture".equals(name) || "big_picture".equals(name)) {
            sIconSkipped++;
            return;
        }
        boolean mono = iv.getImageTintList() != null;
        if (!mono) {
            sIconSkipped++;
            return;
        }
        if (!sOrigTint.containsKey(iv)) sOrigTint.put(iv, iv.getImageTintList());
        iv.setImageTintList(ColorStateList.valueOf(palette));
        sTinted.add(iv);
        sIconTinted++;
    }

    /**
     * 把本行改过的颜色/tint 全部写回并清理缓存；开关关闭、横幅收起、行销毁都走这里。
     * 注意：<b>不</b>清 {@link #sMode}——上一次的判定要留到下一条横幅，滞回才真正生效（N6）。
     */
    private static void restoreColors(View row) {
        try {
            // 整行遍历：私有/公开/子容器三套都还原，避免只改到其中一套时残留。
            restoreWalk(row);
        } catch (Throwable t) {
            Diag.err("hun-contrast: restore failed", t);
        }
    }

    private static void restoreWalk(View v) {
        if (v == null) return;
        if (v instanceof TextView) {
            TextView tv = (TextView) v;
            Integer orig = sOrigText.remove(v);
            CharSequence origCs = sOrigSpanText.remove(v);
            CharSequence applied = sAppliedText.remove(v);
            // 只有文本仍是我们写进去的那版，才写回原文；系统已 rebind 过就只还原颜色，
            // 绝不把旧文案覆盖到新内容上（时钟横幅内容倒退的根因）。
            if (origCs != null && applied != null
                    && android.text.TextUtils.equals(tv.getText(), applied)) {
                tv.setText(origCs);
            }
            if (orig != null) tv.setTextColor(orig);
            return;
        }
        if (v instanceof ImageView) {
            // 原 tint 可能就是 null（纯单色图标），必须靠 sTinted 记住"改过"，
            // 否则 remove 返回 null 会被当成没改过，颜色残留。
            if (sTinted.remove(v)) {
                ColorStateList orig = sOrigTint.remove(v);
                ((ImageView) v).setImageTintList(orig);
            }
            return;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                restoreWalk(g.getChildAt(i));
            }
        }
    }
}
