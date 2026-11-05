package com.adsbanner.clean.ui

import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.OneShotPreDrawListener
import androidx.core.view.children
import androidx.core.view.doOnPreDraw
import androidx.recyclerview.widget.RecyclerView
import com.adsbanner.clean.R

/** Fill the viewport below a scrolling header without adding a placeholder row to its adapter. */
internal fun listDataLoading(
    activity: AppCompatActivity,
    list: RecyclerView,
    header: View,
    message: String
): LocalPageLoading {
    val host = list.parent as FrameLayout
    val placeholder = activity.layoutInflater.inflate(R.layout.view_local_loading, host, false)
    val params = FrameLayout.LayoutParams(-1, -1)
    host.addView(placeholder, params)

    fun position() {
        if (placeholder.visibility != View.VISIBLE || host.height == 0) return
        val top = if (header.parent === list) list.top + header.bottom else list.top + list.paddingTop
        params.topMargin = top.coerceIn(list.top, list.bottom)
        params.bottomMargin = (host.height - list.bottom + list.paddingBottom).coerceAtLeast(0)
        params.leftMargin = list.left + list.paddingLeft
        params.rightMargin = host.width - list.right + list.paddingRight
        // Set the first visible bounds before drawing; subsequent layout uses the same margins.
        val width = (host.width - params.leftMargin - params.rightMargin).coerceAtLeast(0)
        val height = (host.height - params.topMargin - params.bottomMargin).coerceAtLeast(0)
        placeholder.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        placeholder.layout(params.leftMargin, params.topMargin,
            params.leftMargin + width, params.topMargin + height)
    }

    val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> position() }
    val scrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) = position()
    }
    var dataAlpha = 0f
    val itemAnimator = list.itemAnimator
    list.itemAnimator = null
    val childListener = object : RecyclerView.OnChildAttachStateChangeListener {
        override fun onChildViewAttachedToWindow(view: View) { if (view !== header) view.alpha = dataAlpha }
        override fun onChildViewDetachedFromWindow(view: View) = Unit
    }
    list.addOnChildAttachStateChangeListener(childListener)
    list.children.filter { it !== header }.forEach { it.alpha = 0f }
    var firstDraw: OneShotPreDrawListener? = null
    var detached: View.OnAttachStateChangeListener? = null
    fun detachListeners() {
        firstDraw?.removeListener()
        firstDraw = null
        list.removeOnLayoutChangeListener(layoutListener)
        header.removeOnLayoutChangeListener(layoutListener)
        list.removeOnScrollListener(scrollListener)
        list.removeOnChildAttachStateChangeListener(childListener)
        list.itemAnimator = itemAnimator
        detached?.let(placeholder::removeOnAttachStateChangeListener)
        detached = null
    }
    list.addOnLayoutChangeListener(layoutListener)
    header.addOnLayoutChangeListener(layoutListener)
    list.addOnScrollListener(scrollListener)
    detached = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = Unit
        override fun onViewDetachedFromWindow(view: View) = detachListeners()
    }.also(placeholder::addOnAttachStateChangeListener)
    firstDraw = host.doOnPreDraw { firstDraw = null; position() }
    return LocalPageLoading(activity, null, placeholder, message, layoutHost = list,
        revealTargets = { list.children.filter { it !== header }.toList() },
        onContentReady = ::detachListeners,
        onRevealProgress = { alpha ->
            dataAlpha = alpha
            list.children.filter { it !== header }.forEach { it.alpha = alpha }
        })
}
