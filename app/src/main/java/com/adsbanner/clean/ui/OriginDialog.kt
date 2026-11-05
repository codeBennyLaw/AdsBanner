package com.adsbanner.clean.ui

import android.animation.ValueAnimator
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.content.DialogInterface
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.descendants
import androidx.core.view.doOnPreDraw
import androidx.core.view.OneShotPreDrawListener
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.recyclerview.widget.RecyclerView
import androidx.transition.Transition
import androidx.transition.TransitionListenerAdapter
import androidx.transition.TransitionManager
import com.adsbanner.clean.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.card.MaterialCardView
import com.google.android.material.button.MaterialButton
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import com.google.android.material.shape.Shapeable
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
    private val originBounds = Rect().also { source?.getGlobalVisibleRect(it) }
    private var originOffsetX = 0
    private var originOffsetY = 0
    private lateinit var stage: FrameLayout
    private lateinit var panel: ViewGroup
    private lateinit var anchor: View
    private lateinit var scrim: View
    private var work: PageTransitionWork? = null
    private var returnMotion: PopupReturnMotion? = null
    private var entering = true
    private var closing = false
    private var finished = false
    private var actionTaken = false
    private var backPreview = false
    private var dropdown = false
    private var expandInPlace = false
    private var enterAnimator: ValueAnimator? = null
    private var enterSurface: PopupRenderSurface? = null
    private var enterGeometry: OriginReturnTransform? = null
    private var enterProgress = 0f
    private var entrySourceColor = 0
    private var entryPanelColor = 0
    private var originLayoutWait: OneShotPreDrawListener? = null
    private val dropdownInsets = Rect()
    private val scrimColor get() = if (dropdown) Color.TRANSPARENT else 0x52000000
    var onClosing: (() -> Unit)? = null
    private val frozen = mutableListOf<RecyclerView>()
    private val activityObserver = object : DefaultLifecycleObserver {
        override fun onStop(owner: LifecycleOwner) {
            if (::stage.isInitialized) TransitionManager.endTransitions(stage)
            if (closing) finishDismiss()
            else if (backPreview || returnMotion != null) restoreBackPreview()
        }
        override fun onDestroy(owner: LifecycleOwner) = finishDismiss()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val window = checkNotNull(window)
        panel = checkNotNull(findViewById(androidx.appcompat.R.id.parentPanel))
        val parent = panel.parent as ViewGroup
        parent.removeView(panel)
        stage = object : FrameLayout(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
                if (!dropdown && ::panel.isInitialized) {
                    val margins = panel.layoutParams as FrameLayout.LayoutParams
                    val available = (measuredHeight - margins.topMargin - margins.bottomMargin).coerceAtLeast(0)
                    // AlertDialog's custom panel can exceed AT_MOST when its form is tall.
                    // Give the native title/buttons a fixed safe height; its scroll content takes the remainder.
                    if (panel.measuredHeight > available) {
                        panel.measure(View.MeasureSpec.makeMeasureSpec(panel.measuredWidth, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(available, View.MeasureSpec.EXACTLY))
                    }
                }
            }
            override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
                super.onLayout(changed, left, top, right, bottom)
                if (!dropdown && ::panel.isInitialized) {
                    val margins = panel.layoutParams as FrameLayout.LayoutParams
                    // FrameLayout's CENTER shifts by the full difference between margins.
                    // Center inside the safe rectangle instead, especially when IME makes them unequal.
                    val y = margins.topMargin +
                        (height - margins.topMargin - margins.bottomMargin - panel.measuredHeight).coerceAtLeast(0) / 2
                    panel.layout(panel.left, y, panel.right, y + panel.measuredHeight)
                }
            }
        }.apply {
            id = R.id.motion_dialog_stage
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        scrim = View(context).apply {
            setBackgroundColor(scrimColor)
            alpha = 0f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        stage.addView(scrim, FrameLayout.LayoutParams(-1, -1))
        panel.background = if (dropdown) ContextCompat.getDrawable(context, R.drawable.bg_popup) else material.background
        panel.clipToOutline = true
        panel.isClickable = true
        stage.addView(panel, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        anchor = object : View(context) {
            override fun onDraw(canvas: Canvas) {
                // Only opening samples the entry. Closing retracts the existing popup instead.
                if (closing) return
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
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        ViewCompat.setOnApplyWindowInsetsListener(stage) { _, insets ->
            val bars = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val bottom = maxOf(bars.bottom, insets.getInsets(WindowInsetsCompat.Type.ime()).bottom)
            if (dropdown) {
                val gap = dp(12)
                dropdownInsets.set(bars.left + gap, bars.top + gap, bars.right + gap, bottom + gap)
                positionDropdown()
                return@setOnApplyWindowInsetsListener insets
            }
            val params = panel.layoutParams as FrameLayout.LayoutParams
            val gap = dp(24)
            if (params.leftMargin != bars.left + gap || params.topMargin != bars.top + gap ||
                params.rightMargin != bars.right + gap || params.bottomMargin != bottom + gap) {
                panel.layoutParams = params.apply {
                    leftMargin = bars.left + gap
                    topMargin = bars.top + gap
                    rightMargin = bars.right + gap
                    bottomMargin = bottom + gap
                }
            }
            insets
        }
        val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !night
            isAppearanceLightNavigationBars = !night
        }
        stage.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
            if (dropdown) {
                positionDropdown()
                return@addOnLayoutChangeListener
            }
            val params = panel.layoutParams as FrameLayout.LayoutParams
            val width = (right - left - params.leftMargin - params.rightMargin).coerceAtMost(dp(560))
            if (width > 0 && panel.layoutParams.width != width) {
                panel.layoutParams = (panel.layoutParams as FrameLayout.LayoutParams).apply { this.width = width }
            }
        }
        work = PageTransitionWork(panel, entering = true)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackStarted(backEvent: BackEventCompat) {
                if (entering || closing || finished || !animationsEnabled() || panel.width == 0 ||
                    panel.height == 0 || !placeAnchor()) return
                backPreview = true
                work?.beginPreview()
                freezeLists()
                returnMotion = (returnMotion ?: PopupReturnMotion(panel, scrim, originBounds, ::finishDismiss,
                    originCornerRadius(), originContainerColor())).also { it.preview() }
            }
            override fun handleOnBackProgressed(backEvent: BackEventCompat) {
                if (backPreview && !closing) returnMotion?.seek(backEvent.progress)
            }
            override fun handleOnBackCancelled() {
                if (!backPreview || closing) return
                backPreview = false
                if (!animationsEnabled()) restoreBackPreview()
                else returnMotion?.settleBack {
                    if (!closing && !backPreview) restoreBackPreview()
                }
            }
            override fun handleOnBackPressed() { cancel() }
        })
        activity.lifecycle.addObserver(activityObserver)
        stage.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                stage.viewTreeObserver.removeOnPreDrawListener(this)
                if (finished) return true
                if (closing) { finishDismiss(); return false }
                if (animationsEnabled() && expandInPlace) {
                    startPanelEnter()
                    return true
                }
                if (!animationsEnabled() || !placeAnchor()) {
                    entering = false
                    scrim.alpha = 1f
                    work?.completeImmediately()
                    return true
                }
                startEnterTransform()
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

    /** Keep a compact menu next to its button, flipping above it when space is limited. */
    @SuppressLint("RtlHardcoded") // Bounds and margins are physical screen coordinates.
    private fun positionDropdown() {
        val source = validOrigin() ?: return
        if (stage.width == 0 || stage.height == 0 || closing) return
        val bounds = Rect()
        if (!source.getGlobalVisibleRect(bounds)) return
        val location = IntArray(2)
        stage.getLocationOnScreen(location)
        bounds.offset(-location[0], -location[1])
        val availableWidth = stage.width - dropdownInsets.left - dropdownInsets.right
        if (availableWidth <= 0) return
        val width = source.width.coerceAtLeast(dp(200)).coerceAtMost(dp(320)).coerceAtMost(availableWidth)
        val below = (stage.height - dropdownInsets.bottom - bounds.bottom - dp(4)).coerceAtLeast(0)
        val above = (bounds.top - dropdownInsets.top - dp(4)).coerceAtLeast(0)
        val availableHeight = maxOf(below, above)
        if (availableHeight == 0) return
        panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(availableHeight, View.MeasureSpec.AT_MOST))
        val height = panel.measuredHeight.coerceAtMost(availableHeight)
        val left = (if (source.layoutDirection == View.LAYOUT_DIRECTION_RTL) bounds.right - width else bounds.left)
            .coerceIn(dropdownInsets.left, stage.width - dropdownInsets.right - width)
        val top = if (height <= below) bounds.bottom + dp(4) else bounds.top - dp(4) - height
        val params = panel.layoutParams as FrameLayout.LayoutParams
        if (params.width != width || params.height != height || params.leftMargin != left || params.topMargin != top) {
            panel.layoutParams = params.apply {
                gravity = Gravity.TOP or Gravity.LEFT
                this.width = width
                this.height = height
                leftMargin = left
                topMargin = top
                rightMargin = stage.width - left - width
                bottomMargin = stage.height - top - height
            }
        }
    }

    private fun placeAnchor(): Boolean {
        val source = validOrigin()
        if (source != null) {
            if (closing) clearOriginFeedback(source) else Motion.resetPressFeedback(source)
            source.getGlobalVisibleRect(originBounds)
            val location = IntArray(2)
            source.getLocationOnScreen(location)
            if (dropdown && source is MaterialButton && !originBounds.intersect(
                    location[0], location[1] + source.insetTop,
                    location[0] + source.width, location[1] + source.height - source.insetBottom)) {
                originBounds.setEmpty()
            }
            originOffsetX = originBounds.left - location[0]
            originOffsetY = originBounds.top - location[1]
        }
        if (originBounds.isEmpty) return false
        val destination = originBounds
        val location = IntArray(2)
        stage.getLocationOnScreen(location)
        anchor.layoutParams = FrameLayout.LayoutParams(destination.width(), destination.height()).apply {
            gravity = Gravity.TOP or Gravity.LEFT // Screen coordinates, independent of layout direction.
            leftMargin = destination.left - location[0]
            topMargin = destination.top - location[1]
        }
        // Immediate geometry is needed for transition capture; no per-frame layout changes.
        anchor.measure(View.MeasureSpec.makeMeasureSpec(destination.width(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(destination.height(), View.MeasureSpec.EXACTLY))
        anchor.layout(destination.left - location[0], destination.top - location[1],
            destination.right - location[0], destination.bottom - location[1])
        return true
    }

    private fun startEnterTransform() {
        if (Build.VERSION.SDK_INT >= 29 && startRecordedEnter()) return
        val rounded = (validOrigin() as? Shapeable)?.shapeAppearanceModel
            ?: ShapeAppearanceModel.builder().setAllCornerSizes(context.resources.getDimension(R.dimen.corner_card)).build()
        val dialogShape = ShapeAppearanceModel.builder().setAllCornerSizes(
            context.resources.getDimension(if (dropdown) R.dimen.corner_card else R.dimen.corner_dialog)).build()
        val sourceColor = originContainerColor()
        val dialogColor = context.getColor(R.color.surface)
        val transition = MaterialContainerTransform().apply {
            drawingViewId = stage.id
            startView = anchor
            endView = panel
            addTarget(panel)
            startShapeAppearanceModel = rounded
            endShapeAppearanceModel = dialogShape
            duration = 280L
            transitionDirection = MaterialContainerTransform.TRANSITION_DIRECTION_ENTER
            fadeMode = MaterialContainerTransform.FADE_MODE_CROSS
            interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
            fadeProgressThresholds = MaterialContainerTransform.ProgressThresholds(0.05f, 0.4f)
            scaleMaskProgressThresholds = MaterialContainerTransform.ProgressThresholds(0f, 1f)
            shapeMaskProgressThresholds = MaterialContainerTransform.ProgressThresholds(0f, 1f)
            containerColor = sourceColor
            startContainerColor = sourceColor
            endContainerColor = dialogColor
            scrimColor = this@OriginDialog.scrimColor
            addListener(object : TransitionListenerAdapter() {
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
        work?.beginEnter()
        anchor.visibility = View.VISIBLE
        panel.visibility = View.INVISIBLE
        TransitionManager.beginDelayedTransition(stage, transition)
        scrim.alpha = 0f
        anchor.visibility = View.INVISIBLE
        panel.visibility = View.VISIBLE
    }

    @androidx.annotation.RequiresApi(29)
    private fun startRecordedEnter(): Boolean {
        val surface = PopupRenderSurface.capture(panel) ?: return false
        enterSurface = surface
        entrySourceColor = originContainerColor()
        entryPanelColor = context.getColor(R.color.surface)
        enterGeometry = OriginReturnTransform(surface.view, originBounds, originCornerRadius())
        work?.beginEnter()
        applyRecordedEnter(0f)
        animateRecordedEnter(1f)
        return true
    }

    @androidx.annotation.RequiresApi(29)
    private fun applyRecordedEnter(progress: Float) {
        enterProgress = progress
        enterGeometry?.apply(1f - progress)
        enterSurface?.apply {
            view.setBackgroundColor(ColorUtils.blendARGB(entrySourceColor, entryPanelColor,
                motionFraction(0.05f, 0.9f, progress)))
            setContentAlpha(motionFraction(0.1f, 0.72f, progress))
        }
        scrim.alpha = progress
    }

    @androidx.annotation.RequiresApi(29)
    private fun animateRecordedEnter(destination: Float) {
        enterAnimator = ValueAnimator.ofFloat(enterProgress, destination).apply {
            duration = (280L * kotlin.math.abs(destination - enterProgress)).toLong().coerceAtLeast(70L)
            interpolator = if (destination == 1f) PathInterpolator(0.2f, 0f, 0f, 1f)
                else PathInterpolator(0.3f, 0f, 0.6f, 1f)
            addUpdateListener { applyRecordedEnter(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    enterAnimator = null
                    if (finished) return
                    if (closing) {
                        if (destination == 0f) finishDismiss()
                    } else {
                        enterSurface?.release(restore = true)
                        enterSurface = null
                        enterGeometry = null
                        entering = false
                        restoreOrigin()
                        work?.endEnter()
                    }
                }
            })
            start()
        }
    }

    private fun startPanelEnter() {
        work?.beginEnter()
        panel.pivotX = panel.width / 2f
        panel.pivotY = panel.height / 2f
        enterAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 240L
            interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener {
                val progress = it.animatedValue as Float
                panel.alpha = progress
                panel.scaleX = 0.96f + progress * 0.04f
                panel.scaleY = panel.scaleX
                scrim.alpha = progress
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    enterAnimator = null
                    if (!closing && !finished) {
                        entering = false
                        restoreOrigin()
                        work?.endEnter()
                    }
                }
            })
            start()
        }
    }

    private fun originContainerColor(): Int {
        var view = validOrigin()
        while (view != null) {
            val background = view.background
            val color = when {
                view is MaterialCardView -> view.cardBackgroundColor.getColorForState(view.drawableState,
                    view.cardBackgroundColor.defaultColor)
                view is MaterialButton -> view.backgroundTintList?.getColorForState(view.drawableState,
                    view.backgroundTintList?.defaultColor ?: Color.TRANSPARENT)
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
        backPreview = false
        onClosing?.invoke()
        work?.beginReturn()
        if (!::stage.isInitialized || !isShowing || activity.isFinishing || activity.isDestroyed ||
            !animationsEnabled() || !stage.isAttachedToWindow) {
            finishDismiss()
            return
        }
        freezeLists()
        if (entering && enterSurface != null && Build.VERSION.SDK_INT >= 29) {
            enterAnimator?.cancel()
            animateRecordedEnter(0f)
            return
        }
        if (entering && !expandInPlace) {
            // An immediate back cancels the unfinished opening without flashing its final frame.
            finishDismiss()
        } else if (panel.width > 0 && panel.height > 0 && placeAnchor()) {
            // A quick back continues from the visible opening pose rather than snapping shut.
            entering = false
            enterAnimator?.cancel()
            enterAnimator = null
            val source = validOrigin()
            if (returnMotion == null && source?.isLayoutRequested == true) {
                originLayoutWait = source.doOnPreDraw {
                    originLayoutWait = null
                    if (!finished && closing) startRetraction()
                }
            } else startRetraction()
        } else finishDismiss()
    }

    private fun startRetraction() {
        if (!placeAnchor()) { finishDismiss(); return }
        val motion = returnMotion ?: PopupReturnMotion(panel, scrim, originBounds, ::finishDismiss,
            originCornerRadius(), originContainerColor())
        returnMotion = motion
        motion.start()
    }

    private fun restoreOrigin() {
        validOrigin()?.let {
            clearOriginFeedback(it)
        }
    }

    private fun originCornerRadius(): Float {
        val source = validOrigin()
        return (source as? Shapeable)?.shapeAppearanceModel?.topLeftCornerSize?.getCornerSize(
            RectF(0f, 0f, source.width.toFloat(), source.height.toFloat()))
            ?: context.resources.getDimension(R.dimen.corner_card)
    }

    private fun freezeLists() {
        panel.descendants.filterIsInstance<RecyclerView>().forEach {
            if (it !in frozen) {
                it.stopScroll(); it.itemAnimator?.endAnimations(); it.suppressLayout(true)
                frozen.add(it)
            }
        }
    }

    private fun restoreBackPreview() {
        backPreview = false
        returnMotion?.restore()
        returnMotion = null
        frozen.forEach { it.suppressLayout(false) }
        frozen.clear()
        work?.endPreview()
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
        originLayoutWait?.removeListener()
        originLayoutWait = null
        enterAnimator?.cancel()
        enterAnimator = null
        if (Build.VERSION.SDK_INT >= 29) enterSurface?.release(restore = false)
        enterSurface = null
        enterGeometry = null
        returnMotion?.cancel()
        returnMotion = null
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
        fun asDropdown() = apply { dialog.dropdown = true }
        fun expandInPlace() = apply { dialog.expandInPlace = true }
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
