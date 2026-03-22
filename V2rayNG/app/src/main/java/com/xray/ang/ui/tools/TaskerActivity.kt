package com.xray.ang.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.ArrayAdapter
import com.xray.ang.AppConfig
import com.xray.ang.R
import com.xray.ang.databinding.ActivityTaskerBinding
import com.xray.ang.handler.MmkvManager

class TaskerActivity : BaseActivity() {
    private val binding by lazy { ActivityTaskerBinding.inflate(layoutInflater) }

    private val serverLabels = arrayListOf("Default")
    private val serverGuids = arrayListOf(AppConfig.TASKER_DEFAULT_GUID)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentViewWithToolbar(binding.root, showHomeAsUp = true, title = "")

        MmkvManager.decodeAllServerList().forEach { key ->
            MmkvManager.decodeServerConfig(key)?.let { config ->
                serverLabels.add(config.remarks)
                serverGuids.add(key)
            }
        }
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_single_choice, serverLabels
        )
        binding.listview.adapter = adapter
        SystemFontWeightHelper.scheduleApply(binding.listview)

        restoreInitialSelection()
    }

    private fun restoreInitialSelection() {
        try {
            val bundle = intent?.getBundleExtra(AppConfig.TASKER_EXTRA_BUNDLE)
            val switch = bundle?.getBoolean(AppConfig.TASKER_EXTRA_BUNDLE_SWITCH, false)
            val guid = bundle?.getString(AppConfig.TASKER_EXTRA_BUNDLE_GUID, "")

            if (switch == null || guid.isNullOrEmpty()) {
                return
            }

            binding.switchStartService.isChecked = switch
            val position = serverGuids.indexOf(guid)
            if (position >= 0) {
                binding.listview.setItemChecked(position, true)
            }
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to initialize Tasker settings", e)
        }
    }

    private fun confirmFinish() {
        val position = binding.listview.checkedItemPosition.takeIf { it >= 0 } ?: return

        val extraBundle = Bundle()
        extraBundle.putBoolean(AppConfig.TASKER_EXTRA_BUNDLE_SWITCH, binding.switchStartService.isChecked)
        extraBundle.putString(AppConfig.TASKER_EXTRA_BUNDLE_GUID, serverGuids[position])
        val intent = Intent()

        val remarks = serverLabels[position]
        val blurb = if (binding.switchStartService.isChecked) {
            "Start $remarks"
        } else {
            "Stop $remarks"
        }

        intent.putExtra(AppConfig.TASKER_EXTRA_BUNDLE, extraBundle)
        intent.putExtra(AppConfig.TASKER_EXTRA_STRING_BLURB, blurb)
        setResult(RESULT_OK, intent)
        finish()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.action_server, menu)
        val delConfig = menu.findItem(R.id.del_config)
        delConfig?.isVisible = false
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.save_config -> {
            confirmFinish()
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

}
