package com.adsbanner.clean.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.adsbanner.clean.R
import com.google.android.material.card.MaterialCardView
import java.util.Locale

internal data class TargetAppRow(val pkg: String, val name: String) {
    val searchKey = "$name\n$pkg".lowercase(Locale.ROOT)
}

internal class TargetAppAdapter(
    private val icons: AppIconLoader,
    private val selected: Set<String>,
    private val toggle: (String) -> Unit
) : ListAdapter<TargetAppRow, TargetAppAdapter.Holder>(object : DiffUtil.ItemCallback<TargetAppRow>() {
    override fun areItemsTheSame(old: TargetAppRow, new: TargetAppRow) = old.pkg == new.pkg
    override fun areContentsTheSame(old: TargetAppRow, new: TargetAppRow) = old == new
}) {
    class Holder(val card: MaterialCardView) : RecyclerView.ViewHolder(card) {
        val name: TextView = card.findViewById(R.id.tvName)
        val pkg: TextView = card.findViewById(R.id.tvPkg)
        val icon: ImageView = card.findViewById(R.id.ivIcon)
        val checked: CheckBox = card.findViewById(R.id.cbChecked)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val card = LayoutInflater.from(parent.context).inflate(R.layout.item_app_check, parent, false) as MaterialCardView
        return Holder(card).also { holder ->
            card.isCheckable = true
            card.checkedIcon = null
            Motion.bindPressFeedback(card)
            card.setOnClickListener {
                val position = holder.bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    val row = getItem(position)
                    toggle(row.pkg)
                    bindSelection(holder, row.pkg)
                }
            }
        }
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = getItem(position)
        holder.name.text = row.name
        holder.pkg.text = row.pkg
        bindSelection(holder, row.pkg)
        icons.bind(holder.icon, row.pkg)
    }

    private fun bindSelection(holder: Holder, pkg: String) {
        val checked = pkg in selected
        holder.card.isChecked = checked
        holder.checked.isChecked = checked
        holder.card.strokeColor = ContextCompat.getColor(holder.card.context, if (checked) R.color.brand else R.color.card_stroke)
        holder.card.setCardBackgroundColor(ContextCompat.getColor(holder.card.context, if (checked) R.color.brand_light else R.color.surface))
    }

    override fun onViewRecycled(holder: Holder) {
        icons.cancel(holder.icon)
        Motion.resetPressFeedback(holder.card)
    }
}

internal data class StatAppRow @JvmOverloads constructor(
    val pkg: String, val name: String, val detail: String, val recent: String, val count: Int,
    val iconPackage: String = pkg,
    val lastAt: Long = 0L
)

internal class StatAppAdapter(private val icons: AppIconLoader) : ListAdapter<StatAppRow, StatAppAdapter.Holder>(
    object : DiffUtil.ItemCallback<StatAppRow>() {
        override fun areItemsTheSame(old: StatAppRow, new: StatAppRow) = old.pkg == new.pkg
        override fun areContentsTheSame(old: StatAppRow, new: StatAppRow) = old == new
    }
) {
    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.tvName)
        val detail: TextView = view.findViewById(R.id.tvDetail)
        val recent: TextView = view.findViewById(R.id.tvRecent)
        val count: TextView = view.findViewById(R.id.tvCount)
        val icon: ImageView = view.findViewById(R.id.ivIcon)
    }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_stat, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = getItem(position)
        holder.name.text = row.name
        holder.detail.text = row.detail
        holder.recent.text = row.recent
        holder.recent.visibility = if (row.recent.isEmpty()) View.GONE else View.VISIBLE
        holder.count.text = row.count.toString()
        icons.bind(holder.icon, row.iconPackage)
    }
    override fun onViewRecycled(holder: Holder) { icons.cancel(holder.icon) }
}
