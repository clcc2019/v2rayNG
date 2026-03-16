package com.xray.ang.ui

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
import com.xray.ang.R
import com.xray.ang.contracts.MainAdapterListener
import com.xray.ang.databinding.ItemRecyclerFooterBinding
import com.xray.ang.databinding.ItemRecyclerMainBinding
import com.xray.ang.dto.ServersCache
import com.xray.ang.handler.MmkvManager
import com.xray.ang.helper.ItemTouchHelperAdapter
import com.xray.ang.helper.ItemTouchHelperViewHolder
import com.xray.ang.helper.ListDiffExecutors
import com.xray.ang.ui.common.hapticClick
import com.xray.ang.ui.common.hapticLongPress
import com.xray.ang.ui.common.hapticVirtualKey
import com.xray.ang.viewmodel.MainViewModel
import java.util.Collections
import kotlin.math.abs

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
        private const val FLOAT_EPSILON = 0.001f
        private val selectionInterpolator = FastOutSlowInInterpolator()

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
                bindSelectionPayload(holder, item)
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
        holder.boundItem = item
        holder.itemView.setBackgroundColor(Color.TRANSPARENT)
        bindPrimaryContent(holder, item)
        bindSubscription(holder, item)
        bindSelectionState(holder, item.guid == selectedGuid)
        bindTestResult(holder, item)
        bindEntranceMotion(holder, position, item)
    }

    private fun bindSelectionPayload(holder: MainViewHolder, item: ServersCache) {
        bindSelectionState(holder, item.guid == selectedGuid)
        bindSubscription(holder, item)
    }

    private fun bindEntranceMotion(holder: MainViewHolder, position: Int, item: ServersCache) {
        if (hasAnimatedInitialList) {
            ensureRestingCardState(holder)
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
        val targetStatisticsAlpha = if (holder.lastSelectionState == true) 0.96f else 0.9f
        holder.itemMainBinding.tvStatistics.apply {
            if (abs(translationY) > FLOAT_EPSILON || abs(alpha - targetStatisticsAlpha) > FLOAT_EPSILON) {
                animate().cancel()
                translationY = 0f
                alpha = targetStatisticsAlpha
            }
        }
        holder.itemMainBinding.tvName.text = item.profile.remarks
        holder.itemMainBinding.tvStatistics.text = item.displayAddress
        holder.itemMainBinding.tvType.text = item.profile.configType.name
    }

    private fun ensureRestingCardState(holder: MainViewHolder) {
        val isSelected = holder.lastSelectionState == true
        val targetBackground = resolveCardBackgroundColor(holder.colors, isSelected)
        val targetStrokeColor = resolveCardStrokeColor(holder.colors, isSelected)
        val card = holder.itemMainBinding.itemBg
        val hasTransformResidue = abs(card.translationY) > FLOAT_EPSILON ||
            abs(card.scaleX - 1f) > FLOAT_EPSILON ||
            abs(card.scaleY - 1f) > FLOAT_EPSILON ||
            abs(card.alpha - 1f) > FLOAT_EPSILON
        val hasStyleResidue = card.strokeWidth != holder.cardStrokeWidthPx ||
            card.strokeColor != targetStrokeColor ||
            card.cardBackgroundColor?.defaultColor != targetBackground
        if (hasTransformResidue || hasStyleResidue) {
            resetCardState(
                target = card,
                backgroundColor = targetBackground,
                strokeColor = targetStrokeColor,
                strokeWidth = holder.cardStrokeWidthPx
            )
        }
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
                    .setInterpolator(selectionInterpolator)
                    .start()
                holder.itemMainBinding.viewCardGlassOverlay.animate()
                    .alpha(0.12f)
                    .setDuration(MotionTokens.SHORT_ANIMATION_DURATION)
                    .setInterpolator(selectionInterpolator)
                    .start()
                holder.itemMainBinding.viewSelectedEdgeGlow.animate()
                    .alpha(0.14f)
                    .setDuration(MotionTokens.SHORT_ANIMATION_DURATION)
                    .setInterpolator(selectionInterpolator)
                    .start()
                UiMotion.animateFocusShift(holder.itemMainBinding.itemBg, holder.itemMainBinding.layoutIndicator)
                UiMotion.animateStatePulse(
                    holder.itemMainBinding.itemBg,
                    expandScale = 1.02f,
                    contractScale = 0.99f,
                    duration = MotionTokens.EMPHASIS_DURATION
                )
            } else {
                holder.itemMainBinding.viewCardGlassOverlay.animate()
                    .alpha(0.08f)
                    .setDuration(MotionTokens.SHORT_ANIMATION_DURATION)
                    .setInterpolator(selectionInterpolator)
                    .start()
                holder.itemMainBinding.viewSelectedEdgeGlow.animate()
                    .alpha(0f)
                    .setDuration(MotionTokens.SHORT_ANIMATION_DURATION)
                    .setInterpolator(selectionInterpolator)
                    .start()
                holder.itemMainBinding.tvActiveStatus.animate().cancel()
                UiMotion.animatePulse(holder.itemMainBinding.layoutIndicator, pulseScale = 1.06f, duration = MotionTokens.PULSE_QUICK)
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
        holder.itemMainBinding.tvTestResult.text = testResult
        applyLatencyBadgeStyle(holder.itemMainBinding.tvTestResult, delayMillis, holder.latencyBadgeColors)
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
        val latencyBadgeColors = LatencyBadgeColors.from(itemMainBinding.root)
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
                itemMainBinding.tvTestResult.hapticVirtualKey()
                adapterListener?.onTestDelay(item.guid, currentPosition)
            }
            itemMainBinding.tvTestResult.setOnClickListener(onTestDelayClick)
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

    override fun onViewRecycled(holder: BaseViewHolder) {
        if (holder is MainViewHolder) {
            holder.itemMainBinding.itemBg.animate().cancel()
            holder.itemMainBinding.viewCardGlassOverlay.animate().cancel()
            holder.itemMainBinding.viewSelectedEdgeGlow.animate().cancel()
            holder.itemMainBinding.tvActiveStatus.animate().cancel()
            holder.itemMainBinding.layoutIndicator.animate().cancel()
            ensureRestingCardState(holder)
            holder.itemMainBinding.tvStatistics.translationY = 0f
        }
        super.onViewRecycled(holder)
    }

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

    data class LatencyBadgeColors(
        val idleBackground: ColorStateList,
        val idleText: Int,
        val badBackground: ColorStateList,
        val badText: Int,
        val goodBackground: ColorStateList,
        val goodText: Int,
        val warnBackground: ColorStateList,
        val warnText: Int
    ) {
        companion object {
            fun from(view: View): LatencyBadgeColors {
                val context = view.context
                return LatencyBadgeColors(
                    idleBackground = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.color_home_metric_idle)),
                    idleText = ContextCompat.getColor(context, R.color.color_home_metric_idle_text),
                    badBackground = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.color_home_metric_bad)),
                    badText = ContextCompat.getColor(context, R.color.color_home_metric_bad_text),
                    goodBackground = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.color_home_metric_good)),
                    goodText = ContextCompat.getColor(context, R.color.color_home_metric_good_text),
                    warnBackground = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.color_home_metric_warn)),
                    warnText = ContextCompat.getColor(context, R.color.color_home_metric_warn_text)
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

    private fun applyLatencyBadgeStyle(target: TextView, delayMillis: Long, colors: LatencyBadgeColors) {
        val (background, textColor) = when {
            delayMillis == 0L -> colors.idleBackground to colors.idleText
            delayMillis < 0L -> colors.badBackground to colors.badText
            delayMillis < 150L -> colors.goodBackground to colors.goodText
            delayMillis < 300L -> colors.warnBackground to colors.warnText
            else -> colors.badBackground to colors.badText
        }
        target.backgroundTintList = background
        target.setTextColor(textColor)
        target.alpha = if (delayMillis == 0L) 0.84f else 1f
    }
}
