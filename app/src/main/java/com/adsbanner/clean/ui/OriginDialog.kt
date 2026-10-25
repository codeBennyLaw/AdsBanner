package com.adsbanner.clean.ui

import android.animation.ValueAnimator
import android.content.DialogInterface
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.descendants
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.recyclerview.widget.RecyclerView
import androidx.transition.Transition
import androidx.transition.TransitionListenerAdapter
import androidx.transition.TransitionManager
import com.adsbanner.clean.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.card.MaterialCardView
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import com.google.android.material.transition.MaterialContainerTransform
import java.lang.ref.WeakReference

/** Native alert content in a transparent stage, so its container can reach an external anchor. */
internal class OriginDialog private constructor(
    private val activity: AppCompatActivity,
    source: View?,
    private val material: MaterialAlertDialogBuilder
) : AlertDialog(material.context, R.style.ThemeOverlay_AdsBanner_OriginDialog) {
    private val origin = WeakReference(source)
    private val originDescription = source?.contentDescription?.toString()
    private val originAlpha = source?.alpha ?: 1f
    private val originBounds = Rect().also { source?.getGlobalVisibleRect(it) }
    private var originOffsetX = 0
    private var originOffsetY = 0
    private lateinit var stage: FrameLayout
    private lateinit var panel: ViewGroup
    private lateinit var anchor: View
    private lateinit var scrim: View
    private var work: PageTransitionWork? = null
    private var entering = true
    private var closing = false
    private var finished = false
    private var actionTaken = false
    var onClosing: (() -> Unit)? = null
    private val frozen = mutableListOf<RecyclerView>()
    private val activityObserver = object : DefaultLifecycleObserver {
        override fun onStop(owner: LifecycleOwner) {
            if (::stage.isInitialized) TransitionManager.endTransitions(stage)
            if (closing) finishDismiss()
        }
        override fun onDestroy(owner: LifecycleOwner) = finishDismiss()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val window = checkNotNull(window)
        panel = checkNotNull(findViewById(androidx.appcompat.R.id.parentPanel))
        val parent = panel.parent as ViewGroup
        parent.removeView(panel)
        stage = FrameLayout(context).apply {
            id = R.id.motion_dialog_stage
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        scrim = View(context).apply {
            setBackgroundColor(0x52000000)
            alpha = 0f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        stage.addView(scrim, FrameLayout.LayoutParams(-1, -1))
        panel.background = material.background
        panel.clipToOutline = true
        panel.isClickable = true
        stage.addView(panel, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        anchor = object : View(context) {
            override fun onDraw(canvas: Canvas) {
                canvas.save()
                canvas.translate(-originOffsetX.toFloat(), -originOffsetY.toFloat())
                validOrigin()?.draw(canvas)
                canvas.restore()
            }
        }.apply { visibility = View.INVISIBLE; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        stage.addView(anchor, FrameLayout.LayoutParams(1, 1))
        parent.addView(stage, ViewGroup.LayoutParams(-1, -1))
        stage.setOnClickListener { cancel() }
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.setWindowAnimations(0)
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(stage) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            panel.layoutParams = (panel.layoutParams as FrameLayout.LayoutParams).apply {
                leftMargin = bars.left + dp(24)
                topMargin = bars.top + dp(24)
                rightMargin = bars.right + dp(24)
                bottomMargin = bars.bottom + dp(24)
            }
            insets
        }
        val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !night
            isAppearanceLightNavigationBars = !night
        }
        stage.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
            val params = panel.layoutParams as FrameLayout.LayoutParams
            val width = (right - left - params.leftMargin - params.rightMargin).coerceAtMost(dp(560))
            if (width > 0 && panel.layoutParams.width != width) {
                panel.layoutParams = (panel.layoutParams as FrameLayout.LayoutParams).apply { this.width = width }
            }
        }
        work = PageTransitionWork(panel, entering = true)
        activity.lifecycle.addObserver(activityObserver)
        stage.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                stage.viewTreeObserver.removeOnPreDrawListener(this)
                if (finished) return true
                if (closing) { finishDismiss(); return false }
                if (!animationsEnabled() || !placeAnchor()) {
                    entering = false
                    scrim.alpha = 1f
                    work?.completeImmediately()
                    return true
                }
                startTransform(forward = true)
                // Capture the final state on the next pre-draw, before displaying any dialog frame.
                return false
            }
        })
    }

    override fun show() {
        validOrigin()?.let(Motion::resetPressFeedback)
        super.show()
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    fun whenReady(key: Any, task: () -> Unit) {
        if (!closing && !finished) work?.submit(key, task) ?: task()
    }

    private fun validOrigin(): View? = origin.get()?.takeIf {
        it.isAttachedToWindow && it.isShown && it.contentDescription?.toString() == originDescription
    }

    private fun placeAnchor(): Boolean {
        val source = validOrigin()
        if (source != null) {
            if (closing) clearOriginFeedback(source) else Motion.resetPressFeedback(source)
            source.getGlobalVisibleRect(originBounds)
            val location = IntArray(2)
            source.getLocationOnScreen(location)
            originOffsetX = originBounds.left - location[0]
            originOffsetY = originBounds.top - location[1]
        }
        if (originBounds.isEmpty) return false
        val location = IntArray(2)
        stage.getLocationOnScreen(location)
        anchor.layoutParams = FrameLayout.LayoutParams(originBounds.width(), originBounds.height()).apply {
            gravity = Gravity.TOP or Gravity.LEFT // Screen coordinates, independent of layout direction.
            leftMargin = originBounds.left - location[0]
            topMargin = originBounds.top - location[1]
        }
        // Immediate geometry is needed for transition capture; no per-frame layout changes.
        anchor.measure(View.MeasureSpec.makeMeasureSpec(originBounds.width(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(originBounds.height(), View.MeasureSpec.EXACTLY))
        anchor.layout(originBounds.left - location[0], originBounds.top - location[1],
            originBounds.right - location[0], originBounds.bottom - location[1])
        return true
    }

    private fun startTransform(forward: Boolean) {
        val rounded = ShapeAppearanceModel.builder().setAllCornerSizes(dp(20).toFloat()).build()
        val dialogShape = ShapeAppearanceModel.builder().setAllCornerSizes(dp(28).toFloat()).build()
        val sourceColor = originContainerColor()
        val dialogColor = context.getColor(R.color.surface)
        val transition = MaterialContainerTransform().apply {
            drawingViewId = stage.id
            startView = if (forward) anchor else panel
            endView = if (forward) panel else anchor
            addTarget(if (forward) panel else anchor)
            startShapeAppearanceModel = if (forward) rounded else dialogShape
            endShapeAppearanceModel = if (forward) dialogShape else rounded
            duration = if (forward) 280L else 220L
            transitionDirection = if (forward) MaterialContainerTransform.TRANSITION_DIRECTION_ENTER
                else MaterialContainerTransform.TRANSITION_DIRECTION_RETURN
            fadeMode = MaterialContainerTransform.FADE_MODE_THROUGH
            // Transparent entry buttons belong to their parent's surface. Painting the dialog
            // color underneath them creates a bright plate at both ends of the transform.
            containerColor = sourceColor
            startContainerColor = if (forward) sourceColor else dialogColor
            endContainerColor = if (forward) dialogColor else sourceColor
            scrimColor = 0x52000000
            addListener(object : TransitionListenerAdapter() {
                override fun onTransitionStart(transition: Transition) {
                    validOrigin()?.alpha = 0f
                }
                override fun onTransitionEnd(transition: Transition) {
                    restoreOrigin()
                    if (closing) finishDismiss() else {
                        entering = false
                        scrim.alpha = 1f
                        work?.endEnter()
                    }
                }
            })
        }
        if (forward) work?.beginEnter()
        anchor.visibility = if (forward) View.VISIBLE else View.INVISIBLE
        panel.visibility = if (forward) View.INVISIBLE else View.VISIBLE
        TransitionManager.beginDelayedTransition(stage, transition)
        scrim.alpha = 0f
        anchor.visibility = if (forward) View.INVISIBLE else View.VISIBLE
        panel.visibility = if (forward) View.VISIBLE else View.INVISIBLE
    }

    private fun originContainerColor(): Int {
        var view = validOrigin()
        while (view != null) {
            val background = view.background
            val color = when {
                view is MaterialCardView -> view.cardBackgroundColor.getColorForState(view.drawableState,
                    view.cardBackgroundColor.defaultColor)
                background is ColorDrawable -> background.color
                background is MaterialShapeDrawable -> background.fillColor?.getColorForState(view.drawableState,
                    background.fillColor?.defaultColor ?: Color.TRANSPARENT)
                background is GradientDrawable -> background.color?.getColorForState(view.drawableState,
                    background.color?.defaultColor ?: Color.TRANSPARENT)
                else -> null
            }
            // Ripple masks are not a button's fill. Continue to the actual opaque surface.
            if (color != null && Color.alpha(color) == 255) return color
            view = view.parent as? View
        }
        return context.getColor(R.color.surface)
    }

    override fun dismiss() {
        if (closing || finished) return
        closing = true
        onClosing?.invoke()
        work?.beginReturn()
        if (!::stage.isInitialized || !isShowing || activity.isFinishing || activity.isDestroyed ||
            !animationsEnabled() || !stage.isAttachedToWindow) {
            finishDismiss()
            return
        }
        panel.descendants.filterIsInstance<RecyclerView>().forEach {
            it.stopScroll()
            it.suppressLayout(true)
            frozen.add(it)
        }
        if (entering) {
            // An immediate back cancels the unfinished opening without flashing its final frame.
            finishDismiss()
        } else if (placeAnchor()) startTransform(forward = false) else finishDismiss()
    }

    private fun restoreOrigin() {
        validOrigin()?.let {
            clearOriginFeedback(it)
            it.alpha = originAlpha
        }
    }

    private fun clearOriginFeedback(view: View) {
        // Let the initiating click ripple run while opening, then settle it before revealing
        // the source again. A return transform must never replay the opening click.
        Motion.resetPressFeedback(view)
        view.isPressed = false
        view.jumpDrawablesToCurrentState()
    }

    private fun finishDismiss() {
        if (finished) return
        finished = true
        if (!closing) { closing = true; onClosing?.invoke() }
        work?.close()
        if (::stage.isInitialized) TransitionManager.endTransitions(stage)
        restoreOrigin()
        frozen.forEach { it.suppressLayout(false) }
        frozen.clear()
        activity.lifecycle.removeObserver(activityObserver)
        super.dismiss()
    }

    fun dismissImmediately() = finishDismiss()

    private fun animationsEnabled() = ValueAnimator.areAnimatorsEnabled() &&
        android.provider.Settings.Global.getFloat(activity.contentResolver,
            android.provider.Settings.Global.TRANSITION_ANIMATION_SCALE, 1f) > 0f
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    class Builder(activity: AppCompatActivity, source: View?) {
        private val dialog = OriginDialog(activity, source, MaterialAlertDialogBuilder(activity))
        fun setTitle(title: CharSequence) = apply { dialog.setTitle(title) }
        fun setMessage(message: CharSequence) = apply { dialog.setMessage(message) }
        fun setView(view: View) = apply { dialog.setView(view) }
        private fun button(which: Int, text: String, listener: DialogInterface.OnClickListener?) = apply {
            dialog.setButton(which, text) { target, id ->
                if (!dialog.actionTaken && !dialog.closing) {
                    dialog.actionTaken = true
                    listener?.onClick(target, id)
                }
            }
        }
        fun setPositiveButton(text: String, listener: DialogInterface.OnClickListener?) = button(BUTTON_POSITIVE, text, listener)
        fun setNegativeButton(text: String, listener: DialogInterface.OnClickListener?) = button(BUTTON_NEGATIVE, text, listener)
        fun setNeutralButton(text: String, listener: DialogInterface.OnClickListener?) = button(BUTTON_NEUTRAL, text, listener)
        fun create() = dialog
        fun show() = dialog.apply { show() }
    }
}
