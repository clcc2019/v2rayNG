package com.v2ray.ang.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.recyclerview.widget.AdapterListUpdateCallback
import androidx.recyclerview.widget.AsyncDifferConfig
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.v2ray.ang.R
import com.v2ray.ang.contracts.MainAdapterListener
import com.v2ray.ang.databinding.ItemRecyclerFooterBinding
import com.v2ray.ang.databinding.ItemRecyclerMainBinding
import com.v2ray.ang.dto.ServersCache
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.helper.ItemTouchHelperAdapter
import com.v2ray.ang.helper.ItemTouchHelperViewHolder
import com.v2ray.ang.helper.ListDiffExecutors
import com.v2ray.ang.ui.common.hapticClick
import com.v2ray.ang.ui.common.hapticLongPress
import com.v2ray.ang.ui.common.hapticVirtualKey
import com.v2ray.ang.viewmodel.MainViewModel
import java.util.Collections

class MainRecyclerAdapter(
    private val mainViewModel: MainViewModel,
    private val adapterListener: MainAdapterListener?,
    private val onContentCommitted: (() -> Unit)? = null
) : RecyclerView.Adapter<MainRecyclerAdapter.BaseViewHolder>(), ItemTouchHelperAdapter {
    companion object {
        private const val VIEW_TYPE_ITEM = 1
        private const val VIEW_TYPE_FOOTER = 2
        private const val PAYLOAD_TEST_RESULT = "payload_test_result"
        private const val PAYLOAD_SELECTION = "payload_selection"
        private const val PAYLOAD_CONTENT = "payload_content"

        private fun resolveCardBackgroundColor(colors: ItemColors, isSelected: Boolean): Int {
            return if (isSelected) colors.selectedSurface else colors.surface
        }

        private fun resolveCardStrokeColor(colors: ItemColors, isSelected: Boolean): Int {
            return if (isSelected) colors.selectedOutline else colors.outline
        }

        private fun resetCardState(
            target: MaterialCardView,
            backgroundColor: Int,
            strokeColor: Int,
            strokeWidth: Int
        ) {
            target.animate().cancel()
            target.alpha = 1f
            target.translationY = 0f
            target.scaleX = 1f
            target.scaleY = 1f
            target.strokeWidth = strokeWidth
            target.setStrokeColor(strokeColor)
            target.setCardBackgroundColor(backgroundColor)
        }
    }

    private val differ = AsyncListDiffer(
        AdapterListUpdateCallback(this),
        AsyncDifferConfig.Builder(MainDiffCallback)
            .setBackgroundThreadExecutor(ListDiffExecutors.background)
            .build()
    )
    private var selectedGuid: String = MmkvManager.getSelectServer().orEmpty()
    private val testDelayOverrides = mutableMapOf<String, Long>()

    init {
        setHasStableIds(true)
    }

    private val data: List<ServersCache>
        get() = differ.currentList
    private var hasAnimatedInitialList = false

    fun setData(newData: MutableList<ServersCache>?, position: Int = -1) {
        if (position >= 0) {
            if (newData == null || data.size != newData.size || position !in data.indices || position !in newData.indices) {
                setData(newData, position = -1)
                return
            }
            updateTestResultItem(newData[position], position)
            return
        }

        val updatedData = newData?.toList() ?: emptyList()
        selectedGuid = MmkvManager.getSelectServer().orEmpty()
        if (updatedData.isEmpty()) {
            hasAnimatedInitialList = false
        }
        testDelayOverrides.clear()
        differ.submitList(updatedData) {
            onContentCommitted?.invoke()
        }
    }

    fun updateTestResults(newData: MutableList<ServersCache>?, positions: List<Int>) {
        if (newData == null || positions.isEmpty()) return
        if (data.size != newData.size) {
            setData(newData, position = -1)
            return
        }
        val distinctPositions = positions.distinct().sorted()
        for (position in distinctPositions) {
            if (position !in data.indices || position !in newData.indices) {
                setData(newData, position = -1)
                return
            }
            if (data[position].guid != newData[position].guid) {
                setData(newData, position = -1)
                return
            }
        }
        val changedPositions = ArrayList<Int>(distinctPositions.size)
        distinctPositions.forEach { position ->
            val currentItem = data[position]
            val newItem = newData[position]
            val currentDelay = testDelayOverrides[currentItem.guid] ?: currentItem.testDelayMillis
            if (currentDelay == newItem.testDelayMillis) {
                return@forEach
            }
            testDelayOverrides[newItem.guid] = newItem.testDelayMillis
            changedPositions.add(position)
        }
        if (changedPositions.isEmpty()) return
        var rangeStart = changedPositions[0]
        var prev = rangeStart
        for (index in 1 until changedPositions.size) {
            val pos = changedPositions[index]
            if (pos == prev + 1) {
                prev = pos
            } else {
                notifyItemRangeChanged(rangeStart, prev - rangeStart + 1, PAYLOAD_TEST_RESULT)
                rangeStart = pos
                prev = pos
            }
        }
        notifyItemRangeChanged(rangeStart, prev - rangeStart + 1, PAYLOAD_TEST_RESULT)
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
            if (position >= data.size) {
                return
            }
            if (payloads.isEmpty()) {
                bindFullItem(holder, position)
                return
            }

            val item = getItem(position)
            holder.boundItem = item
            if (payloads.contains(PAYLOAD_SELECTION)) {
                val isSelected = item.guid == selectedGuid
                bindSelectionState(holder, isSelected)
                bindSubscription(holder, item)
            }
            if (payloads.contains(PAYLOAD_TEST_RESULT)) {
                bindTestResult(holder, item)
            }
            if (payloads.contains(PAYLOAD_CONTENT)) {
                bindPrimaryContent(holder, item)
                bindSubscription(holder, item)
            }
        }
    }

    fun removeServerSub(guid: String, position: Int) {
        val idx = when {
            position in data.indices && data[position].guid == guid -> position
            else -> data.indexOfFirst { it.guid == guid }
        }
        if (idx >= 0) {
            differ.submitList(data.toMutableList().apply { removeAt(idx) }) {
                onContentCommitted?.invoke()
            }
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
                MainViewHolder(
                    ItemRecyclerMainBinding.inflate(LayoutInflater.from(parent.context), parent, false),
                    adapterListener
                )

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

    private fun bindFullItem(holder: MainViewHolder, position: Int) {
        val item = getItem(position)
        val guid = item.guid
        val isSelected = guid == selectedGuid
        holder.boundItem = item
        holder.itemView.setBackgroundColor(Color.TRANSPARENT)
        bindPrimaryContent(holder, item)
        bindSubscription(holder, item)
        bindSelectionState(holder, isSelected)
        bindTestResult(holder, item)
        bindEntranceMotion(holder, position, item)
    }

    private fun bindEntranceMotion(holder: MainViewHolder, position: Int, item: ServersCache) {
        if (hasAnimatedInitialList) {
            resetCardState(
                holder.itemMainBinding.itemBg,
                resolveCardBackgroundColor(holder.colors, holder.lastSelectionState == true),
                resolveCardStrokeColor(holder.colors, holder.lastSelectionState == true),
                holder.cardStrokeWidthPx
            )
            return
        }
        val startDelay = if (!hasAnimatedInitialList) {
            (position.coerceAtMost(7) * MotionTokens.LIST_ITEM_STAGGER_DELAY)
        } else {
            0L
        }
        UiMotion.animateEntranceOnce(
            view = holder.itemMainBinding.itemBg,
            key = item.guid,
            translationOffsetDp = if (position == 0) 18f else 14f,
            scaleFrom = 0.988f,
            startDelay = startDelay
        )
        if (!hasAnimatedInitialList && position >= data.lastIndex.coerceAtMost(7)) {
            hasAnimatedInitialList = true
        }
    }

    private fun bindPrimaryContent(holder: MainViewHolder, item: ServersCache) {
        holder.itemMainBinding.tvStatistics.animate().cancel()
        holder.itemMainBinding.tvStatistics.translationY = 0f
        holder.itemMainBinding.tvStatistics.alpha = if (holder.lastSelectionState == true) 0.96f else 0.9f
        holder.itemMainBinding.tvName.text = item.profile.remarks
        holder.itemMainBinding.tvStatistics.text = item.displayAddress
        holder.itemMainBinding.tvType.text = item.profile.configType.name
    }

    private fun bindSubscription(holder: MainViewHolder, item: ServersCache) {
        val subRemarks = item.subscriptionRemarks
        holder.itemMainBinding.tvSubscription.text = subRemarks
        holder.itemMainBinding.layoutSubscription.isVisible = subRemarks.isNotEmpty()
    }

    private fun bindSelectionState(holder: MainViewHolder, isSelected: Boolean) {
        if (holder.lastSelectionState == isSelected) {
            return
        }
        val previousSelection = holder.lastSelectionState
        holder.lastSelectionState = isSelected

        holder.itemMainBinding.tvName.alpha = if (isSelected) 1f else 0.95f
        holder.itemMainBinding.tvStatistics.alpha = if (isSelected) 0.96f else 0.9f
        holder.itemMainBinding.tvType.alpha = if (isSelected) 0.96f else 0.86f
        holder.itemMainBinding.tvActiveStatus.isVisible = isSelected
        holder.itemMainBinding.tvActiveStatus.alpha = if (isSelected) 1f else 0f
        holder.itemMainBinding.layoutMetaPanel.alpha = if (isSelected) 1f else 0.96f
        holder.itemMainBinding.tvTestResult.alpha = 1f
        holder.itemMainBinding.layoutSubscription.alpha = if (isSelected) 1f else 0.92f
        holder.itemMainBinding.layoutMore.alpha = if (isSelected) 0.78f else 0.62f
        holder.itemMainBinding.viewCardGlassOverlay.animate().cancel()
        holder.itemMainBinding.viewSelectedEdgeGlow.animate().cancel()

        holder.itemMainBinding.viewCardGlassOverlay.alpha = if (isSelected) 0.12f else 0.08f
        holder.itemMainBinding.viewSelectedEdgeGlow.alpha = if (isSelected) 0.14f else 0f
        updateIndicatorStyle(holder, isSelected)
        holder.itemMainBinding.layoutIndicator.setBackgroundResource(
            if (isSelected) R.drawable.bg_home_selected_indicator else R.drawable.bg_item_indicator_idle
        )
        resetCardState(
            holder.itemMainBinding.itemBg,
            resolveCardBackgroundColor(holder.colors, isSelected),
            resolveCardStrokeColor(holder.colors, isSelected),
            holder.cardStrokeWidthPx
        )
        if (previousSelection != null) {
            if (isSelected) {
                holder.itemMainBinding.viewCardGlassOverlay.alpha = 0.1f
                holder.itemMainBinding.viewSelectedEdgeGlow.alpha = 0.04f
                holder.itemMainBinding.tvActiveStatus.alpha = 0.7f
                holder.itemMainBinding.tvActiveStatus.animate()
                    .alpha(1f)
                    .setDuration(MotionTokens.SHORT_ANIMATION_DURATION)
                    .setInterpolator(FastOutSlowInInterpolator())
                    .start()
                holder.itemMainBinding.viewCardGlassOverlay.animate()
                    .alpha(0.12f)
                    .setDuration(MotionTokens.SHORT_ANIMATION_DURATION)
                    .setInterpolator(FastOutSlowInInterpolator())
                    .start()
                holder.itemMainBinding.viewSelectedEdgeGlow.animate()
                    .alpha(0.14f)
                    .setDuration(MotionTokens.SHORT_ANIMATION_DURATION)
                    .setInterpolator(FastOutSlowInInterpolator())
                    .start()
                UiMotion.animateFocusShift(holder.itemMainBinding.itemBg, holder.itemMainBinding.layoutIndicator)
            } else {
                holder.itemMainBinding.viewCardGlassOverlay.animate()
                    .alpha(0.08f)
                    .setDuration(MotionTokens.SHORT_ANIMATION_DURATION)
                    .setInterpolator(FastOutSlowInInterpolator())
                    .start()
                holder.itemMainBinding.viewSelectedEdgeGlow.animate()
                    .alpha(0f)
                    .setDuration(MotionTokens.SHORT_ANIMATION_DURATION)
                    .setInterpolator(FastOutSlowInInterpolator())
                    .start()
                holder.itemMainBinding.tvActiveStatus.animate().cancel()
                UiMotion.animatePulse(holder.itemMainBinding.layoutIndicator, pulseScale = 1.04f, duration = MotionTokens.PULSE_QUICK)
            }
        }
    }

    private fun updateIndicatorStyle(holder: MainViewHolder, isSelected: Boolean) {
        val layoutParams = holder.itemMainBinding.layoutIndicator.layoutParams as ViewGroup.MarginLayoutParams
        val targetWidth = if (isSelected) holder.indicatorSelectedWidthPx else holder.indicatorIdleWidthPx
        val targetHeight = if (isSelected) holder.indicatorSelectedHeightPx else holder.indicatorIdleHeightPx
        var changed = false
        if (layoutParams.width != targetWidth) {
            layoutParams.width = targetWidth
            changed = true
        }
        if (layoutParams.height != targetHeight) {
            layoutParams.height = targetHeight
            changed = true
        }
        if (layoutParams.marginEnd != holder.indicatorMarginEndPx) {
            layoutParams.marginEnd = holder.indicatorMarginEndPx
            changed = true
        }
        if (layoutParams.topMargin != 0) {
            layoutParams.topMargin = 0
            changed = true
        }
        if (changed) {
            holder.itemMainBinding.layoutIndicator.layoutParams = layoutParams
        }
        holder.itemMainBinding.layoutIndicator.alpha = if (isSelected) 1f else 0.78f
    }

    private fun bindTestResult(holder: MainViewHolder, item: ServersCache) {
        val shouldAnimateResult = holder.boundGuid == item.guid &&
            holder.lastTestDelay != null &&
            holder.lastTestDelay != item.testDelayMillis &&
            item.testDelayMillis != 0L

        val delayMillis = item.testDelayMillis
        val context = holder.itemMainBinding.root.context
        val testResult = when {
            delayMillis > 0L -> "${delayMillis}ms"
            delayMillis < 0L -> context.getString(R.string.connection_test_fail)
            else -> "--"
        }
        holder.itemMainBinding.tvTestResultInline.text = testResult
        holder.itemMainBinding.tvTestResult.text = testResult
        holder.itemMainBinding.tvTestResult.visibility = View.VISIBLE
        holder.itemMainBinding.tvTestResultInline.visibility = View.GONE
        applyLatencyBadgeStyle(holder.itemMainBinding.tvTestResult, delayMillis)
        if (shouldAnimateResult && delayMillis != 0L) {
            val targetView = holder.itemMainBinding.tvTestResult
            UiMotion.animatePulse(targetView, pulseScale = 1.03f)
        }
        holder.boundGuid = item.guid
        holder.lastTestDelay = item.testDelayMillis
    }

    private fun getItem(position: Int): ServersCache {
        val item = data[position]
        val overriddenDelay = testDelayOverrides[item.guid] ?: return item
        return if (overriddenDelay == item.testDelayMillis) item else item.copy(testDelayMillis = overriddenDelay)
    }

    private fun updateTestResultItem(item: ServersCache?, position: Int) {
        if (item == null || position !in data.indices) {
            return
        }

        val currentItem = data[position]
        if (currentItem.guid != item.guid) {
            setData(ArrayList(data).apply {
                if (position in indices) {
                    this[position] = item
                }
            })
            return
        }

        val currentDelay = testDelayOverrides[currentItem.guid] ?: currentItem.testDelayMillis
        if (currentDelay == item.testDelayMillis) {
            return
        }

        testDelayOverrides[item.guid] = item.testDelayMillis
        notifyItemChanged(position, PAYLOAD_TEST_RESULT)
    }

    open class BaseViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        open fun onItemSelected() {}

        open fun onItemClear() {}
    }

    class MainViewHolder(
        val itemMainBinding: ItemRecyclerMainBinding,
        private val adapterListener: MainAdapterListener?
    ) :
        BaseViewHolder(itemMainBinding.root), ItemTouchHelperViewHolder {
        val colors = ItemColors.from(itemMainBinding.root)
        val cardStrokeWidthPx = itemMainBinding.root.resources.getDimensionPixelSize(R.dimen.padding_spacing_dp1)
        val indicatorIdleWidthPx = itemMainBinding.root.resources.getDimensionPixelSize(R.dimen.padding_spacing_dp4)
        val indicatorIdleHeightPx = itemMainBinding.root.resources.getDimensionPixelSize(R.dimen.padding_spacing_dp18)
        val indicatorSelectedWidthPx = itemMainBinding.root.resources.getDimensionPixelSize(R.dimen.padding_spacing_dp4)
        val indicatorSelectedHeightPx = itemMainBinding.root.resources.getDimensionPixelSize(R.dimen.view_height_dp40)
        val indicatorMarginEndPx = itemMainBinding.root.resources.getDimensionPixelSize(R.dimen.padding_spacing_dp14)
        var lastSelectionState: Boolean? = null
        var boundGuid: String? = null
        var lastTestDelay: Long? = null
        var boundItem: ServersCache? = null

        init {
            itemMainBinding.layoutMore.visibility = View.VISIBLE
            UiMotion.attachPressFeedback(itemMainBinding.layoutMore, pressedScale = 0.96f)
            UiMotion.attachPressFeedbackComposite(
                source = itemMainBinding.infoContainer,
                scaleTarget = itemMainBinding.itemBg,
                alphaTarget = itemMainBinding.itemBg,
                pressedScale = 0.992f,
                pressedAlpha = 0.94f
            )

            itemMainBinding.layoutMore.setOnClickListener {
                val item = boundItem ?: return@setOnClickListener
                val currentPosition = bindingAdapterPosition
                if (currentPosition == RecyclerView.NO_POSITION) {
                    return@setOnClickListener
                }
                itemMainBinding.layoutMore.hapticClick()
                UiMotion.animatePulse(itemMainBinding.layoutMore, pulseScale = 1.02f, duration = MotionTokens.PULSE_QUICK)
                adapterListener?.onShare(item.guid, item.profile, currentPosition, true)
            }

            itemMainBinding.infoContainer.setOnClickListener {
                val item = boundItem ?: return@setOnClickListener
                itemMainBinding.infoContainer.hapticClick()
                adapterListener?.onSelectServer(item.guid)
            }
            itemMainBinding.infoContainer.setOnLongClickListener {
                val item = boundItem ?: return@setOnLongClickListener false
                val currentPosition = bindingAdapterPosition
                if (currentPosition == RecyclerView.NO_POSITION) {
                    return@setOnLongClickListener false
                }
                itemMainBinding.infoContainer.hapticLongPress()
                adapterListener?.onShare(item.guid, item.profile, currentPosition, true)
                true
            }

            val onTestDelayClick = View.OnClickListener {
                val item = boundItem ?: return@OnClickListener
                val currentPosition = bindingAdapterPosition
                if (currentPosition == RecyclerView.NO_POSITION) {
                    return@OnClickListener
                }
                itemMainBinding.tvTestResultInline.hapticVirtualKey()
                adapterListener?.onTestDelay(item.guid, currentPosition)
            }
            itemMainBinding.tvTestResult.setOnClickListener(onTestDelayClick)
            itemMainBinding.tvTestResultInline.setOnClickListener(onTestDelayClick)
        }

        override fun onItemSelected() {
            resetCardState(
                itemMainBinding.itemBg,
                resolveCardBackgroundColor(colors, lastSelectionState == true),
                resolveCardStrokeColor(colors, lastSelectionState == true),
                cardStrokeWidthPx
            )
        }

        override fun onItemClear() {
            resetCardState(
                itemMainBinding.itemBg,
                resolveCardBackgroundColor(colors, lastSelectionState == true),
                resolveCardStrokeColor(colors, lastSelectionState == true),
                cardStrokeWidthPx
            )
        }
    }

    class FooterViewHolder(val itemFooterBinding: ItemRecyclerFooterBinding) :
        BaseViewHolder(itemFooterBinding.root)

    override fun onItemMove(fromPosition: Int, toPosition: Int): Boolean {
        mainViewModel.swapServer(fromPosition, toPosition)
        if (fromPosition < data.size && toPosition < data.size) {
            val updatedData = data.toMutableList()
            Collections.swap(updatedData, fromPosition, toPosition)
            differ.submitList(updatedData)
        }
        return true
    }

    override fun onItemMoveCompleted() {
        // do nothing
    }

    override fun onItemDismiss(position: Int) {
    }

    data class ItemColors(
        val surface: Int,
        val selectedSurface: Int,
        val outline: Int,
        val selectedOutline: Int
    ) {
        companion object {
            fun from(view: View): ItemColors {
                val context = view.context
                return ItemColors(
                    surface = ContextCompat.getColor(context, R.color.color_home_card_bg),
                    selectedSurface = ContextCompat.getColor(context, R.color.color_home_card_bg_selected),
                    outline = ContextCompat.getColor(context, R.color.color_home_card_stroke),
                    selectedOutline = ContextCompat.getColor(context, R.color.color_home_card_stroke_selected)
                )
            }
        }
    }

    private object MainDiffCallback : DiffUtil.ItemCallback<ServersCache>() {
        override fun areItemsTheSame(oldItem: ServersCache, newItem: ServersCache): Boolean {
            return oldItem.guid == newItem.guid
        }

        override fun areContentsTheSame(oldItem: ServersCache, newItem: ServersCache): Boolean {
            return oldItem == newItem
        }

        override fun getChangePayload(oldItem: ServersCache, newItem: ServersCache): Any? {
            return when {
                oldItem.testDelayMillis != newItem.testDelayMillis &&
                    oldItem.profile == newItem.profile &&
                    oldItem.displayAddress == newItem.displayAddress -> PAYLOAD_TEST_RESULT
                oldItem.profile != newItem.profile || oldItem.displayAddress != newItem.displayAddress -> PAYLOAD_CONTENT
                else -> null
            }
        }
    }

    private fun applyLatencyBadgeStyle(target: TextView, delayMillis: Long) {
        val context = target.context
        val (backgroundRes, textRes) = when {
            delayMillis == 0L -> R.color.color_home_metric_idle to R.color.color_home_metric_idle_text
            delayMillis < 0L -> R.color.color_home_metric_bad to R.color.color_home_metric_bad_text
            delayMillis < 150L -> R.color.color_home_metric_good to R.color.color_home_metric_good_text
            delayMillis < 300L -> R.color.color_home_metric_warn to R.color.color_home_metric_warn_text
            else -> R.color.color_home_metric_bad to R.color.color_home_metric_bad_text
        }
        target.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, backgroundRes))
        target.setTextColor(ContextCompat.getColor(context, textRes))
        target.alpha = if (delayMillis == 0L) 0.84f else 1f
    }
}
