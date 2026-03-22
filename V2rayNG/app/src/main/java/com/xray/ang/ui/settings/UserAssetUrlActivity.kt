package com.xray.ang.ui

import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import com.xray.ang.AppConfig
import com.xray.ang.R
import com.xray.ang.databinding.ActivityUserAssetUrlBinding
import com.xray.ang.dto.AssetUrlItem
import com.xray.ang.extension.toast
import com.xray.ang.extension.toastSuccess
import com.xray.ang.handler.MmkvManager
import com.xray.ang.ui.common.showConfirmDialog
import com.xray.ang.util.Utils
import java.io.File

class UserAssetUrlActivity : BaseActivity() {
    companion object {
        const val ASSET_URL_QRCODE = "ASSET_URL_QRCODE"
    }

    private val binding by lazy { ActivityUserAssetUrlBinding.inflate(layoutInflater) }

    private var deleteMenuItem: MenuItem? = null

    private val extDir by lazy { File(Utils.userAssetPath(this)) }
    private val editAssetId by lazy { intent.getStringExtra("assetId").orEmpty() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentViewWithToolbar(binding.root, showHomeAsUp = true, title = getString(R.string.title_user_asset_add_url))

        val assetItem = MmkvManager.decodeAsset(editAssetId)
        val assetUrlQrcode = intent.getStringExtra(ASSET_URL_QRCODE)
        when {
            assetItem != null -> bindAsset(assetItem)
            assetUrlQrcode != null -> {
                binding.etRemarks.setText(File(assetUrlQrcode).name)
                binding.etUrl.setText(assetUrlQrcode)
            }

            else -> clearAsset()
        }
    }

    private fun bindAsset(assetItem: AssetUrlItem) {
        binding.etRemarks.text = Utils.getEditable(assetItem.remarks)
        binding.etUrl.text = Utils.getEditable(assetItem.url)
    }

    private fun clearAsset() {
        binding.etRemarks.text = null
        binding.etUrl.text = null
    }

    private fun saveAsset() {
        var assetItem = MmkvManager.decodeAsset(editAssetId)
        var assetId = editAssetId
        if (assetItem != null) {
            val file = extDir.resolve(assetItem.remarks)
            if (file.exists()) {
                try {
                    file.delete()
                } catch (e: Exception) {
                    Log.e(AppConfig.TAG, "Failed to delete asset file: ${file.path}", e)
                }
            }
        } else {
            assetId = Utils.getUuid()
            assetItem = AssetUrlItem()
        }

        assetItem.remarks = binding.etRemarks.text.toString()
        assetItem.url = binding.etUrl.text.toString()

        val assetList = MmkvManager.decodeAssetUrls()
        if (assetList.any { it.assetUrl.remarks == assetItem.remarks && it.guid != assetId }) {
            toast(R.string.msg_remark_is_duplicate)
            return
        }

        if (TextUtils.isEmpty(assetItem.remarks)) {
            toast(R.string.sub_setting_remarks)
            return
        }
        if (TextUtils.isEmpty(assetItem.url)) {
            toast(R.string.title_url)
            return
        }

        MmkvManager.encodeAsset(assetId, assetItem)
        toastSuccess(R.string.toast_success)
        finish()
    }

    private fun deleteAsset() {
        if (editAssetId.isNotEmpty()) {
            showConfirmDialog {
                MmkvManager.removeAssetUrl(editAssetId)
                finish()
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.action_server, menu)
        deleteMenuItem = menu.findItem(R.id.del_config)

        if (editAssetId.isEmpty()) {
            deleteMenuItem?.isVisible = false
        }

        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.del_config -> {
            deleteAsset()
            true
        }

        R.id.save_config -> {
            saveAsset()
            true
        }

        else -> super.onOptionsItemSelected(item)
    }
}
