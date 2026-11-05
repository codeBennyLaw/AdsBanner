package com.adsbanner.clean.ui

import android.annotation.SuppressLint
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.app.ActivityOptions
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RenderNode
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import androidx.annotation.RequiresApi
import androidx.activity.BackEventCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.core.graphics.ColorUtils
import androidx.transition.TransitionManager
import com.adsbanner.clean.R
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
    private val page = activity.findViewById<ViewGroup>(R.id.root)
    private val origin = pendingOrigin.takeIf { it?.get()?.transitionName == stage.transitionName }
    private val background = activity.getColor(R.color.bg)
    private var returning = false
    private var entering = true
    private var closed = false
    private var previewing = false
    private var translucent = false
    private var homeReady = false
    private var returningFromGesture = false
    private var returnHeightVelocity = 0f
    private var latestBackEvent: BackEventCompat? = null
    private var revealedHome: WeakReference<Activity>? = null
    private var homeWindowAnimations = 0
    // Keep the real hierarchy attached: drawable, text and property animations remain live.
    private val surface = object : FrameLayout(activity) {
        override fun onInterceptTouchEvent(event: MotionEvent) = returning || super.onInterceptTouchEvent(event)
        override fun onTouchEvent(event: MotionEvent) = returning || super.onTouchEvent(event)
    }.apply { setBackgroundColor(this@PageOriginMotion.background) }
    private val backMotion = BackProgressMotion(surface)
    private var returnPose: View? = null
    private var returnGeometry: OriginReturnTransform? = null
    private var homeBackdropNode: RenderNode? = null
    private val homeBackdrop = object : View(activity) {
        override fun onDraw(canvas: Canvas) {
            val node = homeBackdropNode ?: return
            if (canvas.isHardwareAccelerated && node.hasDisplayList()) canvas.drawRenderNode(node)
        }
    }.apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO; visibility = View.INVISIBLE }
    private var homeFrameObserver: ViewTreeObserver? = null
    private var homeFrameCallback: Runnable? = null
    private var entryAnimator: ValueAnimator? = null
    private var entryGeometry: OriginReturnTransform? = null
    private var entryProgress = 0f
    private var entryBackStart: Float? = null
    private data class EntryAppearance(val progress: Float, val fill: Int, val contents: Float, val alpha: Float)
    private var entryExit: EntryAppearance? = null
    private var entryResume: EntryAppearance? = null
    private var returnAnimator: ValueAnimator? = null
    private var poseFill = background
    private val anchor = View(activity).apply {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        visibility = View.INVISIBLE
    }

    init {
        val index = stage.indexOfChild(page)
        val params = page.layoutParams
        stage.removeView(page)
        surface.addView(page, FrameLayout.LayoutParams(-1, -1))
        stage.addView(surface, index, params)
        pendingOrigin = null
        stage.addView(homeBackdrop, 0, ViewGroup.LayoutParams(-1, -1))
        stage.addView(anchor, ViewGroup.LayoutParams(1, 1))
        if (!restored && animated() && placeAnchor() && revealHome()) {
            surface.visibility = View.INVISIBLE
            anchor.visibility = View.VISIBLE
            stage.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    stage.viewTreeObserver.removeOnPreDrawListener(this)
                    if (!entering || closed || returning || previewing || activity.isFinishing) return true
                    // Begin after layout and apply the starting pose before the first visible draw.
                    preparePagePose()
                    startEntryMorph()
                    return false
                }
            })
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
        if (!view.getGlobalVisibleRect(rect)) return false
        val location = IntArray(2)
        stage.getLocationOnScreen(location)
        anchor.layoutParams = FrameLayout.LayoutParams(rect.width(), rect.height()).apply {
            gravity = Gravity.TOP or Gravity.LEFT // Bounds are physical screen coordinates, also in RTL.
            leftMargin = rect.left - location[0]
            topMargin = rect.top - location[1]
        }
        anchor.measure(View.MeasureSpec.makeMeasureSpec(rect.width(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(rect.height(), View.MeasureSpec.EXACTLY))
        anchor.layout(rect.left - location[0], rect.top - location[1], rect.right - location[0], rect.bottom - location[1])
        return true
    }

    private fun startEntryMorph() {
        val pose = returnPose ?: return
        val location = IntArray(2).also(anchor::getLocationOnScreen)
        entryGeometry = OriginReturnTransform(pose,
            Rect(location[0], location[1], location[0] + anchor.width, location[1] + anchor.height),
            activity.resources.getDimension(R.dimen.corner_card))
        anchor.visibility = View.INVISIBLE
        pose.visibility = View.VISIBLE
        work.beginEnter()
        applyEntry(0f)
        animateEntry(1f)
    }

    private fun entryAppearance() = EntryAppearance(entryProgress, poseFill, page.alpha, returnPose?.alpha ?: 1f)

    private fun applyEntry(progress: Float) {
        entryProgress = progress
        entryGeometry?.apply(1f - progress)
        val exit = entryExit
        val resume = entryResume
        when {
            exit != null -> {
                val fraction = ((exit.progress - progress) / exit.progress.coerceAtLeast(0.001f)).coerceIn(0f, 1f)
                setPoseFill(exit.fill)
                page.alpha = exit.contents * RetractionVisuals.contentAlpha(fraction)
                returnPose?.alpha = exit.alpha * RetractionVisuals.panelAlpha(fraction)
            }
            resume != null -> {
                val fraction = ((progress - resume.progress) / (1f - resume.progress).coerceAtLeast(0.001f)).coerceIn(0f, 1f)
                setPoseFill(ColorUtils.blendARGB(resume.fill, background, fraction))
                page.alpha = resume.contents + (1f - resume.contents) * fraction
                returnPose?.alpha = resume.alpha + (1f - resume.alpha) * fraction
            }
            else -> {
                setPoseFill(ColorUtils.blendARGB(color, background, motionFraction(0.05f, 0.9f, progress)))
                page.alpha = motionFraction(0.12f, 0.72f, progress)
                returnPose?.alpha = motionFraction(0f, 0.12f, progress)
            }
        }
        returnPose?.invalidate()
    }

    private fun animateEntry(destination: Float) {
        entryAnimator = ValueAnimator.ofFloat(entryProgress, destination).apply {
            duration = (340L * kotlin.math.abs(destination - entryProgress)).toLong().coerceAtLeast(80L)
            interpolator = if (destination == 1f) PathInterpolator(0.2f, 0f, 0f, 1f)
                else PathInterpolator(0.3f, 0f, 0.6f, 1f)
            addUpdateListener { applyEntry(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    entryAnimator = null
                    if (closed || activity.isFinishing || previewing) return
                    if (returning) handOffToHome()
                    else if (entering) {
                        entering = false
                        restore()
                        work.endEnter()
                    }
                }
            })
            start()
        }
    }

    fun returnToOrigin() {
        if (closed || returning || activity.isFinishing) return
        if (entering) {
            if (entryGeometry == null) { close(); finish(); return }
            entryExit = entryAppearance()
            entryResume = null
            returning = true
            entering = false
            previewing = false
            entryBackStart = null
            // Cancelling retains the current pose; the reverse uses the same geometry.
            previewing = true
            entryAnimator?.cancel()
            previewing = false
            animateEntry(0f)
            return
        }
        returning = true
        returningFromGesture = previewing
        returnHeightVelocity = backMotion.heightVelocity
        previewing = false
        backMotion.freeze()
        if (!animated() || page.width == 0 || page.height == 0 || !placeAnchor() || !revealHome()) {
            finish()
            return
        }
        fun startReturn() {
            if (!placeAnchor()) { finish(); return }
            preparePagePose()
            // Continue transforming the same hierarchy from the current gesture pose.
            startReturnMorph()
        }
        if (homeReady || homeBackdropNode != null) startReturn() else whenHomeFrameReady {
            homeReady = true
            clearHomeBackdrop()
            startReturn()
        }
    }

    private fun startReturnMorph() {
        val pose = returnPose ?: run { finish(); return }
        val location = IntArray(2).also(anchor::getLocationOnScreen)
        val destination = Rect(location[0], location[1], location[0] + anchor.width, location[1] + anchor.height)
        val geometry = OriginReturnTransform(pose, destination, activity.resources.getDimension(R.dimen.corner_card))
        returnGeometry = geometry
        val heightDistance = destination.height() - pose.height * pose.scaleY
        val speed = if (returningFromGesture && heightDistance != 0f) (returnHeightVelocity / heightDistance).coerceIn(0f, 4f) else 0f
        anchor.visibility = View.INVISIBLE
        pose.visibility = View.VISIBLE
        returnAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = RetractionVisuals.DURATION_MS
            interpolator = if (returningFromGesture) PathInterpolator(0.2f, (0.2f * speed * duration / 1000f).coerceIn(0f, 0.8f), 0.2f, 1f)
                else PathInterpolator(0.3f, 0f, 0.4f, 1f)
            addUpdateListener {
                val progress = it.animatedValue as Float
                geometry.apply(progress)
                setPoseFill(background)
                page.alpha = RetractionVisuals.contentAlpha(progress)
                pose.alpha = RetractionVisuals.panelAlpha(progress)
                pose.invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    returnAnimator = null
                    if (!closed && !activity.isFinishing) handOffToHome()
                }
            })
            start()
        }
    }

    private fun restore() {
        clearHomeFrameWait()
        clearHomeBackdrop()
        latestBackEvent = null
        anchor.visibility = View.INVISIBLE
        // A committed return must leave the child window transparent until it is removed.
        // Showing the full page here can expose one last stale frame over the home activity.
        surface.visibility = if (!returning && !closed && !activity.isFinishing) View.VISIBLE else View.INVISIBLE
        entryGeometry?.restore()
        returnGeometry?.restore()
        returnGeometry = null
        backMotion.reset()
        page.alpha = 1f
        setPoseFill(background)
        returnPose = null
        entryGeometry = null
        entryExit = null
        entryResume = null
        if (!returning && !activity.isFinishing && !closed && translucent) {
            activity.window.setBackgroundDrawable(background.toDrawable())
            activity.setTranslucent(false)
            translucent = false
            homeReady = false
            restoreHomeWindowAnimations()
        }
    }

    private fun handOffToHome() {
        // The source card stays visible. Hold until the home window's buffer is submitted.
        if (homeReady && sourceActivity()?.window?.decorView?.isLayoutRequested == false) finish()
        else whenHomeFrameReady { finish() }
    }

    private fun whenHomeFrameReady(completion: () -> Unit) {
        clearHomeFrameWait()
        val homeCard = source()
        if (homeCard == null || !homeCard.isHardwareAccelerated) {
            completion()
            return
        }
        val callback = object : Runnable {
            override fun run() {
                if (homeFrameCallback !== this) return
                clearHomeFrameWait()
                if (!closed && !activity.isFinishing) completion()
            }
        }
        homeFrameCallback = callback
        homeFrameObserver = homeCard.viewTreeObserver.also {
            it.registerFrameCommitCallback(callback)
        }
        homeCard.invalidate()
    }

    private fun clearHomeFrameWait() {
        homeFrameCallback?.let { callback ->
            homeFrameObserver?.takeIf { it.isAlive }?.unregisterFrameCommitCallback(callback)
        }
        homeFrameCallback = null
        homeFrameObserver = null
    }

    private fun finish() {
        clearHomeFrameWait()
        activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
        activity.finish()
    }

    fun startBack(event: BackEventCompat) {
        if (closed || returning || activity.isFinishing || !animated()) return
        if (entering && entryGeometry != null) {
            entryExit = entryAppearance()
            entryResume = null
            previewing = true
            entryAnimator?.cancel()
            entryBackStart = entryProgress
            work.beginPreview()
            return
        }
        TransitionManager.endTransitions(stage)
        if (entering) {
            entering = false
            surface.visibility = View.VISIBLE
            work.endEnter()
        }
        prepareHomeBackdrop()
        if (!revealHome()) {
            clearHomeBackdrop()
            return
        }
        previewing = true
        work.beginPreview()
        backMotion.start(event)
        latestBackEvent = null
        if (!homeReady) whenHomeFrameReady {
            homeReady = true
            clearHomeBackdrop()
            if (previewing && !returning) latestBackEvent?.let(backMotion::update)
        }
    }

    fun progressBack(event: BackEventCompat) {
        if (previewing && !returning) {
            entryBackStart?.let { applyEntry(it * (1f - event.progress.coerceIn(0f, 1f))); return }
            latestBackEvent = event
            if (homeReady || homeBackdropNode != null) backMotion.update(event)
        }
    }

    fun cancelBack() {
        if (!previewing || returning) return
        previewing = false
        if (entryBackStart != null) {
            entryResume = entryAppearance()
            entryExit = null
            entryBackStart = null
            work.endPreview()
            animateEntry(1f)
            return
        }
        clearHomeFrameWait()
        backMotion.settle(animated()) {
            if (!closed && !returning && !previewing) {
                restore()
                work.endPreview()
            }
        }
    }

    fun stop() {
        if (closed) return
        if (entering && !returning) {
            entering = false
            entryAnimator?.cancel()
            entryAnimator = null
            entryBackStart = null
            previewing = false
            restore()
            work.endEnter()
        }
        if (returning) {
            if (!activity.isFinishing) {
                TransitionManager.endTransitions(stage)
                restore()
                if (!activity.isFinishing) finish()
            }
            return
        }
        previewing = false
        restore()
        work.endPreview()
    }

    private fun revealHome(): Boolean {
        // setTranslucent returns false when the window is already translucent.
        if (!translucent) {
            // Revealing a stopped activity can start a separate TO_FRONT animation. Its
            // window must stay still underneath our gesture and container transform.
            sourceActivity()?.let {
                    homeWindowAnimations = it.window.attributes.windowAnimations
                    it.window.setWindowAnimations(R.style.Animation_AdsBanner_PageReveal)
                    revealedHome = WeakReference(it)
                }
            if (!activity.setTranslucent(true)) {
                restoreHomeWindowAnimations()
                return false
            }
            translucent = true
            homeReady = false
            activity.window.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        }
        return true
    }

    private fun sourceActivity(): Activity? = generateSequence(source()?.context) {
        (it as? ContextWrapper)?.baseContext
    }.filterIsInstance<Activity>().firstOrNull()

    /** A short-lived display list bridges the parent's first buffer, so input needn't wait for it. */
    private fun prepareHomeBackdrop() {
        if (homeReady || homeBackdropNode != null) return
        val decor = sourceActivity()?.window?.decorView ?: return
        if (!decor.isAttachedToWindow || !decor.isHardwareAccelerated || decor.width <= 0 || decor.height <= 0) return
        source()?.takeUnless { it.isPressed }?.jumpDrawablesToCurrentState()
        val node = RenderNode("AdsBanner back preview background").apply {
            setPosition(0, 0, decor.width, decor.height)
        }
        val recording = node.beginRecording()
        try {
            recording.drawColor(background)
            decor.draw(recording)
        } finally { node.endRecording() }
        homeBackdropNode = node
        homeBackdrop.visibility = View.VISIBLE
        homeBackdrop.invalidate()
    }

    private fun clearHomeBackdrop() {
        homeBackdrop.visibility = View.INVISIBLE
        homeBackdropNode?.discardDisplayList()
        homeBackdropNode = null
    }

    private fun restoreHomeWindowAnimations() {
        revealedHome?.get()?.window?.setWindowAnimations(homeWindowAnimations)
        revealedHome = null
    }

    private fun setPoseFill(color: Int) {
        if (poseFill != color) {
            poseFill = color
            surface.setBackgroundColor(color)
        }
    }

    /** Transform the attached hierarchy; no image or one-off display list replaces the page. */
    private fun preparePagePose() {
        returnPose = surface
        surface.visibility = View.VISIBLE
    }

    fun close() {
        if (closed) return
        closed = true
        entryAnimator?.cancel()
        entryAnimator = null
        returnAnimator?.cancel()
        returnAnimator = null
        clearHomeFrameWait()
        restoreHomeWindowAnimations()
        // Do not finish the activity from a cancelled transition during destruction.
        returning = false
        previewing = false
        TransitionManager.endTransitions(stage)
        restore()
        stage.overlay.clear()
        stage.removeView(anchor)
        stage.removeView(homeBackdrop)
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
