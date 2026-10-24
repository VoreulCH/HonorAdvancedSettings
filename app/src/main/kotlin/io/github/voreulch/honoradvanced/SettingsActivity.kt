package io.github.voreulch.honoradvanced

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import io.github.voreulch.honoradvanced.ui.component.FloatingBottomBar
import io.github.voreulch.honoradvanced.ui.component.FloatingBottomBarItem

/**
 * 药师 — module UI (miuix rebuild).
 * Functional parity with the prior hand-built SettingsActivity:
 * 9 switches, compat1/compat2 mutual exclusion, v1.0.x prefs migration,
 * root scope-restart, QR donate/QQ/privacy dialogs. Visual layer fully
 * rebuilt on the open-source miuix component library.
 */
class SettingsActivity : ComponentActivity() {

    /** QQ group join link (time-limited authKey); swap the whole URL on expiry. */
    private val qqJoinUrl =
        "https://qun.qq.com/universal-share/share?ac=1" +
        "&authKey=A%2F%2BiHb%2BVpkrFqqI7Qcg5%2BwsvbXYVA7uQD96Uf4711yPds" +
        "%2FrhUu%2FqDyi4KrQboVXM" +
        "&busi_data=eyJncm91cENvZGUiOiIyOTc2NDkyMzAiLCJ0b2tlbiI6InMw" +
        "K2pqbmRpbnA2bDl5M0ppMXVBeitUdll6enAzUkc2dVpDQnh2RDdoaXNSNGF4" +
        "VlBMWjhRa3JvNS93bFVMeVoiLCJ1aW4iOiI0NDY4MyJ9" +
        "&data=h-QBqjNfq-nswTwFBF3Mk1rluWMYzC2NCoB1da80tL95oHZq0z4yVEul" +
        "GV45rm0ntVXbG4hbek3pB84TX2seGA&svctype=4&tempid=h5_group_info"

    private val scopeNames = arrayOf("系统界面", "相册", "安装器")
    private val scopeCmds = arrayOf(
        "killall com.android.systemui",
        "am force-stop com.hihonor.photos",
        "am force-stop com.android.packageinstaller"
    )

    // switch write target shared with hooks via CfgProvider
    private fun prefs() = getSharedPreferences(Cfg.PREFS_FILE, MODE_PRIVATE)

    private fun prefsOn(key: String, def: Boolean) = prefs().getBoolean(key, def)

    // theme: locked to follow system night mode — the old System/Light/Dark
    // picker and its persisted "theme_mode" key were removed with the miuix
    // rework (migratePrefs drops any leftover value)

    /** bumped on every ON_RESUME — keyed backdrop rebuild fixes the stale
     *  grey glass pill that appears after switching back from another app */
    private val resumeTick = androidx.compose.runtime.mutableIntStateOf(0)

    override fun onResume() {
        super.onResume()
        resumeTick.intValue++
    }

    private fun applyBinding(key: String, on: Boolean, restart: Boolean) {
        try { window.decorView.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK) } catch (_: Throwable) {}
        prefs().edit().putBoolean(key, on).commit()
        notifyCfgChanged()
        val tail = if (restart) "，重启系统界面（或手机）后生效" else "，下次安装即生效"
        Toast.makeText(this, (if (on) "已开启" else "已关闭") + tail, Toast.LENGTH_SHORT).show()
    }

    private fun notifyCfgChanged() {
        try { getContentResolver().notifyChange(CfgProvider.URI, null) } catch (_: Throwable) {}
    }

    private fun suExec(cmd: String): Boolean {
        return try {
            val pb = ProcessBuilder("su", "-c", cmd)
            pb.redirectErrorStream(true)
            val p = pb.start()
            val ins = p.inputStream
            val buf = ByteArray(512)
            while (ins.read(buf) >= 0) { /* drain */ }
            p.waitFor() == 0
        } catch (_: Throwable) { false }
    }

    // ---- v1.0.x -> v1.4 key migration (same mapping as CfgProvider) ----
    private fun migratePrefs() {
        val p = prefs()
        val ed = p.edit()
        var dirty = false
        if (!p.contains(Cfg.K_GLASS) && p.contains("master")) {
            ed.putBoolean(Cfg.K_GLASS, p.getBoolean("master", true))
            ed.putBoolean(Cfg.K_SYSTEM, true)
            ed.putBoolean(Cfg.K_KEYGUARD, p.getBoolean("keyguard", true) || p.getBoolean("kgcard", true))
            ed.putBoolean(Cfg.K_GALLERY, p.getBoolean("gallery", true))
            ed.putBoolean(Cfg.K_PURIFY, true)
            ed.putBoolean(Cfg.K_INFOCARD, p.getBoolean("infocard", true))
            ed.putBoolean(Cfg.K_DOWNGRADE, p.getBoolean("downgrade", true))
            for (old in arrayOf("master","keyguard","kgcard","gallery","skipcheck","nonet","infocard","downgrade","hideextras")) ed.remove(old)
            dirty = true
        }
        for (k in Cfg.KEYS) {
            if (!p.contains(k)) {
                // every switch defaults ON EXCEPT the DIY master — auto-
                // true here would silently flip upgraders onto the extreme
                // slider values (same class of bug as the v1.4.20 compat
                // incident). DIY must stay opt-in.
                ed.putBoolean(k, k != Cfg.K_GLASS_EXTREME)
                dirty = true
            }
        }
        // clean up obsolete keys: compat switches (dropped with the adaptive
        // hook set), the removed theme picker's value, and the old UI-pref
        // keys (blur/floating-bar/glass are locked on since the miuix rework)
        for (obsolete in arrayOf("compat1", "compat2", "compat", "theme_mode",
                "ui_blur", "ui_floating_bar", "ui_glass")) {
            if (p.contains(obsolete)) { ed.remove(obsolete); dirty = true }
        }
        if (dirty) { ed.commit(); notifyCfgChanged() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        migratePrefs()
        // transparent bars, edge-to-edge; dark icons in light theme
        try {
            val ic = window.getInsetsController()
            val night = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
            if (ic != null) {
                val light = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                ic.setSystemBarsAppearance(if (night) 0 else light, light)
            }
        } catch (_: Throwable) {}
        try {
            window.setStatusBarColor(android.graphics.Color.TRANSPARENT)
            window.setNavigationBarColor(android.graphics.Color.TRANSPARENT)
        } catch (_: Throwable) {}
        setContent { HasApp() }
    }

    @Composable
    private fun HasApp() {
        // theme: locked to follow system night mode (old picker removed)
        val dark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        MiuixTheme(colors = if (dark) darkColorScheme() else lightColorScheme()) {
            var page by remember { mutableStateOf(Page.Mine) }
            val uiBlur = remember { mutableStateOf(true) }          // locked on (磨砂)
            val uiFloatingBar = remember { mutableStateOf(true) }   // locked on (悬浮底栏)
            val uiGlass = remember { mutableStateOf(true) }         // locked on (玻璃)
            // rootColor follows the theme (NOT black — the liquid-glass toggle
            // only gates the glass blur/lens effect, not the background color;
            // forcing black made the pill sample a dark backdrop = grey slab)
            val rootColor = MiuixTheme.colorScheme.surface
            val enableBlur = uiBlur.value && android.os.Build.VERSION.SDK_INT >= 33
            // intercept system back on sub-pages: go up one level instead of exiting to launcher
            BackHandler(enabled = page != Page.Mine) {
                page = Page.Mine
            }
            // rebuilding the backdrop on resume fixes the stale/grey glass
            // pill that appears after switching back from another app
            val backdrop = androidx.compose.runtime.key(resumeTick.intValue) {
                rememberLayerBackdrop {
                    drawRect(rootColor)
                    drawContent()
                }
            }
            Box(modifier = Modifier.fillMaxSize().background(rootColor)) {
                Scaffold(
                bottomBar = {
                    // floating bar only on Mine/About — never on sub-pages
                    if (uiFloatingBar.value && (page == Page.Mine || page == Page.About)) {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                            // rebuild the whole bar on resume: the internal
                            // tabsBackdrop layer also goes stale after app
                            // switches (grey pill bug) — keying the call
                            // recreates both backdrops cleanly
                            androidx.compose.runtime.key(resumeTick.intValue) {
                            FloatingBottomBar(
                                modifier = Modifier.padding(bottom = 12.dp),
                                selectedIndex = if (page == Page.Mine) 0 else 1,
                                onSelected = { i -> page = if (i == 0) Page.Mine else Page.About },
                                backdrop = backdrop,
                                tabsCount = 2,
                                isBlurEnabled = enableBlur
                            ) { activateTab ->
                                FloatingBottomBarItem(
                                    selected = page == Page.Mine,
                                    onClick = { activateTab(0) },
                                    modifier = Modifier.defaultMinSize(minWidth = 76.dp)
                                ) {
                                    Icon(Icons.Filled.Home, contentDescription = "首页", tint = LocalContentColor.current)
                                    Text("首页", fontSize = 12.sp)
                                }
                                FloatingBottomBarItem(
                                    selected = page == Page.About,
                                    onClick = { activateTab(1) },
                                    modifier = Modifier.defaultMinSize(minWidth = 76.dp)
                                ) {
                                    Icon(Icons.Filled.Info, contentDescription = "关于", tint = LocalContentColor.current)
                                    Text("关于", fontSize = 12.sp)
                                }
                            }
                            }
                        }
                    } else if (page == Page.Mine || page == Page.About) {
                        NavigationBar {
                            NavigationBarItem(
                                selected = page == Page.Mine,
                                onClick = { page = Page.Mine },
                                icon = Icons.Filled.Home,
                                label = "首页"
                            )
                            NavigationBarItem(
                                selected = page == Page.About,
                                onClick = { page = Page.About },
                                icon = Icons.Filled.Info,
                                label = "关于"
                            )
                        }
                    }
                }
                ) { padding ->
                    // extend content UNDER the floating bar (no bottom inset):
                    // the backdrop blur samples it, so the pill reads as real
                    // glass instead of a solid grey slab
                    // no top bar anywhere — all pages draw an in-content
                    // big title under the status bar
                    val topPad = androidx.compose.foundation.layout.WindowInsets.statusBars
                        .asPaddingValues().calculateTopPadding()
                    Box(Modifier.padding(top = topPad).then(if (enableBlur) Modifier.layerBackdrop(backdrop) else Modifier)) {
                        when (page) {
                            Page.Mine -> MinePage { page = it }
                            Page.Glass -> GlassPage { page = Page.Mine }
                            Page.Installer -> InstallerPage { page = Page.Mine }
                            Page.About -> AboutPage()
                        }
                        if (rootDialogVisible) ScopeRestartDialog { rootDialogVisible = false }
                    }
                }
            }
        }
    }

    private enum class Page { Mine, Glass, Installer, About }

    // ================= Mine (home) =================

    @Composable
    private fun MinePage(go: (Page) -> Unit) {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp)) {
            item { PageTitle("荣耀高级设置") }
            item { Subtitle("基于MagicOS11开发") }
            item { ActionCard("液态玻璃", "系统 / 锁屏 / 相册三线玻璃效果与总开关") { go(Page.Glass) } }
            item { ActionCard("安装器净化", "跳过安全检测、禁止联网、信息卡与降级安装") { go(Page.Installer) } }
            item {
                ActionCard("重启作用域（需ROOT）", "点击申请ROOT授权，可重启系统界面 / 相册 / 安装器") { requestRootThenPickScope() }
            }
        }
    }

    // ================= Glass =================

    @Composable
    private fun GlassPage(back: () -> Unit) {
        var diyOn by remember { mutableStateOf(prefs().getBoolean(Cfg.K_GLASS_EXTREME, false)) }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp)) {
            item { BackHeader("液态玻璃", back) }
            item { TipText("系统 / 锁屏 / 相册三线玻璃效果与总开关。开关改动后需重启系统界面（或重启手机）生效。") }
            item {
                SwitchCard(listOf(
                    SwRow("总开关", "液态玻璃总闸，含 HIGH 强制渲染、LIQUID 材质判定等系统级底层，关闭后全部回退原厂", Cfg.K_GLASS, true),
                    SwRow("系统液态玻璃", "状态栏通知与控制中心的观感（模糊半径、遮罩、饱和度等参数）", Cfg.K_SYSTEM, true),
                    SwRow("锁屏液态玻璃", "锁屏按钮与通知卡片的玻璃效果", Cfg.K_KEYGUARD, true),
                    SwRow("相册液态玻璃", "相册照片页工具栏的玻璃效果", Cfg.K_GALLERY, true)
                ))
            }
            item {
                CardShell {
                    Row(Modifier.fillMaxWidth().clickable {
                        diyOn = !diyOn
                        prefs().edit().putBoolean(Cfg.K_GLASS_EXTREME, diyOn).commit()
                    }.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("DIY 液态", fontSize = 16.sp, color = MiuixTheme.colorScheme.onSurface)
                            Text("开启后下方滑条生效；关闭回退普通版固定参数", fontSize = 12.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                        val hv = androidx.compose.ui.platform.LocalView.current
                        Switch(checked = diyOn, onCheckedChange = {
                            hv.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                            diyOn = it
                            prefs().edit().putBoolean(Cfg.K_GLASS_EXTREME, it).commit()
                        })
                    }
                }
            }
            item {
                AnimatedVisibility(
                    visible = diyOn,
                    enter = androidx.compose.animation.expandVertically(),
                    exit = androidx.compose.animation.shrinkVertically()
                ) {
                    Column {
                        SectionLabel("DIY 参数（0%~100%）")
                        CardShell {
                            DiySliderRow("通知栏 + 工具栏遮罩", Cfg.D_MASK)
                            DiySliderRow("通知栏 + 工具栏模糊", Cfg.D_TBLUR)
                            DiySliderRow("通知横幅遮罩", Cfg.D_BMASK)
                            DiySliderRow("通知横幅模糊", Cfg.D_BBLUR)
                            DiySliderRow("锁屏通知模糊", Cfg.D_KBLUR)
                            DiySliderRow("锁屏组件 + 按钮遮罩", Cfg.D_WMASK)
                            DiySliderRow("锁屏通知亮度", Cfg.D_KBBRIGHT)
                            DiySliderRow("圆角", Cfg.D_RADIUS)
                            DiySliderRow("饱和度", Cfg.D_SAT)
                            DiySliderRow("边缘光", Cfg.D_EDGE)
                            DiySliderRow("折射", Cfg.D_REFRACT)
                            DiySliderRow("边框", Cfg.D_RIM)
                            DiySliderRow("厚度", Cfg.D_THICK)
                            DiySliderRow("色散", Cfg.D_DISP)
                        }
                        Footnote("DIY 滑条改动后重启系统界面（或手机）生效。锁屏通知遮罩与通知栏遮罩共用底层；锁屏通知/壁纸模糊共用底层。")
                    }
                }
            }
            item { Footnote("开关改动后需重启系统界面（或重启手机）生效。") }
        }
    }

    // ================= Installer =================

    @Composable
    private fun DiySliderRow(label: String, key: String) {
        var pct by remember { mutableStateOf(prefs().getInt(key, Cfg.diyDefPct(key))) }
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(label, fontSize = 15.sp, color = MiuixTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                Text("$pct%", fontSize = 13.sp, color = MiuixTheme.colorScheme.primary)
            }
            BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 2.dp)) {
                val w = maxWidth
                val view = androidx.compose.ui.platform.LocalView.current
                Slider(
                    value = pct.toFloat(),
                    onValueChange = { v ->
                        val nv = v.toInt()
                        if (nv != pct && nv % 20 == 0) {
                            view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                        }
                        pct = nv
                    },
                    valueRange = 0f..100f,
                    steps = 4,
                    onValueChangeFinished = { prefs().edit().putInt(key, pct).commit() },
                    modifier = Modifier.fillMaxWidth()
                )
                // white anchor dots on the filled capsule, grey beyond —
                // dot center = fraction of full track width (snap math)
                val frac = pct / 100f
                for (i in 1..4) {
                    val f = i / 5f
                    Box(Modifier.align(Alignment.CenterStart)
                        .offset(x = 14.dp + (w - 28.dp) * f - 4.dp)
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (f <= frac + 0.001f) Color.White.copy(alpha = 0.85f)
                                    else MiuixTheme.colorScheme.onSurface.copy(alpha = 0.25f)))
                }
            }
        }
    }

    @Composable
    private fun InstallerPage(back: () -> Unit) {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp)) {
            item { BackHeader("安装器净化", back) }
            item { TipText("跳过安全检测、禁止联网、信息卡与降级安装。开关后需重启安装器生效，降级安装需重启手机生效。") }
            item {
                SwitchCard(listOf(
                    SwRow("总开关", "跳过安全检测与指纹验证、禁止联网、隐藏推广及多余卡片，关闭后全部回退原厂", Cfg.K_PURIFY, false),
                    SwRow("安装包信息卡", "展示版本 / 大小 / SDK / 架构信息卡；关闭时同时隐藏安全模式提醒", Cfg.K_INFOCARD, false),
                    SwRow("允许降级安装", "允许安装低于当前版本号的 APK", Cfg.K_DOWNGRADE, false)
                ))
            }
            item { Footnote("开关后需重启安装器生效，降级开关需重启手机生效") }
        }
    }

    // ================= About =================

    @Composable
    private fun AboutPage() {
        var donateDialog by remember { mutableStateOf(-1) } // -1 none, 0 alipay, 1 wechat, 2 qq, 3 privacy
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp)) {
            item { PageTitle("关于") }
            item { SectionLabel("捐赠（给AI喂点Token）") }
            item {
                LinkCard(listOf(
                    LinkRow("支付宝") { donateDialog = 0 },
                    LinkRow("微信") { donateDialog = 1 }
                ))
            }
            item { SectionLabel("链接") }
            item {
                LinkCard(listOf(
                    LinkRow("GitHub 仓库") {
                        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/VoreulCH/HonorAdvancedSettings"))) } catch (_: Throwable) {}
                    },
                    LinkRow("联系我们") { donateDialog = 2 },
                    LinkRow("隐私政策") { donateDialog = 3 }
                ))
            }
            item { SectionLabel("免责声明") }
            item { DisclaimerCard() }
            item { VersionFooter() }
        }
        if (donateDialog >= 0) {
            when (donateDialog) {
                0 -> QrDialog("支付宝收款", "img/donate_alipay.jpg", true, "支付宝收款", "打开支付宝扫一扫", onJump = { openAlipayScan() }) { donateDialog = -1 }
                1 -> QrDialog("微信赞赏", "img/donate_wechat.png", true, "微信赞赏", "打开微信扫一扫", onJump = { openWechatScan() }) { donateDialog = -1 }
                2 -> QrDialog("联系我们", "img/qq_group.jpg", false, "QQ群二维码", "打开加群链接（跳转浏览器）", onJump = { openQqJoinLink() }) { donateDialog = -1 }
                3 -> PrivacyDialog { donateDialog = -1 }
            }
        }
    }

    // ================= shared composable building blocks =================

    @Composable
    private fun Subtitle(text: String) {
        Text(text, fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 12.dp))
    }

    /** in-content big page title (28sp bold, left-aligned) — shared by
     *  Mine/About; sub-pages use BackHeader (with the back affordance) */
    @Composable
    private fun PageTitle(text: String) {
        Text(text, fontSize = 28.sp, fontWeight = FontWeight.Bold,
            color = MiuixTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 8.dp, bottom = 8.dp))
    }

    /** page-level tip: the how-it-works note moved here from the home menu */
    @Composable
    private fun TipText(text: String) {
        Text(text, fontSize = 12.5.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 10.dp))
    }

    @Composable
    private fun SectionLabel(text: String) {
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 8.dp))
    }

    @Composable
    private fun Footnote(text: String) {
        Text(text, fontSize = 12.sp, textAlign = TextAlign.Center,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
    }

    @Composable
    private fun BackHeader(title: String, back: () -> Unit) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("‹ 返回", fontSize = 15.sp, color = MiuixTheme.colorScheme.primary,
                modifier = Modifier.clickable { back() }.padding(horizontal = 10.dp, vertical = 8.dp))
            Spacer(Modifier.width(8.dp))
            Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.onSurface)
        }
    }

    @Composable
    private fun CardShell(content: @Composable ColumnScope.() -> Unit) {
        // miuix Card — the one card container used everywhere (all pages
        // read as XRadiant-style white rounded cards now)
        Card(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            cornerRadius = 16.dp
        ) { Column(content = content) }
    }

    @Composable
    private fun ActionCard(title: String, sub: String, onClick: () -> Unit) {
        CardShell {
            Column(Modifier.clickable { onClick() }.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f))
                    Text("›", fontSize = 22.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
                Spacer(Modifier.height(4.dp))
                Text(sub, fontSize = 12.5.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
    }

    private data class SwRow(val title: String, val sub: String, val key: String, val restart: Boolean, val def: Boolean = true)

    @Composable
    private fun SwitchCard(rows: List<SwRow>) {
        // shared state map so the compat1/compat2 mutual exclusion instantly
        // reflects on the other switch's UI (a per-row remember would go stale
        // because writing the peer's prefs never recomposes its SwitchRow).
        val state = remember(rows) {
            mutableStateMapOf<String, Boolean>().apply {
                rows.forEach { put(it.key, prefsOn(it.key, it.def)) }
            }
        }
        CardShell {
            rows.forEachIndexed { i, r ->
                SwitchRow(r, state[r.key] ?: r.def) { v ->
                    state[r.key] = v
                    applyBinding(r.key, v, r.restart)
                }
                if (i < rows.size - 1) {
                    Box(Modifier.fillMaxWidth().padding(start = 18.dp).height(1.dp)
                        .background(MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.08f)))
                }
            }
        }
    }

    @Composable
    private fun SwitchRow(r: SwRow, checked: Boolean, onChange: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(r.title, fontSize = 16.sp, color = MiuixTheme.colorScheme.onSurface)
                Spacer(Modifier.height(3.dp))
                Text(r.sub, fontSize = 12.5.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }

    private data class LinkRow(val label: String, val action: () -> Unit)

    @Composable
    private fun LinkCard(rows: List<LinkRow>) {
        CardShell {
            rows.forEachIndexed { i, r ->
                Row(Modifier.fillMaxWidth().clickable { r.action() }.padding(horizontal = 14.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(r.label, fontSize = 15.sp, color = MiuixTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                    Text("›", fontSize = 20.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
                if (i < rows.size - 1) {
                    Box(Modifier.fillMaxWidth().padding(start = 14.dp).height(1.dp)
                        .background(MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.08f)))
                }
            }
        }
    }

    @Composable
    private fun DisclaimerCard() {
        CardShell {
            Text(
                "这是一个 Xposed 模块，仅供个人学习、研究、交流使用。\n\n" +
                "　　使用本模块需要 LSPosed 或兼容管理器；模块通过修改系统应用的行为实现功能，可能存在不可预见的风险。\n\n" +
                "　　因使用本模块造成的系统不稳定、数据丢失或其他任何损失，作者不承担任何责任。\n\n" +
                "　　请遵守当地法律法规，禁止将本模块用于任何违法用途。",
                fontSize = 13.5.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(18.dp))
        }
    }

    @Composable
    private fun VersionFooter() {
        Column(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("v2.0.0 (46) · by VoreulCH@GitHub", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Spacer(Modifier.height(2.dp))
            Text("AI-assisted · MIT", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
    }

    // ================= QR / privacy dialogs =================

    @Composable
    private fun QrDialog(title: String, file: String, square: Boolean, saveName: String, jumpLabel: String, onJump: () -> Unit, onDismiss: () -> Unit) {
        val bmp = remember(file) { loadAssetBitmap(file) }
        Dialog(onDismissRequest = onDismiss) {
            Surface(shape = RoundedCornerShape(24.dp), color = MiuixTheme.colorScheme.surface) {
                Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.onSurface)
                    if (bmp != null) {
                        val avail = (resources.displayMetrics.widthPixels - 96 * resources.displayMetrics.density).toInt()
                        val w = (if (square) 260 else 230).let { kotlin.math.min(it, avail) }
                        val h = if (square) w else (w * bmp.height / bmp.width).let { hh -> kotlin.math.min(hh, resources.displayMetrics.heightPixels / 2) }
                        Image(bmp.asImageBitmap(), contentDescription = title,
                            modifier = Modifier.padding(top = 12.dp).size(w.dp, h.dp)
                                .clip(RoundedCornerShape(16.dp)).background(Color.White).padding(8.dp)
                                .clickable { saveQrToDownload(file, saveName) })
                    }
                    Text("截图或点击二维码保存", fontSize = 12.5.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
                    Text(jumpLabel, fontSize = 15.sp, color = MiuixTheme.colorScheme.primary,
                        modifier = Modifier.clickable { onJump() }.padding(vertical = 6.dp))
                    Text("关闭", fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.clickable { onDismiss() }.padding(vertical = 6.dp))
                }
            }
        }
    }

    @Composable
    private fun PrivacyDialog(onDismiss: () -> Unit) {
        Dialog(onDismissRequest = onDismiss) {
            Surface(shape = RoundedCornerShape(24.dp), color = MiuixTheme.colorScheme.surface) {
                Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("隐私政策", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.onSurface)
                    Text(
                        "本模块完全运行于本机，不申请网络权限：\n\n" +
                        "· 不联网、不上传、不收集任何数据\n" +
                        "· 开关状态仅保存在本机应用私有目录\n" +
                        "· 仅在所声明的作用域进程内运行钩子逻辑",
                        fontSize = 13.5.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))
                    Text("关闭", fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.clickable { onDismiss() }.padding(top = 4.dp))
                }
            }
        }
    }

    private fun loadAssetBitmap(file: String): Bitmap? = try {
        val ins = assets.open(file)
        val b = BitmapFactory.decodeStream(ins)
        ins.close()
        b
    } catch (_: Throwable) { null }

    // ---- scope-restart dialog ----

    @Composable
    private fun ScopeRestartDialog(onDismiss: () -> Unit) {
        Dialog(onDismissRequest = onDismiss) {
            Surface(shape = RoundedCornerShape(24.dp), color = MiuixTheme.colorScheme.surface) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 16.dp)) {
                    Text("重启作用域", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.onSurface, modifier = Modifier.padding(vertical = 4.dp))
                    Text("选择要重启的应用", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(bottom = 10.dp))
                    scopeNames.forEachIndexed { i, name ->
                        Text(name, fontSize = 16.sp, color = MiuixTheme.colorScheme.onSurface,
                            modifier = Modifier.fillMaxWidth().clickable { onDismiss(); restartScope(i) }.padding(vertical = 13.dp))
                        if (i < scopeNames.size - 1) {
                            Box(Modifier.fillMaxWidth().height(1.dp)
                                .background(MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.08f)))
                        }
                    }
                    Text("取消", fontSize = 15.sp, color = MiuixTheme.colorScheme.primary, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().clickable { onDismiss() }.padding(top = 12.dp))
                }
            }
        }
    }

    private fun requestRootThenPickScope() {
        Toast.makeText(this, "正在检查ROOT权限…", Toast.LENGTH_SHORT).show()
        Thread {
            val root = suExec("id")
            runOnUiThread {
                if (!root) Toast.makeText(this, "没有root权限", Toast.LENGTH_SHORT).show()
                else rootDialogVisible = true
            }
        }.start()
    }

    // shown when the root check above succeeds; the Composable observes it
    private var rootDialogVisible by mutableStateOf(false)

    // ---- QR save to Downloads + external app launches ----

    private fun saveQrToDownload(file: String, name: String) {
        if (android.os.Build.VERSION.SDK_INT < 29 &&
            checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingQrFile = file; pendingQrName = name
            requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 71)
            return
        }
        val cr = contentResolver
        Thread {
            var ok = false
            try {
                val ins = assets.open(file)
                val bmp = BitmapFactory.decodeStream(ins); ins.close()
                if (bmp != null) {
                    if (android.os.Build.VERSION.SDK_INT >= 29) {
                        val cv = android.content.ContentValues()
                        cv.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, "$name.png")
                        cv.put(android.provider.MediaStore.Downloads.MIME_TYPE, "image/png")
                        val uri = cr.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                        if (uri != null) {
                        val os = cr.openOutputStream(uri)
                        if (os != null) { bmp.compress(Bitmap.CompressFormat.PNG, 100, os); os.close(); ok = true }
                        }
                    } else {
                        val dir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                        if (!dir.exists()) dir.mkdirs()
                        val fos = java.io.FileOutputStream(java.io.File(dir, "$name.png"))
                        bmp.compress(Bitmap.CompressFormat.PNG, 100, fos); fos.close(); ok = true
                    }
                }
            } catch (_: Throwable) {}
            val saved = ok
            runOnUiThread { Toast.makeText(this, if (saved) "已保存到 Download 目录" else "保存失败，请重试", Toast.LENGTH_SHORT).show() }
        }.start()
    }

    private var pendingQrFile: String? = null
    private var pendingQrName: String? = null

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        if (requestCode != 71) return
        val f = pendingQrFile; val n = pendingQrName; pendingQrFile = null; pendingQrName = null
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED && f != null && n != null) saveQrToDownload(f, n)
        else Toast.makeText(this, "没有存储权限，无法保存", Toast.LENGTH_SHORT).show()
    }

    private fun openAlipayScan() {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("alipays://platformapi/startapp?saId=10000007"))) }
        catch (_: Throwable) { Toast.makeText(this, "未检测到支付宝，请手动打开扫一扫", Toast.LENGTH_SHORT).show() }
    }

    private fun openWechatScan() {
        var ok = false
        try {
            val launch = packageManager.getLaunchIntentForPackage("com.tencent.mm")
            if (launch != null) { startActivity(launch); ok = true }
        } catch (_: Throwable) {}
        Toast.makeText(this, if (ok) "微信已打开，请点右上角 + → 扫一扫" else "未检测到微信，请手动打开扫一扫", Toast.LENGTH_LONG).show()
    }

    private fun openQqJoinLink() {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(qqJoinUrl))) }
        catch (_: Throwable) { Toast.makeText(this, "未检测到浏览器，请手动搜索群 297649230", Toast.LENGTH_SHORT).show() }
    }

    private fun restartScope(which: Int) {
        val name = scopeNames[which]
        Toast.makeText(this, "正在重启$name…", Toast.LENGTH_SHORT).show()
        Thread {
            val ok = suExec(scopeCmds[which])
            runOnUiThread { Toast.makeText(this, if (ok) "已重启$name" else "重启失败：ROOT授权被拒绝", Toast.LENGTH_SHORT).show() }
        }.start()
    }
}
