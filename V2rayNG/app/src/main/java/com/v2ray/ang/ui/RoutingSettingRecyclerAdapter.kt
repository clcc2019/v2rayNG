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
import com.v2ray.ang.contracts.BaseAdapterListener
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ItemRecyclerRoutingSettingBinding
import com.v2ray.ang.dto.RulesetItem
import com.v2ray.ang.helper.ItemTouchHelperAdapter
import com.v2ray.ang.helper.ItemTouchHelperViewHolder
import com.v2ray.ang.viewmodel.RoutingSettingsViewModel
import com.v2ray.ang.ui.hapticLongPress
import java.util.Collections
import java.util.concurrent.Executors

class RoutingSettingRecyclerAdapter(
    private val viewModel: RoutingSettingsViewModel,
    private val adapterListener: BaseAdapterListener?,
    private val onStartDrag: ((RecyclerView.ViewHolder) -> Unit)?
) : RecyclerView.Adapter<RoutingSettingRecyclerAdapter.MainViewHolder>(),
    ItemTouchHelperAdapter {

    companion object {
        private val DIFF_EXECUTOR = Executors.newSingleThreadExecutor()
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
            .setBackgroundThreadExecutor(DIFF_EXECUTOR)
            .build()
    )
    private val items: List<RulesetItem>
        get() = differ.currentList

    fun submitList(newItems: List<RulesetItem>) {
        differ.submitList(newItems.toList())
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: MainViewHolder, position: Int) {
        val ruleset = items[position]

        holder.itemRoutingSettingBinding.remarks.text = ruleset.remarks
        holder.itemRoutingSettingBinding.domainIp.text = (ruleset.domain ?: ruleset.ip ?: ruleset.port)?.toString()
        holder.itemRoutingSettingBinding.outboundTag.text = ruleset.outboundTag
        holder.itemRoutingSettingBinding.chkEnable.isChecked = ruleset.enabled
        holder.itemRoutingSettingBinding.imgLocked.isVisible = ruleset.locked == true
        holder.itemRoutingSettingBinding.tvPriority.text = (position + 1).toString()
        holder.itemView.setBackgroundColor(
            ContextCompat.getColor(holder.itemView.context, R.color.md_theme_surface)
        )

        holder.itemRoutingSettingBinding.layoutEdit.setOnClickListener {
            adapterListener?.onEdit("", position)
        }

        holder.itemRoutingSettingBinding.imgDragHandle.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                holder.itemRoutingSettingBinding.imgDragHandle.hapticLongPress()
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
        BaseViewHolder(itemRoutingSettingBinding.root), ItemTouchHelperViewHolder

    open class BaseViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        fun onItemSelected() {
            itemView.setBackgroundColor(
                ContextCompat.getColor(itemView.context, R.color.colorSelectionFill)
            )
        }

        fun onItemClear() {
            itemView.setBackgroundColor(
                ContextCompat.getColor(itemView.context, R.color.md_theme_surface)
            )
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
