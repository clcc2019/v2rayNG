package com.v2ray.ang.ui

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.AdapterListUpdateCallback
import androidx.recyclerview.widget.AsyncDifferConfig
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.v2ray.ang.R
import com.v2ray.ang.contracts.BaseAdapterListener
import com.v2ray.ang.databinding.ItemRecyclerSubSettingBinding
import com.v2ray.ang.dto.SubscriptionCache
import com.v2ray.ang.helper.ItemTouchHelperAdapter
import com.v2ray.ang.helper.ItemTouchHelperViewHolder
import com.v2ray.ang.helper.ListDiffExecutors
import com.v2ray.ang.util.Utils
import com.v2ray.ang.viewmodel.SubscriptionsViewModel
import java.util.Collections

class SubSettingRecyclerAdapter(
    private val viewModel: SubscriptionsViewModel,
    private val adapterListener: BaseAdapterListener?
) : RecyclerView.Adapter<SubSettingRecyclerAdapter.MainViewHolder>(), ItemTouchHelperAdapter {

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<SubscriptionCache>() {
            override fun areItemsTheSame(oldItem: SubscriptionCache, newItem: SubscriptionCache): Boolean {
                return oldItem.guid == newItem.guid
            }

            override fun areContentsTheSame(oldItem: SubscriptionCache, newItem: SubscriptionCache): Boolean {
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
    private val items: List<SubscriptionCache>
        get() = differ.currentList
    private var hasAnimatedInitialList = false

    init {
        setHasStableIds(true)
    }

    fun submitList(newItems: List<SubscriptionCache>) {
        if (newItems.isEmpty()) {
            hasAnimatedInitialList = false
        }
        differ.submitList(newItems.toList())
    }

    override fun getItemCount() = items.size

    override fun getItemId(position: Int): Long {
        return items.getOrNull(position)?.guid?.hashCode()?.toLong() ?: RecyclerView.NO_ID
    }

    override fun onBindViewHolder(holder: MainViewHolder, position: Int) {
        val subscription = items[position]
        val subId = subscription.guid
        val subItem = subscription.subscription
        val binding = holder.itemSubSettingBinding
        val hasUrl = subItem.url.isNotBlank()

        binding.tvName.text = subItem.remarks
        binding.tvUrl.text = subItem.url
        binding.chkEnable.setOnCheckedChangeListener(null)
        binding.chkEnable.isChecked = subItem.enabled
        binding.tvLastUpdated.text = if (subItem.lastUpdated > 0L) {
            Utils.formatTimestamp(subItem.lastUpdated)
        } else {
            binding.root.context.getString(R.string.sub_last_updated_never)
        }
        holder.itemView.alpha = 1f
        holder.itemView.translationZ = 0f

        bindStatusBadges(holder, subItem.enabled, subItem.autoUpdate)
        bindUrlState(holder, hasUrl)
        bindEntranceMotion(holder, position, subId)

        binding.infoContainer.setOnClickListener {
            adapterListener?.onEdit(subId, position)
        }

        binding.layoutEdit.setOnClickListener {
            adapterListener?.onEdit(subId, position)
        }

        binding.layoutRemove.setOnClickListener {
            adapterListener?.onRemove(subId, position)
        }
        binding.layoutRemove.isVisible = viewModel.canRemove(subId)

        binding.chkEnable.setOnCheckedChangeListener { buttonView, isChecked ->
            if (!buttonView.isPressed) return@setOnCheckedChangeListener
            subItem.enabled = isChecked
            viewModel.update(subId, subItem)
            bindStatusBadges(holder, isChecked, subItem.autoUpdate)
        }

        if (hasUrl) {
            binding.layoutShare.setOnClickListener {
                adapterListener?.onShare(subItem.url)
            }
        } else {
            binding.layoutShare.setOnClickListener(null)
        }
    }

    private fun bindEntranceMotion(holder: MainViewHolder, position: Int, key: String) {
        if (hasAnimatedInitialList) {
            holder.itemSubSettingBinding.itemBg.apply {
                alpha = 1f
                translationY = 0f
                scaleX = 1f
                scaleY = 1f
            }
            return
        }
        UiMotion.animateEntranceOnce(
            view = holder.itemSubSettingBinding.itemBg,
            key = key,
            translationOffsetDp = if (position == 0) 14f else 10f,
            scaleFrom = 0.992f,
            startDelay = position.coerceAtMost(5) * MotionTokens.LIST_ITEM_STAGGER_DELAY
        )
        if (position >= items.lastIndex.coerceAtMost(5)) {
            hasAnimatedInitialList = true
        }
    }

    private fun bindUrlState(holder: MainViewHolder, hasUrl: Boolean) {
        holder.itemSubSettingBinding.layoutUrl.isVisible = hasUrl
        holder.itemSubSettingBinding.layoutShare.isVisible = hasUrl
        holder.itemSubSettingBinding.chkEnable.isVisible = hasUrl
        holder.itemSubSettingBinding.layoutLastUpdated.isVisible = hasUrl
    }

    private fun bindStatusBadges(holder: MainViewHolder, enabled: Boolean, autoUpdate: Boolean) {
        val context = holder.itemView.context
        holder.itemSubSettingBinding.tvStatus.text =
            context.getString(if (enabled) R.string.sub_status_enabled else R.string.sub_status_paused)
        holder.itemSubSettingBinding.tvAutoUpdate.text =
            context.getString(if (autoUpdate) R.string.sub_status_auto_update else R.string.sub_status_manual_update)

        val enabledBg = if (enabled) R.color.color_home_metric_good else R.color.color_home_metric_idle
        val enabledText = if (enabled) R.color.color_home_metric_good_text else R.color.color_home_metric_idle_text
        val autoBg = if (autoUpdate) R.color.md_theme_primaryContainer else R.color.md_theme_surfaceVariant
        val autoText = if (autoUpdate) R.color.md_theme_onPrimaryContainer else R.color.md_theme_onSurfaceVariant

        holder.itemSubSettingBinding.tvStatus.backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(context, enabledBg))
        holder.itemSubSettingBinding.tvStatus.setTextColor(ContextCompat.getColor(context, enabledText))

        holder.itemSubSettingBinding.tvAutoUpdate.backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(context, autoBg))
        holder.itemSubSettingBinding.tvAutoUpdate.setTextColor(ContextCompat.getColor(context, autoText))
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MainViewHolder {
        return MainViewHolder(
            ItemRecyclerSubSettingBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
        )
    }

    class MainViewHolder(val itemSubSettingBinding: ItemRecyclerSubSettingBinding) :
        BaseViewHolder(itemSubSettingBinding.root), ItemTouchHelperViewHolder {
        init {
            UiMotion.attachPressFeedback(itemSubSettingBinding.infoContainer, pressedScale = 0.992f)
            UiMotion.attachPressFeedback(itemSubSettingBinding.layoutShare, pressedScale = 0.988f)
            UiMotion.attachPressFeedback(itemSubSettingBinding.layoutEdit, pressedScale = 0.988f)
            UiMotion.attachPressFeedback(itemSubSettingBinding.layoutRemove, pressedScale = 0.988f)
        }
    }

    open class BaseViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        fun onItemSelected() {
            itemView.alpha = 0.92f
            itemView.translationZ = 8f
        }

        fun onItemClear() {
            itemView.alpha = 1f
            itemView.translationZ = 0f
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
}
