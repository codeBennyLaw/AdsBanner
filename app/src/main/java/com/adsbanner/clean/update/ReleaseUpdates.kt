package com.adsbanner.clean.update

import android.content.Context
import android.widget.TextView
import com.adsbanner.clean.BuildConfig
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin
import io.noties.markwon.linkify.LinkifyPlugin
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

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

    private var markwon: Markwon? = null

    /** Release 正文按 Markdown 渲染，支持标题、列表、表格、任务清单、删除线与链接 */
    fun renderMarkdown(context: Context, target: TextView, markdown: String) {
        val instance = markwon ?: Markwon.builder(context)
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(TablePlugin.create(context))
            .usePlugin(TaskListPlugin.create(context))
            .usePlugin(LinkifyPlugin.create())
            .build()
            .also { markwon = it }

        instance.setMarkdown(
            target,
            markdown.ifBlank { "该版本没有填写更新说明。" }
        )
    }
}
