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
import kotlin.math.abs
import kotlin.math.roundToInt

/** Retract popup-sized contents toward their entry; never morph them into a returning button. */
internal class PopupReturnMotion(
    private val panel: View,
    private val scrim: View,
    origin: Rect,
    private val onEnd: () -> Unit
) {
    private val width = panel.width
    private val height = panel.height
    private val clip = Rect(0, 0, width, height)
    private val initialClip = panel.clipBounds
    private val initialAlpha = panel.alpha
    private val initialScrimAlpha = scrim.alpha
    private val initialX = panel.translationX
    private val initialY = panel.translationY
    private val location = IntArray(2).also(panel::getLocationOnScreen)
    private val dx = origin.exactCenterX() - (location[0] + width / 2f)
    private val dy = origin.exactCenterY() - (location[1] + height / 2f)
    private val vertical = abs(dy) / height >= abs(dx) / width
    private val positive = if (vertical) dy >= 0f else dx >= 0f
    private val travel = if (vertical) {
        (if (positive) origin.top - location[1] else origin.bottom - location[1] - height).toFloat()
    } else {
        (if (positive) origin.left - location[0] else origin.right - location[0] - width).toFloat()
    }
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
            duration = (220L * (1f - progress)).toLong().coerceAtLeast(80L)
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
        val retract = value * 0.75f
        clip.set(0, 0, width, height)
        if (vertical) {
            if (positive) clip.bottom = (height * (1f - retract)).roundToInt()
            else clip.top = (height * retract).roundToInt()
            target.translationX = initialX + dx * retract
            target.translationY = initialY + travel * retract
        } else {
            if (positive) clip.right = (width * (1f - retract)).roundToInt()
            else clip.left = (width * retract).roundToInt()
            target.translationX = initialX + travel * retract
            target.translationY = initialY + dy * retract
        }
        target.clipBounds = clip
        val fade = ((value - 0.15f) / 0.85f).coerceIn(0f, 1f)
        target.alpha = initialAlpha * (1f - fade * fade * (3f - 2f * fade))
        scrim.alpha = initialScrimAlpha * (1f - value)
    }

    private fun freeze() {
        generation++
        animator?.cancel(); animator = null
        spring?.cancel(); spring = null
    }

    private fun restoreProperties() {
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
    }
}
