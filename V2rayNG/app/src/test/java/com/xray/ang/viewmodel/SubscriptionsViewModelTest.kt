package com.xray.ang.viewmodel

import com.xray.ang.data.repository.SubscriptionsRepository
import com.xray.ang.domain.settings.SettingsChangeNotifier
import com.xray.ang.dto.SubscriptionCache
import com.xray.ang.dto.SubscriptionItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionsViewModelTest {
    @Test
    fun remove_updatesStateAndRequestsGroupRefresh() {
        val repository = FakeSubscriptionsRepository(
            initial = mutableListOf(
                SubscriptionCache("a", SubscriptionItem(remarks = "A")),
                SubscriptionCache("b", SubscriptionItem(remarks = "B"))
            )
        )
        val notifier = FakeSettingsChangeNotifier()
        val viewModel = SubscriptionsViewModel(repository, notifier)

        val removed = viewModel.remove("a")

        assertTrue(removed)
        assertEquals(listOf("b"), viewModel.getAll().map { it.guid })
        assertEquals(listOf("a"), repository.removedIds)
        assertEquals(1, notifier.groupRefreshRequests)
    }

    @Test
    fun remove_returnsFalseWhenRepositoryRejectsChange() {
        val repository = FakeSubscriptionsRepository(
            initial = mutableListOf(SubscriptionCache("a", SubscriptionItem())),
            removableIds = emptySet()
        )
        val notifier = FakeSettingsChangeNotifier()
        val viewModel = SubscriptionsViewModel(repository, notifier)

        val removed = viewModel.remove("a")

        assertFalse(removed)
        assertEquals(listOf("a"), viewModel.getAll().map { it.guid })
        assertEquals(0, notifier.groupRefreshRequests)
    }

    @Test
    fun swap_updatesMemoryAndRepository() {
        val repository = FakeSubscriptionsRepository(
            initial = mutableListOf(
                SubscriptionCache("a", SubscriptionItem(remarks = "A")),
                SubscriptionCache("b", SubscriptionItem(remarks = "B"))
            )
        )
        val notifier = FakeSettingsChangeNotifier()
        val viewModel = SubscriptionsViewModel(repository, notifier)

        viewModel.swap(0, 1)

        assertEquals(listOf("b", "a"), viewModel.getAll().map { it.guid })
        assertEquals(listOf(0 to 1), repository.swaps)
        assertEquals(1, notifier.groupRefreshRequests)
    }

    private class FakeSubscriptionsRepository(
        initial: MutableList<SubscriptionCache>,
        private val removableIds: Set<String> = initial.mapTo(linkedSetOf()) { it.guid }
    ) : SubscriptionsRepository {
        private val items = initial
        val removedIds = mutableListOf<String>()
        val swaps = mutableListOf<Pair<Int, Int>>()

        override fun getAll(): List<SubscriptionCache> = items.toList()

        override fun canRemove(subId: String): Boolean = removableIds.contains(subId)

        override fun remove(subId: String): Boolean {
            if (!canRemove(subId)) {
                return false
            }
            removedIds += subId
            return items.removeAll { it.guid == subId }
        }

        override fun update(subId: String, item: SubscriptionItem) {
            val index = items.indexOfFirst { it.guid == subId }
            if (index >= 0) {
                items[index] = SubscriptionCache(subId, item)
            }
        }

        override fun swap(fromPosition: Int, toPosition: Int) {
            swaps += fromPosition to toPosition
            java.util.Collections.swap(items, fromPosition, toPosition)
        }
    }

    private class FakeSettingsChangeNotifier : SettingsChangeNotifier {
        var restartRequests: Int = 0
        var groupRefreshRequests: Int = 0

        override fun requestRestartService() {
            restartRequests += 1
        }

        override fun requestGroupTabRefresh() {
            groupRefreshRequests += 1
        }
    }
}
