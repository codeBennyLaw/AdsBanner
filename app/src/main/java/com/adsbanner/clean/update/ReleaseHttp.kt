package com.adsbanner.clean.update

import java.io.IOException
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/** Shared GitHub transport. Credentials and conditional headers never follow a redirect. */
internal class ReleaseHttp(
    private val token: () -> String,
    private val connect: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    private val pause: (Long) -> Unit = Thread::sleep
) {
    class Failure(val status: Int) : IOException("GitHub HTTP $status")
    class Cancellation {
        @Volatile var cancelled = false
            private set
        private var connection: HttpURLConnection? = null
        @Synchronized fun attach(value: HttpURLConnection) {
            check()
            connection = value
        }
        @Synchronized fun detach(value: HttpURLConnection) {
            if (connection === value) connection = null
        }
        fun cancel() {
            val active = synchronized(this) {
                cancelled = true
                connection.also { connection = null }
            }
            runCatching { active?.disconnect() }
        }
        fun check() {
            if (cancelled || Thread.currentThread().isInterrupted) throw InterruptedIOException("已取消")
        }
    }
    data class Text(val body: String?, val etag: String?, val notModified: Boolean)

    fun open(address: String, accept: String, headers: Map<String, String> = emptyMap(),
        cancellation: Cancellation = Cancellation()): HttpURLConnection {
        var url = URL(address)
        repeat(5) { hop ->
            cancellation.check()
            require(url.protocol == "https" && url.userInfo == null) { "更新地址无效" }
            val connection = connect(url)
            try {
                cancellation.attach(connection)
                connection.connectTimeout = 8_000
                connection.readTimeout = if (accept == BINARY) 20_000 else 12_000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("Accept", accept)
                connection.setRequestProperty("User-Agent", "AdsBanner")
                if (hop == 0 && url.host.equals("api.github.com", true)) {
                    token().takeIf { it.isNotBlank() }?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
                    connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                }
                headers.forEach { (key, value) ->
                    if (hop == 0 || key.equals("Range", true)) connection.setRequestProperty(key, value)
                }
                val status = connection.responseCode
                cancellation.check()
                if (status in REDIRECTS) {
                    url = URL(url, connection.getHeaderField("Location") ?: throw IOException("缺少下载地址"))
                } else {
                    if (status !in 200..299 && !(status == 304 && hop == 0 && "If-None-Match" in headers)) throw Failure(status)
                    return connection
                }
            } catch (error: Exception) {
                cancellation.detach(connection)
                connection.disconnect()
                throw error
            }
            cancellation.detach(connection)
            connection.disconnect()
        }
        throw IOException("下载跳转次数过多")
    }

    fun text(address: String, etag: String? = null): Text {
        repeat(2) { attempt ->
            val connection = try {
                open(address, JSON, etag?.let { mapOf("If-None-Match" to it) }.orEmpty())
            } catch (error: IOException) {
                if (attempt == 1 || !retryable(error)) throw error
                pause(250L)
                return@repeat
            }
            try {
                val unchanged = connection.responseCode == 304
                val body = if (unchanged) null else connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                Cancellation().check()
                return Text(body, connection.getHeaderField("ETag"), unchanged)
            } catch (error: IOException) {
                if (attempt == 1 || !retryable(error)) throw error
            } finally { connection.disconnect() }
            pause(250L)
        }
        throw IOException("无法获取更新信息")
    }

    companion object {
        const val JSON = "application/vnd.github+json"
        const val BINARY = "application/octet-stream"
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)
        fun retryable(error: IOException) = !Thread.currentThread().isInterrupted &&
            (error !is InterruptedIOException || error is SocketTimeoutException) &&
            (error !is Failure || error.status in setOf(408, 429, 500, 502, 503, 504))
        fun reason(error: Throwable) = when (error) {
            is SocketTimeoutException -> "连接 GitHub 超时，请检查网络后重试"
            is Failure -> when (error.status) {
                401, 403 -> "暂时无法访问更新，请稍后重试"
                404 -> "该版本的安装包暂时不可用"
                429 -> "请求过于频繁，请稍后重试"
                else -> "更新服务暂时不可用（${error.status}）"
            }
            is java.net.UnknownHostException, is java.net.ConnectException -> "无法连接 GitHub，请检查网络后重试"
            else -> error.message ?: "网络异常，请重试"
        }
    }
}
