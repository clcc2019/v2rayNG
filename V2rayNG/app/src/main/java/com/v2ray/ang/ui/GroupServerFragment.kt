package com.v2ray.ang.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
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
import com.v2ray.ang.databinding.ItemBottomSheetActionBinding
import com.v2ray.ang.databinding.ItemQrcodeBinding
import com.v2ray.ang.databinding.LayoutBottomSheetActionsBinding
import com.v2ray.ang.dto.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.helper.SimpleItemTouchHelperCallback
import com.v2ray.ang.viewmodel.MainViewModel
import com.google.android.material.bottomsheet.BottomSheetDialog
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

    private data class ServerAction(
        val label: String,
        val iconRes: Int,
        val destructive: Boolean = false,
        val handler: () -> Unit
    )

    private fun showServerActions(guid: String, profile: ProfileItem, position: Int) {
        focusServerItem(position)
        val sheetBinding = LayoutBottomSheetActionsBinding.inflate(LayoutInflater.from(ownerActivity))
        val bottomSheetDialog = BottomSheetDialog(ownerActivity)
        val description = profile.description.orEmpty().ifBlank { profile.server.orEmpty() }
        sheetBinding.tvTitle.text = profile.remarks
        sheetBinding.tvSubtitle.text = description
        sheetBinding.tvSubtitle.isVisible = description.isNotBlank()

        buildServerActions(guid, profile, position).forEach { action ->
            val itemBinding = ItemBottomSheetActionBinding.inflate(LayoutInflater.from(ownerActivity), sheetBinding.layoutActions, false)
            itemBinding.ivIcon.setImageResource(action.iconRes)
            val iconColor = ContextCompat.getColor(
                ownerActivity,
                if (action.destructive) R.color.md_theme_error else R.color.md_theme_onSurfaceVariant
            )
            itemBinding.ivIcon.imageTintList = ColorStateList.valueOf(iconColor)
            itemBinding.tvLabel.text = action.label
            itemBinding.tvLabel.setTextColor(
                ContextCompat.getColor(
                    ownerActivity,
                    if (action.destructive) R.color.md_theme_error else R.color.md_theme_onSurface
                )
            )
            UiMotion.attachPressFeedback(itemBinding.root)
            itemBinding.root.setOnClickListener {
                bottomSheetDialog.dismiss()
                runCatching(action.handler).onFailure { e ->
                    Log.e(AppConfig.TAG, "Error handling server action", e)
                }
            }
            sheetBinding.layoutActions.addView(itemBinding.root)
        }

        bottomSheetDialog.setContentView(sheetBinding.root)
        bottomSheetDialog.setOnShowListener {
            sheetBinding.root.alpha = 0f
            sheetBinding.root.translationY = sheetBinding.root.resources.displayMetrics.density * 6f
            sheetBinding.root.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(160L)
                .setInterpolator(androidx.interpolator.view.animation.FastOutSlowInInterpolator())
                .start()
            UiMotion.animateStaggeredChildren(sheetBinding.layoutActions, startDelay = 40L)
        }
        bottomSheetDialog.show()
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
            holder?.itemMainBinding?.itemBg?.let { UiMotion.animatePulse(it, pulseScale = 1.02f, duration = 110L) }
        }, 140L)
    }

    private fun buildServerActions(guid: String, profile: ProfileItem, position: Int): List<ServerAction> {
        val isCustom = profile.configType == EConfigType.CUSTOM || profile.configType == EConfigType.POLICYGROUP
        val actions = mutableListOf<ServerAction>()

        if (!isCustom) {
            actions += ServerAction(
                label = getString(R.string.action_qrcode),
                iconRes = R.drawable.ic_qu_scan_24dp,
                handler = { showQRCode(guid) }
            )
            actions += ServerAction(
                label = getString(R.string.action_export_clipboard),
                iconRes = R.drawable.ic_copy,
                handler = { share2Clipboard(guid) }
            )
        }

        actions += ServerAction(
            label = getString(R.string.action_export_full_clipboard),
            iconRes = R.drawable.ic_description_24dp,
            handler = { shareFullContent(guid) }
        )
        actions += ServerAction(
            label = getString(R.string.menu_item_edit_config),
            iconRes = R.drawable.ic_edit_24dp,
            handler = { editServer(guid, profile) }
        )
        actions += ServerAction(
            label = getString(R.string.menu_item_del_config),
            iconRes = R.drawable.ic_delete_24dp,
            destructive = true,
            handler = { removeServer(guid, position) }
        )

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
        AlertDialog.Builder(ownerActivity).setView(ivBinding.root).show()
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

        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_CONFIRM_REMOVE)) {
            AlertDialog.Builder(ownerActivity).setMessage(R.string.del_config_comfirm)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    removeServerSub(guid, position)
                }
                .setNegativeButton(android.R.string.cancel) { _, _ ->
                    //do noting
                }
                .show()
        } else {
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
