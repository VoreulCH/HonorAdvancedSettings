package io.github.voreulch.honoradvanced

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 模块是否真的被 LSPosed 激活 —— 判据是**回执**，不是 binder。
 *
 * <p>数据源只有两条，任一命中即判绿：
 * <ol>
 *   <li>模块注入进程后写下的 {@code persist.sys.has.*} 回执（见 [RECEIPT_PROPS]）；</li>
 *   <li>本进程能加载 {@code de.robv.android.xposed.XposedBridge}（模块没被启用时
 *       LSPosed 不会往普通 App 进程注入这个类）。</li>
 * </ol>
 *
 * <p>三态，严格不猜红：
 * <ul>
 *   <li>{@link Activation#ACTIVE} 绿 —— 读到任一非空回执，或 XposedBridge 可加载；</li>
 *   <li>{@link Activation#INACTIVE} 红 —— 读取成功、一条回执都没有，且 XposedBridge 也加载不到；</li>
 *   <li>{@link Activation#UNCONFIRMED} 黄 —— 只有读取本身出错（{@code SystemProperties}
 *       反射抛异常）才转黄，正常使用看不到。</li>
 * </ul>
 *
 * <p>**每次打开药师跑一遍**：不轮询、不定时器、无后台任务、无 binder 监听。
 */
enum class Activation { UNKNOWN, ACTIVE, INACTIVE, UNCONFIRMED }

object ActivationProbe {

    /**
     * 模块自己写的回执属性（不信任何外部输入）：
     * <ul>
     *   <li>{@code persist.sys.has.framework_epoch} —— MainHook.markSystemServerInjected()
     *       {@code pid:startAt:wallclock}，system_server 每次加载模块时写；</li>
     *   <li>{@code persist.sys.has.sysprobe} —— 同上方法写 {@code 版本名(版本号)|boot_id|epoch}；</li>
     *   <li>{@code persist.sys.has.nofraud} —— MiscHook 写的挂载回执
     *       {@code versionCode|挂载数|来源腿}。</li>
     * </ul>
     * 属性全局可读，不需要 root。
     */
    private val RECEIPT_PROPS = arrayOf(
        "persist.sys.has.framework_epoch",
        "persist.sys.has.sysprobe",
        "persist.sys.has.nofraud"
    )

    /** 首页读它渲染状态行；只在主线程被写。 */
    var state by mutableStateOf(Activation.UNKNOWN)
        private set

    /** 打开药师时调用；同步执行，不建线程、不留定时器。 */
    fun probe() {
        var hit = false
        var readError = false
        try {
            val sp = Class.forName("android.os.SystemProperties")
            val get = sp.getMethod(
                "get", String::class.java, String::class.java
            )
            for (key in RECEIPT_PROPS) {
                val v = get.invoke(null, key, "") as? String
                if (!v.isNullOrBlank()) {
                    hit = true
                    break
                }
            }
        } catch (_: Throwable) {
            readError = true
        }
        if (!hit) hit = bridgeLoaded()
        state = when {
            hit -> Activation.ACTIVE
            readError -> Activation.UNCONFIRMED
            else -> Activation.INACTIVE
        }
    }

    /** 本进程是否拿到 XposedBridge（LSPosed 已对模块启用并注入时才有）。 */
    private fun bridgeLoaded(): Boolean = try {
        Class.forName("de.robv.android.xposed.XposedBridge")
        true
    } catch (_: Throwable) {
        false
    }
}
