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
import kotlinx.coroutines.launch

class GroupServerFragment : BaseFragment<FragmentGroupServerBinding>() {
    private val ownerActivity: MainActivity
        get() = requireActivity() as MainActivity
    private val mainViewModel: MainViewModel by activityViewModels()
    private lateinit var adapter: MainRecyclerAdapter
    private val subId: String by lazy { arguments?.getString(ARG_SUB_ID).orEmpty() }

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

        mainViewModel.updateListAction.observe(viewLifecycleOwner) { index ->
            if (mainViewModel.subscriptionId != subId) {
                return@observe
            }
            // Log.d(TAG, "GroupServerFragment updateListAction subId=$subId")
            adapter.setData(mainViewModel.serversCache, index)
            updateEmptyState()
        }

        updateEmptyState()
        // Log.d(TAG, "GroupServerFragment onViewCreated: subId=$subId")
    }

    override fun onResume() {
        super.onResume()
        mainViewModel.subscriptionIdChanged(subId)
        ownerActivity.onServerListScrolled(0, binding.recyclerView.canScrollVertically(-1))
    }

    private fun setupRecyclerView() {
        binding.recyclerView.setHasFixedSize(true)
        binding.recyclerView.layoutManager = GridLayoutManager(requireContext(), getSpanCount())
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
        val isEmpty = mainViewModel.serversCache.isEmpty()
        binding.recyclerView.isVisible = !isEmpty
        binding.layoutEmptyState.isVisible = isEmpty
        binding.tvEmptyTitle.setText(
            if (mainViewModel.keywordFilter.isBlank()) R.string.empty_server_title else R.string.empty_search_title
        )
        binding.tvEmptySubtitle.setText(
            if (mainViewModel.keywordFilter.isBlank()) R.string.empty_server_subtitle else R.string.empty_search_subtitle
        )
    }

    private data class ServerAction(
        val label: String,
        val iconRes: Int,
        val destructive: Boolean = false,
        val handler: () -> Unit
    )

    private fun showServerActions(guid: String, profile: ProfileItem, position: Int) {
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
            itemBinding.root.setOnClickListener {
                bottomSheetDialog.dismiss()
                runCatching(action.handler).onFailure { e ->
                    Log.e(AppConfig.TAG, "Error handling server action", e)
                }
            }
            sheetBinding.layoutActions.addView(itemBinding.root)
        }

        bottomSheetDialog.setContentView(sheetBinding.root)
        bottomSheetDialog.show()
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

    /**
     * Sets the selected server
     * Updates UI and restarts service if needed
     * @param guid The server unique identifier to select
     */
    private fun setSelectServer(guid: String) {
        val selected = MmkvManager.getSelectServer()
        if (guid != selected) {
            MmkvManager.setSelectServer(guid)
            val fromPosition = mainViewModel.getPosition(selected.orEmpty())
            val toPosition = mainViewModel.getPosition(guid)
            adapter.setSelectServer(fromPosition, toPosition)
            ownerActivity.refreshConnectionCard()

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

        override fun onShare(guid: String, profile: ProfileItem, position: Int, more: Boolean) {
            showServerActions(guid, profile, position)
        }
    }
}
