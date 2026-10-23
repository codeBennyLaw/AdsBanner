package com.adsbanner.clean.ui

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.LruCache
import android.widget.ImageView
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import com.adsbanner.clean.R
import java.util.IdentityHashMap
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Visible rows only; icons are rasterized off the main thread and kept in a bounded cache. */
internal class AppIconLoader(
    context: Context,
    private val deliver: (Any, () -> Unit) -> Unit = { _, task -> task() }
) : AutoCloseable {
    private val context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val size = (48 * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
    private val configurationKey = "${context.resources.displayMetrics.densityDpi}:${context.resources.configuration.uiMode}"
    private val executor = ThreadPoolExecutor(2, 2, 5, TimeUnit.SECONDS, LinkedBlockingQueue()) { task ->
        Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); task.run() }, "AdsBanner-icons")
    }.apply { allowCoreThreadTimeOut(true) }
    private class Request(var task: Future<*>? = null)
    private val requests = IdentityHashMap<ImageView, Request>()
    private var closed = false

    fun bind(view: ImageView, pkg: String) {
        cancel(view)
        val key = "$configurationKey:$pkg"
        cache.get(key)?.let { showIcon(view, it); return }
        view.setImageDrawable(null)
        if (closed) return
        val request = Request()
        requests[view] = request
        request.task = executor.submit {
            val bitmap = cache.get(key) ?: runCatching {
                val drawable = context.packageManager.getApplicationIcon(pkg)
                if (Thread.currentThread().isInterrupted) return@submit
                drawable.toBitmap(size, size, Bitmap.Config.ARGB_8888)
            }.getOrNull()?.also { cache.put(key, it) }
            if (!Thread.currentThread().isInterrupted) handler.post {
                deliver(view) {
                    if (!closed && requests[view] === request) {
                        requests.remove(view)
                        if (bitmap != null) showIcon(view, bitmap)
                    }
                }
            }
        }
    }

    private fun showIcon(view: ImageView, bitmap: Bitmap) {
        view.setImageDrawable(RoundedBitmapDrawableFactory.create(view.resources, bitmap).apply {
            cornerRadius = view.resources.getDimension(R.dimen.corner_icon)
            setAntiAlias(true)
            setFilterBitmap(true)
        })
    }

    fun cancel(view: ImageView) {
        val task = requests.remove(view)?.task ?: return
        task.cancel(true)
        (task as? Runnable)?.let { executor.remove(it) }
    }

    override fun close() {
        closed = true
        requests.values.forEach { it.task?.cancel(true) }
        requests.clear()
        executor.shutdownNow()
        handler.removeCallbacksAndMessages(null)
    }

    companion object {
        private val cache = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
        }
    }
}
