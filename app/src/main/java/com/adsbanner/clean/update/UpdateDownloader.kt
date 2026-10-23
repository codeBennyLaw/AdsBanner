package com.adsbanner.clean.update

import android.content.Context
import android.os.Build
import android.util.Log
import com.adsbanner.clean.BuildConfig
import java.io.File

/** Front-end for the resumable asset download; cancellation disconnects pending network reads. */
object UpdateDownloader {
    class Handle internal constructor(internal val cancellation: ReleaseHttp.Cancellation = ReleaseHttp.Cancellation()) {
        internal val cancelled get() = cancellation.cancelled
        fun cancel() = cancellation.cancel()
    }

    fun download(context: Context, url: String, fileName: String,
        assetId: Long, assetSize: Long, assetDigest: String, version: String,
        onProgress: (Int) -> Unit, onComplete: (File) -> Unit, onError: (String) -> Unit): Handle {
        val handle = Handle()
        val app = context.applicationContext
        Thread({
            try {
                val asset = ReleaseAsset(assetId, assetSize, assetDigest, url)
                val file = ReleaseAssetDownloader(ReleaseUpdates.http).download(asset, File(app.cacheDir, "updates"),
                    fileName, handle.cancellation, onProgress) { part ->
                    val info = app.packageManager.getPackageArchiveInfo(part.absolutePath, 0)
                    @Suppress("DEPRECATION")
                    val code = if (Build.VERSION.SDK_INT >= 28) info?.longVersionCode else info?.versionCode?.toLong()
                    check(info?.packageName == app.packageName && code != null && code > BuildConfig.VERSION_CODE &&
                        info.versionName == version) { "安装包版本或包名不符" }
                }
                if (!handle.cancelled) onComplete(file)
            } catch (error: Exception) {
                if (!handle.cancelled) {
                    Log.w("AdsBanner", "更新下载失败: ${error.javaClass.simpleName}")
                    onError(ReleaseHttp.reason(error))
                }
            }
        }, "AdsBanner-update").apply { isDaemon = true }.start()
        return handle
    }
}
