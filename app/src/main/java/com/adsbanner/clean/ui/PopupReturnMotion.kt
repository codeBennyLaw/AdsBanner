package com.adsbanner.clean.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.animation.PathInterpolator
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.adsbanner.clean.R

/** Retract popup-sized contents toward their entry; never morph them into a returning button. */
internal class PopupReturnMotion(
    private val panel: View,
    private val scrim: View,
    origin: Rect,
    private val onEnd: () -> Unit,
    private val originRadius: Float = panel.resources.getDimension(R.dimen.corner_control)
) {
    private val destination = Rect(origin)
    private val initialClip = panel.clipBounds
    private val initialAlpha = panel.alpha
    private val initialScrimAlpha = scrim.alpha
    private val initialX = panel.translationX
    private val initialY = panel.translationY
    private var transform: OriginReturnTransform? = null
    private var attached = false
    private var generation = 0
    private var animator: ValueAnimator? = null
    private var spring: SpringAnimation? = null
    private var previewStart = 0f
    private var lastSeekAt = 0L
    private var progressVelocity = 0f
    var progress = 0f
        private set
    private val movingView get() = panel

    private fun attach() {
        if (attached) return
        attached = true
        transform = OriginReturnTransform(movingView, destination, originRadius)
    }

    fun preview() {
        freeze()
        attach()
        previewStart = progress
        lastSeekAt = SystemClock.uptimeMillis()
        progressVelocity = 0f
    }

    fun seek(progress: Float) {
        val value = previewStart + (1f - previewStart) * progress.coerceIn(0f, 1f)
        val now = SystemClock.uptimeMillis()
        if (now > lastSeekAt) progressVelocity = ((value - this.progress) * 1000f / (now - lastSeekAt)).coerceIn(-8f, 8f)
        lastSeekAt = now
        apply(value)
    }

    fun start() {
        freeze()
        attach()
        val token = generation
        val remaining = 1f - progress
        val durationMs = (RetractionVisuals.DURATION_MS * remaining).toLong().coerceAtLeast(80L)
        val velocity = if (SystemClock.uptimeMillis() - lastSeekAt <= 80L) progressVelocity.coerceAtLeast(0f) else 0f
        val tangent = if (remaining > 0f) (0.2f * velocity * durationMs / 1000f / remaining).coerceIn(0f, 0.8f) else 0f
        animator = ValueAnimator.ofFloat(progress, 1f).apply {
            duration = durationMs
            interpolator = if (progress > 0f) PathInterpolator(0.2f, tangent, 0.2f, 1f)
                else PathInterpolator(0.3f, 0f, 0.4f, 1f)
            addUpdateListener { apply(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { if (generation == token) onEnd() }
            })
            start()
        }
    }

    fun settleBack(completion: () -> Unit) {
        freeze()
        if (progress == 0f) { restore(); completion(); return }
        val token = generation
        spring = SpringAnimation(FloatValueHolder(progress)).apply {
            spring = SpringForce(0f).setDampingRatio(1f).setStiffness(800f)
            setStartVelocity(if (SystemClock.uptimeMillis() - lastSeekAt <= 80L) progressVelocity else 0f)
            setMinimumVisibleChange(0.001f)
            addUpdateListener { _, value, _ -> apply(value.coerceIn(0f, 1f)) }
            addEndListener { _, cancelled, _, _ ->
                if (!cancelled && token == generation) { restore(); completion() }
            }
            start()
        }
    }

    private fun apply(value: Float) {
        progress = value
        val target = movingView
        transform?.apply(value)
        target.alpha = initialAlpha * RetractionVisuals.panelAlpha(value)
        scrim.alpha = initialScrimAlpha * (1f - value)
    }

    private fun freeze() {
        generation++
        animator?.cancel(); animator = null
        spring?.cancel(); spring = null
    }

    private fun restoreProperties() {
        transform?.restore()
        transform = null
        panel.clipBounds = initialClip
        panel.translationX = initialX
        panel.translationY = initialY
        panel.alpha = initialAlpha
    }

    fun restore() {
        freeze()
        attached = false
        restoreProperties()
        scrim.alpha = initialScrimAlpha
        progress = 0f
    }

    /** Closing must not expose the original full popup for a frame. */
    fun cancel() {
        freeze()
        // Keep the live panel at its final pose until the dialog window is removed.
        attached = false
        transform = null
    }
}
