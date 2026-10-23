package com.adsbanner.clean.update

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.security.MessageDigest

class ReleaseAssetDownloaderTest {
    @get:Rule val folder = TemporaryFolder()
    private val data = ByteArray(2048) { (it % 251).toByte() }
    private fun asset(bytes: ByteArray = data) = ReleaseAsset(12, bytes.size.toLong(),
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) },
        "https://github.com/example/app/releases/download/v2/app.apk")
    private fun partial(): InputStream = object : InputStream() {
        private var sent = false
        override fun read(): Int = error("use bulk read")
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            if (sent) throw SocketTimeoutException("interrupted transfer")
            data.copyInto(bytes, offset, 0, 700)
            sent = true
            return 700
        }
    }
    @Test fun interruptedDownloadsResumeFromTheWrittenOffset() {
        val connections = mutableListOf<FakeReleaseConnection>()
        val http = ReleaseHttp({ "token" }, { url ->
            (if (connections.isEmpty()) FakeReleaseConnection(url, payload = ::partial)
            else FakeReleaseConnection(url, 206, mapOf("Content-Range" to "bytes 700-2047/2048"),
                { ByteArrayInputStream(data, 700, data.size - 700) })).also { connections += it }
        })
        val progress = mutableListOf<Int>()
        var validated = false
        val result = ReleaseAssetDownloader(http, {}).download(asset(), folder.root, "app.apk", ReleaseHttp.Cancellation(),
            { progress += it }) { validated = true; assertArrayEquals(data, it.readBytes()) }
        assertArrayEquals(data, result.readBytes())
        assertEquals("bytes=700-", connections[1].getRequestProperty("Range"))
        assertTrue(connections.all { it.disconnected })
        assertTrue(validated)
        assertEquals(100, progress.last())
        assertTrue(progress.dropLast(1).all { it < 100 })
        assertEquals(listOf("app.apk"), folder.root.list()!!.toList())
    }
    @Test fun aServerIgnoringRangeRestartsRatherThanAppending() {
        var attempts = 0
        val http = ReleaseHttp({ "" }, { url ->
            if (attempts++ == 0) FakeReleaseConnection(url, payload = ::partial)
            else FakeReleaseConnection(url, payload = { data.inputStream() })
        })
        val result = ReleaseAssetDownloader(http, {}).download(asset(), folder.root, "app.apk", ReleaseHttp.Cancellation(), {}) {}
        assertArrayEquals(data, result.readBytes())
        assertEquals(2, attempts)
    }
    @Test fun aDeniedApiRouteFallsBackWithoutSendingCredentials() {
        val connections = mutableListOf<FakeReleaseConnection>()
        val http = ReleaseHttp({ "token" }, { url ->
            FakeReleaseConnection(url, if (connections.isEmpty()) 403 else 200, payload = { data.inputStream() })
                .also { connections += it }
        })
        ReleaseAssetDownloader(http, {}).download(asset(), folder.root, "app.apk", ReleaseHttp.Cancellation(), {}) {}
        assertEquals("github.com", connections[1].url.host)
        assertNull(connections[1].getRequestProperty("Authorization"))
    }
    @Test fun aWrongResumePositionCannotProduceAnInstallableFile() {
        var attempts = 0
        val http = ReleaseHttp({ "" }, { url ->
            if (attempts++ == 0) FakeReleaseConnection(url, payload = ::partial)
            else FakeReleaseConnection(url, 206, mapOf("Content-Range" to "bytes 0-2047/2048"), { data.inputStream() })
        })
        assertThrows(IOException::class.java) {
            ReleaseAssetDownloader(http, {}).download(asset(), folder.root, "app.apk", ReleaseHttp.Cancellation(), {}) { error("must not validate") }
        }
        assertTrue(folder.root.list()!!.isEmpty())
    }
    @Test fun truncatedFilesCannotBeCompletedAndRetriesAreBounded() {
        var attempts = 0
        val http = ReleaseHttp({ "" }, { url -> attempts++; FakeReleaseConnection(url, payload = { data.copyOf(600).inputStream() }) })
        assertThrows(IOException::class.java) {
            ReleaseAssetDownloader(http, {}).download(asset(), folder.root, "app.apk", ReleaseHttp.Cancellation(), {}) { error("must not validate") }
        }
        assertEquals(3, attempts)
        assertTrue(folder.root.list()!!.isEmpty())
    }
    @Test fun digestOrApkValidationFailureDoesNotReplaceAnExistingFile() {
        val original = folder.newFile("app.apk").apply { writeText("previous") }
        val http = ReleaseHttp({ "" }, { FakeReleaseConnection(it, payload = { data.inputStream() }) })
        val progress = mutableListOf<Int>()
        assertThrows(IllegalStateException::class.java) {
            ReleaseAssetDownloader(http, {}).download(asset().copy(sha256 = "0".repeat(64)), folder.root, "app.apk",
                ReleaseHttp.Cancellation(), { progress += it }) { error("must not validate") }
        }
        assertEquals("previous", original.readText())
        assertFalse(100 in progress)
        assertThrows(IllegalStateException::class.java) {
            ReleaseAssetDownloader(http, {}).download(asset(), folder.root, "app.apk", ReleaseHttp.Cancellation(), {}) { error("wrong package") }
        }
        assertEquals("previous", original.readText())
    }
    @Test fun cancellationCleansThePartialFileAndDoesNotReportSuccess() {
        val cancellation = ReleaseHttp.Cancellation()
        val http = ReleaseHttp({ "" }, { FakeReleaseConnection(it, payload = { data.inputStream() }) })
        assertThrows(IOException::class.java) {
            ReleaseAssetDownloader(http, {}).download(asset(), folder.root, "app.apk", cancellation,
                { cancellation.cancel() }) { error("must not validate") }
        }
        assertTrue(folder.root.list()!!.isEmpty())
    }
}
