package com.adsbanner.clean.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityOptions
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderNode
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import androidx.annotation.RequiresApi
import androidx.activity.BackEventCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.core.graphics.withScale
import androidx.core.view.descendants
import androidx.recyclerview.widget.RecyclerView
import androidx.transition.Transition
import androidx.transition.TransitionListenerAdapter
import androidx.transition.TransitionManager
import com.adsbanner.clean.R
import com.google.android.material.shape.ShapeAppearanceModel
import com.google.android.material.transition.MaterialContainerTransform
import java.lang.ref.WeakReference
import kotlin.math.roundToInt

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
    private var latestBackEvent: BackEventCompat? = null
    private var revealedHome: WeakReference<Activity>? = null
    private var homeWindowAnimations = 0
    private val backMotion = BackProgressMotion(page)
    private var returnPose: View? = null
    private var returnNode: RenderNode? = null
    private var homeFrameObserver: ViewTreeObserver? = null
    private var homeFrameCallback: Runnable? = null
    private var returnRadius = 0f
    private val anchor = object : View(activity) {
        override fun onDraw(canvas: Canvas) { if (!returning) source()?.draw(canvas) }
    }.apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO; visibility = View.INVISIBLE }

    init {
        pendingOrigin = null
        stage.addView(anchor, ViewGroup.LayoutParams(1, 1))
        if (!restored && animated() && placeAnchor() && revealHome()) {
            page.visibility = View.INVISIBLE
            anchor.visibility = View.VISIBLE
            stage.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    stage.viewTreeObserver.removeOnPreDrawListener(this)
                    if (!entering || closed || returning || previewing || activity.isFinishing) return true
                    // Record only after layout, then let the transform capture its end state
                    // on the next pre-draw. Never submit the full-size snapshot in between.
                    capturePagePose()
                    morph(forward = true)
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
            startView = if (forward) anchor else returnPose ?: page
            endView = if (forward) returnPose ?: page else anchor
            addTarget(if (forward) returnPose ?: page else anchor)
            startShapeAppearanceModel = if (forward) cardShape else
                ShapeAppearanceModel.builder().setAllCornerSizes(returnRadius).build()
            endShapeAppearanceModel = if (forward) pageShape else cardShape
            startContainerColor = if (forward) color else background
            endContainerColor = if (forward) background else Color.TRANSPARENT
            scrimColor = Color.TRANSPARENT
            duration = if (!forward && returningFromGesture) 280L else 320L
            fadeMode = MaterialContainerTransform.FADE_MODE_CROSS
            // A released gesture is already moving: continue immediately and decelerate.
            // A tap starts at rest. Both routes settle into exactly the same card geometry.
            interpolator = if (!forward && returningFromGesture) PathInterpolator(0.2f, 0.6f, 0.2f, 1f)
                else PathInterpolator(0.2f, 0f, 0f, 1f)
            fadeProgressThresholds = if (forward) MaterialContainerTransform.ProgressThresholds(0.05f, 0.4f)
                else MaterialContainerTransform.ProgressThresholds(0.35f, 0.9f)
            scaleMaskProgressThresholds = MaterialContainerTransform.ProgressThresholds(0f, 1f)
            shapeMaskProgressThresholds = MaterialContainerTransform.ProgressThresholds(0f, 1f)
            transitionDirection = if (forward) MaterialContainerTransform.TRANSITION_DIRECTION_ENTER
                else MaterialContainerTransform.TRANSITION_DIRECTION_RETURN
            // Keep the final card covering the child window until the home card has drawn.
            isHoldAtEndEnabled = !forward
            addListener(object : TransitionListenerAdapter() {
                override fun onTransitionEnd(transition: Transition) {
                    entering = false
                    if (returning) handOffToHome() else {
                        restore()
                        work.endEnter()
                    }
                }
            })
        }
        if (forward) work.beginEnter()
        TransitionManager.beginDelayedTransition(stage, transition)
        returnPose?.visibility = if (forward) View.VISIBLE else View.INVISIBLE
        anchor.visibility = if (forward) View.INVISIBLE else View.VISIBLE
        page.visibility = if (forward && returnPose == null) View.VISIBLE else View.INVISIBLE
    }

    fun returnToOrigin() {
        if (closed || returning || activity.isFinishing) return
        if (entering) {
            close()
            finish()
            return
        }
        returning = true
        returningFromGesture = previewing
        previewing = false
        backMotion.freeze()
        if (!animated() || page.width == 0 || page.height == 0 || !placeAnchor() || !revealHome()) {
            finish()
            return
        }
        fun startReturn() {
            capturePagePose()
            // The pose is already placed. Prepare the overlay before the next draw.
            morph(forward = false)
        }
        if (homeReady) startReturn() else whenHomeFrameReady {
            homeReady = true
            startReturn()
        }
    }

    private fun restore() {
        clearHomeFrameWait()
        latestBackEvent = null
        anchor.visibility = View.INVISIBLE
        // A committed return must leave the child window transparent until it is removed.
        // Showing the full page here can expose one last stale frame over the home activity.
        page.visibility = if (!returning && !closed && !activity.isFinishing) View.VISIBLE else View.INVISIBLE
        backMotion.reset()
        returnPose?.let { stage.removeView(it) }
        returnPose = null
        returnNode?.discardDisplayList()
        returnNode = null
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
        whenHomeFrameReady { finish() }
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
        val interruptedEntry = entering
        TransitionManager.endTransitions(stage)
        if (interruptedEntry) {
            entering = false
            restore()
            work.endEnter()
        }
        if (!revealHome()) return
        previewing = true
        work.beginPreview()
        page.descendants.filterIsInstance<RecyclerView>().forEach {
            it.stopScroll()
            it.itemAnimator?.endAnimations()
        }
        backMotion.start(event)
        latestBackEvent = null
        if (!homeReady) whenHomeFrameReady {
            homeReady = true
            if (previewing && !returning) latestBackEvent?.let(backMotion::update)
        }
    }

    fun progressBack(event: BackEventCompat) {
        if (previewing && !returning) {
            latestBackEvent = event
            if (homeReady) backMotion.update(event)
        }
    }

    fun cancelBack() {
        if (!previewing || returning) return
        previewing = false
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
            generateSequence(source()?.context) { (it as? ContextWrapper)?.baseContext }
                .filterIsInstance<Activity>().firstOrNull()?.let {
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

    private fun restoreHomeWindowAnimations() {
        revealedHome?.get()?.window?.setWindowAnimations(homeWindowAnimations)
        revealedHome = null
    }

    /** Material's bounds capture ignores scale. Hand it a live view at the exact gesture pose. */
    @SuppressLint("RtlHardcoded")
    private fun capturePagePose() {
        val bounds = RectF(0f, 0f, page.width.toFloat(), page.height.toFloat())
        page.matrix.mapRect(bounds)
        bounds.offset(page.left.toFloat(), page.top.toFloat())
        returnRadius = backMotion.cornerRadius
        // Record the settled page once. Replaying its display list avoids traversing the
        // entire RecyclerView hierarchy for every frame of the container transform.
        val node = RenderNode("AdsBanner page morph").apply { setPosition(0, 0, page.width, page.height) }
        val recording = node.beginRecording()
        try { page.draw(recording) } finally { node.endRecording() }
        returnNode = node
        val pose = object : View(activity) {
            override fun onDraw(canvas: Canvas) {
                canvas.withScale(width.toFloat() / page.width, height.toFloat() / page.height) {
                    if (isHardwareAccelerated && node.hasDisplayList()) drawRenderNode(node)
                    else page.draw(this)
                }
            }
        }.apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = FrameLayout.LayoutParams(bounds.width().roundToInt(), bounds.height().roundToInt()).apply {
                gravity = Gravity.TOP or Gravity.LEFT
                leftMargin = bounds.left.roundToInt()
                topMargin = bounds.top.roundToInt()
            }
        }
        stage.addView(pose)
        pose.measure(View.MeasureSpec.makeMeasureSpec(bounds.width().roundToInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(bounds.height().roundToInt(), View.MeasureSpec.EXACTLY))
        pose.layout(bounds.left.roundToInt(), bounds.top.roundToInt(), bounds.right.roundToInt(), bounds.bottom.roundToInt())
        returnPose = pose
        page.visibility = View.INVISIBLE
        backMotion.reset()
    }

    fun close() {
        if (closed) return
        closed = true
        clearHomeFrameWait()
        restoreHomeWindowAnimations()
        // Do not finish the activity from a cancelled transition during destruction.
        returning = false
        previewing = false
        TransitionManager.endTransitions(stage)
        restore()
        stage.overlay.clear()
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
