package io.github.voreulch.honoradvanced;

import android.app.AndroidAppHelper;
import android.content.Context;
import android.content.SharedPreferences;
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
    /**
     * 通知横幅文字自适应反色（默认开启）。
     *
     * <p>与 {@link #K_BANNER}（横幅液态玻璃）相互独立：玻璃总闸关掉时本项仍然生效，
     * 它只读横幅实际渲染出来的背景亮度来决定用亮字还是暗字。
     * 默认开启 = 不进 {@link #isOptIn}，由 {@link #isOn} 的"缺失键 = !isOptIn"自动给出 true。
     */
    public static final String K_HUN_INVERT = "hun_invert";
    public static final String K_DESK = "desk";           // launcher 卡片玻璃（v2.6.20）
    public static final String K_PURIFY = "purify";       // installer purify master
    public static final String K_INFOCARD = "infocard";   // package info card
    public static final String K_DOWNGRADE = "downgrade"; // allow downgrade installs
    public static final String K_NODUCK = "noduck";       // misc: block notification audio ducking
    public static final String K_HOTTEMP = "hottemp";     // misc: suppress the system high-temperature popups
    public static final String K_DIAG = "diag";           // about: verbose diagnostics into the LSPosed log
    // ===== 安全组件 =====
    //
    // 这几个都是"解除系统限制"型开关，默认全关、需要用户显式打开。
    public static final String K_NOSECURE = "nosecure";   // 允许截屏/录屏（清 FLAG_SECURE）
    public static final String K_NOCLOUD = "nocloud";     // 屏蔽性能/温控策略的云控下发
    /**
     * 云控「读/收」与「写盘」两层共用 {@link #K_NOCLOUD} 单键门控；
     * 本常量只是同键别名，保留两个门控点是为了将来可一行拆回两个开关。
     *
     * <p>默认关，关掉即刻放行，不需要重启。
     */
    public static final String K_NOWRITE = K_NOCLOUD;
    public static final String K_NOFRAUD = "nofraud";     // 屏蔽反诈组件的数据采集与上报（只含 2 个 Fraud Processor）
    /**
     * 清理云端配置残留的占位键。
     *
     * <p>删除动作由界面上的独立按钮执行（确认框 → 一次 su rm），不读写这个 prefs 键；
     * 删除目标为 /data/cota/para 下三份性能相关配置（cpuconfig / pg_config / thermal_config），
     * 需要 ROOT。
     *
     * <p><b>⚠ 恒为 false，不得用于门控</b>：
     * <ul>
     *   <li>保留在 KEYS 里，是为了让 prefs 迁移不把可能存在的历史键当孤儿删掉</li>
     *   <li>provider 快照的键集合保持稳定，hook 读到它也不会出错</li>
     * </ul>
     * 任何新代码都不应再调 {@code isOn(K_PURGECOTA)} —— 它恒为 false。
     */
    public static final String K_PURGECOTA = "purgecota";

    /**
     * 玻璃配置的「重置代数」**唯一来源是系统属性**。
     *
     * <p>为什么不放 prefs：prefs 和属性会有两份状态，一旦 prefs 前进、属性没前进，
     * 宿主每次启动都会判 epoch 不符，缓存永久失效。只保留属性这一份：
     * 恢复 / 导入 / 玻璃线开关变更时先写属性并回读，读回一致才继续改 prefs；
     * 写失败就整笔中止，不存在"两份状态分叉"。
     *
     * <p>为什么用属性而不是 provider：宿主在 {@code handleLoadPackage} 里拿不到
     * Context，provider 还没起来；属性是唯一能在首帧之前同步读到的通道。
     *
     * <p>读失败（反射异常 / 值非数字）返回 {@link #EPOCH_UNKNOWN}，由宿主
     * 显式走 fail-open（接受本次缓存并在日志里写清），绝不静默当 0。
     */
    public static final String PROP_GLASS_EPOCH = "persist.sys.has.glass_epoch";
    /** epoch 读不到 / 解析失败时的哨兵值。 */
    public static final int EPOCH_UNKNOWN = -1;

    public static final String[] KEYS = {
            K_GLASS,
            K_NOSECURE, K_NOCLOUD, K_NOFRAUD, K_PURGECOTA, K_SYSTEM, K_KEYGUARD, K_GALLERY, K_BANNER, K_HUN_INVERT, K_DESK,
            K_PURIFY, K_INFOCARD, K_DOWNGRADE, K_NODUCK, K_HOTTEMP, K_DIAG,
    };

    /**
     * 作用域清单（与 res/values/arrays.xml 的 xposed_scope **除模块自身外**一致）。
     * 两处用途：CfgProvider 校验 probe 上报来源；UI 判断某个开关依赖的进程有没有跑起来。
     *
     * <p>注意：arrays.xml 里还列了模块自身 {@code io.github.voreulch.honoradvanced}，
     * 那是 LSPosed 为了让模块进程也加载（设置页自检/导出）而勾的；但模块自身从不
     * 通过 {@code Probe.report} 上报，所以这里的 probe 白名单**故意不列自己**。
     * 两者不是“必须逐项相同”，而是“宿主进程集合相同 + 模块自身仅用于激活”。
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
            // 感知服务本体（自己就有反诈逻辑和独立上报通道）
            "com.hihonor.awareness",
            "android",
    };

    // ===== 分控件强度 =====
    //
    // 原生的 initBulrParams 给四类控件**分别**赋了亮度/饱和度/遮罩资源：
    //   通知卡    brightness 0.08  saturation 1.2
    //   控制中心瓦片 brightness 0.12  saturation 1.1
    //   滑块      brightness 0.12  saturation 1.1
    //   圆形按钮   brightness 0.08  saturation 1.1
    // 单一 mask 乘数会把所有控件一起改 —— 想单独压暗通知卡，滑块和按钮会跟着变，
    // 这就是"一刀切"。
    //
    // 这里给每类控件一个 0-200 的强度百分比（100 = 保持原生配方），同时作用于
    // 该类的遮罩 alpha、亮度、饱和度三项。默认全 100 时行为与原生完全一致。
    // 分区域粗调（tune_*）并入精细调节 —— 每个字段都能单独拖，再留一层"整类缩放"
    // 只会让同一件事有两个旋钮，反而困惑。
    // TUNE_KEYS 保留为空通道（provider 的通用 int 通道，目前无使用者）。
    public static final String[] TUNE_KEYS = {};

    /**
     * 分区域强度的**出厂默认值**（百分比，100 = 原厂）。
     *
     * <p>这是给"烧录"留的口子：用户把所有滑条调到满意之后，把想固化的值逐项写进
     * 这里重新打包，装上去就是那套观感；用户仍然可以在设置页继续微调（滑条值存
     * prefs，优先于这里的默认）。全部返回 100 表示跟随荣耀原厂。
     */
    public static int tuneDefPct(String key) {
        return 100;   // 粗调层与精细调节重复，恒为 100；保留签名只为兼容现有调用点
    }

    /**
     * 精细调节的**出厂默认值**（百分比，100 = 原厂）。
     *
     * <p>同 {@link #tuneDefPct(String)}，烧录时在这里逐项写死。
     * 注意 {@link #F_HL_ANGLE} 是角度而不是百分比，它的出厂值单独写在下面。
     */
    /** 「高光语义」一次性迁移标记。 */
    public static final String MIG_HL_V2 = "hl_semantic_v2";

    /**
     * 锁屏按钮历史烧录值 -> 原厂等价的一次性迁移标记。
     *
     * <p>v2：v1 的标记在「四键还不存在」时就被写成了 true（见旧实现），
     * 之后 {@link #migrateBakedParams} 才把 50/140/110/120 写进 prefs，
     * 于是 v1 永远提前返回，脏参数一直留在机器上（锁屏手电筒底板发白就是这么来的）。
     * 换新标记名等于让 219 再跑一次迁移，把老用户机器上的残留值纠回来。
     */
    public static final String MIG_KG_BTN_V2 = "kg_btn_orig_v2";

    /**
     * 把历史烧录值（fn_kg_btn=50 / dep=140 / dis=110 / edge=120）重置为 100（原厂等价）。
     *
     * <p>只重置**恰好等于历史烧录值**的项：用户手动拖过别的值就保留。
     * 一次性标记，避免每次启动覆盖用户后续的调整。
     */
    public static void migrateKgBtnDefaults(SharedPreferences p) {
        if (p == null) return;
        if (p.getBoolean(MIG_KG_BTN_V2, false)) return;
        // 四键还没落地（新装 / 本次启动还没 seed）时不许打标记 —— 旧实现就在这里
        // 提前落标记，之后 migrateBakedParams 才把历史烧录值写进 prefs，
        // 迁移从此永远够不着，锁屏手电筒底板被这些值弄成发白。
        if (!p.contains(F_KG_BTN) && !p.contains(F_KG_BTN_DEP)
                && !p.contains(F_KG_BTN_DIS) && !p.contains(F_KG_BTN_EDGE)) return;
        // 逐键核对：只有恰好等于历史烧录值的那一项回到原厂等价 100，
        // 用户自己拖过的值原样保留。
        SharedPreferences.Editor ed = p.edit();
        if (p.getInt(F_KG_BTN, 100) == 50) ed.putInt(F_KG_BTN, 100);
        if (p.getInt(F_KG_BTN_DEP, 100) == 140) ed.putInt(F_KG_BTN_DEP, 100);
        if (p.getInt(F_KG_BTN_DIS, 100) == 110) ed.putInt(F_KG_BTN_DIS, 100);
        if (p.getInt(F_KG_BTN_EDGE, 100) == 120) ed.putInt(F_KG_BTN_EDGE, 100);
        ed.putBoolean(MIG_KG_BTN_V2, true).apply();
    }

    /**
     * 高光键当前语义是 1 = 开启高光；老数据里存的是反语义值（1 = 关闭），
     * 这里把老用户存下来的值翻转一次。
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
                // 老数据里勾过「关闭全部高光」——当前语义下等价于**四个全关**。
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

    /** 「烧录配方落地」的一次性标记。 */
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
     * 把「烧录配方」按新装 / 老用户两种情况落地。
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
        // 四个高光键语义：1 = 开启高光，0 = 关闭；原厂四类全开，所以默认 1。
        // 判定侧：hook 里 `getInt(key, 1) == 0` 才关。
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

        // ===== 出厂配方 =====
        //
        // 这里的"100%"不是"系统原厂"，而是**主人自己调好的那套观感**。
        // 新装用户打开就是下面这组值，不用自己再拖一遍。
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
                // 锁屏按钮（手电筒 + 相机）默认 100（原厂等价、不处理）。
                // 更强的烧录值会让手电筒底板在浅色壁纸下发白；
                // 用户仍可用现有 fn_kg_btn* 滑条调。
                // prefs 里等于历史烧录值的键由 migrateKgBtnDefaults 一次性重置。
                case F_KG_ICON: return 50;
                case F_KG_ICON_DEP: return 130;
                case F_KG_ICON_DIS: return 130;
                case F_KG_ICON_EDGE: return 120;
                case F_KG_TXT_DEP: return 130;
                case F_KG_TXT_DIS: return 130;
                case F_KG_TXT_EDGE: return 120;
                // F_KG_WIDGET（锁屏小组件透明度）不占位，走 default = 100（系统原样）。
                // 小组件是整块 alpha（连文字一起淡），烧低了新用户
                // 一开箱就觉得天气卡发灰，说不清是自己还是模块干的。
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
    // 不要给 isWindowBlur 加开关：它是 initBulrParams 内部的**分支标记**而不是可调参数 ——
    // 原生给 case=1/2/4 赋 false、case=3/5 赋 true，强行改成 true 会让普通
    // 通知卡也走整窗模糊（效果错乱 + 功耗上升）。

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
    /**
     * 允许降级安装的系统属性。
     *
     * <p>和截图同一个原因：`hookDowngradeInSystemServer` 挂在 system_server，
     * 它读不到模块的 provider（SELinux），`on(K_DOWNGRADE)` 恒 false，
     * 于是系统层的 `isDowngradePermitted` 一直返回"不允许"。
     * 安装器进程那层（`hookAllowDowngrade` 设 installFlags）是好的，
     * 但 system_server 会二次校验，两层都得过。
     */
    public static final String PROP_DOWNGRADE = "persist.sys.has.downgrade";

    // Cached reader: the thermal hooks fire on EVERY HAL temperature callback
    // (several per second) and on EVERY status query, so a Class.forName +
    // getMethod per call would be real overhead inside system_server and
    // PowerGenie. Resolve the Method once, then re-read the property at most
    // once per second (a toggle therefore lands within ~1s, still "instant").
    private static volatile java.lang.reflect.Method sPropGet;
    private static volatile long sPropAt;
    // ⚠ 反射失败（Class.forName 抛异常、或字段被 SELinux 拦）时必须按关处理，
    // 绝不能默认 true —— 否则高温旁路会在没有用户授权的情况下一直开着（root 级安全绕过）。
    // 首次成功读取前一律 false，最多延迟 1 秒（TTL）。
    private static volatile boolean sPropVal = false;

    /** 降级开关的缓存时间戳。 */
    private static volatile long sDgAt;
    private static volatile boolean sDgVal = false;

    /**
     * 降级安装是否开启（system_server 侧用，走属性）。
     *
     * <p>缺省值改为 **1（允许）** —— 和设置页默认、provider 缺省统一。
     * 属性还没被写入（刚装模块 / 属性服务异常）时，系统层按「允许」处理；
     * 老用户在设置里显式关过的，属性写过 0，仍然按关处理。
     */
    public static boolean downgradeOn() {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - sDgAt < 1000L) {
            return sDgVal;
        }
        try {
            java.lang.reflect.Method m = sPropGet;
            if (m == null) {
                Class<?> sp = Class.forName("android.os.SystemProperties");
                m = sp.getMethod("get", String.class, String.class);
                sPropGet = m;
            }
            sDgVal = !"0".equals(m.invoke(null, PROP_DOWNGRADE, "1"));
        } catch (Throwable t) {
            // 沿用上次值
        }
        sDgAt = now;
        return sDgVal;
    }

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

    /**
     * 当前玻璃重置代数（唯一来源 {@link #PROP_GLASS_EPOCH}）。
     *
     * <p>返回 {@link #EPOCH_UNKNOWN} 表示"读不到/读不懂"，不是 0。宿主必须
     * 明确处理 unknown：按 fail-open 接受本次缓存并记日志，避免属性服务一时
     * 异常就把所有宿主的缓存永久判死。属性为空 = 从未恢复过 = 0（这是已知值）。
     */
    public static int glassEpoch() {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Object v = sp.getMethod("get", String.class, String.class)
                    .invoke(null, PROP_GLASS_EPOCH, "");
            String s = v == null ? "" : String.valueOf(v).trim();
            if (s.isEmpty()) {
                return 0; // 属性未设置：明确知道是 0
            }
            return Integer.parseInt(s);
        } catch (Throwable t) {
            return EPOCH_UNKNOWN;
        }
    }

    /** 本进程读缓存时用的 epoch；provider 晚到后用于告警对比。 */
    private static volatile int sCachedEpoch = EPOCH_UNKNOWN;

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
    /** volatile：awaitReady 在别的线程读它判断"到底有没有启动过预加载"。 */
    private static volatile boolean sStarted = false;

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
        // ===== 所有**总闸**默认关闭 =====
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
                || K_NOFRAUD.equals(key)
                || K_PURGECOTA.equals(key)) return true;
        return K_NODUCK.equals(key) || K_HOTTEMP.equals(key)
                || K_DIAG.equals(key);
    }

    /** 快照是否已从 provider 落地。未落地时 hook 只能用 baked 默认值。 */
    public static boolean ready() {
        return sSnapshot != null;
    }

    /**
     * 等快照落地，最多 {@code ms} 毫秒。
     *
     * <p>给**用户主动触发**的路径用：安装器净化。重启作用域后第一次安装时，
     * installer 进程刚起、provider 可能还没回来，而 {@code isOn(K_PURIFY)} 在
     * 未就绪时返回 false —— 结果是这一单的广告全放行。用户点完"安装"本来就要
     * 等系统解析 APK，多等几百毫秒无感，比漏拦强。
     *
     * <p><b>绝对不要</b>在玻璃线（systemui / launcher / photos）里调它：那些
     * 回调在渲染线程上，阻塞会直接卡首帧，而且玻璃线有本地缓存兜底，不需要等。
     *
     * <p>返回 true = 快照可用；false = 超时或本进程压根没启动预加载。
     */
    public static boolean awaitReady(long ms) {
        if (sSnapshot != null) {
            return true;
        }
        if (!sStarted) {
            // startPreload 没跑过，等多久都不会有结果 —— 直接返回，别白等。
            return false;
        }
        // ⚠ 每个进程只真正等一次。provider 不可达时（system_server 属预期，
        // 普通进程则是环境异常），如果没有这个标记，每一次 hook 回调都会
        // 等满 ms —— 安装器会在一单安装里被拖出几十次 1200ms，直接卡死。
        // 第一次等过之后一律立刻返回当前状态。
        if (sAwaited) {
            return false;
        }
        sAwaited = true;
        try {
            return sReadyLatch.await(ms, java.util.concurrent.TimeUnit.MILLISECONDS)
                    && sSnapshot != null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /** awaitReady 是否已经等过一次（避免重复阻塞）。 */
    private static volatile boolean sAwaited = false;

    /** 快照第一次可用的信号。只 countDown 一次，进程内不复位。 */
    private static final java.util.concurrent.CountDownLatch sReadyLatch =
            new java.util.concurrent.CountDownLatch(1);

    /** 快照已落地（provider 或本地缓存任一路径）时放行所有等待者。 */
    private static void markReady() {
        try {
            if (sReadyLatch.getCount() > 0L) {
                sReadyLatch.countDown();
            }
        } catch (Throwable ignored) {
        }
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
        // **同步**读本地副本，必须在起线程之前。
        // 线程里第一次 loadOnce() 会 query provider，而 provider 属于模块 app ——
        // 开机时那是一次冷启动，query 能阻塞好几秒。等它返回再填缓存，SystemUI
        // 早就把锁屏和材质渲染完了，等于没救。
        // 这里是纯文件 IO（几百字节，<5ms），放在 handleLoadPackage 里可接受。
        try {
            fillFromCache();
        } catch (Throwable ignored) {
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                // early phase: app/binder may not be up yet — retry briefly
                //
                // **不能只重试 15 秒**。真机实测（11-02 12:24）：
                // 开机时用户还没解锁，模块 App 装在 CE 存储里**根本起不来**，
                // provider 查询返回 "Unknown URL"；15 秒一到放弃之后，整个开机
                // 周期都不会再试 —— 于是缓存永远写不上，下次开机还是失效。
                //
                // 现在的节奏：
                //   0~15s   每 500ms   （覆盖正常开机；此时多半还没解锁）
                //   15~60s  每 2s      （用户开始解锁，模块 App 变得可启动）
                //   60s 后  每 10s     （兜底，最长到 5 分钟）
                // 只要成功一次就会写缓存，下一次开机首帧就有正确配置。
                long startAt = android.os.SystemClock.elapsedRealtime();
                while (true) {
                    // 顺序不能反：先填本地副本，再查 provider。
                    // 首次 query 会触发模块 app 冷启动，能阻塞好几秒；如果等它
                    // 返回再填缓存，SystemUI 早把锁屏和材质渲染完了，等于没救。
                    fillFromCache();
                    if (loadOnce()) {
                        break;
                    }
                    long elapsed = android.os.SystemClock.elapsedRealtime() - startAt;
                    long wait;
                    if (elapsed < 15000L) {
                        wait = 500L;
                    } else if (elapsed < 60000L) {
                        wait = 2000L;
                    } else {
                        wait = 10000L;
                    }
                    if (elapsed > 300000L) {
                        Diag.warn("Cfg preload gave up after 5min; "
                                + "provider 始终不可达（缓存若已存在则功能不受影响）");
                        break;
                    }
                    if (!sleep(wait)) {
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
     * 拿一个能用的 Context。
     *
     * <p>单靠 {@code AndroidAppHelper.currentApplication()} 不够：**有些系统进程
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

    // ==================== 快照本地副本（开机失效修复） ====================
    //
    // 为什么需要它：provider 是**跨进程**的，开机时模块 app 还没起来，SystemUI 的
    // 首次 query 必然失败，要重试 1~3 秒才拿到数据。而 SystemUI 在这之前就已经
    // 把锁屏 / 通知栏 / 材质渲染完了，那段窗口里 Cfg.isOn(K_GLASS) 对 opt-in 键
    // 返回 false（见 isOn 的注释），整条玻璃线按"开关关着"跑原厂值。
    //
    // 更麻烦的是**晚到的配置不会让已渲染的元素重算**：
    //   · 玻璃线 startPreload(false) —— 快照冻结，不会二次应用
    //   · MaterialTransparentEngine.mHnMaterial —— 建一次就缓存，参数对了也不重建
    // 所以必须在**第一帧之前**就有正确的开关值，不能等 provider。
    //
    // 缓存里存的是**上一次真正生效的配置**（loadOnce 成功那一刻写盘）。它天然
    // 等于用户当前的设置 —— 因为改开关本来就要重启 SystemUI 才生效，而每次
    // SystemUI 成功加载都会把缓存刷新一遍。
    //
    // 用 filesDir 不用 cacheDir：cacheDir 会在低存储时被系统清掉，而这份数据
    // 恰恰是"低存储/开机卡顿"时最需要的。
    private static final String CACHE_FILE = "cfg_snapshot.txt";
    /** 格式版本。改动缓存结构时递增，旧文件会被丢弃而不是误读。 */
    private static final String CACHE_VER = "v2";
    /** 只尝试读一次，避免 30 轮重试里反复读盘。 */
    private static boolean sCacheTried = false;
    /** 当前快照是不是从缓存来的（用于"缓存与线上不一致"的告警判据）。 */
    private static volatile boolean sSnapshotFromCache = false;

    /**
     * 当前进程名，直接读 {@code /proc/self/cmdline}，**不依赖 Context**。
     *
     * <p>为什么不能用 {@link #apiContext()}：
     * <ul>
     *   <li>{@code AndroidAppHelper.currentApplication()} 在本回调时刻返回 null ——
     *       Xposed 的 handleLoadPackage 发生在 {@code handleBindApplication} 里
     *       {@code makeApplication()} **之前**，Application 还没实例化</li>
     *   <li>{@code ActivityThread.getSystemContext()} 对**普通 app 进程**同样返回 null
     *       —— {@code mSystemContext} 只在 {@code systemMain()} 里赋值，app 进程走的是
     *       {@code attach(false, ...)} 那条分支，这个字段从未被写过（apiContext 的注释
     *       「系统进程一定有」说的就是这件事）</li>
     * </ul>
     * 而开机读缓存**必须**在 handleLoadPackage 里同步完成，等 Context 可用时
     * SystemUI 早就把锁屏渲染完了。所以这里绕开 Context，用进程名拼路径。
     *
     * <p>子进程（{@code pkg:remote}）的数据目录和主进程是同一个，冒号后缀要去掉。
     */
    private static String selfProcessName() {
        java.io.FileInputStream in = null;
        try {
            in = new java.io.FileInputStream("/proc/self/cmdline");
            byte[] buf = new byte[256];
            int n = in.read(buf);
            if (n <= 0) {
                return null;
            }
            int end = 0;
            while (end < n && buf[end] != 0) {
                end++;
            }
            if (end <= 0) {
                return null;
            }
            String name = new String(buf, 0, end, "UTF-8").trim();
            int colon = name.indexOf(':');
            if (colon > 0) {
                name = name.substring(0, colon);
            }
            return name.length() == 0 ? null : name;
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 缓存文件句柄；{@code create=true} 时顺带建目录。拿不到返回 null。 */
    private static java.io.File cacheFile(boolean create) {
        String pkg = selfProcessName();
        if (pkg == null) {
            return null;
        }
        // DE（device-encrypted）优先：**直接开机到解锁前这个阶段也能读**。
        // 玻璃最要命的就是锁屏首帧，那时用户还没解锁，CE 存储根本不可用。
        java.io.File de = cacheFileIn("/data/user_de/0/" + pkg + "/files", create);
        // 兜底：CE 目录（/data/data 是指向 /data/user/0 的软链）
        java.io.File ce = cacheFileIn("/data/data/" + pkg + "/files", create);
        if (!create) {
            // 读：**必须按"文件存在"来选，不能只看目录存在** ——
            // DE 目录可能建得出来但缓存实际写在 CE（或反过来），
            // 按目录选会直接读空，然后 sCacheTried 一设就再也不试了。
            if (de != null && de.exists()) {
                return de;
            }
            if (ce != null && ce.exists()) {
                return ce;
            }
            return null;
        }
        // 写：DE 能写就写 DE（它解锁前可读），否则退回 CE
        return de != null ? de : ce;
    }

    private static java.io.File cacheFileIn(String base, boolean create) {
        try {
            java.io.File dir = new java.io.File(base);
            if (!dir.isDirectory()) {
                if (!create) {
                    return null;
                }
                dir.mkdirs();
            }
            if (!dir.isDirectory()) {
                return null;
            }
            if (create && !dir.canWrite()) {
                // 目录在但不可写（SELinux / 配额）：写的时候直接放弃这个候选，
                // 读的时候无所谓 —— 读不需要写权限。
                return null;
            }
            return new java.io.File(dir, CACHE_FILE);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 用本地副本填 sSnapshot。拿不到路径 / 文件不存在 / 格式不符都静默返回 ——
     * 这只是兜底，失败不影响原有重试流程。
     */
    private static void fillFromCache() {
        if (sCacheTried || sSnapshot != null) {
            return;
        }
        java.io.File f = cacheFile(false);
        if (f == null) {
            // 连进程名都拿不到（极早期）：**不设** sCacheTried，留给后面几轮再试
            return;
        }
        sCacheTried = true;
        java.io.BufferedReader r = null;
        try {
            if (!f.exists() || f.length() <= 0L) {
                return;
            }
            r = new java.io.BufferedReader(new java.io.InputStreamReader(
                    new java.io.FileInputStream(f), "UTF-8"));
            String first = r.readLine();
            if (!CACHE_VER.equals(first)) {
                // 用 note 不走 warn：warn 在 SystemUI 里被 diag 门控吞掉，
                // 而"缓存版本不符"正好是升级后第一次开机才会发生、必须看见的事。
                Diag.note("Cfg cache: 版本不符(" + first + ")，已忽略");
                return;
            }
            // 版本握手。缓存必须带 build fingerprint + versionCode + 时间戳；
            // 任何一项不匹配（换机/换 ROM/模块升级）都视为失效，避免把旧包的观感
            // 带到新版本。时间戳只用于日志，不做短过期（常驻进程可能几天不重启）。
            String fpLine = r.readLine();
            String vcLine = r.readLine();
            String tsLine = r.readLine();
            String fp = android.os.Build.FINGERPRINT;
            if (fpLine == null || !fpLine.equals("fp=" + fp)) {
                Diag.note("Cfg cache: fingerprint 不符，已丢弃");
                try { f.delete(); } catch (Throwable ignored) { }
                return;
            }
            if (vcLine == null || !vcLine.equals("vc=" + BuildConfig.VERSION_CODE)) {
                Diag.note("Cfg cache: versionCode 不符，已丢弃");
                try { f.delete(); } catch (Throwable ignored) { }
                return;
            }
            if (tsLine == null || !tsLine.startsWith("ts=")) {
                Diag.note("Cfg cache: 缺少时间戳，已丢弃");
                try { f.delete(); } catch (Throwable ignored) { }
                return;
            }
            // epoch 三态。
            //   known 且一致  -> 用缓存
            //   known 且不符  -> 丢弃，回退 provider
            //   unknown       -> fail-open 接受本次缓存 + 日志写清
            // 没有 ep= 的缓存无法校验，vc 升级时已经会被版本握手丢掉；
            // 这里再遇到缺 ep 一律当"无法证明有效"→ 丢弃。
            String epLine = r.readLine();
            int cacheEp;
            if (epLine == null || !epLine.startsWith("ep=")) {
                Diag.note("Cfg cache: 缺少 epoch，已丢弃");
                try { f.delete(); } catch (Throwable ignored) { }
                return;
            }
            try {
                cacheEp = Integer.parseInt(epLine.substring(3).trim());
            } catch (Throwable t) {
                Diag.note("Cfg cache: epoch 解析失败(" + epLine + ")，已丢弃");
                try { f.delete(); } catch (Throwable ignored) { }
                return;
            }
            int curEp = glassEpoch();
            if (curEp == EPOCH_UNKNOWN) {
                // 属性读不到：宁可接受一次旧缓存，也不要把缓存永久判死。
                // 下一次进程启动如果属性恢复，缓存会被重新比对并纠正。
                Diag.note("Cfg cache: epoch 未知（属性读取失败），"
                        + "fail-open 采用缓存 ep=" + cacheEp);
            } else if (cacheEp != curEp) {
                Diag.note("Cfg cache: epoch 不符(cache=" + cacheEp
                        + ", current=" + curEp + ")，已丢弃并回退 provider");
                try { f.delete(); } catch (Throwable ignored) { }
                return;
            }
            Map<String, int[]> m = new HashMap<String, int[]>();
            String line;
            while ((line = r.readLine()) != null) {
                int eq = line.indexOf('=');
                if (eq <= 0 || eq >= line.length() - 1) {
                    continue;
                }
                try {
                    m.put(line.substring(0, eq),
                            new int[]{Integer.parseInt(line.substring(eq + 1))});
                } catch (NumberFormatException ignored) {
                }
            }
            if (m.isEmpty()) {
                return;
            }
            if (sSnapshot == null) {
                sSnapshot = m;
                sSnapshotFromCache = true;
                sCachedEpoch = cacheEp;
                markReady();
                // 这行用 note 而不是 log：note 不受诊断开关控制，用户反馈
                // "开机玻璃没了"时能直接在日志里看到首帧用的是哪份配置。
                Diag.note("Cfg cache hit: " + m.size() + " 项（首帧用上次生效的配置）");
            }
        } catch (Throwable t) {
            Diag.note("Cfg cache read failed: " + t.getClass().getSimpleName());
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 把一份快照写到本地副本。只在内容变化时调用（观察者会反复 loadOnce）。 */
    private static void writeCache(Map<String, int[]> m) {
        if (m == null || m.isEmpty()) {
            return;
        }
        java.io.File dst = cacheFile(true);
        if (dst == null) {
            Diag.note("Cfg cache: 拿不到可写目录，缓存未落盘（下次开机仍走重试）");
            return;
        }
        java.io.File tmp = null;
        java.io.FileOutputStream fos = null;
        try {
            StringBuilder sb = new StringBuilder(4096);
            sb.append(CACHE_VER).append('\n');
            sb.append("fp=").append(android.os.Build.FINGERPRINT).append('\n');
            sb.append("vc=").append(BuildConfig.VERSION_CODE).append('\n');
            sb.append("ts=").append(System.currentTimeMillis()).append('\n');
            // epoch 只有一个来源（属性）。读不到就**不写缓存**，
            // 免得写进一个无法校验的 ep=0/-1，下一次启动反而永久丢缓存。
            int ep = glassEpoch();
            if (ep == EPOCH_UNKNOWN) {
                Diag.note("Cfg cache write: epoch 未知，跳过写缓存");
                return;
            }
            sb.append("ep=").append(ep).append('\n');
            for (Map.Entry<String, int[]> e : m.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) {
                    continue;
                }
                sb.append(e.getKey()).append('=').append(e.getValue()[0]).append('\n');
            }
            byte[] body = sb.toString().getBytes("UTF-8");
            tmp = new java.io.File(dst.getParentFile(), CACHE_FILE + ".tmp");
            fos = new java.io.FileOutputStream(tmp);
            fos.write(body);
            fos.flush();
            fos.getFD().sync();
            fos.close();
            fos = null;
            // 先写 .tmp 再 rename：直接覆写会在开机中途断电时留下半截文件，
            // 下次开机读到的就是残缺配置（比没有更糟）。
            if (!tmp.renameTo(dst)) {
                if (!(dst.exists() && dst.delete() && tmp.renameTo(dst))) {
                    Diag.note("Cfg cache write: rename failed (" + dst.getPath() + ")");
                    return;
                }
            }
            // 校验：写出去了但读不回来（权限/SELinux/配额）等于没写。
            // 这条日志是真机排查「为什么开机还是失效」的第一现场。
            if (dst.length() == body.length) {
                if (!sCacheWrote) {
                    sCacheWrote = true;
                    Diag.note("Cfg cache written: " + m.size() + " 项 / "
                            + body.length + " B -> " + dst.getPath());
                }
            } else {
                Diag.note("Cfg cache write: 长度不符 (expect " + body.length
                        + ", got " + dst.length() + ")");
            }
        } catch (Throwable t) {
            Diag.note("Cfg cache write failed: " + t.getClass().getSimpleName());
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (Throwable ignored) {
                }
            }
            if (tmp != null && tmp.exists()) {
                try {
                    tmp.delete();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 只在第一次写成功时打一条日志，避免观察者刷新时刷屏。 */
    private static boolean sCacheWrote = false;

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
            // 快照和本地缓存对不上 = 用户改过设置但没让 SystemUI 重新加载，
            // 而玻璃线是冻结的、已渲染的元素不会重算。这条告警专门给排查用：
            // 用户反馈「界面显示的设置和实际效果不一致」时，日志里能直接看到。
            if (sSnapshotFromCache) {
                Diag.warn("Cfg 快照与本地缓存不一致（epoch "
                        + sCachedEpoch + "→" + glassEpoch()
                        + "）：本进程已按缓存渲染，重启 SystemUI 后才会用线上值");
            }
            // 每次真正拿到线上值都刷新本地副本 —— 它代表"下一次开机时该用什么"。
            writeCache(m);
        }
        sSnapshot = m;
        sSnapshotFromCache = false;
        markReady();
        return true;
    }
}
