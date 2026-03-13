package com.v2ray.ang.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityNoneBinding
import com.v2ray.ang.extension.toast
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.QRCodeDecoder
import io.github.g00fy2.quickie.QRResult
import io.github.g00fy2.quickie.ScanCustomCode
import io.github.g00fy2.quickie.config.BarcodeFormat
import io.github.g00fy2.quickie.config.ScannerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ScannerActivity : HelperBaseActivity() {
    private val binding by lazy {  ActivityNoneBinding.inflate(layoutInflater) }
    private val maxDecodeSizePx = 1024

    private val scanQrCode = registerForActivityResult(ScanCustomCode(), ::handleResult)

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentViewWithToolbar(binding.root, showHomeAsUp = true, title = getString(R.string.menu_item_import_config_qrcode))

        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_START_SCAN_IMMEDIATE)) {
            launchScan()
        }
    }

    private fun launchScan() {
        scanQrCode.launch(
            ScannerConfig.build {
                setHapticSuccessFeedback(true) // enable (default) or disable haptic feedback when a barcode was detected
                setShowTorchToggle(true) // show or hide (default) torch/flashlight toggle button
                setShowCloseButton(true) // show or hide (default) close button
                setBarcodeFormats(listOf(BarcodeFormat.QR_CODE))
            }
        )
    }

    private fun handleResult(result: QRResult) {
        if (result is QRResult.QRSuccess) {
            finished(result.content.rawValue.orEmpty())
        } else {
            finish()
        }
    }

    private fun finished(text: String) {
        val intent = Intent()
        intent.putExtra("SCAN_RESULT", text)
        setResult(RESULT_OK, intent)
        finish()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_scanner, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.scan_code -> {
            launchScan()
            true
        }

        R.id.select_photo -> {
            showFileChooser()
            true
        }


        else -> super.onOptionsItemSelected(item)
    }

    private fun showFileChooser() {
        launchFileChooser("image/*") { uri ->
            if (uri == null) {
                return@launchFileChooser
            }
            showLoading()
            lifecycleScope.launch(Dispatchers.IO) {
                val result = runCatching {
                    decodeQrFromUri(uri)
                }
                withContext(Dispatchers.Main) {
                    hideLoading()
                    result.onSuccess { text ->
                        if (text.isNullOrEmpty()) {
                            toast(R.string.toast_decoding_failed)
                        } else {
                            finished(text)
                        }
                    }.onFailure { e ->
                        Log.e(AppConfig.TAG, "Failed to decode QR code from file", e)
                        toast(R.string.toast_decoding_failed)
                    }
                }
            }
        }
    }

    private fun decodeQrFromUri(uri: Uri): String? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri).use { inputStream ->
            BitmapFactory.decodeStream(inputStream, null, options)
        }
        options.inSampleSize = calculateInSampleSize(options, maxDecodeSizePx, maxDecodeSizePx)
        options.inJustDecodeBounds = false
        val bitmap = contentResolver.openInputStream(uri).use { inputStream ->
            BitmapFactory.decodeStream(inputStream, null, options)
        }
        return QRCodeDecoder.syncDecodeQRCode(bitmap)
    }

    private fun calculateInSampleSize(
        options: BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            var halfHeight = height / 2
            var halfWidth = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }
}
