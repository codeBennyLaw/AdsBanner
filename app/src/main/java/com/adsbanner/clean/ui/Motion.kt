package com.adsbanner.clean.ui

import android.animation.ValueAnimator
import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.provider.Settings
import android.transition.Transition
import android.transition.TransitionListenerAdapter
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import androidx.activity.OnBackPressedCallback
import androidx.activity.BackEventCompat
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.doOnPreDraw
import androidx.core.view.descendants
import androidx.recyclerview.widget.RecyclerView
import androidx.transition.TransitionManager
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
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
    private const val EXTRA_CARD_TRANSITION = "com.adsbanner.clean.CARD_TRANSITION"

    fun prepareHome(activity: Activity) {
        bindPressFeedback(activity.findViewById(R.id.cardStats))
        bindPressFeedback(activity.findViewById(R.id.cardTargets))
        activity.setExitSharedElementCallback(MaterialContainerTransformSharedElementCallback())
        activity.window.apply {
            sharedElementsUseOverlay = false
            exitTransition = null
            reenterTransition = null
        }
    }

    fun openPage(activity: Activity, source: View, destination: Class<out Activity>) {
        resetPressFeedback(source)
        val intent = Intent(activity, destination).putExtra(EXTRA_CARD_TRANSITION, true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            PageOriginMotion.open(activity, source, intent)
            return
        }
        val options = ActivityOptions.makeSceneTransitionAnimation(
            activity, source, source.transitionName
        )
        activity.startActivity(intent, options.toBundle())
    }

    /** Back progress previews the home page, then the container returns to its entry card. */
    fun preparePage(activity: AppCompatActivity, name: String, sourceColor: Int, restored: Boolean = false) {
        val content = activity.findViewById<View>(android.R.id.content)
        content.transitionName = name
        val background = ContextCompat.getColor(activity, R.color.bg)
        val origin = ContextCompat.getColor(activity, sourceColor)
        val enabled = pageAnimationsEnabled(activity)
        val work = PageTransitionWork(content,
            enabled && !restored && activity.intent.getBooleanExtra(EXTRA_CARD_TRANSITION, false))
        content.setTag(R.id.motion_page_work, work)
        val originMotion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            PageOriginMotion(activity, work, origin, { pageAnimationsEnabled(activity) }, restored)
        } else null
        fun transform(entering: Boolean) = MaterialContainerTransform().apply {
            addTarget(android.R.id.content)
            duration = if (!enabled) 0L else if (entering) PAGE_ENTER_MS else PAGE_RETURN_MS
            fadeMode = MaterialContainerTransform.FADE_MODE_THROUGH
            containerColor = background
            startContainerColor = if (entering) origin else background
            endContainerColor = if (entering) background else origin
            scrimColor = Color.TRANSPARENT
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            activity.setEnterSharedElementCallback(MaterialContainerTransformSharedElementCallback())
            activity.window.apply {
                sharedElementsUseOverlay = false
                sharedElementEnterTransition = transform(true).apply {
                    addListener(object : TransitionListenerAdapter() {
                        override fun onTransitionStart(transition: Transition) = work.beginEnter()
                        override fun onTransitionEnd(transition: Transition) = work.endEnter()
                        override fun onTransitionCancel(transition: Transition) = work.endEnter()
                    })
                }
                sharedElementReturnTransition = transform(false)
                enterTransition = null
                returnTransition = null
            }
            activity.postponeEnterTransition()
            content.doOnPreDraw { activity.startPostponedEnterTransition() }
        }
        var returning = false
        val frozenLists = mutableListOf<RecyclerView>()
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) originMotion?.stop()
                work.completeImmediately()
            }
            override fun onDestroy(owner: LifecycleOwner) {
                work.close()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) originMotion?.close()
                frozenLists.forEach { it.suppressLayout(false) }
                content.setTag(R.id.motion_page_work, null)
            }
        })
        fun returnToOrigin() {
            if (returning || activity.isFinishing) return
            returning = true
            val animated = pageAnimationsEnabled(activity)
            work.beginReturn()
            if (content is ViewGroup) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) TransitionManager.endTransitions(content)
                content.descendants.filterIsInstance<RecyclerView>().forEach {
                    (it.parent as? ViewGroup)?.let(TransitionManager::endTransitions)
                    it.stopScroll()
                    it.itemAnimator?.endAnimations()
                    it.suppressLayout(true)
                    frozenLists.add(it)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                originMotion?.returnToOrigin()
            } else {
                if (!animated) activity.window.sharedElementReturnTransition?.duration = 0L
                activity.finishAfterTransition()
            }
        }
        activity.findViewById<View>(R.id.btnBack).setOnClickListener { returnToOrigin() }
        activity.onBackPressedDispatcher.addCallback(activity, object : OnBackPressedCallback(true) {
            override fun handleOnBackStarted(backEvent: BackEventCompat) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) originMotion?.startBack(backEvent)
            }
            override fun handleOnBackProgressed(backEvent: BackEventCompat) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) originMotion?.progressBack(backEvent)
            }
            override fun handleOnBackCancelled() {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) originMotion?.cancelBack()
            }
            override fun handleOnBackPressed() = returnToOrigin()
        })
    }

    fun bindPressFeedback(view: View) = PressFeedback.bind(view)
    fun resetPressFeedback(view: View) = PressFeedback.reset(view)

    internal fun whenPageReady(activity: Activity, key: Any, task: () -> Unit) {
        val work = activity.findViewById<View>(android.R.id.content).getTag(R.id.motion_page_work) as? PageTransitionWork
        if (work == null) task() else work.submit(key, task)
    }

    fun pageEnterComplete(activity: Activity) {
        (activity.findViewById<View>(android.R.id.content).getTag(R.id.motion_page_work) as? PageTransitionWork)?.completeEnter()
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

}
