package com.adsbanner.clean.ui

import android.view.View

/** Keep asynchronous UI deliveries out of the shared element's drawing frames. Main thread only. */
internal class PageTransitionWork(private val view: View, private var entering: Boolean) {
    private val pending = LinkedHashMap<Any, () -> Unit>()
    private var returning = false
    private var closed = false
    private val release = Runnable { completeImmediately() }

    fun submit(key: Any, task: () -> Unit) {
        if (closed || returning) return
        if (entering) pending[key] = task else task()
    }

    fun completeEnter() {
        if (!entering || closed || returning) return
        view.removeCallbacks(release)
        view.postOnAnimation(release)
    }

    fun completeImmediately() {
        view.removeCallbacks(release)
        if (closed || returning) return
        entering = false
        val tasks = pending.values.toList()
        pending.clear()
        tasks.forEach { if (!closed && !returning) it() }
    }

    fun beginReturn() {
        returning = true
        view.removeCallbacks(release)
        pending.clear()
    }

    fun close() {
        closed = true
        view.removeCallbacks(release)
        pending.clear()
    }
}
