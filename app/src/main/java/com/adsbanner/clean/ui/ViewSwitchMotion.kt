package com.adsbanner.clean.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import androidx.core.view.isVisible

/** Keep both settled viewports mounted through a switch; rapid reversals retain their current pose. */
internal class ViewSwitchMotion(parent: ViewGroup) : View.OnAttachStateChangeListener {
    private var animator: ValueAnimator? = null
    private var generation = 0
    private var show: View? = null
    private var hide: View? = null
    private var hiddenAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO

    init { parent.addOnAttachStateChangeListener(this) }

    fun switch(show: View, hide: View, forward: Boolean, animated: Boolean) {
        generation++
        animator?.cancel()
        this.hide?.importantForAccessibility = hiddenAccessibility
        this.show = show
        this.hide = hide
        hiddenAccessibility = hide.importantForAccessibility
        if (!animated) { settle(); return }
        val token = generation
        val distance = show.resources.displayMetrics.density * 8f * if (forward) 1 else -1
        val startShowAlpha = if (show.isVisible) show.alpha else 0f
        val startShowX = if (show.isVisible) show.translationX else distance
        val startHideAlpha = hide.alpha
        val startHideX = hide.translationX
        show.visibility = View.VISIBLE
        show.bringToFront()
        hide.visibility = View.VISIBLE
        hide.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        show.alpha = startShowAlpha
        show.translationX = startShowX
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 200L
            interpolator = PathInterpolator(0.2f, 0f, 0.1f, 1f)
            addUpdateListener {
                val progress = it.animatedValue as Float
                show.alpha = startShowAlpha + (1f - startShowAlpha) * progress
                show.translationX = startShowX * (1f - progress)
                hide.alpha = startHideAlpha * (1f - progress)
                hide.translationX = startHideX + (-distance - startHideX) * progress
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { if (generation == token) settle() }
            })
            start()
        }
    }

    private fun settle() {
        animator = null
        show?.apply { visibility = View.VISIBLE; alpha = 1f; translationX = 0f }
        hide?.apply {
            visibility = View.GONE; alpha = 1f; translationX = 0f
            importantForAccessibility = hiddenAccessibility
        }
    }

    override fun onViewAttachedToWindow(view: View) = Unit
    override fun onViewDetachedFromWindow(view: View) {
        generation++
        animator?.cancel()
        settle()
    }
}
