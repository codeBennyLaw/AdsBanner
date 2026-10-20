package com.adsbanner.clean.update

import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 在应用内下载 Release 里的 APK。
 *
 * 下载地址取 Release asset 的 browser_download_url，仓库目前是公开的，
 * 因此不需要再带凭据；接口凭据只用于 api.github.com，不跟到下载重定向。
 *
 * 文件落在 cacheDir/updates，先写 .part 再改名，避免中断后留下半截安装包。
 */
object UpdateDownloader {

    private const val TAG = "AdsBanner"
    private const val TIMEOUT_CONNECT = 15_000
    private const val TIMEOUT_READ = 30_000
    private const val BUFFER_SIZE = 8 * 1024
    private const val MIN_APK_BYTES = 1024L

    class Handle {
        @Volatile
        internal var cancelled = false

        fun cancel() {
            cancelled = true
        }
    }

    fun download(
        context: Context,
        url: String,
        fileName: String,
        onProgress: (Int) -> Unit,
        onComplete: (File) -> Unit,
        onError: (String) -> Unit
    ): Handle {
        val handle = Handle()
        val appContext = context.applicationContext

        Thread {
            var connection: HttpURLConnection? = null
            try {
                val dir = File(appContext.cacheDir, "updates").apply { mkdirs() }
                val target = File(dir, fileName)
                val temp = File(dir, "$fileName.part")
                temp.delete()

                connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = TIMEOUT_CONNECT
                    readTimeout = TIMEOUT_READ
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "AdsBanner")
                }

                val code = connection.responseCode
                if (code !in 200..299) {
                    onError("服务返回 $code")
                    return@Thread
                }

                val total = connection.contentLengthLong
                connection.inputStream.use { input ->
                    temp.outputStream().use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var written = 0L
                        var lastPercent = -1
                        while (true) {
                            if (handle.cancelled) {
                                temp.delete()
                                return@Thread
                            }
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            written += read
                            if (total > 0) {
                                val percent = (written * 100 / total).toInt().coerceIn(0, 100)
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    onProgress(percent)
                                }
                            }
                        }
                    }
                }

                if (handle.cancelled) {
                    temp.delete()
                    return@Thread
                }
                if (temp.length() < MIN_APK_BYTES) {
                    temp.delete()
                    onError("安装包内容不完整")
                    return@Thread
                }

                target.delete()
                if (!temp.renameTo(target)) {
                    temp.delete()
                    onError("保存安装包失败")
                    return@Thread
                }
                onProgress(100)
                onComplete(target)
            } catch (t: Throwable) {
                Log.w(TAG, "下载更新失败: ${t.message}")
                onError(t.message ?: "网络异常")
            } finally {
                runCatching { connection?.disconnect() }
            }
        }.apply { isDaemon = true }.start()

        return handle
    }
}
