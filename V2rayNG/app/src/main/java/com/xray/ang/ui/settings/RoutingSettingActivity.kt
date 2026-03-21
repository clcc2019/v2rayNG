package com.xray.ang.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import com.xray.ang.AppConfig
import com.xray.ang.R
import com.xray.ang.contracts.BaseAdapterListener
import com.xray.ang.databinding.ActivityRoutingSettingBinding
import com.xray.ang.handler.MmkvManager
import com.xray.ang.handler.SettingsChangeManager
import com.xray.ang.handler.SettingsManager
import com.xray.ang.helper.SimpleItemTouchHelperCallback
import com.xray.ang.util.JsonUtil
import com.xray.ang.util.Utils
import com.xray.ang.viewmodel.RoutingSettingsViewModel
import com.xray.ang.viewmodel.UserAssetViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RoutingSettingActivity : HelperBaseActivity() {
    private data class RoutingActionItem(
        val label: CharSequence,
        val handler: () -> Unit
    )

    private val binding by lazy { ActivityRoutingSettingBinding.inflate(layoutInflater) }
    private val ownerActivity: RoutingSettingActivity
        get() = this
    private val viewModel: RoutingSettingsViewModel by viewModels()
    private val assetViewModel: UserAssetViewModel by viewModels()
    private lateinit var adapter: RoutingSettingRecyclerAdapter
    private var mItemTouchHelper: ItemTouchHelper? = null
    private var refreshJob: Job? = null
    private var lastRuleCount: Int? = null
    private var lastEnabledCount: Int? = null
    private var lastLockedCount: Int? = null
    private var lastEmptyStateVisible: Boolean? = null
    private val extDir by lazy { File(Utils.userAssetPath(this)) }
    private val routing_domain_strategy: Array<out String> by lazy {
        resources.getStringArray(R.array.routing_domain_strategy)
    }
    private val preset_rulesets: Array<out String> by lazy {
        resources.getStringArray(R.array.preset_rulesets)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        //setContentView(binding.root)
        setContentViewWithToolbar(binding.root, showHomeAsUp = true, title = getString(R.string.routing_settings_title))

        adapter = RoutingSettingRecyclerAdapter(viewModel, ActivityAdapterListener()) { viewHolder ->
            mItemTouchHelper?.startDrag(viewHolder)
        }

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        optimizeRecyclerViewForHighRefresh(binding.recyclerView)
        // RecyclerView is embedded in a NestedScrollView and needs to fully measure its content.
        binding.recyclerView.setHasFixedSize(false)
        binding.recyclerView.adapter = adapter

        mItemTouchHelper = ItemTouchHelper(
            SimpleItemTouchHelperCallback(adapter, allowLongPressDrag = false)
        )
        mItemTouchHelper?.attachToRecyclerView(binding.recyclerView)

        binding.tvDomainStrategySummary.text = getDomainStrategy()
        binding.layoutDomainStrategy.setOnClickListener {
            setDomainStrategy()
        }
        binding.actionAddRule.setOnClickListener {
            openRoutingEditor()
        }
        binding.actionMoreSheet.setOnClickListener {
            showMoreActionsSheet()
        }
        binding.actionEmptyAddRule.setOnClickListener {
            openRoutingEditor()
        }
        binding.layoutPerAppProxySettings.setOnClickListener {
            startActivityWithDefaultTransition(Intent(this, PerAppProxyActivity::class.java))
        }
        binding.layoutRoutingAssets.setOnClickListener {
            startActivityWithDefaultTransition(Intent(this, UserAssetActivity::class.java))
        }
        binding.layoutRoutingAssetsRefresh.setOnClickListener {
            refreshRoutingAssets()
        }
        binding.layoutRoutingAssetsRefresh.setOnTouchListener { view, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                view.parent?.requestDisallowInterceptTouchEvent(true)
            }
            false
        }
        applyPressMotion(
            binding.actionAddRule,
            binding.actionMoreSheet,
            binding.layoutDomainStrategy,
            binding.layoutPerAppProxySettings,
            binding.layoutRoutingAssets,
            binding.layoutRoutingAssetsRefresh,
            binding.actionEmptyAddRule
        )
        (binding.root.getChildAt(0) as? android.view.ViewGroup)?.let {
            postStaggeredEnterMotion(it, translationOffsetDp = 10f, startDelay = 36L)
        }
    }

    override fun onResume() {
        super.onResume()
        updateRoutingAssetsUpdatedAt()
        refreshData()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_routing_setting, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.add_rule -> openRoutingEditor().let { true }
        R.id.action_more_sheet -> showMoreActionsSheet().let { true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun openRoutingEditor(position: Int? = null) {
        startActivityWithDefaultTransition(
            Intent(this, RoutingEditActivity::class.java).apply {
                position?.let { putExtra("position", it) }
            }
        )
    }

    private fun getDomainStrategy(): String {
        return MmkvManager.decodeSettingsString(AppConfig.PREF_ROUTING_DOMAIN_STRATEGY) ?: routing_domain_strategy.first()
    }

    private fun setDomainStrategy() {
        showChoiceBottomSheet(
            title = getString(R.string.routing_settings_domain_strategy),
            options = routing_domain_strategy.toList(),
            iconRes = R.drawable.ic_routing_24dp
        ) { i ->
            try {
                val value = routing_domain_strategy[i]
                MmkvManager.encodeSettings(AppConfig.PREF_ROUTING_DOMAIN_STRATEGY, value)
                SettingsChangeManager.makeRestartService()
                binding.tvDomainStrategySummary.text = value
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "Failed to set domain strategy", e)
            }
        }
    }

    private fun importPredefined() {
        showActionBottomSheet(
            title = getString(R.string.routing_settings_import_predefined_rulesets),
            subtitle = getString(R.string.routing_settings_import_rulesets_tip),
            actions = preset_rulesets.mapIndexed { index, preset ->
                actionBottomSheetItem(preset, R.drawable.ic_routing_24dp) {
                    try {
                        lifecycleScope.launch(Dispatchers.IO) {
                            SettingsManager.resetRoutingRulesetsFromPresets(this@RoutingSettingActivity, index)
                            launch(Dispatchers.Main) {
                                refreshData()
                                toastSuccess(R.string.toast_success)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(AppConfig.TAG, "Failed to import predefined ruleset", e)
                    }
                }
            }
        )
    }

    private fun importFromClipboard() {
        showConfirmImportSheet {
            val clipboard = try {
                Utils.getClipboard(this)
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "Failed to get clipboard content", e)
                toastError(R.string.toast_failure)
                return@showConfirmImportSheet
            }
            lifecycleScope.launch(Dispatchers.IO) {
                val result = SettingsManager.resetRoutingRulesets(clipboard)
                withContext(Dispatchers.Main) {
                    if (result) {
                        refreshData()
                        toastSuccess(R.string.toast_success)
                    } else {
                        toastError(R.string.toast_failure)
                    }
                }
            }
        }
    }

    private fun importQRcode(): Boolean {
        launchQRCodeScanner { scanResult ->
            if (scanResult != null) {
                importRulesetsFromQRcode(scanResult)
            }
        }
        return true
    }

    private fun export2Clipboard() {
        val rulesetList = SettingsManager.getRoutingRulesets()
        if (rulesetList.isNullOrEmpty()) {
            toastError(R.string.toast_failure)
        } else {
            Utils.setClipboard(this, JsonUtil.toJson(rulesetList))
            toastSuccess(R.string.toast_success)
        }
    }


    private fun importRulesetsFromQRcode(qrcode: String?): Boolean {
        showConfirmImportSheet {
            lifecycleScope.launch(Dispatchers.IO) {
                val result = SettingsManager.resetRoutingRulesets(qrcode)
                withContext(Dispatchers.Main) {
                    if (result) {
                        refreshData()
                        toastSuccess(R.string.toast_success)
                    } else {
                        toastError(R.string.toast_failure)
                    }
                }
            }
        }
        return true
    }

    private fun showMoreActionsSheet() {
        val learnedDomains = SettingsManager.getLiteLearnedProxyDomains()
        val liteLearnedLabel = buildString {
            append(getString(R.string.routing_settings_lite_learned_domains))
            if (learnedDomains.isNotEmpty()) {
                append(" (")
                append(learnedDomains.size)
                append(')')
            }
        }
        showActionBottomSheet(
            title = getString(R.string.routing_settings_title),
            subtitle = getString(R.string.action_more),
            actions = listOf(
                actionBottomSheetItem(R.string.routing_settings_import_predefined_rulesets, R.drawable.ic_routing_24dp) {
                    importPredefined()
                },
                actionBottomSheetItem(R.string.routing_settings_import_rulesets_from_clipboard, R.drawable.ic_copy) {
                    importFromClipboard()
                },
                actionBottomSheetItem(R.string.routing_settings_import_rulesets_from_qrcode, R.drawable.ic_qu_scan_24dp) {
                    importQRcode()
                },
                actionBottomSheetItem(R.string.routing_settings_export_rulesets_to_clipboard, R.drawable.ic_share_24dp) {
                    export2Clipboard()
                },
                actionBottomSheetItem(liteLearnedLabel, R.drawable.ic_routing_24dp) {
                    showLiteLearnedDomainsSheet()
                }
            )
        )
    }

    private fun showLiteLearnedDomainsSheet() {
        val learnedDomains = SettingsManager.getLiteLearnedProxyDomains()
        val title = getString(R.string.routing_settings_lite_learned_domains)
        val subtitle = if (SettingsManager.isLiteDirectRoutingActive()) {
            null
        } else {
            getString(R.string.routing_settings_lite_learned_domains_inactive)
        }
        if (learnedDomains.isEmpty()) {
            showMessageBottomSheet(
                title = title,
                message = getString(R.string.routing_settings_lite_learned_domains_empty),
                actions = listOf(
                    actionBottomSheetItem(getString(android.R.string.ok), R.drawable.ic_action_done) {}
                )
            )
            return
        }

        val message = buildString {
            subtitle?.let {
                append(it)
                append("\n\n")
            }
            learnedDomains.forEachIndexed { index, domain ->
                append(index + 1)
                append(". ")
                append(domain)
                if (index != learnedDomains.lastIndex) {
                    append('\n')
                }
            }
        }

        showMessageBottomSheet(
            title = title,
            message = message,
            actions = listOf(
                actionBottomSheetItem(R.string.routing_settings_lite_learned_domains_export, R.drawable.ic_share_24dp) {
                    exportLiteLearnedDomainsToClipboard(learnedDomains)
                },
                actionBottomSheetItem(R.string.routing_settings_lite_learned_domains_manage, R.drawable.ic_routing_24dp) {
                    showLiteLearnedDomainManagementSheet(learnedDomains)
                },
                actionBottomSheetItem(R.string.routing_settings_lite_learned_domains_clear, R.drawable.ic_delete_24dp, destructive = true) {
                    clearLiteLearnedDomains()
                },
                actionBottomSheetItem(getString(android.R.string.ok), R.drawable.ic_action_done, secondary = true) {}
            )
        )
    }

    private fun showLiteLearnedDomainManagementSheet(domains: List<String>) {
        showChoiceBottomSheet(
            title = getString(R.string.routing_settings_lite_learned_domains_manage),
            subtitle = getString(R.string.routing_settings_lite_learned_domains_remove),
            options = domains,
            iconRes = R.drawable.ic_delete_24dp
        ) { index ->
            val target = domains.getOrNull(index) ?: return@showChoiceBottomSheet
            showActionBottomSheet(
                title = target,
                subtitle = getString(R.string.routing_settings_lite_learned_domains_remove),
                actions = listOf(
                    actionBottomSheetItem(R.string.routing_settings_lite_learned_domains_remove, R.drawable.ic_delete_24dp, destructive = true) {
                        removeLiteLearnedDomain(target)
                    },
                    actionBottomSheetItem(getString(android.R.string.cancel), R.drawable.ic_close_20dp, secondary = true) {}
                )
            )
        }
    }

    private fun exportLiteLearnedDomainsToClipboard(domains: List<String>) {
        Utils.setClipboard(this, domains.joinToString(separator = "\n"))
        toastSuccess(R.string.toast_success)
    }

    private fun clearLiteLearnedDomains() {
        if (SettingsManager.clearLiteLearnedProxyDomains()) {
            toastSuccess(R.string.routing_settings_lite_learned_domains_cleared)
        } else {
            toastError(R.string.toast_failure)
        }
    }

    private fun removeLiteLearnedDomain(domain: String) {
        if (SettingsManager.removeLiteLearnedProxyDomain(domain)) {
            toastSuccess(R.string.routing_settings_lite_learned_domains_removed)
        } else {
            toastError(R.string.toast_failure)
        }
    }

    private fun showConfirmImportSheet(onConfirmed: () -> Unit) {
        showActionBottomSheet(
            title = getString(R.string.action_confirm),
            subtitle = getString(R.string.routing_settings_import_rulesets_tip),
            actions = listOf(
                actionBottomSheetItem(getString(android.R.string.ok), R.drawable.ic_action_done) {
                    onConfirmed()
                },
                actionBottomSheetItem(getString(android.R.string.cancel), R.drawable.ic_close_20dp, secondary = true) {}
            )
        )
    }

    private fun getGeoFilesSources(): String {
        return MmkvManager.decodeSettingsString(AppConfig.PREF_GEO_FILES_SOURCES) ?: AppConfig.GEO_FILES_SOURCES.first()
    }

    private fun refreshRoutingAssets() {
        toast(R.string.msg_downloading_content)
        val httpPort = SettingsManager.getHttpPort()
        launchLoadingTask(
            taskContext = Dispatchers.IO,
            task = {
                assetViewModel.reload(getGeoFilesSources())
                assetViewModel.downloadGeoFiles(extDir, httpPort)
            },
            onSuccess = { result ->
                if (result.successCount > 0) {
                    toast(getString(R.string.title_update_config_count, result.successCount))
                } else {
                    toastError(R.string.toast_failure)
                }
                updateRoutingAssetsUpdatedAt()
            }
        )
    }

    private fun updateRoutingAssetsUpdatedAt() {
        val files = extDir.listFiles()
        if (files.isNullOrEmpty()) {
            binding.tvRoutingAssetsUpdated.text = getString(R.string.routing_assets_updated_unknown)
            return
        }
        val latest = files.maxOfOrNull { it.lastModified() } ?: return
        val formatted = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(latest))
        binding.tvRoutingAssetsUpdated.text = getString(R.string.routing_assets_updated_at, formatted)
    }

    fun refreshData() {
        refreshJob?.cancel()
        refreshJob = lifecycleScope.launch(Dispatchers.IO) {
            viewModel.reload()
            val items = viewModel.getAll()
            withContext(Dispatchers.Main) {
                updateRuleSummary(items)
                updateEmptyState(items.isEmpty())
                adapter.submitList(items) {
                    binding.recyclerView.requestLayout()
                }
            }
        }
    }

    private fun updateRuleSummary(items: List<com.xray.ang.dto.RulesetItem>) {
        val total = items.size
        val enabled = items.count { it.enabled }
        val locked = items.count { it.locked == true }
        updateSummaryValue(binding.tvRuleCount, total, lastRuleCount)
        updateSummaryValue(binding.tvRuleEnabledCount, enabled, lastEnabledCount)
        updateSummaryValue(binding.tvRuleLockedCount, locked, lastLockedCount)
        lastRuleCount = total
        lastEnabledCount = enabled
        lastLockedCount = locked
    }

    private fun updateEmptyState(isEmpty: Boolean) {
        val shouldAnimate = lastEmptyStateVisible != null &&
            lastEmptyStateVisible != isEmpty &&
            lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
        if (shouldAnimate) {
            UiMotion.animateVisibility(binding.layoutEmptyState, isEmpty, translationOffsetDp = 8f, duration = MotionTokens.SHORT_ANIMATION_DURATION)
            UiMotion.animateVisibility(binding.recyclerView, !isEmpty, translationOffsetDp = 6f, duration = MotionTokens.SHORT_ANIMATION_DURATION)
        } else {
            UiMotion.setVisibility(binding.layoutEmptyState, isEmpty)
            UiMotion.setVisibility(binding.recyclerView, !isEmpty)
        }
        lastEmptyStateVisible = isEmpty
    }

    private fun updateSummaryValue(view: android.widget.TextView, value: Int, previousValue: Int?) {
        view.text = value.toString()
        if (previousValue != null && previousValue != value) {
            UiMotion.animatePulse(view, pulseScale = 1.028f, duration = MotionTokens.PULSE_QUICK)
        }
    }

    private inner class ActivityAdapterListener : BaseAdapterListener {
        override fun onEdit(guid: String, position: Int) {
            ownerActivity.openRoutingEditor(position)
        }

        override fun onRemove(guid: String, position: Int) {
        }

        override fun onShare(url: String) {
        }

        override fun onRefreshData() {
            refreshData()
        }
    }

    private fun toast(@StringRes messageResId: Int) {
        Toast.makeText(this, getString(messageResId), Toast.LENGTH_SHORT).show()
    }

    private fun toast(message: CharSequence) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun toastSuccess(@StringRes messageResId: Int) {
        toast(messageResId)
    }

    private fun toastError(@StringRes messageResId: Int) {
        toast(messageResId)
    }

    private fun startActivityWithDefaultTransition(intent: Intent) {
        startActivity(intent)
    }

    private fun actionBottomSheetItem(
        @StringRes labelResId: Int,
        @DrawableRes iconRes: Int,
        destructive: Boolean = false,
        secondary: Boolean = false,
        handler: () -> Unit
    ): RoutingActionItem {
        return RoutingActionItem(label = getString(labelResId), handler = handler)
    }

    private fun actionBottomSheetItem(
        label: CharSequence,
        @DrawableRes iconRes: Int,
        destructive: Boolean = false,
        secondary: Boolean = false,
        handler: () -> Unit
    ): RoutingActionItem {
        return RoutingActionItem(label = label, handler = handler)
    }

    private fun showActionBottomSheet(
        title: CharSequence,
        subtitle: CharSequence? = null,
        actions: List<RoutingActionItem>
    ) {
        val labels = actions.map { it.label }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(subtitle?.takeIf { it.isNotBlank() })
            .setItems(labels) { _, which -> actions[which].handler() }
            .show()
    }

    private fun showChoiceBottomSheet(
        title: CharSequence,
        subtitle: CharSequence? = null,
        options: List<CharSequence>,
        @DrawableRes iconRes: Int,
        onSelected: (Int) -> Unit
    ) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(subtitle?.takeIf { it.isNotBlank() })
            .setItems(options.map(CharSequence::toString).toTypedArray()) { _, which ->
                onSelected(which)
            }
            .show()
    }

    private fun showMessageBottomSheet(
        title: CharSequence,
        message: CharSequence,
        actions: List<RoutingActionItem>
    ) {
        val builder = AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
        actions.getOrNull(0)?.let { builder.setPositiveButton(it.label) { _, _ -> it.handler() } }
        actions.getOrNull(1)?.let { builder.setNegativeButton(it.label) { _, _ -> it.handler() } }
        actions.getOrNull(2)?.let { builder.setNeutralButton(it.label) { _, _ -> it.handler() } }
        builder.show()
    }
}
