package com.adsbanner.clean.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.widget.TextView
import android.widget.Toast
import com.adsbanner.clean.BuildConfig
import com.adsbanner.clean.R
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TableAwareMovementMethod
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tables.TableTheme
import io.noties.markwon.ext.tasklist.TaskListPlugin
import io.noties.markwon.image.DefaultDownScalingMediaDecoder
import io.noties.markwon.image.ImagesPlugin
import io.noties.markwon.image.destination.ImageDestinationProcessor
import io.noties.markwon.linkify.LinkifyPlugin
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.time.Instant

/**
 * 通过 GitHub Release 检查更新。
 *
 * 仓库是私有的，请求需要携带凭据。用的是细粒度 Personal Access Token，
 * 只勾了 Metadata 的只读权限 —— 能读 Release 与仓库公开信息，改不了代码、动不了 Issue。
 *
 * 凭据由构建时从 local.properties 注入 BuildConfig，随 APK 一起分发：
 * 应用没有服务端，检查更新只能直连 api.github.com，所以 token 必须打进包里；
 * 但不能写进源码 —— GitHub 的推送保护会直接拒绝包含有效 token 的提交。
 */
object ReleaseUpdates {

    private const val OWNER = "codeBennyLaw"
    private const val REPO = "AdsBanner"

    private const val API = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"

    const val REPOSITORY_URL = "https://github.com/$OWNER/$REPO"

    private val TOKEN: String get() = BuildConfig.GITHUB_TOKEN

    private val notesRepository = ReleaseNotesRepository(SystemClock::elapsedRealtime) { page, etag ->
        val connection = (URL("$REPOSITORY_API/releases?per_page=${ReleaseNotesRepository.PAGE_SIZE}&page=$page")
            .openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("Authorization", "Bearer $TOKEN")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "AdsBanner")
            etag?.let { setRequestProperty("If-None-Match", it) }
        }
        try {
            when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_NOT_MODIFIED -> ReleaseNotesRepository.Response(
                    etag = connection.getHeaderField("ETag"), notModified = true)
                HttpURLConnection.HTTP_OK -> ReleaseNotesRepository.Response(
                    page = parseNotes(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }, page),
                    etag = connection.getHeaderField("ETag"))
                else -> error("服务返回 $code")
            }
        } finally { connection.disconnect() }
    }

    private const val REPOSITORY_API = "https://api.github.com/repos/$OWNER/$REPO"

    /** Blocking; the dialog requests subsequent pages only while it remains open. */
    internal fun releaseNotes(page: Int = 1): ReleaseNotesRepository.NotesPage {
        check(TOKEN.isNotBlank()) { "构建时未注入仓库凭据" }
        return notesRepository.load(page)
    }

    private fun parseNotes(body: String, page: Int): ReleaseNotesRepository.NotesPage {
        val releases = JSONArray(body)
        val notes = buildList {
            for (index in 0 until releases.length()) {
                val release = releases.optJSONObject(index) ?: continue
                if (release.optBoolean("draft")) continue
                val version = release.optString("tag_name").ifBlank { release.optString("name") }
                if (version.isBlank()) continue
                add(ReleaseNotesRepository.ReleaseNote(version, release.optString("body"), release.optLong("id"),
                    runCatching { Instant.parse(release.optString("published_at")) }.getOrNull()))
            }
        }
        // Use the raw page size: filtered drafts must not hide older published releases.
        return ReleaseNotesRepository.NotesPage(notes,
            if (releases.length() == ReleaseNotesRepository.PAGE_SIZE) page + 1 else null)
    }

    sealed interface Result {
        data class New(
            val version: String,
            val title: String,
            val notes: String,
            val downloadUrl: String?,
            val pageUrl: String
        ) : Result

        data object Current : Result

        data class Failed(val reason: String) : Result
    }

    /** 阻塞请求，必须放在工作线程调用 */
    fun check(currentVersion: String): Result {
        if (TOKEN.isBlank()) return Result.Failed("构建时未注入仓库凭据")

        val connection = runCatching {
            (URL(API).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("Authorization", "Bearer $TOKEN")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                setRequestProperty("User-Agent", "AdsBanner")
            }
        }.getOrElse { return Result.Failed("无法建立连接") }

        return try {
            when (val code = connection.responseCode) {
                200 -> {
                    val body = connection.inputStream.bufferedReader().use { it.readText() }
                    parse(JSONObject(body), currentVersion)
                }
                // 还没有发布过 Release，视为最新
                404 -> Result.Current
                else -> Result.Failed("服务返回 $code")
            }
        } catch (t: Throwable) {
            Result.Failed(t.message ?: "网络异常")
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    private fun parse(json: JSONObject, currentVersion: String): Result {
        val version = json.optString("tag_name").removePrefix("v")
        if (!isNewer(version, currentVersion)) return Result.Current

        val expected = "AdsBanner-release-v-${version.replace('.', '-')}.apk"
        val assets = json.optJSONArray("assets")
        var downloadUrl: String? = null
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                if (asset.optString("name") == expected) {
                    downloadUrl = asset.optString("browser_download_url").ifBlank { null }
                    break
                }
            }
        }

        return Result.New(
            version = version,
            title = json.optString("name").ifBlank { version },
            notes = json.optString("body"),
            downloadUrl = downloadUrl,
            pageUrl = json.optString("html_url").ifBlank { "$REPOSITORY_URL/releases" }
        )
    }

    /** 只有明确拿到比本机更高的版本，才认为有更新 */
    internal fun isNewer(remote: String, local: String): Boolean {
        val remoteParts = versionParts(remote) ?: return false
        val localParts = versionParts(local) ?: return false
        for (i in 0 until maxOf(remoteParts.size, localParts.size)) {
            val a = remoteParts.getOrElse(i) { 0 }
            val b = localParts.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    private fun versionParts(value: String): List<Int>? {
        val parts = value.trim().removePrefix("v").split('.')
        if (parts.isEmpty() || parts.size > 4) return null
        return parts.map { it.toIntOrNull() ?: return null }
    }

    /** Shared complete Markdown for update prompts and history; retain the view's themed context only. */
    fun renderMarkdown(context: Context, target: TextView, markdown: String) {
        val accent = context.getColor(R.color.brand)
        val line = context.getColor(R.color.divider)
        val instance = Markwon.builder(context)
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(TablePlugin.create(TableTheme.buildWithDefaults(context)
                .tableBorderColor(line).tableHeaderRowBackgroundColor(line)
                .tableCellPadding((context.resources.displayMetrics.density * 6).toInt()).build()))
            .usePlugin(TaskListPlugin.create(context))
            .usePlugin(LinkifyPlugin.create())
            .usePlugin(ImagesPlugin.create { plugin ->
                val metrics = context.resources.displayMetrics
                plugin.defaultMediaDecoder(DefaultDownScalingMediaDecoder.create(context.resources,
                    metrics.widthPixels, metrics.heightPixels))
            })
            .usePlugin(object : AbstractMarkwonPlugin() {
                override fun configureTheme(builder: MarkwonTheme.Builder) {
                    builder.linkColor(accent).blockQuoteColor(accent)
                        .codeBackgroundColor(line).codeBlockBackgroundColor(line)
                        .headingTextSizeMultipliers(floatArrayOf(1.5f, 1.3f, 1.15f, 1.1f, 1f, 1f))
                }

                override fun configureConfiguration(builder: MarkwonConfiguration.Builder) {
                    builder.linkResolver { view, destination ->
                        val link = resolveLink(destination) ?: return@linkResolver
                        runCatching { view.context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) }
                            .onFailure { Toast.makeText(view.context, "暂时无法打开链接", Toast.LENGTH_SHORT).show() }
                    }
                    builder.imageDestinationProcessor(object : ImageDestinationProcessor() {
                        override fun process(destination: String): String {
                            if (destination.startsWith("data:image/")) return destination
                            return resolveLink(destination)?.takeIf {
                                it.startsWith("https://") || it.startsWith("http://")
                            }.orEmpty()
                        }
                    })
                }
            })
            .build()
        target.movementMethod = TableAwareMovementMethod.create()
        target.setLinkTextColor(accent)
        instance.setMarkdown(
            target,
            markdown.ifBlank { "该版本没有填写更新说明。" }
        )
    }

    private fun resolveLink(destination: String): String? = runCatching {
        URI("$REPOSITORY_URL/").resolve(destination).takeIf {
            it.scheme?.lowercase() in setOf("https", "http", "mailto")
        }?.toString()
    }.getOrNull()
}
