package com.adsbanner.clean.update

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** Per-page conditional cache. A changed first page invalidates shifted later pages. */
internal class ReleaseNotesRepository(
    private val now: () -> Long,
    private val restore: () -> Map<Int, Saved> = { emptyMap() },
    private val persist: (Map<Int, Saved>) -> Unit = {},
    private val fetch: (page: Int, etag: String?) -> Response
) {
    companion object {
        const val PAGE_SIZE = 100
        private const val CACHE_MS = 2 * 60 * 1000L
    }

    data class ReleaseNote(val version: String, val notes: String, val id: Long, val publishedAt: Instant?)
    data class NotesPage(val notes: List<ReleaseNote>, val nextPage: Int?)
    data class Response(val page: NotesPage? = null, val etag: String? = null, val notModified: Boolean = false)
    data class Saved(val page: NotesPage, val etag: String?)
    private data class Cached(val page: NotesPage, val etag: String?, val loadedAt: Long?)
    private val cache = ConcurrentHashMap<Int, Cached>()
    private val fetchLock = Any()
    private var restored = false

    @Synchronized private fun restoreOnce() {
        if (restored) return
        runCatching(restore).getOrDefault(emptyMap()).forEach { (number, saved) ->
            if (number > 0) cache[number] = Cached(saved.page, saved.etag, null)
        }
        restored = true
    }

    /** Local reads never wait for an in-flight network refresh. Call on an IO thread for disk restoration. */
    fun cached(page: Int): NotesPage? {
        require(page > 0)
        restoreOnce()
        return cache[page]?.page
    }

    fun load(page: Int): NotesPage {
        require(page > 0)
        restoreOnce()
        synchronized(fetchLock) {
            val time = now()
            val previous = cache[page]
            if (previous?.loadedAt != null && time - previous.loadedAt in 0 until CACHE_MS) return previous.page
            val response = fetch(page, previous?.etag)
            val result = if (response.notModified) checkNotNull(previous).page else checkNotNull(response.page)
            if (page == 1 && previous != null && result != previous.page) cache.clear()
            cache[page] = Cached(result,
                if (response.notModified) response.etag ?: previous?.etag else response.etag, now())
            runCatching { persist(cache.mapValues { Saved(it.value.page, it.value.etag) }) }
            return result
        }
    }
}
