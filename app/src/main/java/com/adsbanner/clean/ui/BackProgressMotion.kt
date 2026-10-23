package com.adsbanner.clean.ui

import android.graphics.Outline
import android.view.View
import android.view.ViewOutlineProvider
import androidx.activity.BackEventCompat
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce

/** Gesture progress writes render properties directly; cancellation settles from the current pose. */
internal class BackProgressMotion(private val view: View) {
    private val density = view.resources.displayMetrics.density
    private val originalOutline = view.outlineProvider
    private val originalClip = view.clipToOutline
    private var startY = 0f
    private var initialScale = 1f
    private var initialX = 0f
    private var initialY = 0f
    private val outline = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            val rounding = ((1f - view.scaleX) / 0.04f).coerceIn(0f, 1f)
            outline.setRoundRect(0, 0, view.width, view.height, 20 * density * rounding)
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
            if (!cancelled && springs.none { it.isRunning }) restoreOutline()
        } }
    }

    fun start(event: BackEventCompat) {
        springs.forEach { it.cancel() }
        startY = event.touchY
        initialScale = view.scaleX
        initialX = view.translationX
        initialY = view.translationY
        view.outlineProvider = outline
        view.clipToOutline = true
    }

    fun update(event: BackEventCompat) {
        val progress = event.progress.coerceIn(0f, 1f)
        val direction = if (event.swipeEdge == BackEventCompat.EDGE_LEFT) 1 else -1
        view.scaleX = initialScale + (0.96f - initialScale) * progress
        view.scaleY = view.scaleX
        view.translationX = initialX + (24 * density * direction - initialX) * progress
        val followY = ((event.touchY - startY) * 0.03f).coerceIn(-12 * density, 12 * density)
        view.translationY = initialY + (followY - initialY) * progress
        view.invalidateOutline()
    }

    fun settle(animated: Boolean) {
        if (!animated) { reset(); return }
        if (view.scaleX == 1f && view.scaleY == 1f && view.translationX == 0f && view.translationY == 0f) {
            restoreOutline()
            return
        }
        springs.forEach { it.animateToFinalPosition(it.spring.finalPosition) }
    }

    fun reset() {
        freeze()
        view.scaleX = 1f
        view.scaleY = 1f
        view.translationX = 0f
        view.translationY = 0f
        restoreOutline()
    }

    /** The container return takes over this pose; only a cancelled gesture springs back. */
    fun freeze() { springs.forEach { it.cancel() } }

    private fun restoreOutline() {
        view.outlineProvider = originalOutline
        view.clipToOutline = originalClip
    }
}
