package io.github.voreulch.honoradvanced

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * App 侧诊断日志（v3.0.3）。
 *
 * ## 为什么需要它
 *
 * 现有的 [Diag] 是 **hook 侧**日志 —— 它走 `XposedBridge.log`，进的是 LSPosed
 * 自己的 log buffer，只能在被 hook 的进程（systemui / launcher / 安装器）里产生。
 *
 * 而"温控开关关不掉"这类问题发生在**模块 app 自己**身上：它去调 `su`、去写
 * `persist.sys.*`，这些动作 **LSPosed 日志一条都收不到**。实测用户的诊断包里
 * 那 8 条 `setprop` 全是系统自己的 `HwGsmOnsDisplayParamsManager`，我们的零条。
 *
 * 结果就是：用户报"关不掉"，我们只能靠猜。这个类把关键操作留在本地，
 * 导出时一起打包，省掉好几轮来回问。
 *
 * ## 记什么
 *
 * - `su` 的**每条**调用：用了哪个路径、命令、耗时、退出码
 * - 属性读写：`persist.sys.has.hottemp` 写前 / 写后回读
 * - 开关切换：key、目标值、结果
 * - 导出时的设备与 root 环境快照
 *
 * ## 存哪
 *
 * 内存环形缓冲（最近 [MAX_LINES] 条）+ 落盘 `files/applog.txt`（私有目录，无需权限）。
 * 超过 [MAX_FILE] 轮转成 `applog.old.txt`，不会无限涨。
 */
object AppLog {

    private const val MAX_LINES = 400
    private const val MAX_FILE = 512L * 1024L

    private val buf = ArrayDeque<String>()
    private var logFile: File? = null
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    /** 幂等；在 Application/Activity 起来时调一次即可。 */
    fun init(ctx: Context) {
        if (logFile != null) return
        logFile = File(ctx.applicationContext.filesDir, "applog.txt")
    }

    /** 记一条。任何异常都吞掉 —— 日志本身绝不能影响主流程。 */
    @Synchronized
    fun d(tag: String, msg: String) {
        val line = "[${fmt.format(Date())}] $tag | $msg"
        buf.addLast(line)
        while (buf.size > MAX_LINES) buf.removeFirst()
        try {
            val f = logFile ?: return
            if (f.length() > MAX_FILE) {
                val old = File(f.parentFile, "applog.old.txt")
                try { if (old.exists()) old.delete() } catch (_: Throwable) {}
                // renameTo 失败（跨分区 / 权限 / 磁盘满）时文件会继续变大 ——
                // 早先没检查返回值，轮转形同虚设，日志能涨到几百 MB。
                // 失败就退化成"清空重写"，宁可丢历史也不能让文件无限涨。
                if (!f.renameTo(old)) {
                    try { f.writeText("") } catch (_: Throwable) {}
                }
            }
            f.appendText(line + "\n")
        } catch (_: Throwable) {
        }
    }

    /** 内存里的最近若干条（导出用）。 */
    @Synchronized
    fun snapshot(): String = if (buf.isEmpty()) "(无记录)" else buf.joinToString("\n")

    /** 落盘文件的完整内容（跨多次启动的历史）。 */
    @Synchronized
    fun fileText(): String = try {
        logFile?.takeIf { it.exists() }?.readText() ?: "(无文件)"
    } catch (_: Throwable) {
        "(读取失败)"
    }

    @Synchronized
    fun clear() {
        buf.clear()
        try {
            logFile?.delete()
            logFile?.parentFile?.let { File(it, "applog.old.txt").delete() }
        } catch (_: Throwable) {
        }
    }
}
