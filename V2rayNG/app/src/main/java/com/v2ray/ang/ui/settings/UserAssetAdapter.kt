package com.v2ray.ang.ui

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.AdapterListUpdateCallback
import androidx.recyclerview.widget.AsyncDifferConfig
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.v2ray.ang.R
import com.v2ray.ang.contracts.BaseAdapterListener
import com.v2ray.ang.databinding.ItemRecyclerUserAssetBinding
import com.v2ray.ang.dto.AssetUrlCache
import com.v2ray.ang.helper.ListDiffExecutors
class UserAssetAdapter(
    private val adapterListener: BaseAdapterListener?
) : RecyclerView.Adapter<UserAssetAdapter.UserAssetViewHolder>() {

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<AssetUrlCache>() {
            override fun areItemsTheSame(oldItem: AssetUrlCache, newItem: AssetUrlCache): Boolean {
                return oldItem.guid == newItem.guid
            }

            override fun areContentsTheSame(oldItem: AssetUrlCache, newItem: AssetUrlCache): Boolean {
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
    private val items: List<AssetUrlCache>
        get() = differ.currentList
    private var fileMeta: Map<String, AssetFileMeta> = emptyMap()

    init {
        setHasStableIds(true)
    }

    data class AssetFileMeta(
        val propertiesText: String
    )

    fun submitList(
        newItems: List<AssetUrlCache>,
        fileMeta: Map<String, AssetFileMeta>,
        onCommitted: (() -> Unit)? = null
    ) {
        this.fileMeta = fileMeta
        differ.submitList(newItems.toList()) {
            onCommitted?.invoke()
        }
    }

    override fun getItemCount() = items.size

    override fun getItemId(position: Int): Long {
        return items.getOrNull(position)?.guid?.hashCode()?.toLong() ?: RecyclerView.NO_ID
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): UserAssetViewHolder {
        return UserAssetViewHolder(
            ItemRecyclerUserAssetBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
        )
    }

    @SuppressLint("SetTextI18n")
    override fun onBindViewHolder(holder: UserAssetViewHolder, position: Int) {
        val item = items.getOrNull(position) ?: return
        val meta = fileMeta[item.assetUrl.remarks]

        holder.itemUserAssetBinding.assetName.text = item.assetUrl.remarks

        if (meta != null) {
            holder.itemUserAssetBinding.assetProperties.text = meta.propertiesText
        } else {
            holder.itemUserAssetBinding.assetProperties.text =
                holder.itemUserAssetBinding.root.context.getString(R.string.msg_file_not_found)
        }

        if (item.assetUrl.locked == true) {
            holder.itemUserAssetBinding.layoutEdit.visibility = View.GONE
        } else {
            holder.itemUserAssetBinding.layoutEdit.visibility = if (item.assetUrl.url == "file") {
                View.GONE
            } else {
                View.VISIBLE
            }
        }

        holder.itemUserAssetBinding.layoutEdit.setOnClickListener {
            adapterListener?.onEdit(item.guid, position)
        }
        holder.itemUserAssetBinding.layoutRemove.setOnClickListener {
            adapterListener?.onRemove(item.guid, position)
        }
    }

    class UserAssetViewHolder(val itemUserAssetBinding: ItemRecyclerUserAssetBinding) :
        RecyclerView.ViewHolder(itemUserAssetBinding.root)
}
