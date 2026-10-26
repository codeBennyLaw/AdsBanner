package com.adsbanner.clean.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Rect
import android.view.View
import android.view.animation.PathInterpolator
import kotlin.math.abs
import kotlin.math.roundToInt

/** Retract the existing popup's contents, without morphing them into an entry button. */
internal class PopupReturnMotion(
    private val panel: View,
    private val scrim: View,
    origin: Rect,
    private val onEnd: () -> Unit
) {
    private val clip = Rect(0, 0, panel.width, panel.height)
    private val initialClip = panel.clipBounds
    private val initialAlpha = panel.alpha
    private val initialScrimAlpha = scrim.alpha
    private val initialX = panel.translationX
    private val initialY = panel.translationY
    private val location = IntArray(2).also(panel::getLocationOnScreen)
    private val dx = origin.exactCenterX() - (location[0] + panel.width / 2f)
    private val dy = origin.exactCenterY() - (location[1] + panel.height / 2f)
    private val vertical = abs(dy) / panel.height >= abs(dx) / panel.width
    private val positive = if (vertical) dy >= 0f else dx >= 0f
    private val travel = if (vertical) {
        (if (positive) origin.top - location[1] else origin.bottom - location[1] - panel.height).toFloat()
    } else {
        (if (positive) origin.left - location[0] else origin.right - location[0] - panel.width).toFloat()
    }
    private var cancelled = false
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 220L
        interpolator = PathInterpolator(0.3f, 0f, 0.4f, 1f)
        addUpdateListener {
            val progress = it.animatedValue as Float
            // Keep text at its original size. The popup slides behind its origin-facing edge,
            // then disappears while it is still a popup-sized fragment, never a tiny button.
            val retract = progress * 0.75f
            clip.set(0, 0, panel.width, panel.height)
            if (vertical) {
                if (positive) clip.bottom = (panel.height * (1f - retract)).roundToInt()
                else clip.top = (panel.height * retract).roundToInt()
                panel.translationX = initialX + dx * retract
                panel.translationY = initialY + travel * retract
            } else {
                if (positive) clip.right = (panel.width * (1f - retract)).roundToInt()
                else clip.left = (panel.width * retract).roundToInt()
                panel.translationX = initialX + travel * retract
                panel.translationY = initialY + dy * retract
            }
            panel.clipBounds = clip
            val fade = ((progress - 0.15f) / 0.85f).coerceIn(0f, 1f)
            panel.alpha = initialAlpha * (1f - fade * fade * (3f - 2f * fade))
            scrim.alpha = initialScrimAlpha * (1f - progress)
        }
        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (!cancelled) onEnd()
            }
        })
    }

    fun start() = animator.start()

    fun cancel() {
        cancelled = true
        animator.cancel()
        panel.clipBounds = initialClip
        panel.translationX = initialX
        panel.translationY = initialY
        panel.alpha = initialAlpha
    }
}
