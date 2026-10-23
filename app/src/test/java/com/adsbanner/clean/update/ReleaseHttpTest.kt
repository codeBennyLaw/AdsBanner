package com.adsbanner.clean.update

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.ArrayDeque

internal class FakeReleaseConnection(address: URL, private val status: Int = 200,
    val headers: Map<String, String> = emptyMap(), private val payload: () -> InputStream = { ByteArrayInputStream(byteArrayOf()) },
    private val length: Long = -1) : HttpURLConnection(address) {
    var disconnected = false
    override fun connect() {}
    override fun disconnect() { disconnected = true }
    override fun usingProxy() = false
    override fun getResponseCode() = status
    override fun getHeaderField(name: String) = headers[name]
    override fun getInputStream() = payload()
    override fun getContentLengthLong() = length
}

class ReleaseHttpTest {
    private val api = "https://api.github.com/repos/example/app/releases/latest"
    @Test fun publicRequestsNeverAuthenticateAndOnlyRangeFollowsRedirects() {
        val connections = mutableListOf<FakeReleaseConnection>()
        val http = ReleaseHttp({ url ->
            FakeReleaseConnection(url, if (connections.isEmpty()) 302 else 200,
                mapOf("Location" to "https://release-assets.githubusercontent.com/file.apk")).also { connections += it }
        })
        val final = http.open(api, ReleaseHttp.BINARY, mapOf("If-None-Match" to "cached", "Range" to "bytes=100-"))
        assertTrue(connections.all { it.getRequestProperty("Authorization") == null })
        assertNull(final.getRequestProperty("Authorization"))
        assertNull(final.getRequestProperty("If-None-Match"))
        assertEquals("bytes=100-", final.getRequestProperty("Range"))
        assertTrue(connections[0].disconnected)
        final.disconnect()
    }
    @Test fun credentialsAreNeverSentToTheBrowserRoute() {
        val http = ReleaseHttp({ FakeReleaseConnection(it) })
        val response = http.open("https://github.com/example/app/file.apk", ReleaseHttp.BINARY)
        assertNull(response.getRequestProperty("Authorization"))
    }
    @Test fun rejectsInsecureRedirectsAndClosesTheFirstConnection() {
        val first = FakeReleaseConnection(URL(api), 302, mapOf("Location" to "http://example.com/file"))
        val http = ReleaseHttp({ first })
        assertThrows(IllegalArgumentException::class.java) { http.open(api, ReleaseHttp.BINARY) }
        assertTrue(first.disconnected)
    }
    @Test fun metadataReadTimeoutRetriesAndDisconnectsBothAttempts() {
        val first = FakeReleaseConnection(URL(api), payload = { throw SocketTimeoutException("read") })
        val second = FakeReleaseConnection(URL(api), headers = mapOf("ETag" to "new"), payload = { "{}".byteInputStream() })
        val queue = ArrayDeque(listOf(first, second))
        val http = ReleaseHttp({ queue.removeFirst() }, {})
        assertEquals("{}", http.text(api).body)
        assertTrue(first.disconnected && second.disconnected)
        assertTrue(queue.isEmpty())
    }
    @Test fun nonTransientHttpFailuresAreNotRetried() {
        var calls = 0
        val http = ReleaseHttp({ calls++; FakeReleaseConnection(it, 403) }, {})
        assertEquals(403, assertThrows(ReleaseHttp.Failure::class.java) { http.text(api) }.status)
        assertEquals(1, calls)
    }
    @Test fun anonymousRateLimitsAreReportedWithoutImmediateRetries() {
        for (status in listOf(403, 429)) {
            var calls = 0
            val http = ReleaseHttp({ calls++; FakeReleaseConnection(it, status, mapOf("X-RateLimit-Remaining" to "0")) }, {})
            val error = assertThrows(ReleaseHttp.Failure::class.java) { http.text(api) }
            assertTrue(error.rateLimited)
            assertTrue(ReleaseHttp.reason(error).contains("访问次数已达上限"))
            assertEquals(1, calls)
        }
    }
    @Test fun conditional304DoesNotReadTheBody() {
        val response = FakeReleaseConnection(URL(api), 304, payload = { error("must not read") })
        val http = ReleaseHttp({ response })
        assertTrue(http.text(api, "previous").notModified)
        assertEquals("previous", response.getRequestProperty("If-None-Match"))
        assertTrue(response.disconnected)
    }
    @Test fun cancellationDisconnectsTheActiveConnectionAndPreventsNewRequests() {
        val response = FakeReleaseConnection(URL(api))
        val http = ReleaseHttp({ response })
        val cancellation = ReleaseHttp.Cancellation()
        http.open(api, ReleaseHttp.BINARY, cancellation = cancellation)
        cancellation.cancel()
        assertTrue(response.disconnected)
        assertThrows(InterruptedIOException::class.java) { http.open(api, ReleaseHttp.BINARY, cancellation = cancellation) }
        assertFalse(ReleaseHttp.retryable(InterruptedIOException("cancelled")))
        assertTrue(ReleaseHttp.retryable(SocketTimeoutException("slow")))
    }
    @Test fun redirectLoopsHaveABound() {
        var calls = 0
        val http = ReleaseHttp({ calls++; FakeReleaseConnection(it, 302, mapOf("Location" to api)) })
        assertThrows(IOException::class.java) { http.open(api, ReleaseHttp.BINARY) }
        assertEquals(5, calls)
    }
}
