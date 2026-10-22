package com.adsbanner.clean.ui

import android.animation.ValueAnimator
import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.graphics.Color
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.doOnPreDraw
import androidx.transition.TransitionManager
import com.adsbanner.clean.R
import com.google.android.material.transition.MaterialSharedAxis
import com.google.android.material.transition.platform.MaterialContainerTransform
import com.google.android.material.transition.platform.MaterialContainerTransformSharedElementCallback

/** Shared motion tokens. Android's animator scale is the reduced-motion contract. */
object Motion {
    private const val ENTER_MS = 240L
    private const val SWITCH_MS = 180L
    private const val PAGE_ENTER_MS = 320L
    private const val PAGE_RETURN_MS = 260L

    fun prepareHome(activity: Activity) {
        activity.setExitSharedElementCallback(MaterialContainerTransformSharedElementCallback())
        activity.window.apply {
            sharedElementsUseOverlay = false
            exitTransition = null
            reenterTransition = null
        }
    }

    fun openPage(activity: Activity, source: View, destination: Class<out Activity>) {
        val options = ActivityOptions.makeSceneTransitionAnimation(
            activity, source, source.transitionName
        )
        activity.startActivity(Intent(activity, destination), options.toBundle())
    }

    /** Expand the clicked home card into the page, then reverse that same path on back. */
    fun preparePage(activity: AppCompatActivity, name: String, sourceColor: Int) {
        val content = activity.findViewById<View>(android.R.id.content)
        content.transitionName = name
        val background = ContextCompat.getColor(activity, R.color.bg)
        val origin = ContextCompat.getColor(activity, sourceColor)
        val enabled = pageAnimationsEnabled(activity)
        fun transform(entering: Boolean) = MaterialContainerTransform().apply {
            addTarget(android.R.id.content)
            duration = if (!enabled) 0L else if (entering) PAGE_ENTER_MS else PAGE_RETURN_MS
            fadeMode = MaterialContainerTransform.FADE_MODE_THROUGH
            containerColor = background
            startContainerColor = if (entering) origin else background
            endContainerColor = if (entering) background else origin
            scrimColor = Color.TRANSPARENT
        }
        activity.setEnterSharedElementCallback(MaterialContainerTransformSharedElementCallback())
        activity.window.apply {
            sharedElementsUseOverlay = false
            sharedElementEnterTransition = transform(true)
            sharedElementReturnTransition = transform(false)
            enterTransition = null
            returnTransition = null
        }
        activity.postponeEnterTransition()
        content.doOnPreDraw { activity.startPostponedEnterTransition() }
        var returning = false
        activity.onBackPressedDispatcher.addCallback(activity) {
            if (returning || activity.isFinishing) return@addCallback
            returning = true
            if (!pageAnimationsEnabled(activity)) {
                activity.window.sharedElementReturnTransition?.duration = 0L
            }
            activity.finishAfterTransition()
        }
    }

    private fun pageAnimationsEnabled(activity: Activity): Boolean =
        ValueAnimator.areAnimatorsEnabled() && Settings.Global.getFloat(
            activity.contentResolver, Settings.Global.TRANSITION_ANIMATION_SCALE, 1f
        ) > 0f

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

    fun switchViews(show: View, hide: View, forward: Boolean) {
        val parent = show.parent as ViewGroup
        // Finish the previous switch before capturing another one. Visibility always reflects
        // the current selection, even when the user toggles twice in the same frame.
        TransitionManager.endTransitions(parent)
        if (!ValueAnimator.areAnimatorsEnabled()) {
            hide.visibility = View.GONE
            hide.alpha = 1f
            show.visibility = View.VISIBLE
            show.alpha = 1f
            return
        }
        TransitionManager.beginDelayedTransition(
            parent,
            MaterialSharedAxis(MaterialSharedAxis.X, forward).apply {
                duration = SWITCH_MS
                addTarget(show)
                addTarget(hide)
            }
        )
        hide.visibility = View.GONE
        show.visibility = View.VISIBLE
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
