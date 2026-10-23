package com.adsbanner.clean.ui

import android.os.Handler
import android.os.Looper
import android.os.Process
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Latest request wins; leaving a page cancels its work and pending UI deliveries. */
internal class UiWorker : AutoCloseable {
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); task.run() }, "AdsBanner-ui")
    }
    private var pending: Future<*>? = null
    private var generation = 0
    private var closed = false

    fun <T> submit(work: () -> T, apply: (Result<T>) -> Unit) {
        if (closed) return
        val ticket = ++generation
        pending?.cancel(true)
        pending = executor.submit {
            val result = runCatching(work)
            if (!Thread.currentThread().isInterrupted) handler.post {
                if (!closed && generation == ticket) apply(result)
            }
        }
    }

    override fun close() {
        closed = true
        generation++
        pending?.cancel(true)
        executor.shutdownNow()
        handler.removeCallbacksAndMessages(null)
    }
}
