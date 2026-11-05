package com.adsbanner.clean.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Rect
import android.os.Build
import android.view.View
import android.view.animation.PathInterpolator
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.adsbanner.clean.R
import androidx.core.graphics.ColorUtils

/** Retract popup-sized contents toward their entry; never morph them into a returning button. */
internal class PopupReturnMotion(
    private val panel: View,
    private val scrim: View,
    origin: Rect,
    private val onEnd: () -> Unit,
    private val originRadius: Float = panel.resources.getDimension(R.dimen.corner_control),
    private val originColor: Int = panel.context.getColor(R.color.surface)
) {
    private val destination = Rect(origin)
    private val initialClip = panel.clipBounds
    private val initialAlpha = panel.alpha
    private val initialScrimAlpha = scrim.alpha
    private val initialX = panel.translationX
    private val initialY = panel.translationY
    private val panelColor = panel.context.getColor(R.color.surface)
    private var transform: OriginReturnTransform? = null
    private var surface: PopupRenderSurface? = null
    private var attached = false
    private var generation = 0
    private var animator: ValueAnimator? = null
    private var spring: SpringAnimation? = null
    private var previewStart = 0f
    var progress = 0f
        private set
    private val movingView get() = if (Build.VERSION.SDK_INT >= 29) surface?.view ?: panel else panel

    private fun attach() {
        if (attached) return
        attached = true
        if (Build.VERSION.SDK_INT >= 29) surface = PopupRenderSurface.capture(panel)
        transform = OriginReturnTransform(movingView, destination, originRadius)
    }

    fun preview() {
        freeze()
        attach()
        previewStart = progress
    }

    fun seek(progress: Float) = apply(previewStart + (1f - previewStart) * progress.coerceIn(0f, 1f))

    fun start() {
        freeze()
        attach()
        val token = generation
        animator = ValueAnimator.ofFloat(progress, 1f).apply {
            duration = (300L * (1f - progress)).toLong().coerceAtLeast(80L)
            interpolator = if (progress > 0f) PathInterpolator(0.2f, 0.6f, 0.2f, 1f)
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
        if (Build.VERSION.SDK_INT >= 29) surface?.apply {
            view.setBackgroundColor(ColorUtils.blendARGB(panelColor, originColor, motionFraction(0f, 0.95f, value)))
            setContentAlpha(1f - motionFraction(0.3f, 0.9f, value))
        }
        target.alpha = initialAlpha * (1f - motionFraction(0.9f, 1f, value))
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
        if (Build.VERSION.SDK_INT >= 29) surface?.release(restore = true)
        surface = null; attached = false
        restoreProperties()
        scrim.alpha = initialScrimAlpha
        progress = 0f
    }

    /** Closing must not expose the original full popup for a frame. */
    fun cancel() {
        freeze()
        if (Build.VERSION.SDK_INT >= 29 && surface != null) surface?.release(restore = false)
        else restoreProperties()
        surface = null; attached = false
        transform = null
    }
}
