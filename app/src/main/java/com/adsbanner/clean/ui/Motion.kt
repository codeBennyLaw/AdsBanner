package com.adsbanner.clean.ui

import android.animation.ValueAnimator
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator

/** Shared motion tokens. Android's animator scale is the reduced-motion contract. */
object Motion {
    private const val ENTER_MS = 240L
    private const val SWITCH_MS = 180L

    fun enter(view: View) {
        if (!ValueAnimator.areAnimatorsEnabled()) {
            view.alpha = 1f
            view.translationY = 0f
            return
        }
        view.alpha = 0f
        view.translationY = 12f
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(ENTER_MS)
            .setInterpolator(DecelerateInterpolator(1.5f))
            .start()
    }

    fun crossfade(show: View, hide: View) {
        if (!ValueAnimator.areAnimatorsEnabled()) {
            hide.visibility = View.GONE
            hide.alpha = 1f
            show.visibility = View.VISIBLE
            show.alpha = 1f
            return
        }
        hide.animate().cancel()
        show.animate().cancel()
        hide.animate().alpha(0f).setDuration(SWITCH_MS / 2).withEndAction {
            hide.visibility = View.GONE
            hide.alpha = 1f
            show.alpha = 0f
            show.visibility = View.VISIBLE
            show.animate().alpha(1f).setDuration(SWITCH_MS).start()
        }.start()
    }

    fun staggerChildren(container: ViewGroup) {
        if (!ValueAnimator.areAnimatorsEnabled()) return
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            child.alpha = 0f
            child.translationY = 8f
            child.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay((i * 18L).coerceAtMost(180L))
                .setDuration(180L)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }
}
