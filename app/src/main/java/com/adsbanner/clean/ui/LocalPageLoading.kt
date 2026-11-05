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
    private val onContentReady: () -> Unit = {},
    private val onRevealProgress: (Float) -> Unit = {}
) : DefaultLifecycleObserver, AutoCloseable {
    private val dots = overlay.findViewById<LocalLoadingDots>(R.id.loadingDots)
    private val label = overlay.findViewById<TextView>(R.id.loadingLabel)
    private val retry = overlay.findViewById<View>(R.id.loadingRetry)
    private var animator: ValueAnimator? = null
    private var pulse: ValueAnimator? = null
    private var revealProgress = 0f
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
        onRevealProgress(0f)
        overlay.visibility = View.VISIBLE
        label.text = message
        motionHosts.forEach { host -> regions(host).add(this) }
        activity.lifecycle.addObserver(this)
        schedule(this) { beginCycle() }
    }

    private fun animationsEnabled() = ValueAnimator.areAnimatorsEnabled() && Settings.Global.getFloat(
        activity.contentResolver, Settings.Global.TRANSITION_ANIMATION_SCALE, 1f) > 0f

    private fun beginCycle() {
        if (closed || stopped || !regionActive || failedState || begun || isComplete) return
        begun = true
        if (!animationsEnabled()) {
            cycleDone = true
            advanceReady()
            return
        }
        val ticket = generation
        pulse = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 280L
            repeatCount = ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { dots.phase = it.animatedValue as Float }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationRepeat(animation: Animator) {
                    if (closed || generation != ticket) return
                    cycleDone = true
                    advanceReady()
                }
            })
            start()
        }
        if (cycleDone) advanceReady()
    }

    private fun advanceReady() {
        if (navigationPaused || revealing || binding || closed || stopped || !regionActive) return
        if (pendingBind != null) applyPendingBind() else if (dataReady) requestReveal()
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
        if (cycleDone) applyPendingBind()
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
            // Hide data before making its region visible; newly attached rows use the same progress.
            fadingViews = revealTargets()
            applyReveal(0f)
            content?.visibility = View.VISIBLE
            // Both views share the data region; trade their opacity without an empty frame.
            layoutWait = layoutHost.doOnPreDraw {
                layoutWait = null
                if (!closed) {
                    fadingViews = revealTargets()
                    applyReveal(revealProgress)
                    reveal()
                }
            }
            layoutHost.invalidate()
        }
    }

    private fun applyReveal(progress: Float) {
        revealProgress = progress
        overlay.alpha = 1f - progress
        fadingViews.forEach { it.alpha = progress }
        onRevealProgress(progress)
    }

    private fun reveal() {
        if (navigationPaused || !regionActive || stopped || closed) return
        if (!animationsEnabled()) { finish(); return }
        val ticket = generation
        animator = ValueAnimator.ofFloat(revealProgress, 1f).apply {
            duration = (240L * (1f - revealProgress)).toLong().coerceAtLeast(1L)
            interpolator = PathInterpolator(0.2f, 0f, 0.3f, 1f)
            addUpdateListener { value -> applyReveal(value.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { if (!closed && generation == ticket) finish() }
            })
            start()
        }
    }

    private fun finish() {
        animator = null
        isComplete = true
        pulse?.cancel()
        pulse = null
        applyReveal(1f)
        overlay.visibility = View.GONE
        overlay.alpha = 1f
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
            pulse?.cancel()
            pulse = null
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
        pulse?.pause()
        animator?.pause()
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
        pulse?.cancel()
        pulse = null
        // Keep the final visible state until its owning window is removed.
        fadingViews = emptyList()
        motionHosts.forEach { host -> regions(host).remove(this) }
        activity.lifecycle.removeObserver(this)
    }

    fun pauseForNavigation() {
        if (closed || isComplete) return
        navigationPaused = true
        // Defer model/layout delivery, while existing visible animations keep running.
    }

    fun resumeAfterNavigation() {
        navigationPaused = false
        resumeRegion()
    }

    fun setActive(active: Boolean) {
        regionActive = active
        if (active) resumeRegion() else { pulse?.pause(); animator?.pause() }
    }

    private fun resumeRegion() {
        if (closed || stopped || navigationPaused || !regionActive || isComplete) return
        pulse?.resume()
        if (revealing && animator == null && layoutWait == null) reveal() else animator?.resume()
        if (!revealing) schedule(this) {
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
