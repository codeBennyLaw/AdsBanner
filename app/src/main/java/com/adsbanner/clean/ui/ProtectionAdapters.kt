package com.adsbanner.clean.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.adsbanner.clean.R
import com.adsbanner.clean.core.LocalAdRule
import com.adsbanner.clean.core.ProtectionStore
import com.google.android.material.materialswitch.MaterialSwitch

internal data class RuleRow(val rule: LocalAdRule, val appName: String)
internal class RuleAdapter(private val edit: (LocalAdRule, View) -> Unit, private val toggle: (LocalAdRule, Boolean) -> Unit) :
    ListAdapter<RuleRow, RuleAdapter.Holder>(object : DiffUtil.ItemCallback<RuleRow>() {
        override fun areItemsTheSame(old: RuleRow, new: RuleRow) = old.rule.id == new.rule.id
        override fun areContentsTheSame(old: RuleRow, new: RuleRow) = old == new
    }) {
    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.tvRuleName)
        val detail: TextView = view.findViewById(R.id.tvRuleDetail)
        val enabled: MaterialSwitch = view.findViewById(R.id.switchRuleEnabled)
    }
    override fun onCreateViewHolder(parent: ViewGroup, type: Int) = Holder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_protection_rule, parent, false)
    ).also { holder ->
        Motion.bindPressFeedback(holder.itemView)
        holder.itemView.setOnClickListener {
            val position = holder.bindingAdapterPosition
            if (position != RecyclerView.NO_POSITION) edit(getItem(position).rule, holder.itemView)
        }
    }
    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = getItem(position)
        holder.name.text = row.rule.name
        holder.detail.text = "${row.appName} · ${row.rule.maxAgeMs / 1000} 秒内\n${row.rule.viewId}"
        holder.enabled.setOnCheckedChangeListener(null)
        holder.enabled.isChecked = row.rule.enabled
        holder.enabled.contentDescription = "启用 ${row.rule.name}"
        holder.enabled.setOnCheckedChangeListener { _, checked -> toggle(row.rule, checked) }
    }
}

internal data class ActionRow(val action: ProtectionStore.Action, val appName: String, val detail: String)
internal class ActionAdapter(private val click: (ProtectionStore.Action, View) -> Unit) :
    ListAdapter<ActionRow, ActionAdapter.Holder>(object : DiffUtil.ItemCallback<ActionRow>() {
        override fun areItemsTheSame(old: ActionRow, new: ActionRow) = old.action == new.action
        override fun areContentsTheSame(old: ActionRow, new: ActionRow) = old == new
    }) {
    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.tvActionName)
        val detail: TextView = view.findViewById(R.id.tvActionDetail)
    }
    override fun onCreateViewHolder(parent: ViewGroup, type: Int) = Holder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_protection_action, parent, false)
    ).also { holder ->
        Motion.bindPressFeedback(holder.itemView)
        holder.itemView.setOnClickListener {
            val position = holder.bindingAdapterPosition
            if (position != RecyclerView.NO_POSITION) click(getItem(position).action, holder.itemView)
        }
    }
    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = getItem(position)
        holder.name.text = row.appName
        holder.detail.text = row.detail
    }
}
