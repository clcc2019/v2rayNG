package com.xray.ang.data.repository

import com.xray.ang.dto.SubscriptionCache
import com.xray.ang.dto.SubscriptionItem
import com.xray.ang.handler.MmkvManager
import com.xray.ang.handler.SettingsManager

interface SubscriptionsRepository {
    fun getAll(): List<SubscriptionCache>
    fun canRemove(subId: String): Boolean
    fun remove(subId: String): Boolean
    fun update(subId: String, item: SubscriptionItem)
    fun swap(fromPosition: Int, toPosition: Int)
}

object DefaultSubscriptionsRepository : SubscriptionsRepository {
    override fun getAll(): List<SubscriptionCache> = MmkvManager.decodeSubscriptions()

    override fun canRemove(subId: String): Boolean = MmkvManager.canRemoveSubscription(subId)

    override fun remove(subId: String): Boolean = MmkvManager.removeSubscription(subId)

    override fun update(subId: String, item: SubscriptionItem) {
        MmkvManager.encodeSubscription(subId, item)
    }

    override fun swap(fromPosition: Int, toPosition: Int) {
        SettingsManager.swapSubscriptions(fromPosition, toPosition)
    }
}
