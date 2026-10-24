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

面向荣耀 MagicOS 11 的一站式 LSPosed 模块，整合了前作 **荣耀液态玻璃恢复（HonorLiquidGlassRestore）** 与 **荣耀安装器净化（HonorInstallerPurify）** 的全部功能，并重写了模块界面：

- **液态玻璃**：还原系统 OTA 更新阉割的控制中心 / 通知中心液态玻璃，覆盖系统、锁屏、相册三条线，每线独立开关；定稿配方烤入，装上即得调校后的观感；另有 DIY 模式提供 14 个 0~100 滑条实时调观感。
- **安装器净化**：跳过安装器联网安全检测与指纹预装提示、禁止联网、隐藏推广卡片，支持降级安装；**钩子点自适应解析**（DexKit 字符串锚 + 参数签名扫描），荣耀安装器 OTA 混淆重排后大概率无需更新模块。
- **应用内开关**：所有功能在 miuix 风格界面以开关呈现，配置经 ContentProvider 桥跨进程同步，安装器线改开关无需重启，玻璃线一键重启作用域。

An all-in-one LSPosed module for Honor MagicOS 11 that merges the previous
**HonorLiquidGlassRestore** and **HonorInstallerPurify** modules: restores the
liquid-glass effect gutted by the system update (system shade / lock screen /
gallery, each independently switchable) and cleans up the package installer
(skip the online security check & preinstall fingerprint prompt, block
networking, hide promo cards, allow downgrades) — all toggleable in-app, with
cross-process config sync via a ContentProvider bridge.

## 功能一览 / Features

### 液态玻璃 / Liquid glass

| 功能 Feature | 说明 Description |
| --- | --- |
| 系统液态玻璃 / System shade | 还原 LIQUID 材质位、强制 HIGH 机型渲染路径、材质类型改判（大方法 Hook，AOT 内联免疫）；面板遮罩 / 模糊半径 / 光泽 / 折射 / 色散 / 边光按定稿配方烤入 Restores the LIQUID material bit, HIGH render tier and material type (big-method hooks, inline-proof); veil / blur / gloss / refraction / dispersion / rim light baked in pre-calibrated |
| 锁屏液态玻璃 / Lock screen | 锁屏通知卡片与快捷按钮获得与通知栏一致的液态玻璃：卡面亮度、纱深浅、边光、合成模糊半径逐项对齐；静态壁纸下从锐利壁纸经原厂 `ViewBlur.blurBitmap` 管线合成位图 Lock-screen cards & buttons match the shade look item by item; a blur bitmap is synthesized from sharp wallpapers through the stock pipeline |
| 相册液态玻璃 / Gallery | 相册照片页工具栏的液态玻璃效果 Liquid glass on the gallery photo-page toolbar |
| 分线开关 / Per-line switches | 三条线独立开关；系统级底层钩子（材质判定 / HIGH 强制渲染）归总开关管辖，避免关闭分开关连带破坏依赖底层的玻璃效果 Per-line switches; system-level hooks live under the master switch so turning a sub-switch off never breaks the shared base |
| DIY 模式 / DIY mode | 14 个 0~100 滑条（遮罩 / 模糊 / 圆角 / 饱和度 / 边缘光 / 折射 / 边框 / 厚度 / 色散 / 锁屏参数等），实时覆盖烤入配方；默认关闭，滑条默认值=旧极致观感 14 sliders (mask / blur / radius / saturation / edge / refraction / rim / thickness / dispersion / keyguard params) overriding the baked-in look in real time; opt-in, defaults match the old extreme preset |

### 安装器净化 / Installer purify

| 功能 Feature | 说明 Description |
| --- | --- |
| 跳过安全检测 / Skip security check | 安装前不再上传 APK 做联网云检测与指纹预装提示 No more pre-install cloud scan or fingerprint prompt |
| 禁止联网 / No networking | 阻断安装器的推广与上报请求 Blocks the installer's promo/telemetry requests |
| 安装包信息卡 / Info card | 展示版本 / 大小 / SDK / 架构信息卡；可关闭（关闭时同时隐藏安全模式提醒） Version / size / SDK / ABI info card, toggleable (off also hides the safe-mode reminder) |
| 允许降级安装 / Downgrade | 允许安装低于当前版本号的 APK Allows installing lower-version APKs |
| 安装提速 / Faster staging | 跳过「准备安装」阶段的腾讯免检与分流网络等待（禁网后不再干等超时） Skips the Tencent no-detect + diversion network waits during staging |
| 自适应钩子 / Adaptive hooks | 类经 DexKit 字符串锚定位、方法经参数签名扫描定位（锚=跨版本稳定的实体完整名）；放行结果双配方结构探测——安装器 OTA 混淆重排后大概率免更新 Classes located via DexKit string anchors, methods via parameter-signature scanning (anchored on stable entity full names); pass-result delivery tries two known recipes — installer OTA obfuscation reshuffles usually need no module update |

## 环境要求 / Requirements

* 已 root 的荣耀 MagicOS 11（Android 17）设备：Magisk / KernelSU / ReSukkiSU + LSPosed（或 Vector 等第三方管理器）；模块要求 Android 13+（minSdk 33）。
  A rooted Honor MagicOS 11 (Android 17) device: Magisk / KernelSU / ReSukkiSU + LSPosed (or a third-party manager such as Vector); the module requires Android 13+ (minSdk 33).
* 作用域：**系统界面**、**荣耀桌面**（`com.hihonor.android.launcher` / `com.hihonor.desktop.systemui`）、**相册**、**安装器**（安装包内已声明推荐作用域，管理器通常可一键应用）。
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
│       │   ├── GlassHook.java             # 液态玻璃钩子（含 DIY 滑条参数）/ liquid-glass hooks
│       │   ├── InstallerHook.java         # 安装器净化钩子（自适应解析）/ adaptive installer hooks
│       │   ├── DexKitHelper.java          # DexKit 运行时 dex 搜索封装
│       │   ├── Cfg.java                   # 配置快照与门控（开关 + DIY 滑条值）
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

## 版本历史 / Changelog

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
