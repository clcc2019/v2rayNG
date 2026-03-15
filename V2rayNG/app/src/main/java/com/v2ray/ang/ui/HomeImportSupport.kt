package com.v2ray.ang.ui

import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import com.v2ray.ang.R
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.ui.common.ActionBottomSheetItem
import com.v2ray.ang.ui.common.actionBottomSheetItem

internal data class BatchImportResult(
    val configCount: Int,
    val subscriptionCount: Int
)

internal object HomeImportSupport {
    data class ManualImportAction(
        val type: EConfigType,
        @param:StringRes val labelResId: Int,
        val iconResId: Int
    )

    val manualImportActions: List<ManualImportAction> = listOf(
        ManualImportAction(EConfigType.POLICYGROUP, R.string.menu_item_import_config_policy_group, R.drawable.ic_subscriptions_24dp),
        ManualImportAction(EConfigType.VMESS, R.string.menu_item_import_config_manually_vmess, R.drawable.ic_add_24dp),
        ManualImportAction(EConfigType.VLESS, R.string.menu_item_import_config_manually_vless, R.drawable.ic_add_24dp),
        ManualImportAction(EConfigType.SHADOWSOCKS, R.string.menu_item_import_config_manually_ss, R.drawable.ic_add_24dp),
        ManualImportAction(EConfigType.SOCKS, R.string.menu_item_import_config_manually_socks, R.drawable.ic_add_24dp),
        ManualImportAction(EConfigType.HTTP, R.string.menu_item_import_config_manually_http, R.drawable.ic_add_24dp),
        ManualImportAction(EConfigType.TROJAN, R.string.menu_item_import_config_manually_trojan, R.drawable.ic_add_24dp),
        ManualImportAction(EConfigType.WIREGUARD, R.string.menu_item_import_config_manually_wireguard, R.drawable.ic_add_24dp),
        ManualImportAction(EConfigType.HYSTERIA2, R.string.menu_item_import_config_manually_hysteria2, R.drawable.ic_add_24dp)
    )

    fun buildManualImportActionItems(onSelected: (EConfigType) -> Unit): List<ActionBottomSheetItem> {
        return manualImportActions.map { entry ->
            actionBottomSheetItem(entry.labelResId, entry.iconResId) { onSelected(entry.type) }
        }
    }

    fun createManualImportIntent(context: Context, subscriptionId: String, type: EConfigType): Intent {
        return if (type == EConfigType.POLICYGROUP) {
            Intent(context, ServerGroupActivity::class.java)
                .putExtra("subscriptionId", subscriptionId)
        } else {
            Intent(context, ServerActivity::class.java)
                .putExtra("createConfigType", type.value)
                .putExtra("subscriptionId", subscriptionId)
        }
    }

    suspend fun importBatchConfig(server: String?, subscriptionId: String): BatchImportResult {
        val (configCount, subscriptionCount) = AngConfigManager.importBatchConfig(server, subscriptionId, true)
        return BatchImportResult(configCount = configCount, subscriptionCount = subscriptionCount)
    }
}
