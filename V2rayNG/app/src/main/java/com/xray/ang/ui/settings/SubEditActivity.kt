package com.xray.ang.ui

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.lifecycle.lifecycleScope
import com.xray.ang.R
import com.xray.ang.databinding.ActivitySubEditBinding
import com.xray.ang.dto.SubscriptionItem
import com.xray.ang.extension.toast
import com.xray.ang.extension.toastSuccess
import com.xray.ang.handler.MmkvManager
import com.xray.ang.handler.SettingsChangeManager
import com.xray.ang.ui.common.runWithRemovalConfirmation
import com.xray.ang.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SubEditActivity : BaseActivity() {
    private val binding by lazy { ActivitySubEditBinding.inflate(layoutInflater) }

    private var deleteMenuItem: MenuItem? = null

    private val editSubId by lazy { intent.getStringExtra("subId").orEmpty() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentViewWithToolbar(binding.root, showHomeAsUp = true, title = getString(R.string.title_subscription_details))

        SettingsChangeManager.makeSetupGroupTab()
        renderSubscription(MmkvManager.decodeSubscription(editSubId))
    }

    private fun renderSubscription(subItem: SubscriptionItem?) {
        if (subItem == null) {
            resetForm()
            return
        }
        binding.apply {
            etRemarks.text = Utils.getEditable(subItem.remarks)
            etUrl.text = Utils.getEditable(subItem.url)
            etUserAgent.text = Utils.getEditable(subItem.userAgent)
            etFilter.text = Utils.getEditable(subItem.filter)
            chkEnable.isChecked = subItem.enabled
            autoUpdateCheck.isChecked = subItem.autoUpdate
            allowInsecureUrl.isChecked = subItem.allowInsecureUrl
            etPreProfile.text = Utils.getEditable(subItem.prevProfile)
            etNextProfile.text = Utils.getEditable(subItem.nextProfile)
        }
    }

    private fun resetForm() {
        binding.apply {
            etRemarks.text = null
            etUrl.text = null
            etUserAgent.text = null
            etFilter.text = null
            chkEnable.isChecked = true
            autoUpdateCheck.isChecked = false
            allowInsecureUrl.isChecked = false
            etPreProfile.text = null
            etNextProfile.text = null
        }
    }

    private fun buildSubscriptionItem(): SubscriptionItem {
        val existing = MmkvManager.decodeSubscription(editSubId) ?: SubscriptionItem()
        return existing.apply {
            remarks = binding.etRemarks.text?.toString().orEmpty().trim()
            url = binding.etUrl.text?.toString().orEmpty().trim()
            userAgent = binding.etUserAgent.text?.toString().orEmpty().trim()
            filter = binding.etFilter.text?.toString().orEmpty().trim()
            enabled = binding.chkEnable.isChecked
            autoUpdate = binding.autoUpdateCheck.isChecked
            prevProfile = binding.etPreProfile.text?.toString().orEmpty().trim()
            nextProfile = binding.etNextProfile.text?.toString().orEmpty().trim()
            allowInsecureUrl = binding.allowInsecureUrl.isChecked
        }
    }

    private fun validateSubscription(subItem: SubscriptionItem): Boolean {
        if (subItem.remarks.isBlank()) {
            toast(R.string.sub_setting_remarks)
            return false
        }
        if (subItem.url.isNotEmpty()) {
            if (!Utils.isValidUrl(subItem.url)) {
                toast(R.string.toast_invalid_url)
                return false
            }

            if (!Utils.isValidSubUrl(subItem.url)) {
                toast(R.string.toast_insecure_url_protocol)
                if (!subItem.allowInsecureUrl) {
                    return false
                }
            }
        }
        return true
    }

    private fun saveServer(): Boolean {
        val subItem = buildSubscriptionItem()
        if (!validateSubscription(subItem)) {
            return false
        }
        MmkvManager.encodeSubscription(editSubId, subItem)
        toastSuccess(R.string.toast_success)
        finish()
        return true
    }

    private fun deleteServer(): Boolean {
        if (editSubId.isBlank()) {
            return true
        }
        if (!MmkvManager.canRemoveSubscription(editSubId)) {
            toast(R.string.toast_action_not_allowed)
            return false
        }
        runWithRemovalConfirmation {
            lifecycleScope.launch(Dispatchers.IO) {
                val removed = MmkvManager.removeSubscription(editSubId)
                launch(Dispatchers.Main) {
                    if (removed) {
                        SettingsChangeManager.makeSetupGroupTab()
                        finish()
                    } else {
                        toast(R.string.toast_action_not_allowed)
                    }
                }
            }
        }
        return true
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.action_server, menu)
        deleteMenuItem = menu.findItem(R.id.del_config)
        deleteMenuItem?.isVisible = editSubId.isNotEmpty() && MmkvManager.canRemoveSubscription(editSubId)

        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.del_config -> {
            deleteServer()
            true
        }

        R.id.save_config -> {
            saveServer()
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

}
