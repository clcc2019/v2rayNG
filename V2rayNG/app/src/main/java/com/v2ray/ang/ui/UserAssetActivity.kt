package com.v2ray.ang.ui

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.contracts.BaseAdapterListener
import com.v2ray.ang.databinding.ActivityUserAssetBinding
import com.v2ray.ang.dto.AssetUrlItem
import com.v2ray.ang.extension.toTrafficString
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.util.Utils
import com.v2ray.ang.viewmodel.UserAssetViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

class UserAssetActivity : HelperBaseActivity() {
    private val binding by lazy { ActivityUserAssetBinding.inflate(layoutInflater) }
    private val ownerActivity: UserAssetActivity
        get() = this
    private val viewModel: UserAssetViewModel by viewModels()
    private lateinit var adapter: UserAssetAdapter
    private var refreshJob: Job? = null

    val extDir by lazy { File(Utils.userAssetPath(this)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentViewWithToolbar(binding.root, showHomeAsUp = true, title = getString(R.string.title_user_asset_setting))

        binding.recyclerView.setHasFixedSize(false)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        optimizeRecyclerViewForHighRefresh(binding.recyclerView)
        adapter = UserAssetAdapter(ActivityAdapterListener())
        binding.recyclerView.adapter = adapter

        binding.tvGeoFilesSourcesSummary.text = getGeoFilesSources()
        binding.layoutGeoFilesSources.setOnClickListener {
            setGeoFilesSources()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshData()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_asset, menu)
        return super.onCreateOptionsMenu(menu)
    }

    // Use when to streamline the option selection
    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.add_file -> showFileChooser().let { true }
        R.id.add_url -> startActivity(Intent(this, UserAssetUrlActivity::class.java)).let { true }
        R.id.add_qrcode -> importAssetFromQRcode().let { true }
        R.id.download_file -> downloadGeoFiles().let { true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun getGeoFilesSources(): String {
        return MmkvManager.decodeSettingsString(AppConfig.PREF_GEO_FILES_SOURCES) ?: AppConfig.GEO_FILES_SOURCES.first()
    }

    private fun setGeoFilesSources() {
        AlertDialog.Builder(this).setItems(AppConfig.GEO_FILES_SOURCES.toTypedArray()) { _, i ->
            try {
                val value = AppConfig.GEO_FILES_SOURCES[i]
                MmkvManager.encodeSettings(AppConfig.PREF_GEO_FILES_SOURCES, value)
                binding.tvGeoFilesSourcesSummary.text = value
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "Failed to set geo files sources", e)
            }
        }.show()
    }

    private fun showFileChooser() {
        launchFileChooser { uri ->
            if (uri == null) {
                return@launchFileChooser
            }

            val assetId = Utils.getUuid()
            lifecycleScope.launch(Dispatchers.IO) {
                val displayName = getCursorName(uri) ?: uri.toString()
                val assetItem = AssetUrlItem(displayName, "file")
                val assetList = MmkvManager.decodeAssetUrls()
                if (assetList.any { it.assetUrl.remarks == assetItem.remarks && it.guid != assetId }) {
                    withContext(Dispatchers.Main) {
                        toast(R.string.msg_remark_is_duplicate)
                    }
                    return@launch
                }

                MmkvManager.encodeAsset(assetId, assetItem)
                val copied = copyFileInternal(uri, displayName)
                withContext(Dispatchers.Main) {
                    if (copied) {
                        toastSuccess(R.string.toast_success)
                        refreshData()
                    } else {
                        toastError(R.string.toast_asset_copy_failed)
                        MmkvManager.removeAssetUrl(assetId)
                    }
                }
            }
        }
    }

    private fun copyFileInternal(uri: Uri, fileName: String): Boolean {
        val targetFile = File(extDir, fileName)
        return try {
            contentResolver.openInputStream(uri).use { inputStream ->
                targetFile.outputStream().use { fileOut ->
                    inputStream?.copyTo(fileOut)
                }
            }
            true
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to copy asset file", e)
            false
        }
    }

    private fun getCursorName(uri: Uri): String? = try {
        contentResolver.query(uri, null, null, null, null)?.let { cursor ->
            cursor.run {
                if (moveToFirst()) getString(getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                else null
            }.also { cursor.close() }
        }
    } catch (e: Exception) {
        Log.e(AppConfig.TAG, "Failed to get cursor name", e)
        null
    }

    private fun importAssetFromQRcode(): Boolean {
        launchQRCodeScanner { scanResult ->
            if (scanResult != null) {
                importAsset(scanResult)
            }
        }
        return true
    }


    private fun importAsset(url: String?): Boolean {
        try {
            if (!Utils.isValidUrl(url)) {
                toast(R.string.toast_invalid_url)
                return false
            }
            // Send URL to UserAssetUrlActivity for Processing
            startActivity(
                Intent(this, UserAssetUrlActivity::class.java)
                    .putExtra(UserAssetUrlActivity.ASSET_URL_QRCODE, url)
            )
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to import asset from URL", e)
            return false
        }
        return true
    }

    private fun downloadGeoFiles() {
        refreshData()
        showLoading()
        toast(R.string.msg_downloading_content)

        val httpPort = SettingsManager.getHttpPort()
        lifecycleScope.launch(Dispatchers.IO) {
            val result = viewModel.downloadGeoFiles(extDir, httpPort)
            withContext(Dispatchers.Main) {
                if (result.successCount > 0) {
                    toast(getString(R.string.title_update_config_count, result.successCount))
                } else {
                    toast(getString(R.string.toast_failure))
                }
                refreshData()
                hideLoading()
            }
        }
    }

    fun initAssets() {
        lifecycleScope.launch(Dispatchers.Default) {
            SettingsManager.initAssets(this@UserAssetActivity, assets)
            withContext(Dispatchers.Main) {
                refreshData()
            }
        }
    }

    fun refreshData() {
        refreshJob?.cancel()
        refreshJob = lifecycleScope.launch(Dispatchers.IO) {
            viewModel.reload(getGeoFilesSources())
            val assets = viewModel.getAssets()
            val fileMeta = buildAssetFileMeta(extDir.listFiles())
            withContext(Dispatchers.Main) {
                adapter.submitList(assets, fileMeta)
            }
        }
    }

    private fun buildAssetFileMeta(files: Array<File>?): Map<String, UserAssetAdapter.AssetFileMeta> {
        if (files.isNullOrEmpty()) return emptyMap()
        val dateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM)
        return files.associate { file ->
            val properties = "${file.length().toTrafficString()}  •  ${dateFormat.format(Date(file.lastModified()))}"
            file.name to UserAssetAdapter.AssetFileMeta(properties)
        }
    }

    private inner class ActivityAdapterListener : BaseAdapterListener {
        override fun onEdit(guid: String, position: Int) {
            startActivity(
                Intent(ownerActivity, UserAssetUrlActivity::class.java)
                    .putExtra("assetId", guid)
            )
        }

        override fun onRemove(guid: String, position: Int) {
            val asset = viewModel.getAsset(position)?.takeIf { it.guid == guid }
                ?: viewModel.getAssets().find { it.guid == guid }
                ?: return
            val file = extDir.listFiles()?.find { it.name == asset.assetUrl.remarks }

            AlertDialog.Builder(ownerActivity).setMessage(R.string.del_config_comfirm)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    file?.delete()
                    MmkvManager.removeAssetUrl(guid)
                    initAssets()
                }
                .setNegativeButton(android.R.string.cancel) { _, _ ->
                    // do nothing
                }
                .show()
        }

        override fun onShare(url: String) {
        }

        override fun onRefreshData() {
            refreshData()
        }
    }
}
