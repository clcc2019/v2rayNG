package com.v2ray.ang.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.contracts.BaseAdapterListener
import com.v2ray.ang.databinding.ActivityRoutingSettingBinding
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.helper.SimpleItemTouchHelperCallback
import com.v2ray.ang.ui.common.v2rayAlertDialogBuilder
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.Utils
import com.v2ray.ang.viewmodel.RoutingSettingsViewModel
import com.v2ray.ang.viewmodel.UserAssetViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RoutingSettingActivity : HelperBaseActivity() {
    private val binding by lazy { ActivityRoutingSettingBinding.inflate(layoutInflater) }
    private val ownerActivity: RoutingSettingActivity
        get() = this
    private val viewModel: RoutingSettingsViewModel by viewModels()
    private val assetViewModel: UserAssetViewModel by viewModels()
    private lateinit var adapter: RoutingSettingRecyclerAdapter
    private var mItemTouchHelper: ItemTouchHelper? = null
    private var refreshJob: Job? = null
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
        // Routing list lives in a NestedScrollView and uses wrap_content height, so it must remeasure
        // after async list updates instead of being treated as a fixed-size RecyclerView.
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
        binding.layoutPerAppProxySettings.setOnClickListener {
            startActivity(Intent(this, PerAppProxyActivity::class.java))
        }
        binding.layoutRoutingAssets.setOnClickListener {
            startActivity(Intent(this, UserAssetActivity::class.java))
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
        R.id.add_rule -> startActivity(Intent(this, RoutingEditActivity::class.java)).let { true }
        R.id.import_predefined_rulesets -> importPredefined().let { true }
        R.id.import_rulesets_from_clipboard -> importFromClipboard().let { true }
        R.id.import_rulesets_from_qrcode -> importQRcode()
        R.id.export_rulesets_to_clipboard -> export2Clipboard().let { true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun getDomainStrategy(): String {
        return MmkvManager.decodeSettingsString(AppConfig.PREF_ROUTING_DOMAIN_STRATEGY) ?: routing_domain_strategy.first()
    }

    private fun setDomainStrategy() {
        v2rayAlertDialogBuilder().setItems(routing_domain_strategy.asList().toTypedArray()) { _, i ->
            try {
                val value = routing_domain_strategy[i]
                MmkvManager.encodeSettings(AppConfig.PREF_ROUTING_DOMAIN_STRATEGY, value)
                binding.tvDomainStrategySummary.text = value
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "Failed to set domain strategy", e)
            }
        }.show()
    }

    private fun importPredefined() {
        v2rayAlertDialogBuilder().setItems(preset_rulesets.asList().toTypedArray()) { _, i ->
            v2rayAlertDialogBuilder().setMessage(R.string.routing_settings_import_rulesets_tip)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    try {
                        lifecycleScope.launch(Dispatchers.IO) {
                            SettingsManager.resetRoutingRulesetsFromPresets(this@RoutingSettingActivity, i)
                            launch(Dispatchers.Main) {
                                refreshData()
                                toastSuccess(R.string.toast_success)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(AppConfig.TAG, "Failed to import predefined ruleset", e)
                    }
                }
                .setNegativeButton(android.R.string.cancel) { _, _ ->
                    //do nothing
                }
                .show()
        }.show()
    }

    private fun importFromClipboard() {
        v2rayAlertDialogBuilder().setMessage(R.string.routing_settings_import_rulesets_tip)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val clipboard = try {
                    Utils.getClipboard(this)
                } catch (e: Exception) {
                    Log.e(AppConfig.TAG, "Failed to get clipboard content", e)
                    toastError(R.string.toast_failure)
                    return@setPositiveButton
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
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                //do nothing
            }
            .show()
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
        val rulesetList = MmkvManager.decodeRoutingRulesets()
        if (rulesetList.isNullOrEmpty()) {
            toastError(R.string.toast_failure)
        } else {
            Utils.setClipboard(this, JsonUtil.toJson(rulesetList))
            toastSuccess(R.string.toast_success)
        }
    }


    private fun importRulesetsFromQRcode(qrcode: String?): Boolean {
        v2rayAlertDialogBuilder().setMessage(R.string.routing_settings_import_rulesets_tip)
            .setPositiveButton(android.R.string.ok) { _, _ ->
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
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                //do nothing
            }
            .show()
        return true
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
                adapter.submitList(items) {
                    binding.recyclerView.requestLayout()
                }
            }
        }
    }

    private inner class ActivityAdapterListener : BaseAdapterListener {
        override fun onEdit(guid: String, position: Int) {
            startActivity(
                Intent(ownerActivity, RoutingEditActivity::class.java)
                    .putExtra("position", position)
            )
        }

        override fun onRemove(guid: String, position: Int) {
        }

        override fun onShare(url: String) {
        }

        override fun onRefreshData() {
            refreshData()
        }
    }
}
