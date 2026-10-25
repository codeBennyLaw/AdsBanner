package com.adsbanner.clean.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.RequiresApi
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.doOnPreDraw
import androidx.transition.Transition
import androidx.transition.TransitionListenerAdapter
import androidx.transition.TransitionManager
import com.adsbanner.clean.R
import com.google.android.material.shape.ShapeAppearanceModel
import com.google.android.material.transition.MaterialContainerTransform
import java.lang.ref.WeakReference

/** An in-window card morph, so the activity itself never has a scene-transition back stack. */
@RequiresApi(34)
internal class PageOriginMotion(
    private val activity: Activity,
    private val work: PageTransitionWork,
    private val color: Int,
    private val animated: () -> Boolean,
    restored: Boolean
) {
    private val stage = activity.findViewById<ViewGroup>(android.R.id.content)
    private val page = activity.findViewById<View>(R.id.root)
    private val origin = pendingOrigin.takeIf { it?.get()?.transitionName == stage.transitionName }
    private val background = activity.getColor(R.color.bg)
    private val originalAlpha = origin?.get()?.alpha ?: 1f
    private var returning = false
    private var entering = true
    private var closed = false
    private val anchor = object : View(activity) {
        override fun onDraw(canvas: Canvas) { source()?.draw(canvas) }
    }.apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO; visibility = View.INVISIBLE }

    init {
        pendingOrigin = null
        stage.addView(anchor, ViewGroup.LayoutParams(1, 1))
        if (!restored && animated() && placeAnchor() && activity.setTranslucent(true)) {
            activity.window.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
            page.visibility = View.INVISIBLE
            anchor.visibility = View.VISIBLE
            stage.doOnPreDraw {
                if (!closed && !returning && !activity.isFinishing) morph(forward = true)
            }
        } else {
            entering = false
            work.completeImmediately()
        }
    }

    // The home decor is temporarily invisible while this opaque activity is on top.
    private fun source() = origin?.get()?.takeIf { it.isAttachedToWindow }

    @SuppressLint("RtlHardcoded") // The anchor uses physical screen coordinates.
    private fun placeAnchor(): Boolean {
        val view = source() ?: return false
        val rect = Rect()
        if (!view.getGlobalVisibleRect(rect) || rect.width() != view.width || rect.height() != view.height) return false
        val location = IntArray(2)
        stage.getLocationOnScreen(location)
        anchor.layoutParams = FrameLayout.LayoutParams(view.width, view.height).apply {
            gravity = Gravity.TOP or Gravity.LEFT // Bounds are physical screen coordinates, also in RTL.
            leftMargin = rect.left - location[0]
            topMargin = rect.top - location[1]
        }
        anchor.measure(View.MeasureSpec.makeMeasureSpec(view.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(view.height, View.MeasureSpec.EXACTLY))
        anchor.layout(rect.left - location[0], rect.top - location[1], rect.right - location[0], rect.bottom - location[1])
        return true
    }

    private fun morph(forward: Boolean) {
        val cardShape = ShapeAppearanceModel.builder().setAllCornerSizes(
            activity.resources.getDimension(R.dimen.corner_card)).build()
        val pageShape = ShapeAppearanceModel.builder().setAllCornerSizes(0f).build()
        val transition = MaterialContainerTransform().apply {
            drawingViewId = android.R.id.content
            startView = if (forward) anchor else page
            endView = if (forward) page else anchor
            addTarget(if (forward) page else anchor)
            startShapeAppearanceModel = if (forward) cardShape else pageShape
            endShapeAppearanceModel = if (forward) pageShape else cardShape
            startContainerColor = if (forward) color else background
            endContainerColor = if (forward) background else color
            scrimColor = Color.TRANSPARENT
            duration = if (forward) 320L else 260L
            fadeMode = MaterialContainerTransform.FADE_MODE_THROUGH
            transitionDirection = if (forward) MaterialContainerTransform.TRANSITION_DIRECTION_ENTER
                else MaterialContainerTransform.TRANSITION_DIRECTION_RETURN
            addListener(object : TransitionListenerAdapter() {
                override fun onTransitionStart(transition: Transition) { source()?.alpha = 0f }
                override fun onTransitionEnd(transition: Transition) {
                    entering = false
                    restore()
                    if (returning) finish() else work.endEnter()
                }
            })
        }
        if (forward) work.beginEnter()
        TransitionManager.beginDelayedTransition(stage, transition)
        anchor.visibility = if (forward) View.INVISIBLE else View.VISIBLE
        page.visibility = if (forward) View.VISIBLE else View.INVISIBLE
    }

    fun returnToOrigin() {
        if (closed || returning || activity.isFinishing) return
        if (entering) {
            close()
            finish()
            return
        }
        returning = true
        if (!animated() || !placeAnchor() || !activity.setTranslucent(true)) {
            finish()
            return
        }
        activity.window.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        stage.doOnPreDraw { if (!closed && !activity.isFinishing) morph(forward = false) }
        stage.invalidate()
    }

    private fun restore() {
        origin?.get()?.alpha = originalAlpha
        anchor.visibility = View.INVISIBLE
        page.visibility = View.VISIBLE
        if (!returning && !activity.isFinishing && !closed) {
            activity.window.setBackgroundDrawable(background.toDrawable())
            activity.setTranslucent(false)
        }
    }

    private fun finish() {
        activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
        activity.finish()
    }

    fun close() {
        if (closed) return
        closed = true
        // Do not finish the activity from a cancelled transition during destruction.
        returning = false
        TransitionManager.endTransitions(stage)
        restore()
        stage.removeView(anchor)
    }

    companion object {
        private var pendingOrigin: WeakReference<View>? = null

        fun open(activity: Activity, source: View, intent: Intent) {
            pendingOrigin = WeakReference(source)
            // ANIM_SCENE_TRANSITION permanently disables the system's predictive back preview.
            activity.startActivity(intent, ActivityOptions.makeCustomAnimation(activity, 0, 0).toBundle())
        }
    }
}
