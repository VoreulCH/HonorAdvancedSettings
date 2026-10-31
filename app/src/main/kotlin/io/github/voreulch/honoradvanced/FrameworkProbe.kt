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
    /** 上报时间（System.currentTimeMillis）。 */
    val at: Long
)

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
    val running: Boolean get() = reports.isNotEmpty()

    fun has(pkg: String): Boolean = reports.containsKey(pkg)

    fun missing(required: Collection<String>): List<String> =
        required.filter { !reports.containsKey(it) }

    /** 该作用域在跑，但跑的是旧版本模块（OTA 后没重启进程）。 */
    fun stale(pkg: String): Boolean {
        val r = reports[pkg] ?: return false
        return r.ver < BuildConfig.VERSION_CODE
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
                val ai = c.getColumnIndex("at")
                if (pi < 0 || vi < 0 || ai < 0) return null
                while (c.moveToNext()) {
                    val p = c.getString(pi) ?: continue
                    out[p] = ScopeReport(p, c.getInt(vi), c.getLong(ai))
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
