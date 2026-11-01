package io.github.voreulch.honoradvanced;

import android.app.AndroidAppHelper;
import android.content.Context;
import android.database.ContentObserver;
import android.database.Cursor;
import android.os.Handler;
import android.os.HandlerThread;

import java.util.HashMap;
import java.util.Map;

/**
 * Switch store read by hooks. SettingsActivity writes shared_prefs/config.xml
 * (no root). On this device Vector lacks LSPosed's prefs serving and SELinux
 * MCS blocks direct cross-app reads, so values travel over the module's
 * exported CfgProvider.
 *
 * CRITICAL (v1.2.1, after the 2026-10-10 keyguard deadlock): hook callbacks
 * must NEVER do binder work. The old 2s-TTL design ran provider queries
 * synchronously inside synchronized isOn() — when a glass hook callback
 * landed on a SystemUI binder thread holding the Dependency lock, the
 * cross-process query (which first has to unfreeze the module app) held
 * that lock long enough to blow the stock keyguard lazy-init race into an
 * AB-BA deadlock ("cannot wake screen"). Now:
 *   - isOn() reads a volatile snapshot only; unconfirmed = default ON.
 *   - A single daemon thread preloads the snapshot once the provider is
 *     reachable. Glass scopes freeze it for the process lifetime — switch
 *     changes there apply after the scoped process restarts (which the
 *     settings UI already states).
 *   - Installer scopes (packageinstaller, system_server) additionally
 *     register a ContentObserver; SettingsActivity notifies the URI on
 *     every toggle, so changes propagate instantly without polling and
 *     without a process restart ("applies to the next install").
 */
public final class Cfg {

    public static final String PREFS_FILE = "config";

    // prefs keys shared with SettingsActivity
    public static final String K_GLASS = "glass";         // liquid glass master (incl. system-level base render: HIGH tier, LIQUID bits)
    public static final String K_SYSTEM = "system";       // status-bar notifications + control-center look tuning
    public static final String K_KEYGUARD = "keyguard";   // lockscreen buttons + cards (merged)
    public static final String K_GALLERY = "gallery";     // gallery photo page
    public static final String K_BANNER = "banner";       // heads-up banner (来通知时弹出的那条)
    public static final String K_DESK = "desk";           // launcher 卡片玻璃（v2.6.20）
    public static final String K_PURIFY = "purify";       // installer purify master
    public static final String K_INFOCARD = "infocard";   // package info card
    public static final String K_DOWNGRADE = "downgrade"; // allow downgrade installs
    public static final String K_NODUCK = "noduck";       // misc: block notification audio ducking
    public static final String K_HOTTEMP = "hottemp";     // misc: suppress the system high-temperature popups
    public static final String K_DIAG = "diag";           // about: verbose diagnostics into the LSPosed log
    // ===== 安全组件（v3.2.0）=====
    //
    // 这几个都是"解除系统限制"型开关，默认全关、需要用户显式打开。
    public static final String K_NOSECURE = "nosecure";   // 允许截屏/录屏（清 FLAG_SECURE）
    public static final String K_NOCLOUD = "nocloud";     // 屏蔽性能/温控策略的云控下发
    public static final String K_NOFRAUD = "nofraud";     // 屏蔽反诈组件的数据采集与上报
    /**
     * 清理云端配置残留（一次性操作，不是常驻开关）。
     *
     * 删除 /data/cota/para/ 下三份性能相关配置（cpuconfig / pg_config / thermal_config）。
     * 需要 ROOT。执行完 UI 会自动把它关回去 —— 它代表"动作"而不是"状态"。
     */
    public static final String K_PURGECOTA = "purgecota";

    public static final String[] KEYS = {
            K_GLASS,
            K_NOSECURE, K_NOCLOUD, K_NOFRAUD, K_PURGECOTA, K_SYSTEM, K_KEYGUARD, K_GALLERY, K_BANNER, K_DESK,
            K_PURIFY, K_INFOCARD, K_DOWNGRADE, K_NODUCK, K_HOTTEMP, K_DIAG,
    };

    /**
     * 作用域清单（与 res/values/arrays.xml 的 xposed_scope 一致）。
     * 两处用途：CfgProvider 校验 probe 上报来源；UI 判断某个开关依赖的进程有没有跑起来。
     */
    public static final String[] SCOPES = {
            "com.android.systemui",
            "com.hihonor.android.launcher",
            "com.hihonor.desktop.systemui",
            "com.hihonor.photos",
            "com.android.packageinstaller",
            "com.hihonor.powergenie",
            "com.hihonor.iaware",
            "com.hihonor.securityserver",
            "android",
    };

    // ===== 分控件强度（v2.5.0）=====
    //
    // 原生的 initBulrParams 给四类控件**分别**赋了亮度/饱和度/遮罩资源：
    //   通知卡    brightness 0.08  saturation 1.2
    //   控制中心瓦片 brightness 0.12  saturation 1.1
    //   滑块      brightness 0.12  saturation 1.1
    //   圆形按钮   brightness 0.08  saturation 1.1
    // 而旧版只有一个 mask 乘数一把刷到所有控件上 —— 想单独压暗通知卡，滑块和
    // 按钮会跟着一起变，这就是"一刀切"。
    //
    // 这里给每类控件一个 0-200 的强度百分比（100 = 保持原生配方），同时作用于
    // 该类的遮罩 alpha、亮度、饱和度三项。默认全 100 时行为与旧版完全一致。
    // v2.5.7: 分区域粗调（tune_*）已并入精细调节 —— 既然每个字段都能单独拖，
    // 再留一层"整类缩放"只会让同一件事有两个旋钮，反而困惑。
    public static final String[] TUNE_KEYS = {};

    /**
     * 分区域强度的**出厂默认值**（百分比，100 = 原厂）。
     *
     * <p>这是给"烧录"留的口子：用户把所有滑条调到满意之后，把想固化的值逐项写进
     * 这里重新打包，装上去就是那套观感；用户仍然可以在设置页继续微调（滑条值存
     * prefs，优先于这里的默认）。全部返回 100 表示跟随荣耀原厂。
     */
    public static int tuneDefPct(String key) {
        return 100;   // 粗调层已移除，保留签名只为兼容旧调用点
    }

    /**
     * 精细调节的**出厂默认值**（百分比，100 = 原厂）。
     *
     * <p>同 {@link #tuneDefPct(String)}，烧录时在这里逐项写死。
     * 注意 {@link #F_HL_ANGLE} 是角度而不是百分比，它的出厂值单独写在下面。
     */
    /** v3.0.5「高光语义反转」的一次性迁移标记。 */
    public static final String MIG_HL_V2 = "hl_semantic_v2";

    /**
     * v3.0.5：四个高光键的语义反转（旧 1 = 关闭高光 → 新 1 = 开启高光），
     * 把老用户存下来的值翻转一次。
     *
     * <p><b>⚠ 必须在 {@code CfgProvider.onCreate()} 里调，不能只放在
     * SettingsActivity</b> —— 那个方法只有用户打开模块界面才会跑。老用户升级后
     * 如果一直没打开界面，hook 读到的还是旧语义的值，四个高光开关会整个反掉
     * （本来关了的变成全开、本来开着的变成全灭）。
     *
     * <p>幂等：靠 {@link #MIG_HL_V2} 标记；新装用户 prefs 里没有这四个键，
     * 循环里 {@code contains} 直接跳过，不会被写脏。
     *
     * <p>不主动拿 Context —— 这个类在 system_server 里也会被加载，
     * 那里没有 SharedPreferences 可用。由调用方把实例传进来。
     */
    public static void migrateHlSemantics(android.content.SharedPreferences p) {
        if (p == null) return;
        try {
            if (p.getBoolean(MIG_HL_V2, false)) return;
            android.content.SharedPreferences.Editor ed = p.edit();

            // 旧语义：1 = 关闭这一类高光，0 = 不关（默认，原厂全开）
            int oldOn = intOf(p, F_HL_ON);
            int oldCc = intOf(p, F_HL_CC);
            int oldBtn = intOf(p, F_HL_BTN);
            int oldSl = intOf(p, F_HL_SL);

            if (oldOn != 0) {
                // 用户旧版勾过「关闭全部高光」——新语义下等价于**四个全关**。
                //
                // ⚠ 这里绝不能逐个翻转。逐个翻会得到「hl_on=0（关）+ 三个分项=1（开）」
                // 的自相矛盾状态；hook 按"任一开着就算开"判断，结果反而是**全亮** ——
                // 用户明明全关了，升级后却全开。
                ed.putInt(F_HL_ON, 0);
                ed.putInt(F_HL_CC, 0);
                ed.putInt(F_HL_BTN, 0);
                ed.putInt(F_HL_SL, 0);
            } else {
                // 全局原本是开的 -> 全局置开，三个分项逐个翻转
                ed.putInt(F_HL_ON, 1);
                if (p.contains(F_HL_CC)) ed.putInt(F_HL_CC, oldCc != 0 ? 0 : 1);
                if (p.contains(F_HL_BTN)) ed.putInt(F_HL_BTN, oldBtn != 0 ? 0 : 1);
                if (p.contains(F_HL_SL)) ed.putInt(F_HL_SL, oldSl != 0 ? 0 : 1);
            }
            ed.putBoolean(MIG_HL_V2, true);
            ed.commit();
        } catch (Throwable ignored) {
        }
    }

    /** 读 int，类型不对就按 0（"没关"）处理。极老版本可能存成 boolean。 */
    private static int intOf(android.content.SharedPreferences p, String k) {
        try {
            return p.getInt(k, 0);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** v3.2.0「烧录配方落地」的一次性标记。 */
    public static final String MIG_BAKED = "baked_v1";

    /**
     * **原厂**默认值（不是烧录配方）。
     *
     * <p>和 {@link #fnDefPct} 的区别要分清：
     * <ul>
     *   <li>{@code fnDefPct} = <b>本品出厂观感</b>（主人烧录的那套，不是原厂）</li>
     *   <li>{@code factoryDefPct} = <b>系统原厂值</b>（100 = 不改动、角度 250、开关 0/1）</li>
     * </ul>
     * 老用户缺键时补的是**这个**，不能补烧录值 —— 否则他就成了"半套我的配方"。
     */
    public static int factoryDefPct(String key) {
        if (F_HL_ANGLE.equals(key)) return 250;
        if (F_FORCE_LIQUID.equals(key) || F_CC_EXPAND.equals(key)) return 0;
        if (F_HL_ON.equals(key) || F_HL_CC.equals(key)
                || F_HL_BTN.equals(key) || F_HL_SL.equals(key)) {
            return 1;   // 原厂四类高光全开
        }
        return 100;
    }

    /**
     * v3.2.0：把「烧录配方」按新装 / 老用户两种情况落地。
     *
     * <p><b>规则（用户定死）</b>：
     * <ul>
     *   <li>全新安装（prefs 里一个 {@code fn_} 键都没有）→ 把烧录配方写进去，开箱即用</li>
     *   <li><b>老用户只要调过任何一项</b>（哪怕只有一根滑条）→ 缺的键补**原厂值**，
     *       绝不把烧录配方掺进去。他调过的那套完整保留</li>
     * </ul>
     *
     * <p>为什么必须显式落地、不能只靠 {@code fnDefPct} 兜底：一旦某个键在 prefs 里不存在，
     * 读出来就是烧录值 —— 老用户没调过的项会自动变成我的观感，形成"半套我的配方"。
     * 这种混合状态最难排查（用户以为是自己的设置，其实一半是别人的）。
     */
    public static void migrateBakedParams(android.content.SharedPreferences p) {
        if (p == null) return;
        try {
            if (p.getBoolean(MIG_BAKED, false)) return;

            // 判据：prefs 里是否存在任何一个 fn_ 键 = 他是不是调过
            // 判据：有没有用户自己动过的精细参数。
            // ⚠ 必须排除高光那四个键 —— migrateHlSemantics 会无条件写 fn_hl_on，
            // 把它算进来的话，全新安装也会被误判成"老用户调过"。
            boolean userHasParams = false;
            for (String k : p.getAll().keySet()) {
                if (k == null || !k.startsWith("fn_")) continue;
                if (F_HL_ON.equals(k) || F_HL_CC.equals(k)
                        || F_HL_BTN.equals(k) || F_HL_SL.equals(k)) continue;
                userHasParams = true;
                break;
            }

            android.util.Log.d("HAS-Baked", "userHasParams=" + userHasParams
                    + " prefsKeys=" + p.getAll().size()
                    + " sample_blur_card=" + Cfg.fnDefPct(F_BLUR_CARD));
            android.content.SharedPreferences.Editor ed = p.edit();
            if (userHasParams) {
                for (String k : FN_KEYS) {
                    if (!p.contains(k)) ed.putInt(k, factoryDefPct(k));
                }
            } else {
                for (String k : FN_KEYS) ed.putInt(k, fnDefPct(k));
            }
            ed.putBoolean(MIG_BAKED, true);
            ed.commit();
        } catch (Throwable ignored) {
        }
    }

    public static int fnDefPct(String key) {
        if (F_HL_ANGLE.equals(key)) {
            return 250;   // 边缘光方向，原厂 250°
        }
        // ---- 开关型默认值 ----
        //
        // v3.0.5 语义反转：四个高光键从「1 = 关闭高光」改成「1 = 开启高光」。
        // 原厂是四类高光**全开**的，所以默认值必须是 1，不是 0。
        // 判定侧同步改了：hook 里现在是 `getInt(key, 1) == 0` 才关。
        //
        // ⚠ 老用户的数据由 migratePrefs 一次性翻转（见那个方法里的 hl_semantic_v2 标记），
        // 不翻的话他们升级后四个开关会全部反掉。
        if (F_HL_ON.equals(key) || F_HL_CC.equals(key) || F_HL_BTN.equals(key)
                || F_HL_SL.equals(key)) {
            return 1;
        }
        // 开关型默认值：锁屏控制中心展开，出厂关。
        // （强制液态已并入下面的烧录配方 —— 主人那台是开着的。）
        if (F_CC_EXPAND.equals(key)) {
            return 0;
        }

        // ===== 出厂配方（v3.2.0 烧录）=====
        //
        // 这一版的"100%"不再是"系统原厂"，而是**主人自己调好的那套观感**。
        // 也就是说新装用户打开就是下面这组值，不用自己再拖一遍。
        //
        // 数据来源：从共享 prefs 里逐项导出（fn_* -> 值），只列非 100 的项
        // （100 本来就是"不改动"，不用占位）。改这里等于改所有人的出厂观感，
        // 动之前先想清楚。
        switch (key) {
                case F_BLUR_CARD: return 80;
                case F_BN_BLUR: return 90;
                case F_BN_MASK: return 70;
                case F_BN_OVL: return 80;
                case F_BTN_MASK: return 60;
                case F_BTN_OVL: return 60;
                case F_CC_EXPAND: return 0;
                case F_CC_MASK: return 60;
                case F_CC_OVL: return 60;
                case F_DESK_RADIUS: return 130;
                case F_FORCE_LIQUID: return 1;
                case F_HL_ANGLE: return 250;
                case F_HL_BTN: return 1;
                case F_HL_CC: return 1;
                case F_HL_ON: return 1;
                case F_HL_SL: return 1;
                case F_KG_BTN: return 50;
                case F_KG_BTN_DEP: return 140;
                case F_KG_BTN_DIS: return 110;
                case F_KG_BTN_EDGE: return 120;
                case F_KG_ICON: return 50;
                case F_KG_ICON_DEP: return 130;
                case F_KG_ICON_DIS: return 130;
                case F_KG_ICON_EDGE: return 120;
                case F_KG_TXT_DEP: return 130;
                case F_KG_TXT_DIS: return 130;
                case F_KG_TXT_EDGE: return 120;
                case F_KG_WIDGET: return 60;
                case F_KL_CORNER: return 80;
                case F_KL_MASK: return 40;
                case F_KL_OVL: return 60;
                case F_MULTI_MASK: return 80;
                case F_NTF_CORNER: return 80;
                case F_NTF_MASK: return 50;
                case F_NTF_OVL: return 60;
                case F_OPT_DEPTH: return 130;
                case F_OPT_DISP: return 110;
                case F_OPT_REFRACT: return 160;
                case F_OPT_THICK: return 120;
                case F_SL_MASK: return 60;
                case F_SL_OVL: return 60;
            default:
                break;
        }
        return 100;
    }

    // ===== 精细调节（v2.5.2）=====
    //
    // 逐项对应 BlurParametersConfig 里的渲染字段。tune_* 是"整类粗调"，fn_* 是
    // "单项细调"，两者相乘 —— 粗调把握整体观感，细调单独抠某一项。
    // 百分比项默认 100（原厂值），0-200 可调；开关项默认 0（跟随原厂）。
    public static final String F_NTF_MASK = "fn_ntf_mask";
    public static final String F_NTF_OVL = "fn_ntf_ovl";
    public static final String F_NTF_BRIGHT = "fn_ntf_bright";
    public static final String F_NTF_SAT = "fn_ntf_sat";
    public static final String F_NTF_CORNER = "fn_ntf_corner";
    public static final String F_NTFB_MASK = "fn_ntfb_mask";
    public static final String F_NTFB_OVL = "fn_ntfb_ovl";
    public static final String F_NTFB_BRIGHT = "fn_ntfb_bright";
    public static final String F_CC_MASK = "fn_cc_mask";
    public static final String F_CC_OVL = "fn_cc_ovl";
    public static final String F_CC_BRIGHT = "fn_cc_bright";
    public static final String F_CC_SAT = "fn_cc_sat";
    public static final String F_CC_CORNER = "fn_cc_corner";
    public static final String F_BTN_MASK = "fn_btn_mask";
    public static final String F_BTN_OVL = "fn_btn_ovl";
    public static final String F_BTN_BRIGHT = "fn_btn_bright";
    public static final String F_BTN_SAT = "fn_btn_sat";
    public static final String F_BTN_CORNER = "fn_btn_corner";
    public static final String F_SL_MASK = "fn_sl_mask";
    public static final String F_SL_OVL = "fn_sl_ovl";
    public static final String F_SL_BRIGHT = "fn_sl_bright";
    public static final String F_SL_SAT = "fn_sl_sat";
    public static final String F_SL_CORNER = "fn_sl_corner";
    public static final String F_OPT_REFRACT = "fn_opt_refract";
    public static final String F_OPT_DEPTH = "fn_opt_depth";
    public static final String F_OPT_THICK = "fn_opt_thick";
    public static final String F_OPT_DISP = "fn_opt_disp";
    public static final String F_BLUR_CARD = "fn_blur_card";
    public static final String F_BLUR_DYNAMIC = "fn_blur_dynamic";
    public static final String F_BLUR_SCALE = "fn_blur_scale";
    public static final String F_HL_THICK = "fn_hl_thick";
    public static final String F_HL_ALPHA = "fn_hl_alpha";
    public static final String F_HL_ANGLE = "fn_hl_angle";
    public static final String F_HL_ON = "fn_hl_on";
    public static final String F_HL_CC = "fn_hl_cc";
    public static final String F_HL_BTN = "fn_hl_btn";
    public static final String F_HL_SL = "fn_hl_sl";
    // 注：原本还有一个 F_WINBLUR（强制 isWindowBlur=true），v2.6.3 删除。
    // 原因：isWindowBlur 是 initBulrParams 内部的**分支标记**而不是可调参数 ——
    // 原生给 case=1/2/4 赋 false、case=3/5 赋 true，强行改成 true 会让普通
    // 通知卡也走整窗模糊（效果错乱 + 功耗上升）；而且 UI 上从来没暴露过这个
    // 键，用户摸不到，属于只会在旧 prefs 残留时偷偷改变行为的死代码。

    // 锁屏通知卡片（case=2 且 z=true）：和普通通知卡同名但值不同，必须分开调
    public static final String F_KL_MASK = "fn_kl_mask";
    public static final String F_KL_OVL = "fn_kl_ovl";
    public static final String F_KL_BRIGHT = "fn_kl_bright";
    public static final String F_KL_SAT = "fn_kl_sat";
    public static final String F_KL_CORNER = "fn_kl_corner";
    // ===== 锁屏元素透明度：**做不了，已放弃**（v2.6.10 实测）=====
    //
    // 本来按 BlurUtils 的 ElementType 拆了 8 个键（时钟/文字/图标/背板/状态栏/
    // 总控/亮度/厚度），代码全写完了、钩子也挂上了（日志 `kg=on`），但**改了没有任何
    // 效果**。两次开关对比截图完全一致。原因查清了，记在这里免得下一个人再试：
    //
    //   ① BlurUtils.getColorAlphaWithBlurState() —— 全仓 grep 只有定义、**零调用点**。
    //      调用方是锁屏主题/时钟插件（另一个 apk），不在 SystemUI 进程里，
    //      我们 hook 的这个类根本没人用。
    //   ② BlurUtils.registerView() —— 同样零调用点，所以 View→ElementType 映射
    //      永远是空的，View.setAlpha 那条路也走不通。
    //   ③ ViewBlur.blurBackground() 里的 BlurParamEx / GlassParamEx —— 钩子挂上了
    //      但没命中，锁屏壁纸的模糊配方只在首次生成时构造一次、之后走缓存。
    //
    // 真正的旋钮是系统设置 `Settings.System.transparency_value`（0-100）：
    //   >80 → 元素 alpha 0.4；40~80 → 0.5；<=40 → 0.6；透明模式关 → 0.72
    // 只三档、且是全局的，改完要重启 SystemUI 才生效。要调就走
    // 系统设置 → 桌面和壁纸 → 通透度，模块不接管。

    // 通知横幅（case=3，来通知时从顶部弹出的那条）
    public static final String F_BN_MASK = "fn_bn_mask";
    public static final String F_BN_OVL = "fn_bn_ovl";
    public static final String F_BN_BRIGHT = "fn_bn_bright";
    public static final String F_BN_SAT = "fn_bn_sat";
    public static final String F_BN_BLUR = "fn_bn_blur";

    // ===== 锁屏时钟的液态玻璃（v2.6.12）=====
    //
    // 溯源结论（之前一直以为锁屏时钟调不了，其实是找错了入口）：
    //   锁屏大时钟的玻璃效果不在 BlurParametersConfig 里，走的是
    //   `com.hihonor.keyguardengine.clockplugin.blur.ClockErodeTable.getErode(...)`
    //   —— 按「时钟样式 + 字号 + 单条/双条 + 字体」四元组查表，返回一个腐蚀值
    //   （0.45 ~ 2.49 之间），然后 `kge029.c` 直接把它塞进
    //   `blurParamEx.setSigma(...)` 当腐蚀半径用。
    //
    //   也就是说：**时钟玻璃有多"厚"、边缘腐蚀多明显，全由这一个 float 决定**，
    //   而它原本只跟字体和字号挂钩，用户完全没法调。
    public static final String F_KG_ERODE = "fn_kg_erode";

    // ===== 锁屏按钮 / 图标 / 文字（v2.6.14）=====
    //
    // 溯源结论：锁屏上这些元素的玻璃参数全部走同一条链 ——
    //   IBlurStrategy.applyBlur(IViewDataProvider, State, kge028.b)
    // 第三个参数 kge028.b 是**通用渲染参数包**：
    //   f501a=blurRadius  b=saturation  f502c=brightness
    //   h=glassType  k=depth  l=dispersion  m=thickness  n/o/p=边缘光角度·厚度·颜色
    // 而 provider.getViewType() 是一对 (字符串, ElementType)，实测值：
    //   bottom_tool / flashlight_tool / flashlight_tool_backplane / button_frame_backplane  -> 按钮组
    //   lock_icon_view / festival_frame_layout                                              -> 图标组
    //   indication_view / charging_status_view / magazine_simple_title_view                 -> 文字组
    //   clock_view（时钟，另有一套腐蚀表，见 F_KG_ERODE）
    //
    // 注意：这里调的是"玻璃本身的浓度/亮度/饱和"，**不是图标颜色**。
    // 图标颜色走 ViewRenderService.updateAppearance() 的取色体系，
    // 那个实现类不在反编译范围内，拿不到。
    public static final String F_KG_BTN = "fn_kg_btn";      // 按钮（图标 + 底板）
    public static final String F_KG_ICON = "fn_kg_icon2";   // 锁形图标 / 节日图标
    public static final String F_KG_TXT = "fn_kg_txt";      // 提示文字 / 充电状态 / 杂志标题

    // 锁屏小组件（天气卡 / 闹钟 / 步数那种）。它**不走** applyBlur 那条链 ——
    // 走的是 AppWidgetHelper.updateAppWidgetColorAndBlur()
    //   -> KgAppWidgetHostView.setChildBlur(View, boolean)
    //      -> HnVisualEffectable.setTransparentStatus(open, degree, liquidMode)
    // 我们拦 setChildBlur 的出口，直接给那个 View 设最终 alpha。
    // 代价：整块小组件（含里面的文字）一起变透，没法只调玻璃底。
    public static final String F_KG_WIDGET = "fn_kg_widget";

    // ===== 锁屏壁纸玻璃 / 指纹（v2.6.16）=====
    //
    // 溯源结论：锁屏壁纸那层模糊由 KeyguardBlurUtils.getHnBlurParametersEx() 构造，
    // 原厂是纯算术写死的：
    //   radiusX = radiusY = (通透度 × -23) / 100 + 32     ← 通透度 100 时只有 9，0 时 32
    //   saturation = 1.3f                                  ← **固定值，跟通透度无关**
    //   scaleX = scaleY = 0.1f                             ← 降采样
    //   brightnessParams(0.05, 0.05, 0.05, 0.0)
    //   maskColor = MASK_COLOR，multiMaskColors = {MASK_COLOR, OVERLAY_COLOR}
    // 指纹另一套：getFPHnBlurParameters() → saturation = 通透度<40 ? 1.3 : 1.2，
    //   brightnessParams(0.2, 0.2, 0.2, 0.0)；另有 getFpColor() 三档白。
    //
    // 这里 hook 两个方法的返回值，改 radiusX/Y 和 saturation（都是 public float 字段）。
    // brightnessParams 是 HnBrightnessParams 对象，暂时不动。
    public static final String F_KG_BG_RADIUS = "fn_kg_bg_radius";  // 锁屏壁纸模糊半径
    public static final String F_KG_BG_SAT = "fn_kg_bg_sat";        // 锁屏壁纸饱和度
    public static final String F_KG_FP_SAT = "fn_kg_fp_sat";        // 指纹饱和度

    // ===== 锁屏元素的玻璃光学（v2.6.17）=====
    //
    // kge028.b 参数包里除了 blurRadius/saturation/brightness，还有：
    //   k = depth（折射深度）  l = dispersion（色散）  m = thickness（厚度）
    //   n = 边缘光角度  o = 边缘光厚度  p = 边缘光颜色
    // kge029.c 会把这些原样塞进 GlassParamEx / EdgeLightParamEx。
    //
    // **按元素分组、各自独立**（用户明确要求）：
    //   按钮组（bottom_tool / flashlight_tool / *_backplane / button_frame_backplane）
    //   图标组（lock_icon_view / festival_frame_layout）
    //   文字组（indication_view / charging_status_view / magazine_simple_title_view）
    // 每组 4 项：深度 / 色散 / 厚度 / 边缘光强度，互不影响。
    public static final String F_KG_BTN_DEP = "fn_kg_btn_dep";
    public static final String F_KG_BTN_DIS = "fn_kg_btn_dis";
    public static final String F_KG_BTN_THK = "fn_kg_btn_thk";
    public static final String F_KG_BTN_EDGE = "fn_kg_btn_edge";
    public static final String F_KG_ICON_DEP = "fn_kg_icon_dep";
    public static final String F_KG_ICON_DIS = "fn_kg_icon_dis";
    public static final String F_KG_ICON_THK = "fn_kg_icon_thk";
    public static final String F_KG_ICON_EDGE = "fn_kg_icon_edge";
    public static final String F_KG_TXT_DEP = "fn_kg_txt_dep";
    public static final String F_KG_TXT_DIS = "fn_kg_txt_dis";
    public static final String F_KG_TXT_THK = "fn_kg_txt_thk";
    public static final String F_KG_TXT_EDGE = "fn_kg_txt_edge";

    // ===== 强制液态玻璃（v2.6.19）=====
    //
    // BlurParametersConfig.glassType：1 = 磨砂玻璃，2 = 液态玻璃。
    // 原生给它赋值的地方：
    //   case=1（磨砂配方）      -> 1
    //   case=2（液态面板）      -> 2
    //   case=3（横幅）          -> isLiquidBlurType() ? 2 : 1   ← 看系统开关
    //   case=4 / case=5         -> 1
    // 也就是说：只要系统当前判定不是"液态"，横幅和另外两个分支就会渲染成磨砂。
    // 打开这个开关后，我们在 initBulrParams 结束时把 glassType 一律改成 2，
    // 让这些界面也走液态折射。
    //
    // ⚠ 风险：如果某个界面本来就不支持液态（没有可折射的实时背景），
    // 强制成 2 可能渲染成一片糊或者纯色。开关默认关，出问题关掉即可。
    public static final String F_FORCE_LIQUID = "fn_force_liquid";

    // ===== 多层遮罩 / 动态渐变 / 锁屏控制中心（v3.1.3）=====
    //
    // 这几个字段在 BlurParametersConfig 里一直是"有但没接"的状态（v3.1.2 时
    // 逐个核对字段名才发现的）：
    //
    //   multiMaskColors / multiButtonMaskColors / multiControlMaskColors /
    //   multiSliderMaskColors   —— int[]，**多层**遮罩的 ARGB 数组
    //     ButtonViewModel:136 直接 setMultiMaskColors(数组, blendModes)，
    //     所以数组里每个元素的 alpha 才是最终的遮罩浓度。
    //     单根 maskColor 只是其中一层，调它盖不住其余几层。
    //
    //   dynamicCardGradientMaskColor / ...Other   —— int[]，动态卡渐变的两组颜色
    //   dynamicCardGradientPos / ...Other         —— float[]，渐变位置（0~1）
    //
    //   isKerguardContrlCenterExpand —— 锁屏控制中心「展开态」。
    //     注意它还会反哺 isWindowBlur（KeyguardBusinessDataProvider:178
    //     `isWindowBlur = !isKerguardContrlCenterExpand && ...`），
    //     所以改它等于同时切换"锁屏上是否用窗口模糊"。
    public static final String F_MULTI_MASK = "fn_multi_mask";     // 多层遮罩浓度（4 个数组一起）
    public static final String F_DYN_GRAD_COLOR = "fn_dyn_grad_clr"; // 动态卡渐变颜色
    public static final String F_DYN_GRAD_POS = "fn_dyn_grad_pos";   // 动态卡渐变位置
    public static final String F_CC_EXPAND = "fn_cc_expand";         // 锁屏控制中心强制展开态（开关）

    // ===== 桌面（launcher）的卡片玻璃（v2.6.19）=====
    //
    // 桌面卡片走的是另一套类，和 SystemUI 完全不相干：
    //   com.hihonor.android.launcher.cardstyle.utils.LiquidGlassUtils
    //     .setLiquidGlassParams(View, TransparentBlurParams, Object shape)
    //       -> HnLiquidGlassParamsEx$BlurParamEx.setRadius / setScale /
    //          setSaturation / setBrightnessParams / setMultiMaskColors
    //       -> setGlassParamEx(null)      ← **桌面卡片没有液态折射**
    //       -> setHighLightParamEx(null)  ← 也没有高光
    // 也就是说桌面的"液态玻璃"其实只是「模糊 + 形状 + 遮罩」，
    // 调不出锁屏那种折射感，能调的只有模糊半径 / 饱和度 / 亮度。
    //
    // ⚠ 这三个在 launcher 进程生效，改完要重启桌面（不是重启系统界面）。
    public static final String F_DESK_RADIUS = "fn_desk_radius";
    public static final String F_DESK_SAT = "fn_desk_sat";
    public static final String F_DESK_BRIGHT = "fn_desk_bright";

    public static final String[] FN_KEYS = {
            F_NTF_MASK, F_NTF_OVL, F_NTF_BRIGHT, F_NTF_SAT, F_NTF_CORNER,
            F_NTFB_MASK, F_NTFB_OVL, F_NTFB_BRIGHT,
            F_CC_MASK, F_CC_OVL, F_CC_BRIGHT, F_CC_SAT, F_CC_CORNER,
            F_BTN_MASK, F_BTN_OVL, F_BTN_BRIGHT, F_BTN_SAT, F_BTN_CORNER,
            F_SL_MASK, F_SL_OVL, F_SL_BRIGHT, F_SL_SAT, F_SL_CORNER,
            F_OPT_REFRACT, F_OPT_DEPTH, F_OPT_THICK, F_OPT_DISP,
            F_BLUR_CARD, F_BLUR_DYNAMIC, F_BLUR_SCALE,
            F_HL_THICK, F_HL_ALPHA, F_HL_ANGLE,
            F_HL_ON, F_HL_CC, F_HL_BTN, F_HL_SL,
            F_KL_MASK, F_KL_OVL, F_KL_BRIGHT, F_KL_SAT, F_KL_CORNER,
            F_BN_MASK, F_BN_OVL, F_BN_BRIGHT, F_BN_SAT, F_BN_BLUR,
            F_KG_ERODE, F_KG_BTN, F_KG_ICON, F_KG_TXT, F_KG_WIDGET,
            F_KG_BG_RADIUS, F_KG_BG_SAT, F_KG_FP_SAT,
            F_KG_BTN_DEP, F_KG_BTN_DIS, F_KG_BTN_THK, F_KG_BTN_EDGE,
            F_KG_ICON_DEP, F_KG_ICON_DIS, F_KG_ICON_THK, F_KG_ICON_EDGE,
            F_KG_TXT_DEP, F_KG_TXT_DIS, F_KG_TXT_THK, F_KG_TXT_EDGE,
            F_FORCE_LIQUID,
            F_MULTI_MASK, F_DYN_GRAD_COLOR, F_DYN_GRAD_POS, F_CC_EXPAND, F_DESK_RADIUS, F_DESK_SAT, F_DESK_BRIGHT,
    };

    /**
     * Cross-process gate for the high-temperature bypass. system_server can
     * never read the module prefs/provider (SELinux), so SettingsActivity
     * mirrors this one switch into a system property with root; every hook
     * (SystemUI *and* system_server) reads the property live, so toggling
     * takes effect immediately — no scope restart, no reboot.
     * Missing/unreadable -> "0" (OFF): a root-level safety bypass must never
     * be silently active on a fresh install.
     */
    public static final String PROP_HOTTEMP = "persist.sys.has.hottemp";
    /**
     * 截图限制开关的系统属性。
     *
     * 为什么必须走属性：`hookSecureFlag` 挂在 **system_server**，它读不到模块的
     * ContentProvider（SELinux 禁止 system_server 访问普通 app 的 provider），
     * 所以 `Cfg.isOn(K_NOSECURE)` 在那里恒为 false —— 钩子挂上也不会拦任何东西。
     * 属性是唯一全局可读的通道，和高温那条同一套机制。
     */
    public static final String PROP_NOSECURE = "persist.sys.has.nosecure";

    // Cached reader: the thermal hooks fire on EVERY HAL temperature callback
    // (several per second) and on EVERY status query, so a Class.forName +
    // getMethod per call would be real overhead inside system_server and
    // PowerGenie. Resolve the Method once, then re-read the property at most
    // once per second (a toggle therefore lands within ~1s, still "instant").
    private static volatile java.lang.reflect.Method sPropGet;
    private static volatile long sPropAt;
    // ⚠ v3.0.0 修正：这里原来是 `= true`，与上面注释"读不到 -> OFF"**正好相反**。
    // 后果：万一反射失败（Class.forName 抛异常、或字段被 SELinux 拦），
    // 代码会一路保持 true —— 也就是**高温旁路在没有用户授权的情况下一直开着**，
    // 而这恰恰是注释里说"绝不能发生"的情况（root 级安全绕过）。
    // 改成 false 后：首次成功读取前一律按关处理，最多延迟 1 秒（TTL）。
    private static volatile boolean sPropVal = false;

    /** 截图限制开关的缓存时间戳（独立于高温，避免互相干扰）。 */
    private static volatile long sNsAt;
    private static volatile boolean sNsVal = false;

    /**
     * 截图限制是否开启。
     *
     * <p>语义：**1 = 允许截屏/录屏**（清掉 FLAG_SECURE），0 = 保持系统限制。
     * 默认 "0"（关）。
     *
     * <p>与 {@link #hottempOn()} 结构完全一致：1 秒缓存 + 反射读属性，
     * 读失败时沿用上次值而不反复重试。
     */
    public static boolean nosecureOn() {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - sNsAt < 1000L) {
            return sNsVal;
        }
        try {
            java.lang.reflect.Method m = sPropGet;
            if (m == null) {
                Class<?> sp = Class.forName("android.os.SystemProperties");
                m = sp.getMethod("get", String.class, String.class);
                sPropGet = m;
            }
            sNsVal = !"0".equals(m.invoke(null, PROP_NOSECURE, "0"));
        } catch (Throwable t) {
            // 读失败沿用上次值，别在每次回调里重试反射
        }
        sNsAt = now;
        return sNsVal;
    }

    public static boolean hottempOn() {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - sPropAt < 1000L) {
            return sPropVal;
        }
        try {
            java.lang.reflect.Method m = sPropGet;
            if (m == null) {
                Class<?> sp = Class.forName("android.os.SystemProperties");
                m = sp.getMethod("get", String.class, String.class);
                sPropGet = m;
            }
            sPropVal = !"0".equals(m.invoke(null, PROP_HOTTEMP, "0"));
        } catch (Throwable t) {
            // keep the last known value and back off instead of retrying the
            // (possibly failing) reflection on every single callback
        }
        sPropAt = now;
        return sPropVal;
    }

    /** null = preload not landed yet (default ON); set once, then frozen
     *  for glass scopes / observer-refreshed for installer scopes.
     *  Values are stored as int: switches use 0/1, DIY sliders use 0-100. */
    private static volatile Map<String, int[]> sSnapshot = null;
    private static boolean sStarted = false;

    private Cfg() {
    }

    /**
     * 未就绪 / 快照里缺键时**必须按关闭处理**的开关（opt-in）。
     *
     * 这些不是"功能开关"，而是**模式选择器 / 高风险旁路**：默认 ON 会让 hook
     * 走上另一套完全不同的配方，而不是"更完整的功能"。
     *
     * GLASS_EXTREME 就是实例（v2.3.11 白玻璃事故）：它是 DIY 模式总闸，一旦误判为
     * ON，refreshProps() 会去读另一套模式滑条；而滑条在未就绪时只能返回出厂百分比，
     * 比 baked 标准配方弱得多（tblur 0.1 vs 0.6、nmask 0.15 vs 0.4），玻璃直接退化成
     * "几乎不糊、几乎不遮"的亮片 —— 白色背景下文字就看不清了。
     * 这个误判只在 SystemUI 启动那一刻 Cfg 快照还没从 provider 落地时发生，所以
     * 表现为"开机后发亮，重启一两次 SystemUI 又自己好了"的偶发 bug。
     *
     * <p><b>这是唯一事实来源</b>：{@link CfgProvider#migrated}（provider 对缺失键的
     * 默认值）和 SettingsActivity.migratePrefs()（首次启动补全）都必须调它。
     * 三处各写一份 if 的写法在 v2.3.12 之前就存在，加新开关时漏改一处就会出现
     * "UI 显示关闭、hook 实际开启"的鬼故事 —— 别再拆开写。
     */
    public static boolean isOptIn(String key) {
        // ===== v3.2.0：所有**总闸**默认关闭 =====
        //
        // 规则（用户定）：装完模块什么都不该自动生效，要用户自己开。
        //   · 有总开关的 -> 总开关默认关
        //   · 二级 / 三级（分线、分项）-> 默认全开
        // 例：液态玻璃只关 K_GLASS，系统/锁屏/横幅/桌面/相册这几条线仍默认开；
        //     安装器净化只关 K_PURIFY，信息卡/降级安装等子项仍默认开。
        //
        // 这样总闸一开就是"完整的一套"，用户不需要进去逐条勾，关的时候也是整块关。
        if (K_GLASS.equals(key) || K_PURIFY.equals(key)) return true;
        // 安全组件：每一项都是独立的能力，默认全关。
        if (K_NOSECURE.equals(key) || K_NOCLOUD.equals(key)
                || K_NOFRAUD.equals(key) || K_PURGECOTA.equals(key)) return true;
        return K_NODUCK.equals(key) || K_HOTTEMP.equals(key)
                || K_DIAG.equals(key);
    }

    /** 快照是否已从 provider 落地。未落地时 hook 只能用 baked 默认值。 */
    public static boolean ready() {
        return sSnapshot != null;
    }

    /** hook side: pure memory read — no locks, no binder, no blocking. */
    public static boolean isOn(String key) {
        Map<String, int[]> m = sSnapshot;
        if (m == null) {
            return !isOptIn(key); // preload not landed yet: opt-in keys stay OFF
        }
        int[] v = m.get(key);
        return v == null ? !isOptIn(key) : v[0] != 0;
    }

    /** hook side: DIY slider read (percent), falls back to the default pct. */
    public static int getInt(String key, int def) {
        Map<String, int[]> m = sSnapshot;
        if (m == null) {
            return def;
        }
        int[] v = m.get(key);
        return v == null ? def : v[0];
    }

    /**
     * 诊断用：把当前快照拼成一行。未就绪时明确说"未就绪"，绝不假装有值 ——
     * 反馈日志里最常见的问题就是分不清"开关是关的"还是"根本没读到"。
     */
    public static String dump() {
        Map<String, int[]> m = sSnapshot;
        if (m == null) {
            return "(快照未就绪，hook 侧按默认值运行)";
        }
        StringBuilder sb = new StringBuilder();
        for (String k : KEYS) {
            int[] v = m.get(k);
            sb.append(k).append('=').append(v == null ? "?" : v[0]).append(' ');
        }
        // 精细参数只列"跟出厂值不一样"的项 —— 48 个 100 全打出来会把日志撑爆，
        // 而排查时真正想知道的就是"用户到底动了哪几项"。
        StringBuilder fn = new StringBuilder();
        for (String k : FN_KEYS) {
            int[] v = m.get(k);
            int def = fnDefPct(k);
            int val = v == null ? def : v[0];
            if (val != def) {
                if (fn.length() > 0) {
                    fn.append(' ');
                }
                fn.append(k).append('=').append(val);
            }
        }
        if (fn.length() > 0) {
            sb.append(" | 改过: ").append(fn);
        }
        return sb.toString().trim();
    }

    /**
     * Call once per scoped process from handleLoadPackage.
     *
     * @param watchChanges true for installer scopes: after the initial load,
     *                     a ContentObserver refreshes the snapshot whenever
     *                     SettingsActivity toggles a switch; false for glass
     *                     scopes: snapshot frozen until the process restarts.
     */
    public static synchronized void startPreload(final boolean watchChanges) {
        if (sStarted) {
            return;
        }
        sStarted = true;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                // early phase: app/binder may not be up yet — retry briefly
                for (int i = 0; i < 30; i++) {
                    if (loadOnce()) {
                        break;
                    }
                    if (!sleep(500L)) {
                        return;
                    }
                }
                if (sSnapshot == null) {
                    // system_server 读不到模块 provider 是**预期**的（SELinux），
                // 记成 E 级错误只会在排查时误导人 —— 降为警告并写明原因
                Diag.warn("Cfg preload gave up; staying default-ON"
                        + " (system_server 属预期；其他进程说明 provider 不可达)");
                    return;
                }
                if (!watchChanges) {
                    Diag.log("Cfg frozen for process lifetime (diag still live)");
                    // v2.3.0: even frozen scopes keep ONE live value — the diagnostics
                    // switch. It has to take effect the moment the user flips it
                    // (asking them to restart SystemUI just to turn logging on
                    // would be absurd), and refreshing a single key cannot disturb
                    // the baked-in glass parameters.
                    registerObserver(false);
                    return;
                }
                registerObserver(true);
            }
        }, "HAS-CfgPreload");
        t.setDaemon(true);
        t.start();
    }

    /**
     * @param full true  = 刷新整份快照（installer/system_server 线）
     *             false = 只刷新诊断开关（玻璃线：快照保持冻结）
     */
    private static void registerObserver(final boolean full) {
        try {
            Context ctx = AndroidAppHelper.currentApplication();
            if (ctx == null) {
                Diag.warn("Cfg: no context, observer skipped (snapshot stays at initial load)");
                return;
            }
            HandlerThread ht = new HandlerThread("HAS-CfgObs");
            ht.start();
            ctx.getContentResolver().registerContentObserver(
                    CfgProvider.URI, true, new ContentObserver(new Handler(ht.getLooper())) {
                @Override
                public void onChange(boolean selfChange) {
                    // this thread holds no system locks — safe to do binder work
                    if (full) {
                        loadOnce();
                    } else {
                        refreshDiag();
                    }
                }
            });
            Diag.log("Cfg watching provider changes (" + (full ? "full" : "diag only") + ")");
        } catch (Throwable t) {
            Diag.err("Cfg observer failed", t);
        }
    }

    // ==================== 诊断开关的实时通道 (v2.3.0) ====================

    /** -1 = 还没从 provider 收到过实时值（此时退回快照里的那份）。 */
    private static volatile int sDiagLive = -1;

    /** 诊断开关是否打开。无 root：走 prefs→provider→observer 这条链。 */
    public static boolean diagOn() {
        int live = sDiagLive;
        if (live >= 0) {
            return live != 0;
        }
        Map<String, int[]> m = sSnapshot;
        if (m != null) {
            int[] v = m.get(K_DIAG);
            return v != null && v[0] != 0;
        }
        return false;
    }

    /** 只取 diag 一个键，不碰冻结的玻璃快照。 */
    private static void refreshDiag() {
        Context ctx = null;
        try {
            ctx = AndroidAppHelper.currentApplication();
        } catch (Throwable ignored) {
        }
        if (ctx == null) {
            return;
        }
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(
                    CfgProvider.URI, null, null, null, null);
            if (c == null) {
                return;
            }
            int ki = c.getColumnIndex("key");
            int vi = c.getColumnIndex("val");
            if (ki < 0 || vi < 0) {
                return;
            }
            while (c.moveToNext()) {
                if (K_DIAG.equals(c.getString(ki))) {
                    sDiagLive = c.getInt(vi);
                    break;
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (c != null) {
                try {
                    c.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static boolean sleep(long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            return false;
        }
    }

    /** one provider query; true on success. Never called from a hook callback. */
    /**
     * 拿一个能用的 Context（v3.2.5）。
     *
     * <p>原来只用 {@code AndroidAppHelper.currentApplication()}，但**有些系统进程
     * 根本没有 Application 实例** —— securityserver 就是以 Service 为主的进程，
     * 那个调用恒返回 null。后果极其隐蔽：
     * <ul>
     *   <li>{@code loadOnce()} 每次都 false → 30 次重试全失败 → 快照永远为空</li>
     *   <li>{@code isOn()} 对 opt-in 键返回 false → **每个钩子回调都静默 return**</li>
     *   <li>日志里只显示"共接管 N 处"，看起来一切正常，实际一个都没拦</li>
     * </ul>
     * 所以补一条后备路径：走 ActivityThread 的 systemContext（系统进程一定有）。
     */
    private static Context apiContext() {
        try {
            Context c = AndroidAppHelper.currentApplication();
            if (c != null) return c;
        } catch (Throwable ignored) {
        }
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object thread = at.getMethod("currentActivityThread").invoke(null);
            if (thread != null) {
                Object sys = at.getMethod("getSystemContext").invoke(thread);
                if (sys instanceof Context) return (Context) sys;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static boolean loadOnce() {
        Context ctx = apiContext();
        if (ctx == null) {
            return false;
        }
        Map<String, int[]> m = new HashMap<String, int[]>();
        try {
            Cursor c = ctx.getContentResolver().query(
                    CfgProvider.URI, null, null, null, null);
            if (c == null) {
                return false;
            }
            try {
                while (c.moveToNext()) {
                    m.put(c.getString(0), new int[]{c.getInt(1)});
                }
            } finally {
                try {
                    c.close();
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            return false; // provider unreachable this round — retry happens in the loop
        }
        if (m.isEmpty()) {
            return false;
        }
        Map<String, int[]> old = sSnapshot;
        if (old == null || !old.equals(m)) {
            Diag.state("Cfg via provider", m);
        }
        sSnapshot = m;
        return true;
    }
}
