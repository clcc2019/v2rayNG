package com.xray.ang.ui

import android.content.res.ColorStateList
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
import com.xray.ang.R
import com.xray.ang.contracts.BaseAdapterListener
import com.xray.ang.databinding.ItemRecyclerSubSettingBinding
import com.xray.ang.dto.SubscriptionCache
import com.xray.ang.helper.ItemTouchHelperAdapter
import com.xray.ang.helper.ItemTouchHelperViewHolder
import com.xray.ang.helper.ListDiffExecutors
import com.xray.ang.util.Utils
import com.xray.ang.viewmodel.SubscriptionsViewModel
import java.util.Collections

class SubSettingRecyclerAdapter(
    private val viewModel: SubscriptionsViewModel,
    private val adapterListener: BaseAdapterListener?,
    private val onStartDrag: ((RecyclerView.ViewHolder) -> Unit)? = null
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
    private var removableGuids = emptySet<String>()

    init {
        setHasStableIds(true)
    }

    fun submitList(newItems: List<SubscriptionCache>) {
        if (newItems.isEmpty()) {
            hasAnimatedInitialList = false
        }
        removableGuids = newItems.asSequence()
            .filter { viewModel.canRemove(it.guid) }
            .map { it.guid }
            .toSet()
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
        val canRemove = removableGuids.contains(subId)

        holder.boundGuid = subId
        holder.boundSubscription = subItem
        holder.bindStatusBadges(subItem.enabled, subItem.autoUpdate)
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

        bindUrlState(holder, hasUrl)
        bindEntranceMotion(holder, position, subId)

        binding.chkEnable.setOnCheckedChangeListener { buttonView, isChecked ->
            if (!buttonView.isPressed) return@setOnCheckedChangeListener
            subItem.enabled = isChecked
            viewModel.update(subId, subItem)
            holder.bindStatusBadges(isChecked, subItem.autoUpdate)
        }
        binding.layoutDragHandle.isVisible = items.size > 1
        binding.layoutRemove.isVisible = canRemove
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

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MainViewHolder {
        return MainViewHolder(
            ItemRecyclerSubSettingBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            ),
            adapterListener = adapterListener,
            onStartDrag = onStartDrag
        )
    }

    class MainViewHolder(
        val itemSubSettingBinding: ItemRecyclerSubSettingBinding,
        private val adapterListener: BaseAdapterListener?,
        private val onStartDrag: ((RecyclerView.ViewHolder) -> Unit)?
    ) :
        BaseViewHolder(itemSubSettingBinding.root), ItemTouchHelperViewHolder {
        var boundGuid: String? = null
        var boundSubscription: com.xray.ang.dto.SubscriptionItem? = null

        init {
            UiMotion.attachPressFeedback(itemSubSettingBinding.infoContainer, pressedScale = 0.992f)
            UiMotion.attachPressFeedback(itemSubSettingBinding.layoutShare, pressedScale = 0.984f)
            UiMotion.attachPressFeedback(itemSubSettingBinding.layoutEdit, pressedScale = 0.984f)
            UiMotion.attachPressFeedback(itemSubSettingBinding.layoutDragHandle, pressedScale = 0.984f)
            UiMotion.attachPressFeedback(itemSubSettingBinding.layoutRemove, pressedScale = 0.984f)

            itemSubSettingBinding.infoContainer.setOnClickListener {
                val guid = boundGuid ?: return@setOnClickListener
                val position = bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) return@setOnClickListener
                adapterListener?.onEdit(guid, position)
            }

            itemSubSettingBinding.layoutEdit.setOnClickListener {
                val guid = boundGuid ?: return@setOnClickListener
                val position = bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) return@setOnClickListener
                adapterListener?.onEdit(guid, position)
            }

            itemSubSettingBinding.layoutShare.setOnClickListener {
                val subscription = boundSubscription ?: return@setOnClickListener
                if (subscription.url.isBlank()) return@setOnClickListener
                adapterListener?.onShare(subscription.url)
            }

            itemSubSettingBinding.layoutRemove.setOnClickListener {
                val guid = boundGuid ?: return@setOnClickListener
                val position = bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) return@setOnClickListener
                adapterListener?.onRemove(guid, position)
            }

            itemSubSettingBinding.layoutDragHandle.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    onStartDrag?.invoke(this)
                }
                false
            }
        }

        fun bindStatusBadges(enabled: Boolean, autoUpdate: Boolean) {
            val context = itemView.context
            itemSubSettingBinding.tvStatus.text =
                context.getString(if (enabled) R.string.sub_status_enabled else R.string.sub_status_paused)
            itemSubSettingBinding.tvAutoUpdate.text =
                context.getString(if (autoUpdate) R.string.sub_status_auto_update else R.string.sub_status_manual_update)

            val enabledBg = if (enabled) R.color.color_home_metric_good else R.color.color_home_metric_idle
            val enabledText = if (enabled) R.color.color_home_metric_good_text else R.color.color_home_metric_idle_text
            val autoBg = if (autoUpdate) R.color.md_theme_primaryContainer else R.color.md_theme_surfaceVariant
            val autoText = if (autoUpdate) R.color.md_theme_onPrimaryContainer else R.color.md_theme_onSurfaceVariant

            itemSubSettingBinding.tvStatus.backgroundTintList =
                ColorStateList.valueOf(ContextCompat.getColor(context, enabledBg))
            itemSubSettingBinding.tvStatus.setTextColor(ContextCompat.getColor(context, enabledText))

            itemSubSettingBinding.tvAutoUpdate.backgroundTintList =
                ColorStateList.valueOf(ContextCompat.getColor(context, autoBg))
            itemSubSettingBinding.tvAutoUpdate.setTextColor(ContextCompat.getColor(context, autoText))
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
