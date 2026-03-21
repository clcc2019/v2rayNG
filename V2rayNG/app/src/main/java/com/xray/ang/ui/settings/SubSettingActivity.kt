package com.xray.ang.ui

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
import com.xray.ang.AppConfig
import com.xray.ang.R
import com.xray.ang.contracts.BaseAdapterListener
import com.xray.ang.databinding.ActivitySubSettingBinding
import com.xray.ang.databinding.ItemQrcodeBinding
import com.xray.ang.extension.toast
import com.xray.ang.extension.toastError
import com.xray.ang.handler.AngConfigManager
import com.xray.ang.handler.MmkvManager
import com.xray.ang.helper.SimpleItemTouchHelperCallback
import com.xray.ang.ui.common.actionBottomSheetItem
import com.xray.ang.ui.common.runWithRemovalConfirmation
import com.xray.ang.ui.common.showActionBottomSheet
import com.xray.ang.ui.common.startActivityWithDefaultTransition
import com.xray.ang.util.QRCodeDecoder
import com.xray.ang.util.Utils
import com.xray.ang.dto.SubscriptionCache
import com.xray.ang.viewmodel.SubscriptionsViewModel
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
    private var lastSubscriptionsSignature: Int? = null
    private val shareMethods: Array<out String> by lazy {
        resources.getStringArray(R.array.share_sub_method)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentViewWithToolbar(binding.root, showHomeAsUp = true, title = getString(R.string.title_subscription_list))

        adapter = SubSettingRecyclerAdapter(viewModel, ActivityAdapterListener()) { viewHolder ->
            itemTouchHelper?.startDrag(viewHolder)
        }
        setupHeaderActions()
        setupRecyclerView()
        preloadInitialSubscriptions()
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
        binding.recyclerView.itemAnimator = null
        binding.recyclerView.adapter = adapter

        itemTouchHelper = ItemTouchHelper(SimpleItemTouchHelperCallback(adapter, allowLongPressDrag = false))
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

    fun refreshData(force: Boolean = false) {
        val currentSignature = MmkvManager.decodeSubscriptions().hashCode()
        if (!force && lastSubscriptionsSignature == currentSignature) {
            return
        }
        refreshJob?.cancel()
        refreshJob = lifecycleScope.launch(Dispatchers.IO) {
            viewModel.reload()
            val items = viewModel.getAll()
            withContext(Dispatchers.Main) {
                renderSubscriptions(items, currentSignature)
            }
        }
    }

    private fun preloadInitialSubscriptions() {
        renderSubscriptions(viewModel.getAll(), MmkvManager.decodeSubscriptions().hashCode())
    }

    private fun renderSubscriptions(items: List<SubscriptionCache>, signature: Int) {
        adapter.submitList(items)
        lastSubscriptionsSignature = signature
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
