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
import androidx.compose.runtime.saveable.rememberSaveable
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
        ScopeTarget("com.hihonor.iaware", "温控服务", "killall com.hihonor.iaware || true"),
        ScopeTarget("com.hihonor.securityserver", "反诈服务", "killall com.hihonor.securityserver || true"),
        // v3.2.48：反诈现在同时作用于感知服务本体（它自己有 fraud 逻辑和独立上报通道），
        // 重启作用域时不能漏 —— 漏了的话改了反诈开关、重启完"反诈服务"还以为生效了，
        // 实际上 awareness 那边照旧跑旧代码。
        ScopeTarget("com.hihonor.awareness", "感知服务", "killall com.hihonor.awareness || true"),
        // 特殊项：android（system_server）作用域改不了单个进程，只能整机重启。
        // pkg 用 __reboot__ 作哨兵，restartScopes 会单独识别并弹确认。
        ScopeTarget("__reboot__", "重启系统（system_server 生效）", "reboot")
    )

    // 各开关依赖的作用域（用于"作用域状态"提示）。system_server（android）读不到
    // 模块 provider、也没法自证，所以不列进来 —— 它在开机时随进程加载。
    private val S_SYSTEMUI = listOf("com.android.systemui")
    private val S_PHOTOS = listOf("com.hihonor.photos")
    private val S_LAUNCHER = listOf("com.hihonor.android.launcher")
    private val S_INSTALLER = listOf("com.android.packageinstaller")
    private val S_THERMAL = listOf("com.android.systemui", "com.hihonor.iaware")
    /** 安全组件里的窗口/系统级钩子都在 system_server（作用域显示为 android）。 */
    private val S_SYSTEM = listOf("android")
    /** 反诈组件在 securityserver 进程。 */
    // v3.2.48：反诈现在要两个作用域 —— 反诈服务（securityserver）+ 感知服务（awareness）。
    // 只列前者的话，界面会显示"作用域已生效"，但 awareness 那边根本没被注入，
    // 反诈数据照样从感知服务流出去 —— 典型的假绿灯。
    private val S_NOFRAUD = listOf("com.hihonor.securityserver", "com.hihonor.awareness")

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

    override fun onDestroy() {
        // v3.2.36：Activity 销毁后，postDelayed 排进主队列的 Runnable **仍持有 this**。
        // 延迟任务体里只要碰一次 UI（recreate / 强制刷新那种），轻则泄漏一整个
        // Activity，重则 NPE 闪退。mainHandler 是实例字段，清它不会影响别的 Activity。
        try {
            mainHandler.removeCallbacksAndMessages(null)
        } catch (_: Throwable) {
        }
        super.onDestroy()
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
        // v3.2.33：「清理云端配置残留」已改成独立按钮（见 SecurityPage 里的
        // NavRow + ConfirmEnableDialog），不再走开关分支 —— 它代表"动作"而不是"状态"。
        // 降级安装的 system_server 侧同样读不到 provider，也要走属性。
        if (key == Cfg.K_DOWNGRADE) {
            syncDowngradeProp(on) { ok ->
                if (!ok && on) {
                    prefs().edit().putBoolean(Cfg.K_DOWNGRADE, false).commit()
                    notifyCfgChanged()
                    onResult?.invoke(false)
                } else {
                    onResult?.invoke(ok)
                }
            }
            return
        }
        // 截图限制也必须同步到系统属性：hook 挂在 system_server，那边读不到 provider。
        if (key == Cfg.K_NOSECURE) {
            syncNosecureProp(on) { ok ->
                // 写属性失败时把开关拨回去，别让 UI 显示"已开启"却没生效
                if (!ok && on) {
                    prefs().edit().putBoolean(Cfg.K_NOSECURE, false).commit()
                    notifyCfgChanged()
                    onResult?.invoke(false)
                } else {
                    onResult?.invoke(ok)
                }
            }
            return
        }
        // v3.2.33：「清理云端配置残留」已从开关改成独立按钮（SecurityPage 里
        // 的 NavRow），不再经过这里 —— 它没有"开/关"两种状态，也没有 prefs 键
        // 需要写。Cfg.K_PURGECOTA 常量保留只为兼容旧 prefs 与 provider 快照。
        if (key == Cfg.K_HOTTEMP) {
            Toast.makeText(this, "正在写入系统属性（ROOT）…", Toast.LENGTH_SHORT).show()
            syncHottempProp(on) { ok ->
                if (!ok && !on) {
                    // cannot disable the system layer without root: keep the
                    // switch ON so the UI matches what the device is doing
                    // commit 失败（磁盘满 / 存储异常）时 prefs 会保持旧值，
                    // 界面却已经回滚成"开" —— 下次进来会看到"关"，与实际不符。
                    // 这种静默失败必须留痕，否则永远查不出来。
                    val wrote = prefs().edit().putBoolean(key, true).commit()
                    if (!wrote) AppLog.d("prefs", "回写 hottemp=true 失败（commit 返回 false）")
                    notifyCfgChanged()
                    Toast.makeText(this,
                        "关不掉：su 没执行成功。① 去 KernelSU / Magisk 管理器的「超级用户」里给本模块授权；" +
                        "② 或在终端执行 su -c setprop persist.sys.has.hottemp 0。" +
                        "属性现在还是 1，屏蔽仍在生效——开关保持开启是为了不骗你。" + "授权完请再点一次这个开关（重装过模块的话，UID 变了要重新授权）。",
                        Toast.LENGTH_LONG).show()
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
            // 上一次 su 还在等授权（最长 30 秒）。此时再点不能静默返回"失败"——
            // 用户会以为点了没用，反复点。明确告诉他正在等。
            runOnUiThread {
                Toast.makeText(this,
                    "正在等待 ROOT 授权…请在 KernelSU / Magisk 的弹窗或「超级用户」里点允许",
                    Toast.LENGTH_SHORT).show()
                done?.invoke(false)
            }
            return
        }
        propSyncBusy = true
        Thread {
            var ok = false
            try {
                val want = if (on) "1" else "0"
                val before = readProp(Cfg.PROP_HOTTEMP)
                AppLog.d("prop", "目标=" + want + " 当前=" + before)
                if (before != want) {
                    // 30 秒：KernelSU / Magisk 的授权是**异步**的 —— su 会一直阻塞，
                    // 等用户切到管理器点「允许」。原来 8 秒，从点开关到切过去根本不够，
                    // 表现就是「明明 root 了却提示未获得 ROOT」。
                    ok = suExecTimeout("setprop ${Cfg.PROP_HOTTEMP} $want", 30000L)
                } else {
                    ok = true
                }
                // 只认回读：exit code 为 0 不代表属性真的落地了。
                // 早先这里写成 `(now == want) || now.isNullOrEmpty()` ——
                // 属性读不到也当成功，等于谎报，用户以为关掉了其实还在屏蔽。
                if (ok) {
                    var now = readProp(Cfg.PROP_HOTTEMP)
                    if (now != want) {
                        // persist.* 一般同步落地；给属性服务 250ms 再读一次
                        try { Thread.sleep(250L) } catch (_: Throwable) {}
                        now = readProp(Cfg.PROP_HOTTEMP)
                    }
                    ok = (now == want)
                    AppLog.d("prop", "写后回读=" + now + " → " + (if (ok) "成功" else "不一致"))
                } else {
                    AppLog.d("prop", "写入失败，回读=" + readProp(Cfg.PROP_HOTTEMP))
                }
            } catch (_: Throwable) { ok = false }
            propSyncBusy = false
            if (done != null) runOnUiThread { done(ok) }
        }.apply { name = "has-prop-sync" }.start()
    }

    /**
     * `su` 的候选路径。
     *
     * v3.0.2 加：原来只 `ProcessBuilder("su", ...)` 依赖 PATH。实测有用户的
     * KernelSU 是**越狱模式（LKM）**，app 进程的 PATH 里没有 su，
     * 于是 `start()` 直接抛 IOException —— 表现就是「未获得 ROOT」，
     * 但用户其实是 root 了的，只是模块根本没找到 su 在哪。
     *
     * 顺序按命中概率排：PATH → Magisk/KernelSU 标准 → 老路径 → KSU 实路径。
     */
    private val SU_PATHS = listOf(
        "su",
        "/system/bin/su",
        "/system/xbin/su",
        "/debug_ramdisk/su",
        "/sbin/su",
        "/data/adb/ksu/bin/su",
        "/data/adb/ap/bin/su",
    )

    /**
     * 起一个 su 进程；路径全都不存在时返回 null。
     *
     * 注意：**拿到进程就不再换路径**。如果 su 存在但没被授权，换路径还是同一个 su，
     * 再试只会多弹几次授权框。所以只有 IOException（路径不存在）才往下试。
     */
    private fun startSu(cmd: String): Process? {
        for (path in SU_PATHS) {
            try {
                val p = ProcessBuilder(path, "-c", cmd).redirectErrorStream(true).start()
                AppLog.d("su", "启动 path=$path cmd=$cmd")
                return p
            } catch (_: java.io.IOException) {
                AppLog.d("su", "路径不可用 path=$path")
            } catch (e: SecurityException) {
                // SELinux 拦了这条路径 —— 也必须换下一条试，不能就此放弃。
                // 早先这里掉进下面的 Throwable 分支直接 return null，
                // 等于「一条路径被策略挡住 = 整个 root 兜底失效」。
                AppLog.d("su", "被安全策略拒绝 path=" + path + " " + e.javaClass.simpleName)
            } catch (_: Throwable) {
                AppLog.d("su", "异常 path=$path")
                return null
            }
        }
        AppLog.d("su", "全部路径都起不来 cmd=$cmd")
        return null
    }


    /** su -c with a hard timeout (never block on an unanswered prompt). */
    private fun suExecTimeout(cmd: String, timeoutMs: Long): Boolean {
        return try {
            val p = startSu(cmd) ?: return false
            val drain = Thread {
                try {
                    val ins = p.inputStream
                    val buf = ByteArray(256)
                    while (ins.read(buf) >= 0) { /* drain */ }
                } catch (_: Throwable) {}
            }
            drain.isDaemon = true
            drain.start()
            val t0 = android.os.SystemClock.uptimeMillis()
            val finished = p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            val cost = android.os.SystemClock.uptimeMillis() - t0
            if (!finished) {
                AppLog.d("su", "超时 " + cost + " ms 已强杀")
                // 先 destroy（SIGTERM），再补一刀 destroyForcibly —— su 是 native
                // 进程，对 SIGTERM 不一定响应，只 destroy 会留个僵尸挂着等授权
                try { p.destroy() } catch (_: Throwable) {}
                try { p.destroyForcibly() } catch (_: Throwable) {}
                false
            } else {
                val code = p.exitValue()
                AppLog.d("su", "完成 exit=" + code + " 耗时=" + cost + " ms")
                code == 0
            }
        } catch (_: Throwable) { false }
    }

    private fun readProp(name: String): String? = try {
        val sp = Class.forName("android.os.SystemProperties")
        sp.getMethod("get", String::class.java, String::class.java)
            .invoke(null, name, "") as? String
    } catch (_: Throwable) { null }

    // ================= 诊断日志导出（v3.0.3） =================

    /**
     * 组装一份能直接发给我们看的诊断报告。
     *
     * 设计原则：**能自动拿到的全部自动拿，用户只需要点一下**。
     * 上次那位「温控关不掉」的用户，光是要他说明"用的什么 root、su 在哪、
     * 授权没授权"就来回问了好几轮 —— 这些全都可以自动收集。
     */
    private fun buildDiagReport(): String {
        val sb = StringBuilder()
        val now = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())

        sb.appendLine("===== 药师高级设置 诊断报告 =====")
        sb.appendLine("生成时间: " + now)
        sb.appendLine("应用版本: " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")")
        sb.appendLine("设备: " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL
                + " / Android " + android.os.Build.VERSION.RELEASE
                + " (SDK " + android.os.Build.VERSION.SDK_INT + ")"
                + " / " + android.os.Build.SUPPORTED_ABIS.firstOrNull())
        sb.appendLine("ROM: " + (readProp("ro.build.display.id") ?: "?"))
        sb.appendLine()

        sb.appendLine("===== su 路径探测 =====")
        for (path in SU_PATHS) {
            val f = if (path.contains("/")) java.io.File(path) else null
            val state = when {
                f == null -> "(走 PATH)"
                f.exists() && f.canExecute() -> "存在且可执行"
                f.exists() -> "存在但不可执行"
                else -> "不存在"
            }
            sb.appendLine("  " + path.padEnd(24) + " : " + state)
        }
        sb.appendLine()

        sb.appendLine("===== root 方案痕迹 =====")
        for (d in listOf("/data/adb/magisk", "/data/adb/ksu", "/data/adb/ap", "/data/adb/modules")) {
            sb.appendLine("  " + d.padEnd(24) + " : " + if (java.io.File(d).exists()) "存在" else "无")
        }
        sb.appendLine()

        sb.appendLine("===== 属性快照 =====")
        // 三个走系统属性的开关都要列出来 —— 它们的值决定 system_server 那边
        // 到底放不放行，用户报"开关不生效"时，这三行是第一手证据。
        sb.appendLine("  " + Cfg.PROP_HOTTEMP + " = " + (readProp(Cfg.PROP_HOTTEMP) ?: "(读不到)"))
        sb.appendLine("  " + Cfg.PROP_NOSECURE + " = " + (readProp(Cfg.PROP_NOSECURE) ?: "(读不到)"))
        sb.appendLine("  " + Cfg.PROP_DOWNGRADE + " = " + (readProp(Cfg.PROP_DOWNGRADE) ?: "(读不到)"))
        sb.appendLine()

        // system_server 有没有被注入，之前只能靠猜（它无法向 provider 自证）。
        // 现在 hook 侧会写 sysprobe 属性，这里读出来即可。
        sb.appendLine("  persist.sys.has.sysprobe = "
                + (readProp("persist.sys.has.sysprobe") ?: "(读不到 → system_server 可能未注入)"))
        sb.appendLine()
        sb.appendLine("===== 开关快照 =====")
        val p = prefs()
        for (k in Cfg.KEYS) {
            sb.appendLine("  " + k.padEnd(14) + " = " + p.getBoolean(k, false))
        }
        sb.appendLine()

        sb.appendLine("===== 操作日志（本次会话，最近 400 条）=====")
        sb.appendLine(AppLog.snapshot())
        sb.appendLine()
        sb.appendLine("===== 操作日志（历史落盘，尾部 20000 字）=====")
        val hist = AppLog.fileText()
        sb.appendLine(if (hist.length > 20000) hist.takeLast(20000) else hist)
        sb.appendLine()

        sb.appendLine("===== LSPosed 模块日志（尾部 300 行，需 root）=====")
        sb.appendLine(readLsposedLogTail())

        return sb.toString()
    }

    /**
     * 用 su 读一段命令输出，带**硬超时**。
     *
     * 为什么不用 `readText()` + `waitFor()`：`readText()` 要读到 EOF 才返回，
     * 而 su 卡在授权提示时永远不会 EOF —— 导出按钮就会一直转圈。
     * 这里用「后台线程读 + 主线程 waitFor 卡超时 + 超时强杀」的组合，
     * 任何情况下都能在 [timeoutMs] 内返回。
     */
    private fun suReadOutput(cmd: String, timeoutMs: Long): String? {
        return try {
            val p = startSu(cmd) ?: return null
            val sb = StringBuilder()
            val reader = Thread {
                try {
                    p.inputStream.bufferedReader().use { r ->
                        var line = r.readLine()
                        // 上限 200KB：日志尾巴用不了这么多，防极端情况下吃内存
                        while (line != null && sb.length < 200_000) {
                            sb.append(line).append("\n")
                            line = r.readLine()
                        }
                    }
                } catch (_: Throwable) {
                }
            }
            reader.isDaemon = true
            reader.start()
            val finished = p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!finished) {
                try { p.destroy() } catch (_: Throwable) {}
                try { p.destroyForcibly() } catch (_: Throwable) {}
                AppLog.d("su", "读日志超时 " + timeoutMs + "ms，已强杀")
                return null
            }
            reader.join(2000L)   // 等读线程收尾，最多再等 2 秒
            sb.toString()
        } catch (t: Throwable) {
            AppLog.d("su", "读日志失败: " + t.javaClass.simpleName)
            null
        }
    }

    /** 用 su 读 LSPosed 的模块日志尾巴；读不到就明说，不假装成功。 */
    private fun readLsposedLogTail(): String {
        val out = suReadOutput("tail -n 300 /data/adb/lspd/log/*.log 2>/dev/null", 8000L)
            ?: return "(读不到 —— su 起不来或超时。这本身就是一条诊断信息)"
        return if (out.isBlank()) "(空)" else out
    }

    private fun notifyCfgChanged() {
        try { getContentResolver().notifyChange(CfgProvider.URI, null) } catch (_: Throwable) {}
    }

    // ================= 液态玻璃参数导出 / 导入（v3.1.4） =================

    /** 液态玻璃这一摊子管的所有键：6 个线开关 + 75 个精细参数。 */
    private fun glassSwitchKeys() = listOf(
        Cfg.K_GLASS, Cfg.K_SYSTEM, Cfg.K_KEYGUARD,
        Cfg.K_GALLERY, Cfg.K_BANNER, Cfg.K_DESK
    )

    /**
     * 把液态玻璃的全部配置打包成 JSON。
     *
     * 只导**这一摊**的键（线开关 + 精细参数），不带安装器 / 温控 / 诊断开关 ——
     * 那份配置拿去别的机器上导入时，不应该顺手改掉别人的安装器净化设置。
     */
    private fun buildGlassConfigJson(): String {
        val p = prefs()
        val root = org.json.JSONObject()
        root.put("app", "HonorAdvancedSettings")
        root.put("kind", "glass-config")
        root.put("version", BuildConfig.VERSION_NAME)
        root.put("versionCode", BuildConfig.VERSION_CODE)
        root.put("exportedAt", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
            java.util.Locale.US).format(java.util.Date()))

        val sw = org.json.JSONObject()
        for (k in glassSwitchKeys()) sw.put(k, p.getBoolean(k, true))
        root.put("switches", sw)

        val params = org.json.JSONObject()
        for (k in Cfg.FN_KEYS) params.put(k, p.getInt(k, Cfg.fnDefPct(k)))
        root.put("params", params)

        return root.toString(2)
    }

    /** 导出：写进「下载」目录，文件名带时间戳。 */
    private fun exportGlassConfig() {
        Thread {
            val json = try { buildGlassConfigJson() } catch (t: Throwable) {
                runOnUiThread {
                    Toast.makeText(this, "导出失败：" + t.javaClass.simpleName, Toast.LENGTH_LONG).show()
                }
                return@Thread
            }
            val name = "has-glass-" + java.text.SimpleDateFormat("yyyyMMdd-HHmmss",
                java.util.Locale.US).format(java.util.Date()) + ".json"
            val saved = saveTextToDownloads(name, json, "application/json")
            runOnUiThread {
                if (saved != null) {
                    Toast.makeText(this, "已导出到 " + saved + "，换机时用「导入」恢复",
                        Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "导出失败，请检查存储空间", Toast.LENGTH_LONG).show()
                }
            }
        }.apply { name = "has-glass-export" }.start()
    }

    /**
     * 导入：读 JSON → 校验 → 写 prefs。
     *
     * 校验规则：只认**允许列表内**的键（防手改 JSON 塞奇怪的东西），
     * 数值夹到 0..1000。导入完提示重启系统界面。
     */
    private fun importGlassConfig(uri: android.net.Uri) {
        Thread {
            var okCount = 0
            var err: String? = null
            try {
                // 上限 1MB：一份完整配置不到 8KB，超过这个数一定是选错文件了。
                // 不设限的话 readBytes() 会把整个文件读进内存，选了个几百 MB 的
                // 文件就直接 OOM —— 后台线程 OOM 会拖崩整个进程。
                val bytes = contentResolver.openInputStream(uri)?.use { ins ->
                    val buf = java.io.ByteArrayOutputStream()
                    val tmp = ByteArray(8192)
                    while (true) {
                        val n = ins.read(tmp)
                        if (n < 0) break
                        buf.write(tmp, 0, n)
                        if (buf.size() > 1024 * 1024) {
                            throw IllegalStateException("文件太大，不像是配置文件")
                        }
                    }
                    buf.toByteArray()
                } ?: throw IllegalStateException("读不到文件")
                val text = bytes.toString(Charsets.UTF_8)
                val root = org.json.JSONObject(text)
                if (root.optString("kind") != "glass-config") {
                    throw IllegalStateException("这不是液态玻璃配置（缺少 kind 标记）")
                }
                val ed = prefs().edit()
                val sw = root.optJSONObject("switches")
                if (sw != null) {
                    for (k in glassSwitchKeys()) {
                        if (sw.has(k)) {
                            ed.putBoolean(k, sw.optBoolean(k, true))
                            okCount++
                        }
                    }
                }
                val ps = root.optJSONObject("params")
                if (ps != null) {
                    for (k in Cfg.FN_KEYS) {
                        if (ps.has(k)) {
                            val v = ps.optInt(k, Cfg.fnDefPct(k)).coerceIn(0, 1000)
                            ed.putInt(k, v)
                            okCount++
                        }
                    }
                }
                if (okCount == 0) throw IllegalStateException("文件里没有任何可识别的参数")
                if (!ed.commit()) throw IllegalStateException("写入失败（存储异常）")
                AppLog.d("glasscfg", "导入成功 " + okCount + " 项")
            } catch (t: Throwable) {
                err = t.message ?: t.javaClass.simpleName
                AppLog.d("glasscfg", "导入失败: " + err)
            }
            runOnUiThread {
                if (err == null) {
                    notifyCfgChanged()
                    Toast.makeText(this,
                        "已导入 " + okCount + " 项。"
                                + "\n液态玻璃效果需要重启「系统界面」才会生效（锁屏/桌面要重启手机/桌面）",
                        Toast.LENGTH_LONG).show()
                    // 关键：所有 TuneRow 的值都缓存在 remember 里，光写 prefs 界面不会变。
                    // recreate() 让整个 Activity 重建，重新从 prefs 读一遍 —— 这才是
                    // 用户说的「导入后 UI 要立刻生效」。
                    window.decorView.postDelayed({ try { recreate() } catch (_: Throwable) {} }, 1200)
                } else {
                    Toast.makeText(this, "导入失败：" + err, Toast.LENGTH_LONG).show()
                }
            }
        }.apply { name = "has-glass-import" }.start()
    }

    /**
     * 恢复官方原始参数：把 75 项精细调节全部写回**系统原厂值**。
     *
     * <p>注意用的是 {@link Cfg#factoryDefPct} 而不是 {@code fnDefPct} ——
     * 后者是本品的**烧录配方**（主人调好的观感），不是原厂。用户想要的是"回到原厂"，
     * 不是"回到我的配方"。
     *
     * <p>线开关不动：用户点这个通常只是想清掉参数，不想把整条线关掉。
     */
    private fun restoreFactoryParams() {
        Thread {
            var ok = false
            try {
                val ed = prefs().edit()
                for (k in Cfg.FN_KEYS) ed.putInt(k, Cfg.factoryDefPct(k))
                ok = ed.commit()
                AppLog.d("glasscfg", "恢复原厂参数 " + (if (ok) "成功" else "失败"))
            } catch (t: Throwable) {
                AppLog.d("glasscfg", "恢复原厂参数异常: " + t.javaClass.simpleName)
            }
            runOnUiThread {
                if (ok) {
                    notifyCfgChanged()
                    Toast.makeText(this,
                        "已恢复官方原始参数。重启「系统界面」后液态玻璃生效",
                        Toast.LENGTH_LONG).show()
                    window.decorView.postDelayed({ try { recreate() } catch (_: Throwable) {} }, 1200)
                } else {
                    Toast.makeText(this, "恢复失败，请检查存储空间", Toast.LENGTH_LONG).show()
                }
            }
        }.apply { name = "has-factory-restore" }.start()
    }

    /**
     * 把截图限制开关同步到系统属性 `persist.sys.has.nosecure`。
     *
     * <p>为什么必须这么做：`hookSecureFlag` 挂在 **system_server**，它读不到模块的
     * ContentProvider（SELinux），`Cfg.isOn(K_NOSECURE)` 在那里恒 false。
     * 属性和 `persist.sys.has.hottemp` 一样是全局可读的，所以走这条。
     *
     * <p>写属性需要 root；失败时**把开关拨回 false** 并如实报错 —— 不能让 UI
     * 显示"已开启"而实际没生效（那正是这个 bug 最初的样子）。
     */
    /**
     * 把「允许降级安装」同步到系统属性 `persist.sys.has.downgrade`。
     *
     * <p>`hookDowngradeInSystemServer` 挂在 system_server，读不到模块 provider
     * （SELinux），所以系统层的 isDowngradePermitted 需要靠属性判断。
     * 安装器进程那层（hookAllowDowngrade）不依赖属性，直接读 Cfg 就行。
     */
    private fun syncDowngradeProp(on: Boolean, done: (Boolean) -> Unit) {
        Thread {
            val want = if (on) "1" else "0"
            var ok = false
            try {
                val before = readProp(Cfg.PROP_DOWNGRADE)
                AppLog.d("downgrade", "目标=" + want + " 当前=" + before)
                ok = if (before == want) true
                else suExecTimeout("setprop " + Cfg.PROP_DOWNGRADE + " " + want, 30000L)
                if (ok) {
                    var now = readProp(Cfg.PROP_DOWNGRADE)
                    if (now != want) {
                        try { Thread.sleep(250L) } catch (_: Throwable) {}
                        now = readProp(Cfg.PROP_DOWNGRADE)
                    }
                    ok = (now == want)
                }
                AppLog.d("downgrade", "写后回读=" + readProp(Cfg.PROP_DOWNGRADE)
                        + " -> " + (if (ok) "成功" else "失败"))
            } catch (t: Throwable) {
                AppLog.d("downgrade", "异常: " + t.javaClass.simpleName)
            }
            runOnUiThread {
                if (!ok) {
                    Toast.makeText(this,
                        if (on) "开启失败：su 没执行成功（检查 KernelSU/Magisk 授权）"
                        else "关闭失败：su 没执行成功", Toast.LENGTH_LONG).show()
                }
                done(ok)
            }
        }.apply { name = "has-downgrade-sync" }.start()
    }

    private fun syncNosecureProp(on: Boolean, done: (Boolean) -> Unit) {
        Thread {
            val want = if (on) "1" else "0"
            var ok = false
            try {
                val before = readProp(Cfg.PROP_NOSECURE)
                AppLog.d("nosecure", "目标=" + want + " 当前=" + before)
                ok = if (before == want) {
                    true
                } else {
                    suExecTimeout("setprop " + Cfg.PROP_NOSECURE + " " + want, 30000L)
                }
                if (ok) {
                    var now = readProp(Cfg.PROP_NOSECURE)
                    if (now != want) {
                        try { Thread.sleep(250L) } catch (_: Throwable) {}
                        now = readProp(Cfg.PROP_NOSECURE)
                    }
                    ok = (now == want)
                    AppLog.d("nosecure", "写后回读=" + now + " → " + (if (ok) "成功" else "不一致"))
                } else {
                    AppLog.d("nosecure", "写入失败，回读=" + readProp(Cfg.PROP_NOSECURE))
                }
            } catch (t: Throwable) {
                AppLog.d("nosecure", "异常: " + t.javaClass.simpleName)
            }
            runOnUiThread {
                if (!ok) {
                    Toast.makeText(this,
                        if (on) "开启失败：su 没执行成功（检查 KernelSU/Magisk 授权）"
                        else "关闭失败：su 没执行成功",
                        Toast.LENGTH_LONG).show()
                }
                done(ok)
            }
        }.apply { name = "has-nosecure-sync" }.start()
    }

    /**
     * 删除 /data/cota/para/ 下三份性能相关的云端配置。
     *
     * 设计上刻意保守：
     *   · 只删这三个明确已知的文件，不做通配（`rm -rf` 太危险）
     *   · 删之前先判断存不存在，不存在就如实告诉用户"本来就干净"
     *   · 每条命令都带 `2>/dev/null`，避免 SELinux 警告污染结果
     *   · 不删其他 10 类云控（contenttransfer / compliancecontrol 等，与本功能无关）
     */
    /**
     * 清理云控残留（v3.2.33 重写判定逻辑）。
     *
     * <p>原实现有三个问题，用户实测报"清理失败：su 没执行成功"就是因为它们：
     *
     * <ol>
     *   <li><b>用 `ls` 的行数当文件数</b>：{@code startSu} 开了
     *       {@code redirectErrorStream(true)}，`ls` 对不存在文件的报错会混进 stdout，
     *       "3 个不存在"被数成"存在 3 个"。</li>
     *   <li><b>用 su 的退出码当成功判据</b>：`rm -f` 的目标本来就不存在时，
     *       退出码受 shell 解析影响，会给出 exit=1；文件根本不存在这件事
     *       被误报成"su 没授权"。</li>
     *   <li>提示语把"没东西可删"和"没权限"混为一谈，用户以为是 root 坏了。</li>
     * </ol>
     *
     * <p>现在改成：用 {@code test -e} 逐个精确列出现存文件，删完**再数一遍**，
     * 以"还剩几个"作为唯一判据。文件不存在 = 本来就没东西可清，直接说清楚。
     *
     * <p>注意：这三个路径来自 HAware 反编译（{@code /data/cota/para/cpuconfig} 等），
     * 在 MagicOS 11 / Android 17 上实测**可能压根不会生成** —— 该版本的 cota 目录
     * 是「一个策略一个子目录」的结构。所以"本来就不存在"是完全正常的状态，
     * 不是失败。
     */
    private fun purgeCotaConfigs(done: (Boolean) -> Unit) {
        Thread {
            val files = listOf(
                "/data/cota/para/cpuconfig",
                "/data/cota/para/pg_config",
                "/data/cota/para/thermal_config"
            )
            // 用 test -e 精确判定：不存在就什么都不输出，绝不会像 ls 那样
            // 把报错行也吐到同一个流里。
            val probe = "for f in " + files.joinToString(" ") +
                    "; do [ -e \"\$f\" ] && echo \"\$f\"; done"
            fun existing(): List<String> =
                (suReadOutput(probe, 6000L) ?: "")
                    .lineSequence()
                    .map { it.trim() }
                    .filter { it.startsWith("/data/cota/") }
                    .toList()

            val before = try { existing() } catch (_: Throwable) { emptyList() }
            AppLog.d("purgecota", "清理前存在 " + before.size + " 个 " + before)

            if (before.isEmpty()) {
                runOnUiThread {
                    Toast.makeText(this,
                        "云端配置本来就不存在，无需清理。\n" +
                        "（这台系统的 cota 目录是「一个策略一个子目录」结构，" +
                        "这三个旧版路径通常不会生成 —— 不是清理失败）",
                        Toast.LENGTH_LONG).show()
                }
                done(true)   // 没有残留 = 目标达成，不算失败
                return@Thread
            }

            // 删的时候不带任何重定向：真出错时错误文本会被 startSu 的
            // redirectErrorStream 收进来，便于排查
            suExecTimeout("rm -f " + before.joinToString(" "), 8000L)

            val after = try { existing() } catch (_: Throwable) { before }
            AppLog.d("purgecota", "清理后剩 " + after.size + " 个 " + after)

            runOnUiThread {
                when {
                    after.isEmpty() -> Toast.makeText(this,
                        "已清理 " + before.size + " 个云端配置文件。\n" +
                        "请立即重启手机，让 PowerGenie 回到内置默认策略。",
                        Toast.LENGTH_LONG).show()
                    after.size < before.size -> Toast.makeText(this,
                        "部分清理：已删 " + (before.size - after.size) +
                        " 个，还剩 " + after.size + " 个（可能被系统占用，重启后再试）",
                        Toast.LENGTH_LONG).show()
                    else -> Toast.makeText(this,
                        "没删掉（还剩 " + after.size + " 个）：这些文件被系统占用或只读。\n" +
                        "可用 root 终端手动执行：\nrm -f " + after.joinToString(" "),
                        Toast.LENGTH_LONG).show()
                }
            }
            done(after.isEmpty())
        }.apply { name = "has-purge-cota" }.start()
    }

    /** 通用：把文本写进「下载」目录。 */
    private fun saveTextToDownloads(fileName: String, text: String, mime: String): String? {
        return try {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(android.provider.MediaStore.Downloads.MIME_TYPE, mime)
                put(android.provider.MediaStore.Downloads.RELATIVE_PATH,
                    android.os.Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = contentResolver.insert(
                android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            val os = contentResolver.openOutputStream(uri)
            if (os == null) {
                try { contentResolver.delete(uri, null, null) } catch (_: Throwable) {}
                return null
            }
            os.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            "Download/" + fileName
        } catch (t: Throwable) {
            AppLog.d("export", "失败: " + t.javaClass.simpleName)
            null
        }
    }

    /** 写进系统「下载」目录（MediaStore，Android 10+ 免存储权限）。 */
    private fun saveReportToDownloads(report: String): String? {
        return try {
            val name = "has-diag-" + java.text.SimpleDateFormat("yyyyMMdd-HHmmss",
                java.util.Locale.US).format(java.util.Date()) + ".txt"
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(android.provider.MediaStore.Downloads.RELATIVE_PATH,
                    android.os.Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = contentResolver.insert(
                android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return null
            // openOutputStream 可能返回 null（存储满 / 被策略拦）。早先用了
            // `?.use {}`，null 时静默跳过 —— 结果 Mediastore 里留下一个**空文件**，
            // 用户拿到 0 字节的 txt，还以为是我们没写日志。
            val os = contentResolver.openOutputStream(uri)
            if (os == null) {
                try { contentResolver.delete(uri, null, null) } catch (_: Throwable) {}
                AppLog.d("export", "openOutputStream 返回 null，已删除空条目")
                return null
            }
            os.use { it.write(report.toByteArray(Charsets.UTF_8)) }
            "Download/" + name
        } catch (t: Throwable) {
            AppLog.d("export", "失败: " + t.javaClass.simpleName + " " + t.message)
            null
        }
    }

    /** 导出按钮：后台组装 → 写下载目录 → 把路径告诉用户。 */
    private fun exportDiag() {
        Toast.makeText(this, "正在收集诊断信息…", Toast.LENGTH_SHORT).show()
        Thread {
            val report = try { buildDiagReport() } catch (t: Throwable) {
                "报告生成失败: " + t
            }
            val saved = saveReportToDownloads(report)
            runOnUiThread {
                if (saved != null) {
                    Toast.makeText(this,
                        "已导出到 " + saved + "，可以直接把这个文件发给我们",
                        Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "导出失败，请检查存储空间", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    /**
     * v3.0.2 改：原来这里是 `p.waitFor()` **无超时**。
     *
     * 后台线程虽然不会 ANR，但 su 会一直阻塞着等用户点授权 —— 线程挂死、
     * `runOnUiThread` 永远等不到，用户看到的就是"点了重启没有任何反应"。
     * 统一走 [suExecTimeout]，超时按"人的操作时间"给 30 秒。
     */
    private fun suExec(cmd: String, timeoutMs: Long = 30000L): Boolean =
        suExecTimeout(cmd, timeoutMs)

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
        // v3.0.5 的高光语义迁移（旧 1=关闭 → 新 1=开启）**不在这里做**了。
        // 它被移到 Cfg.migrateHlSemantics()，由 CfgProvider.onCreate() 调用 ——
        // 因为那个方法只有用户打开本界面才会跑，而老用户可能升级后一直不打开界面，
        // 那样 hook 会读到旧语义的值，四个高光开关整个反掉。
        // 这里不再重复实现，避免两处逻辑漂移。
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
        AppLog.init(this)
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
            var page by rememberSaveable { mutableStateOf(Page.Mine) }
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
                            Page.Security -> SecurityPage { page = Page.Mine }
                            Page.About -> AboutPage()
                        }
                        if (rootDialogVisible) ScopeRestartDialog { rootDialogVisible = false }
                    }
                }
            }
        }
    }

    private enum class Page { Mine, Glass, Installer, Misc, Security, About }

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
            item { ActionCard("安全组件", "允许截屏 / 录屏、屏蔽云控下发、屏蔽反诈组件") { go(Page.Security) } }
            item {
                ActionCard("重启作用域（需ROOT）", "可多选一次重启：系统界面 / 相册 / 安装器 / 桌面 / 温控服务 / 反诈服务 / 感知服务") { requestRootThenPickScope() }
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
        "com.hihonor.awareness" -> "感知服务"
        "android" -> "系统框架"
        else -> pkg
    }

    // ================= Glass =================

    @Composable
    private fun GlassPage(back: () -> Unit) {
        val probeCtx = LocalContext.current
        LaunchedEffect(Unit) { FrameworkProbe.refresh(probeCtx, true) }
        var subPage by rememberSaveable { mutableStateOf("") }
        var openGroup by rememberSaveable { mutableStateOf("") }
        // 导入用系统文件选择器：只让用户挑 json/txt，不申请任何存储权限。
        var restoreAsk by remember { mutableStateOf(false) }
        val glassImportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
        ) { uri -> if (uri != null) importGlassConfig(uri) }
        if (restoreAsk) {
            ConfirmEnableDialog(
                title = "恢复官方原始参数",
                lines = listOf(
                    "把 75 项精细调节全部重置为系统原厂值（100 = 不改动）",
                    "线开关（总开关 / 系统 / 锁屏 / 横幅 / 桌面 / 相册）保持不变",
                    "你烧录或自己调过的观感会被清掉"
                ),
                note = "这个操作不可撤销，建议先「导出液态玻璃参数」备份一份。",
                onConfirm = {
                    restoreAsk = false
                    restoreFactoryParams()
                },
                onDismiss = { restoreAsk = false }
            )
        }
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
                    // 参数备份：换机 / 刷机 / 折腾前先把这一摊配置存一份。
                    // 复用现成的 LinkCard，不额外造卡片样式。
                    LinkCard(listOf(
                        LinkRow("导出液态玻璃参数（到 下载 目录）") { exportGlassConfig() },
                        LinkRow("导入液态玻璃参数") {
                            glassImportLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                        },
                        LinkRow("恢复官方原始参数") { restoreAsk = true }
                    ))
                }
                item { Footnote("导出的 json 只包含液态玻璃这一摊的开关和 75 项参数，不含安装器/温控设置。导入后需要重启「系统界面」生效（锁屏和桌面要重启手机/桌面）。") }
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
                        SectionLabel("精细调节（100% = 不改动 · 改完重启「桌面」生效）")
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
                        SectionLabel("精细调节（100% = 不改动 · 改完重启「系统界面」生效）")
                        // v3.0.5：这个开关原来是裸的（直接放在 Column 里，没有卡片底），
                        // 和下面的 ParamGroup 视觉上不齐。包一层 CardShell。
                        CardShell {
                            TuneSwitch("强制液态玻璃", Cfg.F_FORCE_LIQUID,
                                "有些界面系统会自己判定成磨砂（比如通知横幅、某些卡片），你没法改。打开这个开关，强制它们也走液态折射。如果哪个界面变糊或变色，关掉即可。")
                        }
                        Spacer(Modifier.height(10.dp))
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
                            TuneRow("高光角度", Cfg.F_HL_ANGLE, "光源方向，0 到 360 度。原厂 250，改它能让光从别的方向照过来。", rangeMax = 360, unit = "°")
                            HighlightSwitches()
                            TuneSwitch("锁屏控制中心展开态", Cfg.F_CC_EXPAND,
                                "强制锁屏上的控制中心按「展开」状态渲染。注意：它同时会让锁屏不再使用窗口模糊（系统里这两件事绑在一起），观感变化比较大，默认关闭。")
                        }
                        ParamGroup("多层遮罩与渐变", "multimask", openGroup, { openGroup = it }) {
                            TuneRow("多层遮罩浓度", Cfg.F_MULTI_MASK,
                                "通知卡、按钮、滑块背后其实是**好几层**遮罩叠出来的。上面那几根「底色浓度」只管其中一层，这一根同时调所有层——想整体变透或变实，用它最直接。")
                            TuneRow("动态卡渐变颜色", Cfg.F_DYN_GRAD_COLOR,
                                "动态卡片（正在播放那种）的渐变遮罩浓淡。")
                            TuneRow("动态卡渐变位置", Cfg.F_DYN_GRAD_POS,
                                "渐变的起止位置。调低范围更窄、颜色更集中；调高更分散。")
                        }
                        Footnote("三层遮罩和渐变是这次新补上的——之前这些字段系统一直在用，只是模块没接。")
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
                        SectionLabel("精细调节（100% = 不改动）")
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
                        Footnote("这一组管的是「整块锁屏壁纸」，不是上面的卡片或按钮。三个都是乘在系统原值上，100% = 完全不动。")
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
                        SectionLabel("精细调节（100% = 不改动 · 改完重启「系统界面」生效）")
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
                // 和其它卡片（LinkCard / ActionCard）统一：用 › 而不是 ASCII >，
                // 颜色也用次要色 —— 主色会让箭头抢眼，像可点的按钮。
                Text("›", fontSize = 20.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
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
     * 边缘高光那一组（v3.0.5）。
     *
     * 语义从「关闭某某高光」翻成「某某高光」（开关打开 = 有高光，出厂就是全开）。
     *
     * **联动**：全局关掉时，下面三个一起关、并置灰点不动。
     * 这样不会出现「全局关着、分项还亮着」这种自相矛盾的状态 —— 也就是用户说的
     * "不要写打架了"。
     */
    @Composable
    private fun HighlightSwitches() {
        var g by remember { mutableStateOf(prefs().getInt(Cfg.F_HL_ON, 1) != 0) }
        var cc by remember { mutableStateOf(prefs().getInt(Cfg.F_HL_CC, 1) != 0) }
        var bt by remember { mutableStateOf(prefs().getInt(Cfg.F_HL_BTN, 1) != 0) }
        var sl by remember { mutableStateOf(prefs().getInt(Cfg.F_HL_SL, 1) != 0) }

        fun put(k: String, v: Boolean) {
            prefs().edit().putInt(k, if (v) 1 else 0).commit()
        }

        /**
         * 分项变化后把全局同步成「三者之或」。
         *
         * 规则（用户定死的）：
         *   · 开任意一个分项 -> 全局必须跟着开（否则 hook 关总闸，勾了也看不到亮边）
         *   · 三个都关      -> 全局也关（否则 UI 显示"开"但画面全灭，自相矛盾）
         *
         * 所以下面每一个分项的 onChange 都是「写自己的值 -> syncGlobal()」。
         */
        fun syncGlobal() {
            val any = cc || bt || sl
            if (any != g) {
                g = any
                put(Cfg.F_HL_ON, any)
            }
        }

        HlSwitchRow("全局高光", g,
            "总闸。打开它 = 下面三个一起打开（四处亮边全有）；关掉它 = 下面三个一起关掉，一处亮边都不留。") { v ->
            g = v
            put(Cfg.F_HL_ON, v)
            // 双向联动：开全局 -> 三个分项一起开；关全局 -> 三个一起关。
            // 反过来（单独开某个分项 -> 全局自动开）在下面三行里处理。
            cc = v; bt = v; sl = v
            put(Cfg.F_HL_CC, v)
            put(Cfg.F_HL_BTN, v)
            put(Cfg.F_HL_SL, v)
        }
        HlSwitchRow("大按钮高光", cc,
            "只管工具栏上的方块大按钮。打开它时全局高光会自动跟着打开。关掉它不会影响全局——小按钮和亮度条的亮边照旧，除非你把三个分项全关了，那全局才会跟着关。") { v ->
            cc = v; put(Cfg.F_HL_CC, v); syncGlobal()
        }
        HlSwitchRow("小按钮高光", bt,
            "只管工具栏上的圆形小按钮。打开它时全局高光会自动跟着打开。关掉它不会影响全局——大按钮和亮度条的亮边照旧，除非三个分项全关。") { v ->
            bt = v; put(Cfg.F_HL_BTN, v); syncGlobal()
        }
        HlSwitchRow("亮度条高光", sl,
            "只管亮度条和音量条。打开它时全局高光会自动跟着打开。关掉它不会影响全局——按钮的亮边照旧，除非三个分项全关。") { v ->
            sl = v; put(Cfg.F_HL_SL, v); syncGlobal()
        }
    }

    /**
     * 高光用的开关行。
     *
     * **不置灰**：分项永远可点。全局关掉后分项显示为"关"，但用户随时可以打开
     * 其中任何一个 —— 打开时上层会顺带把全局也打开（见 [HighlightSwitches]）。
     * 早先想过"全局关就置灰分项"，但那样就点不动，用户永远没法单独开一类。
     */
    @Composable
    private fun HlSwitchRow(label: String, on: Boolean,
                            tip: String, onChange: (Boolean) -> Unit) {
        val hv = androidx.compose.ui.platform.LocalView.current
        Row(
            Modifier.fillMaxWidth()
                .clickable {
                    hv.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                    onChange(!on)
                }
                .padding(horizontal = 18.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, fontSize = 15.sp, color = MiuixTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f))
            InfoDot(tip)
            Spacer(Modifier.width(8.dp))
            Switch(checked = on, onCheckedChange = { onChange(it) })
        }
    }

    /**
     * 开关型精细参数。
     *
     * v3.0.4：从「点击整行 + 右侧文字」改成**真开关**。
     * 原来那一版右边显示「已关闭 / 原厂」，用户反馈两点：
     *   ① 看着像按钮不像开关，不知道点一下是开还是关；
     *   ② "原厂"看不懂。
     * 现在状态由 Switch 本身表达，不再需要文字。
     */
    @Composable
    private fun TuneSwitch(label: String, key: String, tip: String = "") {
        val hv = androidx.compose.ui.platform.LocalView.current
        var on by remember { mutableStateOf(prefs().getInt(key, Cfg.fnDefPct(key)) != 0) }
        val setOn: (Boolean) -> Unit = { v ->
            on = v
            hv.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
            prefs().edit().putInt(key, if (v) 1 else 0).commit()
        }
        Row(
            Modifier.fillMaxWidth().clickable { setOn(!on) }
                .padding(horizontal = 18.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, fontSize = 15.sp, color = MiuixTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f))
            if (tip.isNotEmpty()) {
                InfoDot(tip)
                Spacer(Modifier.width(8.dp))
            }
            Switch(checked = on, onCheckedChange = { setOn(it) })
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
    /**
     * 精细调节滑条。
     *
     * @param stepCount 吸附段数；**传 -1（默认）表示"1 单位一格"**，即无极调整。
     *        历史上这里是 19（200 范围 → 每格 10%），太粗，用户反馈"拧一下跳好多"。
     *
     * 说明：`steps` 只影响**停靠点**，不产生额外绘制 —— 所以 199 段和 19 段的
     * 渲染开销一样，不会因为变精细就卡。
     */
    private fun TuneRow(label: String, key: String, tip: String = "",
                        rangeMax: Int = 200, unit: String = "%", stepCount: Int = -1) {
        // -1 = 无极：rangeMax 段 → 每段 1 个单位
        val effSteps = if (stepCount > 0) stepCount else (rangeMax - 1).coerceAtLeast(1)
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
                    // 1% 粒度下每格都震会很吵，改成每 5% 震一次作为"刻度感"；
                    // 数值本身仍然是 1% 连续可调。
                    if (nv != pct && nv % 5 == 0) {
                        view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                    }
                    pct = nv
                },
                valueRange = 0f..rangeMax.toFloat(),
                steps = effSteps,
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

    // ================= Security =================

    /**
     * 安全组件。
     *
     * 全部是"解除系统限制"型的开关，默认关闭、开启前二次确认 —— 它们都会让系统
     * 变得"更不设防"，必须让用户自己承担这个选择。
     */
    @Composable
    private fun SecurityPage(back: () -> Unit) {
        // v3.2.33：清理云控已从「开关」改成「按钮」。
        // 它本来就不是状态（点一次执行一次），做成开关会让用户误以为
        // "开着就一直生效"，关着就以为没清理过。用局部状态控制确认框即可。
        var showPurge by remember { mutableStateOf(false) }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { BackHeader("安全组件", back) }
            item { TipText("下面每一项都会让系统变得更「不设防」。默认全部关闭，开启前会二次确认，请确认你理解它的后果。") }
            // v3.2.47：三张独立卡片，顺序按用户要求：反诈 → 云控+清理 → 截屏。
            // 清理**不能**单独成卡 —— 它和云控是同一件事的两半，拆开看不出因果关系。
            item {
                SwitchCard(listOf(
                    SwRow("屏蔽反诈组件采集与上报",
                        "同时作用于两个进程：**securityserver**（反诈服务）和 **awareness**（感知服务）。"
                                + "securityserver 侧不注册检测观察者、开关一律返回关闭、云控校验不通过；"
                                + "awareness 侧诈骗行为事件写不进存储层、连不上感知服务。"
                                + "两边都拿不到数据，自然谈不上上报。",
                        Cfg.K_NOFRAUD, true, false, confirmOnEnable = true, scopes = S_NOFRAUD,
                        confirmTitle = "屏蔽反诈组件采集与上报",
                        confirmLines = listOf(
                            "反诈检测不再注册观察者（检测不启动）",
                            "不再注册感知围栏：短信、剪贴板、扫码支付、位置、指纹、账号、安装/卸载、常用应用、通知等",
                            "云控安全校验一律返回不通过",
                            "感知服务（awareness）的诈骗行为事件写入被拦截",
                            "作用域：securityserver（反诈服务）+ awareness（感知服务）",
                            "",
                            "不受影响：查找手机、云备份、本地人脸、指纹、密钥、账号同步",
                            "会失灵：统一认证的环境判断（异地登录这类）"
                        ),
                        confirmNote = "两个作用域都要勾选。改完用「重启作用域」一次重启即可，那里也会重启感知服务。")
                ))
            }
            item {
                SwitchCard(
                    rows = listOf(
                    SwRow("屏蔽性能 / 温控策略云控下发",
                        "云端把性能/温控策略写进 /data/cota/para/ 之后，PowerGenie 既不收重载通知，"
                                + "也读不到那几个文件（对它们的存在性判断和打开操作一律按「不存在」处理），"
                                + "只会用自己出厂内置的策略。和「屏蔽高温限制」互补：那个管设备已经热了别降频，"
                                + "这个管别再推新策略。",
                        Cfg.K_NOCLOUD, true, false, confirmOnEnable = true, scopes = S_THERMAL,
                        confirmTitle = "屏蔽性能 / 温控策略云控下发",
                        confirmLines = listOf(
                            "三个下发通知（CpuCfg / PgConfig / ThermalCfgCotaUpdateReceiver）全拦下，不会触发热重载",
                            "cpuconfig / pg_config / thermal_config 这三份配置，PowerGenie 一律看不到",
                            "进程启动时读 /data/cota/para/ 的旧配置同样被挡",
                            "只影响 PowerGenie / iaware；无障碍、权限、胶囊等其它 cota 配置不受影响"
                        ),
                        confirmNote = "文件本身可能仍会被写下来（写入方是别的进程，拦不到），但 PowerGenie 读不到 —— 效果等同于没下发。想彻底清干净，用下面的「清理云端配置残留（ROOT）」。")
                    ),
                    trailing = {
                    Row(
                        Modifier.fillMaxWidth().clickable { showPurge = true }.padding(18.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("清理云端配置残留（ROOT）", fontSize = 16.sp,
                                color = MiuixTheme.colorScheme.onSurface)
                            Spacer(Modifier.height(3.dp))
                            Text("删除 /data/cota/para/ 下三份性能相关云控配置"
                                    + "（cpuconfig / pg_config / thermal_config），让 PowerGenie "
                                    + "回到内置默认策略。点一下执行一次，需要 ROOT。",
                                fontSize = 12.5.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                        Spacer(Modifier.width(12.dp))
                        Text("清理", fontSize = 14.sp, color = MiuixTheme.colorScheme.primary)
                    }
                    }
                )
            }
            item {
                SwitchCard(listOf(
                    SwRow("允许截屏 / 录屏（ROOT）",
                        "清除窗口的 FLAG_SECURE，让原本一截就黑屏、录屏一片黑的页面（银行 / 支付 / 部分阅读类 app）"
                                + "可以正常截图录屏。"
                                + "需要两样东西：① LSPosed 里勾选「系统框架」（system_server，钩子装在那里）；"
                                + "② ROOT（开关值要写成系统属性 persist.sys.has.nosecure 才能传过去）。"
                                + "注：system_server 无法向本模块自证，所以这里不显示作用域状态，"
                                + "勾没勾请到 LSPosed 模块页确认。",
                        // 不传 scopes：system_server 无法向模块 provider 自证
                        // （它 binder 到普通 app 的 provider 会挂住，MainHook 里明确排除了），
                        // 所以 FrameworkProbe 永远认为 android 作用域缺失 —— 传了只会
                        // 一直显示"作用域未生效"，哪怕功能已经正常工作。
                        Cfg.K_NOSECURE, true, false, confirmOnEnable = true,
                        confirmTitle = "允许截屏 / 录屏（需要 ROOT）",
                        confirmLines = listOf(
                            "开启后：",
                            "· 原本禁止截图的页面（银行、支付、部分阅读类 app）可以正常截图",
                            "· 录屏不再出现黑色区域",
                            "· 最近任务里的纯色卡片恢复正常预览",
                            "",
                            "需要 ROOT 的原因：",
                            "· 这个钩子挂在 system_server（系统进程）",
                            "· system_server 读不到模块的开关（SELinux 限制）",
                            "· 只能通过系统属性 persist.sys.has.nosecure 传递",
                            "· 写系统属性必须 root",
                            "",
                            "⚠ 安全提醒：",
                            "· 开启后这些敏感页面也能被别人截图，包括别人拿你手机时",
                            "· 该属性会持久保存，重启后仍然有效",
                            "· 卸载模块前请先关闭此开关，否则属性会残留",
                            "· 如果 su 授权失败，开关会自动拨回关闭状态"
                        ),
                        confirmNote = "首次开启会弹出 su 授权请求，请点「允许」。"),
                ))
            }
            item { Footnote("改完重启「系统界面」生效（这个钩子在 system_server 里，作用域要勾「android」/显示为「系统」）。") }
        }

        if (showPurge) {
            ConfirmEnableDialog(
                title = "⚠ 清理云端配置残留（危险操作）",
                lines = listOf(
                    "将要删除这三个文件（不可撤销）：",
                    "  /data/cota/para/cpuconfig      ← CPU 频率/核数策略",
                    "  /data/cota/para/pg_config      ← PowerGenie 通用策略",
                    "  /data/cota/para/thermal_config ← 温控策略",
                    "",
                    "删除后无法恢复。如果这些文件正被系统使用，",
                    "可能出现功耗/温控策略异常（异常发热、耗电变快或降温变慢）。",
                    "",
                    "执行完请立即重启手机，让 PowerGenie 回到内置默认策略。",
                    "",
                    "⚠ 绝大多数设备上这三个文件**本来就不存在** —— 那说明云端",
                    "从未给这台机器下发过性能配置，属于正常状态，不是清理失败。"
                ),
                note = "需要 ROOT。点「清理」后立即执行一次，不会留下任何状态。",
                onConfirm = {
                    showPurge = false
                    purgeCotaConfigs { /* 结果由 purgeCotaConfigs 内部 Toast 提示 */ }
                },
                onDismiss = { showPurge = false }
            )
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
                    LinkRow("加入群聊") { donateDialog = 2 },
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
            item {
                CardShell {
                    Row(
                        Modifier.fillMaxWidth().clickable { exportDiag() }.padding(18.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("导出诊断报告", fontSize = 16.sp, color = MiuixTheme.colorScheme.onSurface)
                            Spacer(Modifier.height(3.dp))
                            Text("一键收集设备信息、root 环境、su 路径探测、属性与开关状态、模块操作记录，保存到「下载」目录。遇到问题时直接把这个文件发给我们，比截图管用得多。",
                                fontSize = 12.5.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                        Spacer(Modifier.width(12.dp))
                        Text("导出", fontSize = 14.sp, color = MiuixTheme.colorScheme.primary)
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
                2 -> QrDialog("加入群聊", "img/qq_group.jpg", false, "QQ群二维码", "打开加群链接（跳转浏览器）", onJump = { openQqJoinLink() }) { donateDialog = -1 }
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
        // v3.2.36：触控区 17dp → **48dp**（Android 无障碍标准的最小触控尺寸）。
        // 原来视觉和触控都是 17dp，手指很难点中；现在外层 48dp 只负责接收点击
        // （无背景、无涟漪），内层 17dp 才是那个小圆点，视觉不变。
        Box(
            Modifier.size(48.dp).clickable { open = true },
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier.size(17.dp).clip(CircleShape)
                    .background(MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.20f)),
                contentAlignment = Alignment.Center
            ) {
                Text("!", fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
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
        /** 确认框标题（不填沿用旧的高温文案，兼容老调用点） */
        val confirmTitle: String? = null,
        /** 确认框正文，每行一条 */
        val confirmLines: List<String> = emptyList(),
        /** 确认框里"为什么需要这个开关"的补充说明 */
        val confirmNote: String? = null,
        /** optional second line under the title (live system-level state) */
        val statusText: String? = null,
        /** statusText 是否代表"正常"（决定文字颜色；"未生效"三个字里也含"已生效"，不能靠 contains 判断） */
        val statusOk: Boolean = true,
        /** 这个开关依赖哪些作用域 —— 未生效时在标题下面直接点出来 */
        val scopes: List<String> = emptyList()
    )

    @Composable
    private fun SwitchCard(
        rows: List<SwRow>,
        onAfterChange: (() -> Unit)? = null,
        // v3.2.47：卡片尾部追加一块自定义内容（不是开关，而是一个动作行）。
        // 用在「云控下发」那张卡：清理动作必须和它连在一起，不能被拆成两张卡片 ——
        // 拆开会让用户以为它们是两件不相干的事。
        trailing: (@Composable () -> Unit)? = null
    ) {
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
            if (trailing != null) {
                // 有开关行时先画分隔线；只有 trailing 的卡片不画
                if (rows.isNotEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(start = 18.dp).height(1.dp)
                        .background(MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.08f)))
                }
                trailing()
            }
        }
        confirmRow?.let { r ->
            // 之前这里硬编码 HotTempConfirmDialog —— 结果"反诈""云控"这些开关点开也是
            // 高温那套 ROOT 文案，说的完全是别的事。现在按行传入自己的标题和正文，
            // 没传的才回落到高温文案（兼容老调用点）。
            if (r.confirmTitle == null) {
                HotTempConfirmDialog(
                    onConfirm = {
                        state[r.key] = true
                        applyBinding(r.key, true, r.restart) { onAfterChange?.invoke() }
                        warnMissingScopes(ctx, r.scopes)
                        confirmRow = null
                    },
                    onDismiss = { confirmRow = null }
                )
            } else {
                ConfirmEnableDialog(
                    title = r.confirmTitle,
                    lines = r.confirmLines,
                    note = r.confirmNote,
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
    }

    /**
     * 通用"开启前二次确认"对话框。
     *
     * 标题 + 逐行正文 + 可选的补充说明，配色和 HotTempConfirmDialog 保持一致，
     * 只是文案由调用方给。
     */
    @Composable
    private fun ConfirmEnableDialog(
        title: String,
        lines: List<String>,
        note: String?,
        onConfirm: () -> Unit,
        onDismiss: () -> Unit
    ) {
        Dialog(onDismissRequest = onDismiss) {
            Surface(shape = RoundedCornerShape(24.dp), color = MiuixTheme.colorScheme.surface) {
                Column(Modifier.padding(18.dp)) {
                    Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                        color = MiuixTheme.colorScheme.onSurface)
                    Text(
                        lines.joinToString("\n") { "· " + it },
                        fontSize = 13.5.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 10.dp)
                    )
                    if (note != null) {
                        Text(note, fontSize = 13.sp, color = MiuixTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 10.dp))
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 16.dp),
                        horizontalArrangement = Arrangement.End) {
                        Text("取消", fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.clickable { onDismiss() }.padding(8.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("开启", fontSize = 14.sp, fontWeight = FontWeight.Bold,
                            color = MiuixTheme.colorScheme.primary,
                            modifier = Modifier.clickable { onConfirm() }.padding(8.dp))
                    }
                }
            }
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
            // v3.2.37：把「从没上报过」和「上报过但记录超过 3 天」分开说。
            // 后者很可能只是很久没重启进程，不是模块坏了 —— 之前一律显示
            // "作用域未生效"，用户会以为要重新配置，白折腾。
            if (miss.all { st.expired(it) }) {
                val names = miss.joinToString("、") { scopeLabel(it) }
                return "作用域状态：${names} 的记录已超过 3 天未刷新（重启作用域即可更新，不一定是故障）" to true
            }
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
                    if (bmp != null && bmp.width > 0 && bmp.height > 0) {
                        val avail = (resources.displayMetrics.widthPixels - 96 * resources.displayMetrics.density).toInt()
                        val w = (if (square) 260 else 230).let { kotlin.math.min(it, avail) }
                        // 除零保护：bmp.width 理论上不会是 0，但解码异常时可能给个
                        // 0×0 的畸形位图，除法会直接 ArithmeticException 崩掉整个对话框。
                        val h = if (square) w else {
                            val ratio = bmp.height.toFloat() / bmp.width.toFloat()
                            (w * ratio).toInt().let { hh ->
                                kotlin.math.min(hh, resources.displayMetrics.heightPixels / 2)
                            }
                        }
                        Image(bmp.asImageBitmap(), contentDescription = title,
                            modifier = Modifier.padding(top = 12.dp).size(w.dp, h.dp)
                                .clip(RoundedCornerShape(16.dp)).background(Color.White).padding(8.dp)
                                .clickable { saveQrToDownload(file, saveName) })
                    } else {
                        // 图片没加载出来（assets 里缺文件 / 解码失败）时给一句明确提示，
                        // 否则用户只看到一个空框，不知道是资源丢了还是屏幕问题。
                        Text("二维码图片缺失（assets/" + file + "）",
                            fontSize = 13.sp, color = MiuixTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 16.dp))
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
                        "本模块完全运行于本机，不获取、不上传任何隐私内容：\n\n" +
                        "· 不读取通讯录、短信、通话记录、位置、相册、麦克风、摄像头、剪贴板\n" +
                        "· 不收集设备标识（IMEI / 序列号 / Android ID / 广告 ID），不碰任何账号信息\n" +
                        "· 没有埋点统计、没有第三方 SDK、不做用户画像\n" +
                        "· 开关与参数只保存在本机应用私有目录，卸载即删除\n" +
                        "· 仅在您勾选的作用域进程内运行钩子逻辑，不碰其他应用\n" +
                        "· 只有您点击「检查更新」时才会访问 GitHub 公开接口查版本号，其余时间完全不联网\n" +
                        "· 「诊断日志」只写入本机 LSPosed 本地日志，不会外发\n" +
                        "· 「导出诊断报告」只在您点击时生成一个文本文件，存在本机「下载」目录；发不发、发给谁完全由您决定\n\n" +
                        "模块申请的唯一网络权限用于「检查更新」，除此之外没有任何后台请求。",
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
    /** 「重启系统」的二次确认。system_server 作用域的改动只有整机重启才生效。 */
    private fun askReboot() {
        android.app.AlertDialog.Builder(this)
            .setTitle("重启系统？")
            .setMessage("system_server（作用域显示为「系统」）的改动只有整机重启才会生效。"
                    + "\n\n重启后会中断当前所有操作，请确认已保存内容。")
            .setNegativeButton("取消", null)
            .setPositiveButton("立即重启") { _, _ ->
                Toast.makeText(this, "正在重启…", Toast.LENGTH_SHORT).show()
                Thread {
                    AppLog.d("scope", "重启系统")
                    suExec("reboot")
                }.start()
            }
            .show()
    }

    private fun restartScopes(targets: List<ScopeTarget>) {
        if (targets.isEmpty()) return
        // 「重启系统」必须单独走：它不能和普通进程的 killall 混在一批里执行，
        // 而且这是不可撤销的操作，必须先弹确认。
        if (targets.any { it.pkg == "__reboot__" }) {
            val others = targets.filter { it.pkg != "__reboot__" }
            if (others.isNotEmpty()) {
                // 先重启普通进程，再单独问要不要重启整机
                restartScopes(others)
                mainHandler.postDelayed({ askReboot() }, 1500L)
            } else {
                askReboot()
            }
            return
        }
        val names = targets.joinToString("、") { it.label }
        Toast.makeText(this, "正在重启：$names…", Toast.LENGTH_SHORT).show()
        Thread {
            AppLog.d("scope", "重启: " + targets.joinToString("、") { it.label })
            val ok = suExec(targets.joinToString(" ; ") { it.cmd })
            AppLog.d("scope", "结果=" + (if (ok) "成功" else "失败"))
            runOnUiThread {
                Toast.makeText(this,
                    if (ok) "已重启：$names" else "重启失败：su 没执行成功（检查 KernelSU/Magisk 里的授权）",
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
