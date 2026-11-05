package com.adsbanner.clean.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.LayoutRes
import androidx.recyclerview.widget.RecyclerView

/** An absent message has no adapter item, so it cannot retain a measured row height. */
internal class EmptyStateAdapter(@LayoutRes private val layout: Int) : RecyclerView.Adapter<EmptyStateAdapter.Holder>() {
    private var message: String? = null

    fun submit(text: String?) {
        val previous = message
        message = text
        when {
            previous == null && text != null -> notifyItemInserted(0)
            previous != null && text == null -> notifyItemRemoved(0)
            previous != text -> notifyItemChanged(0)
        }
    }

    override fun getItemCount() = if (message == null) 0 else 1
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
        (LayoutInflater.from(parent.context).inflate(layout, parent, false) as TextView).apply {
            visibility = View.VISIBLE
        })
    override fun onBindViewHolder(holder: Holder, position: Int) { holder.label.text = message }
    class Holder(val label: TextView) : RecyclerView.ViewHolder(label)
}
