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

/** Keep first-read placeholders stable, then reveal laid-out content through a single overlay. */
internal class LocalPageLoading(
    private val activity: AppCompatActivity,
    private val content: View,
    private val overlay: View,
    private val message: String,
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
    var isComplete = false
        private set

    init {
        content.visibility = View.INVISIBLE
        overlay.visibility = View.VISIBLE
        label.text = message
        activity.lifecycle.addObserver(this)
        Motion.whenPageReady(activity, this) { beginCycle() }
    }

    private fun animationsEnabled() = ValueAnimator.areAnimatorsEnabled() && Settings.Global.getFloat(
        activity.contentResolver, Settings.Global.TRANSITION_ANIMATION_SCALE, 1f) > 0f

    private fun beginCycle() {
        if (closed || stopped || begun || revealing || isComplete) return
        begun = true
        if (!animationsEnabled()) { cycleDone = true; requestReveal(); return }
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
                    if (dataReady) requestReveal() else { begun = false; beginCycle() }
                }
            })
            start()
        }
    }

    fun ready() {
        if (closed || isComplete) return
        dataReady = true
        requestReveal()
    }

    private fun requestReveal() {
        if (closed || stopped || isComplete || revealing || !cycleDone || !dataReady) return
        Motion.whenPageReady(activity, this) {
            if (closed || stopped || revealing || isComplete) return@whenPageReady
            revealing = true
            animator?.cancel()
            animator = null
            content.visibility = View.VISIBLE
            // The real content is measured behind an opaque overlay before its first visible frame.
            layoutWait = content.doOnPreDraw {
                layoutWait = null
                if (!closed) reveal()
            }
            content.invalidate()
        }
    }

    private fun reveal() {
        if (!animationsEnabled()) { finish(); return }
        val ticket = generation
        animator = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 180L
            interpolator = PathInterpolator(0.2f, 0f, 0.1f, 1f)
            addUpdateListener { overlay.alpha = it.animatedValue as Float }
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
        overlay.alpha = 1f
        onContentReady()
    }

    fun failed(text: String, retryWork: () -> Unit) {
        if (closed || isComplete) return
        Motion.whenPageReady(activity, this) {
            if (closed || isComplete) return@whenPageReady
            generation++
            animator?.cancel()
            animator = null
            layoutWait?.removeListener()
            layoutWait = null
            revealing = false
            dataReady = false
            content.visibility = View.INVISIBLE
            overlay.alpha = 1f
            dots.visibility = View.GONE
            label.text = text
            label.setTextColor(activity.getColor(R.color.danger))
            retry.visibility = View.VISIBLE
            retry.setOnClickListener {
                retry.visibility = View.GONE
                dots.visibility = View.VISIBLE
                dots.phase = 0f
                label.text = message
                label.setTextColor(activity.getColor(R.color.text_secondary))
                begun = false
                cycleDone = false
                dataReady = false
                Motion.whenPageReady(activity, this) { beginCycle(); retryWork() }
            }
        }
    }

    override fun onDestroy(owner: LifecycleOwner) = close()

    override fun onStop(owner: LifecycleOwner) {
        stopped = true
        if (!revealing && !isComplete) {
            generation++
            animator?.cancel()
            animator = null
            begun = false
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        stopped = false
        Motion.whenPageReady(activity, this) {
            if (cycleDone && dataReady) requestReveal() else beginCycle()
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
        activity.lifecycle.removeObserver(this)
    }
}
