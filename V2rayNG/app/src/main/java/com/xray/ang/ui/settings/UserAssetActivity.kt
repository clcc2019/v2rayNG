package com.xray.ang.ui

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
import com.xray.ang.AppConfig
import com.xray.ang.R
import com.xray.ang.contracts.BaseAdapterListener
import com.xray.ang.databinding.ActivityUserAssetBinding
import com.xray.ang.dto.AssetUrlCache
import com.xray.ang.dto.AssetUrlItem
import com.xray.ang.extension.toTrafficString
import com.xray.ang.extension.toast
import com.xray.ang.extension.toastError
import com.xray.ang.extension.toastSuccess
import com.xray.ang.handler.MmkvManager
import com.xray.ang.handler.SettingsManager
import com.xray.ang.ui.common.showChoiceBottomSheet
import com.xray.ang.ui.common.showConfirmDialog
import com.xray.ang.util.Utils
import com.xray.ang.viewmodel.UserAssetViewModel
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
    private var lastAssetsSignature: Int? = null

    val extDir by lazy { File(Utils.userAssetPath(this)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentViewWithToolbar(binding.root, showHomeAsUp = true, title = getString(R.string.title_user_asset_setting))

        binding.recyclerView.setHasFixedSize(false)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        optimizeRecyclerViewForHighRefresh(binding.recyclerView)
        binding.recyclerView.itemAnimator = null
        adapter = UserAssetAdapter(ActivityAdapterListener())
        binding.recyclerView.adapter = adapter

        binding.tvGeoFilesSourcesSummary.text = getGeoFilesSources()
        bindClickAction(binding.layoutGeoFilesSources, withHaptic = false) {
            setGeoFilesSources()
        }
        preloadInitialAssets()
        postScreenContentEnterMotion(binding.root)
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

    fun refreshData(force: Boolean = false) {
        val currentSignature = buildAssetsSignature(MmkvManager.decodeAssetUrls(), extDir.listFiles(), getGeoFilesSources())
        if (!force && lastAssetsSignature == currentSignature) {
            return
        }
        refreshJob?.cancel()
        refreshJob = lifecycleScope.launch(Dispatchers.IO) {
            viewModel.reload(getGeoFilesSources())
            val assets = viewModel.getAssets()
            val files = extDir.listFiles()
            val fileMeta = buildAssetFileMeta(files)
            val signature = buildAssetsSignature(assets, files, getGeoFilesSources())
            withContext(Dispatchers.Main) {
                renderAssets(assets, fileMeta, signature)
            }
        }
    }

    private fun preloadInitialAssets() {
        val geoFilesSource = getGeoFilesSources()
        viewModel.reload(geoFilesSource)
        val files = extDir.listFiles()
        renderAssets(
            assets = viewModel.getAssets(),
            fileMeta = buildAssetFileMeta(files),
            signature = buildAssetsSignature(viewModel.getAssets(), files, geoFilesSource)
        )
    }

    private fun renderAssets(
        assets: List<AssetUrlCache>,
        fileMeta: Map<String, UserAssetAdapter.AssetFileMeta>,
        signature: Int
    ) {
        adapter.submitList(assets, fileMeta)
        lastAssetsSignature = signature
    }

    private fun buildAssetFileMeta(files: Array<File>?): Map<String, UserAssetAdapter.AssetFileMeta> {
        if (files.isNullOrEmpty()) return emptyMap()
        val dateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM)
        return files.associate { file ->
            val properties = "${file.length().toTrafficString()}  •  ${dateFormat.format(Date(file.lastModified()))}"
            file.name to UserAssetAdapter.AssetFileMeta(properties)
        }
    }

    private fun buildAssetsSignature(
        assets: List<AssetUrlCache>,
        files: Array<File>?,
        geoFilesSource: String
    ): Int {
        val filesSignature = files
            ?.sortedBy { it.name }
            ?.fold(1) { acc, file ->
                31 * acc + file.name.hashCode() + file.length().hashCode() + file.lastModified().hashCode()
            }
            ?: 0
        return 31 * assets.hashCode() + 17 * filesSignature + geoFilesSource.hashCode()
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
