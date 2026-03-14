package com.v2ray.ang.ui

import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.AdapterListUpdateCallback
import androidx.recyclerview.widget.AsyncDifferConfig
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.v2ray.ang.contracts.BaseAdapterListener
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ItemRecyclerRoutingSettingBinding
import com.v2ray.ang.dto.RulesetItem
import com.v2ray.ang.helper.ItemTouchHelperAdapter
import com.v2ray.ang.helper.ItemTouchHelperViewHolder
import com.v2ray.ang.helper.ListDiffExecutors
import com.v2ray.ang.ui.common.hapticLongPress
import com.v2ray.ang.viewmodel.RoutingSettingsViewModel
import java.util.Collections

class RoutingSettingRecyclerAdapter(
    private val viewModel: RoutingSettingsViewModel,
    private val adapterListener: BaseAdapterListener?,
    private val onStartDrag: ((RecyclerView.ViewHolder) -> Unit)?
) : RecyclerView.Adapter<RoutingSettingRecyclerAdapter.MainViewHolder>(),
    ItemTouchHelperAdapter {

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<RulesetItem>() {
            override fun areItemsTheSame(oldItem: RulesetItem, newItem: RulesetItem): Boolean {
                return oldItem == newItem
            }

            override fun areContentsTheSame(oldItem: RulesetItem, newItem: RulesetItem): Boolean {
                return oldItem == newItem
            }
        }
    }

    private val differ = AsyncListDiffer(
        AdapterListUpdateCallback(this),
        AsyncDifferConfig.Builder(DIFF_CALLBACK)
            .setBackgroundThreadExecutor(ListDiffExecutors.background)
            .build()
    )
    private val items: List<RulesetItem>
        get() = differ.currentList
    private var hasAnimatedInitialList = false

    fun submitList(newItems: List<RulesetItem>, onCommitted: (() -> Unit)? = null) {
        if (newItems.isEmpty()) {
            hasAnimatedInitialList = false
        }
        differ.submitList(newItems.toList(), onCommitted)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: MainViewHolder, position: Int) {
        val ruleset = items[position]
        val context = holder.itemView.context
        val advancedSummary = buildAdvancedSummary(context, ruleset)

        holder.itemRoutingSettingBinding.remarks.text =
            ruleset.remarks?.takeIf { it.isNotBlank() } ?: context.getString(R.string.routing_rule_untitled)
        holder.itemRoutingSettingBinding.domainIp.text = buildMatchSummary(context, ruleset)
        holder.itemRoutingSettingBinding.outboundTag.text = ruleset.outboundTag
        holder.itemRoutingSettingBinding.outboundTag.isVisible = ruleset.outboundTag.isNotBlank()
        holder.itemRoutingSettingBinding.chkEnable.isChecked = ruleset.enabled
        holder.itemRoutingSettingBinding.imgLocked.isVisible = ruleset.locked == true
        holder.itemRoutingSettingBinding.tvPriority.text = (position + 1).toString()
        holder.itemRoutingSettingBinding.tvAdvancedSummary.isVisible = advancedSummary != null
        holder.itemRoutingSettingBinding.tvAdvancedSummary.text = advancedSummary
        holder.itemView.alpha = if (ruleset.enabled) 1f else 0.68f
        holder.resetCardStyle()
        bindEntranceMotion(holder, position, buildItemKey(ruleset, position))

        holder.itemRoutingSettingBinding.infoContainer.setOnClickListener {
            adapterListener?.onEdit("", position)
        }
        holder.itemRoutingSettingBinding.layoutEdit.setOnClickListener {
            adapterListener?.onEdit("", position)
        }

        holder.itemRoutingSettingBinding.layoutDragHandle.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                holder.itemRoutingSettingBinding.layoutDragHandle.hapticLongPress()
                onStartDrag?.invoke(holder)
            }
            false
        }

        holder.itemRoutingSettingBinding.chkEnable.setOnCheckedChangeListener { it, isChecked ->
            if (!it.isPressed) return@setOnCheckedChangeListener
            ruleset.enabled = isChecked
            viewModel.update(position, ruleset)
        }
    }

    private fun bindEntranceMotion(holder: MainViewHolder, position: Int, key: String) {
        if (hasAnimatedInitialList) {
            holder.itemRoutingSettingBinding.itemBg.apply {
                alpha = 1f
                translationY = 0f
                scaleX = 1f
                scaleY = 1f
            }
            return
        }
        UiMotion.animateEntranceOnce(
            view = holder.itemRoutingSettingBinding.itemBg,
            key = key,
            translationOffsetDp = if (position == 0) 14f else 10f,
            scaleFrom = 0.992f,
            startDelay = position.coerceAtMost(5) * MotionTokens.LIST_ITEM_STAGGER_DELAY
        )
        if (position >= items.lastIndex.coerceAtMost(5)) {
            hasAnimatedInitialList = true
        }
    }

    private fun buildItemKey(ruleset: RulesetItem, position: Int): String {
        return "${ruleset.remarks.orEmpty()}_${ruleset.outboundTag}_${position}"
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MainViewHolder {
        return MainViewHolder(
            ItemRecyclerRoutingSettingBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
        )
    }

    class MainViewHolder(val itemRoutingSettingBinding: ItemRecyclerRoutingSettingBinding) :
        BaseViewHolder(itemRoutingSettingBinding.root), ItemTouchHelperViewHolder {
        init {
            UiMotion.attachPressFeedback(itemRoutingSettingBinding.infoContainer, pressedScale = 0.992f)
            UiMotion.attachPressFeedback(itemRoutingSettingBinding.layoutEdit, pressedScale = 0.984f)
            UiMotion.attachPressFeedback(itemRoutingSettingBinding.layoutDragHandle, pressedScale = 0.984f)
        }
    }

    open class BaseViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val itemCardView = itemView as MaterialCardView
        private val colors by lazy { ItemColors.from(itemCardView) }

        fun resetCardStyle() {
            itemCardView.setCardBackgroundColor(colors.surface)
            itemCardView.strokeColor = colors.outline
        }

        fun onItemSelected() {
            itemCardView.setCardBackgroundColor(colors.surface)
            itemCardView.strokeColor = colors.selectionIndicator
        }

        fun onItemClear() {
            resetCardStyle()
        }
    }

    data class ItemColors(
        val selectionIndicator: Int,
        val outline: Int,
        val surface: Int
    ) {
        companion object {
            fun from(view: View): ItemColors {
                val context = view.context
                return ItemColors(
                    selectionIndicator = ContextCompat.getColor(context, R.color.colorSelectionIndicator),
                    outline = ContextCompat.getColor(context, R.color.color_card_outline),
                    surface = ContextCompat.getColor(context, R.color.md_theme_surface)
                )
            }
        }
    }

    override fun onItemMove(fromPosition: Int, toPosition: Int): Boolean {
        viewModel.swap(fromPosition, toPosition)
        if (fromPosition in items.indices && toPosition in items.indices) {
            val updated = items.toMutableList()
            Collections.swap(updated, fromPosition, toPosition)
            differ.submitList(updated)
        }
        return true
    }

    override fun onItemMoveCompleted() {
        adapterListener?.onRefreshData()
    }

    override fun onItemDismiss(position: Int) {
    }

    private fun buildMatchSummary(context: android.content.Context, ruleset: RulesetItem): String {
        val parts = buildList {
            ruleset.domain?.takeIf { it.isNotEmpty() }?.let {
                add("${context.getString(R.string.routing_settings_domain)}: ${previewValues(it)}")
            }
            ruleset.ip?.takeIf { it.isNotEmpty() }?.let {
                add("${context.getString(R.string.routing_settings_ip)}: ${previewValues(it)}")
            }
            ruleset.port?.takeIf { it.isNotBlank() }?.let {
                add("${context.getString(R.string.routing_settings_port)}: $it")
            }
        }
        return parts.joinToString(" · ").ifBlank { context.getString(R.string.routing_rule_no_condition) }
    }

    private fun buildAdvancedSummary(context: android.content.Context, ruleset: RulesetItem): String? {
        val parts = buildList {
            ruleset.protocol?.takeIf { it.isNotEmpty() }?.let {
                add("${context.getString(R.string.routing_settings_protocol)}: ${previewValues(it)}")
            }
            ruleset.network?.takeIf { it.isNotBlank() }?.let {
                add("${context.getString(R.string.routing_settings_network)}: $it")
            }
        }
        return parts.joinToString(" · ").takeIf { it.isNotBlank() }
    }

    private fun previewValues(values: List<String>): String {
        val preview = values.take(2).joinToString(", ")
        val remaining = values.size - 2
        return if (remaining > 0) {
            "$preview +$remaining"
        } else {
            preview
        }
    }
}
