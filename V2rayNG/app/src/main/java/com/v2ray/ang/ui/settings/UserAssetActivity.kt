package com.v2ray.ang.ui

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
import com.v2ray.ang.ui.common.showChoiceBottomSheet
import com.v2ray.ang.ui.common.showConfirmDialog
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
        postScreenContentEnterMotion(binding.root)

        binding.recyclerView.setHasFixedSize(false)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        optimizeRecyclerViewForHighRefresh(binding.recyclerView)
        adapter = UserAssetAdapter(ActivityAdapterListener())
        binding.recyclerView.adapter = adapter

        binding.tvGeoFilesSourcesSummary.text = getGeoFilesSources()
        bindClickAction(binding.layoutGeoFilesSources, withHaptic = false) {
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
        showChoiceBottomSheet(
            title = getString(R.string.title_user_asset_setting),
            options = AppConfig.GEO_FILES_SOURCES,
            iconRes = R.drawable.ic_cloud_download_24dp
        ) { i ->
            try {
                val value = AppConfig.GEO_FILES_SOURCES[i]
                MmkvManager.encodeSettings(AppConfig.PREF_GEO_FILES_SOURCES, value)
                binding.tvGeoFilesSourcesSummary.text = value
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "Failed to set geo files sources", e)
            }
        }
    }

    private fun showFileChooser() {
        launchFileChooser { uri ->
            if (uri == null) {
                return@launchFileChooser
            }
            importAssetFile(uri)
        }
    }

    private fun importAssetFile(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            val assetId = Utils.getUuid()
            val displayName = getCursorName(uri) ?: uri.toString()
            if (isDuplicateAsset(displayName, assetId)) {
                withContext(Dispatchers.Main) {
                    toast(R.string.msg_remark_is_duplicate)
                }
                return@launch
            }

            MmkvManager.encodeAsset(assetId, AssetUrlItem(displayName, "file"))
            val copied = copyFileInternal(uri, displayName)
            withContext(Dispatchers.Main) {
                handleImportedAssetFile(copied, assetId)
            }
        }
    }

    private fun isDuplicateAsset(displayName: String, assetId: String): Boolean {
        return MmkvManager.decodeAssetUrls().any {
            it.assetUrl.remarks == displayName && it.guid != assetId
        }
    }

    private fun handleImportedAssetFile(copied: Boolean, assetId: String) {
        if (copied) {
            toastSuccess(R.string.toast_success)
            refreshData()
            return
        }
        toastError(R.string.toast_asset_copy_failed)
        MmkvManager.removeAssetUrl(assetId)
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
        toast(R.string.msg_downloading_content)

        val httpPort = SettingsManager.getHttpPort()
        launchLoadingTask(
            task = { viewModel.downloadGeoFiles(extDir, httpPort) },
            onSuccess = { result ->
                if (result.successCount > 0) {
                    toast(getString(R.string.title_update_config_count, result.successCount))
                } else {
                    toast(getString(R.string.toast_failure))
                }
                refreshData()
            }
        )
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
                // This list sits inside a NestedScrollView with wrap_content height, so it must
                // request a fresh measurement after async diff updates or rows may stay collapsed.
                adapter.submitList(assets, fileMeta) {
                    binding.recyclerView.requestLayout()
                }
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
            startActivity(Intent(ownerActivity, UserAssetUrlActivity::class.java).putExtra("assetId", guid))
        }

        override fun onRemove(guid: String, position: Int) {
            val asset = viewModel.getAsset(position)?.takeIf { it.guid == guid }
                ?: viewModel.getAssets().find { it.guid == guid }
                ?: return
            val file = extDir.listFiles()?.find { it.name == asset.assetUrl.remarks }

            ownerActivity.showConfirmDialog {
                file?.delete()
                MmkvManager.removeAssetUrl(guid)
                initAssets()
            }
        }

        override fun onShare(url: String) {
        }

        override fun onRefreshData() {
            refreshData()
        }
    }
}
