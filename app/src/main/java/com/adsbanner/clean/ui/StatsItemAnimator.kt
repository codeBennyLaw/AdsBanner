package com.adsbanner.clean.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.View
import android.view.animation.PathInterpolator
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator

/** Independent row motions can be retargeted from their current pose without moving the header. */
internal class StatsItemAnimator : SimpleItemAnimator() {
    private enum class Kind { ADD, REMOVE, MOVE, CHANGE }
    private class Effect(
        val holder: RecyclerView.ViewHolder,
        val kind: Kind,
        val x: Float,
        val y: Float,
        val alpha: Float,
        val contentAlpha: Float
    ) {
        var animator: ValueAnimator? = null
    }

    private val effects = LinkedHashMap<RecyclerView.ViewHolder, Effect>()
    private val pending = mutableListOf<Effect>()
    private val easing = PathInterpolator(0.2f, 0f, 0.1f, 1f)
    private var emptyMotion: ValueAnimator? = null
    private var emptyView: View? = null
    private var endingAll = false
    private var allowed = false
    var enabled: Boolean
        get() = allowed
        set(value) {
            allowed = value
            if (!value) endAnimations()
        }

    init {
        supportsChangeAnimations = false
        addDuration = 180L
        removeDuration = 140L
        moveDuration = 240L
        changeDuration = 220L
    }

    fun showEmpty(view: View, visible: Boolean) {
        val wasVisible = view.isVisible
        val startAlpha = if (wasVisible) view.alpha else 0f
        cancelEmptyMotion()
        emptyView = view
        view.isVisible = visible
        if (!visible || !enabled || !ValueAnimator.areAnimatorsEnabled() || startAlpha == 1f) {
            view.alpha = 1f
            return
        }
        // Let departing cards clear the same space before the empty message appears.
        view.alpha = startAlpha
        val animation = ValueAnimator.ofFloat(startAlpha, 1f).apply {
            startDelay = removeDuration
            duration = addDuration
            interpolator = easing
            addUpdateListener { view.alpha = it.animatedValue as Float }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (emptyMotion === animation) {
                        emptyMotion = null
                        view.alpha = 1f
                        if (!isRunning) dispatchAnimationsFinished()
                    }
                }
            })
        }
        emptyMotion = animation
        animation.start()
    }

    override fun animateAdd(holder: RecyclerView.ViewHolder): Boolean {
        val view = holder.itemView
        val interrupted = effects.containsKey(holder)
        val alpha = if (interrupted) view.alpha else 0f
        val y = if (interrupted) view.translationY else 8f * view.resources.displayMetrics.density
        endAnimation(holder)
        return enqueue(Effect(holder, Kind.ADD, 0f, y, alpha, 1f))
    }

    override fun animateRemove(holder: RecyclerView.ViewHolder): Boolean {
        val view = holder.itemView
        val effect = Effect(holder, Kind.REMOVE, view.translationX, view.translationY, view.alpha, contentAlpha(holder))
        endAnimation(holder)
        return enqueue(effect)
    }

    override fun animateMove(holder: RecyclerView.ViewHolder, fromX: Int, fromY: Int, toX: Int, toY: Int): Boolean =
        reposition(holder, fromX, fromY, toX, toY, (holder as? StatAppAdapter.Holder)?.valuesChanged == true)

    override fun animateChange(oldHolder: RecyclerView.ViewHolder, newHolder: RecyclerView.ViewHolder?,
        fromX: Int, fromY: Int, toX: Int, toY: Int): Boolean {
        if (oldHolder !== newHolder) {
            endAnimation(oldHolder)
            dispatchChangeFinished(oldHolder, true)
            newHolder?.let { endAnimation(it); dispatchChangeFinished(it, false) }
            return false
        }
        return reposition(oldHolder, fromX, fromY, toX, toY, changed = true)
    }

    override fun animatePersistence(holder: RecyclerView.ViewHolder, preInfo: ItemHolderInfo, postInfo: ItemHolderInfo): Boolean {
        // A range/type change can update values while leaving the app in the same position.
        return reposition(holder, preInfo.left, preInfo.top, postInfo.left, postInfo.top,
            (holder as? StatAppAdapter.Holder)?.valuesChanged == true)
    }

    private fun reposition(holder: RecyclerView.ViewHolder, fromX: Int, fromY: Int, toX: Int, toY: Int, changed: Boolean): Boolean {
        val view = holder.itemView
        val x = fromX + view.translationX - toX
        val y = fromY + view.translationY - toY
        val alpha = view.alpha
        val content = if (changed && !effects.containsKey(holder)) 0.55f else contentAlpha(holder)
        endAnimation(holder)
        val kind = if (changed) Kind.CHANGE else Kind.MOVE
        if (x == 0f && y == 0f && alpha == 1f && content == 1f) {
            finish(Effect(holder, kind, x, y, alpha, content))
            return false
        }
        return enqueue(Effect(holder, kind, x, y, alpha, content))
    }

    private fun enqueue(effect: Effect): Boolean {
        val holder = effect.holder
        (holder as? StatAppAdapter.Holder)?.valuesChanged = false
        if (holder !is StatAppAdapter.Holder || !enabled || !ValueAnimator.areAnimatorsEnabled()) {
            reset(holder)
            finish(effect)
            return false
        }
        holder.itemView.apply {
            translationX = effect.x
            translationY = effect.y
            alpha = effect.alpha
        }
        setContentAlpha(holder, effect.contentAlpha)
        effects[holder] = effect
        pending.add(effect)
        return true
    }

    override fun runPendingAnimations() {
        val ready = pending.toList()
        pending.clear()
        ready.forEach { effect ->
            if (effects[effect.holder] !== effect) return@forEach
            val view = effect.holder.itemView
            val endAlpha = if (effect.kind == Kind.REMOVE) 0f else 1f
            val animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = when (effect.kind) {
                    Kind.ADD -> addDuration
                    Kind.REMOVE -> removeDuration
                    Kind.MOVE -> moveDuration
                    Kind.CHANGE -> changeDuration
                }
                interpolator = easing
                addUpdateListener {
                    val progress = it.animatedValue as Float
                    view.translationX = effect.x * (1f - progress)
                    view.translationY = effect.y * (1f - progress)
                    view.alpha = effect.alpha + (endAlpha - effect.alpha) * progress
                    setContentAlpha(effect.holder, effect.contentAlpha + (1f - effect.contentAlpha) * progress)
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (effects[effect.holder] === effect) endAnimation(effect.holder)
                    }
                })
            }
            effect.animator = animator
            when (effect.kind) {
                Kind.ADD -> dispatchAddStarting(effect.holder)
                Kind.REMOVE -> dispatchRemoveStarting(effect.holder)
                Kind.MOVE -> dispatchMoveStarting(effect.holder)
                Kind.CHANGE -> dispatchChangeStarting(effect.holder, true)
            }
            animator.start()
        }
    }

    override fun endAnimation(item: RecyclerView.ViewHolder) {
        val effect = effects.remove(item) ?: return
        pending.remove(effect)
        effect.animator?.apply {
            removeAllUpdateListeners()
            removeAllListeners()
            cancel()
        }
        reset(item)
        finish(effect)
        if (!endingAll && !isRunning) dispatchAnimationsFinished()
    }

    override fun endAnimations() {
        if (endingAll) return
        // Navigation calls this before taking its page snapshot. A diff already in flight
        // must not start another row animation until the next explicit filter/sort action.
        allowed = false
        val wasRunning = isRunning
        endingAll = true
        cancelEmptyMotion()
        effects.keys.toList().forEach(::endAnimation)
        endingAll = false
        if (wasRunning && !isRunning) dispatchAnimationsFinished()
    }

    override fun isRunning() = effects.isNotEmpty() || emptyMotion != null

    private fun cancelEmptyMotion() {
        emptyMotion?.apply { removeAllUpdateListeners(); removeAllListeners(); cancel() }
        emptyMotion = null
        emptyView?.alpha = 1f
    }

    private fun contentAlpha(holder: RecyclerView.ViewHolder) = (holder as? StatAppAdapter.Holder)?.count?.alpha ?: 1f

    private fun setContentAlpha(holder: RecyclerView.ViewHolder, alpha: Float) {
        if (holder !is StatAppAdapter.Holder) return
        holder.detail.alpha = alpha
        holder.recent.alpha = alpha
        holder.count.alpha = alpha
    }

    private fun reset(holder: RecyclerView.ViewHolder) {
        if (holder !is StatAppAdapter.Holder) return
        holder.itemView.apply { alpha = 1f; translationX = 0f; translationY = 0f }
        setContentAlpha(holder, 1f)
    }

    private fun finish(effect: Effect) {
        when (effect.kind) {
            Kind.ADD -> dispatchAddFinished(effect.holder)
            Kind.REMOVE -> dispatchRemoveFinished(effect.holder)
            Kind.MOVE -> dispatchMoveFinished(effect.holder)
            Kind.CHANGE -> dispatchChangeFinished(effect.holder, true)
        }
    }
}
