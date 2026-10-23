package com.adsbanner.clean.update

import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant

/** Only release text and ETags are stored; request headers and access tokens stay out of the cache. */
internal class ReleaseNotesDiskCache(file: File) {
    private val storage = AtomicFile(file)
    fun read(): Map<Int, ReleaseNotesRepository.Saved> = runCatching {
        val pages = JSONObject(String(storage.readFully(), Charsets.UTF_8))
        buildMap {
            for (key in pages.keys()) {
                val number = key.toIntOrNull()?.takeIf { it > 0 } ?: continue
                val page = pages.getJSONObject(key)
                val items = page.getJSONArray("notes")
                val notes = (0 until items.length()).map { index ->
                    val item = items.getJSONObject(index)
                    ReleaseNotesRepository.ReleaseNote(item.getString("version"), item.getString("notes"), item.getLong("id"),
                        item.optString("publishedAt").takeIf { it.isNotBlank() }?.let(Instant::parse))
                }
                val next = page.optInt("next").takeIf { it > number }
                put(number, ReleaseNotesRepository.Saved(ReleaseNotesRepository.NotesPage(notes, next),
                    page.optString("etag").takeIf { it.isNotBlank() }))
            }
        }
    }.getOrDefault(emptyMap())

    fun write(pages: Map<Int, ReleaseNotesRepository.Saved>) {
        val json = JSONObject()
        pages.forEach { (number, saved) ->
            val items = JSONArray()
            saved.page.notes.forEach { note -> items.put(JSONObject().put("version", note.version).put("notes", note.notes)
                .put("id", note.id).put("publishedAt", note.publishedAt?.toString().orEmpty())) }
            json.put(number.toString(), JSONObject().put("notes", items).put("next", saved.page.nextPage ?: 0)
                .put("etag", saved.etag.orEmpty()))
        }
        val output = storage.startWrite()
        try {
            output.write(json.toString().toByteArray(Charsets.UTF_8))
            storage.finishWrite(output)
        } catch (error: Exception) {
            storage.failWrite(output)
            throw error
        }
    }
}
