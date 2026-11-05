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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
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

    companion object {
        /** 上次自动检查更新「成功拿到远端结果」的时间（毫秒）；失败不写，下次打开可重试。 */
        private const val KEY_UPDATE_CHECK_TS = "auto_update_check_ts"
        /** 自动检查节流：1 小时内再次打开 App 直接跳过，完全静默。 */
        private const val UPDATE_CHECK_INTERVAL_MS = 60 * 60 * 1000L
        /** 防止「检查进行中又打开一次 App」并发发起第二套请求。 */
        @Volatile private var autoChecking = false
    }

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
     * 宿主表：首页判定 / 作用域标签 / 重启列表的**唯一数据源**。
     *
     * <p>以前首页清单、重启清单、标签 when、缺失标签各写一份，改一处漏三处
     * （217 就因此把"安装器、桌面模式、相册"也算成必达，绿灯在正常手机上永远亮不了）。
     *
     * <p>`alwaysOn=true`：常驻宿主，进程活着就必须有新鲜回执才算绿。
     * `alwaysOn=false`：按需宿主，进程没起时中性展示"按需未运行"，**不阻塞绿灯**；
     * 进程在跑却没回执 / 回执过期才阻塞。`proc == null` 一律按"无法判断"，不许假绿。
     *
     * <p>`restartCmd=null`：与别的宿主同一进程，不单独重启（powergenie 就是 iaware 的进程）。
     *
     * <p>常驻/system 进程必须用 killall —— `am force-stop` 对它们**静默无效**
     * （v2.3.0 实测：对 com.hihonor.iaware 执行后 PID 一字不变，界面却提示"已重启"）。
     * 普通 app 仍用 force-stop，能连后台状态一起清。每条都跟 `|| true`：
     * 目标本来就没在跑时 killall 会返回非 0，会把整批退出码带成非 0，界面谎报"重启失败"；
     * 真失败（su 被拒）仍由 su 自身的退出码反映。
     */
    private data class HomeHost(
        val pkg: String,
        val label: String,
        val alwaysOn: Boolean,
        val restartCmd: String? = null
    )

    private val homeHosts = listOf(
        HomeHost("com.android.systemui", "系统界面", true,
            "killall com.android.systemui || true"),
        HomeHost("com.hihonor.android.launcher", "桌面", true,
            "killall com.hihonor.android.launcher || true"),
        HomeHost("com.hihonor.iaware", "温控服务", true,
            "killall com.hihonor.iaware || true"),
        // 与 iaware 同一进程，首页单独列（判定时 OR），但不单独重启。
        HomeHost("com.hihonor.powergenie", "性能服务", true),
        HomeHost("com.hihonor.securityserver", "反诈服务", true,
            "killall com.hihonor.securityserver || true"),
        // 反诈现在同时作用于感知服务本体（它自己有 fraud 逻辑和独立上报通道），
        // 重启作用域时不能漏 —— 漏了的话改了反诈开关、重启完"反诈服务"还以为生效了，
        // 实际上 awareness 那边照旧跑旧代码。
        HomeHost("com.hihonor.awareness", "感知服务", true,
            "killall com.hihonor.awareness || true"),
        // 以下三个是按需宿主：只有对应场景启动进程，不能阻塞首页绿灯。
        HomeHost("com.hihonor.photos", "相册", false,
            "am force-stop com.hihonor.photos || true"),
        HomeHost("com.android.packageinstaller", "安装器", false,
            "am force-stop com.android.packageinstaller || true"),
        HomeHost("com.hihonor.desktop.systemui", "桌面模式", false,
            "killall com.hihonor.desktop.systemui || true")
    )

    /** 重启作用域列表由宿主表派生，避免和首页清单漂移。 */
    private val scopeTargets = homeHosts.mapNotNull { h ->
        h.restartCmd?.let { ScopeTarget(h.pkg, h.label, it) }
    } + ScopeTarget("__reboot__", "系统框架（需整机重启）", "reboot")

    // switch write target shared with hooks via CfgProvider
    private fun prefs() = getSharedPreferences(Cfg.PREFS_FILE, MODE_PRIVATE)

    private fun prefsOn(key: String, def: Boolean) = prefs().getBoolean(key, def)
    /** bumped on every ON_RESUME — keyed backdrop rebuild fixes the stale
     *  grey glass pill that appears after switching back from another app */
    private val resumeTick = androidx.compose.runtime.mutableIntStateOf(0)

    override fun onResume() {
        super.onResume()
        resumeTick.intValue++
        // 回到前台也查一次版本：与 onCreate 共用同一套 1 小时节流 + 并发闸，
        // 冷启动瞬间 onCreate 已经起请求时，这里的 autoChecking 会直接挡住。
        try { autoCheckUpdate() } catch (t: Throwable) {
            AppLog.d("update", "auto check skipped: " + t.javaClass.simpleName)
        }
    }

    override fun onDestroy() {
        // Activity 销毁后，postDelayed 排进主队列的 Runnable **仍持有 this**。
        // 延迟任务体里只要碰一次 UI（recreate / 强制刷新那种），轻则泄漏一整个
        // Activity，重则 NPE 闪退。mainHandler 是实例字段，清它不会影响别的 Activity。
        try {
            mainHandler.removeCallbacksAndMessages(null)
        } catch (_: Throwable) {
        }
        super.onDestroy()
    }

    private fun applyBinding(key: String, on: Boolean, restart: Boolean, onResult: ((Boolean) -> Unit)? = null) {
        try { window.decorView.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK) } catch (_: Throwable) {}
        // 玻璃线开关走"先 epoch、再提交、再清缓存"的串行事务。
        // epoch 写不进去就整笔中止，绝不出现"开关改了、批次号没跟上"。
        if (glassSwitchKeys().contains(key)) {
            Thread {
                var fail: String? = null
                var commitOk = false
                var state = CacheCleanState.UNKNOWN
                synchronized(glassEpochLock) {
                    val ep = bumpGlassEpochProp()
                    if (ep == null) {
                        fail = "无法更新配置批次号，开关未生效（请检查 ROOT / 属性服务）"
                    } else {
                        commitOk = prefs().edit().putBoolean(key, on).commit()
                        if (!commitOk) {
                            fail = "写入失败（存储异常），开关未生效"
                        } else {
                            notifyCfgChanged()
                            state = invalidateGlassSnapshotsBlocking()
                        }
                    }
                }
                runOnUiThread {
                    if (fail != null) {
                        Toast.makeText(this, fail, Toast.LENGTH_LONG).show()
                        onResult?.invoke(false)
                    } else {
                        val head = if (on) "已开启" else "已关闭"
                        val tail = when (state) {
                            CacheCleanState.GONE ->
                                "，缓存已清理，重启系统界面后生效"
                            CacheCleanState.REMAIN ->
                                "，但缓存清理失败，建议重启系统界面"
                            CacheCleanState.UNKNOWN ->
                                "，但无法确认缓存是否清理，建议重启系统界面"
                        }
                        Toast.makeText(this, head + tail, Toast.LENGTH_LONG).show()
                        // 开关本身已经落盘；缓存问题只影响下次启动，不把 UI 拨回去。
                        onResult?.invoke(true)
                    }
                }
            }.apply { name = "has-glass-switch" }.start()
            return
        }
        prefs().edit().putBoolean(key, on).commit()
        notifyCfgChanged()
        // the thermal bypass also lives in system_server, which can never read
        // the module prefs (SELinux) — mirror this one switch into a system
        // property so BOTH layers honour it (hooks read it live per call).
        // The write needs root, so report the real outcome instead of a blind
        // "enabled" toast; if turning it OFF fails we also revert the pref so
        // the UI never claims "off" while the system layer is still bypassing.
        // 「清理云端配置残留」是独立按钮（见 SecurityPage 里的
        // NavRow + ConfirmEnableDialog），不走开关分支 —— 它代表"动作"而不是"状态"。
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
        // 「清理云端配置残留」是独立按钮（SecurityPage 里的 NavRow），
        // 不经过这里 —— 它没有"开/关"两种状态，也没有 prefs 键
        // 需要写。Cfg.K_PURGECOTA 常量保留只为兼容历史 prefs 与 provider 快照。
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
                    // 等用户切到管理器点「允许」。8 秒不够，从点开关到切过去根本来不及，
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
     * 只靠 `ProcessBuilder("su", ...)` 依赖 PATH 不够：实测有用户的
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


    /** su -c with a hard timeout; 返回退出码，超时/起不来返回 null。 */
    private fun suExecCode(cmd: String, timeoutMs: Long): Int? {
        return try {
            val p = startSu(cmd) ?: return null
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
                null
            } else {
                val code = p.exitValue()
                AppLog.d("su", "完成 exit=" + code + " 耗时=" + cost + " ms")
                code
            }
        } catch (_: Throwable) { null }
    }

    private fun suExecTimeout(cmd: String, timeoutMs: Long): Boolean =
        suExecCode(cmd, timeoutMs) == 0

    /**
     * su -c 带硬超时，返回 stdout 文本；超时 / 起不来 / 非 0 一律返回 null。
     *
     * <p>不能用 `bufferedReader().use { it.readText() }`：readText() 会一直阻塞到
     * su 进程退出，写在它后面的 waitFor 超时永远轮不到。su 停在 root 授权弹窗、
     * 被拒但没退出、或 root 管理器无响应时，调用线程就永久卡死 —— 软重启的
     * busy 锁因此再也放不开，按钮彻底点不动。
     *
     * <p>这里照 [suExecCode] 的写法：独立 drain 线程读输出，调用线程只等
     * waitFor 超时；超时先 destroy 再 destroyForcibly（su 是 native 进程，
     * 对 SIGTERM 不一定响应）。
     */
    private fun suExecCapture(cmd: String, timeoutMs: Long): String? {
        val p = startSu(cmd) ?: return null
        val out = java.io.ByteArrayOutputStream()
        val drain = Thread {
            try {
                val ins = p.inputStream
                val buf = ByteArray(1024)
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    synchronized(out) { out.write(buf, 0, n) }
                }
            } catch (_: Throwable) {}
        }
        drain.isDaemon = true
        drain.start()
        return try {
            val t0 = android.os.SystemClock.uptimeMillis()
            val finished = p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            val cost = android.os.SystemClock.uptimeMillis() - t0
            if (!finished) {
                AppLog.d("su", "超时 " + cost + " ms 已强杀 cmd=" + cmd)
                try { p.destroy() } catch (_: Throwable) {}
                try { p.destroyForcibly() } catch (_: Throwable) {}
                null
            } else if (p.exitValue() != 0) {
                AppLog.d("su", "完成 exit=" + p.exitValue() + " 耗时=" + cost + " ms cmd=" + cmd)
                null
            } else {
                // drain 线程可能还差最后一块没读完，最多等 1 秒收尾。
                drain.join(1000L)
                val bytes = synchronized(out) { out.toByteArray() }
                String(bytes, java.nio.charset.StandardCharsets.UTF_8)
            }
        } catch (_: Throwable) {
            try { p.destroyForcibly() } catch (_: Throwable) {}
            null
        }
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
                + (readProp("persist.sys.has.sysprobe") ?: "(读不到 → 系统框架状态未知)"))
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
    private data class SuRun(val exit: Int?, val out: String?)

    /**
     * 跑一段 su 命令，同时拿退出码和输出。
     *
     * 空输出不等于成功。调用方必须再检查哨兵串与退出码，
     * 见 [rootOk]。
     */
    private fun suRun(cmd: String, timeoutMs: Long): SuRun {
        return try {
            val p = startSu(cmd) ?: return SuRun(null, null)
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
                return SuRun(null, sb.toString())
            }
            reader.join(2000L)   // 等读线程收尾，最多再等 2 秒
            SuRun(p.exitValue(), sb.toString())
        } catch (t: Throwable) {
            AppLog.d("su", "读日志失败: " + t.javaClass.simpleName)
            SuRun(null, null)
        }
    }

    /** 关键 root 操作只认：退出码 0 + 看到哨兵。空输出、无哨兵、exit!=0 全判失败。 */
    private fun rootOk(r: SuRun, sentinel: String): Boolean =
        r.exit == 0 && r.out != null && r.out.contains(sentinel)

    private fun suReadOutput(cmd: String, timeoutMs: Long): String? =
        suRun(cmd, timeoutMs).out

    /** 用 su 读 LSPosed 的模块日志尾巴；读不到就明说，不假装成功。 */
    private fun readLsposedLogTail(): String {
        val out = suReadOutput("tail -n 300 /data/adb/lspd/log/*.log 2>/dev/null", 8000L)
            ?: return "(读不到 —— su 起不来或超时。这本身就是一条诊断信息)"
        return if (out.isBlank()) "(空)" else out
    }

    private fun notifyCfgChanged() {
        try { getContentResolver().notifyChange(CfgProvider.URI, null) } catch (_: Throwable) {}
    }

    /**
     * 玻璃配置的「防回写」双保险。
     *
     * <p>宿主的 cfg_snapshot 是进程级冻结的；只改 prefs/provider 不够 —— 下一次
     * 启动仍会先读旧缓存，旧进程还可能把旧快照写回磁盘。所以每次恢复 / 导入 /
     * 线开关变更都做两件事：
     * <ol>
     *   <li>推进系统属性里的 epoch（**唯一来源**）：先写属性并回读，成功才继续。
     *       新进程读缓存时发现 ep 不符，整份丢弃、回退 provider。</li>
     *   <li>用 root 删四个宿主的 CE/DE 缓存，并逐路径做 EXIST/GONE/UNKNOWN 三态复核。</li>
     * </ol>
     * 两者是"与"关系：epoch 负责逻辑上拒收旧值，删除负责物理上少读一次盘。
     * 删除失败必须由调用方原样告诉用户，不能静默。
     */

    /** 四个玻璃宿主的 CE/DE 小抄路径（DE 主路径 + CE 回退，两个都清）。 */
    private fun glassCachePaths(): List<String> = listOf(
        "/data/user_de/0/com.android.systemui/files/cfg_snapshot.txt",
        "/data/user_de/0/com.hihonor.android.launcher/files/cfg_snapshot.txt",
        "/data/user_de/0/com.hihonor.photos/files/cfg_snapshot.txt",
        "/data/user_de/0/com.hihonor.desktop.systemui/files/cfg_snapshot.txt",
        "/data/user/0/com.android.systemui/files/cfg_snapshot.txt",
        "/data/user/0/com.hihonor.android.launcher/files/cfg_snapshot.txt",
        "/data/user/0/com.hihonor.photos/files/cfg_snapshot.txt",
        "/data/user/0/com.hihonor.desktop.systemui/files/cfg_snapshot.txt"
    )

    /** 缓存清理的三态：只有 GONE 能报成功；UNKNOWN 必须说"无法确认"。 */
    private enum class CacheCleanState { GONE, REMAIN, UNKNOWN }

    /** 云控探测三态：探测失败单独处理，绝不混进"不存在/已清理"。 */
    private enum class PurgeProbe { FOUND, GONE, UNKNOWN }

    // ================= 云控清理白名单 =================
    //
    // 产品口径：
    //   · 一级：默认勾选；二级 / 三级：默认不勾；
    //   · 三级：默认不勾、红字风险提示，但用户勾了**真的会删** —— 有整批备份兜底；
    //   · 备份是硬前提：下载目录不可写 / 备份不完整 -> 整批中止，一个都不删。
    // rel 相对 /data/cota/para；isDir=false 表示只清一个明确文件。

    /** 云控清理条目。key 只用于界面/备份目录名，rel 才是真正路径。 */
    private data class CotaEntry(
        val key: String,
        val level: Int,
        val title: String,
        val rel: String,
        val isDir: Boolean
    )

    private val cotaEntries = listOf(
        // 一级（默认全选）：原二级那批。
        CotaEntry("power", 1, "power（充电 / 性能策略）", "power", true),
        CotaEntry("iaware", 1, "iaware（智能调度）", "iaware", true),
        // 二级（可以清理，默认不选）：原一级那批。
        CotaEntry("behaviorurl", 2, "behaviorurl（行为 URL 名单）", "behaviorurl", true),
        CotaEntry("hpcc_ads", 2, "hpcc/BlockAdsPolicy.json（广告策略）",
            "hpcc/BlockAdsPolicy.json", false),
        CotaEntry("onetouch", 2, "onetouch（一键触达）", "onetouch", true),
        CotaEntry("capsulenotification", 2, "capsulenotification（胶囊通知）",
            "capsulenotification", true),
        CotaEntry("contenttransfer", 2, "contenttransfer（内容流转）", "contenttransfer", true),
        CotaEntry("intelligentdiagnosis", 2, "intelligentdiagnosis（智能诊断规则）",
            "intelligentdiagnosis", true),
        // 三级：UI 红字标风险，默认不勾；勾选后照常执行（备份兜底）。
        CotaEntry("compliancecontrol", 3, "compliancecontrol（合规控制）",
            "compliancecontrol", true),
        CotaEntry("hpcc_attest", 3, "hpcc/remote_attest_policy.json（远程证明）",
            "hpcc/remote_attest_policy.json", false),
        CotaEntry("permissionmanager", 3, "permissionmanager（权限管理）",
            "permissionmanager", true),
        CotaEntry("activityintercept", 3, "activityintercept（活动拦截）",
            "activityintercept", true),
        CotaEntry("accessibilitypolicy", 3, "accessibilitypolicy（无障碍策略）",
            "accessibilitypolicy", true),
        CotaEntry("behaviorcontrolpolicy", 3, "behaviorcontrolpolicy（行为控制）",
            "behaviorcontrolpolicy", true)
    )

    private val cotaRoot = "/data/cota/para"

    /** 备份落在下载目录（用户可自己拷出来留底）；su 下用完整路径 + init ns 写入。 */
    private val cotaBackupRoot = "/storage/emulated/0/Download/has_cota_backup"

    /** 单个条目的扫描结果：路径存在与否 + 具体文件清单/大小（删除与恢复只认这份清单）。 */
    private data class CotaScan(
        val entry: CotaEntry,
        val state: PurgeProbe,
        val files: List<String>,
        val sizes: Map<String, Long> = emptyMap()
    )

    private data class CotaScanResult(
        val scans: List<CotaScan>,
        val error: String? = null,
        /** 预览时预生成的备份时间戳，确认后沿用同一个目录名。 */
        val backupTs: String? = null
    ) {
        val files: List<String> get() = scans.flatMap { it.files }
        val totalBytes: Long get() = scans.sumOf { it.sizes.values.sum() }
        val hasUnknown: Boolean get() = scans.any { it.state == PurgeProbe.UNKNOWN }
        val hasExist: Boolean get() = scans.any { it.state == PurgeProbe.FOUND }
        val allGone: Boolean get() = scans.isNotEmpty() && scans.all { it.state == PurgeProbe.GONE }
        val highRiskCount: Int get() = scans.count {
            it.state == PurgeProbe.FOUND && it.entry.level == 3
        }
    }

    /** 备份列表里的一项。 */
    private data class CotaBackup(val ts: String, val entries: Int, val files: Int)

    /** epoch 的读-改-写必须串行，否则连续两次点击会拿到同一个旧值。 */
    private val glassEpochLock = Any()

    /**
     * 唯一权威来源：`persist.sys.has.glass_epoch`。
     *
     * <p>先读当前值 → +1 → setprop → **回读一致**才返回新值。任何一步失败
     * 返回 null，调用方必须整笔中止（不改 prefs、不删缓存、不报成功）。
     * 因为没有第二份 epoch，失败不会造成"prefs 前进、属性没前进"的永久分叉。
     */
    private fun bumpGlassEpochProp(): Int? = synchronized(glassEpochLock) {
        val cur = Cfg.glassEpoch()
        if (cur == Cfg.EPOCH_UNKNOWN) {
            AppLog.d("prop", "glass_epoch 读取失败，拒绝执行（不改任何配置）")
            return null
        }
        val next = if (cur == Int.MAX_VALUE) 1 else cur + 1
        val want = next.toString()
        // root 调用同样要哨兵：空输出/exit!=0 一律失败。
        val wr = suRun("setprop ${Cfg.PROP_GLASS_EPOCH} $want && echo __NS_DONE__", 8000L)
        if (!rootOk(wr, "__NS_DONE__")) {
            AppLog.d("prop", "glass_epoch 写入失败，拒绝执行（exit=" + wr.exit +
                    " out=" + (wr.out?.trim()?.take(160) ?: "(null)") + "）")
            return null
        }
        var now = readProp(Cfg.PROP_GLASS_EPOCH)?.trim()
        if (now != want) {
            try { Thread.sleep(250L) } catch (_: Throwable) {}
            now = readProp(Cfg.PROP_GLASS_EPOCH)?.trim()
        }
        if (now != want) {
            AppLog.d("prop", "glass_epoch 回读不一致：目标=$want 实际=$now，拒绝执行")
            return null
        }
        AppLog.d("prop", "glass_epoch $cur→$next 成功")
        next
    }

    /**
     * 删缓存并做**三态复核**。
     *
     * <p>空输出不等于成功。命令必须满足：
     * <ol>
     *   <li>最后看到哨兵 `__NS_DONE__`；</li>
     *   <li>su 退出码 0；</li>
     *   <li>每条路径都有明确结果（EXIST / GONE / UNKNOWN）。</li>
     * </ol>
     * 任一条不满足 → UNKNOWN。`test -e` 在权限被拒时也会返回 false，
     * 所以用 `ls -ld` 再区分"真的不存在"和"看不见"。
     */
    private fun invalidateGlassSnapshotsBlocking(): CacheCleanState {
        val paths = glassCachePaths()
        val joined = paths.joinToString(" ")
        // ⚠ 实测：模块 app 的 su 子进程跑在应用自己的 mount namespace，
        // 看不到 /data/user_de/0/<别的应用>/；必须 nsenter 到 init 的命名空间。
        val inner = "rm -f $joined; for f in $joined; do " +
                "if [ -e \"\$f\" ]; then echo \"EXIST:\$f\"; " +
                "else if ls -ld \"\$f\" 2>&1 | grep -q \"No such file\"; " +
                "then echo \"GONE:\$f\"; else echo \"UNKNOWN:\$f\"; fi; fi; done"
        val r = suRun("nsenter -t 1 -m -- sh -c '$inner' && echo __NS_DONE__", 8000L)
        if (!rootOk(r, "__NS_DONE__")) {
            AppLog.d("cache", "invalidate glass snapshots UNKNOWN（哨兵/退出码不通过）" +
                    " exit=" + r.exit + " out=" + (r.out?.trim()?.take(200) ?: "(null)"))
            return CacheCleanState.UNKNOWN
        }
        var exist = 0
        var gone = 0
        var unknown = 0
        for (line in (r.out ?: "").lineSequence()) {
            when {
                line.startsWith("EXIST:") -> exist++
                line.startsWith("GONE:") -> gone++
                line.startsWith("UNKNOWN:") -> unknown++
            }
        }
        val state = when {
            unknown > 0 || exist + gone != paths.size -> CacheCleanState.UNKNOWN
            exist > 0 -> CacheCleanState.REMAIN
            gone == paths.size -> CacheCleanState.GONE
            else -> CacheCleanState.UNKNOWN
        }
        AppLog.d("cache", "invalidate glass snapshots " + state +
                " exist=" + exist + " gone=" + gone + " unknown=" + unknown)
        return state
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
     * 数值夹到各滑条真实上限（普通 200，高光角度 360）。导入完提示重启系统界面。
     */
    private fun importGlassConfig(uri: android.net.Uri) {
        Thread {
            var okCount = 0
            var err: String? = null
            var hadNocloudDirs = false
            var cacheState = CacheCleanState.UNKNOWN
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
                // 旧版导出里可能带 nocloudDirs（多选云控）。它不属于玻璃参数，
                // 这里不导入，但要在结果里明确告诉用户，不能静默丢掉。
                hadNocloudDirs = root.has("nocloudDirs")
                        || root.optJSONObject("nocloud") != null
                // epoch 是唯一来源。先 setprop+回读，失败就整笔中止；
                // 成功才提交 prefs；然后清缓存并三态复核。全程与开关/恢复串行。
                synchronized(glassEpochLock) {
                    if (bumpGlassEpochProp() == null) {
                        throw IllegalStateException(
                            "无法更新配置批次号（ROOT / 属性服务异常），未导入")
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
                                val v = ps.optInt(k, Cfg.fnDefPct(k))
                                    .coerceIn(0, glassParamMax(k))
                                ed.putInt(k, v)
                                okCount++
                            }
                        }
                    }
                    if (okCount == 0) throw IllegalStateException("文件里没有任何可识别的参数")
                    if (!ed.commit()) throw IllegalStateException("写入失败（存储异常）")
                    AppLog.d("glasscfg", "导入成功 " + okCount + " 项")
                    cacheState = invalidateGlassSnapshotsBlocking()
                }
            } catch (t: Throwable) {
                err = t.message ?: t.javaClass.simpleName
                AppLog.d("glasscfg", "导入失败: " + err)
            }
            runOnUiThread {
                if (err == null) {
                    notifyCfgChanged()
                    val msg = when (cacheState) {
                        CacheCleanState.GONE ->
                            "已导入 " + okCount + " 项。缓存已清理，" +
                                    "重启「系统界面」后生效（锁屏/桌面要重启手机/桌面）"
                        CacheCleanState.REMAIN ->
                            "已导入 " + okCount + " 项，但缓存清理失败，建议重启系统界面"
                        CacheCleanState.UNKNOWN ->
                            "已导入 " + okCount + " 项，但无法确认缓存是否清理，建议重启系统界面"
                    }
                    val extra = if (hadNocloudDirs) {
                        "\n旧的多选云控配置已废弃，本次只导入玻璃参数"
                    } else ""
                    Toast.makeText(this, msg + extra, Toast.LENGTH_LONG).show()
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

    /** 各滑条的真实上限：默认 200，高光角度 360；导入时按这个夹，不许拉极端值。 */
    private fun glassParamMax(key: String): Int =
        if (Cfg.F_HL_ANGLE == key) 360 else 200

    /**
     * 恢复官方原始参数（真·出厂语义）：
     *
     * <ol>
     *   <li>75 项精细调节写回 {@link Cfg#factoryDefPct}（100 = 不改动，角度 250）</li>
     *   <li>6 个玻璃线开关一并写为关闭 —— 只回参数、不关线，用户看到的仍然是
     *       "玻璃还在"，这正是上一轮误判的来源</li>
     *   <li>推进 epoch 并同步属性，然后删缓存 + 逐路径复核</li>
     * </ol>
     *
     * <p>注意用的是 {@code factoryDefPct} 而不是 {@code fnDefPct}：后者是本品的
     * **烧录配方**（主人调好的观感），不是原厂。用户点"恢复出厂"要的是原厂，
     * 不是"回到我的配方"。
     */
    private fun restoreFactoryParams() {
        Thread {
            var err: String? = null
            var state = CacheCleanState.UNKNOWN
            try {
                synchronized(glassEpochLock) {
                    if (bumpGlassEpochProp() == null) {
                        throw IllegalStateException(
                            "无法更新配置批次号（ROOT / 属性服务异常），未恢复")
                    }
                    val ed = prefs().edit()
                    for (k in Cfg.FN_KEYS) ed.putInt(k, Cfg.factoryDefPct(k))
                    // 真·出厂语义：六条玻璃线全部关闭。
                    for (k in glassSwitchKeys()) ed.putBoolean(k, false)
                    // 恢复出厂同时关闭云控屏蔽（默认关；读写两层同键）。
                    ed.putBoolean(Cfg.K_NOCLOUD, false)
                    if (!ed.commit()) throw IllegalStateException("写入失败（存储异常）")
                    AppLog.d("glasscfg", "恢复原厂参数 成功")
                    state = invalidateGlassSnapshotsBlocking()
                }
            } catch (t: Throwable) {
                err = t.message ?: t.javaClass.simpleName
                AppLog.d("glasscfg", "恢复原厂参数失败: " + err)
            }
            runOnUiThread {
                if (err == null) {
                    notifyCfgChanged()
                    val tail = when (state) {
                        CacheCleanState.GONE -> "缓存已清理，重启「系统界面」后生效"
                        CacheCleanState.REMAIN -> "但缓存清理失败，建议重启系统界面"
                        CacheCleanState.UNKNOWN -> "但无法确认缓存是否清理，建议重启系统界面"
                    }
                    Toast.makeText(this,
                        "已恢复官方原始参数，关闭全部玻璃线，并关闭云控屏蔽，$tail",
                        Toast.LENGTH_LONG).show()
                    window.decorView.postDelayed({ try { recreate() } catch (_: Throwable) {} }, 1200)
                } else {
                    Toast.makeText(this, "恢复失败：$err", Toast.LENGTH_LONG).show()
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
     * 云控清理执行层。
     *
     * <p>产品裁决：一级默认勾选；二级 / 三级默认不勾；三级红字标风险，但勾选后**照常删除**
     * （有整批备份兜底）。唯一硬约束是「备份必须成功」，否则一个都不删。
     * 流程：
     * <ol>
     *   <li>只读扫描（init mount namespace），列具体文件清单；</li>
     *   <li>整批备份到下载目录 has_cota_backup/&lt;时间戳&gt;/，
     *       下载目录不可写或回读校验不一致就**整批中止、一个都不删**；</li>
     *   <li>逐路径 rm -f 明确文件名（不用 glob），空目录单独 rmdir；</li>
     *   <li>三态复核 EXIST / GONE / UNKNOWN，失败如实报。</li>
     * </ol>
     *
     * <p>模块 app 自己起的 su 子进程跑在应用的 mount namespace 里，
     * 看不到 /data/cota 全局视图，所以扫描、备份、删除、复核全部走
     * {@code nsenter -t 1 -m}，并用 {@code __NS_DONE__} 哨兵 + 退出码判成功。
     */

    /** 只接受 /data/cota/para 下、不含 shell 元字符的路径（文件来自 find，必须校验）。 */
    private fun cotaSafePath(p: String): Boolean {
        if (!p.startsWith(cotaRoot + "/")) return false
        return p.none {
            it == '\'' || it == '"' || it == ' ' || it == '\n'
                    || it == '\r' || it == '$' || it == '`' || it == '\\'
        }
    }

    /** 只读扫描。返回每个条目的具体文件清单；删除只认这份清单。 */
    private fun scanCota(entries: List<CotaEntry>): CotaScanResult {
        val script = StringBuilder()
        for (e in entries) {
            val abs = "$cotaRoot/${e.rel}"
            if (!cotaSafePath(abs)) {
                return CotaScanResult(
                    entries.map { CotaScan(it, PurgeProbe.UNKNOWN, emptyList()) },
                    "条目路径含不允许的字符：$abs")
            }
            script.append("echo \"BEGIN:${e.key}\"; ")
            if (e.isDir) {
                script.append("if [ -d \"$abs\" ]; then echo \"DIR:${e.key}\"; ")
                script.append("find \"$abs\" -type f 2>/dev/null | while read f; do " +
                        "echo \"FILE:${e.key}|\$f|\$(wc -c < \"\$f\")\"; done; ")
                script.append("elif ls -ld \"$abs\" 2>&1 | grep -q \"No such file\"; " +
                        "then echo \"GONE:${e.key}\"; ")
                script.append("else echo \"UNKNOWN:${e.key}\"; fi; ")
            } else {
                script.append("if [ -f \"$abs\" ]; then " +
                        "echo \"FILE:${e.key}|$abs|\$(wc -c < \"$abs\")\"; ")
                script.append("elif ls -ld \"$abs\" 2>&1 | grep -q \"No such file\"; " +
                        "then echo \"GONE:${e.key}\"; ")
                script.append("else echo \"UNKNOWN:${e.key}\"; fi; ")
            }
        }
        val r = suRun("nsenter -t 1 -m -- sh -c '$script' && echo __NS_DONE__", 15000L)
        if (!rootOk(r, "__NS_DONE__")) {
            AppLog.d("purgecota", "扫描失败：exit=" + r.exit +
                    " out=" + (r.out?.trim()?.take(300) ?: "(null)"))
            return CotaScanResult(
                entries.map { CotaScan(it, PurgeProbe.UNKNOWN, emptyList()) },
                "扫描命令没有正常返回（exit=${r.exit}）")
        }
        val dirs = HashSet<String>()
        val gone = HashSet<String>()
        val unknown = HashSet<String>()
        val files = HashMap<String, MutableList<String>>()
        val sizes = HashMap<String, MutableMap<String, Long>>()
        for (line in (r.out ?: "").lineSequence()) {
            when {
                line.startsWith("DIR:") -> dirs.add(line.substring(4).trim())
                line.startsWith("FILE:") -> {
                    val parts = line.substring(5).split('|')
                    if (parts.size >= 3) {
                        val k = parts[0]
                        val p = parts[1]
                        val sz = parts[2].trim().toLongOrNull() ?: -1L
                        if (p.isNotEmpty() && sz >= 0) {
                            files.getOrPut(k) { mutableListOf() }.add(p)
                            sizes.getOrPut(k) { mutableMapOf() }[p] = sz
                        }
                    }
                }
                line.startsWith("GONE:") -> gone.add(line.substring(5).trim())
                line.startsWith("UNKNOWN:") -> unknown.add(line.substring(8).trim())
            }
        }
        val scans = entries.map { e ->
            val f = files[e.key].orEmpty().filter { cotaSafePath(it) }
            val sz = sizes[e.key].orEmpty().filterKeys { cotaSafePath(it) }
            val state = when {
                unknown.contains(e.key) -> PurgeProbe.UNKNOWN
                f.isNotEmpty() || dirs.contains(e.key) -> PurgeProbe.FOUND
                gone.contains(e.key) -> PurgeProbe.GONE
                else -> PurgeProbe.UNKNOWN
            }
            CotaScan(e, state, f, sz)
        }
        AppLog.d("purgecota", "扫描结果 " + scans.joinToString {
            it.entry.key + "=" + it.state + "(" + it.files.size + ")"
        })
        return CotaScanResult(scans)
    }

    /**
     * 整批备份到下载目录。返回备份目录；null = 失败（调用方必须放弃删除）。
     *
     * <p>路径固定 /storage/emulated/0/Download/has_cota_backup/&lt;ts&gt;/：
     * 下载目录不可写就如实失败，**绝不换地方偷偷存**。
     * 每个备份目录保留原始子目录结构，manifest.txt 逐文件记录
     * 「相对原路径 / 原绝对路径 / 备份内路径 / 字节数」，另有一行 E 记录条目。
     * 备份后逐文件回读大小并累计文件数/总大小，必须与源完全一致，否则整批中止。
     */
    private fun backupCota(scans: List<CotaScan>, ts: String): String? {
        val dir = "$cotaBackupRoot/$ts"
        var expectN = 0
        var expectS = 0L
        val sb = StringBuilder()
        sb.append("set -e; B=\"$dir\"; mkdir -p \"\$B\"; : > \"\$B/manifest.txt\"; ")
        sb.append("echo \"# has_cota_backup $ts\" >> \"\$B/manifest.txt\"; ")
        sb.append("N=0; S=0; ")
        for (s in scans) {
            if (s.state != PurgeProbe.FOUND) continue
            val e = s.entry
            val abs = "$cotaRoot/${e.rel}"
            if (!cotaSafePath(abs)) continue
            val kind = if (e.isDir) "dir" else "file"
            val total = s.sizes.values.sum()
            sb.append("mkdir -p \"\$B/${e.key}\"; ")
            sb.append("echo \"E|${e.key}|$abs|$kind|${s.files.size}|$total\" " +
                    ">> \"\$B/manifest.txt\"; ")
            for (f in s.files) {
                if (!cotaSafePath(f)) continue
                val size = s.sizes[f] ?: continue
                val rel = f.removePrefix("$cotaRoot/")
                val bpath = if (e.isDir) rel.removePrefix(e.rel + "/")
                else f.substringAfterLast('/')
                if (bpath.isEmpty() || bpath.contains('|')) continue
                if (bpath.contains('/')) {
                    sb.append("mkdir -p \"\$B/${e.key}/${bpath.substringBeforeLast('/')}\"; ")
                }
                sb.append("cp -a \"$f\" \"\$B/${e.key}/$bpath\"; ")
                sb.append("sz1=\$(wc -c < \"$f\"); sz2=\$(wc -c < \"\$B/${e.key}/$bpath\"); ")
                sb.append("[ \"\$sz1\" = \"\$sz2\" ] || exit 9; ")
                sb.append("echo \"F|$rel|$f|${e.key}/$bpath|\$sz2\" >> \"\$B/manifest.txt\"; ")
                sb.append("N=\$((N+1)); S=\$((S+sz2)); ")
                expectN++
                expectS += size
            }
        }
        sb.append("echo \"COUNTS:\$N:\$S\"; echo __BAK_OK__")
        val r = suRun("nsenter -t 1 -m -- sh -c '$sb' && echo __NS_DONE__", 45000L)
        val out = r.out ?: ""
        if (!rootOk(r, "__NS_DONE__") || !out.contains("__BAK_OK__") ||
            !out.contains("COUNTS:$expectN:$expectS")) {
            AppLog.d("purgecota", "备份失败：exit=" + r.exit +
                    " expect=$expectN/$expectS out=" + out.trim().take(400))
            return null
        }
        AppLog.d("purgecota", "备份完成：$dir（$expectN 个文件 / $expectS 字节）")
        return dir
    }

    /** 执行清理：硬校验 → 扫描 → 备份 → 逐文件删除 → 三态复核。 */
    private fun purgeCotaEntries(entries: List<CotaEntry>, ts: String, done: (Boolean) -> Unit) {
        Thread {
            // 0) 只允许白名单条目；三级高风险也允许（有整批备份兜底）。
            var rejected: String? = null
            for (e in entries) {
                if (cotaEntries.none { it.key == e.key && it.rel == e.rel }) {
                    rejected = e.rel + "（不在清理白名单）"
                    break
                }
            }
            if (rejected != null) {
                AppLog.d("purgecota", "拒绝删除：$rejected 不在白名单，未做任何改动")
                runOnUiThread {
                    Toast.makeText(this,
                        "拒绝删除：$rejected 不在清理白名单，未做任何改动。",
                        Toast.LENGTH_LONG).show()
                }
                done(false)
                return@Thread
            }

            val before = try { scanCota(entries) } catch (t: Throwable) {
                CotaScanResult(entries.map { CotaScan(it, PurgeProbe.UNKNOWN, emptyList()) },
                    t.javaClass.simpleName)
            }
            if (before.error != null || before.hasUnknown) {
                runOnUiThread {
                    Toast.makeText(this,
                        "无法确认云控文件状态（ROOT / nsenter 探测失败），未执行删除。\n" +
                        (before.error ?: "有路径在当前命名空间看不到"),
                        Toast.LENGTH_LONG).show()
                }
                done(false)
                return@Thread
            }
            if (before.allGone) {
                runOnUiThread {
                    Toast.makeText(this, "所选目录本来就没有可清理的文件。",
                        Toast.LENGTH_LONG).show()
                }
                done(false)
                return@Thread
            }

            // 1) 备份是硬前提；下载目录不可写 / 回读校验不一致 -> 整批中止。
            val backupDir = try { backupCota(before.scans, ts) } catch (_: Throwable) { null }
            if (backupDir == null) {
                runOnUiThread {
                    Toast.makeText(this,
                        "备份失败，停止清理（未改动任何文件）。\n" +
                        "请确认下载目录可写、存储空间充足后重试。\n" +
                        "备份位置：$cotaBackupRoot/$ts/",
                        Toast.LENGTH_LONG).show()
                }
                done(false)
                return@Thread
            }

            // 2) 逐路径删明确文件（不用 glob），空目录单独 rmdir。
            val sb = StringBuilder()
            for (s in before.scans) {
                if (s.state != PurgeProbe.FOUND) continue
                for (f in s.files) {
                    if (cotaSafePath(f)) sb.append("rm -f -- \"$f\"; ")
                }
                if (s.entry.isDir) {
                    val abs = "$cotaRoot/${s.entry.rel}"
                    if (cotaSafePath(abs)) {
                        sb.append("find \"$abs\" -depth -type d -exec rmdir {} \\; 2>/dev/null; ")
                    }
                }
            }
            sb.append("true")
            val rm = suRun("nsenter -t 1 -m -- sh -c '$sb' && echo __NS_DONE__", 20000L)
            AppLog.d("purgecota", "删除 " +
                    (if (rootOk(rm, "__NS_DONE__")) "ok" else "失败") +
                    " exit=" + rm.exit +
                    " out=" + (rm.out?.trim()?.take(300) ?: "(null)"))

            // 3) 三态复核。
            val after = try { scanCota(entries) } catch (t: Throwable) {
                CotaScanResult(entries.map { CotaScan(it, PurgeProbe.UNKNOWN, emptyList()) },
                    t.javaClass.simpleName)
            }
            val allGone = after.scans.isNotEmpty() &&
                    after.scans.all { it.state == PurgeProbe.GONE }
            val left = after.scans.map { it.files.size }.sum()
            AppLog.d("purgecota", "清理后 " + after.scans.joinToString {
                it.entry.key + "=" + it.state + "(" + it.files.size + ")"
            })
            runOnUiThread {
                when {
                    after.hasUnknown -> Toast.makeText(this,
                        "删除命令已执行，但无法确认结果（探测失败）。备份：$backupDir",
                        Toast.LENGTH_LONG).show()
                    allGone -> Toast.makeText(this,
                        "已清理 ${before.files.size} 个文件（${entries.size} 个条目）。\n" +
                        "备份：$backupDir\n请重启手机，让相关服务回到内置策略。",
                        Toast.LENGTH_LONG).show()
                    else -> Toast.makeText(this,
                        "部分清理：还剩 $left 个文件（" +
                        after.scans.filter { it.state == PurgeProbe.FOUND }
                            .joinToString { it.entry.key } +
                        " 仍为 EXIST，可能被系统占用）。\n备份：$backupDir",
                        Toast.LENGTH_LONG).show()
                }
            }
            done(allGone)
        }.apply { name = "has-purge-cota" }.start()
    }

    /** 列出下载目录下的备份（时间倒序）。 */
    private fun listCotaBackups(): List<CotaBackup> {
        val script = "for d in $cotaBackupRoot/*/; do " +
                "[ -f \"\$d/manifest.txt\" ] || continue; " +
                "e=\$(grep -c \"^E|\" \"\$d/manifest.txt\" 2>/dev/null); " +
                "f=\$(grep -c \"^F|\" \"\$d/manifest.txt\" 2>/dev/null); " +
                "echo \"BAK:\$(basename \"\$d\"):\$e:\$f\"; " +
                "done; true"
        val r = suRun("nsenter -t 1 -m -- sh -c '$script' && echo __NS_DONE__", 10000L)
        if (!rootOk(r, "__NS_DONE__")) {
            AppLog.d("purgecota", "列出备份失败：exit=" + r.exit +
                    " out=" + (r.out?.trim()?.take(200) ?: "(null)"))
            return emptyList()
        }
        val out = mutableListOf<CotaBackup>()
        for (line in (r.out ?: "").lineSequence()) {
            if (!line.startsWith("BAK:")) continue
            val parts = line.substring(4).split(":")
            if (parts.size < 3) continue
            val ts = parts[0].trim()
            val entries = parts[1].trim().toIntOrNull() ?: continue
            val files = parts[2].trim().toIntOrNull() ?: continue
            out.add(CotaBackup(ts, entries, files))
        }
        return out.sortedByDescending { it.ts }
    }

    /**
     * 从下载目录的备份整批恢复。
     *
     * <p>manifest.txt 两种行：
     * <pre>
     *   E|key|abs|dir|file|n|bytes
     *   F|rel|abs|bpath|bytes
     * </pre>
     * 恢复前校验所有路径仍在云控白名单内；恢复时逐文件 cp 并回读大小；
     * 恢复后重新扫描做 EXIST / GONE / UNKNOWN 三态复核 + 文件大小比对。
     */
    private fun restoreCotaBackup(ts: String, done: (Boolean) -> Unit) {
        Thread {
            if (!ts.matches(Regex("\\d{8}-\\d{6}-\\d{3}"))) {
                runOnUiThread {
                    Toast.makeText(this, "备份名不合法：$ts", Toast.LENGTH_LONG).show()
                }
                done(false)
                return@Thread
            }
            val dir = "$cotaBackupRoot/$ts"
            val mf = suRun("nsenter -t 1 -m -- sh -c 'cat \"$dir/manifest.txt\"'" +
                    " && echo __NS_DONE__", 8000L)
            if (!rootOk(mf, "__NS_DONE__")) {
                runOnUiThread {
                    Toast.makeText(this, "备份清单读取失败（备份可能不完整），未恢复。",
                        Toast.LENGTH_LONG).show()
                }
                done(false)
                return@Thread
            }
            val rows = (mf.out ?: "").lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && it.contains('|') }
                .map { it.split('|') }
                .toList()
            val entryRows = rows.filter { it.size >= 6 && it[0] == "E" }
            val fileRows = rows.filter { it.size >= 5 && it[0] == "F" }
            fun known(p: String): Boolean = cotaEntries.any { e ->
                val base = "$cotaRoot/${e.rel}"
                p == base || p.startsWith("$base/")
            }
            val bad = entryRows.firstOrNull { it[2].isNotEmpty() && !known(it[2]) }
                ?: fileRows.firstOrNull { it[1].isNotEmpty() && !known("$cotaRoot/${it[1]}") }
                ?: fileRows.firstOrNull { it[2].isNotEmpty() && !known(it[2]) }
            if (bad != null) {
                AppLog.d("purgecota", "恢复拒绝：清单含非白名单路径 $bad")
                runOnUiThread {
                    Toast.makeText(this,
                        "备份清单包含非白名单路径，已拒绝恢复：${bad.joinToString("|")}",
                        Toast.LENGTH_LONG).show()
                }
                done(false)
                return@Thread
            }
            val keys = entryRows.map { it[1] }.distinct()
            val expected = fileRows.mapNotNull { row ->
                val rel = row[1]
                val size = row[4].trim().toLongOrNull() ?: return@mapNotNull null
                "$cotaRoot/$rel" to size
            }.toMap()
            val script = "set -e; B=\"$dir\"; " +
                    "while IFS=\"|\" read -r tag key abs kind n b; do " +
                    "if [ \"\$tag\" = \"E\" ] && [ \"\$kind\" = \"dir\" ]; then " +
                    "mkdir -p \"\$abs\"; fi; " +
                    "done < \"\$B/manifest.txt\"; " +
                    "while IFS=\"|\" read -r tag rel abs bpath sz; do " +
                    "if [ \"\$tag\" = \"F\" ]; then " +
                    "mkdir -p \"\$(dirname \"\$abs\")\"; " +
                    "cp -a \"\$B/\$bpath\" \"\$abs\"; " +
                    "s1=\$(wc -c < \"\$B/\$bpath\"); s2=\$(wc -c < \"\$abs\"); " +
                    "[ \"\$s1\" = \"\$s2\" ] || exit 8; fi; " +
                    "done < \"\$B/manifest.txt\"; echo __RESTORE_OK__"
            val r = suRun("nsenter -t 1 -m -- sh -c '$script' && echo __NS_DONE__", 45000L)
            if (!rootOk(r, "__NS_DONE__") || !(r.out ?: "").contains("__RESTORE_OK__")) {
                AppLog.d("purgecota", "恢复失败：exit=" + r.exit +
                        " out=" + (r.out?.trim()?.take(300) ?: "(null)"))
                runOnUiThread {
                    Toast.makeText(this,
                        "恢复失败（cp / 大小校验未通过），请检查下载目录与存储空间。",
                        Toast.LENGTH_LONG).show()
                }
                done(false)
                return@Thread
            }
            val restored = cotaEntries.filter { e -> keys.contains(e.key) }
            val after = try { scanCota(restored) } catch (t: Throwable) {
                CotaScanResult(restored.map { CotaScan(it, PurgeProbe.UNKNOWN, emptyList()) },
                    t.javaClass.simpleName)
            }
            val got = after.scans.flatMap { s -> s.sizes.entries }
                .associate { it.key to it.value }
            val mismatch = expected.entries.firstOrNull { (abs, size) -> got[abs] != size }
            val allFound = after.scans.isNotEmpty() &&
                    after.scans.all { it.state == PurgeProbe.FOUND }
            val okAll = allFound && mismatch == null && !after.hasUnknown
            AppLog.d("purgecota", "恢复后 " + after.scans.joinToString {
                it.entry.key + "=" + it.state + "(" + it.files.size + ")"
            } + " mismatch=" + (mismatch?.key ?: "none"))
            runOnUiThread {
                when {
                    after.hasUnknown -> Toast.makeText(this,
                        "恢复命令已执行，但有路径无法确认（UNKNOWN）。备份：$dir",
                        Toast.LENGTH_LONG).show()
                    okAll -> Toast.makeText(this,
                        "已从备份 $ts 整批恢复 ${restored.size} 个条目，" +
                        "复核 EXIST 且文件大小一致。\n请重启手机生效。",
                        Toast.LENGTH_LONG).show()
                    mismatch != null -> Toast.makeText(this,
                        "恢复不完整：${mismatch.key} 期望 ${mismatch.value} 字节，" +
                        "实际 ${got[mismatch.key] ?: "不存在"}。备份：$dir",
                        Toast.LENGTH_LONG).show()
                    else -> Toast.makeText(this,
                        "恢复后复核未全部通过（" +
                        after.scans.joinToString { it.entry.key + "=" + it.state } +
                        "）。备份：$dir", Toast.LENGTH_LONG).show()
                }
            }
            done(okAll)
        }.apply { name = "has-cota-restore" }.start()
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
     * 所有 su 命令都走带超时的实现，不能直接 `p.waitFor()`。
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
        // 通用孤儿键清理。
        // 精细调节（fn_*）和粗调（tune_*）的键不能手写进 stale 列表 ——
        // 每删一个参数就要记得补一条，漏一次，旧值就留在 prefs 里静默生效
        // （hook 里已经不信这个键了，但用户之前存的值还在，将来哪天同名键复活就会"诈尸"）。
        // 按白名单统一扫：凡是以 fn_/tune_ 开头、又不在当前定义里的键，
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
        migratePrefs()
        // 旧键 nocloud_nowrite 已并入 nocloud（读写两层同键）：取或后删除旧键。
        prefs().let { p ->
            if (p.contains("nocloud_nowrite")) {
                val ed = p.edit()
                if (p.getBoolean("nocloud_nowrite", false)) ed.putBoolean(Cfg.K_NOCLOUD, true)
                ed.remove("nocloud_nowrite")
                if (ed.commit()) notifyCfgChanged()
            }
        }
        // keep the system-property bridge in sync with the stored switch
        // (first run after install, or after the pref was changed elsewhere)
        try { syncHottempProp(prefs().getBoolean(Cfg.K_HOTTEMP, false)) } catch (_: Throwable) {}
        // 降级属性在启动时对齐（首次安装 / pm clear / 属性被外部改过）。
        // 缺省 true：没有这个键（新装、pm clear）一律写 1；老用户 prefs 里显式
        // 写了 false 的保持 0，不覆盖人家的选择。
        // 写失败时 syncDowngradeProp 会弹失败提示，不假装成功。
        try {
            val desired = prefs().getBoolean(Cfg.K_DOWNGRADE, true)
            syncDowngradeProp(desired) { ok ->
                AppLog.d("downgrade", "onCreate 同步 " + (if (ok) "成功" else "失败"))
            }
        } catch (t: Throwable) {
            AppLog.d("downgrade", "onCreate 同步异常: " + t.javaClass.simpleName)
        }
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
        // 每次打开药师读一遍回执，决定首页那行「已激活 / 未激活」。
        // 同步执行、只跑一次，不轮询、不留后台任务。
        try { ActivationProbe.probe() } catch (_: Throwable) {}
        setContent { HasApp() }
        // 打开 App 自动检查更新：后台请求、1 小时节流、只有发现新版本才提示。
        try { autoCheckUpdate() } catch (t: Throwable) { AppLog.d("update", "auto check skipped: " + t.javaClass.simpleName) }
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
                        if (rebootChoiceVisible) RebootChoiceDialog { rebootChoiceVisible = false }
                    }
                }
            }
        }
    }

    private enum class Page { Mine, Glass, Installer, Misc, Security, About }

    // ================= Mine (home) =================

    @Composable
    private fun MinePage(go: (Page) -> Unit) {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp)) {
            item { PageTitle("荣耀高级设置") }
            item { HomeSubtitle() }
            item { ActionCard("液态玻璃", "系统 / 锁屏 / 相册三线玻璃效果与总开关") { go(Page.Glass) } }
            item { ActionCard("安装器净化", "跳过安全检测、禁止联网、信息卡与降级安装") { go(Page.Installer) } }
            item { ActionCard("系统综合", "通知音量、高温限制与系统级行为的总开关集合") { go(Page.Misc) } }
            item { ActionCard("安全组件", "允许截屏 / 录屏、屏蔽云控下发、屏蔽反诈组件") { go(Page.Security) } }
            item {
                ActionCard("重启作用域（需ROOT）",
                    "可多选一次重启：" + scopeTargets.filter { it.pkg != "__reboot__" }
                        .joinToString(" / ") { it.label }) { requestRootThenPickScope() }
            }
        }
    }

    /**
     * 首页标题下那行：原来的版本说明 + 激活状态点。
     *
     * <p>三态（见 {@link ActivationProbe}）：绿 = 读到回执（或 XposedBridge 可加载）；
     * 红 = 读取成功但一条回执都没有；黄 = 读取本身出错，无法确认（不猜红）。
     */
    @Composable
    private fun HomeSubtitle() {
        // 高饱和三色，浅色/深色共用同一套。
        val (label, dot) = when (ActivationProbe.state) {
            Activation.ACTIVE -> "已激活" to Color(0xFF00C853)
            Activation.INACTIVE -> "未激活" to Color(0xFFFF1744)
            else -> "无法确认" to Color(0xFFFFD600)
        }
        Row(Modifier.padding(start = 8.dp, top = 4.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("基于MagicOS11开发", fontSize = 14.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Spacer(Modifier.width(12.dp))
            Box(
                Modifier.size(8.dp)
                    .clip(CircleShape)
                    .background(dot)
                    .drawBehind {
                        // 环画在圆内（半径减半个环宽），不用 border：它居中描边，
                        // 外侧会被上面的 clip 裁掉一半。实色 #808080 不随 Monet 调色板漂移。
                        val w = 1.dp.toPx()
                        drawCircle(
                            color = Color(0xFF808080),
                            radius = size.minDimension / 2 - w / 2,
                            style = Stroke(width = w)
                        )
                    }
            )
            Spacer(Modifier.width(6.dp))
            Text(label, fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
    }

    // ================= Glass =================

    @Composable
    private fun GlassPage(back: () -> Unit) {
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
                    "会同时关闭全部玻璃线（总开关 / 系统 / 锁屏 / 横幅 / 桌面 / 相册），玻璃效果完全停止",
                    "会同时关闭云控屏蔽（K_NOCLOUD，默认关）",
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
                        SwRow("总开关", "液态玻璃总闸，含 HIGH 强制渲染、LIQUID 材质判定等系统级底层，关闭后全部回退原厂", Cfg.K_GLASS, true)
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
                        SwRow("相册液态玻璃", "相册照片页工具栏的玻璃效果", Cfg.K_GALLERY, true)
                    ))
                }
                item { Footnote("开关改动后需重启系统界面（或重启手机）生效。") }
            } else if (subPage == "desk") {
                item { BackHeader("桌面液态玻璃", { subPage = "" }) }
                item {
                    SwitchCard(listOf(
                        SwRow("桌面液态玻璃", "桌面卡片与文件夹的玻璃效果", Cfg.K_DESK, true)
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
                        SwRow("系统液态玻璃", "状态栏通知与控制中心的观感", Cfg.K_SYSTEM, true)
                    ))
                }
                item {
                    Column {
                        SectionLabel("精细调节（100% = 不改动 · 改完重启「系统界面」生效）")
                        // 这个开关要包一层 CardShell，和下面的 ParamGroup 视觉对齐。
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
                        SwRow("锁屏液态玻璃", "锁屏按钮与通知卡片的玻璃效果", Cfg.K_KEYGUARD, true)
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
                        SwRow("通知横幅液态玻璃", "来通知时顶部弹出的横幅玻璃效果", Cfg.K_BANNER, true)
                    ))
                }
                item {
                    // 222：与上面的玻璃总闸相互独立 —— 只读横幅实际渲染出来的背景亮度，
                    // 深底自动亮字、浅底自动暗字（含单色图标）。关闭即完全回原厂。
                    SwitchCard(listOf(
                        SwRow("通知横幅文字自适应反色",
                            "按横幅实际背景自动用亮字或暗字（含图标）；关闭后完全恢复原厂配色",
                            Cfg.K_HUN_INVERT, true)
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
     * 状态用真开关（Switch）表达，不用「点击整行 + 右侧文字」：
     * 文字版看着像按钮不像开关，用户不知道点一下是开还是关；"原厂"也不好懂。
     */
    @Composable
    private fun TuneSwitch(label: String, key: String, tip: String = "") {
        val hv = androidx.compose.ui.platform.LocalView.current
        var on by remember { mutableStateOf(prefs().getInt(key, Cfg.fnDefPct(key)) != 0) }
        val setOn: (Boolean) -> Unit = { v ->
            on = v
            hv.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
            prefs().edit().putInt(key, if (v) 1 else 0).commit()
            notifyCfgChanged()
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
                onValueChangeFinished = {
                    prefs().edit().putInt(key, pct).commit()
                    notifyCfgChanged()
                    // 不在这里删 cfg_snapshot。删缓存只对「下一次进程启动」
                    // 有效，对当前正在渲染的进程没有作用；而删完若用户直接重启手机，
                    // 开机首帧反而无缓存可读，会把玻璃失效请回来。
                },
                modifier = Modifier.fillMaxWidth()
            )
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

    // ================= Security =================

    /**
     * 安全组件。
     *
     * 全部是"解除系统限制"型的开关，默认关闭、开启前二次确认 —— 它们都会让系统
     * 变得"更不设防"，必须让用户自己承担这个选择。
     */
    @Composable
    private fun SecurityPage(back: () -> Unit) {
        // 清理是独立动作：按目录多选 -> 扫描列清单 -> 备份后删除，并提供恢复入口。
        var showPurgePicker by remember { mutableStateOf(false) }
        // 默认勾选只在数据层定：一级全部默认勾选；二级、三级默认不勾。
        // 用户点进来看到的就是已勾状态，不是渲染时假装勾上。
        var purgeSelected by remember {
            mutableStateOf(cotaEntries.filter { it.level == 1 }.map { it.key }.toSet())
        }
        var purgePreview by remember { mutableStateOf<CotaScanResult?>(null) }
        var backups by remember { mutableStateOf<List<CotaBackup>?>(null) }
        var showBackupPicker by remember { mutableStateOf(false) }
        var restoreTs by remember { mutableStateOf<String?>(null) }

        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { BackHeader("安全组件", back) }
            item { TipText("下面每一项都会让系统变得更「不设防」。默认全部关闭，开启前会二次确认，请确认你理解它的后果。") }
            // 三张独立卡片，顺序按用户要求：反诈 → 云控+清理 → 截屏。
            // 清理**不能**单独成卡 —— 它和云控是同一件事的两半，拆开看不出因果关系。
            item {
                SwitchCard(listOf(
                    SwRow("屏蔽反诈组件采集与上报",
                        "同时作用于两个进程：**securityserver**（反诈服务）和 **awareness**（感知服务）。"
                                + "securityserver 侧不注册检测观察者、开关一律返回关闭、云控校验不通过；"
                                + "awareness 侧诈骗行为事件写不进存储层、连不上感知服务。"
                                + "两边都拿不到数据，自然谈不上上报。",
                        Cfg.K_NOFRAUD, true, false, confirmOnEnable = true,
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
                    SwRow("屏蔽云控下发（双重保险）",
                        "一个开关管两层：① 三份云控（CpuCfg / PgConfig / ThermalCfg）的 Receiver 不再触发，legacy 文件按「不存在」处理；② PowerGenie 进程里所有云控的下发权限禁止（写 / 追加 / 删除 / 改名都返回成功但不产生真实文件）。关闭即刻放行，不需要重启。",
                        Cfg.K_NOCLOUD, true, false, confirmOnEnable = true,
                        confirmTitle = "屏蔽云控下发（双重保险）",
                        confirmLines = listOf(
                            "CpuCfgCotaUpdateReceiver / PgConfigCotaUpdateReceiver / ThermalCfgCotaUpdateReceiver 三个下发入口全拦",
                            "cpuconfig / pg_config / thermal_config 三份 legacy 文件按「不存在」处理（exists / isFile / length / list 等全部返回不存在）",
                            "只作用于 CPU 与温控策略；无障碍、权限、胶囊等其它 cota 配置不受影响",
                            "云控策略文件写入改走 /dev/null，调用方看到的是「写好了」",
                            "删除 / 改名返回成功，但原文件原样保留",
                            "只作用于本模块已注入的 PowerGenie 进程，不影响系统其它进程"
                        ),
                        confirmNote = "想删已落盘的文件，用下面的「清理云端配置残留（ROOT）」。开关关闭即刻放行，不需要重启。")
                    ),
                    trailing = {
                    Row(
                        Modifier.fillMaxWidth().clickable { showPurgePicker = true }.padding(18.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("清理云端配置残留（ROOT）", fontSize = 16.sp,
                                color = MiuixTheme.colorScheme.onSurface)
                            Spacer(Modifier.height(3.dp))
                            Text("按目录多选，删除 /data/cota/para/ 下的云控配置。"
                                    + "删除前会整批备份到下载目录 has_cota_backup/；"
                                    + "三级目录默认不选、红字标注，勾选后也会删。",
                                fontSize = 12.5.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                        Spacer(Modifier.width(12.dp))
                        Text("清理", fontSize = 14.sp, color = MiuixTheme.colorScheme.primary)
                    }
                    Box(Modifier.fillMaxWidth().padding(start = 18.dp).height(1.dp)
                        .background(MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.08f)))
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            backups = null
                            showBackupPicker = true
                            Thread {
                                val list = try { listCotaBackups() } catch (_: Throwable) { emptyList() }
                                runOnUiThread { backups = list }
                            }.apply { name = "has-cota-backup-list" }.start()
                        }.padding(18.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("从备份恢复云控配置（ROOT）", fontSize = 16.sp,
                                color = MiuixTheme.colorScheme.onSurface)
                            Spacer(Modifier.height(3.dp))
                            Text("列出下载目录 has_cota_backup/ 下的备份，"
                                    + "选择后整批恢复到 /data/cota/para/ 原路径。",
                                fontSize = 12.5.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                        Spacer(Modifier.width(12.dp))
                        Text("查看", fontSize = 14.sp, color = MiuixTheme.colorScheme.primary)
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
                                + "注：系统框架无法向本模块上报，勾没勾请到 LSPosed 模块页确认。",
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

        if (showPurgePicker) {
            CotaPickerDialog(
                title = "清理云端配置残留（ROOT）",
                desc = "按目录多选。删除前会先整批备份到下载目录 has_cota_backup/。",
                actionLabel = "扫描所选",
                footer = "三级高风险目录勾选后仍会执行；删除前必须整批备份到下载目录。",
                selected = purgeSelected,
                onToggle = { k ->
                    purgeSelected = if (k in purgeSelected) purgeSelected - k
                    else purgeSelected + k
                },
                onScan = {
                    if (purgeSelected.isEmpty()) {
                        Toast.makeText(this, "先选至少一个目录", Toast.LENGTH_SHORT).show()
                    } else {
                        val picked = cotaEntries.filter { it.key in purgeSelected }
                        showPurgePicker = false
                        Toast.makeText(this, "正在扫描所选目录…", Toast.LENGTH_SHORT).show()
                        Thread {
                            val ts = java.text.SimpleDateFormat("yyyyMMdd-HHmmss-SSS",
                                java.util.Locale.US).format(java.util.Date())
                            val res = try { scanCota(picked) } catch (t: Throwable) {
                                CotaScanResult(
                                    picked.map { CotaScan(it, PurgeProbe.UNKNOWN, emptyList()) },
                                    t.javaClass.simpleName)
                            }
                            runOnUiThread { purgePreview = res.copy(backupTs = ts) }
                        }.apply { name = "has-cota-scan" }.start()
                    }
                },
                onDismiss = { showPurgePicker = false }
            )
        }
        purgePreview?.let { pv ->
            if (pv.error != null || pv.hasUnknown) {
                ConfirmEnableDialog(
                    title = "无法确认云控文件状态",
                    lines = pv.scans.map {
                        it.entry.title + "：" + when (it.state) {
                            PurgeProbe.FOUND -> it.files.size.toString() + " 个文件"
                            PurgeProbe.GONE -> "不存在"
                            PurgeProbe.UNKNOWN -> "无法确认"
                        }
                    } + listOf("", pv.error ?: "有路径在当前命名空间看不到"),
                    note = "未执行任何删除。请检查 ROOT 授权后重试。",
                    confirmLabel = "知道了",
                    onConfirm = { purgePreview = null },
                    onDismiss = { purgePreview = null }
                )
            } else if (pv.allGone) {
                ConfirmEnableDialog(
                    title = "没有可清理的文件",
                    lines = pv.scans.map { it.entry.title + "：不存在" },
                    note = "所选目录本来就不存在，无需清理。",
                    confirmLabel = "知道了",
                    onConfirm = { purgePreview = null },
                    onDismiss = { purgePreview = null }
                )
            } else {
                CotaPreviewDialog(
                    preview = pv,
                    onConfirm = {
                        val picked = pv.scans.map { it.entry }
                        val ts = pv.backupTs ?: java.text.SimpleDateFormat(
                            "yyyyMMdd-HHmmss-SSS", java.util.Locale.US)
                            .format(java.util.Date())
                        purgePreview = null
                        purgeCotaEntries(picked, ts) { /* 结果由 purgeCotaEntries 内部 Toast */ }
                    },
                    onDismiss = { purgePreview = null }
                )
            }
        }
        if (showBackupPicker) {
            val list = backups
            if (list == null) {
                ConfirmEnableDialog(
                    title = "读取备份列表…",
                    lines = listOf("正在读取 $cotaBackupRoot/"),
                    note = "需要 ROOT。",
                    confirmLabel = "关闭",
                    onConfirm = { showBackupPicker = false },
                    onDismiss = { showBackupPicker = false }
                )
            } else if (list.isEmpty()) {
                ConfirmEnableDialog(
                    title = "没有可恢复的备份",
                    lines = listOf("$cotaBackupRoot/ 下没有 manifest.txt"),
                    note = "先执行一次「清理云端配置残留」，清理前会自动备份。",
                    confirmLabel = "知道了",
                    onConfirm = { showBackupPicker = false },
                    onDismiss = { showBackupPicker = false }
                )
            } else {
                CotaRestorePickerDialog(
                    backups = list,
                    onPick = { ts ->
                        showBackupPicker = false
                        restoreTs = ts
                    },
                    onDismiss = { showBackupPicker = false }
                )
            }
        }
        restoreTs?.let { ts ->
            ConfirmEnableDialog(
                title = "从备份恢复云控配置",
                lines = listOf(
                    "备份：$cotaBackupRoot/$ts/",
                    "将把该备份记录的条目整批恢复到 /data/cota/para/ 原路径",
                    "恢复后逐文件回读大小并做 EXIST / GONE / UNKNOWN 复核",
                    "恢复后请重启手机生效"
                ),
                note = "只恢复备份清单里的条目（含三级高风险）；逐文件校验大小。",
                confirmLabel = "恢复",
                onConfirm = {
                    restoreTs = null
                    restoreCotaBackup(ts) { /* 结果由 restoreCotaBackup 内部 Toast */ }
                },
                onDismiss = { restoreTs = null }
            )
        }
    }

    // ================= Misc =================

    @Composable
    private fun MiscPage(back: () -> Unit) {
        // 开关改完重读一次系统属性：温控那一行的"系统层状态"要立刻反映结果。
        var statusTick by remember { mutableIntStateOf(0) }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp)) {
            item { BackHeader("系统综合", back) }
            item { TipText("通知、温控与系统行为层面的综合开关：既能改善日常听感与信息呈现，也能解除系统在高温等场景下的自我限制；各项独立生效、互不影响。") }
            item {
                val prop = remember(statusTick) { readProp(Cfg.PROP_HOTTEMP) }
                val status = "系统层状态：" + (if (prop == "1") "已生效" else "已失效")
                SwitchCard(
                    rows = listOf(
                        SwRow("屏蔽通知回避", "播放音乐时来消息通知不再压低音乐音量，通知音与音乐同时播放；闹钟不受影响", Cfg.K_NODUCK, true, false),
                        SwRow("屏蔽高温限制（ROOT）",
                            "系统高温时不弹提示、不限亮度、热状态按正常上报，压制 PowerGenie 温控（游戏限帧/后台清理），高温自动关机也不再触发；内核调度与降频仍由硬件层控制，本开关不干预",
                            Cfg.K_HOTTEMP, true, false, confirmOnEnable = true,
                            statusText = status, statusOk = prop == "1")
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

    /**
     * 打开 App 时自动检查更新（静默）：
     * - 1 小时内已成功检查过 → 直接跳过，不提示、不打日志；
     * - 复用 [UpdateChecker.check]（与「关于」页手动检查同一套请求，不另开一套）；
     * - 只有「确认有新版本」才 Toast，其余（最新 / 检查中 / 失败 / 无网络 / 被节流）全静默；
     * - 网络在 UpdateChecker 的后台线程，回调在主线程，这里只负责弹 Toast。
     */
    private fun autoCheckUpdate() {
        val now = System.currentTimeMillis()
        val last = prefs().getLong(KEY_UPDATE_CHECK_TS, 0L)
        if (now - last < UPDATE_CHECK_INTERVAL_MS) return   // 节流：完全静默
        if (autoChecking) return                            // 已有一次在飞，别并发
        autoChecking = true
        val localCode = try {
            val pi = packageManager.getPackageInfo(packageName, 0)
            if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt()
            else { @Suppress("DEPRECATION") pi.versionCode }
        } catch (_: Throwable) { BuildConfig.VERSION_CODE }
        UpdateChecker.check(localCode) { r ->
            autoChecking = false
            // 只有成功拿到远端结果才写时间戳；失败/超时下次打开还能再试。
            if (r !is UpdateResult.Failed) {
                try {
                    prefs().edit().putLong(KEY_UPDATE_CHECK_TS, System.currentTimeMillis()).apply()
                } catch (_: Throwable) {}
            }
            if (r is UpdateResult.Newer && !isFinishing && !isDestroyed) {
                Toast.makeText(this, "检查到更新 v${r.info.versionName}，请到「关于」页更新", Toast.LENGTH_LONG).show()
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
        // 触控区 17dp → **48dp**（Android 无障碍标准的最小触控尺寸）。
        // 视觉和触控都是 17dp 时手指很难点中；外层 48dp 只负责接收点击
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
        /** 标题下一行的系统层实时状态（目前只有温控那一行用） */
        val statusText: String? = null,
        /** statusText 是否代表"正常"（决定文字颜色；"未生效"里也含"生效"，不能靠 contains） */
        val statusOk: Boolean = true
    )

    @Composable
    private fun SwitchCard(
        rows: List<SwRow>,
        onAfterChange: (() -> Unit)? = null,
        // 卡片尾部追加一块自定义内容（不是开关，而是一个动作行）。
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
        confirmLabel: String = "开启",
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
                        Text(confirmLabel, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                            color = MiuixTheme.colorScheme.primary,
                            modifier = Modifier.clickable { onConfirm() }.padding(8.dp))
                    }
                }
            }
        }
    }

    /**
     * 云控清理：按目录多选。一级 / 二级分组，保护名单明示且不可选。
     */
    @Composable
    private fun CotaPickerDialog(
        title: String,
        desc: String,
        actionLabel: String,
        footer: String,
        selected: Set<String>,
        onToggle: (String) -> Unit,
        onScan: () -> Unit,
        onDismiss: () -> Unit
    ) {
        Dialog(onDismissRequest = onDismiss) {
            Surface(shape = RoundedCornerShape(24.dp), color = MiuixTheme.colorScheme.surface) {
                Column(Modifier.padding(18.dp)) {
                    Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                        color = MiuixTheme.colorScheme.onSurface)
                    Text(desc,
                        fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 8.dp))
                    Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())
                        .padding(top = 4.dp)) {
                        for (level in listOf(1, 2, 3)) {
                            val list = cotaEntries.filter { it.level == level }
                            if (list.isEmpty()) continue
                            Text(
                                when (level) {
                                    1 -> "一级目录（默认全选）"
                                    2 -> "二级目录（可以清理）"
                                    else -> "三级目录（谨慎清理）"
                                },
                                fontSize = 13.sp, fontWeight = FontWeight.Bold,
                                color = if (level == 3) Color(0xFFD32F2F.toInt())
                                else MiuixTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
                            for (e in list) {
                                Row(Modifier.fillMaxWidth().clickable { onToggle(e.key) }
                                    .padding(vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    CheckDot(e.key in selected)
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(e.title, fontSize = 14.sp,
                                            color = if (e.level == 3)
                                                Color(0xFFD32F2F.toInt())
                                            else MiuixTheme.colorScheme.onSurface)
                                        if (e.level == 3) {
                                            Text("⚠ 涉及合规/权限，删了可能出问题（会先备份，可恢复）",
                                                fontSize = 11.5.sp,
                                                color = Color(0xFFD32F2F.toInt()))
                                        }
                                    }
                                }
                            }
                        }
                        Text(footer,
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(top = 10.dp))
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 16.dp),
                        horizontalArrangement = Arrangement.End) {
                        Text("取消", fontSize = 14.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.clickable { onDismiss() }.padding(8.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(actionLabel, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                            color = MiuixTheme.colorScheme.primary,
                            modifier = Modifier.clickable { onScan() }.padding(8.dp))
                    }
                }
            }
        }
    }

    @Composable
    private fun CheckDot(checked: Boolean) {
        Box(Modifier.size(20.dp).clip(CircleShape)
            .background(if (checked) MiuixTheme.colorScheme.primary else Color.Transparent)
            .border(1.dp, MiuixTheme.colorScheme.primary, CircleShape),
            contentAlignment = Alignment.Center) {
            if (checked) {
                Text("✓", fontSize = 12.sp, color = Color.White)
            }
        }
    }

    /**
     * 清理确认框：列出每个条目的具体文件清单与数量（最多每项 8 个，超出显示总数）。
     */
    @Composable
    private fun CotaPreviewDialog(
        preview: CotaScanResult,
        onConfirm: () -> Unit,
        onDismiss: () -> Unit
    ) {
        Dialog(onDismissRequest = onDismiss) {
            Surface(shape = RoundedCornerShape(24.dp), color = MiuixTheme.colorScheme.surface) {
                Column(Modifier.padding(18.dp)) {
                    val ctx = LocalContext.current
                    val lowCount = preview.scans.count {
                        it.entry.level == 1 && it.state == PurgeProbe.FOUND
                    }
                    val midCount = preview.scans.count {
                        it.entry.level == 2 && it.state == PurgeProbe.FOUND
                    }
                    val highCount = preview.highRiskCount
                    val backupPath = "$cotaBackupRoot/${preview.backupTs ?: "执行时生成"}/"
                    Text("确认清理云控配置", fontSize = 18.sp, fontWeight = FontWeight.Bold,
                        color = MiuixTheme.colorScheme.onSurface)
                    Text("一级 $lowCount 个 / 二级 $midCount 个 / 三级 $highCount 个；" +
                            "合计 ${preview.files.size} 个文件、${preview.totalBytes} 字节。",
                        fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 8.dp))
                    if (highCount > 0) {
                        Text("包含 $highCount 个高风险目录（三级）；执行前会整批备份，可恢复。",
                            fontSize = 13.sp, fontWeight = FontWeight.Bold,
                            color = Color(0xFFD32F2F.toInt()),
                            modifier = Modifier.padding(top = 6.dp))
                    } else {
                        Text("未包含三级高风险目录。", fontSize = 13.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(top = 6.dp))
                    }
                    Text("备份位置（点按复制）：$backupPath",
                        fontSize = 12.5.sp, color = MiuixTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 6.dp).clickable {
                            try {
                                val cm = ctx.getSystemService(
                                    android.content.ClipboardManager::class.java)
                                cm?.setPrimaryClip(android.content.ClipData.newPlainText(
                                    "has_cota_backup", backupPath))
                                Toast.makeText(ctx, "已复制备份路径", Toast.LENGTH_SHORT).show()
                            } catch (_: Throwable) {
                            }
                        })
                    Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())
                        .padding(top = 4.dp)) {
                        for (s in preview.scans) {
                            if (s.state != PurgeProbe.FOUND) continue
                            Text(s.entry.title + "（${s.files.size} 个文件）",
                                fontSize = 13.5.sp, fontWeight = FontWeight.Bold,
                                color = if (s.entry.level == 3)
                                    Color(0xFFD32F2F.toInt())
                                else MiuixTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(top = 8.dp))
                            if (s.files.isEmpty()) {
                                Text("  （空目录，只移除目录）", fontSize = 12.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            } else {
                                for (f in s.files.take(8)) {
                                    Text("  " + f.removePrefix(cotaRoot + "/"),
                                        fontSize = 12.sp,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                }
                                if (s.files.size > 8) {
                                    Text("  … 其余 ${s.files.size - 8} 个", fontSize = 12.sp,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                }
                            }
                        }
                    }
                    Text("确认后将删除上述文件，不可撤销；删除前已整目录备份。",
                        fontSize = 12.sp, color = Color(0xFFD32F2F.toInt()),
                        modifier = Modifier.padding(top = 10.dp))
                    Row(Modifier.fillMaxWidth().padding(top = 16.dp),
                        horizontalArrangement = Arrangement.End) {
                        Text("取消", fontSize = 14.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.clickable { onDismiss() }.padding(8.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("确认", fontSize = 14.sp, fontWeight = FontWeight.Bold,
                            color = MiuixTheme.colorScheme.primary,
                            modifier = Modifier.clickable { onConfirm() }.padding(8.dp))
                    }
                }
            }
        }
    }

    /** 备份选择框（时间倒序）。 */
    @Composable
    private fun CotaRestorePickerDialog(
        backups: List<CotaBackup>,
        onPick: (String) -> Unit,
        onDismiss: () -> Unit
    ) {
        Dialog(onDismissRequest = onDismiss) {
            Surface(shape = RoundedCornerShape(24.dp), color = MiuixTheme.colorScheme.surface) {
                Column(Modifier.padding(18.dp)) {
                    val ctx = LocalContext.current
                    Text("从备份恢复", fontSize = 18.sp, fontWeight = FontWeight.Bold,
                        color = MiuixTheme.colorScheme.onSurface)
                    Text("备份根目录（点按复制）：$cotaBackupRoot/", fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 6.dp).clickable {
                            try {
                                val cm = ctx.getSystemService(
                                    android.content.ClipboardManager::class.java)
                                cm?.setPrimaryClip(android.content.ClipData.newPlainText(
                                    "has_cota_backup", cotaBackupRoot + "/"))
                                Toast.makeText(ctx, "已复制备份根目录", Toast.LENGTH_SHORT).show()
                            } catch (_: Throwable) {
                            }
                        })
                    Text("选择要恢复的备份（按时间倒序）：", fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 8.dp))
                    Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())
                        .padding(top = 6.dp)) {
                        for (b in backups) {
                            Row(Modifier.fillMaxWidth().clickable { onPick(b.ts) }
                                .padding(vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(b.ts, fontSize = 14.sp,
                                        color = MiuixTheme.colorScheme.onSurface)
                                    Text("$cotaBackupRoot/${b.ts}/", fontSize = 11.5.sp,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                    Text("${b.entries} 个条目 / ${b.files} 个文件",
                                        fontSize = 12.sp,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                }
                                Text("恢复", fontSize = 13.sp,
                                    color = MiuixTheme.colorScheme.primary)
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp),
                        horizontalArrangement = Arrangement.End) {
                        Text("关闭", fontSize = 14.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.clickable { onDismiss() }.padding(8.dp))
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

    @Composable
    private fun SwitchRow(r: SwRow, checked: Boolean, onChange: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(r.title, fontSize = 16.sp, color = MiuixTheme.colorScheme.onSurface)
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
                        "· 启动或回到应用时最多每小时成功检查一次版本号（GitHub 公开接口），失败或断网时下次打开会重试；不传输任何个人数据；也可在「关于」页手动检查\n" +
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

    /** 重启作用域：可多选，一次 su 会话全部执行。 */
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
                        Row(Modifier.fillMaxWidth().clickable { picked[t.pkg] = !on }.padding(vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            CheckMark(on)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(t.label, fontSize = 15.5.sp, color = MiuixTheme.colorScheme.onSurface)
                                if (t.pkg == "__reboot__") {
                                    Text("整机重启（系统框架无法单进程重启）", fontSize = 10.5.sp,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        modifier = Modifier.padding(top = 1.dp))
                                }
                            }
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
     * 直接弹选择框，不先跑一次 su -c id 预检 —— 那一步要多等一次授权握手，
     * 而命令真失败时 restartScopes 已经会明确报"ROOT 授权被拒绝"。
     */
    private fun requestRootThenPickScope() {
        rootDialogVisible = true
    }

    // shown when the root check above succeeds; the Composable observes it
    private var rootDialogVisible by mutableStateOf(false)
    private var rebootChoiceVisible by mutableStateOf(false)
    private var softRebootBusy = false

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

    /** 「重启系统」的二次确认：彻底重启 / 软重启 / 取消，三个按钮。 */
    private fun askReboot() {
        rebootChoiceVisible = true
    }

    @Composable
    private fun RebootOption(text: String, desc: String, color: Color, onClick: () -> Unit) {
        Column(Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 4.dp)) {
            Text(text, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = color)
            if (desc.isNotEmpty()) {
                Text(desc, fontSize = 11.5.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 2.dp))
            }
        }
    }

    /**
     * 重启方式选择。
     *
     * <p>软重启用 `setprop ctl.restart zygote`：本机实测 root 保留、boot_id 不变、
     * LSPosed 重新注入，屏幕黑约十几秒。彻底重启走 `reboot`，会掉临时 root。
     */
    @Composable
    private fun RebootChoiceDialog(onDismiss: () -> Unit) {
        Dialog(onDismissRequest = onDismiss) {
            Surface(shape = RoundedCornerShape(24.dp), color = MiuixTheme.colorScheme.surface) {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
                    Text("选择重启方式", fontSize = 18.sp, fontWeight = FontWeight.Bold,
                        color = MiuixTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(vertical = 4.dp))
                    Text("软重启只重载系统框架，不会重启内核，临时 root 不会掉。"
                            + "执行后屏幕会黑十几秒到半分钟，期间请不要点屏幕、不要断电；"
                            + "系统框架相关功能会重新加载。",
                        fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(bottom = 8.dp))
                    Text("彻底重启会重启内核，临时 root 会掉，需要重新 root。",
                        fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(bottom = 10.dp))
                    Box(Modifier.fillMaxWidth().height(1.dp)
                        .background(MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.08f)))
                    RebootOption("重启系统（彻底重启）",
                        "执行 reboot，内核重启；临时 root 会掉", Color(0xFFE5484D)) {
                        onDismiss()
                        hardReboot()
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp)
                        .background(MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.08f)))
                    RebootOption("软重启（仅系统框架）",
                        "执行 setprop ctl.restart zygote，不动内核；临时 root 保留", MiuixTheme.colorScheme.primary) {
                        onDismiss()
                        softReboot()
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp)
                        .background(MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.08f)))
                    RebootOption("取消", "", MiuixTheme.colorScheme.onSurfaceVariantSummary) {
                        onDismiss()
                    }
                }
            }
        }
    }

    /** 彻底重启：内核重启，临时 root 会掉。失败也必须明确报出来。 */
    private fun hardReboot() {
        Toast.makeText(this, "正在重启…", Toast.LENGTH_SHORT).show()
        Thread {
            AppLog.d("scope", "重启系统（彻底）")
            val code = suExecCode("reboot", 30000L)
            AppLog.d("scope", "彻底重启 su exit=" + code)
            if (code != 0) {
                runOnUiThread {
                    Toast.makeText(this,
                        "重启命令没执行成功（su 返回 ${code ?: "超时"}），请检查 ROOT 授权",
                        Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    /**
     * 软重启：重载 zygote / system_server，不动内核。
     *
     * <p>执行前先清空探针记录 —— 否则旧记录在 5 分钟窗口里会冒充新鲜，
     * 首页在软重启期间会假绿。
     *
     * <p>失败不静默：命令不存在返回 127、su 非 0 都明确提示改用彻底重启。
     * 即使 exit=0，若 8 秒后本进程还活着（说明 init 没真重启框架），也报失败。
     */
    private fun softReboot() {
        if (softRebootBusy) {
            Toast.makeText(this, "软重启正在准备，请稍候", Toast.LENGTH_SHORT).show()
            return
        }
        softRebootBusy = true
        Toast.makeText(this, "正在检查安装状态…", Toast.LENGTH_SHORT).show()
        Thread {
            // 任何非成功出口都必须复位 busy。早期版本把复位分散写在每个分支里，
            // 漏一处（su 超时 / 读不到安装状态）按钮就永久锁死、彻底点不动。
            // 这里用 try/finally 兜底：所有 return 和异常都会经过 finally。
            // 真成功时本进程会被 zygote 一起杀掉，走不到 finally，也就无需复位。
            try {
                // 1) 有活跃安装会话就拒绝，避免安装到一半黑屏重启。
                val active = hasRecentInstallSession()
                if (active != false) {
                    val msg = if (active == true) "正在安装，稍后再试" else "无法确认安装状态，稍后再试"
                    AppLog.d("scope", "软重启被拒: $msg active=$active")
                    runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
                    return@Thread
                }
                // 2) 先落 pending=1 并回读。新代次到达前，任何旧记录都不许判绿。
                val setCode = suExecCode("setprop persist.sys.has.epoch_pending 1", 30000L)
                val pending = readProp("persist.sys.has.epoch_pending")?.trim()
                AppLog.d("scope", "软重启 pending 写入 exit=$setCode 回读=$pending")
                if (setCode != 0 || pending != "1") {
                    runOnUiThread {
                        Toast.makeText(this,
                            "软重启没执行：无法写入框架换代标记（su=$setCode, 回读=${pending ?: "读不到"}）",
                            Toast.LENGTH_LONG).show()
                    }
                    return@Thread
                }
                // 3) 清旧回执 + 进入 3 秒快速刷新窗口。
                val cleared = CfgProvider.clearProbe(this)
                AppLog.d("scope", "软重启前清空探针记录 ok=$cleared")
                runOnUiThread {
                    Toast.makeText(this, "正在软重启，屏幕会黑十几秒到半分钟，请不要操作、不要断电…",
                        Toast.LENGTH_LONG).show()
                }
                // 4) 命令存在性检查；没有 setprop 直接 127。
                val cmd = "command -v setprop >/dev/null 2>&1 || exit 127; setprop ctl.restart zygote"
                AppLog.d("scope", "软重启 命令=$cmd")
                val code = suExecCode(cmd, 30000L)
                AppLog.d("scope", "软重启 su exit=" + code)
                if (code != 0) {
                    val why = when (code) {
                        null -> "su 超时"
                        127 -> "系统里找不到 setprop 命令"
                        else -> "su 返回 $code"
                    }
                    runOnUiThread {
                        Toast.makeText(this,
                            "软重启没执行成功（$why），请用「重启系统（彻底）」",
                            Toast.LENGTH_LONG).show()
                    }
                    return@Thread
                }
                // 真成功时本进程会被 zygote 一起杀掉，走不到下面这行。
                // 还能走到 = init 没真重启框架，必须报失败，不能假装成功。
                Thread.sleep(8000L)
                runOnUiThread {
                    Toast.makeText(this,
                        "软重启命令已执行，但系统框架没有重启，请用「重启系统（彻底）」",
                        Toast.LENGTH_LONG).show()
                }
            } finally {
                runOnUiThread { softRebootBusy = false }
            }
        }.start()
    }

    /**
     * 是否有"近期活跃"的 PackageInstaller 会话。
     *
     * <p>系统里可能残留几天前的未提交会话（本机就有一条），不能一看到
     * Active Session 就永远拒绝软重启；只认 10 分钟内更新过且未提交的。
     * 读不到 = null，按"无法确认"处理，宁可让用户稍后再试。
     */
    private fun hasRecentInstallSession(): Boolean? {
        // 必须用带超时的 suExecCapture：读输出和等待超时都在同一个硬时限里，
        // su 卡在授权弹窗时也不会把整条软重启线程永久挂住。
        val out = suExecCapture("dumpsys package", 15000L) ?: return null
        val start = out.indexOf("Active install sessions:")
        if (start < 0) return false
        val end = out.indexOf("Finalized install sessions:", start)
        val section = out.substring(start, if (end > start) end else out.length)
        val now = System.currentTimeMillis()
        for (block in section.split("Active Session").drop(1)) {
            val updated = Regex("updatedMillis=(\\d+)").find(block)
                ?.groupValues?.get(1)?.toLongOrNull() ?: continue
            val committed = Regex("committedMillis=(\\d+)").find(block)
                ?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val age = now - updated
            if (committed == 0L && age in 0..10L * 60L * 1000L) return true
        }
        return false
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
            // 先清对应宿主的旧记录，再杀进程。顺序反了的话，旧记录会在 5 分钟
            // 窗口里继续冒充"心跳暂停"或假绿；而安装器这种不常驻进程根本不会
            // 自己刷新心跳，界面就会永远卡住（本机 16:39 那次的现场）。
            val probeKeys = targets.map { it.pkg }
                .filter { it != "__reboot__" }
                .flatMap {
                    if (it == "com.hihonor.iaware")
                        listOf(it, "com.hihonor.powergenie")
                    else listOf(it)
                }
            val cleared = CfgProvider.clearProbe(this, *probeKeys.toTypedArray())
            AppLog.d("scope", "清空探针记录 ok=$cleared keys=" + probeKeys.joinToString(","))
            val ok = suExec(targets.joinToString(" ; ") { it.cmd })
            AppLog.d("scope", "结果=" + (if (ok) "成功" else "失败"))
            runOnUiThread {
                Toast.makeText(this,
                    if (ok) "已重启：$names" else "重启失败：su 没执行成功（检查 KernelSU/Magisk 里的授权）",
                    Toast.LENGTH_LONG).show()
            }
        }.start()
    }
}
