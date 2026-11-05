package com.adsbanner.clean.ui

import android.graphics.Outline
import android.view.View
import android.view.ViewOutlineProvider
import android.os.SystemClock
import androidx.activity.BackEventCompat
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import kotlin.math.abs

/** Direct render-property updates during a back gesture, with a reversible cancellation. */
internal class BackProgressMotion(private val view: View) {
    private val density = view.resources.displayMetrics.density
    private val originalOutline = view.outlineProvider
    private val originalClip = view.clipToOutline
    private var startY = 0f
    private var initialScale = 1f
    private var initialX = 0f
    private var initialY = 0f
    private var settled: (() -> Unit)? = null
    private var lastUpdateAt = 0L
    private val velocity = FloatArray(4)
    val heightVelocity: Float
        get() = if (SystemClock.uptimeMillis() - lastUpdateAt <= 80L) velocity[1] * view.height else 0f
    val cornerRadius: Float
        get() = 28 * density * ((1f - view.scaleX) / 0.1f).coerceIn(0f, 1f) * view.scaleX
    private val outline = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, cornerRadius / view.scaleX)
        }
    }
    private val springs = listOf(
        SpringAnimation(view, SpringAnimation.SCALE_X), SpringAnimation(view, SpringAnimation.SCALE_Y),
        SpringAnimation(view, SpringAnimation.TRANSLATION_X), SpringAnimation(view, SpringAnimation.TRANSLATION_Y)
    ).onEachIndexed { index, animation ->
        animation.spring = SpringForce(if (index < 2) 1f else 0f).setDampingRatio(1f).setStiffness(800f)
        if (index < 2) animation.setMinimumVisibleChange(0.001f)
    }

    init {
        springs.first().addUpdateListener { _, _, _ -> view.invalidateOutline() }
        springs.forEach { animation -> animation.addEndListener { _, cancelled, _, _ ->
            if (!cancelled && springs.none { it.isRunning }) {
                restoreOutline()
                val completion = settled
                settled = null
                completion?.invoke()
            }
        } }
    }

    fun start(event: BackEventCompat) {
        freeze()
        startY = event.touchY
        initialScale = view.scaleX
        initialX = view.translationX
        initialY = view.translationY
        lastUpdateAt = 0L
        velocity.fill(0f)
        view.outlineProvider = outline
        view.clipToOutline = true
    }

    fun update(event: BackEventCompat) {
        // The platform already smooths back progress. A second ease-out makes the page
        // race ahead of the finger at the start, then barely move near the end.
        val progress = event.progress.coerceIn(0f, 1f)
        val oldScale = view.scaleX
        val oldX = view.translationX
        val oldY = view.translationY
        val direction = if (event.swipeEdge == BackEventCompat.EDGE_LEFT) 1 else -1
        val shift = (view.width * 0.05f - 8 * density).coerceAtLeast(0f) * direction
        view.scaleX = initialScale + (0.9f - initialScale) * progress
        view.scaleY = view.scaleX
        view.translationX = initialX + (shift - initialX) * progress
        val limitY = (view.height * 0.05f - 8 * density).coerceAtLeast(0f)
        val dragY = (event.touchY - startY) * 0.15f
        val followY = if (limitY > 0f) dragY / (1f + abs(dragY) / limitY) else 0f
        view.translationY = initialY + (followY - initialY) * progress
        if (view.scaleX != oldScale) view.invalidateOutline()
        val now = SystemClock.uptimeMillis()
        if (lastUpdateAt > 0 && now > lastUpdateAt) {
            val seconds = (now - lastUpdateAt) / 1000f
            velocity[0] = ((view.scaleX - oldScale) / seconds).coerceIn(-1f, 1f)
            velocity[1] = velocity[0]
            velocity[2] = ((view.translationX - oldX) / seconds).coerceIn(-2_000f, 2_000f)
            velocity[3] = ((view.translationY - oldY) / seconds).coerceIn(-2_000f, 2_000f)
        }
        lastUpdateAt = now
    }

    fun settle(animated: Boolean, onSettled: () -> Unit) {
        if (!animated || (view.scaleX == 1f && view.scaleY == 1f && view.translationX == 0f && view.translationY == 0f)) {
            reset()
            onSettled()
            return
        }
        settled = onSettled
        val recent = SystemClock.uptimeMillis() - lastUpdateAt <= 80L
        springs.forEachIndexed { index, spring ->
            spring.setStartVelocity(if (recent) velocity[index] else 0f)
            spring.animateToFinalPosition(spring.spring.finalPosition)
        }
    }

    fun freeze() {
        settled = null
        springs.forEach { it.cancel() }
    }

    fun reset() {
        freeze()
        view.scaleX = 1f
        view.scaleY = 1f
        view.translationX = 0f
        view.translationY = 0f
        restoreOutline()
    }

    private fun restoreOutline() {
        view.outlineProvider = originalOutline
        view.clipToOutline = originalClip
    }
}
