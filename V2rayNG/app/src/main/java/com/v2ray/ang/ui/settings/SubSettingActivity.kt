package com.v2ray.ang.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.contracts.BaseAdapterListener
import com.v2ray.ang.databinding.ActivitySubSettingBinding
import com.v2ray.ang.databinding.ItemQrcodeBinding
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.helper.SimpleItemTouchHelperCallback
import com.v2ray.ang.ui.common.actionBottomSheetItem
import com.v2ray.ang.ui.common.runWithRemovalConfirmation
import com.v2ray.ang.ui.common.showActionBottomSheet
import com.v2ray.ang.ui.common.startActivityWithDefaultTransition
import com.v2ray.ang.util.QRCodeDecoder
import com.v2ray.ang.util.Utils
import com.v2ray.ang.viewmodel.SubscriptionsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SubSettingActivity : BaseActivity() {
    private val binding by lazy { ActivitySubSettingBinding.inflate(layoutInflater) }
    private val viewModel: SubscriptionsViewModel by viewModels()
    private lateinit var adapter: SubSettingRecyclerAdapter
    private var itemTouchHelper: ItemTouchHelper? = null
    private var refreshJob: Job? = null
    private val shareMethods: Array<out String> by lazy {
        resources.getStringArray(R.array.share_sub_method)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentViewWithToolbar(binding.root, showHomeAsUp = true, title = getString(R.string.title_subscription_list))

        adapter = SubSettingRecyclerAdapter(viewModel, ActivityAdapterListener())
        setupHeaderActions()
        setupRecyclerView()
        applyPressMotion(binding.actionAddSubscription, binding.actionRefreshSubscriptions)
        (binding.root.getChildAt(0) as? android.view.ViewGroup)?.let {
            postStaggeredEnterMotion(it, translationOffsetDp = 10f, startDelay = 36L)
        }
        postEnterMotion(binding.recyclerView, translationOffsetDp = 8f, startDelay = 72L)
    }

    override fun onResume() {
        super.onResume()
        refreshData()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.action_sub_setting, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.add_config -> {
            openSubscriptionEditor()
            true
        }

        R.id.sub_update -> {
            updateAllSubscriptions()
            true
        }

        else -> super.onOptionsItemSelected(item)

    }

    private fun setupHeaderActions() {
        binding.actionAddSubscription.setOnClickListener {
            openSubscriptionEditor()
        }
        binding.actionRefreshSubscriptions.setOnClickListener {
            updateAllSubscriptions()
        }
    }

    private fun setupRecyclerView() {
        binding.recyclerView.setHasFixedSize(true)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        optimizeRecyclerViewForHighRefresh(binding.recyclerView)
        binding.recyclerView.adapter = adapter

        itemTouchHelper = ItemTouchHelper(SimpleItemTouchHelperCallback(adapter))
        itemTouchHelper?.attachToRecyclerView(binding.recyclerView)
    }

    private fun openSubscriptionEditor(subId: String? = null) {
        startActivityWithDefaultTransition(
            Intent(this, SubEditActivity::class.java).apply {
                subId?.let { putExtra("subId", it) }
            }
        )
    }

    private fun updateAllSubscriptions() {
        showLoading()
        lifecycleScope.launch(Dispatchers.IO) {
            val result = AngConfigManager.updateConfigViaSubAll()
            delay(500L)
            launch(Dispatchers.Main) {
                when {
                    result.successCount + result.failureCount + result.skipCount == 0 -> {
                        toast(R.string.title_update_subscription_no_subscription)
                    }
                    result.successCount > 0 && result.failureCount + result.skipCount == 0 -> {
                        toast(getString(R.string.title_update_config_count, result.configCount))
                    }
                    else -> {
                        toast(
                            getString(
                                R.string.title_update_subscription_result,
                                result.configCount, result.successCount, result.failureCount, result.skipCount
                            )
                        )
                    }
                }
                hideLoading()
            }
        }
    }

    fun refreshData() {
        refreshJob?.cancel()
        refreshJob = lifecycleScope.launch(Dispatchers.IO) {
            viewModel.reload()
            val items = viewModel.getAll()
            withContext(Dispatchers.Main) {
                adapter.submitList(items)
            }
        }
    }

    private inner class ActivityAdapterListener : BaseAdapterListener {
        override fun onEdit(guid: String, position: Int) {
            openSubscriptionEditor(guid)
        }

        override fun onRemove(guid: String, position: Int) {
            if (!viewModel.canRemove(guid)) {
                toast(R.string.toast_action_not_allowed)
                refreshData()
                return
            }

            runWithRemovalConfirmation {
                if (!viewModel.remove(guid)) {
                    toast(R.string.toast_action_not_allowed)
                }
                refreshData()
            }
        }

        override fun onShare(url: String) {
            showActionBottomSheet(
                title = getString(R.string.title_subscription_list),
                actions = listOf(
                    actionBottomSheetItem(shareMethods[0], R.drawable.ic_qu_scan_24dp) {
                        try {
                            showLoading()
                            lifecycleScope.launch(Dispatchers.Default) {
                                val result = runCatching { QRCodeDecoder.createQRCode(url) }
                                launch(Dispatchers.Main) {
                                    result.onSuccess { bitmap ->
                                        val ivBinding = ItemQrcodeBinding.inflate(LayoutInflater.from(this@SubSettingActivity))
                                        ivBinding.ivQcode.setImageBitmap(bitmap)
                                        showActionBottomSheet(
                                            title = getString(R.string.action_qrcode),
                                            contentView = ivBinding.root
                                        )
                                    }.onFailure {
                                        toastError(R.string.toast_failure)
                                    }
                                    hideLoading()
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(AppConfig.TAG, "Share subscription failed", e)
                        }
                    },
                    actionBottomSheetItem(shareMethods[1], R.drawable.ic_copy) {
                        Utils.setClipboard(this@SubSettingActivity, url)
                    }
                )
            )
        }

        override fun onRefreshData() {
            refreshData()
        }
    }
}
