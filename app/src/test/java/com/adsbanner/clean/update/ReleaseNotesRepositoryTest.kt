package com.adsbanner.clean.update

import com.adsbanner.clean.update.ReleaseNotesRepository.NotesPage
import com.adsbanner.clean.update.ReleaseNotesRepository.ReleaseNote
import com.adsbanner.clean.update.ReleaseNotesRepository.Response
import org.junit.Assert.*
import org.junit.Test

class ReleaseNotesRepositoryTest {
    private fun page(version: String, next: Int? = null) =
        NotesPage(listOf(ReleaseNote(version, "完整说明", version.hashCode().toLong(), null)), next)

    @Test fun reopeningWithinTwoMinutesReusesEachPage() {
        var calls = 0
        val repository = ReleaseNotesRepository({ 0L }) { number, _ ->
            calls++
            Response(page("v$number"), "tag$number")
        }
        val first = repository.load(1)
        val second = repository.load(2)
        assertSame(first, repository.load(1))
        assertSame(second, repository.load(2))
        assertEquals(2, calls)
    }

    @Test fun expiryRevalidatesWithEtagAndKeepsItAfter304() {
        var now = 0L
        val tags = mutableListOf<String?>()
        val repository = ReleaseNotesRepository({ now }) { _, tag ->
            tags += tag
            if (tag == null) Response(page("v2.0.1"), "first") else Response(notModified = true)
        }
        val first = repository.load(1)
        now = 120_000
        assertSame(first, repository.load(1))
        now = 240_000
        assertSame(first, repository.load(1))
        assertEquals(listOf(null, "first", "first"), tags)
    }

    @Test fun changedFirstPageDropsShiftedLaterPages() {
        var now = 0L
        val requests = mutableListOf<Pair<Int, String?>>()
        val repository = ReleaseNotesRepository({ now }) { number, tag ->
            requests += number to tag
            Response(page("v$now.$number", if (number == 1) 2 else null), "tag$now.$number")
        }
        repository.load(1)
        repository.load(2)
        now = 120_000
        repository.load(1)
        repository.load(2)
        assertEquals(listOf(1 to null, 2 to null, 1 to "tag0.1", 2 to null), requests)
    }

    @Test fun equalFirstPagePreservesLaterEtags() {
        var now = 0L
        val requests = mutableListOf<Pair<Int, String?>>()
        val repository = ReleaseNotesRepository({ now }) { number, tag ->
            requests += number to tag
            Response(page("v$number"), "tag$number")
        }
        repository.load(1)
        repository.load(2)
        now = 120_000
        repository.load(1)
        repository.load(2)
        assertEquals(2 to "tag2", requests.last())
    }

    @Test fun failureDoesNotPoisonCacheOrAdvanceRetry() {
        var now = 0L
        var fail = false
        val tags = mutableListOf<String?>()
        val repository = ReleaseNotesRepository({ now }) { _, tag ->
            tags += tag
            if (fail) error("offline")
            if (tag == null) Response(page("v2.0.1"), "first") else Response(notModified = true)
        }
        val first = repository.load(1)
        now = 120_000
        fail = true
        assertThrows(IllegalStateException::class.java) { repository.load(1) }
        fail = false
        assertSame(first, repository.load(1))
        assertEquals(listOf(null, "first", "first"), tags)
    }

    @Test fun invalidPagesNeverReachNetwork() {
        val repository = ReleaseNotesRepository({ 0L }) { _, _ -> error("must not fetch") }
        assertThrows(IllegalArgumentException::class.java) { repository.load(0) }
        assertThrows(IllegalArgumentException::class.java) { repository.load(-1) }
    }

    @Test fun unexpected304WithoutCachedBodyIsRejected() {
        val repository = ReleaseNotesRepository({ 0L }) { _, _ -> Response(notModified = true) }
        assertThrows(IllegalStateException::class.java) { repository.load(1) }
    }

    @Test fun cachedNotesSurviveAProcessRestartAndAreRevalidated() {
        var disk: Map<Int, ReleaseNotesRepository.Saved> = emptyMap()
        val first = ReleaseNotesRepository({ 0L }, persist = { disk = it }) { _, _ -> Response(page("v2.0.1"), "saved") }
        val expected = first.load(1)
        var requests = 0
        val restarted = ReleaseNotesRepository({ 0L }, restore = { disk }) { _, etag ->
            requests++
            assertEquals("saved", etag)
            Response(notModified = true)
        }
        assertEquals(expected, restarted.cached(1))
        assertEquals(0, requests)
        assertEquals(expected, restarted.load(1))
        assertEquals(1, requests)
    }

    @Test fun offlineRefreshKeepsTheSavedDirectory() {
        val saved = mapOf(1 to ReleaseNotesRepository.Saved(page("v2.0.1"), "old"))
        val repository = ReleaseNotesRepository({ 0L }, restore = { saved }) { _, _ -> error("offline") }
        assertEquals(saved[1]!!.page, repository.cached(1))
        assertThrows(IllegalStateException::class.java) { repository.load(1) }
        assertEquals(saved[1]!!.page, repository.cached(1))
    }

    @Test fun cachedReadsDoNotWaitForAStalledNetworkRefresh() {
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val saved = mapOf(1 to ReleaseNotesRepository.Saved(page("v2.0.1"), "old"))
        val repository = ReleaseNotesRepository({ 0L }, restore = { saved }) { _, _ ->
            started.countDown()
            check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
            Response(notModified = true)
        }
        val worker = Thread { repository.load(1) }.apply { start() }
        val localReader = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            assertTrue(started.await(2, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(saved[1]!!.page, localReader.submit<NotesPage?> { repository.cached(1) }
                .get(500, java.util.concurrent.TimeUnit.MILLISECONDS))
        } finally { release.countDown(); worker.join(2000); localReader.shutdownNow() }
    }

    @Test fun changedFirstPageAlsoRemovesObsoletePagesFromDisk() {
        var disk = mapOf(1 to ReleaseNotesRepository.Saved(page("v1", 2), "one"),
            2 to ReleaseNotesRepository.Saved(page("v0"), "two"))
        val repository = ReleaseNotesRepository({ 0L }, restore = { disk }, persist = { disk = it }) { _, _ ->
            Response(page("v2"), "new")
        }
        repository.load(1)
        assertEquals(setOf(1), disk.keys)
        val restarted = ReleaseNotesRepository({ 0L }, restore = { disk }) { _, _ -> error("offline") }
        assertNull(restarted.cached(2))
        assertEquals("v2", restarted.cached(1)!!.notes.single().version)
    }
}
