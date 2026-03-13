package com.v2ray.ang.ui

import android.content.Intent
import android.graphics.Canvas
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EdgeEffect
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.contracts.MainAdapterListener
import com.v2ray.ang.databinding.FragmentGroupServerBinding
import com.v2ray.ang.databinding.ItemQrcodeBinding
import com.v2ray.ang.dto.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.helper.SimpleItemTouchHelperCallback
import com.v2ray.ang.ui.common.ActionBottomSheetItem
import com.v2ray.ang.ui.common.actionBottomSheetItem
import com.v2ray.ang.ui.common.runWithRemovalConfirmation
import com.v2ray.ang.ui.common.showActionBottomSheet
import com.v2ray.ang.ui.common.v2rayAlertDialogBuilder
import com.v2ray.ang.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GroupServerFragment : BaseFragment<FragmentGroupServerBinding>() {
    private val ownerActivity: MainActivity
        get() = requireActivity() as MainActivity
    private val mainViewModel: MainViewModel by activityViewModels()
    private lateinit var adapter: MainRecyclerAdapter
    private val subId: String by lazy { arguments?.getString(ARG_SUB_ID).orEmpty() }
    private var shouldRefreshOnResume = false
    private var lastKnownItemCount: Int? = null
    private var storedCountJob: Job? = null
    private var pendingListFadeIn = false
    private var lastEmptyStateVisible: Boolean? = null
    private var storedCountRequestVersion = 0
    private var lastFilterBlank: Boolean? = null

    companion object {
        private const val ARG_SUB_ID = "subscriptionId"
        fun newInstance(subId: String) = GroupServerFragment().apply {
            arguments = Bundle().apply { putString(ARG_SUB_ID, subId) }
        }
    }

    override fun inflateBinding(inflater: LayoutInflater, container: ViewGroup?) =
        FragmentGroupServerBinding.inflate(inflater, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = MainRecyclerAdapter(mainViewModel, ActivityAdapterListener())
        setupRecyclerView()
        pendingListFadeIn = true
        if (mainViewModel.keywordFilter.isBlank()) {
            fetchStoredItemCount()
        }

        mainViewModel.updateListAction.observe(viewLifecycleOwner) { update ->
            if (mainViewModel.subscriptionId != subId) {
                return@observe
            }
            // Log.d(TAG, "GroupServerFragment updateListAction subId=$subId")
            when (update) {
                is MainViewModel.ServerListUpdate.Full -> {
                    adapter.setData(mainViewModel.serversCache, -1)
                    lastKnownItemCount = mainViewModel.serversCache.size
                    updateEmptyState()
                }
                is MainViewModel.ServerListUpdate.Single -> {
                    adapter.setData(mainViewModel.serversCache, update.position)
                }
                is MainViewModel.ServerListUpdate.Batch -> {
                    adapter.updateTestResults(mainViewModel.serversCache, update.positions)
                }
                null -> Unit
            }
        }

        updateEmptyState()
        // Log.d(TAG, "GroupServerFragment onViewCreated: subId=$subId")
    }

    override fun onResume() {
        super.onResume()
        if (mainViewModel.subscriptionId == subId) {
            pendingListFadeIn = true
        }
        if (mainViewModel.keywordFilter.isBlank()) {
            fetchStoredItemCount()
        }
        mainViewModel.ensureSubscriptionLoaded(subId, forceReload = shouldRefreshOnResume)
        shouldRefreshOnResume = false
        if (mainViewModel.subscriptionId == subId) {
            lastKnownItemCount = mainViewModel.serversCache.size
        }
        updateEmptyState()
        ownerActivity.onServerListScrolled(0, binding.recyclerView.canScrollVertically(-1))
    }

    private fun setupRecyclerView() {
        binding.recyclerView.setHasFixedSize(true)
        binding.recyclerView.layoutManager = GridLayoutManager(requireContext(), getSpanCount())
        binding.recyclerView.itemAnimator = null
        binding.recyclerView.edgeEffectFactory = object : RecyclerView.EdgeEffectFactory() {
            override fun createEdgeEffect(view: RecyclerView, direction: Int): EdgeEffect {
                return object : EdgeEffect(view.context) {
                    override fun onPull(deltaDistance: Float) {
                        // No-op to disable stretch/glow.
                    }

                    override fun onPull(deltaDistance: Float, displacement: Float) {
                        // No-op to disable stretch/glow.
                    }

                    override fun onPullDistance(deltaDistance: Float, displacement: Float): Float {
                        return 0f
                    }

                    override fun draw(canvas: Canvas): Boolean {
                        return false
                    }
                }
            }
        }
        optimizeRecyclerViewForHighRefresh(binding.recyclerView)
        binding.recyclerView.adapter = adapter
        binding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (mainViewModel.subscriptionId != subId) {
                    return
                }
                ownerActivity.onServerListScrolled(dy, recyclerView.canScrollVertically(-1))
            }
        })
        ItemTouchHelper(SimpleItemTouchHelperCallback(adapter, allowSwipe = false))
            .attachToRecyclerView(binding.recyclerView)
    }

    private fun getSpanCount(): Int {
        return if (MmkvManager.decodeSettingsBool(AppConfig.PREF_DOUBLE_COLUMN_DISPLAY, false)) 2 else 1
    }

    private fun updateEmptyState() {
        if (mainViewModel.subscriptionId != subId && mainViewModel.keywordFilter.isBlank() && lastKnownItemCount == null) {
            return
        }
        val itemCount = when {
            mainViewModel.keywordFilter.isNotBlank() && mainViewModel.subscriptionId == subId -> {
                mainViewModel.serversCache.size.also { lastKnownItemCount = it }
            }
            mainViewModel.keywordFilter.isBlank() && mainViewModel.subscriptionId == subId -> {
                mainViewModel.serversCache.size.also { lastKnownItemCount = it }
            }
            else -> lastKnownItemCount
        }
        if (itemCount == null && mainViewModel.keywordFilter.isBlank()) {
            fetchStoredItemCount()
            return
        }
        val shouldShowEmptyState = itemCount == 0
        if (shouldShowEmptyState) {
            if (binding.recyclerView.isVisible) {
                binding.recyclerView.isVisible = false
            }
        } else if (pendingListFadeIn) {
            binding.recyclerView.alpha = 0f
            binding.recyclerView.translationY = resources.displayMetrics.density * 6f
            UiMotion.animateVisibility(binding.recyclerView, true, translationOffsetDp = 6f, duration = 140L)
            pendingListFadeIn = false
        } else if (!binding.recyclerView.isVisible || binding.recyclerView.alpha != 1f || binding.recyclerView.translationY != 0f) {
            binding.recyclerView.isVisible = true
            binding.recyclerView.alpha = 1f
            binding.recyclerView.translationY = 0f
        }
        val shouldAnimateEmptyState =
            lastEmptyStateVisible != null && lastEmptyStateVisible != shouldShowEmptyState && isResumed
        if (shouldAnimateEmptyState) {
            UiMotion.animateVisibility(binding.layoutEmptyState, shouldShowEmptyState, translationOffsetDp = 10f, duration = 140L)
        } else {
            UiMotion.setVisibility(binding.layoutEmptyState, shouldShowEmptyState)
        }
        lastEmptyStateVisible = shouldShowEmptyState
        val filterBlank = mainViewModel.keywordFilter.isBlank()
        if (lastFilterBlank != filterBlank) {
            binding.tvEmptyTitle.setText(
                if (filterBlank) R.string.empty_server_title else R.string.empty_search_title
            )
            binding.tvEmptySubtitle.setText(
                if (filterBlank) R.string.empty_server_subtitle else R.string.empty_search_subtitle
            )
            lastFilterBlank = filterBlank
        }
    }

    private fun fetchStoredItemCount() {
        if (!isAdded) return
        storedCountJob?.cancel()
        val requestVersion = ++storedCountRequestVersion
        val targetSubId = subId
        storedCountJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Default) {
            val count = mainViewModel.countServers(targetSubId)
            withContext(Dispatchers.Main) {
                if (!isAdded || requestVersion != storedCountRequestVersion) {
                    return@withContext
                }
                if (mainViewModel.keywordFilter.isNotBlank()) {
                    return@withContext
                }
                val changed = lastKnownItemCount != count
                lastKnownItemCount = count
                if (count == 0 && mainViewModel.subscriptionId != targetSubId) {
                    adapter.setData(mutableListOf())
                }
                if (changed) {
                    updateEmptyState()
                } else if (lastEmptyStateVisible == null) {
                    updateEmptyState()
                }
            }
        }
    }

    private fun showServerActions(guid: String, profile: ProfileItem, position: Int) {
        focusServerItem(position)
        val description = profile.description.orEmpty().ifBlank { profile.server.orEmpty() }
        ownerActivity.showActionBottomSheet(
            title = profile.remarks,
            subtitle = description,
            actions = buildServerActions(guid, profile, position)
        )
    }

    private fun focusServerItem(position: Int) {
        if (position == RecyclerView.NO_POSITION || position < 0) return
        val layoutManager = binding.recyclerView.layoutManager as? GridLayoutManager ?: return
        val first = layoutManager.findFirstVisibleItemPosition()
        val last = layoutManager.findLastVisibleItemPosition()
        if (position < first || position > last) {
            binding.recyclerView.smoothScrollToPosition(position)
        }
        binding.recyclerView.postDelayed({
            val holder = binding.recyclerView.findViewHolderForAdapterPosition(position)
                as? MainRecyclerAdapter.MainViewHolder
            holder?.itemMainBinding?.itemBg?.let { UiMotion.animatePulse(it, pulseScale = 1.02f, duration = MotionTokens.PULSE_MEDIUM) }
        }, MotionTokens.RELEASE_DURATION)
    }

    private fun buildServerActions(guid: String, profile: ProfileItem, position: Int): List<ActionBottomSheetItem> {
        val isCustom = profile.configType == EConfigType.CUSTOM || profile.configType == EConfigType.POLICYGROUP
        val actions = mutableListOf<ActionBottomSheetItem>()

        if (!isCustom) {
            actions += actionBottomSheetItem(
                label = getString(R.string.action_qrcode),
                iconRes = R.drawable.ic_qu_scan_24dp
            ) { showQRCode(guid) }
            actions += actionBottomSheetItem(
                label = getString(R.string.action_export_clipboard),
                iconRes = R.drawable.ic_copy
            ) { share2Clipboard(guid) }
        }

        actions += actionBottomSheetItem(
            label = getString(R.string.action_export_full_clipboard),
            iconRes = R.drawable.ic_description_24dp
        ) { shareFullContent(guid) }
        actions += actionBottomSheetItem(
            label = getString(R.string.menu_item_edit_config),
            iconRes = R.drawable.ic_edit_24dp
        ) { editServer(guid, profile) }
        actions += actionBottomSheetItem(
            label = getString(R.string.menu_item_del_config),
            iconRes = R.drawable.ic_delete_24dp,
            destructive = true
        ) { removeServer(guid, position) }

        return actions
    }

    /**
     * Displays QR code for the server configuration
     * @param guid The server unique identifier
     */
    private fun showQRCode(guid: String) {
        val ivBinding = ItemQrcodeBinding.inflate(LayoutInflater.from(ownerActivity))
        ivBinding.ivQcode.setImageBitmap(AngConfigManager.share2QRCode(guid))
        ivBinding.ivQcode.contentDescription = getString(R.string.action_qrcode)
        ownerActivity.v2rayAlertDialogBuilder().setView(ivBinding.root).show()
    }

    /**
     * Shares server configuration to clipboard
     * @param guid The server unique identifier
     */
    private fun share2Clipboard(guid: String) {
        if (AngConfigManager.share2Clipboard(ownerActivity, guid) == 0) {
            ownerActivity.toastSuccess(R.string.toast_success)
        } else {
            ownerActivity.toastError(R.string.toast_failure)
        }
    }

    /**
     * Shares full server configuration content to clipboard
     * @param guid The server unique identifier
     */
    private fun shareFullContent(guid: String) {
        ownerActivity.lifecycleScope.launch(Dispatchers.IO) {
            val result = AngConfigManager.shareFullContent2Clipboard(ownerActivity, guid)
            launch(Dispatchers.Main) {
                if (result == 0) {
                    ownerActivity.toastSuccess(R.string.toast_success)
                } else {
                    ownerActivity.toastError(R.string.toast_failure)
                }
            }
        }
    }

    /**
     * Edits server configuration
     * Opens appropriate editing interface based on configuration type
     * @param guid The server unique identifier
     * @param profile The server configuration
     */
    private fun editServer(guid: String, profile: ProfileItem) {
        shouldRefreshOnResume = true
        val intent = Intent().putExtra("guid", guid)
            .putExtra("isRunning", mainViewModel.isRunning.value)
            .putExtra("createConfigType", profile.configType.value)
            .putExtra("subscriptionId", subId)
        when (profile.configType) {
            EConfigType.CUSTOM -> {
                ownerActivity.startActivity(intent.setClass(ownerActivity, ServerCustomConfigActivity::class.java))
            }

            EConfigType.POLICYGROUP -> {
                ownerActivity.startActivity(intent.setClass(ownerActivity, ServerGroupActivity::class.java))
            }

            else -> {
                ownerActivity.startActivity(intent.setClass(ownerActivity, ServerActivity::class.java))
            }
        }
    }

    /**
     * Removes server configuration
     * Handles confirmation dialog and related checks
     * @param guid The server unique identifier
     * @param position The position in the list
     */
    private fun removeServer(guid: String, position: Int) {
        if (guid == MmkvManager.getSelectServer()) {
            ownerActivity.toast(R.string.toast_action_not_allowed)
            return
        }

        ownerActivity.runWithRemovalConfirmation {
            removeServerSub(guid, position)
        }
    }

    /**
     * Executes the actual server removal process
     * @param guid The server unique identifier
     * @param position The position in the list
     */
    private fun removeServerSub(guid: String, position: Int) {
        mainViewModel.removeServer(guid)
        adapter.removeServerSub(guid, position)
    }

    fun scrollToTop(animate: Boolean = true) {
        if (!isAdded) return
        if (binding.recyclerView.adapter?.itemCount == 0) return
        if (animate) {
            binding.recyclerView.smoothScrollToPosition(0)
        } else {
            binding.recyclerView.scrollToPosition(0)
        }
    }

    /**
     * Sets the selected server
     * Updates UI and restarts service if needed
     * @param guid The server unique identifier to select
     */
    private fun setSelectServer(guid: String) {
        val selected = MmkvManager.getSelectServer()
        if (guid != selected) {
            MmkvManager.setSelectServer(guid)
            mainViewModel.onSelectedServerChanged(guid)
            val fromPosition = mainViewModel.getPosition(selected.orEmpty())
            val toPosition = mainViewModel.getPosition(guid)
            adapter.setSelectServer(fromPosition, toPosition)
            mainViewModel.prewarmSelectedConfig(guid)

            if (mainViewModel.isRunning.value == true) {
                ownerActivity.restartV2Ray()
            }
        }
    }

    private inner class ActivityAdapterListener : MainAdapterListener {
        override fun onEdit(guid: String, position: Int) {
        }

        override fun onShare(url: String) {
        }

        override fun onRefreshData() {
        }

        override fun onRemove(guid: String, position: Int) {
            removeServer(guid, position)
        }

        override fun onEdit(guid: String, position: Int, profile: ProfileItem) {
            editServer(guid, profile)
        }

        override fun onSelectServer(guid: String) {
            setSelectServer(guid)
        }

        override fun onTestDelay(guid: String, position: Int) {
            mainViewModel.testServerTcping(guid)
        }

        override fun onShare(guid: String, profile: ProfileItem, position: Int, more: Boolean) {
            showServerActions(guid, profile, position)
        }
    }
}
