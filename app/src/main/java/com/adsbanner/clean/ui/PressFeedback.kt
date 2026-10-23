package com.adsbanner.clean.ui

import android.animation.ValueAnimator
import android.view.MotionEvent
import android.view.View
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.adsbanner.clean.R

/** Small, interruptible springs. The native view still owns clicks, ripples and scrolling. */
internal class PressFeedback private constructor(private val view: View) : View.OnAttachStateChangeListener {
    private fun spring(property: androidx.dynamicanimation.animation.FloatPropertyCompat<View>) =
        SpringAnimation(view, property).apply {
            spring = SpringForce(1f).setStiffness(1500f).setDampingRatio(0.85f)
            setMinimumVisibleChange(0.001f)
        }
    private val x = spring(SpringAnimation.SCALE_X)
    private val y = spring(SpringAnimation.SCALE_Y)

    init {
        view.addOnAttachStateChangeListener(this)
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> animate(0.985f)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> animate(1f)
            }
            false
        }
    }

    private fun animate(scale: Float) {
        if (ValueAnimator.areAnimatorsEnabled()) {
            x.animateToFinalPosition(scale)
            y.animateToFinalPosition(scale)
        } else reset()
    }

    fun reset() {
        x.cancel()
        y.cancel()
        view.scaleX = 1f
        view.scaleY = 1f
    }
    override fun onViewDetachedFromWindow(view: View) = reset()
    override fun onViewAttachedToWindow(view: View) = Unit

    companion object {
        fun bind(view: View) {
            if (view.getTag(R.id.motion_press_feedback) == null) {
                view.setTag(R.id.motion_press_feedback, PressFeedback(view))
            }
        }
        fun reset(view: View) { (view.getTag(R.id.motion_press_feedback) as? PressFeedback)?.reset() }
    }
}
