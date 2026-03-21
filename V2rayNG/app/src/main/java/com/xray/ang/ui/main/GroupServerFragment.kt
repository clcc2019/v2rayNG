package com.xray.ang.ui

import android.animation.ValueAnimator
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import com.xray.ang.AppConfig
import com.xray.ang.R
import com.xray.ang.contracts.MainAdapterListener
import com.xray.ang.dto.ProfileItem
import com.xray.ang.enums.EConfigType
import com.xray.ang.extension.toast
import com.xray.ang.extension.toastError
import com.xray.ang.extension.toastSuccess
import com.xray.ang.handler.AngConfigManager
import com.xray.ang.handler.MmkvManager
import com.xray.ang.helper.SimpleItemTouchHelperCallback
import com.xray.ang.ui.common.ActionBottomSheetItem
import com.xray.ang.ui.common.actionBottomSheetItem
import com.xray.ang.ui.common.hapticClick
import com.xray.ang.ui.common.runWithRemovalConfirmation
import com.xray.ang.ui.common.showActionBottomSheet
import com.xray.ang.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GroupServerFragment : BaseFragment<GroupServerFragment.GroupServerBinding>() {
    private enum class EmptyStateMode {
        DEFAULT,
        SEARCH_IDLE,
        SEARCH_RESULT
    }

    private data class EmptyStateContent(
        val iconRes: Int,
        val titleRes: Int,
        val subtitleRes: Int,
        val showActions: Boolean
    )

    private data class EmptyActionSpec(
        @DrawableRes val iconRes: Int,
        @StringRes val titleRes: Int,
        @StringRes val subtitleRes: Int,
        val onClick: () -> Unit
    )

    class GroupServerBinding private constructor(
        private val rootView: View,
        val recyclerView: RecyclerView,
        val layoutEmptyState: ViewGroup,
        val ivEmptyIcon: ImageView,
        val tvEmptyTitle: android.widget.TextView,
        val tvEmptySubtitle: android.widget.TextView,
        val layoutEmptyActions: ViewGroup
    ) : ViewBinding {
        override fun getRoot(): View = rootView

        companion object {
            fun inflate(inflater: LayoutInflater, container: ViewGroup?): GroupServerBinding {
                val root = inflater.inflate(R.layout.fragment_group_server, container, false)
                return bind(root)
            }

            private fun bind(root: View): GroupServerBinding {
                return GroupServerBinding(
                    rootView = root,
                    recyclerView = requireView(root, R.id.recycler_view),
                    layoutEmptyState = requireView(root, R.id.layout_empty_state),
                    ivEmptyIcon = requireView(root, R.id.iv_empty_icon),
                    tvEmptyTitle = requireView(root, R.id.tv_empty_title),
                    tvEmptySubtitle = requireView(root, R.id.tv_empty_subtitle),
                    layoutEmptyActions = requireView(root, R.id.layout_empty_actions)
                )
            }

            private fun <T : View> requireView(root: View, id: Int): T {
                return root.findViewById<T>(id)
                    ?: throw NullPointerException("Missing required view id=$id in fragment_group_server")
            }
        }
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
    private val switchInterpolator = FastOutSlowInInterpolator()

    companion object {
        private const val ARG_SUB_ID = "subscriptionId"
        fun newInstance(subId: String) = GroupServerFragment().apply {
            arguments = Bundle().apply { putString(ARG_SUB_ID, subId) }
        }
    }

    override fun inflateBinding(inflater: LayoutInflater, container: ViewGroup?) =
        GroupServerBinding.inflate(inflater, container)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = MainRecyclerAdapter(
            mainViewModel = mainViewModel,
            adapterListener = ActivityAdapterListener()
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
        ownerActivity.onServerListScrolled(binding.recyclerView.canScrollVertically(-1))
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

            override fun onScrolled(recyclerView: RecyclerView, unusedDx: Int, unusedDy: Int) {
                if (mainViewModel.subscriptionId != subId) {
                    return
                }
                ownerActivity.onServerListScrolled(recyclerView.canScrollVertically(-1))
            }
        })
        ItemTouchHelper(SimpleItemTouchHelperCallback(adapter, allowSwipe = false, allowLongPressDrag = false))
            .attachToRecyclerView(binding.recyclerView)
    }

    private fun getSpanCount(): Int {
        return if (MmkvManager.decodeSettingsBool(AppConfig.PREF_DOUBLE_COLUMN_DISPLAY, false)) 2 else 1
    }

    private fun updateEmptyState() {
        val itemCount = resolveItemCountForEmptyState() ?: return
        val shouldShowEmptyState = itemCount == 0
        val shouldAnimateEmptyState =
            lastEmptyStateVisible != null && lastEmptyStateVisible != shouldShowEmptyState && isResumed
        if (!shouldShowEmptyState && lastEmptyStateVisible == true) {
            pendingListFadeIn = true
        }
        updateRecyclerVisibility(shouldShowEmptyState)
        updateEmptyStateVisibility(shouldShowEmptyState, shouldAnimateEmptyState)
        updateOwnerListContext(shouldShowEmptyState)
        val emptyStateMode = resolveEmptyStateMode()
        val emptyStateModeChanged = renderEmptyStateContent(emptyStateMode)
        animateEmptyStateIfNeeded(shouldShowEmptyState, emptyStateModeChanged, shouldAnimateEmptyState)
    }

    private fun resolveItemCountForEmptyState(): Int? {
        if (mainViewModel.subscriptionId != subId && mainViewModel.keywordFilter.isBlank() && lastKnownItemCount == null) {
            return null
        }
        val currentCount = if (mainViewModel.subscriptionId == subId) {
            mainViewModel.serversCache.size
        } else {
            null
        }
        val itemCount = currentCount ?: lastKnownItemCount
        if (itemCount == null && mainViewModel.keywordFilter.isBlank()) {
            fetchStoredItemCount()
            return null
        }
        if (currentCount != null) {
            lastKnownItemCount = currentCount
        }
        return itemCount
    }

    private fun updateRecyclerVisibility(shouldShowEmptyState: Boolean) {
        when {
            shouldShowEmptyState -> {
                if (binding.recyclerView.isVisible) {
                    binding.recyclerView.isVisible = false
                }
            }

            pendingListFadeIn -> {
                binding.recyclerView.alpha = 0f
                binding.recyclerView.translationY = resources.displayMetrics.density * 6f
                UiMotion.animateVisibility(binding.recyclerView, true, translationOffsetDp = 6f, duration = 140L)
                pendingListFadeIn = false
            }

            !binding.recyclerView.isVisible || binding.recyclerView.alpha != 1f || binding.recyclerView.translationY != 0f -> {
                binding.recyclerView.isVisible = true
                binding.recyclerView.alpha = 1f
                binding.recyclerView.translationY = 0f
            }
        }
    }

    private fun updateEmptyStateVisibility(shouldShowEmptyState: Boolean, shouldAnimateEmptyState: Boolean) {
        if (shouldAnimateEmptyState) {
            UiMotion.animateVisibility(binding.layoutEmptyState, shouldShowEmptyState, translationOffsetDp = 10f, duration = 140L)
        } else {
            UiMotion.setVisibility(binding.layoutEmptyState, shouldShowEmptyState)
        }
        lastEmptyStateVisible = shouldShowEmptyState
    }

    private fun updateOwnerListContext(shouldShowEmptyState: Boolean) {
        if (mainViewModel.subscriptionId != subId) {
            return
        }
        ownerActivity.onServerListContextChanged(
            isEmpty = shouldShowEmptyState,
            canScrollUp = binding.recyclerView.canScrollVertically(-1),
            resetContext = false
        )
    }

    private fun resolveEmptyStateMode(): EmptyStateMode {
        return when {
            mainViewModel.keywordFilter.isNotBlank() -> EmptyStateMode.SEARCH_RESULT
            ownerActivity.isSearchUiActive() -> EmptyStateMode.SEARCH_IDLE
            else -> EmptyStateMode.DEFAULT
        }
    }

    private fun renderEmptyStateContent(mode: EmptyStateMode): Boolean {
        val changed = lastEmptyStateMode != mode
        if (!changed) {
            return false
        }
        val content = when (mode) {
            EmptyStateMode.DEFAULT -> EmptyStateContent(
                iconRes = R.drawable.ic_subscriptions_24dp,
                titleRes = R.string.empty_server_title,
                subtitleRes = R.string.empty_server_subtitle,
                showActions = true
            )
            EmptyStateMode.SEARCH_IDLE -> EmptyStateContent(
                iconRes = R.drawable.ic_search_24dp,
                titleRes = R.string.empty_search_idle_title,
                subtitleRes = R.string.empty_search_idle_subtitle,
                showActions = false
            )
            EmptyStateMode.SEARCH_RESULT -> EmptyStateContent(
                iconRes = R.drawable.ic_search_24dp,
                titleRes = R.string.empty_search_title,
                subtitleRes = R.string.empty_search_subtitle,
                showActions = false
            )
        }
        binding.ivEmptyIcon.setImageResource(content.iconRes)
        binding.tvEmptyTitle.setText(content.titleRes)
        binding.tvEmptySubtitle.setText(content.subtitleRes)
        binding.layoutEmptyActions.isVisible = content.showActions
        lastEmptyStateMode = mode
        return true
    }

    private fun animateEmptyStateIfNeeded(
        shouldShowEmptyState: Boolean,
        emptyStateModeChanged: Boolean,
        shouldAnimateEmptyState: Boolean
    ) {
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

    fun animateGroupSwitch() {
        if (!isAdded || !isResumed) return
        val target = when {
            binding.layoutEmptyState.isVisible -> binding.layoutEmptyState
            binding.recyclerView.isVisible -> binding.recyclerView
            else -> binding.recyclerView
        }
        if (!ValueAnimator.areAnimatorsEnabled() || !target.isAttachedToWindow) {
            target.alpha = 1f
            target.translationY = 0f
            return
        }
        val offsetPx = resources.displayMetrics.density * 6f
        target.animate().cancel()
        target.alpha = 0.96f
        target.translationY = offsetPx
        target.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(MotionTokens.SHORT_ANIMATION_DURATION)
            .setInterpolator(switchInterpolator)
            .start()
    }

    private fun setupEmptyStateActions() {
        val actions = listOf(
            EmptyActionSpec(
                iconRes = R.drawable.ic_add_24dp,
                titleRes = R.string.empty_action_add_connection,
                subtitleRes = R.string.empty_action_add_connection_hint
            ) { showManualAddSheet() },
            EmptyActionSpec(
                iconRes = R.drawable.ic_qu_scan_24dp,
                titleRes = R.string.empty_action_scan_qrcode,
                subtitleRes = R.string.empty_action_scan_qrcode_hint
            ) { importQRcode() },
            EmptyActionSpec(
                iconRes = R.drawable.ic_cloud_download_24dp,
                titleRes = R.string.title_configuration_migrate_v2rayng,
                subtitleRes = R.string.backup_summary_migrate_v2rayng
            ) { importFromV2rayNg() },
            EmptyActionSpec(
                iconRes = R.drawable.ic_subscriptions_24dp,
                titleRes = R.string.empty_action_add_subscription,
                subtitleRes = R.string.empty_action_add_subscription_hint
            ) {
                shouldRefreshOnResume = true
                ownerActivity.startActivity(Intent(ownerActivity, SubEditActivity::class.java))
            }
        )
        val container = binding.layoutEmptyActions
        val count = minOf(container.childCount, actions.size)
        for (i in 0 until count) {
            val actionView = container.getChildAt(i)
            bindEmptyAction(actionView, actions[i])
        }
    }

    private fun attachEmptyActionMotion(actionView: View) {
        UiMotion.attachPressFeedbackComposite(
            source = actionView,
            pressedScale = 0.988f,
            pressedAlpha = 0.94f
        )
    }

    private fun bindEmptyAction(actionView: View, spec: EmptyActionSpec) {
        val iconView = actionView.findViewById<ImageView>(R.id.empty_action_icon)
        val titleView = actionView.findViewById<TextView>(R.id.empty_action_title)
        val subtitleView = actionView.findViewById<TextView>(R.id.empty_action_subtitle)
        iconView.setImageResource(spec.iconRes)
        titleView.setText(spec.titleRes)
        subtitleView.setText(spec.subtitleRes)
        actionView.contentDescription = getString(spec.titleRes)
        attachEmptyActionMotion(actionView)
        actionView.setOnClickListener {
            it.hapticClick()
            spec.onClick()
        }
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
            actions = HomeImportSupport.buildManualImportActionItems(::importManually)
        )
    }

    private fun importManually(type: EConfigType) {
        shouldRefreshOnResume = true
        ownerActivity.startActivity(HomeImportSupport.createManualImportIntent(ownerActivity, mainViewModel.subscriptionId, type))
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
                val result = HomeImportSupport.importBatchConfig(server, mainViewModel.subscriptionId)
                withContext(Dispatchers.Main) {
                    handleImportBatchResult(result)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    ownerActivity.toastError(R.string.toast_failure)
                }
            } finally {
                withContext(Dispatchers.Main) {
                    ownerActivity.hideLoadingIndicator()
                }
            }
        }
    }

    private fun importFromV2rayNg() {
        ownerActivity.startActivity(
            Intent(ownerActivity, BackupActivity::class.java)
                .putExtra(BackupActivity.EXTRA_AUTO_MIGRATE_V2RAYNG, true)
        )
    }

    private fun handleImportBatchResult(result: BatchImportResult) {
        when {
            result.configCount > 0 -> {
                ownerActivity.toast(getString(R.string.title_import_config_count, result.configCount))
                mainViewModel.reloadServerList()
            }

            result.subscriptionCount > 0 -> {
                ownerActivity.toastSuccess(R.string.toast_success)
                shouldRefreshOnResume = true
                mainViewModel.loadSubscriptions(ownerActivity.applicationContext)
            }

            else -> ownerActivity.toastError(R.string.toast_failure)
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
            val pulseTarget = holder?.itemView?.findViewById<View>(R.id.item_bg) ?: holder?.itemView
            pulseTarget?.let { UiMotion.animatePulse(it, pulseScale = 1.02f, duration = MotionTokens.PULSE_MEDIUM) }
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
        val qrCodeView = LayoutInflater.from(ownerActivity).inflate(R.layout.item_qrcode, null, false)
        val qrImage = qrCodeView.findViewById<ImageView>(R.id.iv_qcode)
        qrImage.setImageBitmap(AngConfigManager.share2QRCode(guid))
        qrImage.contentDescription = getString(R.string.action_qrcode)
        ownerActivity.showActionBottomSheet(
            title = getString(R.string.action_qrcode),
            contentView = qrCodeView
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
