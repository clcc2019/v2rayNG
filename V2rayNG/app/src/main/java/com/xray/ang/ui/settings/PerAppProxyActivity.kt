package com.xray.ang.ui

import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import androidx.activity.viewModels
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.xray.ang.AppConfig
import com.xray.ang.AppConfig.ANG_PACKAGE
import com.xray.ang.R
import com.xray.ang.databinding.ActivityBypassListBinding
import com.xray.ang.dto.AppInfo
import com.xray.ang.extension.toast
import com.xray.ang.extension.toastError
import com.xray.ang.extension.toastLong
import com.xray.ang.extension.toastSuccess
import com.xray.ang.extension.v2RayApplication
import com.xray.ang.handler.MmkvManager
import com.xray.ang.handler.SettingsChangeManager
import com.xray.ang.handler.SettingsManager
import com.xray.ang.util.AppManagerUtil
import com.xray.ang.util.HttpUtil
import com.xray.ang.util.Utils
import com.xray.ang.viewmodel.PerAppProxyViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Collator

class PerAppProxyActivity : BaseActivity() {
    private enum class AppListFilter {
        ALL,
        SELECTED_ONLY,
        USER_ONLY,
        SYSTEM_ONLY
    }

    private val binding by lazy { ActivityBypassListBinding.inflate(layoutInflater) }

    private lateinit var adapter: PerAppProxyAdapter
    private var appsAll: List<AppInfo> = emptyList()
    private val viewModel: PerAppProxyViewModel by viewModels()
    private var listUpdateJob: Job? = null
    private var currentFilter: String = ""
    private var currentAppListFilter: AppListFilter = AppListFilter.ALL

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentViewWithToolbar(binding.root, showHomeAsUp = true, title = getString(R.string.per_app_proxy_settings))
        setupRecyclerView()
        setupSwitchRows()
        setupActions()
        updateSelectionSummary()
        initList()
        postScreenContentEnterMotion(binding.root)
    }

    private fun setupRecyclerView() {
        binding.recyclerView.setHasFixedSize(true)
        optimizeRecyclerViewForHighRefresh(binding.recyclerView)
        binding.recyclerView.itemAnimator = null
        addCustomDividerToRecyclerView(binding.recyclerView, this, R.drawable.bg_per_app_list_divider)
        adapter = PerAppProxyAdapter(emptyList(), ::onAppSelectionChanged)
        binding.recyclerView.adapter = adapter
        binding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return
                adapter.setIconLoadingEnabled(
                    enabled = newState == RecyclerView.SCROLL_STATE_IDLE,
                    firstVisiblePosition = layoutManager.findFirstVisibleItemPosition(),
                    lastVisiblePosition = layoutManager.findLastVisibleItemPosition()
                )
            }
        })
    }

    private fun setupSwitchRows() {
        binding.switchPerAppProxy.isChecked = MmkvManager.decodeSettingsBool(AppConfig.PREF_PER_APP_PROXY, false)
        binding.switchBypassApps.isChecked = MmkvManager.decodeSettingsBool(AppConfig.PREF_BYPASS_APPS, false)

        binding.switchPerAppProxy.setOnCheckedChangeListener { _, isChecked ->
            MmkvManager.encodeSettings(AppConfig.PREF_PER_APP_PROXY, isChecked)
            SettingsChangeManager.makeRestartService()
            updateSelectionSummary()
        }
        binding.switchBypassApps.setOnCheckedChangeListener { _, isChecked ->
            MmkvManager.encodeSettings(AppConfig.PREF_BYPASS_APPS, isChecked)
            SettingsChangeManager.makeRestartService()
            updateSelectionSummary()
        }
    }

    private fun setupActions() {
        applyPressMotion(
            binding.containerPerAppProxy,
            binding.containerBypassApps,
            binding.layoutSwitchBypassAppsTips,
            binding.chipFilterAll,
            binding.chipFilterSelected,
            binding.chipFilterUser,
            binding.chipFilterSystem
        )
        binding.containerPerAppProxy.setOnClickListener {
            binding.switchPerAppProxy.toggle()
        }
        binding.containerBypassApps.setOnClickListener {
            binding.switchBypassApps.toggle()
        }
        binding.layoutSwitchBypassAppsTips.setOnClickListener {
            toastLong(R.string.summary_pref_per_app_proxy)
        }
        binding.chipFilterAll.setOnClickListener { applyAppListFilter(AppListFilter.ALL) }
        binding.chipFilterSelected.setOnClickListener { applyAppListFilter(AppListFilter.SELECTED_ONLY) }
        binding.chipFilterUser.setOnClickListener { applyAppListFilter(AppListFilter.USER_ONLY) }
        binding.chipFilterSystem.setOnClickListener { applyAppListFilter(AppListFilter.SYSTEM_ONLY) }
        updateFilterChips()
    }

    private fun initList() {
        launchLoadingTask(
            taskContext = Dispatchers.IO,
            onError = { e ->
                Log.e(ANG_PACKAGE, "Error loading apps", e)
            },
            task = {
                val appsList = AppManagerUtil.loadNetworkAppList(this@PerAppProxyActivity)
                val blacklistSet = viewModel.getAll()
                if (blacklistSet.isNotEmpty()) {
                    appsList.forEach { app ->
                        app.isSelected = if (blacklistSet.contains(app.packageName)) 1 else 0
                    }
                    appsList.sortedWith(
                        compareByDescending<AppInfo> { it.isSelected }
                            .thenBy { it.isSystemApp }
                            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.appName }
                            .thenBy { it.packageName }
                    )
                } else {
                    appsList.sortedWith(compareBy(Collator.getInstance()) { it.appName })
                }
            },
            onSuccess = { apps ->
                appsAll = apps
                scheduleListUpdate(currentFilter)
            }
        )
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_bypass_list, menu)
        setupSearchView(
            menuItem = menu.findItem(R.id.search_view),
            onQueryChanged = { filterProxyApp(it) },
            onClosed = { filterProxyApp("") },
            debounceMillis = 180L
        )
        return super.onCreateOptionsMenu(menu)
    }


    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.select_all -> {
            selectAllApp()
            true
        }
        R.id.invert_selection -> {
            invertSelection()
            true
        }

        R.id.select_proxy_app -> {
            selectProxyAppAuto()
            true
        }

        R.id.import_proxy_app -> {
            importProxyApp()
            true
        }

        R.id.export_proxy_app -> {
            exportProxyApp()
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    private fun selectAllApp() {
        val pkgNames = adapter.apps.map { it.packageName }
        val allSelected = viewModel.containsAll(pkgNames)

        if (allSelected) {
            viewModel.removeAll(pkgNames)
        } else {
            viewModel.addAll(pkgNames)
            ensurePerAppProxyEnabled()
        }
        refreshData()
        updateSelectionSummary()
    }

    private fun invertSelection() {
        viewModel.toggleAll(adapter.apps.map { it.packageName })
        ensurePerAppProxyEnabled()
        refreshData()
        updateSelectionSummary()
    }

    private fun selectProxyAppAuto() {
        toast(R.string.msg_downloading_content)
        val url = AppConfig.ANDROID_PACKAGE_NAME_LIST_URL
        launchLoadingTask(
            taskContext = Dispatchers.IO,
            onError = {
                toastError(R.string.toast_failure)
            },
            task = {
                var content = HttpUtil.getUrlContent(url, 5000)
                if (content.isNullOrEmpty()) {
                    val httpPort = SettingsManager.getHttpPort()
                    content = HttpUtil.getUrlContent(url, 5000, httpPort) ?: ""
                }
                content
            },
            onSuccess = { content ->
                applyProxySelection(content, true) { success ->
                    if (success) {
                        toastSuccess(R.string.toast_success)
                        ensurePerAppProxyEnabled()
                    } else {
                        toastError(R.string.toast_failure)
                    }
                }
            }
        )
    }

    private fun importProxyApp() {
        val content = Utils.getClipboard(applicationContext)
        if (TextUtils.isEmpty(content)) return
        applyProxySelection(content, false) { success ->
            if (success) {
                toastSuccess(R.string.toast_success)
                ensurePerAppProxyEnabled()
            } else {
                toastError(R.string.toast_failure)
            }
        }
    }

    private fun exportProxyApp() {
        val clipboardContent = buildList {
            add(binding.switchBypassApps.isChecked.toString())
            addAll(viewModel.getAll())
        }.joinToString(System.lineSeparator())
        Utils.setClipboard(applicationContext, clipboardContent)
        toastSuccess(R.string.toast_success)
    }

    private fun onAppSelectionChanged(appInfo: AppInfo, selected: Boolean) {
        if (selected) {
            viewModel.add(appInfo.packageName)
            ensurePerAppProxyEnabled()
        } else {
            viewModel.remove(appInfo.packageName)
        }
        refreshData()
        updateSelectionSummary()
    }

    private fun ensurePerAppProxyEnabled() {
        if (!binding.switchPerAppProxy.isChecked) {
            binding.switchPerAppProxy.isChecked = true
        }
    }

    private fun applyProxySelection(content: String, force: Boolean, onComplete: (Boolean) -> Unit) {
        val bypassChecked = binding.switchBypassApps.isChecked
        val appsSnapshot = appsAll.toList()
        lifecycleScope.launch(Dispatchers.Default) {
            val success = try {
                val proxyApps = if (TextUtils.isEmpty(content)) {
                    Utils.readTextFromAssets(v2RayApplication, "proxy_package_name")
                } else {
                    content
                }
                if (TextUtils.isEmpty(proxyApps)) {
                    false
                } else {
                    val proxyPackageNames = parseProxyPackageNames(proxyApps)
                    val selectedPackages = buildList(appsSnapshot.size) {
                        appsSnapshot.forEach { app ->
                            val matches = inProxyApps(proxyPackageNames, app.packageName, force)
                            if (bypassChecked) {
                                if (!matches) {
                                    add(app.packageName)
                                }
                            } else if (matches) {
                                add(app.packageName)
                            }
                        }
                    }
                    viewModel.replaceAll(selectedPackages)
                    true
                }
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "Error selecting proxy app", e)
                false
            }
            withContext(Dispatchers.Main) {
                if (success) {
                    refreshData()
                    updateSelectionSummary()
                }
                onComplete(success)
            }
        }
    }

    private fun parseProxyPackageNames(proxyApps: String): Set<String> {
        return proxyApps.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filterNot { it.equals("true", ignoreCase = true) || it.equals("false", ignoreCase = true) }
            .toSet()
    }

    private fun inProxyApps(proxyApps: Set<String>, packageName: String, force: Boolean): Boolean {
        if (force) {
            if (packageName == "com.google.android.webview") return false
            if (packageName.startsWith("com.google")) return true
        }

        return proxyApps.contains(packageName)
    }

    private fun filterProxyApp(content: String): Boolean {
        scheduleListUpdate(content)
        return true
    }

    private fun refreshData() {
        scheduleListUpdate(currentFilter)
    }

    private fun scheduleListUpdate(filter: String) {
        val key = filter.trim()
        currentFilter = key
        val snapshot = appsAll
        listUpdateJob?.cancel()
        listUpdateJob = lifecycleScope.launch(Dispatchers.Default) {
            val searchFilteredApps = if (key.isEmpty()) {
                snapshot
            } else {
                snapshot.filter {
                    it.appName.contains(key, ignoreCase = true)
                        || it.packageName.contains(key, ignoreCase = true)
                }
            }
            val synced = syncSelection(searchFilteredApps)
            val filteredApps = synced.filter(::matchesAppListFilter)
            withContext(Dispatchers.Main) {
                renderAppList(filteredApps)
            }
        }
    }

    private fun renderAppList(apps: List<AppInfo>) {
        adapter.submitList(apps)
        binding.recyclerView.isVisible = apps.isNotEmpty()
        binding.layoutEmptyState.isVisible = apps.isEmpty()
        updateSelectionSummary()
    }

    private fun syncSelection(apps: List<AppInfo>): List<AppInfo> {
        return apps.map { app ->
            val selected = if (viewModel.contains(app.packageName)) 1 else 0
            if (app.isSelected == selected) app else app.copy(isSelected = selected)
        }
    }

    private fun updateSelectionSummary() {
        binding.tvSelectionSummary.text = getString(
            R.string.per_app_proxy_selection_summary,
            viewModel.getAll().size,
            appsAll.size
        )
        binding.tvListSummary.text = getString(
            R.string.per_app_proxy_list_state_summary,
            getString(
                if (binding.switchBypassApps.isChecked) {
                    R.string.per_app_proxy_mode_bypass
                } else {
                    R.string.per_app_proxy_mode_general
                }
            ),
            getString(filterLabelRes(currentAppListFilter)),
            if (::adapter.isInitialized) adapter.apps.size else 0
        )
    }

    private fun applyAppListFilter(filter: AppListFilter) {
        if (currentAppListFilter == filter) {
            return
        }
        currentAppListFilter = filter
        updateFilterChips()
        refreshData()
    }

    private fun updateFilterChips() {
        binding.chipFilterAll.isSelected = currentAppListFilter == AppListFilter.ALL
        binding.chipFilterSelected.isSelected = currentAppListFilter == AppListFilter.SELECTED_ONLY
        binding.chipFilterUser.isSelected = currentAppListFilter == AppListFilter.USER_ONLY
        binding.chipFilterSystem.isSelected = currentAppListFilter == AppListFilter.SYSTEM_ONLY
    }

    private fun matchesAppListFilter(app: AppInfo): Boolean {
        return when (currentAppListFilter) {
            AppListFilter.ALL -> true
            AppListFilter.SELECTED_ONLY -> app.isSelected == 1
            AppListFilter.USER_ONLY -> !app.isSystemApp
            AppListFilter.SYSTEM_ONLY -> app.isSystemApp
        }
    }

    private fun filterLabelRes(filter: AppListFilter): Int {
        return when (filter) {
            AppListFilter.ALL -> R.string.per_app_proxy_filter_all
            AppListFilter.SELECTED_ONLY -> R.string.per_app_proxy_filter_selected
            AppListFilter.USER_ONLY -> R.string.per_app_proxy_filter_user
            AppListFilter.SYSTEM_ONLY -> R.string.per_app_proxy_filter_system
        }
    }
}
