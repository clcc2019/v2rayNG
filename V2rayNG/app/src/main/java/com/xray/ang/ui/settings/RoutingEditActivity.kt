package com.xray.ang.ui

import android.os.Bundle
import android.view.View
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.xray.ang.R
import com.xray.ang.databinding.ActivityRoutingEditBinding
import com.xray.ang.dto.RulesetItem
import com.xray.ang.handler.SettingsManager
import com.xray.ang.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class RoutingEditActivity : BaseActivity() {
    private val binding by lazy { ActivityRoutingEditBinding.inflate(layoutInflater) }
    private val position by lazy { intent.getIntExtra("position", -1) }
    private var advancedExpanded = false

    private val outboundTags: Array<out String> by lazy {
        resources.getStringArray(R.array.outbound_tag)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentViewWithToolbar(binding.root, showHomeAsUp = true, title = getString(R.string.routing_settings_rule_title))

        SettingsManager.getRoutingRuleset(position)?.let(::bindServer) ?: clearServer()

        binding.layoutAdvancedToggle.setOnClickListener {
            updateAdvancedSection(!advancedExpanded)
        }
        binding.layoutLockedRow.setOnClickListener {
            binding.chkLocked.toggle()
        }
    }

    private fun bindServer(rulesetItem: RulesetItem) {
        binding.etRemarks.text = Utils.getEditable(rulesetItem.remarks)
        binding.chkLocked.isChecked = rulesetItem.locked == true
        binding.etDomain.text = Utils.getEditable(formatDomainInput(rulesetItem.domain))
        binding.etIp.text = Utils.getEditable(formatMultiValueInput(rulesetItem.ip))
        binding.etPort.text = Utils.getEditable(rulesetItem.port)
        binding.etProtocol.text = Utils.getEditable(formatMultiValueInput(rulesetItem.protocol))
        binding.etNetwork.text = Utils.getEditable(rulesetItem.network)
        val outbound = Utils.arrayFind(outboundTags, rulesetItem.outboundTag)
        binding.spOutboundTag.setSelection(outbound)
        updateAdvancedSection(
            !rulesetItem.protocol.isNullOrEmpty() || !rulesetItem.network.isNullOrEmpty()
        )
    }

    private fun clearServer() {
        binding.etRemarks.text = null
        binding.chkLocked.isChecked = false
        binding.etDomain.text = null
        binding.etIp.text = null
        binding.etPort.text = null
        binding.etProtocol.text = null
        binding.etNetwork.text = null
        binding.spOutboundTag.setSelection(0)
        updateAdvancedSection(false)
    }

    private fun updateAdvancedSection(expanded: Boolean) {
        advancedExpanded = expanded
        binding.layoutAdvancedContent.visibility = if (expanded) View.VISIBLE else View.GONE
        binding.viewAdvancedDivider.visibility = if (expanded) View.VISIBLE else View.GONE
        binding.tvAdvancedSummary.text = getString(
            if (expanded) R.string.routing_advanced_summary_expanded else R.string.routing_advanced_summary_collapsed
        )
        binding.imgAdvancedToggle.animate().cancel()
        binding.imgAdvancedToggle.animate().rotation(if (expanded) 180f else 0f).setDuration(180L).start()
    }

    private fun saveRule() {
        val rulesetItem = SettingsManager.getRoutingRuleset(position) ?: RulesetItem()
        val remarks = binding.etRemarks.text.toString().trim()

        rulesetItem.apply {
            this.remarks = remarks
            locked = binding.chkLocked.isChecked
            domain = parseDomainInput(binding.etDomain.text.toString())
            ip = parseMultiValueInput(binding.etIp.text.toString())
            protocol = parseMultiValueInput(binding.etProtocol.text.toString())
            port = binding.etPort.text.toString().takeIf { it.isNotBlank() }
            network = binding.etNetwork.text.toString().takeIf { it.isNotBlank() }
            outboundTag = outboundTags[binding.spOutboundTag.selectedItemPosition]
        }

        if (rulesetItem.remarks.isNullOrEmpty()) {
            showToast(R.string.sub_setting_remarks)
            return
        }

        SettingsManager.saveRoutingRuleset(position, rulesetItem)
        showToast(R.string.toast_success)
        finish()
    }

    private fun formatDomainInput(values: List<String>?): String {
        return formatMultiValueInput(values?.map(::stripDisplayOnlyDomainPrefix))
    }

    private fun formatMultiValueInput(values: List<String>?): String {
        return values
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.joinToString(separator = "\n")
            .orEmpty()
    }

    private fun parseDomainInput(value: String): List<String>? {
        return parseMultiValueInput(value)
            ?.map(::stripDomainPrefixForStorage)
    }

    private fun parseMultiValueInput(value: String): List<String>? {
        val normalized = value
            .replace('，', ',')
            .replace(';', ',')
            .replace('\n', ',')
            .replace('\r', ',')
        return normalized.takeIf { it.isNotBlank() }
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
    }

    private fun stripDisplayOnlyDomainPrefix(value: String): String {
        return if (value.startsWith("domain:")) value.removePrefix("domain:") else value
    }

    private fun stripDomainPrefixForStorage(value: String): String {
        val trimmed = value.trim()
        return if (trimmed.startsWith("domain:")) trimmed.removePrefix("domain:") else trimmed
    }

    private fun deleteRule() {
        if (position >= 0) {
            showDeleteConfirmDialog {
                lifecycleScope.launch(Dispatchers.IO) {
                    SettingsManager.removeRoutingRuleset(position)
                    launch(Dispatchers.Main) {
                        finish()
                    }
                }
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.action_server, menu)
        val delConfig = menu.findItem(R.id.del_config)

        if (position < 0) {
            delConfig?.isVisible = false
        }

        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.del_config -> {
            deleteRule()
            true
        }

        R.id.save_config -> {
            saveRule()
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    private fun showToast(messageResId: Int) {
        Toast.makeText(this, getString(messageResId), Toast.LENGTH_SHORT).show()
    }

    private fun showDeleteConfirmDialog(onConfirmed: () -> Unit) {
        AlertDialog.Builder(this)
            .setMessage(R.string.del_config_comfirm)
            .setPositiveButton(android.R.string.ok) { _, _ -> onConfirmed() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

}
