package com.adsbanner.clean.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.provider.Settings
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.OneShotPreDrawListener
import androidx.core.view.doOnPreDraw
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.adsbanner.clean.R

/** A short, inline placeholder for one data region; surrounding controls stay usable. */
internal class LocalPageLoading(
    private val activity: AppCompatActivity,
    private val content: View?,
    private val overlay: View,
    private val message: String,
    private val layoutHost: View = content ?: overlay,
    private val revealTargets: () -> List<View> = { listOfNotNull(content) },
    private val schedule: (Any, () -> Unit) -> Unit = { key, task -> Motion.whenPageReady(activity, key, task) },
    navigationHost: View? = null,
    private val onContentReady: () -> Unit = {}
) : DefaultLifecycleObserver, AutoCloseable {
    private val dots = overlay.findViewById<LocalLoadingDots>(R.id.loadingDots)
    private val label = overlay.findViewById<TextView>(R.id.loadingLabel)
    private val retry = overlay.findViewById<View>(R.id.loadingRetry)
    private var animator: ValueAnimator? = null
    private var layoutWait: OneShotPreDrawListener? = null
    private var generation = 0
    private var begun = false
    private var cycleDone = false
    private var dataReady = false
    private var revealing = false
    private var closed = false
    private var stopped = false
    private var binding = false
    private var pendingBind: (() -> Unit)? = null
    private var failedState = false
    private var notified = false
    private var navigationPaused = false
    private var regionActive = true
    private val motionHosts = listOfNotNull(activity.findViewById<View>(android.R.id.content), navigationHost).distinct()
    private var fadingViews = emptyList<View>()
    var isComplete = false
        private set

    init {
        content?.visibility = View.INVISIBLE
        overlay.visibility = View.VISIBLE
        label.text = message
        motionHosts.forEach { host -> regions(host).add(this) }
        activity.lifecycle.addObserver(this)
        schedule(this) { beginCycle() }
    }

    private fun animationsEnabled() = ValueAnimator.areAnimatorsEnabled() && Settings.Global.getFloat(
        activity.contentResolver, Settings.Global.TRANSITION_ANIMATION_SCALE, 1f) > 0f

    private fun beginCycle() {
        if (closed || stopped || navigationPaused || !regionActive || failedState || binding || begun || revealing || isComplete) return
        begun = true
        if (!animationsEnabled()) {
            cycleDone = true
            if (pendingBind != null) applyPendingBind() else requestReveal()
            return
        }
        val ticket = generation
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 280L
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { dots.phase = it.animatedValue as Float }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (closed || generation != ticket) return
                    animator = null
                    cycleDone = true
                    when {
                        pendingBind != null -> applyPendingBind()
                        dataReady -> requestReveal()
                        else -> { begun = false; beginCycle() }
                    }
                }
            })
            start()
        }
    }

    fun ready() {
        if (closed || failedState || isComplete) return
        dataReady = true
        binding = false
        requestReveal()
    }

    fun bind(action: () -> Unit) {
        if (closed) return
        if (failedState) resetFailure()
        if (isComplete) {
            schedule(this) { if (!closed) action() }
            return
        }
        if (revealing || binding) { action(); return }
        pendingBind = action
        if (cycleDone && animator == null) applyPendingBind()
    }

    private fun applyPendingBind() {
        schedule(this) {
            if (closed || stopped || navigationPaused || !regionActive) return@schedule
            val action = pendingBind ?: return@schedule
            pendingBind = null
            binding = true
            action()
        }
    }

    private fun requestReveal() {
        if (closed || stopped || navigationPaused || !regionActive || isComplete || revealing || !cycleDone || !dataReady) return
        schedule(this) {
            if (closed || stopped || navigationPaused || !regionActive || revealing || isComplete) return@schedule
            revealing = true
            animator?.cancel()
            animator = null
            content?.visibility = View.VISIBLE
            overlay.visibility = View.GONE
            // Remove only this placeholder, then fade the newly laid-out data, never the page.
            layoutWait = layoutHost.doOnPreDraw {
                layoutWait = null
                if (!closed) {
                    fadingViews = revealTargets()
                    fadingViews.forEach { it.alpha = 0f }
                    reveal()
                }
            }
            layoutHost.invalidate()
        }
    }

    private fun reveal() {
        if (navigationPaused || !regionActive || stopped || closed) return
        if (!animationsEnabled()) { finish(); return }
        val ticket = generation
        animator = ValueAnimator.ofFloat(fadingViews.firstOrNull()?.alpha ?: 0f, 1f).apply {
            duration = 180L
            interpolator = PathInterpolator(0.2f, 0f, 0.1f, 1f)
            addUpdateListener { value -> fadingViews.forEach { it.alpha = value.animatedValue as Float } }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { if (!closed && generation == ticket) finish() }
            })
            start()
        }
    }

    private fun finish() {
        animator = null
        isComplete = true
        overlay.visibility = View.GONE
        fadingViews.forEach { it.alpha = 1f }
        fadingViews = emptyList()
        notifyContentReady()
    }

    private fun notifyContentReady() {
        if (!closed && !stopped && isComplete && !notified) {
            notified = true
            onContentReady()
        }
    }

    fun failed(text: String, retryWork: () -> Unit) {
        if (closed || isComplete) return
        schedule(this) {
            if (closed || isComplete) return@schedule
            generation++
            failedState = true
            animator?.cancel()
            animator = null
            layoutWait?.removeListener()
            layoutWait = null
            revealing = false
            dataReady = false
            binding = false
            pendingBind = null
            fadingViews.forEach { it.alpha = 1f }
            fadingViews = emptyList()
            content?.visibility = View.INVISIBLE
            overlay.visibility = View.VISIBLE
            overlay.alpha = 1f
            dots.visibility = View.GONE
            label.text = text
            label.setTextColor(activity.getColor(R.color.danger))
            retry.visibility = View.VISIBLE
            retry.setOnClickListener {
                resetFailure()
                schedule(this) { beginCycle(); retryWork() }
            }
        }
    }

    private fun resetFailure() {
        retry.visibility = View.GONE
        failedState = false
        dots.visibility = View.VISIBLE
        dots.phase = 0f
        label.text = message
        label.setTextColor(activity.getColor(R.color.text_secondary))
        begun = false
        cycleDone = false
        dataReady = false
        schedule(this) { beginCycle() }
    }

    override fun onDestroy(owner: LifecycleOwner) = close()

    override fun onStop(owner: LifecycleOwner) {
        stopped = true
        if (revealing) animator?.pause()
        if (!revealing && !isComplete) {
            generation++
            animator?.cancel()
            animator = null
            begun = false
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        stopped = false
        resumeAfterNavigation()
        if (isComplete) { notifyContentReady(); return }
        schedule(this) {
            when {
                cycleDone && pendingBind != null -> applyPendingBind()
                cycleDone && dataReady -> requestReveal()
                else -> beginCycle()
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        generation++
        animator?.cancel()
        animator = null
        layoutWait?.removeListener()
        layoutWait = null
        pendingBind = null
        // Keep the visible pose: a containing popup may capture it for its exit transition.
        fadingViews = emptyList()
        motionHosts.forEach { host -> regions(host).remove(this) }
        activity.lifecycle.removeObserver(this)
    }

    fun pauseForNavigation() {
        if (closed || isComplete) return
        navigationPaused = true
        animator?.pause()
    }

    fun resumeAfterNavigation() {
        navigationPaused = false
        resumeRegion()
    }

    fun setActive(active: Boolean) {
        regionActive = active
        if (active) resumeRegion() else animator?.pause()
    }

    private fun resumeRegion() {
        if (closed || stopped || navigationPaused || !regionActive || isComplete) return
        if (revealing && animator == null && layoutWait == null) reveal() else animator?.resume()
        if (animator == null && !revealing) schedule(this) {
            when {
                cycleDone && pendingBind != null -> applyPendingBind()
                cycleDone && dataReady -> requestReveal()
                else -> beginCycle()
            }
        }
    }

    companion object {
        @Suppress("UNCHECKED_CAST")
        private fun regions(host: View): MutableSet<LocalPageLoading> =
            (host.getTag(R.id.motion_local_loading) as? MutableSet<LocalPageLoading>)
                ?: mutableSetOf<LocalPageLoading>().also { host.setTag(R.id.motion_local_loading, it) }

        fun pause(host: View) { regions(host).toList().forEach { it.pauseForNavigation() } }
        fun resume(host: View) { regions(host).toList().forEach { it.resumeAfterNavigation() } }
    }
}
