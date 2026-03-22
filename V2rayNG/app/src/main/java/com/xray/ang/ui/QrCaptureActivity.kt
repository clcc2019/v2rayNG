package com.xray.ang.ui

import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.xray.ang.R

class QrCaptureActivity : CaptureActivity() {
    override fun initializeContent(): DecoratedBarcodeView {
        setContentView(R.layout.zxing_capture_qr)
        return findViewById(R.id.zxing_barcode_scanner)
    }
}
