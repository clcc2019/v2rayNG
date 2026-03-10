package com.v2ray.ang.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.AdapterListUpdateCallback
import androidx.recyclerview.widget.AsyncDifferConfig
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.v2ray.ang.contracts.BaseAdapterListener
import com.v2ray.ang.databinding.ItemRecyclerRoutingSettingBinding
import com.v2ray.ang.dto.RulesetItem
import com.v2ray.ang.helper.ItemTouchHelperAdapter
import com.v2ray.ang.helper.ItemTouchHelperViewHolder
import com.v2ray.ang.viewmodel.RoutingSettingsViewModel
import java.util.Collections
import java.util.concurrent.Executors

class RoutingSettingRecyclerAdapter(
    private val viewModel: RoutingSettingsViewModel,
    private val adapterListener: BaseAdapterListener?
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
        holder.itemView.setBackgroundColor(Color.TRANSPARENT)

        holder.itemRoutingSettingBinding.layoutEdit.setOnClickListener {
            adapterListener?.onEdit("", position)
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
            itemView.setBackgroundColor(Color.LTGRAY)
        }

        fun onItemClear() {
            itemView.setBackgroundColor(0)
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
