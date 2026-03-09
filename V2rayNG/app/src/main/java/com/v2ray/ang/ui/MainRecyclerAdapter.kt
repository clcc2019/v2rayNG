package com.v2ray.ang.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import com.v2ray.ang.R
import com.v2ray.ang.contracts.MainAdapterListener
import com.v2ray.ang.databinding.ItemRecyclerFooterBinding
import com.v2ray.ang.databinding.ItemRecyclerMainBinding
import com.v2ray.ang.dto.ProfileItem
import com.v2ray.ang.dto.ServersCache
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.helper.ItemTouchHelperAdapter
import com.v2ray.ang.helper.ItemTouchHelperViewHolder
import com.v2ray.ang.viewmodel.MainViewModel
import java.util.Collections

class MainRecyclerAdapter(
    private val mainViewModel: MainViewModel,
    private val adapterListener: MainAdapterListener?
) : RecyclerView.Adapter<MainRecyclerAdapter.BaseViewHolder>(), ItemTouchHelperAdapter {
    companion object {
        private const val VIEW_TYPE_ITEM = 1
        private const val VIEW_TYPE_FOOTER = 2
        private const val PAYLOAD_TEST_RESULT = "payload_test_result"
        private const val PAYLOAD_SELECTION = "payload_selection"
        private const val SELECTION_ANIMATION_DURATION = 120L
        private val SELECTION_INTERPOLATOR = FastOutSlowInInterpolator()
    }

    private var data: MutableList<ServersCache> = mutableListOf()
    private var selectedGuid: String = MmkvManager.getSelectServer().orEmpty()
    private var subscriptionRemarksById: Map<String, String> = emptyMap()

    init {
        setHasStableIds(true)
    }

    fun setData(newData: MutableList<ServersCache>?, position: Int = -1) {
        val updatedData = newData?.toMutableList() ?: mutableListOf()

        if (position >= 0 && position in updatedData.indices && data.size == updatedData.size) {
            data = updatedData
            notifyItemChanged(position, PAYLOAD_TEST_RESULT)
            return
        }

        selectedGuid = MmkvManager.getSelectServer().orEmpty()
        subscriptionRemarksById = buildSubscriptionRemarksMap(updatedData)
        val diffResult = DiffUtil.calculateDiff(MainDiffCallback(data, updatedData))
        data = updatedData
        diffResult.dispatchUpdatesTo(this)
    }

    override fun getItemCount() = data.size + 1
    override fun getItemId(position: Int): Long {
        return if (position == data.size) Long.MIN_VALUE else data[position].guid.hashCode().toLong()
    }

    override fun onBindViewHolder(holder: BaseViewHolder, position: Int) {
        if (holder is MainViewHolder) {
            bindFullItem(holder, position)
        }
    }

    override fun onBindViewHolder(holder: BaseViewHolder, position: Int, payloads: MutableList<Any>) {
        if (holder is MainViewHolder) {
            if (payloads.isEmpty()) {
                bindFullItem(holder, position)
                return
            }

            val item = data[position]
            val guid = item.guid
            if (payloads.contains(PAYLOAD_SELECTION)) {
                bindSelectionState(holder, guid == selectedGuid, animate = true)
            }
            if (payloads.contains(PAYLOAD_TEST_RESULT)) {
                bindTestResult(holder, guid)
            }
        }
    }

    /**
     * Gets the subscription remarks information
     * @param profile The server configuration
     * @return Subscription remarks string, or empty string if none
     */
    private fun getSubscriptionRemarks(profile: ProfileItem): String {
        if (mainViewModel.subscriptionId.isNotEmpty()) {
            return ""
        }
        return subscriptionRemarksById[profile.subscriptionId].orEmpty()
    }

    fun removeServerSub(guid: String, position: Int) {
        val idx = when {
            position in data.indices && data[position].guid == guid -> position
            else -> data.indexOfFirst { it.guid == guid }
        }
        if (idx >= 0) {
            data.removeAt(idx)
            notifyItemRemoved(idx)
            notifyItemRangeChanged(idx, data.size - idx)
        }
    }

    fun setSelectServer(fromPosition: Int, toPosition: Int) {
        selectedGuid = data.getOrNull(toPosition)?.guid ?: selectedGuid
        if (fromPosition in data.indices) {
            notifyItemChanged(fromPosition, PAYLOAD_SELECTION)
        }
        if (toPosition in data.indices) {
            notifyItemChanged(toPosition, PAYLOAD_SELECTION)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BaseViewHolder {
        return when (viewType) {
            VIEW_TYPE_ITEM ->
                MainViewHolder(ItemRecyclerMainBinding.inflate(LayoutInflater.from(parent.context), parent, false))

            else ->
                FooterViewHolder(ItemRecyclerFooterBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }
    }

    override fun getItemViewType(position: Int): Int {
        return if (position == data.size) {
            VIEW_TYPE_FOOTER
        } else {
            VIEW_TYPE_ITEM
        }
    }

    private fun buildSubscriptionRemarksMap(items: List<ServersCache>): Map<String, String> {
        if (mainViewModel.subscriptionId.isNotEmpty()) {
            return emptyMap()
        }

        return items.asSequence()
            .map { it.profile.subscriptionId }
            .filter { it.isNotBlank() }
            .distinct()
            .associateWith { subscriptionId ->
                MmkvManager.decodeSubscription(subscriptionId)?.remarks?.firstOrNull()?.toString().orEmpty()
            }
    }

    private fun bindFullItem(holder: MainViewHolder, position: Int) {
        val item = data[position]
        val guid = item.guid
        val profile = item.profile
        holder.itemView.setBackgroundColor(Color.TRANSPARENT)
        bindPrimaryContent(holder, item)
        bindSubscription(holder, profile)
        bindActions(holder, guid, profile)
        bindSelectionState(holder, guid == selectedGuid, animate = false)
        bindTestResult(holder, guid)
    }

    private fun bindPrimaryContent(holder: MainViewHolder, item: ServersCache) {
        holder.itemMainBinding.tvName.text = item.profile.remarks
        holder.itemMainBinding.tvStatistics.text = item.displayAddress
        holder.itemMainBinding.tvType.text = item.profile.configType.name
    }

    private fun bindSubscription(holder: MainViewHolder, profile: ProfileItem) {
        val subRemarks = getSubscriptionRemarks(profile)
        holder.itemMainBinding.tvSubscription.text = subRemarks
        holder.itemMainBinding.layoutSubscription.visibility = if (subRemarks.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun bindActions(holder: MainViewHolder, guid: String, profile: ProfileItem) {
        holder.itemMainBinding.layoutShare.visibility = View.GONE
        holder.itemMainBinding.layoutEdit.visibility = View.GONE
        holder.itemMainBinding.layoutRemove.visibility = View.GONE
        holder.itemMainBinding.layoutMore.visibility = View.VISIBLE

        holder.itemMainBinding.layoutMore.setOnClickListener {
            val currentPosition = holder.bindingAdapterPosition
            if (currentPosition == RecyclerView.NO_POSITION) {
                return@setOnClickListener
            }
            adapterListener?.onShare(guid, profile, currentPosition, true)
        }

        holder.itemMainBinding.infoContainer.setOnClickListener {
            adapterListener?.onSelectServer(guid)
        }
    }

    private fun bindSelectionState(holder: MainViewHolder, isSelected: Boolean, animate: Boolean) {
        holder.itemMainBinding.tvName.alpha = if (isSelected) 1f else 0.9f
        holder.itemMainBinding.tvStatistics.alpha = if (isSelected) 0.92f else 0.78f
        holder.itemMainBinding.tvType.alpha = if (isSelected) 0.92f else 0.82f
        holder.itemMainBinding.tvTestResult.alpha = if (isSelected) 0.98f else 0.84f
        holder.itemMainBinding.layoutMore.alpha = if (isSelected) 1f else 0.74f

        if (isSelected) {
            holder.itemMainBinding.layoutIndicator.setBackgroundResource(R.drawable.bg_selected_indicator)
            holder.itemMainBinding.itemBg.strokeWidth = 1
            holder.itemMainBinding.itemBg.setStrokeColor(holder.colors.outlineVariant)
            holder.itemMainBinding.itemBg.setCardBackgroundColor(holder.colors.surface)
        } else {
            holder.itemMainBinding.layoutIndicator.setBackgroundResource(R.drawable.bg_item_indicator_idle)
            holder.itemMainBinding.itemBg.strokeWidth = 1
            holder.itemMainBinding.itemBg.setStrokeColor(holder.colors.outlineVariant)
            holder.itemMainBinding.itemBg.setCardBackgroundColor(holder.colors.surfaceVariant)
        }
        holder.itemMainBinding.itemBg.animate().cancel()
        if (animate) {
            holder.itemMainBinding.itemBg.animate()
                .scaleX(if (isSelected) 1f else 0.985f)
                .scaleY(if (isSelected) 1f else 0.985f)
                .setDuration(SELECTION_ANIMATION_DURATION)
                .setInterpolator(SELECTION_INTERPOLATOR)
                .start()
        } else {
            holder.itemMainBinding.itemBg.scaleX = if (isSelected) 1f else 0.985f
            holder.itemMainBinding.itemBg.scaleY = if (isSelected) 1f else 0.985f
        }
        holder.itemMainBinding.itemBg.alpha = 1f
    }

    private fun bindTestResult(holder: MainViewHolder, guid: String) {
        val delayMillis = MmkvManager.getServerTestDelayMillis(guid) ?: 0L
        val testResult = delayMillis.takeIf { it != 0L }?.let { "${it}ms" }.orEmpty()
        holder.itemMainBinding.tvTestResult.text = testResult
        holder.itemMainBinding.tvTestResult.visibility = if (testResult.isBlank()) View.GONE else View.VISIBLE
        holder.itemMainBinding.tvTestResult.setTextColor(if (delayMillis < 0L) holder.colors.pingRed else holder.colors.ping)
    }

    open class BaseViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        fun onItemSelected() {
            itemView.setBackgroundColor(Color.LTGRAY)
        }

        fun onItemClear() {
            itemView.setBackgroundColor(0)
        }
    }

    class MainViewHolder(val itemMainBinding: ItemRecyclerMainBinding) :
        BaseViewHolder(itemMainBinding.root), ItemTouchHelperViewHolder {
        val colors = ItemColors.from(itemMainBinding.root)
    }

    class FooterViewHolder(val itemFooterBinding: ItemRecyclerFooterBinding) :
        BaseViewHolder(itemFooterBinding.root)

    override fun onItemMove(fromPosition: Int, toPosition: Int): Boolean {
        mainViewModel.swapServer(fromPosition, toPosition)
        if (fromPosition < data.size && toPosition < data.size) {
            Collections.swap(data, fromPosition, toPosition)
        }
        notifyItemMoved(fromPosition, toPosition)
        return true
    }

    override fun onItemMoveCompleted() {
        // do nothing
    }

    override fun onItemDismiss(position: Int) {
    }

    data class ItemColors(
        val selectionIndicator: Int,
        val selectionFill: Int,
        val outlineVariant: Int,
        val surface: Int,
        val surfaceVariant: Int,
        val ping: Int,
        val pingRed: Int
    ) {
        companion object {
            fun from(view: View): ItemColors {
                val context = view.context
                return ItemColors(
                    selectionIndicator = ContextCompat.getColor(context, R.color.colorSelectionIndicator),
                    selectionFill = ContextCompat.getColor(context, R.color.colorSelectionFill),
                    outlineVariant = ContextCompat.getColor(context, R.color.md_theme_outlineVariant),
                    surface = ContextCompat.getColor(context, R.color.md_theme_surface),
                    surfaceVariant = ContextCompat.getColor(context, R.color.md_theme_surfaceVariant),
                    ping = ContextCompat.getColor(context, R.color.colorPing),
                    pingRed = ContextCompat.getColor(context, R.color.colorPingRed)
                )
            }
        }
    }

    private class MainDiffCallback(
        private val oldItems: List<ServersCache>,
        private val newItems: List<ServersCache>
    ) : DiffUtil.Callback() {
        override fun getOldListSize(): Int = oldItems.size + 1
        override fun getNewListSize(): Int = newItems.size + 1

        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            val oldItem = oldItems.getOrNull(oldItemPosition)
            val newItem = newItems.getOrNull(newItemPosition)
            return when {
                oldItem == null && newItem == null -> true
                oldItem == null || newItem == null -> false
                else -> oldItem.guid == newItem.guid
            }
        }

        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            val oldItem = oldItems.getOrNull(oldItemPosition)
            val newItem = newItems.getOrNull(newItemPosition)
            return oldItem == newItem
        }
    }
}
