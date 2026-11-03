package io.github.voreulch.honoradvanced

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 某个宿主进程最近一次自证上报。 */
data class ScopeReport(
    val pkg: String,
    /** 上报时跑在进程里的模块版本号（低于当前版本 = 该进程还开着旧代码）。 */
    val ver: Int,
    /** 上报进程的 pid。 */
    val pid: Int,
    /** 上报时的 boot_id（每次重启变化）。 */
    val bootId: String,
    /** 进程启动时刻（/proc/<pid>/stat 第 22 列，开机后的 clock ticks；0=未取到）。 */
    val startAt: Long,
    /** 最近一次心跳时间（System.currentTimeMillis）。 */
    val at: Long
)

/**
 * 心跳窗口。
 *
 * <p>不能用长窗口兜底：模块被禁用后旧记录还能"绿"三天，是假阳性。
 * Probe 每 60 秒写一次心跳，超过 5 分钟没心跳就判失效。
 * 同时要求记录里的 boot_id 等于本次开机的 boot_id：重启后旧记录立刻作废。
 */
private const val HEARTBEAT_WINDOW_MS = 5L * 60L * 1000L

private var sBootId: String? = null

/** 本次开机的 boot_id（读取失败返回空串 = 所有记录判失效）。 */
private fun currentBootId(): String {
    sBootId?.let { return it }
    val b = try {
        java.io.File("/proc/sys/kernel/random/boot_id").readText().trim()
    } catch (_: Throwable) {
        ""
    }
    sBootId = b
    return b
}

/** 某条上报是否还"新鲜"：本次开机 + 最近心跳。 */
private fun ScopeReport.isFresh(): Boolean {
    if (at <= 0L) return false
    val boot = currentBootId()
    if (boot.isEmpty() || bootId != boot) return false
    val age = System.currentTimeMillis() - at
    return age in 0..HEARTBEAT_WINDOW_MS
}

/**
 * 模块在各作用域里的实际运行状态（v2.3.0）。
 *
 * [probed] = 至少成功读过一次 provider；false 说明还没读或读失败，
 * 这时界面不该下任何结论（不能把"没读到"画成"没运行"）。
 */
data class ModuleState(
    val reports: Map<String, ScopeReport> = emptyMap(),
    val probed: Boolean = false
) {
    /** 只算"还新鲜"的记录 —— 避免模块被禁用后旧记录造成的假阳性。 */
    val running: Boolean get() = reports.values.any { it.isFresh() }

    fun has(pkg: String): Boolean = reports[pkg]?.isFresh() == true

    /**
     * 本次开机上报过、进程 pid 有效，但心跳可能已经过期（深休眠 / Doze 后常见）。
     *
     * <p>与 [has] 分开表达：心跳新鲜 = 绿；仅"本次开机仍活着" = 黄（等一下会自愈），
     * 不能一解锁就报红。
     */
    fun alive(pkg: String): Boolean {
        val r = reports[pkg] ?: return false
        val boot = currentBootId()
        return boot.isNotEmpty() && r.bootId == boot && r.pid > 0
    }

    fun missing(required: Collection<String>): List<String> =
        required.filter { !has(it) }

    /**
     * 上报过，但记录已经过期。
     *
     * <p>用来把「**从没上报过**」（模块没勾作用域 / 没重启）和「**上报过但记录太旧**」
     * 区分开。两者的修法完全不同：
     * <ul>
     *   <li>从没上报 → 去 LSPosed 勾作用域并重启进程</li>
     *   <li>记录太旧 → 只是很久没重启，重启一次就能刷新，不一定是坏了</li>
     * </ul>
     * 不区分的话，用户看到"作用域未生效"会以为模块坏了，白折腾一轮。
     */
    fun expired(pkg: String): Boolean {
        val r = reports[pkg] ?: return false
        val boot = currentBootId()
        if (r.bootId.isEmpty() || r.bootId != boot) return false
        return System.currentTimeMillis() - r.at > HEARTBEAT_WINDOW_MS
    }

    /** 该作用域在跑，但跑的是旧版本模块（OTA 后没重启进程）。 */
    fun stale(pkg: String): Boolean {
        val r = reports[pkg] ?: return false
        return r.bootId == currentBootId() && r.ver in 1 until BuildConfig.VERSION_CODE
    }

    companion object {
        val EMPTY = ModuleState()
    }
}

/**
 * 作用域状态探测 —— **不使用 root**。
 *
 * <p>数据来源是 {@link Probe} 的自证上报：每个被 hook 的进程启动时向模块自己的
 * CfgProvider 写一行（模块版本 + 时间），这里读 prefs 就能知道"谁真的在跑模块"。
 *
 * <p>相比去读 LSPosed 的配置数据库，这条路有两个好处：
 * <ul>
 *   <li>不需要 root，也不需要适配 LSPosed / Vector 各版本的 CLI 或表结构</li>
 *   <li>验证的是**实际加载**而不是"勾选过" —— 勾了但没重启进程、或者模块刚升级
 *       而宿主还在跑旧代码，都能如实反映出来</li>
 * </ul>
 *
 * <p>代价：全新安装、还没重启任何作用域时这里必然是空的。界面会明确提示
 * "重启作用域后才会记录"，而不是误报"未启用"。
 */
object FrameworkProbe {

    var state by mutableStateOf(ModuleState.EMPTY)
        private set

    private val main = Handler(Looper.getMainLooper())
    private var loading = false
    private var lastAt = 0L

    /** 主线程预热：mutableStateOf 必须建在主线程，否则首次触碰来自工作线程时
     *  会创建在过期的 snapshot 上，组合阶段抛 IllegalStateException。 */
    fun warmUp() {
        // 触发 <clinit> 即可
    }

    fun refresh(ctx: Context, force: Boolean = false) {
        if (loading) return
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastAt < 1000L) return
        lastAt = now
        loading = true
        val app = ctx.applicationContext ?: ctx
        try {
            Thread {
                val m = read(app)
                main.post {
                    // 读失败 ≠ 没有上报。查询抛异常时保留上一次的结果，
                    // 否则模块 app 刚被 force-stop / 正在安装的那一下，
                    // 首页会闪一下"模块未生效"——纯误报。
                    if (m != null) state = ModuleState(m, true)
                    loading = false
                }
            }.apply { isDaemon = true; name = "HAS-ProbeRead" }.start()
        } catch (t: Throwable) {
            // 线程起不来（极端内存压力）时也要复位，否则这个进程再也不刷新
            loading = false
        }
    }

    /** null = 这次没读成功（区别于"读成功但一条记录都没有"）。 */
    private fun read(ctx: Context): Map<String, ScopeReport>? {
        val out = LinkedHashMap<String, ScopeReport>()
        return try {
            val cursor = ctx.contentResolver.query(
                CfgProvider.URI_PROBE, null, null, null, null
            ) ?: return null
            cursor.use { c ->
                val pi = c.getColumnIndex(CfgProvider.COL_PKG)
                val vi = c.getColumnIndex(CfgProvider.COL_VER)
                val idi = c.getColumnIndex(CfgProvider.COL_PID)
                val bi = c.getColumnIndex(CfgProvider.COL_BOOT)
                val si = c.getColumnIndex(CfgProvider.COL_START)
                val ai = c.getColumnIndex(CfgProvider.COL_BEAT)
                if (pi < 0 || vi < 0 || ai < 0) return null
                while (c.moveToNext()) {
                    val p = c.getString(pi) ?: continue
                    out[p] = ScopeReport(
                        p,
                        c.getInt(vi),
                        if (idi >= 0) c.getInt(idi) else 0,
                        if (bi >= 0) (c.getString(bi) ?: "") else "",
                        if (si >= 0) c.getLong(si) else 0L,
                        c.getLong(ai)
                    )
                }
            }
            out
        } catch (_: Throwable) {
            null
        }
    }

    /** 相对时间，给状态行用（"3 分钟前"）。 */
    fun ago(at: Long): String {
        if (at <= 0L) return "未知"
        val d = System.currentTimeMillis() - at
        return when {
            d < 0 -> "刚刚"
            d < 60_000 -> "刚刚"
            d < 3_600_000 -> "${d / 60_000} 分钟前"
            d < 86_400_000 -> "${d / 3_600_000} 小时前"
            else -> "${d / 86_400_000} 天前"
        }
    }
}
