package com.xray.ang.viewmodel

import androidx.lifecycle.ViewModel
import com.xray.ang.data.repository.DefaultSubscriptionsRepository
import com.xray.ang.data.repository.SubscriptionsRepository
import com.xray.ang.dto.SubscriptionCache
import com.xray.ang.dto.SubscriptionItem
import com.xray.ang.domain.settings.DefaultSettingsChangeNotifier
import com.xray.ang.domain.settings.SettingsChangeNotifier
import java.util.Collections

class SubscriptionsViewModel(
    private val subscriptionsRepository: SubscriptionsRepository,
    private val settingsChangeNotifier: SettingsChangeNotifier
) : ViewModel() {
    constructor() : this(
        subscriptionsRepository = DefaultSubscriptionsRepository,
        settingsChangeNotifier = DefaultSettingsChangeNotifier
    )

    private val subscriptions: MutableList<SubscriptionCache> =
        subscriptionsRepository.getAll().toMutableList()

    fun getAll(): List<SubscriptionCache> = subscriptions.toList()

    fun reload() {
        subscriptions.clear()
        subscriptions.addAll(subscriptionsRepository.getAll())
    }

    fun canRemove(subId: String): Boolean = subscriptionsRepository.canRemove(subId)

    fun remove(subId: String): Boolean {
        if (!subscriptionsRepository.remove(subId)) {
            return false
        }
        subscriptions.removeAll { it.guid == subId }
        notifySubscriptionsChanged()
        return true
    }

    fun update(subId: String, item: SubscriptionItem) {
        subscriptions.indexOfFirst { it.guid == subId }
            .takeIf { it >= 0 }
            ?.let { index ->
                subscriptions[index] = SubscriptionCache(subId, item)
                subscriptionsRepository.update(subId, item)
            }
    }

    fun swap(fromPosition: Int, toPosition: Int) {
        if (fromPosition !in subscriptions.indices || toPosition !in subscriptions.indices) {
            return
        }
        Collections.swap(subscriptions, fromPosition, toPosition)
        subscriptionsRepository.swap(fromPosition, toPosition)
        notifySubscriptionsChanged()
    }

    private fun notifySubscriptionsChanged() {
        settingsChangeNotifier.requestGroupTabRefresh()
    }
}
