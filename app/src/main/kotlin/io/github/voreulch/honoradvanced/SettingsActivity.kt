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
    private val S_LAUNCHER = listOf("com.hihonor.android.launcher")
    private val S_INSTALLER = listOf("com.android.packageinstaller")
    private val S_THERMAL = listOf("com.android.systemui", "com.hihonor.iaware")

    // switch write target shared with hooks via CfgProvider
    private fun prefs() = getSharedPreferences(Cfg.PREFS_FILE, MODE_PRIVATE)

    private fun prefsOn(key: String, def: Boolean) = prefs().getBoolean(key, def)
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
                // 判定统一走 Cfg.isOptIn，别再在这里手写键名。
                ed.putBoolean(k, !Cfg.isOptIn(k))
                dirty = true
            }
        }
        // v2.5.3: DIY（glass_extreme + 14 根滑条）已整体移除，被「分区域强度」和
        // 「精细调节」两层取代。这里把旧键清干净，别让它们留在 prefs 里发霉。
        for (stale in arrayOf("live_tune", "glass_extreme", "diy_aligned",
                "diy_mask", "diy_tblur", "diy_bmask", "diy_bblur", "diy_kblur",
                "diy_wmask", "diy_radius", "diy_sat", "diy_edge", "diy_refract",
                "diy_rim", "diy_thick", "diy_disp", "diy_kbbright",
                "prev_diy_mask", "prev_diy_tblur", "prev_diy_bmask", "prev_diy_bblur",
                "prev_diy_kblur", "prev_diy_wmask", "prev_diy_radius", "prev_diy_sat",
                "prev_diy_edge", "prev_diy_refract", "prev_diy_rim", "prev_diy_thick",
                "prev_diy_disp", "prev_diy_kbbright")) {
            if (p.contains(stale)) {
                ed.remove(stale)
                dirty = true
            }
        }
        // v2.6.3: 通用孤儿键清理。
        // 精细调节（fn_*）和粗调（tune_*）的键以前是手写进 stale 列表的，
        // 每删一个参数就要记得补一条 —— 漏一次，旧值就留在 prefs 里静默生效
        // （v2.6.3 删 fn_winblur 时正好撞上：hook 里已经不信这个键了，但用户
        // 之前存的值还在，将来哪天同名键复活就会"诈尸"）。
        // 现在按白名单统一扫：凡是以 fn_/tune_ 开头、又不在当前定义里的键，
        // 一律清掉。以后增删参数不用再动这里。
        run {
            val known = HashSet<String>()
            known.addAll(Cfg.FN_KEYS)
            known.addAll(Cfg.TUNE_KEYS)
            for (k in p.all.keys.toTypedArray()) {
                if ((k.startsWith("fn_") || k.startsWith("tune_")) && k !in known) {
                    ed.remove(k)
                    dirty = true
                }
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
        var subPage by remember { mutableStateOf("") }
        var openGroup by remember { mutableStateOf("") }
        // 三级子页的物理返回：先回二级菜单，而不是直接退出整个「液态玻璃」页。
        // 之前漏了这个，子页按返回键会一路弹回首页（用户报的 bug）。
        BackHandler(enabled = subPage.isNotEmpty()) { subPage = "" }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp)) {
            if (subPage.isEmpty()) {
                item { BackHeader("液态玻璃", back) }
                item { TipText("总开关控制所有玻璃效果；下面三条线各自进去开关和逐项调节。") }
                item {
                    SwitchCard(listOf(
                        SwRow("总开关", "液态玻璃总闸，含 HIGH 强制渲染、LIQUID 材质判定等系统级底层，关闭后全部回退原厂", Cfg.K_GLASS, true, scopes = S_SYSTEMUI)
                    ))
                }
                item {
                    NavRow("系统液态玻璃", "通知卡片、工具栏按钮与亮度条，含光学 / 模糊 / 高光",
                        onClick = { subPage = "sys" })
                }
                item {
                    NavRow("锁屏液态玻璃", "锁屏界面的通知卡片玻璃",
                        onClick = { subPage = "kl" })
                }
                item {
                    NavRow("通知横幅液态玻璃", "来消息时从屏幕顶部弹出的那一条",
                        onClick = { subPage = "bn" })
                }
                item {
                    NavRow("桌面液态玻璃", "桌面卡片与文件夹的模糊 / 饱和度 / 亮度",
                        onClick = { subPage = "desk" })
                }
                item {
                    SwitchCard(listOf(
                        SwRow("相册液态玻璃", "相册照片页工具栏的玻璃效果", Cfg.K_GALLERY, true, scopes = S_PHOTOS)
                    ))
                }
                item { Footnote("开关改动后需重启系统界面（或重启手机）生效。") }
            } else if (subPage == "desk") {
                item { BackHeader("桌面液态玻璃", { subPage = "" }) }
                item {
                    SwitchCard(listOf(
                        SwRow("桌面液态玻璃", "桌面卡片与文件夹的玻璃效果", Cfg.K_DESK, true, scopes = S_LAUNCHER)
                    ))
                }
                item {
                    Column {
                        SectionLabel("精细调节（100% = 原厂 · 改完重启「桌面」生效）")
                        Footnote("⚠️ 这一页改完要**重启桌面**才生效（不是重启系统界面）。桌面的玻璃参数跑在桌面自己的进程里。")
                        ParamGroup("桌面卡片", "desk", openGroup, { openGroup = it }) {
                            TuneRow("模糊半径", Cfg.F_DESK_RADIUS,
                                "桌面卡片和文件夹背后那层模糊的力度。调高更糊更有玻璃感，调低更清晰能看清图标。")
                            TuneRow("饱和度", Cfg.F_DESK_SAT,
                                "卡片透出的壁纸颜色鲜艳度。原厂偏淡，想让桌面更绚丽就往高拖。")
                            TuneRow("亮度", Cfg.F_DESK_BRIGHT, "卡片整体提亮或压暗。")
                        }
                        Footnote("桌面的「液态玻璃」其实只有模糊 + 形状 + 遮罩：系统在桌面这条链上是把折射和高光都设成 null 的（setGlassParamEx(null) / setHighLightParamEx(null)），所以调不出锁屏那种厚玻璃折射感。这是系统的设计，不是模块限制。")
                    }
                }
            } else if (subPage == "sys") {
                item { BackHeader("系统液态玻璃", { subPage = "" }) }
                item {
                    SwitchCard(listOf(
                        SwRow("系统液态玻璃", "状态栏通知与控制中心的观感", Cfg.K_SYSTEM, true, scopes = S_SYSTEMUI)
                    ))
                }
                item {
                    Column {
                        SectionLabel("精细调节（100% = 原厂 · 改完重启「系统界面」生效）")
                        TuneSwitch("强制液态玻璃", Cfg.F_FORCE_LIQUID,
                            "有些界面系统会自己判定成磨砂（比如通知横幅、某些卡片），你没法改。打开这个开关，强制它们也走液态折射。如果哪个界面变糊或变色，关掉即可。",
                            onLabel = "已开启", offLabel = "已关闭")
                        ParamGroup("通知消息卡片", "ntf", openGroup, { openGroup = it }) {
                            TuneRow("卡片底色浓度", Cfg.F_NTF_MASK, "下拉通知栏里那一条条消息卡片，背后那层暗色遮罩的浓淡。")
                            TuneRow("卡片叠加色", Cfg.F_NTF_OVL, "叠在底色上的第二层颜色，影响整体色调。")
                            TuneRow("卡片亮度", Cfg.F_NTF_BRIGHT, "卡片整体提亮或压暗。原厂 0.08，觉得发白就往下调。")
                            TuneRow("卡片饱和度", Cfg.F_NTF_SAT, "卡片透出的背景色彩鲜艳度，原厂 1.2。")
                            TuneRow("卡片圆角", Cfg.F_NTF_CORNER, "通知卡片四个角有多圆，越大越润。")
                            TuneRow("工具栏内卡片底色", Cfg.F_NTFB_MASK, "工具栏（下滑两次）里那几张通知卡的底色浓度。")
                            TuneRow("工具栏内卡片叠加", Cfg.F_NTFB_OVL, "工具栏内通知卡的第二层叠加色。")
                            TuneRow("工具栏内卡片亮度", Cfg.F_NTFB_BRIGHT, "工具栏内通知卡的亮度。")
                        }
                        ParamGroup("工具栏大按钮", "cc", openGroup, { openGroup = it }) {
                            TuneRow("按钮底色浓度", Cfg.F_CC_MASK, "WiFi、蓝牙、飞行模式那些方块按钮的底色浓度。")
                            TuneRow("按钮叠加色", Cfg.F_CC_OVL, "方块按钮的第二层叠加色。")
                            TuneRow("按钮亮度", Cfg.F_CC_BRIGHT, "原厂 0.12，比通知卡更亮。觉得工具栏发白就调这一项。")
                            TuneRow("按钮饱和度", Cfg.F_CC_SAT, "方块按钮透出的背景鲜艳度。")
                            TuneRow("按钮圆角", Cfg.F_CC_CORNER, "方块按钮四个角有多圆。")
                        }
                        ParamGroup("工具栏小按钮", "btn", openGroup, { openGroup = it }) {
                            TuneRow("按钮底色浓度", Cfg.F_BTN_MASK, "手电筒、相机那种圆形小按钮的底色浓度。")
                            TuneRow("按钮叠加色", Cfg.F_BTN_OVL, "圆形小按钮的第二层叠加色。")
                            TuneRow("按钮亮度", Cfg.F_BTN_BRIGHT, "原厂 0.08。")
                            TuneRow("按钮饱和度", Cfg.F_BTN_SAT, "圆形小按钮透出的背景鲜艳度。")
                            TuneRow("按钮圆角", Cfg.F_BTN_CORNER, "圆形小按钮的圆角。")
                        }
                        ParamGroup("亮度/音量条", "sl", openGroup, { openGroup = it }) {
                            TuneRow("条底色浓度", Cfg.F_SL_MASK, "能左右拖动的那根长条的底色浓度。")
                            TuneRow("条叠加色", Cfg.F_SL_OVL, "长条的第二层叠加色。")
                            TuneRow("条亮度", Cfg.F_SL_BRIGHT, "原厂 0.12。")
                            TuneRow("条饱和度", Cfg.F_SL_SAT, "长条透出的背景鲜艳度。")
                            TuneRow("条圆角", Cfg.F_SL_CORNER, "长条两端有多圆。")
                        }
                        ParamGroup("玻璃光学（全局）", "opt", openGroup, { openGroup = it }) {
                            TuneRow("折射强度", Cfg.F_OPT_REFRACT, "玻璃边缘把背景弯折的力度，越大越像真玻璃。")
                            TuneRow("折射深度", Cfg.F_OPT_DEPTH, "折射影响的范围有多深。")
                            TuneRow("玻璃厚度", Cfg.F_OPT_THICK, "边缘折射带有多宽，越厚越像一块厚玻璃。")
                            TuneRow("色散", Cfg.F_OPT_DISP, "边缘的彩色色边，越高彩虹感越强。")
                        }
                        ParamGroup("模糊（全局）", "blur", openGroup, { openGroup = it }) {
                            TuneRow("卡片模糊半径", Cfg.F_BLUR_CARD, "卡片背后背景糊掉的程度，越小越清晰。不管横幅，横幅有自己的一根。")
                            TuneRow("动态卡模糊", Cfg.F_BLUR_DYNAMIC, "动态卡片单独用的模糊半径。同样不管横幅。")
                            TuneRow("模糊取样精度", Cfg.F_BLUR_SCALE, "模糊前的降采样比例，影响性能与颗粒感。")
                        }
                        ParamGroup("边缘高光（全局）", "hl", openGroup, { openGroup = it }) {
                            TuneRow("高光宽度", Cfg.F_HL_THICK, "玻璃边缘那圈亮边的粗细。")
                            TuneRow("高光亮度", Cfg.F_HL_ALPHA, "那圈亮边的透明度，越低越淡。")
                            TuneRow("高光角度", Cfg.F_HL_ANGLE, "光源方向，0 到 360 度。原厂 250，改它能让光从别的方向照过来。", rangeMax = 360, unit = "°", stepCount = 71)
                            TuneSwitch("关闭全部高光", Cfg.F_HL_ON, "一次性关掉所有玻璃边缘的亮边。")
                            TuneSwitch("关闭大按钮高光", Cfg.F_HL_CC, "只关工具栏方块按钮的亮边。")
                            TuneSwitch("关闭小按钮高光", Cfg.F_HL_BTN, "只关圆形小按钮的亮边。")
                            TuneSwitch("关闭亮度条高光", Cfg.F_HL_SL, "只关亮度/音量条的亮边。")
                        }
                        Footnote("每个数字都是原厂值的百分比。100 = 原厂，低于 100 变弱、高于 100 变强。点右边的 ! 看这一项管什么。")
                    }
                }
            } else if (subPage == "kl") {
                item { BackHeader("锁屏液态玻璃", { subPage = "" }) }
                item {
                    SwitchCard(listOf(
                        SwRow("锁屏液态玻璃", "锁屏按钮与通知卡片的玻璃效果", Cfg.K_KEYGUARD, true, scopes = S_SYSTEMUI)
                    ))
                }
                item {
                    Column {
                        SectionLabel("精细调节（100% = 原厂）")
                        Footnote("⚠️ 这一页改完**必须重启手机**才生效。只重启「系统界面」不够——锁屏上的按钮、图标、文字在系统界面刚启动的头两秒就画好了，那时设置还没读进来，会静默跳过。")
                        ParamGroup("锁屏通知卡片", "kl", openGroup, { openGroup = it }) {
                            TuneRow("卡片底色浓度", Cfg.F_KL_MASK, "锁屏界面上那几条通知卡片，背后那层暗色遮罩的浓淡。")
                            TuneRow("卡片叠加色", Cfg.F_KL_OVL, "叠在锁屏卡片底色上的第二层颜色。")
                            TuneRow("卡片亮度", Cfg.F_KL_BRIGHT, "锁屏通知卡整体提亮或压暗。原厂按壁纸亮度自动算，模块默认锁在 0.1。")
                            TuneRow("卡片饱和度", Cfg.F_KL_SAT, "锁屏卡片透出的背景鲜艳度，原厂 1.2。")
                            TuneRow("卡片圆角", Cfg.F_KL_CORNER, "锁屏通知卡四个角有多圆。")
                        }
                        Footnote("锁屏的配方和桌面不一样：卡片更暗、亮度更低。以上只影响锁屏。")
                        ParamGroup("锁屏时钟玻璃", "kgclock", openGroup, { openGroup = it }) {
                            TuneRow("时钟腐蚀强度", Cfg.F_KG_ERODE,
                                "锁屏那个大时钟的玻璃「厚度」。原厂按字体和字号自动算（0.45 到 2.49 之间），你没法管。往上拖边缘更厚更糊、像块厚玻璃；往下拖边缘变薄变锐利。")
                        }
                        Footnote("时钟这一项走的是另一条链路（keyguardengine 的腐蚀表），不是上面那套卡片参数。调它不会影响通知卡。")
                        ParamGroup("锁屏按钮与文字", "kgelem", openGroup, { openGroup = it }) {
                            TuneRow("底部按钮", Cfg.F_KG_BTN,
                                "锁屏底部那几个玻璃按钮——手电筒、相机那一排，连图标带底下那块圆角底板一起。调低更透更淡，调高更浓更实。")
                            TuneRow("锁形图标", Cfg.F_KG_ICON,
                                "锁屏上方那个小锁图标，还有节日彩蛋图标（春节/中秋那种）。")
                            TuneRow("提示文字", Cfg.F_KG_TXT,
                                "「上滑解锁」这类提示、充电状态文字、杂志锁屏的标题。只改文字底下那层玻璃的浓度，不改文字本身的颜色。")
                        }
                        Footnote("这三项管的是元素**背后的玻璃浓度**（模糊半径 / 饱和度 / 亮度），不是图标颜色。图标颜色由系统的壁纸取色算，模块改不了。")
                        ParamGroup("按钮的光学", "btnopt", openGroup, { openGroup = it }) {
                            TuneRow("折射深度", Cfg.F_KG_BTN_DEP, "按钮玻璃「往里凹」的深度。越深越像一块厚玻璃，越浅越像贴在屏上的膜。只在底部按钮上生效。")
                            TuneRow("色散", Cfg.F_KG_BTN_DIS, "按钮边缘那道彩色边的强弱（像肥皂泡那种）。调低更干净。")
                            TuneRow("玻璃厚度", Cfg.F_KG_BTN_THK, "按钮边缘折射带的宽度。原厂已经很厚，觉得糊就往下调。")
                            TuneRow("边缘光", Cfg.F_KG_BTN_EDGE, "按钮边缘那圈亮边的浓淡。")
                        }
                        ParamGroup("图标的光学", "iconopt", openGroup, { openGroup = it }) {
                            TuneRow("折射深度", Cfg.F_KG_ICON_DEP, "只作用在锁形图标和节日图标上，和按钮那根互不影响。")
                            TuneRow("色散", Cfg.F_KG_ICON_DIS, "图标边缘的彩色边强弱。")
                            TuneRow("玻璃厚度", Cfg.F_KG_ICON_THK, "图标那一小块玻璃的折射带宽度。")
                            TuneRow("边缘光", Cfg.F_KG_ICON_EDGE, "图标边缘亮边的浓淡。")
                        }
                        ParamGroup("文字的光学", "txtopt", openGroup, { openGroup = it }) {
                            TuneRow("折射深度", Cfg.F_KG_TXT_DEP, "提示文字底下那层玻璃的凹陷深度。")
                            TuneRow("色散", Cfg.F_KG_TXT_DIS, "文字玻璃边缘的彩色边。")
                            TuneRow("玻璃厚度", Cfg.F_KG_TXT_THK, "文字那层玻璃的折射带宽度。")
                            TuneRow("边缘光", Cfg.F_KG_TXT_EDGE, "文字玻璃边缘亮边的浓淡。")
                        }
                        Footnote("「光学」这四组是三套完全独立的参数：按钮、图标、文字各有自己的深度/色散/厚度/边缘光，拖其中一组不会影响另外两组。")
                        ParamGroup("锁屏小组件", "kgwidget", openGroup, { openGroup = it }) {
                            TuneRow("小组件透明度", Cfg.F_KG_WIDGET,
                                "锁屏上那几块小组件——天气、闹钟、步数那些圆角卡片。100% 是系统原样，调低更透、能看见后面的壁纸；调高更实。注意是整块一起变透，里面的文字也会跟着淡。")
                        }
                        Footnote("小组件走的是独立的一条链（AppWidgetHelper → KgAppWidgetHostView），和上面的按钮、时钟都不是一套，所以单独一根。")
                        ParamGroup("锁屏壁纸玻璃", "kgbg", openGroup, { openGroup = it }) {
                            TuneRow("壁纸模糊半径", Cfg.F_KG_BG_RADIUS,
                                "锁屏壁纸那层模糊的力度。原厂跟「通透度」绑死：通透度 100 时半径只有 9（很轻），0 时 32（很糊）。想让壁纸更糊就往高拖，更清楚就往低拖。")
                            TuneRow("壁纸饱和度", Cfg.F_KG_BG_SAT,
                                "锁屏壁纸透出来的颜色鲜艳度。原厂是写死的 1.3，跟通透度完全无关——所以你觉得淡或太艳都没法调。现在能了。")
                            TuneRow("指纹饱和度", Cfg.F_KG_FP_SAT,
                                "锁屏指纹区域那圈玻璃的鲜艳度。原厂只有 1.3 / 1.2 两档（按通透度分），没法细调。")
                        }
                        Footnote("这一组管的是「整块锁屏壁纸」，不是上面的卡片或按钮。三个都是乘在原厂值上，100% = 完全不动。")
                    }
                }
            } else {
                item { BackHeader("通知横幅液态玻璃", { subPage = "" }) }
                item {
                    SwitchCard(listOf(
                        SwRow("通知横幅液态玻璃", "来通知时顶部弹出的横幅玻璃效果", Cfg.K_BANNER, true, scopes = S_SYSTEMUI)
                    ))
                }
                item {
                    Column {
                        SectionLabel("精细调节（100% = 原厂 · 改完重启「系统界面」生效）")
                        ParamGroup("通知横幅", "bn", openGroup, { openGroup = it }) {
                            TuneRow("横幅底色浓度", Cfg.F_BN_MASK, "来通知时从屏幕顶部弹出的那条横幅，背后暗色遮罩的浓淡。")
                            TuneRow("横幅叠加色", Cfg.F_BN_OVL, "横幅的第二层叠加色。")
                            TuneRow("横幅亮度", Cfg.F_BN_BRIGHT, "原厂是 0（完全不提亮），和通知卡的 0.08 完全不同，必须单独调。")
                            TuneRow("横幅饱和度", Cfg.F_BN_SAT, "原厂白天 1.8、夜间 1.5，比通知卡艳得多。觉得太冲就往下调。")
                            TuneRow("横幅模糊", Cfg.F_BN_BLUR, "横幅背后背景糊掉的程度。原厂已乘过一次 0.5，这里在其基础上再调。")
                        }
                        Footnote("横幅的亮度和饱和度跟通知卡完全不同（原厂亮度 0、饱和度 1.8），所以单独一组。")
                    }
                }
            }
        }
    }

    /** 二级菜单入口：点击进入三级子页。 */
    @Composable
    private fun NavRow(title: String, desc: String, onClick: () -> Unit) {
        CardShell {
            Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(18.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, fontSize = 16.sp, color = MiuixTheme.colorScheme.onSurface)
                    Text(desc, fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
                Text(">", fontSize = 20.sp, color = MiuixTheme.colorScheme.primary)
            }
        }
    }

    @Composable
    private fun ParamGroup(
        title: String,
        id: String,
        state: String,
        onState: (String) -> Unit,
        content: @Composable ColumnScope.() -> Unit
    ) {
        val open = state == id
        CardShell {
            Row(
                Modifier.fillMaxWidth().clickable { onState(if (open) "" else id) }
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, fontSize = 16.sp, color = MiuixTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f))
                Text(if (open) "收起" else "展开", fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.primary)
            }
            if (open) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 18.dp).height(1.dp)
                    .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.08f)))
                Column(Modifier.padding(bottom = 8.dp), content = content)
            }
        }
    }

    /**
     * 开关型精细参数：原厂已开启，打开这里 = 关掉它。
     * 值语义 0 = 跟随原厂，1 = 强制关闭。
     */
    @Composable
    private fun TuneSwitch(label: String, key: String, tip: String = "",
                           onLabel: String = "已关闭", offLabel: String = "原厂") {
        val hv = androidx.compose.ui.platform.LocalView.current
        var on by remember { mutableStateOf(prefs().getInt(key, Cfg.fnDefPct(key)) != 0) }
        Row(
            Modifier.fillMaxWidth().clickable {
                on = !on
                hv.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                prefs().edit().putInt(key, if (on) 1 else 0).commit()
            }.padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, fontSize = 15.sp, color = MiuixTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f))
            if (tip.isNotEmpty()) {
                InfoDot(tip)
                Spacer(Modifier.width(8.dp))
            }
            Text(if (on) onLabel else offLabel, fontSize = 13.sp,
                color = MiuixTheme.colorScheme.primary)
        }
    }

    @Composable
    /**
     * 一根精细调节滑条。
     *
     * [stepCount] 是 Slider 的 steps 参数（吸附格数 - 1）。默认 19 = 把 0..200
     * 切成 20 格、每格 10（即 5% 吸附）。
     *
     * ⚠ 角度那根必须传 71：rangeMax=360 时用 19 会变成 18°/格，
     * **原厂值 250 根本不在网格上**（只能落在 234 或 252），用户一动就回不去。
     * 71 格 = 5°/格，250 正好是 5 的倍数；haptic 的 %10 判断在两种网格下都能命中。
     */
    private fun TuneRow(label: String, key: String, tip: String = "",
                        rangeMax: Int = 200, unit: String = "%", stepCount: Int = 19) {
        // fn_ 开头的是精细参数、其余是分区域强度，两者的出厂默认分开烧录
        val defPct = if (key.startsWith("fn_")) Cfg.fnDefPct(key) else Cfg.tuneDefPct(key)
        var pct by remember { mutableStateOf(prefs().getInt(key, defPct)) }
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(label, fontSize = 15.sp, color = MiuixTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f))
                if (tip.isNotEmpty()) {
                    InfoDot(tip)
                    Spacer(Modifier.width(8.dp))
                }
                Text("$pct$unit", fontSize = 13.sp, color = MiuixTheme.colorScheme.primary)
            }
            val view = androidx.compose.ui.platform.LocalView.current
            Slider(
                value = pct.toFloat(),
                onValueChange = { v ->
                    val nv = v.toInt()
                    if (nv != pct && nv % 10 == 0) {
                        view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                    }
                    pct = nv
                },
                valueRange = 0f..rangeMax.toFloat(),
                steps = stepCount,
                onValueChangeFinished = { prefs().edit().putInt(key, pct).commit() },
                modifier = Modifier.fillMaxWidth()
            )
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

    /**
     * 圆形感叹号：点一下弹出这段说明。
     * 用来替代底部大段脚注 —— 每项调节旁边各挂一个，想看哪项点哪项，不占版面。
     */
    @Composable
    private fun InfoDot(tip: String) {
        var open by remember { mutableStateOf(false) }
        Box(
            Modifier.size(17.dp).clip(CircleShape)
                .background(MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.20f))
                .clickable { open = true },
            contentAlignment = Alignment.Center
        ) {
            Text("!", fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        if (open) {
            Dialog(onDismissRequest = { open = false }) {
                Column(
                    Modifier.clip(RoundedCornerShape(18.dp))
                        .background(MiuixTheme.colorScheme.surface)
                        .padding(20.dp)
                ) {
                    Text(tip, fontSize = 14.sp, lineHeight = 21.sp,
                        color = MiuixTheme.colorScheme.onSurface)
                }
            }
        }
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
