package com.adsbanner.clean.ui

import android.view.View
import android.os.SystemClock
import com.adsbanner.clean.R

/** Keep asynchronous UI deliveries out of the shared element's drawing frames. Main thread only. */
internal class PageTransitionWork(private val view: View, private var entering: Boolean) {
    private val pending = LinkedHashMap<Any, () -> Unit>()
    private var returning = false
    private var closed = false
    private var transitionRunning = false
    private var previewing = false
    private var draining = false
    private val drain = Runnable { drainFrame() }
    private val release = Runnable {
        if (!transitionRunning && !previewing && !closed && !returning) {
            entering = false
            draining = true
            drainFrame()
        }
    }

    fun beginEnter() {
        if (closed || returning) return
        entering = true
        transitionRunning = true
        view.removeCallbacks(release)
        view.removeCallbacks(drain)
    }

    fun endEnter() {
        transitionRunning = false
        completeEnter()
    }

    fun submit(key: Any, task: () -> Unit) {
        if (closed || returning) return
        if (entering || previewing || draining) pending[key] = task else task()
    }

    fun completeEnter() {
        if (!entering || transitionRunning || previewing || closed || returning) return
        view.removeCallbacks(release)
        view.postOnAnimation(release)
    }

    fun completeImmediately() {
        view.removeCallbacks(release)
        view.removeCallbacks(drain)
        draining = false
        if (closed || returning) return
        transitionRunning = false
        entering = false
        previewing = false
        LocalPageLoading.resume(view)
        val tasks = pending.values.toList()
        pending.clear()
        tasks.forEach { if (!closed && !returning) it() }
    }

    fun beginReturn() {
        returning = true
        LocalPageLoading.pause(view)
        view.removeCallbacks(release)
        view.removeCallbacks(drain)
        draining = false
        pending.clear()
    }

    fun beginPreview() {
        if (closed || returning) return
        previewing = true
        LocalPageLoading.pause(view)
        view.removeCallbacks(release)
        view.removeCallbacks(drain)
    }

    fun endPreview() {
        if (closed || returning) return
        previewing = false
        LocalPageLoading.resume(view)
        if (entering) completeEnter() else {
            draining = true
            view.postOnAnimation(drain)
        }
    }

    private fun drainFrame() {
        if (closed || returning || entering || previewing) return
        val started = SystemClock.uptimeMillis()
        var delivered = 0
        while (pending.isNotEmpty() && !closed && !returning && !entering && !previewing) {
            val entry = pending.entries.iterator().next()
            val task = entry.value
            pending.remove(entry.key)
            task()
            delivered++
            if (delivered >= 4 || SystemClock.uptimeMillis() - started >= 4L) break
        }
        draining = pending.isNotEmpty()
        if (draining && !closed && !returning && !entering && !previewing) view.postOnAnimation(drain)
    }

    fun close() {
        closed = true
        view.removeCallbacks(release)
        view.removeCallbacks(drain)
        draining = false
        pending.clear()
    }
}
