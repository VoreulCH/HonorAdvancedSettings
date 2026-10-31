package io.github.voreulch.honoradvanced

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
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
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import io.github.voreulch.honoradvanced.ui.component.FloatingBottomBar
import io.github.voreulch.honoradvanced.ui.component.FloatingBottomBarItem

/**
 * 药师 — module UI (miuix rebuild).
 * 9 switches (glass 4-line + purify 3 + misc 1 + DIY master), v1.0.x prefs
 * migration, root scope-restart, QR donate/QQ/privacy dialogs. Visual layer
 * fully rebuilt on the open-source miuix component library.
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

    /** 可重启的作用域。命令只认这里写死的包名，不拼接任何外部输入。 */
    private data class ScopeTarget(val pkg: String, val label: String, val cmd: String)

    /**
     * 常驻/system 进程必须用 killall —— `am force-stop` 对它们**静默无效**
     * （v2.3.0 实测：对 com.hihonor.iaware 执行后 PID 一字不变，界面却提示"已重启"，
     * 用户于是反复看到"温控未生效"）。普通 app 仍用 force-stop，能连后台状态一起清。
     *
     * <p>每条都跟 {@code || true}：目标本来就没在跑时 killall 会返回非 0，一条失败就把
     * 整批命令的退出码带成非 0，界面会谎报"重启失败"。真失败（su 被拒）仍由 su 自身的
     * 退出码反映，兜底不影响。
     */
    private val scopeTargets = listOf(
        ScopeTarget("com.android.systemui", "系统界面", "killall com.android.systemui || true"),
        ScopeTarget("com.hihonor.photos", "相册", "am force-stop com.hihonor.photos || true"),
        ScopeTarget("com.android.packageinstaller", "安装器",
            "am force-stop com.android.packageinstaller || true"),
        ScopeTarget("com.hihonor.android.launcher", "桌面",
            "killall com.hihonor.android.launcher || true"),
        ScopeTarget("com.hihonor.desktop.systemui", "桌面模式",
            "killall com.hihonor.desktop.systemui || true"),
        ScopeTarget("com.hihonor.iaware", "温控服务", "killall com.hihonor.iaware || true")
    )

    // 各开关依赖的作用域（用于"作用域状态"提示）。system_server（android）读不到
    // 模块 provider、也没法自证，所以不列进来 —— 它在开机时随进程加载。
    private val S_SYSTEMUI = listOf("com.android.systemui")
    private val S_PHOTOS = listOf("com.hihonor.photos")
    private val S_INSTALLER = listOf("com.android.packageinstaller")
    private val S_THERMAL = listOf("com.android.systemui", "com.hihonor.iaware")

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
        // 每次回到前台重读一次作用域自证（重启作用域后切回来就能看到新状态）。
        // 再补一次 2.5s 后的强制读：用户很可能刚在 LSPosed 里重启完进程，
        // 那时 Application 还没上报，第一次读拿到的还是旧记录。
        try { FrameworkProbe.refresh(this) } catch (_: Throwable) {}
        resumeProbeShot?.let { mainHandler.removeCallbacks(it) }
        val shot = Runnable {
            try { FrameworkProbe.refresh(this, true) } catch (_: Throwable) {}
        }
        resumeProbeShot = shot
        mainHandler.postDelayed(shot, 2500L)
    }

    private var resumeProbeShot: Runnable? = null

    private fun applyBinding(key: String, on: Boolean, restart: Boolean, onResult: ((Boolean) -> Unit)? = null) {
        try { window.decorView.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK) } catch (_: Throwable) {}
        prefs().edit().putBoolean(key, on).commit()
        notifyCfgChanged()
        // the thermal bypass also lives in system_server, which can never read
        // the module prefs (SELinux) — mirror this one switch into a system
        // property so BOTH layers honour it (hooks read it live per call).
        // The write needs root, so report the real outcome instead of a blind
        // "enabled" toast; if turning it OFF fails we also revert the pref so
        // the UI never claims "off" while the system layer is still bypassing.
        if (key == Cfg.K_HOTTEMP) {
            Toast.makeText(this, "正在写入系统属性（ROOT）…", Toast.LENGTH_SHORT).show()
            syncHottempProp(on) { ok ->
                if (!ok && !on) {
                    // cannot disable the system layer without root: keep the
                    // switch ON so the UI matches what the device is doing
                    prefs().edit().putBoolean(key, true).commit()
                    notifyCfgChanged()
                    Toast.makeText(this, "未获得 ROOT：无法解除系统层屏蔽，开关已保持开启", Toast.LENGTH_LONG).show()
                    onResult?.invoke(false)
                    return@syncHottempProp
                }
                Toast.makeText(
                    this,
                    if (ok) (if (on) "已开启，即时生效" else "已关闭，已恢复原厂热限制")
                    else "未获得 ROOT：仅弹窗屏蔽生效，系统热限制仍在",
                    Toast.LENGTH_LONG
                ).show()
                onResult?.invoke(ok)
            }
            return
        }
        val tail = if (restart) "，重启系统界面（或手机）后生效" else "，下次安装即生效"
        Toast.makeText(this, (if (on) "已开启" else "已关闭") + tail, Toast.LENGTH_SHORT).show()
        onResult?.invoke(true)
    }

    /** one prop sync at a time: rapid toggling must not pile up su threads. */
    @Volatile private var propSyncBusy = false

    /** write persist.sys.has.hottemp (root); verify the read-back and report.
     *  Runs off the main thread with a hard timeout so a pending su prompt can
     *  never pin a thread, and never claims success it did not observe. */
    private fun syncHottempProp(on: Boolean, done: ((Boolean) -> Unit)? = null) {
        if (propSyncBusy) {
            // a sync is already in flight; report the current truth instead of
            // spawning a second su process
            if (done != null) runOnUiThread { done(readProp(Cfg.PROP_HOTTEMP) == (if (on) "1" else "0")) }
            return
        }
        propSyncBusy = true
        Thread {
            var ok = false
            try {
                val want = if (on) "1" else "0"
                if (readProp(Cfg.PROP_HOTTEMP) != want) {
                    ok = suExecTimeout("setprop ${Cfg.PROP_HOTTEMP} $want", 8000L)
                } else {
                    ok = true
                }
                // trust the read-back, not the exit code alone
                if (ok) {
                    val now = readProp(Cfg.PROP_HOTTEMP)
                    ok = (now == want) || now.isNullOrEmpty()
                }
            } catch (_: Throwable) { ok = false }
            propSyncBusy = false
            if (done != null) runOnUiThread { done(ok) }
        }.start()
    }

    /** su -c with a hard timeout (never block on an unanswered prompt). */
    private fun suExecTimeout(cmd: String, timeoutMs: Long): Boolean {
        return try {
            val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
            val drain = Thread {
                try {
                    val ins = p.inputStream
                    val buf = ByteArray(256)
                    while (ins.read(buf) >= 0) { /* drain */ }
                } catch (_: Throwable) {}
            }
            drain.isDaemon = true
            drain.start()
            val finished = p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!finished) {
                try { p.destroy() } catch (_: Throwable) {}
                false
            } else {
                p.exitValue() == 0
            }
        } catch (_: Throwable) { false }
    }

    private fun readProp(name: String): String? = try {
        val sp = Class.forName("android.os.SystemProperties")
        sp.getMethod("get", String::class.java, String::class.java)
            .invoke(null, name, "") as? String
    } catch (_: Throwable) { null }

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
                // every switch defaults ON EXCEPT the opt-in ones — auto-true
                // here would silently flip upgraders onto the extreme sliders
                // (v1.4.20 compat incident) or enable the system_server-side
                // notification-ducking hook unasked. Both must stay opt-in.
                ed.putBoolean(k, k != Cfg.K_GLASS_EXTREME && k != Cfg.K_NODUCK &&
                        k != Cfg.K_HOTTEMP && k != Cfg.K_DIAG)
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
        // 主线程先触碰一次：Compose 的 mutableStateOf 建在工作线程的过期 snapshot
        // 上会在组合阶段抛 IllegalStateException（HonorHook 踩过同样的坑）
        FrameworkProbe.warmUp()
        migratePrefs()
        // keep the system-property bridge in sync with the stored switch
        // (first run after install, or after the pref was changed elsewhere)
        try { syncHottempProp(prefs().getBoolean(Cfg.K_HOTTEMP, false)) } catch (_: Throwable) {}
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
                            Page.Misc -> MiscPage { page = Page.Mine }
                            Page.About -> AboutPage()
                        }
                        if (rootDialogVisible) ScopeRestartDialog { rootDialogVisible = false }
                    }
                }
            }
        }
    }

    private enum class Page { Mine, Glass, Installer, Misc, About }

    // ================= Mine (home) =================

    @Composable
    private fun MinePage(go: (Page) -> Unit) {
        val probeCtx = LocalContext.current
        LaunchedEffect(Unit) { FrameworkProbe.refresh(probeCtx, true) }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp)) {
            item { PageTitle("荣耀高级设置") }
            item { Subtitle("基于MagicOS11开发") }
            item { FrameworkCard() }
            item { ActionCard("液态玻璃", "系统 / 锁屏 / 相册三线玻璃效果与总开关") { go(Page.Glass) } }
            item { ActionCard("安装器净化", "跳过安全检测、禁止联网、信息卡与降级安装") { go(Page.Installer) } }
            item { ActionCard("系统综合", "通知音量、高温限制与系统级行为的总开关集合") { go(Page.Misc) } }
            item {
                ActionCard("重启作用域（需ROOT）", "可多选一次重启：系统界面 / 相册 / 安装器 / 桌面 / 温控服务") { requestRootThenPickScope() }
            }
        }
    }

    /** 首页顶部：模块在各作用域里的实际运行状态（无 root，靠进程自证上报）。 */
    @Composable
    private fun FrameworkCard() {
        val st = FrameworkProbe.state
        val ok = st.running
        val title = when {
            !st.probed -> "正在检测模块状态…"
            ok -> "模块已生效"
            else -> "模块未生效"
        }
        val sub = when {
            !st.probed -> "读取作用域运行记录"
            ok -> {
                // powergenie 与 iaware 是同一个进程的两个包名，列表里只留一个
                val names = st.reports.keys
                    .filter { it != "com.hihonor.powergenie" }
                    .joinToString(" / ") { scopeLabel(it) }
                val at = st.reports.values.maxOfOrNull { it.at } ?: 0L
                "已加载 $names · ${FrameworkProbe.ago(at)}上报"
            }
            else -> "请在 LSPosed 中启用本模块、勾选作用域，然后重启作用域或手机"
        }
        val accent = when {
            !st.probed -> MiuixTheme.colorScheme.onSurfaceVariantSummary
            ok -> Color(0xFF2FBF6B)
            else -> Color(0xFFE5484D)
        }
        Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), cornerRadius = 16.dp) {
            Row(Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(accent))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                        color = MiuixTheme.colorScheme.onSurface)
                    Text(sub, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 3.dp))
                }
                Text(when { !st.probed -> "…"; ok -> "正常"; else -> "异常" },
                    fontSize = 13.sp, color = accent)
            }
        }
    }

    private fun scopeLabel(pkg: String): String = when (pkg) {
        "com.android.systemui" -> "系统界面"
        "com.hihonor.photos" -> "相册"
        "com.android.packageinstaller" -> "安装器"
        "com.hihonor.android.launcher" -> "桌面"
        "com.hihonor.desktop.systemui" -> "桌面模式"
        "com.hihonor.powergenie" -> "PowerGenie"
        "com.hihonor.iaware" -> "温控服务"
        "android" -> "系统框架"
        else -> pkg
    }

    // ================= Glass =================

    @Composable
    private fun GlassPage(back: () -> Unit) {
        val probeCtx = LocalContext.current
        LaunchedEffect(Unit) { FrameworkProbe.refresh(probeCtx, true) }
        var diyOn by remember { mutableStateOf(prefs().getBoolean(Cfg.K_GLASS_EXTREME, false)) }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp)) {
            item { BackHeader("液态玻璃", back) }
            item { TipText("系统 / 锁屏 / 相册三线玻璃效果与总开关。开关改动后需重启系统界面（或重启手机）生效。") }
            item {
                SwitchCard(listOf(
                    SwRow("总开关", "液态玻璃总闸，含 HIGH 强制渲染、LIQUID 材质判定等系统级底层，关闭后全部回退原厂", Cfg.K_GLASS, true, scopes = S_SYSTEMUI),
                    SwRow("系统液态玻璃", "状态栏通知与控制中心的观感（模糊半径、遮罩、饱和度等参数）", Cfg.K_SYSTEM, true, scopes = S_SYSTEMUI),
                    SwRow("锁屏液态玻璃", "锁屏按钮与通知卡片的玻璃效果", Cfg.K_KEYGUARD, true, scopes = S_SYSTEMUI),
                    SwRow("相册液态玻璃", "相册照片页工具栏的玻璃效果", Cfg.K_GALLERY, true, scopes = S_PHOTOS)
                ))
            }
            item {
                CardShell {
                    Row(Modifier.fillMaxWidth().clickable {
                        diyOn = !diyOn
                        prefs().edit().putBoolean(Cfg.K_GLASS_EXTREME, diyOn).commit()
                    }.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("DIY 液态玻璃", fontSize = 16.sp, color = MiuixTheme.colorScheme.onSurface)
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
        val probeCtx = LocalContext.current
        LaunchedEffect(Unit) { FrameworkProbe.refresh(probeCtx, true) }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp)) {
            item { BackHeader("安装器净化", back) }
            item { TipText("跳过安全检测、禁止联网、信息卡与降级安装。开关后需重启安装器生效，降级安装需重启手机生效。") }
            item {
                SwitchCard(listOf(
                    SwRow("总开关", "跳过安全检测与指纹验证、禁止联网、隐藏推广及多余卡片，关闭后全部回退原厂", Cfg.K_PURIFY, false, scopes = S_INSTALLER),
                    SwRow("安装包信息卡", "展示版本 / 大小 / SDK / 架构信息卡；关闭时同时隐藏安全模式提醒", Cfg.K_INFOCARD, false, scopes = S_INSTALLER),
                    SwRow("允许降级安装", "允许安装低于当前版本号的 APK", Cfg.K_DOWNGRADE, false, scopes = S_INSTALLER)
                ))
            }
            item { Footnote("开关后需重启安装器生效，降级开关需重启手机生效") }
        }
    }

    // ================= Misc =================

    @Composable
    private fun MiscPage(back: () -> Unit) {
        val probeCtx = LocalContext.current
        LaunchedEffect(Unit) { FrameworkProbe.refresh(probeCtx, true) }
        // bumped on every toggle so the system-layer status line re-reads the
        // property (it lives inside the switch cell, not as a separate row)
        var statusTick by remember { mutableIntStateOf(0) }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp)) {
            item { BackHeader("系统综合", back) }
            item { TipText("通知、温控与系统行为层面的综合开关：既能改善日常听感与信息呈现，也能解除系统在高温等场景下的自我限制；各项独立生效、互不影响。") }
            item {
                val prop = remember(statusTick) { readProp(Cfg.PROP_HOTTEMP) }
                val status = "系统层状态：" + (if (prop == "1") "已生效" else "已失效") +
                        "　属性=" + (if (prop.isNullOrEmpty()) "未写入" else prop)
                SwitchCard(
                    rows = listOf(
                        SwRow("屏蔽通知回避", "播放音乐时来消息通知不再压低音乐音量，通知音与音乐同时播放；闹钟不受影响", Cfg.K_NODUCK, true, false, scopes = S_SYSTEMUI),
                        SwRow("屏蔽高温限制（ROOT）",
                            "系统高温时不弹提示、不限亮度、热状态按正常上报，压制 PowerGenie 温控（游戏限帧/后台清理），高温自动关机也不再触发；内核调度与降频仍由硬件层控制，本开关不干预",
                            Cfg.K_HOTTEMP, true, false, confirmOnEnable = true,
                            statusText = status, statusOk = prop == "1", scopes = S_THERMAL)
                    ),
                    onAfterChange = { statusTick++ }
                )
            }
            item { Footnote("「屏蔽高温限制（ROOT）」默认关闭，开启前会二次确认；卸载模块前请先关闭它，否则系统会持续按正常温度上报。") }
        }
    }

    // ================= About =================

    /** 特别鸣谢名单。以后加人只需在这里加一行；[avatar] 指向 assets 里的图片，
     *  找不到就显示白色圆形占位（用户后续会补图）。 */
    private data class Credit(val name: String, val role: String, val avatar: String?)

    private val credits = listOf(
        Credit("VoreulCH · 阿影", "药师(honoradvanced) · 开发者", "img/credits/voreulch.png")
    )

    @Composable
    private fun AboutPage() {
        // -1 none, 0 alipay, 1 wechat, 2 qq, 3 privacy, 4 credits
        var donateDialog by remember { mutableStateOf(-1) }
        var checking by remember { mutableStateOf(false) }
        var diagOn by remember { mutableStateOf(prefs().getBoolean(Cfg.K_DIAG, false)) }
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
                    LinkRow(if (checking) "正在检查更新…" else "检查更新") {
                        if (!checking) {
                            checking = true
                            checkUpdate { checking = false }
                        }
                    },
                    LinkRow("特别鸣谢") { donateDialog = 4 },
                    LinkRow("隐私政策") { donateDialog = 3 }
                ))
            }
            item {
                CardShell {
                    Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("诊断日志", fontSize = 16.sp, color = MiuixTheme.colorScheme.onSurface)
                            Spacer(Modifier.height(3.dp))
                            Text("向 LSPosed 模块日志输出模块所有功能运行状态，用于排查问题；日常建议关闭，反馈 bug 时使用。开启即时生效。",
                                fontSize = 12.5.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            Text("（系统框架进程读不到模块配置，那部分日志随开机输出）",
                                fontSize = 11.5.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(top = 3.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(checked = diagOn, onCheckedChange = { v ->
                            try { window.decorView.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK) } catch (_: Throwable) {}
                            prefs().edit().putBoolean(Cfg.K_DIAG, v).commit()
                            notifyCfgChanged()
                            diagOn = v
                            Toast.makeText(this@SettingsActivity,
                                if (v) "诊断日志已开启，复现问题后到 LSPosed 日志里搜 HAS-Diag"
                                else "诊断日志已关闭",
                                Toast.LENGTH_LONG).show()
                        })
                    }
                }
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
                4 -> CreditsDialog { donateDialog = -1 }
            }
        }
    }

    /** 检查更新：只连 GitHub 公开接口；超时/失败都给出具体原因。 */
    private fun checkUpdate(onDone: () -> Unit) {
        Toast.makeText(this, "正在检查更新…", Toast.LENGTH_SHORT).show()
        val localCode = try {
            val pi = packageManager.getPackageInfo(packageName, 0)
            if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt()
            else { @Suppress("DEPRECATION") pi.versionCode }
        } catch (_: Throwable) { BuildConfig.VERSION_CODE }
        UpdateChecker.check(localCode) { r ->
            onDone()
            when (r) {
                is UpdateResult.Newer -> {
                    val url = if (r.info.apkUrl.isNotEmpty()) r.info.apkUrl else r.info.pageUrl
                    Toast.makeText(this, "发现新版本 v${r.info.versionName}，正在打开下载页", Toast.LENGTH_LONG).show()
                    try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    catch (_: Throwable) { Toast.makeText(this, "没有可用的浏览器", Toast.LENGTH_SHORT).show() }
                }
                is UpdateResult.Latest ->
                    Toast.makeText(this, "已是最新版本 v${BuildConfig.VERSION_NAME}", Toast.LENGTH_SHORT).show()
                is UpdateResult.Failed ->
                    Toast.makeText(this, "检查更新失败：${r.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** 特别鸣谢：左头像、右两行（名字 / 身份）。名单可以一直往下加。 */
    @Composable
    private fun CreditsDialog(onDismiss: () -> Unit) {
        Dialog(onDismissRequest = onDismiss) {
            Surface(shape = RoundedCornerShape(24.dp), color = MiuixTheme.colorScheme.surface) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
                    Text("特别鸣谢", fontSize = 18.sp, fontWeight = FontWeight.Bold,
                        color = MiuixTheme.colorScheme.onSurface)
                    Text("感谢每一位参与测试、反馈与贡献的人", fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 3.dp, bottom = 8.dp))
                    Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                        credits.forEachIndexed { i, c ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Avatar(c.avatar)
                                Spacer(Modifier.width(14.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(c.name, fontSize = 15.5.sp,
                                        color = MiuixTheme.colorScheme.onSurface)
                                    Text(c.role, fontSize = 12.5.sp,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        modifier = Modifier.padding(top = 2.dp))
                                }
                            }
                            if (i < credits.size - 1) {
                                Box(Modifier.fillMaxWidth().height(1.dp)
                                    .background(MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.08f)))
                            }
                        }
                    }
                    Text("关闭", fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().clickable { onDismiss() }.padding(top = 12.dp))
                }
            }
        }
    }

    /** 圆形头像；assets 里没有对应图片时就是一块白色圆（占位）。 */
    @Composable
    private fun Avatar(path: String?) {
        val bmp = remember(path) { if (path == null) null else loadAssetBitmap(path, 256) }
        Box(Modifier.size(48.dp).clip(CircleShape).background(Color.White),
            contentAlignment = Alignment.Center) {
            if (bmp != null) {
                Image(bmp.asImageBitmap(), contentDescription = null,
                    modifier = Modifier.fillMaxSize().clip(CircleShape))
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

    private data class SwRow(
        val title: String,
        val sub: String,
        val key: String,
        val restart: Boolean,
        val def: Boolean = true,
        /** ask for an explicit second tap before enabling (safety-relevant rows) */
        val confirmOnEnable: Boolean = false,
        /** optional second line under the title (live system-level state) */
        val statusText: String? = null,
        /** statusText 是否代表"正常"（决定文字颜色；"未生效"三个字里也含"已生效"，不能靠 contains 判断） */
        val statusOk: Boolean = true,
        /** 这个开关依赖哪些作用域 —— 未生效时在标题下面直接点出来 */
        val scopes: List<String> = emptyList()
    )

    @Composable
    private fun SwitchCard(rows: List<SwRow>, onAfterChange: (() -> Unit)? = null) {
        val ctx = LocalContext.current
        // shared state map so toggling one switch instantly reflects on the
        // other rows' UI (a per-row remember would go stale because writing
        // the peer's prefs never recomposes its SwitchRow).
        val state = remember(rows) {
            mutableStateMapOf<String, Boolean>().apply {
                rows.forEach { put(it.key, prefsOn(it.key, it.def)) }
            }
        }
        var confirmRow by remember { mutableStateOf<SwRow?>(null) }
        CardShell {
            rows.forEachIndexed { i, r ->
                SwitchRow(r, state[r.key] ?: r.def) { v ->
                    if (v && r.confirmOnEnable) {
                        confirmRow = r                      // ask first, apply on confirm
                    } else {
                        state[r.key] = v
                        applyBinding(r.key, v, r.restart) { ok ->
                            // the system layer could not be changed (no root):
                            // snap the UI back so it never lies about the state
                            if (!ok && !v) state[r.key] = true
                            onAfterChange?.invoke()
                        }
                        if (v) warnMissingScopes(ctx, r.scopes)
                    }
                }
                if (i < rows.size - 1) {
                    Box(Modifier.fillMaxWidth().padding(start = 18.dp).height(1.dp)
                        .background(MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.08f)))
                }
            }
        }
        confirmRow?.let { r ->
            HotTempConfirmDialog(
                onConfirm = {
                    state[r.key] = true
                    applyBinding(r.key, true, r.restart) { onAfterChange?.invoke() }
                    warnMissingScopes(ctx, r.scopes)
                    confirmRow = null
                },
                onDismiss = { confirmRow = null }
            )
        }
    }

    /** Safety confirmation before enabling the ROOT-level thermal bypass. */
    @Composable
    private fun HotTempConfirmDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
        Dialog(onDismissRequest = onDismiss) {
            Surface(shape = RoundedCornerShape(24.dp), color = MiuixTheme.colorScheme.surface) {
                Column(Modifier.padding(18.dp)) {
                    Text("屏蔽高温限制（ROOT）", fontSize = 18.sp, fontWeight = FontWeight.Bold,
                        color = MiuixTheme.colorScheme.onSurface)
                    Text(
                        "开启后：\n" +
                        "· 高温时不再弹「建议暂停使用」提示\n" +
                        "· 不再限制屏幕亮度，系统热状态按正常上报\n" +
                        "· 压制 PowerGenie 温控（游戏限帧、后台清理等）\n" +
                        "· 高温自动关机不再触发\n" +
                        "· 需要 ROOT 写入系统属性；未授权时仅弹窗屏蔽生效\n\n" +
                        "请注意：\n" +
                        "· 内核级降频仍然保留（硬件层面保护芯片），但过热自动关机不会触发，请自行留意手感与温度\n" +
                        "· 系统不再有任何高温预警，长时间高负载（游戏/录屏/快充）请适时休息\n" +
                        "· 卸载模块前请先关闭此开关，否则系统会持续按正常温度上报",
                        fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Text("取消", fontSize = 15.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.clickable { onDismiss() }.padding(8.dp))
                        Spacer(Modifier.width(12.dp))
                        Text("确认开启", fontSize = 15.sp, color = MiuixTheme.colorScheme.primary,
                            modifier = Modifier.clickable { onConfirm() }.padding(8.dp))
                    }
                }
            }
        }
    }

    private val WarnColor = Color(0xFFE5484D)

    /**
     * 作用域状态行。只在**读到了运行记录**之后才下结论：
     * 读不到（probed=false）时不显示，避免把"还没检测"画成"没运行"。
     */
    private fun scopeStatusOf(required: List<String>): Pair<String, Boolean>? {
        if (required.isEmpty()) return null
        val st = FrameworkProbe.state
        if (!st.probed) return null
        if (!st.running) {
            return "作用域状态：未检测到模块运行（在 LSPosed 启用模块并勾选作用域后重启生效）" to false
        }
        val miss = st.missing(required)
        if (miss.isNotEmpty()) {
            val names = miss.joinToString("、") { scopeLabel(it) + "(" + it + ")" }
            return "作用域状态：${names} 作用域未生效（勾选并重启作用域生效）" to false
        }
        // 全部生效 → 默认隐藏，不打扰；只有"跑着旧版本、需要手动重启"才提一句
        if (required.any { st.stale(it) }) {
            return "作用域状态：进程跑的是旧版本模块（重启作用域后更新）" to false
        }
        return null
    }

    /** 上一次挂起的作用域警告（连点开关时只保留最后一条，避免 Toast 排队）。 */
    private var scopeWarnShot: Runnable? = null

    /**
     * 打开开关时如果依赖的作用域没生效，提醒一句 —— 避免用户以为已经用上了。
     *
     * 延迟 2.2s：applyBinding 自己会先弹一条"已开启…"，两条立刻叠加只会让
     * 后一条盖掉前一条。用 applicationContext 是因为这条提示可能在页面销毁后
     * 才到点。
     */
    private fun warnMissingScopes(ctx: android.content.Context, scopes: List<String>) {
        if (scopes.isEmpty()) return
        val st = FrameworkProbe.state
        if (!st.probed) {
            FrameworkProbe.refresh(ctx, true)
            return
        }
        val msg: String = if (!st.running) {
            "模块还没在任何作用域生效：请在 LSPosed 勾选后重启作用域"
        } else {
            val miss = st.missing(scopes)
            if (miss.isEmpty()) return
            miss.joinToString("、") { scopeLabel(it) } + " 作用域未生效：请在 LSPosed 勾选后重启作用域"
        }
        val appCtx = ctx.applicationContext ?: ctx
        scopeWarnShot?.let { mainHandler.removeCallbacks(it) }
        val shot = Runnable { Toast.makeText(appCtx, msg, Toast.LENGTH_LONG).show() }
        scopeWarnShot = shot
        mainHandler.postDelayed(shot, 2200L)
    }

    @Composable
    private fun SwitchRow(r: SwRow, checked: Boolean, onChange: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(r.title, fontSize = 16.sp, color = MiuixTheme.colorScheme.onSurface)
                scopeStatusOf(r.scopes)?.let { (text, ok) ->
                    Text(text, fontSize = 12.sp,
                        color = if (ok) MiuixTheme.colorScheme.primary else WarnColor,
                        modifier = Modifier.padding(top = 2.dp))
                }
                r.statusText?.let { s ->
                    Text(s, fontSize = 12.sp,
                        color = if (r.statusOk) MiuixTheme.colorScheme.primary
                                else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 2.dp))
                }
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
        // read from PackageManager so this footer can never drift from
        // build.gradle.kts again (it sat at v2.0.0 (46) through 2.0.2)
        val ver = remember {
            try {
                val pi = packageManager.getPackageInfo(packageName, 0)
                val code = if (android.os.Build.VERSION.SDK_INT >= 28) {
                    pi.longVersionCode.toString()
                } else {
                    @Suppress("DEPRECATION") pi.versionCode.toString()
                }
                "v${pi.versionName ?: "?"} ($code)"
            } catch (_: Throwable) { "" }
        }
        Column(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (ver.isNotEmpty()) {
                Text("$ver · by VoreulCH@GitHub", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            } else {
                Text("by VoreulCH@GitHub", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
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
                        "本模块完全运行于本机，不会上传任何数据：\n\n" +
                        "· 只有您点击「检查更新」时才会访问 GitHub 公开接口查询版本号，其余时间不联网\n" +
                        "· 开关状态仅保存在本机应用私有目录\n" +
                        "· 仅在所声明的作用域进程内运行钩子逻辑\n" +
                        "· 诊断日志只写入 LSPosed 的本地模块日志，不会外发",
                        fontSize = 13.5.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))
                    Text("关闭", fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.clickable { onDismiss() }.padding(top = 4.dp))
                }
            }
        }
    }

    /**
     * 按显示尺寸采样解码。
     *
     * 头像位置只有 48dp、二维码约 220dp，而用户后续会往 assets 里塞真实头像
     * （可能是 2000×2000 的原图）。原样 decodeStream 等于把整张图展开成
     * 16MB 的 Bitmap，两张就够低端机 OOM —— 这里先读尺寸再按 2 的幂采样。
     */
    private fun loadAssetBitmap(file: String, targetPx: Int = 1024): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        assets.open(file).use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        while (longest / sample > targetPx && sample < 16) sample *= 2
        assets.open(file).use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    } catch (_: Throwable) { null }

    // ---- scope-restart dialog ----

    /** 单选方块（不引第三方 Checkbox：miuix 0.9.4 的开关组件签名不稳，自绘更省心）。 */
    @Composable
    private fun CheckMark(on: Boolean) {
        val primary = MiuixTheme.colorScheme.primary
        Box(
            Modifier.size(20.dp).clip(RoundedCornerShape(6.dp))
                .background(if (on) primary else Color.Transparent)
                .border(1.5.dp,
                    if (on) primary else MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.45f),
                    RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (on) Text("✓", fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Bold)
        }
    }

    /** 重启作用域：可多选，一次 su 会话全部执行（v2.3.0）。 */
    @Composable
    private fun ScopeRestartDialog(onDismiss: () -> Unit) {
        val picked = remember {
            mutableStateMapOf<String, Boolean>().apply { scopeTargets.forEach { put(it.pkg, true) } }
        }
        val count = scopeTargets.count { picked[it.pkg] == true }
        Dialog(onDismissRequest = onDismiss) {
            Surface(shape = RoundedCornerShape(24.dp), color = MiuixTheme.colorScheme.surface) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 16.dp)) {
                    Text("重启作用域", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.onSurface, modifier = Modifier.padding(vertical = 4.dp))
                    Text("勾选要重启的应用，改动后的开关随之生效", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(bottom = 6.dp))
                    scopeTargets.forEachIndexed { i, t ->
                        val on = picked[t.pkg] == true
                        val probed = FrameworkProbe.state.probed
                        val running = FrameworkProbe.state.has(t.pkg)
                        Row(Modifier.fillMaxWidth().clickable { picked[t.pkg] = !on }.padding(vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            CheckMark(on)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(t.label, fontSize = 15.5.sp, color = MiuixTheme.colorScheme.onSurface)
                                Text(t.pkg, fontSize = 10.5.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    modifier = Modifier.padding(top = 1.dp))
                            }
                            Text(if (!probed) "未知" else if (running) "运行中" else "未运行",
                                fontSize = 11.5.sp,
                                color = if (running) MiuixTheme.colorScheme.primary
                                        else MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                        if (i < scopeTargets.size - 1) {
                            Box(Modifier.fillMaxWidth().height(1.dp)
                                .background(MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.08f)))
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("全选", fontSize = 14.sp, color = MiuixTheme.colorScheme.primary,
                            modifier = Modifier.clickable {
                                val all = scopeTargets.all { picked[it.pkg] == true }
                                scopeTargets.forEach { picked[it.pkg] = !all }
                            }.padding(vertical = 6.dp, horizontal = 4.dp))
                        Spacer(Modifier.weight(1f))
                        Text("取消", fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.clickable { onDismiss() }.padding(8.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (count > 0) "重启选中 ($count)" else "重启选中", fontSize = 15.sp,
                            color = if (count > 0) MiuixTheme.colorScheme.primary
                                    else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.clickable {
                                if (count > 0) {
                                    onDismiss()
                                    restartScopes(scopeTargets.filter { picked[it.pkg] == true })
                                }
                            }.padding(8.dp))
                    }
                }
            }
        }
    }

    /**
     * 直接弹选择框，不再先跑一次 su -c id 预检 —— 那一步要多等一次授权握手，
     * 而命令真失败时 restartScopes 已经会明确报"ROOT 授权被拒绝"。
     */
    private fun requestRootThenPickScope() {
        rootDialogVisible = true
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

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 一次 su 会话把选中的作用域全部重启；成功后延后重读自证记录。 */
    private fun restartScopes(targets: List<ScopeTarget>) {
        if (targets.isEmpty()) return
        val names = targets.joinToString("、") { it.label }
        Toast.makeText(this, "正在重启：$names…", Toast.LENGTH_SHORT).show()
        Thread {
            val ok = suExec(targets.joinToString(" ; ") { it.cmd })
            runOnUiThread {
                Toast.makeText(this,
                    if (ok) "已重启：$names" else "重启失败：ROOT 授权被拒绝",
                    Toast.LENGTH_LONG).show()
            }
            if (ok) {
                // 进程重启后要等 Application 起来才会上报，3s / 8s 各读一次
                mainHandler.postDelayed({ FrameworkProbe.refresh(this, true) }, 3000L)
                mainHandler.postDelayed({ FrameworkProbe.refresh(this, true) }, 8000L)
            }
        }.start()
    }
}
