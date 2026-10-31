package io.github.voreulch.honoradvanced

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/** 远端最新 release。 */
data class UpdateInfo(
    val versionName: String,
    val versionCode: Int,
    /** APK 资产直链（浏览器打开即开始下载）；找不到资产时为空。 */
    val apkUrl: String,
    /** release 页面（没有 APK 资产时的退路）。 */
    val pageUrl: String
)

sealed class UpdateResult {
    data class Newer(val info: UpdateInfo) : UpdateResult()
    object Latest : UpdateResult()
    data class Failed(val message: String) : UpdateResult()
}

/**
 * 检查更新（v2.3.0）。
 *
 * <p>只用 GitHub REST 的 `releases/latest`：tag 由发布脚本写成 `versionCode-versionName`
 * （例如 `69-2.2.9`），拿 versionCode 直接比大小最可靠 —— 不依赖语义版本字符串解析。
 *
 * <p>网络失败要能说清是哪一种失败（超时 / DNS / HTTP 码），因为用户点这个按钮时
 * 期待的就是一句明确的话，而不是"检查失败"四个字。
 */
object UpdateChecker {

    private const val API =
        "https://api.github.com/repos/VoreulCH/HonorAdvancedSettings/releases/latest"

    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 8_000

    private val TAG_RE = Regex("^(\\d+)-(.+)$")
    private val SEMVER_RE = Regex("^v?(\\d+(?:\\.\\d+)*)$")

    private val main = Handler(Looper.getMainLooper())

    /** 异步检查；回调在主线程。 */
    fun check(localVersionCode: Int, onResult: (UpdateResult) -> Unit) {
        Thread {
            val r = try {
                val info = fetch()
                if (info.versionCode > localVersionCode) UpdateResult.Newer(info)
                else UpdateResult.Latest
            } catch (t: Throwable) {
                UpdateResult.Failed(describe(t))
            }
            main.post {
                try {
                    onResult(r)
                } catch (t: Throwable) {
                    // UI 回调抛异常会直接干掉主线程，这里兜一层
                    android.util.Log.e("HAS-Update", "callback failed", t)
                }
            }
        }.apply { isDaemon = true; name = "HAS-Update" }.start()
    }

    private fun fetch(): UpdateInfo {
        val conn = (URL(API).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "HonorAdvancedSettings")
        }
        try {
            val code = conn.responseCode
            if (code != 200) {
                throw IllegalStateException(
                    if (code == 404) "远端还没有发布记录" else "GitHub 返回 HTTP $code"
                )
            }
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val tag = json.optString("tag_name")
            val pageUrl = json.optString("html_url")

            var versionCode = 0
            var versionName = tag
            // tag 不符合契约时 versionName 会留空，别让界面显示 "发现新版本 v"

            TAG_RE.matchEntire(tag)?.let {
                versionCode = it.groupValues[1].toIntOrNull() ?: 0
                versionName = it.groupValues[2]
            } ?: SEMVER_RE.matchEntire(tag)?.let {
                versionName = it.groupValues[1]
            }

            var apkUrl = ""
            val assets = json.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.optJSONObject(i) ?: continue
                    if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                        apkUrl = a.optString("browser_download_url")
                        break
                    }
                }
            }
            return UpdateInfo(versionName.ifBlank { tag }, versionCode, apkUrl, pageUrl)
        } finally {
            try { conn.disconnect() } catch (_: Throwable) {}
        }
    }

    private fun describe(t: Throwable): String = when (t) {
        is SocketTimeoutException -> "连接超时，请检查网络后重试"
        is UnknownHostException -> "无法连接 GitHub（DNS 解析失败）"
        is ConnectException -> "连接 GitHub 失败，请检查网络"
        is javax.net.ssl.SSLException -> "安全连接失败，请检查网络环境"
        else -> t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
    }
}
