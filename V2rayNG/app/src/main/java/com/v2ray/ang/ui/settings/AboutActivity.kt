package com.v2ray.ang.ui

import android.os.Bundle
import android.webkit.WebView
import android.widget.LinearLayout
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityAboutBinding
import com.v2ray.ang.handler.V2RayNativeManager
import com.v2ray.ang.ui.common.actionBottomSheetItem
import com.v2ray.ang.ui.common.showActionBottomSheet
import com.v2ray.ang.util.Utils

class AboutActivity : BaseActivity() {
    companion object {
        private const val OPEN_SOURCE_LICENSES_ASSET_URL = "file:///android_asset/open_source_licenses.html"
        private const val OPEN_SOURCE_LICENSES_MIN_HEIGHT_DP = 320f
    }

    private val binding by lazy { ActivityAboutBinding.inflate(layoutInflater) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentViewWithToolbar(binding.root, showHomeAsUp = true, title = getString(R.string.title_about))
        applyPressMotion(
            binding.layoutSoureCcode,
            binding.layoutOssLicenses,
            binding.layoutPrivacyPolicy
        )
        postScreenContentEnterMotion(binding.root)

        bindClickAction(binding.layoutSoureCcode, withHaptic = false) {
            Utils.openUri(this, AppConfig.APP_URL)
        }

        bindClickAction(binding.layoutOssLicenses, withHaptic = false) {
            showOpenSourceLicenses()
        }

        bindClickAction(binding.layoutPrivacyPolicy, withHaptic = false) {
            Utils.openUri(this, AppConfig.APP_PRIVACY_POLICY)
        }

        "v${BuildConfig.VERSION_NAME} (${V2RayNativeManager.getLibVersion()})".also {
            binding.tvVersion.text = it
        }
        BuildConfig.APPLICATION_ID.also {
            binding.tvAppId.text = it
        }
    }

    private fun showOpenSourceLicenses() {
        val webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.density * OPEN_SOURCE_LICENSES_MIN_HEIGHT_DP).toInt()
            )
            loadUrl(OPEN_SOURCE_LICENSES_ASSET_URL)
        }
        showActionBottomSheet(
            title = "Open source licenses",
            contentView = webView,
            actions = listOf(
                actionBottomSheetItem(getString(android.R.string.ok), R.drawable.ic_action_done) {}
            )
        )
    }
}
