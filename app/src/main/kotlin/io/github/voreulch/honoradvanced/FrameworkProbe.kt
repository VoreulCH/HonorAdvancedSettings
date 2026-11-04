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
    /** 上报时的框架代次（system_server 每次加载模块生成；软重启后必须变化）。 */
    val epoch: String,
    /** 最近一次心跳时间（System.currentTimeMillis）。 */
    val at: Long,
    /**
     * 反诈挂载数（审核 N-221-11）。provider 侧写入，不依赖系统属性。
     * `-1` = 本进程没给出结论（老记录 / 未跑反诈钩子 / 扫描异常）→ 按"无回执"处理。
     */
    val nofraudCount: Int = -1,
    /** 反诈来源腿：semantic / shape / hardcoded / none；空 = 无回执。 */
    val nofraudLeg: String = ""
)

/**
 * 一次 root 进程表快照。
 *
 * <p>只靠回执判活有两个漏洞：进程被杀后 pid 还留在记录里（旧实现只看 pid>0，
 * 永远判"活着"），以及 pid 被别的进程复用。这里同时保存"当前在跑的进程"
 * 和"记录里那些 pid 的 startAt"，两者都对上才算活着。
 *
 * <p>没有 root / 读不到进程表时整个对象为 null，界面必须落"无法判断"，不许猜。
 */
data class ProcSnapshot(
    /** 进程名 -> 当前 pid 集合（含 `pkg:sub` 子进程）。 */
    val byName: Map<String, Set<Int>>,
    /** 记录里出现过的 pid -> /proc/<pid>/stat 第 22 列。 */
    val starts: Map<Int, Long>
) {
    fun processesOf(pkg: String): Set<Int> {
        val out = HashSet<Int>()
        for ((name, pids) in byName) {
            if (name == pkg || name.startsWith("$pkg:")) out.addAll(pids)
        }
        return out
    }

    fun hasProcess(pkg: String): Boolean = processesOf(pkg).isNotEmpty()

    /** 记录里的 pid 现在还活着，且 startAt 与记录一致（防 pid 复用）。 */
    fun recordAlive(pkg: String, pid: Int, startAt: Long): Boolean {
        if (pid <= 0 || pid !in processesOf(pkg)) return false
        val now = starts[pid] ?: return false
        return startAt <= 0L || now == startAt
    }
}

/** 当前框架代次（system_server 每次加载模块写入）。空 = 旧框架 / 写不进去。 */
private fun currentFrameworkEpoch(): String = try {
    val sp = Class.forName("android.os.SystemProperties")
    val v = sp.getMethod("get", String::class.java, String::class.java)
        .invoke(null, "persist.sys.has.framework_epoch", "") as? String
    v?.trim() ?: ""
} catch (_: Throwable) {
    ""
}

/** 软重启 pending 标记：1 = 框架正在换代，任何记录都不许判新鲜。null = 读不到（按不新鲜）。 */
private fun currentEpochPending(): Boolean? = try {
    val sp = Class.forName("android.os.SystemProperties")
    val v = sp.getMethod("get", String::class.java, String::class.java)
        .invoke(null, "persist.sys.has.epoch_pending", "0") as? String
    v?.trim() == "1"
} catch (_: Throwable) {
    null
}

/** system_server 写 epoch 后的自检状态：OK / FAIL:<原因>。 */
fun currentEpochState(): String = try {
    val sp = Class.forName("android.os.SystemProperties")
    val v = sp.getMethod("get", String::class.java, String::class.java)
        .invoke(null, "persist.sys.has.epoch_state", "") as? String
    v?.trim() ?: ""
} catch (_: Throwable) {
    "UNKNOWN"
}

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

/**
 * 某条上报是否还"新鲜"：本次开机 + 当前框架代次 + 最近心跳。
 *
 * <p>epoch 是 216 新增的硬条件：软重启不改变 boot_id，旧记录（或重启前刚写的
 * 记录）会带着旧 epoch，必须判失效，否则首页会把"框架其实已经换代、宿主还没
 * 重新上报"画成绿色。旧记录没有 epoch 字段 → 一律不新鲜。
 */
private fun ScopeReport.isFresh(): Boolean {
    if (at <= 0L) return false
    val boot = currentBootId()
    if (boot.isEmpty() || bootId != boot) return false
    // 软重启期间 pending=1：旧代次、甚至新代次刚写一半时，都不许把任何记录判绿。
    if (currentEpochPending() != false) return false
    val epoch = currentFrameworkEpoch()
    if (epoch.isEmpty() || epoch != this.epoch) return false
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
    val probed: Boolean = false,
    /** null = 没 root / 读不到进程表；此时任何"活着/死了"的结论都不许下。 */
    val proc: ProcSnapshot? = null
) {
    /** 只算"还新鲜"的记录 —— 避免模块被禁用后旧记录造成的假阳性。 */
    val running: Boolean get() = reports.values.any { it.isFresh() }

    /**
     * 回执新鲜，且（能拿到进程表时）记录里的 pid 确实还活着、startAt 未变。
     *
     * <p>只判心跳不够：进程刚被杀、心跳还在 5 分钟窗口里时会假绿。拿不到进程表
     * （没 root / 读失败）一律判"无法判断"，绝不把回执直接当绿。
     */
    fun has(pkg: String): Boolean {
        val r = reports[pkg] ?: return false
        if (!r.isFresh()) return false
        // 读不到进程表 = 无法判断，绝不允许默认绿。
        val p = proc ?: return false
        return p.recordAlive(pkg, r.pid, r.startAt)
    }

    /**
     * 本次开机上报过、进程仍活着（心跳可能已经过期，深休眠 / Doze 后常见）。
     *
     * <p>旧实现只判 `pid > 0`，进程死了记录还留着 → 永远卡在"心跳暂停"。
     * 现在必须 /proc 存在 + startAt 一致；读不到进程表一律判"无法判断"。
     */
    fun alive(pkg: String): Boolean {
        val r = reports[pkg] ?: return false
        val boot = currentBootId()
        if (boot.isEmpty() || r.bootId != boot) return false
        // 读不到进程表 = 无法判断，不判活也不判死。
        val p = proc ?: return false
        return p.recordAlive(pkg, r.pid, r.startAt)
    }

    /** 该包的进程当前是否存在（null 进程表 = 不知道）。 */
    fun pkgRunning(pkg: String): Boolean? = proc?.hasProcess(pkg)

    /**
     * 该包**主进程**当前是否在跑（精确等于包名，不含 `pkg:sub` 子进程）。
     * null = 读不到进程表。
     */
    fun mainProcessRunning(pkg: String): Boolean? =
        proc?.byName?.get(pkg)?.isNotEmpty()

    /** 记录里的心跳距今多少毫秒；没有记录 / 时间无效 = null。 */
    fun beatAgeMs(pkg: String): Long? {
        val r = reports[pkg] ?: return null
        if (r.at <= 0L) return null
        return (System.currentTimeMillis() - r.at).coerceAtLeast(0L)
    }

    /** 记录里的框架代次是否等于当前代次（软重启后旧记录会不匹配）。 */
    fun epochMatches(pkg: String): Boolean {
        val r = reports[pkg] ?: return false
        val e = currentFrameworkEpoch()
        return e.isNotEmpty() && r.epoch == e
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

    /**
     * 反诈挂载回执（N-221-11 / N-221-19）：返回 (挂载数, 来源腿)。
     *
     * <p>只认**本次开机 + 主进程 pid** 的记录：awareness 的子进程也会写同一个
     * pkg 键，若采纳子进程的记录，主进程写 0、子进程写 2 会让开关红绿乱跳。
     * 过滤只复用 `pid` 列，不另造 pkg#pid 键。
     *
     * <p>`nofraudCount = -1`（老记录缺列 / 本进程无结论）与进程表读不到都返回
     * null = 无法判断，绝不默认成 0 或 1。
     */
    fun nofraud(pkg: String): Pair<Int, String>? {
        val r = reports[pkg] ?: return null
        if (r.nofraudCount < 0) return null
        val boot = currentBootId()
        if (boot.isEmpty() || r.bootId != boot) return null
        // 主进程 = 进程名精确等于包名的那一组；子进程（pkg:sub）不在其中。
        val mainPids = proc?.byName?.get(pkg) ?: return null
        if (r.pid <= 0 || r.pid !in mainPids) return null
        return r.nofraudCount to r.nofraudLeg
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
    private var procLoading = false
    private var lastProcAt = 0L
    private var fastUntil = 0L

    /** 首页在恢复期用 3 秒刷新；稳定后放宽到 30 秒，熄屏/后台由 repeatOnLifecycle 直接停。 */
    fun pokeFast(windowMs: Long) {
        fastUntil = maxOf(fastUntil, SystemClock.uptimeMillis() + windowMs)
    }

    fun fastRefreshDue(): Boolean = SystemClock.uptimeMillis() < fastUntil

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
                    if (m != null) {
                        val oldPids = state.reports.values.map { it.pid }.filter { it > 0 }.toSet()
                        val newPids = m.values.map { it.pid }.filter { it > 0 }.toSet()
                        state = ModuleState(m, true, state.proc)
                        // 回执里的 pid 变了（进程重启 / 首次读到）就作废进程表节流，
                        // 下一轮 3 秒刷新立即按新 pid 重扫，别让首页空等十几秒。
                        if (oldPids != newPids) lastProcAt = 0L
                    }
                    loading = false
                }
            }.apply { isDaemon = true; name = "HAS-ProbeRead" }.start()
        } catch (t: Throwable) {
            // 线程起不来（极端内存压力）时也要复位，否则这个进程再也不刷新
            loading = false
        }
    }

    /**
     * 用 root 扫一次进程表 + 记录里那些 pid 的 startAt。
     *
     * <p>不带 root 的用户这里会是 null，界面落"无法判断"——不拿 pid>0 冒充活着。
     */
    fun refreshProc(ctx: Context, force: Boolean = false) {
        if (procLoading) return
        val pids = state.reports.values.map { it.pid }.filter { it > 0 }.distinct().take(32)
        // 还没读到任何回执：扫进程表没有意义（不知道要核对谁的 startAt）。
        // 不占用节流，等 refresh 拿到 record 后下一轮立刻重扫。
        if (pids.isEmpty()) {
            lastProcAt = 0L
            return
        }
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastProcAt < 10_000L) return
        lastProcAt = now
        procLoading = true
        try {
            Thread {
                val snap = readProcSnapshot(pids)
                main.post {
                    state = state.copy(proc = snap)
                    procLoading = false
                }
            }.apply { isDaemon = true; name = "HAS-ProcRead" }.start()
        } catch (_: Throwable) {
            procLoading = false
        }
    }

    private fun readProcSnapshot(pids: List<Int>): ProcSnapshot? {
        val cmd = buildString {
            append("ps -A -o PID,NAME; echo __HAS_STARTS__;")
            for (p in pids) {
                append("if [ -d /proc/").append(p).append(" ]; then echo \"")
                    .append(p).append("|\$(cat /proc/").append(p)
                    .append("/stat 2>/dev/null)\"; fi;")
            }
        }
        val out = runSu(cmd) ?: return null
        val byName = HashMap<String, MutableSet<Int>>()
        val starts = HashMap<Int, Long>()
        var inStarts = false
        for (raw in out.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line == "__HAS_STARTS__") {
                inStarts = true
                continue
            }
            if (!inStarts) {
                val parts = line.split(Regex("\\s+"), limit = 2)
                if (parts.size != 2) continue
                val pid = parts[0].toIntOrNull() ?: continue
                if (pid <= 0) continue
                val name = parts[1].trim()
                if (name.isEmpty()) continue
                byName.getOrPut(name) { HashSet() }.add(pid)
            } else {
                // 格式：<pid>|</proc/<pid>/stat 原文>
                val bar = line.indexOf('|')
                if (bar <= 0) continue
                val pid = line.substring(0, bar).toIntOrNull() ?: continue
                if (pid <= 0) continue
                val stat = line.substring(bar + 1)
                val close = stat.lastIndexOf(')')
                if (close < 0 || close + 2 >= stat.length) continue
                val rest = stat.substring(close + 2).trim().split(Regex("\\s+"))
                if (rest.size <= 19) continue
                starts[pid] = rest[19].toLongOrNull() ?: continue
            }
        }
        if (byName.isEmpty()) return null
        return ProcSnapshot(byName, starts)
    }

    /** 起 su 跑一条只读命令；没 root / 超时 / 非 0 全部返回 null。 */
    private fun runSu(cmd: String): String? {
        val paths = arrayOf("su", "/system/bin/su", "/system/xbin/su",
            "/debug_ramdisk/su", "/sbin/su", "/data/adb/ksu/bin/su")
        for (path in paths) {
            try {
                val p = ProcessBuilder(path, "-c", cmd).redirectErrorStream(true).start()
                val done = p.waitFor(8, java.util.concurrent.TimeUnit.SECONDS)
                val out = if (done) p.inputStream.bufferedReader().use { it.readText() } else ""
                if (!done) {
                    try { p.destroyForcibly() } catch (_: Throwable) {}
                    return null
                }
                if (p.exitValue() == 0 && out.isNotBlank()) return out
            } catch (_: Throwable) {
                // 该路径不可用，试下一个
            }
        }
        return null
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
                val ei = c.getColumnIndex(CfgProvider.COL_EPOCH)
                val nci = c.getColumnIndex(CfgProvider.COL_NF_COUNT)
                val nli = c.getColumnIndex(CfgProvider.COL_NF_LEG)
                if (pi < 0 || vi < 0 || ai < 0) return null
                while (c.moveToNext()) {
                    val p = c.getString(pi) ?: continue
                    out[p] = ScopeReport(
                        p,
                        c.getInt(vi),
                        if (idi >= 0) c.getInt(idi) else 0,
                        if (bi >= 0) (c.getString(bi) ?: "") else "",
                        if (si >= 0) c.getLong(si) else 0L,
                        if (ei >= 0) (c.getString(ei) ?: "").trim() else "",
                        c.getLong(ai),
                        if (nci >= 0) c.getInt(nci) else -1,
                        if (nli >= 0) (c.getString(nli) ?: "").trim() else ""
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
