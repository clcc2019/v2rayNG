package com.v2ray.ang.ui

import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.AdapterListUpdateCallback
import androidx.recyclerview.widget.AsyncDifferConfig
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.v2ray.ang.databinding.ItemRecyclerBypassListBinding
import com.v2ray.ang.dto.AppInfo
import com.v2ray.ang.helper.ListDiffExecutors
import com.v2ray.ang.viewmodel.PerAppProxyViewModel
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
    val viewModel: PerAppProxyViewModel
) : RecyclerView.Adapter<PerAppProxyAdapter.BaseViewHolder>() {

    companion object {
        private const val VIEW_TYPE_HEADER = 0
        private const val VIEW_TYPE_ITEM = 1
        private const val ICON_CACHE_SIZE = 48
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

    init {
        setHasStableIds(true)
        submitList(apps)
    }

    fun submitList(newApps: List<AppInfo>) {
        differ.submitList(newApps.toList())
    }

    override fun onBindViewHolder(holder: BaseViewHolder, position: Int) {
        if (holder is AppViewHolder) {
            val appInfo = apps[position - 1]
            holder.bind(appInfo)
        }
    }

    override fun getItemCount() = apps.size + 1

    override fun getItemId(position: Int): Long {
        return if (position == 0) {
            Long.MIN_VALUE
        } else {
            apps[position - 1].packageName.hashCode().toLong()
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BaseViewHolder {
        val ctx = parent.context

        return when (viewType) {
            VIEW_TYPE_HEADER -> {
                val view = View(ctx)
                view.layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0
                )
                BaseViewHolder(view)
            }

            else -> AppViewHolder(ItemRecyclerBypassListBinding.inflate(LayoutInflater.from(ctx), parent, false))
        }
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

    override fun onViewRecycled(holder: BaseViewHolder) {
        if (holder is AppViewHolder) {
            holder.recycle()
        }
        super.onViewRecycled(holder)
    }

    override fun getItemViewType(position: Int) = if (position == 0) VIEW_TYPE_HEADER else VIEW_TYPE_ITEM

    open class BaseViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView)

    inner class AppViewHolder(private val itemBypassBinding: ItemRecyclerBypassListBinding) : BaseViewHolder(itemBypassBinding.root),
        View.OnClickListener {
        private lateinit var appInfo: AppInfo
        private var iconJob: Job? = null

        init {
            itemView.setOnClickListener(this)
        }

        fun bind(appInfo: AppInfo) {
            this.appInfo = appInfo

            bindIcon(appInfo.packageName)
            itemBypassBinding.name.text = appInfo.appName
            itemBypassBinding.systemBadge.isVisible = appInfo.isSystemApp
            itemBypassBinding.packageName.text = appInfo.packageName
            itemBypassBinding.checkBox.isChecked = appInfo.isSelected == 1
        }

        fun recycle() {
            iconJob?.cancel()
            iconJob = null
            itemBypassBinding.icon.setImageDrawable(iconLoader?.placeholder(itemBypassBinding.icon))
        }

        private fun bindIcon(packageName: String) {
            val loader = iconLoader
            if (loader == null) {
                itemBypassBinding.icon.setImageDrawable(null)
                return
            }
            iconJob?.cancel()
            itemBypassBinding.icon.setImageDrawable(loader.placeholder(itemBypassBinding.icon))
            iconJob = loader.load(packageName) { requestedPackageName, drawable ->
                if (::appInfo.isInitialized && appInfo.packageName == requestedPackageName) {
                    itemBypassBinding.icon.setImageDrawable(drawable)
                }
            }
        }

        override fun onClick(v: View?) {
            val packageName = appInfo.packageName
            viewModel.toggle(packageName)
            val isSelected = if (appInfo.isSelected == 1) 0 else 1
            appInfo = appInfo.copy(isSelected = isSelected)
            itemBypassBinding.checkBox.isChecked = isSelected == 1
        }
    }

    private class AppIconLoader(
        private val packageManager: PackageManager
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
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
                val icon = withContext(Dispatchers.IO) {
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
