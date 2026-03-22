package com.xray.ang.domain.main

import com.xray.ang.data.repository.MainServerRepository
import com.xray.ang.dto.ProfileItem
import com.xray.ang.dto.ServersCache
import com.xray.ang.extension.nullIfBlank
import com.xray.ang.handler.AngConfigManager

data class MainServerListSnapshot(
    val subscriptionId: String,
    val keyword: String,
    val serverGuids: MutableList<String>,
    val servers: List<ServersCache>,
    val positions: Map<String, Int>,
    val selectedServer: ServersCache? = null
)

class ServerListSnapshotBuilder(
    private val repository: MainServerRepository,
    private val descriptionProvider: (ProfileItem) -> String = AngConfigManager::generateDescription
) {
    private data class DisplayAddressCacheEntry(
        val signature: String,
        val displayAddress: String
    )

    private val subscriptionRemarksCache = mutableMapOf<String, String>()
    private val displayAddressCache = mutableMapOf<String, DisplayAddressCacheEntry>()

    fun clearSubscriptionRemarksCache() {
        subscriptionRemarksCache.clear()
    }

    fun build(targetSubscriptionId: String, keyword: String): MainServerListSnapshot {
        val targetServerList = if (targetSubscriptionId.isEmpty()) {
            repository.getAllServerIds()
        } else {
            repository.getServerIds(targetSubscriptionId)
        }
        val profilesByGuid = repository.getServers(targetServerList)
        val delaysByGuid = repository.getServerDelayMillisMap(targetServerList)
        val selectedServerId = repository.getSelectedServerId().orEmpty()

        val servers = ArrayList<ServersCache>(targetServerList.size)
        val positions = HashMap<String, Int>(targetServerList.size)
        val includeSubscriptionRemarks = targetSubscriptionId.isEmpty()
        targetServerList.forEach { guid ->
            val profile = profilesByGuid[guid] ?: return@forEach
            val displayAddress = if (keyword.isEmpty()) {
                resolveDisplayAddress(guid, profile)
            } else {
                if (matchesKeywordWithoutDescription(profile, keyword)) {
                    resolveDisplayAddress(guid, profile)
                } else {
                    val resolved = resolveDisplayAddress(guid, profile)
                    if (!resolved.contains(keyword, ignoreCase = true)) {
                        return@forEach
                    }
                    resolved
                }
            }
            val testDelayMillis = delaysByGuid[guid] ?: 0L
            positions[guid] = servers.size
            val remarks = if (includeSubscriptionRemarks) subscriptionRemarkFor(profile.subscriptionId) else ""
            servers += ServersCache(guid, profile, displayAddress, testDelayMillis, remarks)
        }

        val selectedServer = if (selectedServerId.isBlank()) {
            null
        } else {
            resolveSelectedServerSnapshot(selectedServerId, servers)
        }

        return MainServerListSnapshot(
            subscriptionId = targetSubscriptionId,
            keyword = keyword,
            serverGuids = targetServerList,
            servers = servers,
            positions = positions,
            selectedServer = selectedServer
        )
    }

    fun resolveSelectedServerSnapshot(
        guid: String,
        currentServers: List<ServersCache>
    ): ServersCache? {
        if (guid.isBlank()) {
            return null
        }
        return currentServers.firstOrNull { it.guid == guid } ?: repository.getServer(guid)?.let { profile ->
            val displayAddress = resolveDisplayAddress(guid, profile)
            val delay = repository.getServerDelayMillis(guid) ?: 0L
            val subRemarks = subscriptionRemarkFor(profile.subscriptionId)
            ServersCache(guid, profile, displayAddress, delay, subRemarks)
        }
    }

    private fun matchesKeywordWithoutDescription(profile: ProfileItem, keyword: String): Boolean {
        if (profile.remarks.contains(keyword, ignoreCase = true)) return true
        if (profile.server.orEmpty().contains(keyword, ignoreCase = true)) return true
        return false
    }

    private fun resolveDisplayAddress(guid: String, profile: ProfileItem): String {
        profile.description.nullIfBlank()?.let {
            displayAddressCache.remove(guid)
            return it
        }
        val signature = "${profile.server.orEmpty()}|${profile.serverPort.orEmpty()}"
        val cached = displayAddressCache[guid]
        if (cached?.signature == signature) {
            return cached.displayAddress
        }
        val generated = descriptionProvider(profile)
        displayAddressCache[guid] = DisplayAddressCacheEntry(signature, generated)
        return generated
    }

    private fun subscriptionRemarkFor(subscriptionId: String?): String {
        if (subscriptionId.isNullOrBlank()) {
            return ""
        }
        return subscriptionRemarksCache.getOrPut(subscriptionId) {
            repository.getSubscription(subscriptionId)?.remarks.orEmpty()
        }
    }
}
