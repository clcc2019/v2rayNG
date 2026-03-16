package com.xray.ang.ui

import android.util.LruCache
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.AdapterListUpdateCallback
import androidx.recyclerview.widget.AsyncDifferConfig
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.xray.ang.databinding.ItemRecyclerLogcatBinding
import com.xray.ang.helper.ListDiffExecutors

class LogcatRecyclerAdapter(
    private val onLongClick: ((String) -> Boolean)? = null
) : RecyclerView.Adapter<LogcatRecyclerAdapter.MainViewHolder>() {

    private val differ = AsyncListDiffer(
        AdapterListUpdateCallback(this),
        AsyncDifferConfig.Builder(LogcatDiff)
            .setBackgroundThreadExecutor(ListDiffExecutors.background)
            .build()
    )
    private val parsedCache = object : LruCache<String, ParsedLog>(256) {}
    private var hasAnimatedInitialList = false

    fun submitList(newItems: List<String>) {
        if (newItems.isEmpty()) {
            hasAnimatedInitialList = false
        }
        differ.submitList(newItems.toList())
    }

    fun currentLogs(): List<String> = differ.currentList.toList()

    override fun getItemCount() = differ.currentList.size

    override fun onBindViewHolder(holder: MainViewHolder, position: Int) {
        val log = differ.currentList[position]
        val parsed = parseLog(log)
        holder.itemSubSettingBinding.logTag.text = parsed.tag
        holder.itemSubSettingBinding.logContent.text = parsed.content
        bindEntranceMotion(holder, position, log)

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

    private fun bindEntranceMotion(holder: MainViewHolder, position: Int, key: String) {
        if (hasAnimatedInitialList) {
            holder.itemView.apply {
                alpha = 1f
                translationY = 0f
                scaleX = 1f
                scaleY = 1f
            }
            return
        }
        UiMotion.animateEntranceOnce(
            view = holder.itemView,
            key = key,
            translationOffsetDp = 8f,
            scaleFrom = 0.994f,
            startDelay = position.coerceAtMost(5) * MotionTokens.LIST_ITEM_STAGGER_DELAY
        )
        if (position >= differ.currentList.lastIndex.coerceAtMost(5)) {
            hasAnimatedInitialList = true
        }
    }

    private data class ParsedLog(
        val tag: String,
        val content: String
    )

    private fun parseLog(log: String): ParsedLog {
        parsedCache.get(log)?.let { return it }
        val parsed = when {
            log.isEmpty() -> ParsedLog("", "")
            else -> {
                val separatorIndex = log.indexOf("):")
                if (separatorIndex > 0) {
                    val header = log.substring(0, separatorIndex)
                    val payload = log.substring(separatorIndex + 2)
                    ParsedLog(
                        tag = header.substringBefore("(").trim(),
                        content = payload.trim()
                    )
                } else {
                    ParsedLog(
                        tag = log.substringBefore("(").trim(),
                        content = ""
                    )
                }
            }
        }
        parsedCache.put(log, parsed)
        return parsed
    }

    private object LogcatDiff : DiffUtil.ItemCallback<String>() {
        override fun areItemsTheSame(oldItem: String, newItem: String): Boolean {
            return oldItem == newItem
        }

        override fun areContentsTheSame(oldItem: String, newItem: String): Boolean {
            return oldItem == newItem
        }
    }
}
