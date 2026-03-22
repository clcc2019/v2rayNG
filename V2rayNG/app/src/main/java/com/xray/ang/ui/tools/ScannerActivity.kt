package com.xray.ang.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import androidx.lifecycle.lifecycleScope
import com.xray.ang.R
import com.xray.ang.ui.QrCaptureActivity
import com.xray.ang.databinding.ActivityNoneBinding
import com.xray.ang.extension.toast
import com.xray.ang.util.QRCodeDecoder
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanIntentResult
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ScannerActivity : HelperBaseActivity() {
    private val binding by lazy {  ActivityNoneBinding.inflate(layoutInflater) }
    private val maxDecodeSizePx = 1024

    private val scanQrCode = registerForActivityResult(ScanContract(), ::handleResult)

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentViewWithToolbar(binding.root, showHomeAsUp = true, title = getString(R.string.menu_item_import_config_qrcode))
        launchScan()
    }

    private fun launchScan() {
        scanQrCode.launch(
            ScanOptions()
                .setCaptureActivity(QrCaptureActivity::class.java)
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt(getString(R.string.menu_item_import_config_qrcode))
                .setTorchEnabled(false)
                .setBeepEnabled(false)
                .setOrientationLocked(false)
        )
    }

    private fun handleResult(result: ScanIntentResult) {
        val contents = result.contents
        if (!contents.isNullOrEmpty()) {
            finished(contents)
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
                        Log.e(javaClass.simpleName, "Failed to decode QR code from file", e)
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
