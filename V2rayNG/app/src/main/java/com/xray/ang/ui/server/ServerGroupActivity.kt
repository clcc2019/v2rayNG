package com.xray.ang.ui

import android.os.Bundle
import android.text.TextUtils
import android.view.Menu
import android.view.MenuItem
import android.widget.ArrayAdapter
import com.xray.ang.R
import com.xray.ang.databinding.ActivityServerGroupBinding
import com.xray.ang.dto.ProfileItem
import com.xray.ang.enums.EConfigType
import com.xray.ang.extension.isNotNullEmpty
import com.xray.ang.extension.toast
import com.xray.ang.extension.toastSuccess
import com.xray.ang.handler.MmkvManager
import com.xray.ang.ui.common.runWithRemovalConfirmation
import com.xray.ang.util.Utils

class ServerGroupActivity : BaseActivity() {
    private val binding by lazy { ActivityServerGroupBinding.inflate(layoutInflater) }

    private val editGuid by lazy { intent.getStringExtra("guid").orEmpty() }
    private val isRunning by lazy {
        intent.getBooleanExtra("isRunning", false)
                && editGuid.isNotEmpty()
                && editGuid == MmkvManager.getSelectServer()
    }
    private val subscriptionId by lazy {
        intent.getStringExtra("subscriptionId")
    }
    private val subIds = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentViewWithToolbar(binding.root, showHomeAsUp = true, title = EConfigType.POLICYGROUP.toString())

        val config = MmkvManager.decodeServerConfig(editGuid)
        populateSubscriptionSpinner()

        if (config != null) {
            bindingServer(config)
        } else {
            clearServer()
        }
    }

    private fun bindingServer(config: ProfileItem) {
        binding.etRemarks.text = Utils.getEditable(config.remarks)
        binding.etPolicyGroupFilter.text = Utils.getEditable(config.policyGroupFilter)

        val type = config.policyGroupType?.toIntOrNull() ?: 0
        binding.spPolicyGroupType.setSelection(type)

        val pos = subIds.indexOf(config.policyGroupSubscriptionId ?: "").let { if (it >= 0) it else 0 }
        binding.spPolicyGroupSubId.setSelection(pos)
    }

    private fun clearServer() {
        binding.etRemarks.text = null
        binding.etPolicyGroupFilter.text = null

        if (subscriptionId.isNotNullEmpty()) {
            val pos = subIds.indexOf(subscriptionId).let { if (it >= 0) it else 0 }
            binding.spPolicyGroupSubId.setSelection(pos)
        }
    }

    /**
     * save server config
     */
    private fun saveServer(): Boolean {
        if (TextUtils.isEmpty(binding.etRemarks.text.toString())) {
            toast(R.string.server_lab_remarks)
            return false
        }

        val config = MmkvManager.decodeServerConfig(editGuid) ?: ProfileItem.create(EConfigType.POLICYGROUP)
        config.remarks = binding.etRemarks.text.toString().trim()
        config.policyGroupFilter = binding.etPolicyGroupFilter.text.toString().trim()

        config.policyGroupType = binding.spPolicyGroupType.selectedItemPosition.toString()

        val selPos = binding.spPolicyGroupSubId.selectedItemPosition
        config.policyGroupSubscriptionId = if (selPos >= 0 && selPos < subIds.size) subIds[selPos] else null

        if (config.subscriptionId.isEmpty() && !subscriptionId.isNullOrEmpty()) {
            config.subscriptionId = subscriptionId.orEmpty()
        }

        config.description =  "${binding.spPolicyGroupType.selectedItem} - ${binding.spPolicyGroupSubId.selectedItem} - ${config.policyGroupFilter}"

        MmkvManager.encodeServerConfig(editGuid, config)
        toastSuccess(R.string.toast_success)
        finish()
        return true
    }

    /**
     * save server config
     */
    private fun deleteServer(): Boolean {
        if (editGuid.isNotEmpty()) {
            runWithRemovalConfirmation {
                MmkvManager.removeServer(editGuid)
                finish()
            }
        }
        return true
    }

    private fun populateSubscriptionSpinner() {
        val subs = MmkvManager.decodeSubscriptions()
        val displayList = mutableListOf(getString(R.string.filter_config_all)) //none
        subIds.clear()
        subIds.add("") // index 0 => All
        subs.forEach { sub ->
            val name = when {
                sub.subscription.remarks.isNotBlank() -> sub.subscription.remarks
                else -> sub.guid
            }
            displayList.add(name)
            subIds.add(sub.guid)
        }
        val subAdapter = ArrayAdapter(this, R.layout.item_spinner_selected, displayList)
        subAdapter.setDropDownViewResource(R.layout.item_spinner_dropdown)
        binding.spPolicyGroupSubId.adapter = subAdapter
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        ServerActionMenuHelper.configure(menuInflater, menu, editGuid, isRunning)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem) =
        ServerActionMenuHelper.handleMenuItem(item, ::deleteServer, ::saveServer)
            ?: super.onOptionsItemSelected(item)
}
