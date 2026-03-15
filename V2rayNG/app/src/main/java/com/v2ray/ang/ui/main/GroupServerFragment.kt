package com.v2ray.ang.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
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
import com.v2ray.ang.ui.common.hapticClick
import com.v2ray.ang.ui.common.runWithRemovalConfirmation
import com.v2ray.ang.ui.common.showActionBottomSheet
import com.v2ray.ang.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GroupServerFragment : BaseFragment<FragmentGroupServerBinding>() {
    private enum class EmptyStateMode {
        DEFAULT,
        SEARCH_IDLE,
        SEARCH_RESULT
    }

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
    private var lastEmptyStateMode: EmptyStateMode? = null

    companion object {
        private const val ARG_SUB_ID = "subscriptionId"
        fun newInstance(subId: String) = GroupServerFragment().apply {
            arguments = Bundle().apply { putString(ARG_SUB_ID, subId) }
        }
    }

    override fun inflateBinding(inflater: LayoutInflater, container: ViewGroup?) =
        FragmentGroupServerBinding.inflate(inflater, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = MainRecyclerAdapter(
            mainViewModel = mainViewModel,
            adapterListener = ActivityAdapterListener(),
            onContentCommitted = ::requestConnectionDockBackdropRefresh
        )
        setupRecyclerView()
        setupEmptyStateActions()
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
        ownerActivity.onServerListContextChanged(
            isEmpty = binding.layoutEmptyState.isVisible,
            canScrollUp = binding.recyclerView.canScrollVertically(-1),
            resetContext = true
        )
        ownerActivity.onServerListScrollStateChanged(binding.recyclerView.scrollState, binding.recyclerView.canScrollVertically(-1))
        ownerActivity.onServerListScrolled(0, binding.recyclerView.canScrollVertically(-1))
    }

    private fun setupRecyclerView() {
        binding.recyclerView.setHasFixedSize(true)
        binding.recyclerView.setItemViewCacheSize(10)
        binding.recyclerView.layoutManager = GridLayoutManager(requireContext(), getSpanCount())
        binding.recyclerView.itemAnimator = null
        binding.recyclerView.overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        optimizeRecyclerViewForHighRefresh(binding.recyclerView)
        binding.recyclerView.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_MOVE -> view.parent?.requestDisallowInterceptTouchEvent(true)
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> view.parent?.requestDisallowInterceptTouchEvent(false)
            }
            false
        }
        binding.recyclerView.adapter = adapter
        binding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (mainViewModel.subscriptionId != subId) {
                    return
                }
                ownerActivity.onServerListScrollStateChanged(newState, recyclerView.canScrollVertically(-1))
            }

            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (mainViewModel.subscriptionId != subId) {
                    return
                }
                ownerActivity.onServerListScrolled(dy, recyclerView.canScrollVertically(-1))
            }
        })
        ItemTouchHelper(SimpleItemTouchHelperCallback(adapter, allowSwipe = false, allowLongPressDrag = false))
            .attachToRecyclerView(binding.recyclerView)
    }

    private fun requestConnectionDockBackdropRefresh() {
        if (!isAdded || mainViewModel.subscriptionId != subId) {
            return
        }
        ownerActivity.requestConnectionDockBackdropRefreshOnNextDraw()
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
        if (mainViewModel.subscriptionId == subId) {
            ownerActivity.onServerListContextChanged(
                isEmpty = shouldShowEmptyState,
                canScrollUp = binding.recyclerView.canScrollVertically(-1),
                resetContext = false
            )
        }
        val emptyStateMode = when {
            mainViewModel.keywordFilter.isNotBlank() -> EmptyStateMode.SEARCH_RESULT
            ownerActivity.isSearchUiActive() -> EmptyStateMode.SEARCH_IDLE
            else -> EmptyStateMode.DEFAULT
        }
        val emptyStateModeChanged = lastEmptyStateMode != emptyStateMode
        if (lastEmptyStateMode != emptyStateMode) {
            when (emptyStateMode) {
                EmptyStateMode.DEFAULT -> {
                    binding.ivEmptyIcon.setImageResource(R.drawable.ic_subscriptions_24dp)
                    binding.tvEmptyTitle.setText(R.string.empty_server_title)
                    binding.tvEmptySubtitle.setText(R.string.empty_server_subtitle)
                    binding.layoutEmptyActions.isVisible = true
                }
                EmptyStateMode.SEARCH_IDLE -> {
                    binding.ivEmptyIcon.setImageResource(R.drawable.ic_search_24dp)
                    binding.tvEmptyTitle.setText(R.string.empty_search_idle_title)
                    binding.tvEmptySubtitle.setText(R.string.empty_search_idle_subtitle)
                    binding.layoutEmptyActions.isVisible = false
                }
                EmptyStateMode.SEARCH_RESULT -> {
                    binding.ivEmptyIcon.setImageResource(R.drawable.ic_search_24dp)
                    binding.tvEmptyTitle.setText(R.string.empty_search_title)
                    binding.tvEmptySubtitle.setText(R.string.empty_search_subtitle)
                    binding.layoutEmptyActions.isVisible = false
                }
            }
            lastEmptyStateMode = emptyStateMode
        }
        if (shouldShowEmptyState && isResumed) {
            when {
                shouldAnimateEmptyState -> binding.layoutEmptyState.post {
                    animateEmptyStateContent()
                }

                emptyStateModeChanged -> {
                    UiMotion.animatePulse(binding.ivEmptyIcon, pulseScale = 1.03f, duration = MotionTokens.PULSE_QUICK)
                    UiMotion.animateFocusShift(
                        primary = binding.tvEmptyTitle,
                        secondary = binding.tvEmptySubtitle,
                        translationOffsetDp = 6f,
                        duration = MotionTokens.SHORT_ANIMATION_DURATION
                    )
                    if (binding.layoutEmptyActions.isVisible) {
                        binding.layoutEmptyActions.post {
                            UiMotion.animateStaggeredChildren(
                                container = binding.layoutEmptyActions,
                                translationOffsetDp = 8f,
                                stepDelay = 22L
                            )
                        }
                    }
                }
            }
        }
    }

    fun onSearchUiChanged() {
        if (!isAdded || mainViewModel.subscriptionId != subId) return
        updateEmptyState()
    }

    private fun setupEmptyStateActions() {
        attachEmptyActionMotion(binding.actionAddConnection)
        attachEmptyActionMotion(binding.actionScanQrcode)
        attachEmptyActionMotion(binding.actionAddSubscription)
        binding.actionAddConnection.setOnClickListener {
            it.hapticClick()
            showManualAddSheet()
        }
        binding.actionScanQrcode.setOnClickListener {
            it.hapticClick()
            importQRcode()
        }
        binding.actionAddSubscription.setOnClickListener {
            it.hapticClick()
            shouldRefreshOnResume = true
            ownerActivity.startActivity(Intent(ownerActivity, SubEditActivity::class.java))
        }
    }

    private fun attachEmptyActionMotion(actionView: View) {
        UiMotion.attachPressFeedbackComposite(
            source = actionView,
            pressedScale = 0.988f,
            pressedAlpha = 0.94f
        )
    }

    private fun animateEmptyStateContent() {
        val content = binding.layoutEmptyState.getChildAt(0) as? ViewGroup ?: return
        UiMotion.animateStaggeredChildren(
            container = content,
            translationOffsetDp = 12f,
            stepDelay = 36L
        )
        if (binding.layoutEmptyActions.isVisible) {
            binding.layoutEmptyActions.post {
                UiMotion.animateStaggeredChildren(
                    container = binding.layoutEmptyActions,
                    translationOffsetDp = 10f,
                    stepDelay = 24L,
                    startDelay = MotionTokens.STAGGER_START_DELAY
                )
            }
        }
    }

    private fun showManualAddSheet() {
        ownerActivity.showActionBottomSheet(
            title = getString(R.string.empty_action_add_connection),
            subtitle = getString(R.string.empty_action_add_connection_hint),
            actions = listOf(
                actionBottomSheetItem(R.string.menu_item_import_config_policy_group, R.drawable.ic_subscriptions_24dp) {
                    importManually(EConfigType.POLICYGROUP)
                },
                actionBottomSheetItem(R.string.menu_item_import_config_manually_vmess, R.drawable.ic_add_24dp) {
                    importManually(EConfigType.VMESS)
                },
                actionBottomSheetItem(R.string.menu_item_import_config_manually_vless, R.drawable.ic_add_24dp) {
                    importManually(EConfigType.VLESS)
                },
                actionBottomSheetItem(R.string.menu_item_import_config_manually_ss, R.drawable.ic_add_24dp) {
                    importManually(EConfigType.SHADOWSOCKS)
                },
                actionBottomSheetItem(R.string.menu_item_import_config_manually_socks, R.drawable.ic_add_24dp) {
                    importManually(EConfigType.SOCKS)
                },
                actionBottomSheetItem(R.string.menu_item_import_config_manually_http, R.drawable.ic_add_24dp) {
                    importManually(EConfigType.HTTP)
                },
                actionBottomSheetItem(R.string.menu_item_import_config_manually_trojan, R.drawable.ic_add_24dp) {
                    importManually(EConfigType.TROJAN)
                },
                actionBottomSheetItem(R.string.menu_item_import_config_manually_wireguard, R.drawable.ic_add_24dp) {
                    importManually(EConfigType.WIREGUARD)
                },
                actionBottomSheetItem(R.string.menu_item_import_config_manually_hysteria2, R.drawable.ic_add_24dp) {
                    importManually(EConfigType.HYSTERIA2)
                }
            )
        )
    }

    private fun importManually(type: EConfigType) {
        shouldRefreshOnResume = true
        if (type == EConfigType.POLICYGROUP) {
            ownerActivity.startActivity(
                Intent(ownerActivity, ServerGroupActivity::class.java)
                    .putExtra("subscriptionId", mainViewModel.subscriptionId)
            )
        } else {
            ownerActivity.startActivity(
                Intent(ownerActivity, ServerActivity::class.java)
                    .putExtra("createConfigType", type.value)
                    .putExtra("subscriptionId", mainViewModel.subscriptionId)
            )
        }
    }

    private fun importQRcode(): Boolean {
        ownerActivity.launchQRCodeScanner { scanResult ->
            if (scanResult != null) {
                importBatchConfig(scanResult)
            }
        }
        return true
    }

    private fun importBatchConfig(server: String?) {
        ownerActivity.showLoadingIndicator()
        ownerActivity.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val (count, countSub) = AngConfigManager.importBatchConfig(server, mainViewModel.subscriptionId, true)
                withContext(Dispatchers.Main) {
                    when {
                        count > 0 -> {
                            ownerActivity.toast(getString(R.string.title_import_config_count, count))
                            mainViewModel.reloadServerList()
                        }
                        countSub > 0 -> {
                            ownerActivity.toastSuccess(R.string.toast_success)
                            shouldRefreshOnResume = true
                            mainViewModel.loadSubscriptions(ownerActivity.applicationContext)
                        }
                        else -> ownerActivity.toastError(R.string.toast_failure)
                    }
                    ownerActivity.hideLoadingIndicator()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    ownerActivity.toastError(R.string.toast_failure)
                    ownerActivity.hideLoadingIndicator()
                }
            }
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
        ownerActivity.showActionBottomSheet(
            title = getString(R.string.action_qrcode),
            contentView = ivBinding.root
        )
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
                ownerActivity.restartV2Ray(guid)
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
