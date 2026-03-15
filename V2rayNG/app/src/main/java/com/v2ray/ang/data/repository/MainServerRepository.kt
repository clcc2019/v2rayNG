package com.v2ray.ang.data.repository

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.ProfileItem
import com.v2ray.ang.dto.SubscriptionCache
import com.v2ray.ang.dto.SubscriptionItem
import com.v2ray.ang.handler.MmkvManager

interface MainServerRepository {
    fun getCachedSubscriptionId(): String
    fun setCachedSubscriptionId(subscriptionId: String)
    fun getSubscriptions(): List<SubscriptionCache>
    fun getSubscription(subscriptionId: String): SubscriptionItem?
    fun getSubscriptionIds(): List<String>
    fun getAllServerIds(): MutableList<String>
    fun getServerIds(subscriptionId: String): MutableList<String>
    fun saveServerIds(subscriptionId: String, serverIds: MutableList<String>)
    fun getServer(guid: String): ProfileItem?
    fun removeServer(guid: String)
    fun removeAllServers(): Int
    fun removeInvalidServer(guid: String): Int
    fun getSelectedServerId(): String?
    fun getServerDelayMillis(guid: String): Long?
    fun saveServerDelayMillis(guid: String, delayMillis: Long)
    fun clearServerDelayResults(guids: List<String>)
    fun getAllServerCount(): Int
    fun getServerCount(subscriptionId: String): Int
    fun isGroupAllDisplayEnabled(): Boolean
    fun isAutoRemoveInvalidAfterTestEnabled(): Boolean
    fun isAutoSortAfterTestEnabled(): Boolean
}

object DefaultMainServerRepository : MainServerRepository {
    override fun getCachedSubscriptionId(): String {
        return MmkvManager.decodeSettingsString(AppConfig.CACHE_SUBSCRIPTION_ID, "").orEmpty()
    }

    override fun setCachedSubscriptionId(subscriptionId: String) {
        MmkvManager.encodeSettings(AppConfig.CACHE_SUBSCRIPTION_ID, subscriptionId)
    }

    override fun getSubscriptions(): List<SubscriptionCache> = MmkvManager.decodeSubscriptions()

    override fun getSubscription(subscriptionId: String): SubscriptionItem? {
        return MmkvManager.decodeSubscription(subscriptionId)
    }

    override fun getSubscriptionIds(): List<String> = MmkvManager.decodeSubsList()

    override fun getAllServerIds(): MutableList<String> = MmkvManager.decodeAllServerList()

    override fun getServerIds(subscriptionId: String): MutableList<String> {
        return MmkvManager.decodeServerList(subscriptionId)
    }

    override fun saveServerIds(subscriptionId: String, serverIds: MutableList<String>) {
        MmkvManager.encodeServerList(serverIds, subscriptionId)
    }

    override fun getServer(guid: String): ProfileItem? = MmkvManager.decodeServerConfig(guid)

    override fun removeServer(guid: String) {
        MmkvManager.removeServer(guid)
    }

    override fun removeAllServers(): Int = MmkvManager.removeAllServer()

    override fun removeInvalidServer(guid: String): Int = MmkvManager.removeInvalidServer(guid)

    override fun getSelectedServerId(): String? = MmkvManager.getSelectServer()

    override fun getServerDelayMillis(guid: String): Long? = MmkvManager.getServerTestDelayMillis(guid)

    override fun saveServerDelayMillis(guid: String, delayMillis: Long) {
        MmkvManager.encodeServerTestDelayMillis(guid, delayMillis)
    }

    override fun clearServerDelayResults(guids: List<String>) {
        MmkvManager.clearAllTestDelayResults(guids)
    }

    override fun getAllServerCount(): Int = MmkvManager.decodeAllServerList().size

    override fun getServerCount(subscriptionId: String): Int = MmkvManager.decodeServerList(subscriptionId).size

    override fun isGroupAllDisplayEnabled(): Boolean {
        return MmkvManager.decodeSettingsBool(AppConfig.PREF_GROUP_ALL_DISPLAY)
    }

    override fun isAutoRemoveInvalidAfterTestEnabled(): Boolean {
        return MmkvManager.decodeSettingsBool(AppConfig.PREF_AUTO_REMOVE_INVALID_AFTER_TEST)
    }

    override fun isAutoSortAfterTestEnabled(): Boolean {
        return MmkvManager.decodeSettingsBool(AppConfig.PREF_AUTO_SORT_AFTER_TEST)
    }
}
