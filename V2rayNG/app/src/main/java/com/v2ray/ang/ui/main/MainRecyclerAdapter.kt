package com.v2ray.ang.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.AdapterListUpdateCallback
import androidx.recyclerview.widget.AsyncDifferConfig
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.v2ray.ang.R
import com.v2ray.ang.contracts.MainAdapterListener
import com.v2ray.ang.databinding.ItemRecyclerFooterBinding
import com.v2ray.ang.databinding.ItemRecyclerMainBinding
import com.v2ray.ang.dto.ServersCache
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.helper.ItemTouchHelperAdapter
import com.v2ray.ang.helper.ItemTouchHelperViewHolder
import com.v2ray.ang.ui.common.hapticClick
import com.v2ray.ang.ui.common.hapticLongPress
import com.v2ray.ang.ui.common.hapticVirtualKey
import com.v2ray.ang.viewmodel.MainViewModel
import java.util.Collections
import java.util.concurrent.Executors

class MainRecyclerAdapter(
    private val mainViewModel: MainViewModel,
    private val adapterListener: MainAdapterListener?
) : RecyclerView.Adapter<MainRecyclerAdapter.BaseViewHolder>(), ItemTouchHelperAdapter {
    companion object {
        private const val VIEW_TYPE_ITEM = 1
        private const val VIEW_TYPE_FOOTER = 2
        private const val PAYLOAD_TEST_RESULT = "payload_test_result"
        private const val PAYLOAD_SELECTION = "payload_selection"
        private const val PAYLOAD_CONTENT = "payload_content"
        private val DIFF_EXECUTOR = Executors.newSingleThreadExecutor()

        private fun resolveCardBackgroundColor(colors: ItemColors, isSelected: Boolean): Int {
            return if (isSelected) {
                ColorUtils.blendARGB(colors.surface, colors.selectionIndicator, 0.06f)
            } else {
                colors.surface
            }
        }
    }

    private val differ = AsyncListDiffer(
        AdapterListUpdateCallback(this),
        AsyncDifferConfig.Builder(MainDiffCallback)
            .setBackgroundThreadExecutor(DIFF_EXECUTOR)
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
        differ.submitList(updatedData)
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
                bindSelectionState(holder, item.guid == selectedGuid)
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
            differ.submitList(data.toMutableList().apply { removeAt(idx) })
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
        holder.boundItem = item
        holder.itemView.setBackgroundColor(Color.TRANSPARENT)
        bindPrimaryContent(holder, item)
        bindSubscription(holder, item)
        bindSelectionState(holder, guid == selectedGuid)
        bindTestResult(holder, item)
        bindEntranceMotion(holder, position, item)
    }

    private fun bindEntranceMotion(holder: MainViewHolder, position: Int, item: ServersCache) {
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
        holder.itemMainBinding.tvName.text = item.profile.remarks
        holder.itemMainBinding.tvStatistics.text = item.displayAddress
        holder.itemMainBinding.tvType.text = item.profile.configType.name
    }

    private fun bindSubscription(holder: MainViewHolder, item: ServersCache) {
        val subRemarks = item.subscriptionRemarks
        holder.itemMainBinding.tvSubscription.text = subRemarks
        holder.itemMainBinding.layoutSubscription.visibility = if (subRemarks.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun bindSelectionState(holder: MainViewHolder, isSelected: Boolean) {
        if (holder.lastSelectionState == isSelected) {
            return
        }
        val previousSelection = holder.lastSelectionState
        holder.lastSelectionState = isSelected

        holder.itemMainBinding.tvName.alpha = if (isSelected) 1f else 0.92f
        holder.itemMainBinding.tvStatistics.alpha = if (isSelected) 0.96f else 0.84f
        holder.itemMainBinding.tvType.alpha = if (isSelected) 0.96f else 0.88f
        holder.itemMainBinding.tvTestResult.alpha = if (isSelected) 1f else 0.9f
        holder.itemMainBinding.layoutSubscription.alpha = if (isSelected) 1f else 0.9f
        holder.itemMainBinding.layoutMore.alpha = if (isSelected) 0.96f else 0.74f

        holder.itemMainBinding.layoutIndicator.setBackgroundResource(
            if (isSelected) R.drawable.bg_selected_indicator else R.drawable.bg_item_indicator_idle
        )
        holder.itemMainBinding.itemBg.strokeWidth = if (isSelected) 1 else 0
        holder.itemMainBinding.itemBg.setStrokeColor(
            if (isSelected) holder.colors.selectionIndicator else Color.TRANSPARENT
        )
        holder.itemMainBinding.itemBg.setCardBackgroundColor(
            resolveCardBackgroundColor(holder.colors, isSelected)
        )
        holder.itemMainBinding.itemBg.animate().cancel()
        holder.itemMainBinding.itemBg.scaleX = 1f
        holder.itemMainBinding.itemBg.scaleY = 1f
        holder.itemMainBinding.itemBg.alpha = 1f
        if (previousSelection != null) {
            if (isSelected) {
                UiMotion.animateFocusShift(holder.itemMainBinding.itemBg, holder.itemMainBinding.layoutIndicator)
            } else {
                UiMotion.animatePulse(holder.itemMainBinding.layoutIndicator, pulseScale = 1.04f, duration = MotionTokens.PULSE_QUICK)
            }
        }
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
        holder.itemMainBinding.tvTestResult.visibility = View.VISIBLE
        applyLatencyBadgeStyle(holder.itemMainBinding.tvTestResult, delayMillis)
        if (shouldAnimateResult && delayMillis != 0L) {
            UiMotion.animatePulse(holder.itemMainBinding.tvTestResult, pulseScale = 1.03f)
            UiMotion.animateFocusShift(holder.itemMainBinding.tvStatistics, holder.itemMainBinding.tvTestResult, translationOffsetDp = 6f)
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
        var lastSelectionState: Boolean? = null
        var boundGuid: String? = null
        var lastTestDelay: Long? = null
        var boundItem: ServersCache? = null

        init {
            itemMainBinding.layoutMore.visibility = View.VISIBLE
            UiMotion.attachPressFeedbackStroke(
                itemMainBinding.infoContainer,
                itemMainBinding.itemBg,
                pressedStrokeWidth = 2,
                pressedStrokeColor = colors.selectionIndicator
            )
            UiMotion.attachPressFeedback(itemMainBinding.layoutMore, pressedScale = 0.96f)

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

            itemMainBinding.tvTestResult.setOnClickListener {
                val item = boundItem ?: return@setOnClickListener
                val currentPosition = bindingAdapterPosition
                if (currentPosition == RecyclerView.NO_POSITION) {
                    return@setOnClickListener
                }
                itemMainBinding.tvTestResult.hapticVirtualKey()
                adapterListener?.onTestDelay(item.guid, currentPosition)
            }
        }

        override fun onItemSelected() {
            itemMainBinding.itemBg.animate().cancel()
            itemMainBinding.itemBg.alpha = 1f
            itemMainBinding.itemBg.scaleX = 1f
            itemMainBinding.itemBg.scaleY = 1f
            itemMainBinding.itemBg.strokeWidth = 1
            itemMainBinding.itemBg.setStrokeColor(colors.selectionIndicator)
            itemMainBinding.itemBg.setCardBackgroundColor(colors.surface)
        }

        override fun onItemClear() {
            itemMainBinding.itemBg.animate().cancel()
            itemMainBinding.itemBg.alpha = 1f
            itemMainBinding.itemBg.scaleX = 1f
            itemMainBinding.itemBg.scaleY = 1f
            itemMainBinding.itemBg.strokeWidth = if (lastSelectionState == true) 1 else 0
            itemMainBinding.itemBg.setStrokeColor(
                if (lastSelectionState == true) colors.selectionIndicator else Color.TRANSPARENT
            )
            itemMainBinding.itemBg.setCardBackgroundColor(
                resolveCardBackgroundColor(colors, lastSelectionState == true)
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
        val selectionIndicator: Int,
        val surface: Int
    ) {
        companion object {
            fun from(view: View): ItemColors {
                val context = view.context
                return ItemColors(
                    selectionIndicator = ContextCompat.getColor(context, R.color.colorSelectionIndicator),
                    surface = ContextCompat.getColor(context, R.color.md_theme_surface)
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
            delayMillis == 0L -> R.color.color_latency_bg_idle to R.color.md_theme_onSurfaceVariant
            delayMillis < 0L -> R.color.color_latency_bg_bad to R.color.md_theme_error
            delayMillis < 150L -> R.color.color_latency_bg_good to R.color.md_theme_success
            delayMillis < 300L -> R.color.color_latency_bg_warn to R.color.md_theme_warning
            else -> R.color.color_latency_bg_bad to R.color.md_theme_error
        }
        target.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, backgroundRes))
        target.setTextColor(ContextCompat.getColor(context, textRes))
        target.alpha = if (delayMillis == 0L) 0.84f else 1f
    }
}
