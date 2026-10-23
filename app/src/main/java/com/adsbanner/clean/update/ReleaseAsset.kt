package com.adsbanner.clean.update

import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.security.MessageDigest

internal data class ReleaseAsset(val id: Long, val size: Long, val sha256: String, val browserUrl: String) {
    init {
        require(id > 0 && size in 1..MAX_SIZE && sha256.matches(Regex("[a-fA-F0-9]{64}"))) { "安装包信息不完整" }
    }
    val apiUrl get() = "https://api.github.com/repos/codeBennyLaw/AdsBanner/releases/assets/$id"
    companion object { const val MAX_SIZE = 100L * 1024 * 1024 }
}

/** Retry the asset endpoint with Range; use the public download route if it remains unavailable. */
internal class ReleaseAssetDownloader(private val http: ReleaseHttp, private val pause: (Long) -> Unit = Thread::sleep) {
    fun download(asset: ReleaseAsset, directory: File, fileName: String, cancellation: ReleaseHttp.Cancellation,
        progress: (Int) -> Unit, validate: (File) -> Unit): File {
        require(fileName == File(fileName).name && fileName.endsWith(".apk")) { "安装包文件名无效" }
        check(directory.isDirectory || directory.mkdirs()) { "无法创建下载目录" }
        val target = File(directory, fileName)
        val part = File.createTempFile("release-", ".part.apk", directory)
        var lastPercent = -1
        fun report(value: Int) {
            if (lastPercent != value) { lastPercent = value; progress(value) }
        }
        try {
            var attempt = 0
            while (part.length() < asset.size) {
                cancellation.check()
                val address = if (attempt < 2 || asset.browserUrl.isBlank()) asset.apiUrl else asset.browserUrl
                try {
                    receive(asset, address, part, cancellation) { received -> report((received * 100 / asset.size).toInt().coerceAtMost(99)) }
                    if (part.length() != asset.size) throw EOFException("安装包未下载完整")
                } catch (error: IOException) {
                    cancellation.check()
                    // A final read may fail after the entire file was written; integrity is checked below.
                    if (part.length() == asset.size) break
                    val denied = error is ReleaseHttp.Failure && error.status in setOf(401, 403, 404)
                    if (attempt >= 2 || (!denied && !ReleaseHttp.retryable(error))) throw error
                    attempt = if (denied) 2 else attempt + 1
                    pause(250L * attempt)
                }
            }
            cancellation.check()
            val digest = MessageDigest.getInstance("SHA-256")
            part.inputStream().buffered().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    cancellation.check()
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            check(actual.equals(asset.sha256, true)) { "安装包校验失败，请重新下载" }
            validate(part)
            cancellation.check()
            if (target.exists() && !target.delete()) throw IOException("无法替换旧安装包")
            if (!part.renameTo(target)) throw IOException("保存安装包失败")
            report(100)
            return target
        } finally { part.delete() }
    }

    private fun receive(asset: ReleaseAsset, address: String, part: File, cancellation: ReleaseHttp.Cancellation,
        progress: (Long) -> Unit) {
        val offset = part.length()
        val connection = http.open(address, ReleaseHttp.BINARY,
            if (offset > 0) mapOf("Range" to "bytes=$offset-") else emptyMap(), cancellation)
        try {
            val start = if (connection.responseCode == HttpURLConnection.HTTP_PARTIAL) {
                val range = RANGE.matchEntire(connection.getHeaderField("Content-Range").orEmpty())
                    ?: throw IOException("下载续传信息无效")
                val first = range.groupValues[1].toLongOrNull()
                val last = range.groupValues[2].toLongOrNull()
                val total = range.groupValues[3].toLongOrNull()
                if (first != offset || last == null || last < offset || last >= asset.size || total != asset.size)
                    throw IOException("下载续传位置不匹配")
                offset
            } else {
                if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("下载响应无效")
                0L // Servers which ignore Range must restart, never append a second copy.
            }
            val length = connection.contentLengthLong
            if (length > asset.size - start) throw IOException("安装包大小不符")
            connection.inputStream.use { input ->
                RandomAccessFile(part, "rw").use { output ->
                    output.setLength(start)
                    output.seek(start)
                    var received = start
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        cancellation.check()
                        val count = input.read(buffer)
                        if (count < 0) break
                        received += count
                        if (received > asset.size) throw IOException("安装包大小不符")
                        output.write(buffer, 0, count)
                        progress(received)
                    }
                }
            }
        } finally {
            cancellation.detach(connection)
            connection.disconnect()
        }
    }
    companion object { private val RANGE = Regex("bytes (\\d+)-(\\d+)/(\\d+)") }
}
