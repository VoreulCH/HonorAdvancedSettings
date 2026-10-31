# HonorAdvancedSettings（药师 · 荣耀高级设置）

> ## ⚠️ 本模块由 AI 生成 / This module is AI-generated
> 本项目由大语言模型（AI）在人类指导下生成并迭代维护，包括全部逆向分析、Hook 代码、
> 构建流水线与文档。代码未经人工长期审计，请自行评估风险后使用；欢迎人工审查与 PR。
>
> This project was generated and iterated by a large language model (AI) under human
> direction, including all reverse engineering, hook code, the build pipeline and these docs.
> The code has not been long-term audited by humans — evaluate the risk yourself;
> human review and PRs are welcome.

基于MagicOS11开发 by VoreulCH@GitHub

> **源码仓库 / Source repository**：https://github.com/VoreulCH/HonorAdvancedSettings
> 本页面所在仓库是 LSPosed 官方发布镜像（用于 modules.lsposed.org 索引与分发），
> 源码、issue、讨论请走上面的源码库。
> This repository is the LSPosed distribution mirror; source code, issues and
> discussions live in the source repository above.

面向荣耀 MagicOS 11 的一站式 LSPosed 模块，整合了前作 **荣耀液态玻璃恢复（HonorLiquidGlassRestore）** 与 **荣耀安装器净化（HonorInstallerPurify）** 的全部功能，并重写了模块界面：

- **液态玻璃**：还原系统 OTA 更新阉割的控制中心 / 通知中心液态玻璃，覆盖**系统 / 锁屏 / 通知横幅 / 桌面 / 相册**五条线，每线独立开关；配方按界面分组、共 **71 项精确参数**（100% = 原厂，0~200% 可调），从「通知卡底色」到「锁屏时钟的玻璃腐蚀强度」都能单独拖。
- **安装器净化**：跳过安装器联网安全检测与指纹预装提示、禁止联网、隐藏推广卡片，支持降级安装；**钩子点自适应解析**（DexKit 字符串锚 + 参数签名扫描），荣耀安装器 OTA 混淆重排后大概率无需更新模块。
- **高温限制解除**（需 ROOT）：高温时不弹「建议暂停使用」提示、不限制屏幕亮度、系统热状态按正常上报、压制 PowerGenie 温控（游戏限帧 / 后台清理）、高温自动关机不再触发；默认关闭、开启前二次确认，内核调度与降频不受影响。
- **应用内开关**：所有功能在 miuix 风格界面以开关呈现，配置经 ContentProvider 桥跨进程同步，安装器线改开关无需重启，玻璃线一键重启作用域。

An all-in-one LSPosed module for Honor MagicOS 11 that merges the previous
**HonorLiquidGlassRestore** and **HonorInstallerPurify** modules: restores the
liquid-glass effect gutted by the system update (system shade / lock screen /
heads-up banner / launcher / gallery, each independently switchable, with 71
fine-grained parameters grouped per surface), cleans up the package installer
(skip the online security check & preinstall fingerprint prompt, block
networking, hide promo cards, allow downgrades), and — with root — lifts the
system's high-temperature restrictions (no popup, no brightness cap, thermal
status reported normal, PowerGenie thermal policies suppressed) — all
toggleable in-app, with cross-process config sync via a ContentProvider bridge.

## 功能一览 / Features

### 液态玻璃 / Liquid glass

| 功能 Feature | 说明 Description |
| --- | --- |
| 系统液态玻璃 / System shade | 还原 LIQUID 材质位、强制 HIGH 机型渲染路径、材质类型改判（大方法 Hook，AOT 内联免疫）；面板遮罩 / 模糊半径 / 光泽 / 折射 / 色散 / 边光按定稿配方烤入 Restores the LIQUID material bit, HIGH render tier and material type (big-method hooks, inline-proof); veil / blur / gloss / refraction / dispersion / rim light baked in pre-calibrated |
| 锁屏液态玻璃 / Lock screen | 锁屏通知卡片与快捷按钮获得与通知栏一致的液态玻璃：卡面亮度、纱深浅、边光、合成模糊半径逐项对齐；静态壁纸下从锐利壁纸经原厂 `ViewBlur.blurBitmap` 管线合成位图 Lock-screen cards & buttons match the shade look item by item; a blur bitmap is synthesized from sharp wallpapers through the stock pipeline |
| 相册液态玻璃 / Gallery | 相册照片页工具栏的液态玻璃效果 Liquid glass on the gallery photo-page toolbar |
| 分线开关 / Per-line switches | 三条线独立开关；系统级底层钩子（材质判定 / HIGH 强制渲染）归总开关管辖，避免关闭分开关连带破坏依赖底层的玻璃效果 Per-line switches; system-level hooks live under the master switch so turning a sub-switch off never breaks the shared base |
| 桌面液态玻璃 / Launcher | 桌面卡片与文件夹的模糊 / 饱和度 / 亮度，独立开关 Card & folder blur / saturation / brightness, own switch |
| 精细调节 / Fine tuning | **71 项参数，按界面分组**：系统 42（通知卡 / 大按钮 / 小按钮 / 滑块 / 光学 / 模糊 / 高光）、锁屏 25（通知卡 / 时钟腐蚀 / 按钮·图标·文字各 5 / 小组件 / 壁纸指纹）、横幅 5、桌面 3。100% = 原厂 71 parameters grouped per surface: 42 system, 25 lock screen, 5 banner, 3 launcher; 100% = stock |
| 强制液态 / Force liquid | 某些界面系统会自己判定成磨砂，这个开关强制它们走液态折射 Forces surfaces the system renders as frosted onto the liquid path |

### 安装器净化 / Installer purify

| 功能 Feature | 说明 Description |
| --- | --- |
| 跳过安全检测 / Skip security check | 安装前不再上传 APK 做联网云检测与指纹预装提示 No more pre-install cloud scan or fingerprint prompt |
| 禁止联网 / No networking | 阻断安装器的推广与上报请求 Blocks the installer's promo/telemetry requests |
| 安装包信息卡 / Info card | 展示版本 / 大小 / SDK / 架构信息卡；可关闭（关闭时同时隐藏安全模式提醒） Version / size / SDK / ABI info card, toggleable (off also hides the safe-mode reminder) |
| 允许降级安装 / Downgrade | 允许安装低于当前版本号的 APK Allows installing lower-version APKs |
| 安装提速 / Faster staging | 跳过「准备安装」阶段的腾讯免检与分流网络等待（禁网后不再干等超时） Skips the Tencent no-detect + diversion network waits during staging |
| 风险安装弹窗 / Risk tip dialog | 高风险应用安装前的「风险提示」自动继续，无需手动点「继续安装」 Auto-continues the risk-tip dialog |
| 匿名来源弹窗 / Anonymous source | 无来源包名（PC/文件管理器传入）时静默放行、零弹窗；任何失败都自动回退为可见可点的原厂弹窗，绝不吞触摸 Manual-free anonymous-source handling with a visible-dialog fallback on any failure |
| 自适应钩子 / Adaptive hooks | 类经 DexKit 字符串锚定位、方法经参数签名扫描定位（锚=跨版本稳定的实体完整名）；放行结果双配方结构探测——安装器 OTA 混淆重排后大概率免更新 Classes located via DexKit string anchors, methods via parameter-signature scanning (anchored on stable entity full names); pass-result delivery tries two known recipes — installer OTA obfuscation reshuffles usually need no module update |

### 系统综合 / System misc

| 功能 Feature | 说明 Description |
| --- | --- |
| 屏蔽通知回避 / No audio ducking | 通知铃声响起时不再压低正在播放的音乐，两者同时发声；闹钟不受影响（默认关闭） Notification sounds stop ducking your music; alarms unaffected (opt-in) |
| 屏蔽高温限制（ROOT）/ Thermal bypass | 高温时不弹「建议暂停使用」、不限屏幕亮度、系统热状态按正常上报、压制 PowerGenie 温控（游戏限帧 / 后台清理）、高温自动关机不再触发；开关格内实时显示系统层状态与属性值。**需 ROOT**（未授权时仅弹窗屏蔽生效），默认关闭、开启前二次确认；**内核调度与降频不受本开关影响** Disables the system's high-temperature restrictions with one switch (popup, brightness cap, thermal status reporting, PowerGenie policies, emergency shutdown); requires root, opt-in with a confirmation dialog; kernel-level throttling is untouched |

### 作用域自检与诊断 / Scope self-check & diagnostics

| 功能 Feature | 说明 Description |
| --- | --- |
| 作用域自检 / Scope self-check | 每个被 hook 的进程启动时向模块上报一次（走模块自己的 ContentProvider，不需要 ROOT），首页顶部实时显示模块是否生效、已加载哪些作用域；各开关下方**只在未生效时**点名具体作用域（如「作用域状态：系统界面作用域未生效（勾选并重启作用域生效）」），生效后自动隐藏。LSPosed 与 Vector 通用，也适用于任何分叉版本 |
| 重启作用域 / Restart scopes | 可勾选多个一次重启：系统界面 / 相册 / 安装器 / 桌面 / 桌面模式 / 温控服务（需 ROOT） |
| 诊断日志 / Verbose diagnostics | 关于页开关，开启后向 LSPosed 模块日志输出环境快照（模块/框架/宿主版本、进程身份、设备）、开关快照、每个钩子的挂载结果、运行事件与异常堆栈；重复日志压缩为 `*N`。开启即时生效，日常建议关闭 |
| 检查更新 / Update check | 点击查询 GitHub 最新 release（超时或断网会说明具体原因），发现新版本直接打开 APK 下载页 |


## 环境要求 / Requirements

* 已 root 的荣耀 MagicOS 11（Android 17）设备：Magisk / KernelSU / ReSukkiSU + LSPosed（或 Vector 等第三方管理器）；模块要求 Android 13+（minSdk 33）。
  A rooted Honor MagicOS 11 (Android 17) device: Magisk / KernelSU / ReSukkiSU + LSPosed (or a third-party manager such as Vector); the module requires Android 13+ (minSdk 33).
* 作用域：**系统界面**、**荣耀桌面**（`com.hihonor.android.launcher` / `com.hihonor.desktop.systemui`）、**相册**、**安装器**、**系统框架**（降级安装）；**高温限制解除另需 `com.hihonor.powergenie` 与 `com.hihonor.iaware`**（安装包内已声明全部推荐作用域，管理器通常可一键应用）。
  Scopes: SystemUI, the Honor launcher, the gallery and the package installer (recommended scope declared in the APK manifest).
* 仅在 MagicOS 11 上实测；其他版本类名可能变化，Hook 未命中时功能静默失效（不影响系统稳定）。
  Tested on MagicOS 11 only; on other versions the hooks may silently miss (nothing breaks).

## 使用方法 / Usage

1. 安装 APK，在 LSPosed / Vector 中启用模块（推荐作用域自动应用）。
   Install the APK and enable the module (recommended scopes auto-apply).
2. 打开「药师」应用，按需开关各功能。
   Open the 药师 (HonorAdvancedSettings) app and toggle features as needed.
3. 玻璃线改动：应用内「重启作用域」一键重启系统界面（或重启手机）；安装器线改动即时生效。
   Glass changes take effect after a scope restart from the app; installer changes apply immediately.
4. **与前作二选一**：本模块与 HonorLiquidGlassRestore / HonorInstallerPurify 功能重叠，请勿同时启用（避免钩子重复挂载）。从旧模块迁移：禁用并卸载旧模块后启用本模块。
   **Do not run alongside the two predecessor modules** (duplicate hooks). Migrate by disabling/uninstalling them first.

## 工程要点 / Engineering notes

* **跨进程配置同步**：部分管理器（Vector）不投递 LSPosed 配置、SELinux 又限制跨应用直读配置文件——开关状态经模块导出的 `CfgProvider`（ContentProvider 桥）传递；安装器作用域注册 `ContentObserver` 即时刷新，玻璃作用域随进程重启生效。
  Config sync: some managers never deliver LSPosed prefs and SELinux blocks cross-app file reads, so switch states travel through the module's exported ContentProvider; installer scopes observe changes live, glass scopes pick them up on process start.
* **死锁防线**：钩子回调只读内存快照（volatile），任何 Binder 调用都发生在专用后台线程——杜绝在渲染热路径上做跨进程查询。
  Hooks read in-memory snapshots only; binder calls happen on dedicated background threads — never on the render hot path.
* **只 Hook 大方法或构造器**：小 getter 会被 AOT 内联绕过；目标方法（`getTransparentModeState`、`initBulrParams`、构造器级 Hook）全部内联免疫。
  Only big methods or constructors are hooked — inline-proof against AOT.
* **烤入定稿默认值**：全部观感参数按逐项调校的配方烤入 APK，装上即得调校后的观感。
  All optics defaults are baked in pre-calibrated.

## 从源码构建 / Build from source

v2.0.0 起主构建链为 Gradle（Kotlin + Compose + miuix）：

```
gradle assembleRelease
```

签名凭据放在 `local.properties`（`STORE_PASS` / `KEY_PASS`，不入库，见 KEYSTORE.txt），JDK 21 + Android SDK 由 `local.properties` 的 `sdk.dir` 指定。R8 混淆 + 资源收缩默认开启；Xposed 入口 / Provider / 钩子类的 keep 规则见 `app/proguard-rules.pro`。

`module/` 内保留 v1.4.22 的纯 Java 源码与免 Gradle 构建链（`build.ps1`：aapt2 → ecj → d8 → 注入 → zipalign → apksigner）作为历史归档，不再演进。

## 项目结构 / Project layout

```
├── app/                                   # 主工程（Gradle / Kotlin / Compose）
│   ├── build.gradle.kts                   # 版本号 / 依赖 / 签名配置
│   ├── proguard-rules.pro                 # R8 keep 规则（Xposed 入口等）
│   └── src/main/
│       ├── AndroidManifest.xml            # xposedmodule 元数据与作用域
│       ├── java/io/github/voreulch/honoradvanced/
│       │   ├── MainHook.java              # 入口分发 / entry dispatch
│       │   ├── GlassHook.java             # 液态玻璃钩子（71 项精细调节）/ liquid-glass hooks
│       │   ├── InstallerHook.java         # 安装器净化钩子（自适应解析）/ adaptive installer hooks
│       │   ├── DexKitHelper.java          # DexKit 运行时 dex 搜索封装
│       │   ├── Cfg.java                   # 配置快照与门控（开关 + 精细调节值）
│       │   ├── CfgProvider.java           # 跨进程配置桥 / cross-process config bridge
│       │   └── ...SettingsActivity.kt 等   # 见 kotlin/ 目录（miuix UI）
│       ├── kotlin/io/github/voreulch/honoradvanced/
│       │   ├── SettingsActivity.kt        # 模块界面（Compose + miuix）
│       │   └── ui/component/              # 底栏 / 液态玻璃组件 / 手势
│       ├── assets/xposed_init             # Xposed 入口声明
│       ├── assets/img/                    # 图标与二维码
│       └── res/                           # 矢量图标 / 字符串 / 作用域数组
├── module/                                # v1.4.22 纯 Java 源码归档（旧免 Gradle 构建链）
├── build.gradle.kts / settings.gradle.kts # Gradle 工程配置
├── build.ps1                              # 旧构建链（module/ 归档用）
└── voreulch.keystore                      # 签名密钥（不入库）
```

## 往期更新摘要 / Recent highlights

- **2.1.2（58）**：适配荣耀安装器 **14.0.39.301** 新构建；修复部分渠道（如 MT 管理器）安装时匿名来源弹窗消失后「安装」按钮点不动的问题；修复安装全新应用被误标「降级安装」；移除历史调试代码、压缩冗余。
- **2.0.2（48）**：新增「杂项」页与**屏蔽通知回避**（通知音响起时不再压低正在播放的音乐，闹钟不受影响）；安装器净化适配 **14.0.32.303 / 14.0.22.327**（累计覆盖 38.301 · 32.303 · 22.326 · 22.327 四版，自适应零开关）；隐藏安装页「未发现风险」残留卡片（与渲染同帧，无闪现）；液态玻璃通知卡片 / 工具栏按钮参数回迁 1.4.22 认可配方。
- **3.0.1（119）**：**液态玻璃大更新** —— 从「一套配方」改成 **71 项按界面分组的精细调节**（系统 42 / 锁屏 25 / 横幅 5 / 桌面 3 + 强制液态开关）；打通锁屏四条以前调不了的链路（时钟腐蚀表 / 按钮·图标·文字的通用参数包 / 小组件 / 壁纸与指纹）；新增桌面液态玻璃（独立开关）；三级菜单重构；**DIY 模式整体移除**（被精细调节完全覆盖）；修 4 个真 bug（桌面光学被绝对值覆盖、参数重复应用叠乘、强制液态开关状态造假、高温旁路缓存初值方向写反）。
- **2.0.0（46）**：大版本重构 —— DIY 模式（14 个 0~100 滑条实时调观感）、界面 miuix 化、安装器净化自适应钩子集（兼容模式开关取消）、安装提速。
- 2.0.3 ~ 2.0.9 为内部迭代版本，未单独发布，改动已全部并入 2.1.2。

## 版本历史 / Changelog
* **2.3.10**（2026-11-01）：新增**作用域自检**——每个被 hook 的进程启动时向模块自证一次（无需 ROOT，LSPosed / Vector 通用），首页顶部显示「模块已生效 / 未生效」与已加载的作用域；各开关下方只在作用域未生效时点名具体作用域，生效后自动隐藏；打开开关时若依赖的作用域没生效会立刻提醒。「重启作用域」改为**可多选批量重启**（系统界面 / 相册 / 安装器 / 桌面 / 桌面模式 / 温控服务）。关于页新增**诊断日志**（环境快照、钩子挂载点、开关状态、异常堆栈，重复日志压缩为 `*N`，开启即时生效）、**检查更新**（GitHub 最新版本，超时/失败给出具体原因，有新版直接打开下载）与**特别鸣谢**名单。

> 版本号规则（2026-11-01 起）：**新功能进位到 2.x**（如 2.1 → 2.2），**修复用 2.x.xx**（第三段为该项功能的迭代次数）。

* **2.2.9**（2026-10-31）：「屏蔽高温限制（ROOT）」默认值改为**关闭**——ROOT 级安全功能需用户显式开启（开启前二次确认）；已开启的安装不受影响，状态保留。
* **2.2.8**（2026-11-01）：新增**高温不降级**——「系统综合 → 屏蔽高温限制（ROOT）」一个开关解除系统高温限制：不再弹「建议暂停使用」提示、不限制屏幕亮度、系统热状态按正常上报、压制 PowerGenie 温控（游戏限帧/后台清理）、高温自动关机不再触发；开关格内实时显示系统层状态与属性值。需 ROOT 写入系统属性（未授权时仅弹窗屏蔽生效）。**内核调度与降频不受本开关影响。**
* **2.1.2**（2026-10-31，合并 2.0.3~2.1.2）：安装器净化新增第五版适配 **14.0.39.301**（现覆盖 38.301 / 32.303 / 22.326 / 22.327 / 39.301，多锚点合并定位）；新增**风险安装弹窗自动继续**与**匿名来源弹窗零弹出**——无来源包名（PC 工具 / 文件管理器传入）时静默放行，任何一步失败都自动回退为原厂可见弹窗，绝不出现吞触摸的隐形窗口；修复安装包信息卡误标「降级安装」（此前部分构建拿安装来源应用的版本做比较，全新应用也会被标红）；删除全部调试诊断代码，日志只保留安装与运行验证行；代码精简与零引用依赖清理（APK 4.20MB → 3.97MB）；关于页版本号改为动态读取。
* **2.0.3**（2026-10-28）：代码审查精简版（零行为变化）。关于页版本号改为动态读取（此前硬编码停留在 2.0.0）；补上玻璃线诊断日志缺失的调试开关门控（此前默认向 Xposed 日志刷屏）；删除死代码（恒不生效的相册校准路径、无引用常量与 import 等，GlassHook 减 88 行）；移除零引用依赖 material-kolor / miuix-preference / miuix-icons，APK 缩小 245KB（4.2MB→3.97MB）。
* **2.0.2**（2026-10-28，合并 2.0.1）：新增「杂项」页与**屏蔽通知回避**——通知铃声响起时不再压低正在播放的音乐（两者同时发声，闹钟不受影响，开关即时生效）；「DIY液态」文案修正为「DIY液态玻璃」；安装器净化适配 **14.0.32.303 与 14.0.22.327**（净化线现全覆盖 38.301 / 32.303 / 22.326 / 22.327 四版，自适应零开关）；安全检测放行改用安装器原生的安全默认结果构造；隐藏安装页「未发现风险」卡片（与渲染同帧隐藏，无闪现）；广告钩子签名形状扩展；液态玻璃通知卡片 / 工具栏按钮参数回迁 1.4.22 认可配方。
* **2.0.0**（2026-10-24）：大版本重构。**DIY 模式**——14 个 0~100 滑条（遮罩 / 模糊 / 圆角 / 饱和度 / 边缘光 / 折射 / 边框 / 厚度 / 色散 / 锁屏参数）实时覆盖烤入配方，默认关闭、滑条默认值=旧极致观感；**界面 miuix 化**（Kotlin + Compose + miuix 组件库，液态玻璃底栏，APK 从 689KB 的纯 Java 版跃迁后瘦身至 4.2MB）；**安装器净化自适应钩子集**——类经 DexKit 字符串锚、方法经参数签名扫描定位（锚=跨版本稳定的实体完整名），放行结果双配方结构探测，安装器 OTA 混淆重排后大概率无需更新模块，**14.0.22.x 旧版安装器同样自动适配，兼容模式开关取消**；安装提速（跳过准备安装阶段的网络等待）；全面代码审查修复（升级用户 DIY 开关误开防护等）。
* **1.4.22**（2026-10-20）：适配 2026-10 系统更新后的运行时安装器 14.0.38.301（新版混淆钩子集设为默认方案，总开关开启即生效，无需任何兼容开关）；安装提速——跳过「准备安装」阶段的腾讯免检与分流网络等待，禁网后不再干等超时；隐藏安装页「未发现风险」检测残留卡片；兼容模式拆分为「兼容模式1」（早期版本安装器）与「兼容模式2」（14.0.22.x），两者互斥（开启一方自动关闭另一方），旧「兼容模式」开关状态自动迁移到兼容模式2；信息卡 / 降级安装 / 禁止联网等开关在所有方案下均有效。
* **1.4.20**（2026-10-14）：安装器净化新增「兼容模式」开关——安装器为较旧版本（如 14.0.22.x）时开启，切换到旧方案钩子集；其余开关在兼容模式下同样生效，关闭即恢复原行为，总开关关闭时全部回退原厂。
* **1.4.19**（2026-10-10）：公开首发。整合液态玻璃（系统 / 锁屏 / 相册三线 + 总开关）与安装器净化（安全检测跳过 / 禁网 / 信息卡 / 降级安装）为单一模块；全新界面（分段胶囊底栏、玻璃卡片弹窗、捐赠与 QQ 群入口）；ContentProvider 跨进程配置同步；Android 15+ edge-to-edge 兼容。
  First public release: merges the liquid-glass lines (system / lock screen / gallery + master switch) and installer purify into one module, with a brand-new UI, cross-process config sync and Android 15+ edge-to-edge compatibility.

## 免责声明 / Disclaimer

* 本项目仅供学习与研究 Android Hook 技术使用，请勿用于商业用途。
  For learning and research on Android hook techniques only; do not use commercially.
* 与荣耀公司 / HONOR 无任何关联；相关商标与系统界面版权归原厂所有。
  Not affiliated with Honor Device Co., Ltd.
* 使用本模块产生的任何后果由使用者自行承担。Use at your own risk.
