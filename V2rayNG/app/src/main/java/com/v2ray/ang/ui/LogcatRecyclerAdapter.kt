package com.v2ray.ang.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.v2ray.ang.databinding.ItemRecyclerLogcatBinding
class LogcatRecyclerAdapter(
    private val onLongClick: ((String) -> Boolean)? = null
) : RecyclerView.Adapter<LogcatRecyclerAdapter.MainViewHolder>() {

    private val differ = AsyncListDiffer(this, LogcatDiff)

    fun submitList(newItems: List<String>) {
        differ.submitList(newItems.toList())
    }

    override fun getItemCount() = differ.currentList.size

    override fun onBindViewHolder(holder: MainViewHolder, position: Int) {
        val log = differ.currentList[position]

        if (log.isEmpty()) {
            holder.itemSubSettingBinding.logTag.text = ""
            holder.itemSubSettingBinding.logContent.text = ""
        } else {
            val separatorIndex = log.indexOf("):")
            if (separatorIndex > 0) {
                val header = log.substring(0, separatorIndex)
                val payload = log.substring(separatorIndex + 2)
                val tag = header.substringBefore("(").trim()
                holder.itemSubSettingBinding.logTag.text = tag
                holder.itemSubSettingBinding.logContent.text = payload.trim()
            } else {
                holder.itemSubSettingBinding.logTag.text = log.substringBefore("(").trim()
                holder.itemSubSettingBinding.logContent.text = ""
            }
        }

        holder.itemView.setOnLongClickListener {
            onLongClick?.invoke(log) ?: false
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MainViewHolder {
        return MainViewHolder(
            ItemRecyclerLogcatBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
        )
    }

    class MainViewHolder(val itemSubSettingBinding: ItemRecyclerLogcatBinding) : RecyclerView.ViewHolder(itemSubSettingBinding.root)

    private object LogcatDiff : DiffUtil.ItemCallback<String>() {
        override fun areItemsTheSame(oldItem: String, newItem: String): Boolean {
            return oldItem == newItem
        }

        override fun areContentsTheSame(oldItem: String, newItem: String): Boolean {
            return oldItem == newItem
        }
    }
}
