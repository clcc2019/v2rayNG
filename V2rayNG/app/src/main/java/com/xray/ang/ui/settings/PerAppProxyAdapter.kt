package com.xray.ang.ui

import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.util.LruCache
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.AdapterListUpdateCallback
import androidx.recyclerview.widget.AsyncDifferConfig
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.xray.ang.databinding.ItemRecyclerBypassListBinding
import com.xray.ang.dto.AppInfo
import com.xray.ang.helper.ListDiffExecutors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PerAppProxyAdapter(
    apps: List<AppInfo>,
    private val onSelectionChanged: (AppInfo, Boolean) -> Unit
) : RecyclerView.Adapter<PerAppProxyAdapter.AppViewHolder>() {

    companion object {
        private const val ICON_CACHE_SIZE = 48
        private const val PAYLOAD_SELECTION = "payload_selection"
        private const val PAYLOAD_ICON = "payload_icon"
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<AppInfo>() {
            override fun areItemsTheSame(oldItem: AppInfo, newItem: AppInfo): Boolean {
                return oldItem.packageName == newItem.packageName
            }

            override fun areContentsTheSame(oldItem: AppInfo, newItem: AppInfo): Boolean {
                return oldItem.packageName == newItem.packageName
                    && oldItem.appName == newItem.appName
                    && oldItem.isSystemApp == newItem.isSystemApp
                    && oldItem.isSelected == newItem.isSelected
            }

            override fun getChangePayload(oldItem: AppInfo, newItem: AppInfo): Any? {
                return if (oldItem.packageName == newItem.packageName
                    && oldItem.appName == newItem.appName
                    && oldItem.isSystemApp == newItem.isSystemApp
                    && oldItem.isSelected != newItem.isSelected
                ) {
                    PAYLOAD_SELECTION
                } else {
                    null
                }
            }
        }
    }

    private val differ = AsyncListDiffer(
        AdapterListUpdateCallback(this),
        AsyncDifferConfig.Builder(DIFF_CALLBACK)
            .setBackgroundThreadExecutor(ListDiffExecutors.background)
            .build()
    )

    val apps: List<AppInfo>
        get() = differ.currentList

    private var iconLoader: AppIconLoader? = null
    private var iconLoadingEnabled: Boolean = true

    init {
        setHasStableIds(true)
        submitList(apps)
    }

    fun submitList(newApps: List<AppInfo>) {
        differ.submitList(newApps.toList())
    }

    fun setIconLoadingEnabled(
        enabled: Boolean,
        firstVisiblePosition: Int = RecyclerView.NO_POSITION,
        lastVisiblePosition: Int = RecyclerView.NO_POSITION
    ) {
        if (iconLoadingEnabled == enabled) {
            return
        }
        iconLoadingEnabled = enabled
        if (!enabled || itemCount == 0) {
            return
        }
        val start = firstVisiblePosition.coerceAtLeast(0)
        val end = lastVisiblePosition.coerceAtMost(itemCount - 1)
        if (firstVisiblePosition == RecyclerView.NO_POSITION || lastVisiblePosition == RecyclerView.NO_POSITION || start > end) {
            return
        }
        notifyItemRangeChanged(start, end - start + 1, PAYLOAD_ICON)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        holder.bind(apps[position])
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty()) {
            val appInfo = apps[position]
            var handled = false
            if (payloads.contains(PAYLOAD_SELECTION)) {
                holder.bindSelection(appInfo)
                handled = true
            }
            if (payloads.contains(PAYLOAD_ICON)) {
                holder.bindIconState(appInfo.packageName)
                handled = true
            }
            if (handled) {
                return
            }
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun getItemCount() = apps.size

    override fun getItemId(position: Int): Long {
        return apps[position].packageName.hashCode().toLong()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val ctx = parent.context
        return AppViewHolder(ItemRecyclerBypassListBinding.inflate(LayoutInflater.from(ctx), parent, false))
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        if (iconLoader == null) {
            iconLoader = AppIconLoader(recyclerView.context.applicationContext.packageManager)
        }
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        iconLoader?.clear()
        iconLoader = null
        super.onDetachedFromRecyclerView(recyclerView)
    }

    override fun onViewRecycled(holder: AppViewHolder) {
        holder.recycle()
        super.onViewRecycled(holder)
    }

    inner class AppViewHolder(private val itemBypassBinding: ItemRecyclerBypassListBinding) : RecyclerView.ViewHolder(itemBypassBinding.root) {
        private lateinit var appInfo: AppInfo
        private var iconJob: Job? = null

        init {
            UiMotion.attachPressFeedback(itemBypassBinding.infoContainer, pressedScale = 0.992f)
            itemBypassBinding.infoContainer.setOnClickListener {
                toggleSelection()
            }
            itemBypassBinding.checkBox.setOnClickListener {
                toggleSelection()
            }
        }

        fun bind(appInfo: AppInfo) {
            this.appInfo = appInfo

            bindIconState(appInfo.packageName)
            itemBypassBinding.name.text = appInfo.appName
            itemBypassBinding.systemBadge.isVisible = appInfo.isSystemApp
            itemBypassBinding.packageName.text = appInfo.packageName
            bindSelection(appInfo)
        }

        fun bindSelection(appInfo: AppInfo) {
            this.appInfo = appInfo
            itemBypassBinding.checkBox.isChecked = appInfo.isSelected == 1
        }

        fun recycle() {
            iconJob?.cancel()
            iconJob = null
            itemBypassBinding.icon.setImageDrawable(iconLoader?.placeholder(itemBypassBinding.icon))
        }

        fun bindIconState(packageName: String) {
            val loader = iconLoader
            if (loader == null) {
                itemBypassBinding.icon.setImageDrawable(null)
                return
            }
            iconJob?.cancel()
            itemBypassBinding.icon.setImageDrawable(loader.placeholder(itemBypassBinding.icon))
            if (!iconLoadingEnabled) {
                return
            }
            iconJob = loader.load(packageName) { requestedPackageName, drawable ->
                if (::appInfo.isInitialized && appInfo.packageName == requestedPackageName) {
                    itemBypassBinding.icon.setImageDrawable(drawable)
                }
            }
        }

        private fun toggleSelection() {
            setSelection(appInfo.isSelected != 1)
        }

        private fun setSelection(selected: Boolean) {
            val isCurrentlySelected = appInfo.isSelected == 1
            if (isCurrentlySelected == selected) {
                itemBypassBinding.checkBox.isChecked = selected
                return
            }
            if (selected) {
                appInfo = appInfo.copy(isSelected = 1)
            } else {
                appInfo = appInfo.copy(isSelected = 0)
            }
            bindSelection(appInfo)
            onSelectionChanged(appInfo, selected)
        }
    }

    private class AppIconLoader(
        private val packageManager: PackageManager
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private val ioDispatcher = Dispatchers.IO.limitedParallelism(2)
        private val cache = LruCache<String, Drawable>(ICON_CACHE_SIZE)
        private val fallbackIcon: Drawable by lazy(LazyThreadSafetyMode.NONE) {
            packageManager.defaultActivityIcon
        }

        fun load(packageName: String, onLoaded: (String, Drawable) -> Unit): Job {
            val cached = cache[packageName]
            if (cached != null) {
                return scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    onLoaded(packageName, cached.copy())
                }
            }
            return scope.launch {
                val icon = withContext(ioDispatcher) {
                    runCatching { packageManager.getApplicationIcon(packageName) }.getOrNull()
                } ?: fallbackIcon
                cache.put(packageName, icon)
                onLoaded(packageName, icon.copy())
            }
        }

        fun placeholder(target: ImageView): Drawable {
            return fallbackIcon.constantState?.newDrawable(target.resources)?.mutate() ?: fallbackIcon
        }

        fun clear() {
            scope.coroutineContext.cancelChildren()
            cache.evictAll()
        }

        private fun Drawable.copy(): Drawable {
            return constantState?.newDrawable()?.mutate() ?: this
        }
    }
}
