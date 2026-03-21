package com.xray.ang.viewmodel

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.Observer
import com.xray.ang.AngApplication
import com.xray.ang.data.repository.MainServerRepository
import com.xray.ang.domain.main.MainServerTestCoordinator
import com.xray.ang.dto.GroupMapItem
import com.xray.ang.dto.ProfileItem
import com.xray.ang.dto.SubscriptionCache
import com.xray.ang.dto.SubscriptionItem
import com.xray.ang.enums.EConfigType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {
    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private val mainDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun loadSubscriptions_reusesCachedCountsAfterServerListReload() {
        val application = mockApp()
        val repository = FakeMainServerRepository(
            cachedSubscriptionId = "sub1",
            subscriptions = linkedMapOf("sub1" to SubscriptionItem(remarks = "Group A")),
            serverIdsBySubscription = linkedMapOf("sub1" to mutableListOf("a")),
            profiles = linkedMapOf("a" to profile("Alpha", "1.1.1.1", "sub1"))
        )
        val viewModel = MainViewModel(application, repository, MainServerTestCoordinator())
        val emittedGroups = mutableListOf<List<GroupMapItem>>()
        val observer = Observer<List<GroupMapItem>> { groups ->
            emittedGroups += groups.map { it.copy() }
        }
        viewModel.updateGroupsAction.observeForever(observer)

        try {
            viewModel.loadSubscriptions(application, immediate = true)
            waitUntil("initial group tabs") { emittedGroups.size == 1 }
            assertEquals(1, repository.serverCountsRequests)

            viewModel.reloadServerList(immediate = true)
            waitUntil("server list reload") {
                viewModel.updateListAction.value is MainViewModel.ServerListUpdate.Full
            }

            viewModel.loadSubscriptions(application, immediate = true)
            waitUntil("second group tabs") { emittedGroups.size == 2 }
            assertEquals(1, repository.serverCountsRequests)
            assertEquals(1, emittedGroups.last().single().count)
        } finally {
            viewModel.updateGroupsAction.removeObserver(observer)
        }
    }

    @Test
    fun removeServer_invalidatesGroupCountCacheAndRefreshesGroupTabs() {
        val application = mockApp()
        val repository = FakeMainServerRepository(
            cachedSubscriptionId = "sub1",
            subscriptions = linkedMapOf("sub1" to SubscriptionItem(remarks = "Group A")),
            serverIdsBySubscription = linkedMapOf("sub1" to mutableListOf("a")),
            profiles = linkedMapOf("a" to profile("Alpha", "1.1.1.1", "sub1"))
        )
        val viewModel = MainViewModel(application, repository, MainServerTestCoordinator())
        val emittedGroups = mutableListOf<List<GroupMapItem>>()
        val observer = Observer<List<GroupMapItem>> { groups ->
            emittedGroups += groups.map { it.copy() }
        }
        viewModel.updateGroupsAction.observeForever(observer)

        try {
            viewModel.loadSubscriptions(application, immediate = true)
            waitUntil("initial group tabs") { emittedGroups.size == 1 }
            assertEquals(1, repository.serverCountsRequests)
            assertEquals(1, emittedGroups.last().single().count)

            viewModel.removeServer("a")
            waitUntil("refreshed group tabs after removal") {
                emittedGroups.size >= 2 && emittedGroups.last().single().count == 0
            }

            assertEquals(2, repository.serverCountsRequests)
            assertTrue(repository.getServerIds("sub1").isEmpty())
        } finally {
            viewModel.updateGroupsAction.removeObserver(observer)
        }
    }

    @Test
    fun reloadServerList_immediateRequestSupersedesPendingDelayedReload() {
        val application = mockApp()
        val repository = FakeMainServerRepository(
            cachedSubscriptionId = "sub1",
            subscriptions = linkedMapOf("sub1" to SubscriptionItem(remarks = "Group A")),
            serverIdsBySubscription = linkedMapOf("sub1" to mutableListOf("a")),
            profiles = linkedMapOf("a" to profile("Alpha", "1.1.1.1", "sub1"))
        )
        val viewModel = MainViewModel(application, repository, MainServerTestCoordinator())

        viewModel.reloadServerList(immediate = false)
        mainDispatcher.scheduler.runCurrent()
        assertNull(viewModel.updateListAction.value)

        viewModel.reloadServerList(immediate = true)
        mainDispatcher.scheduler.runCurrent()

        waitUntil("immediate reload") {
            viewModel.updateListAction.value is MainViewModel.ServerListUpdate.Full
        }
        assertEquals(1, repository.serverIdsRequests)
    }

    private fun mockApp(): AngApplication {
        return mock<AngApplication>().also { app ->
            whenever(app.applicationContext).thenReturn(app)
        }
    }

    private fun profile(remarks: String, server: String, subscriptionId: String): ProfileItem {
        return ProfileItem.create(EConfigType.VMESS).apply {
            this.remarks = remarks
            this.server = server
            this.serverPort = "443"
            this.subscriptionId = subscriptionId
        }
    }

    private fun waitUntil(label: String, timeoutMs: Long = 2_000L, condition: () -> Boolean) {
        val start = System.currentTimeMillis()
        while (!condition()) {
            if (System.currentTimeMillis() - start > timeoutMs) {
                throw AssertionError("Timed out waiting for $label")
            }
            Thread.sleep(10L)
        }
    }

    private class FakeMainServerRepository(
        private var cachedSubscriptionId: String,
        private val subscriptions: LinkedHashMap<String, SubscriptionItem>,
        private val serverIdsBySubscription: LinkedHashMap<String, MutableList<String>>,
        private val profiles: LinkedHashMap<String, ProfileItem>,
        private val delays: MutableMap<String, Long> = linkedMapOf()
    ) : MainServerRepository {
        var allServerIdsRequests: Int = 0
        var serverIdsRequests: Int = 0
        var serverCountsRequests: Int = 0

        override fun getCachedSubscriptionId(): String = cachedSubscriptionId

        override fun setCachedSubscriptionId(subscriptionId: String) {
            cachedSubscriptionId = subscriptionId
        }

        override fun getSubscriptions(): List<SubscriptionCache> {
            return subscriptions.map { (guid, item) -> SubscriptionCache(guid, item) }
        }

        override fun getSubscription(subscriptionId: String): SubscriptionItem? = subscriptions[subscriptionId]

        override fun getSubscriptionIds(): List<String> = subscriptions.keys.toList()

        override fun getAllServerIds(): MutableList<String> {
            allServerIdsRequests += 1
            return serverIdsBySubscription.values.flatten().toMutableList()
        }

        override fun getServerIds(subscriptionId: String): MutableList<String> {
            serverIdsRequests += 1
            return serverIdsBySubscription[subscriptionId]?.toMutableList() ?: mutableListOf()
        }

        override fun saveServerIds(subscriptionId: String, serverIds: MutableList<String>) {
            serverIdsBySubscription[subscriptionId] = serverIds.toMutableList()
        }

        override fun getServer(guid: String): ProfileItem? = profiles[guid]

        override fun removeServer(guid: String) {
            profiles.remove(guid)
            delays.remove(guid)
            serverIdsBySubscription.values.forEach { ids -> ids.remove(guid) }
        }

        override fun removeAllServers(): Int {
            val count = profiles.size
            profiles.clear()
            delays.clear()
            serverIdsBySubscription.values.forEach(MutableList<String>::clear)
            return count
        }

        override fun removeInvalidServer(guid: String): Int = 0

        override fun getSelectedServerId(): String? = null

        override fun getServerDelayMillis(guid: String): Long? = delays[guid]

        override fun saveServerDelayMillis(guid: String, delayMillis: Long) {
            delays[guid] = delayMillis
        }

        override fun clearServerDelayResults(guids: List<String>) {
            guids.forEach(delays::remove)
        }

        override fun getAllServerCount(): Int = serverIdsBySubscription.values.sumOf { it.size }

        override fun getServerCount(subscriptionId: String): Int = getServerIds(subscriptionId).size

        override fun getServerCounts(subscriptionIds: Collection<String>): Map<String, Int> {
            serverCountsRequests += 1
            return subscriptionIds.associateWith(::getServerCount)
        }

        override fun isGroupAllDisplayEnabled(): Boolean = false

        override fun isAutoRemoveInvalidAfterTestEnabled(): Boolean = false

        override fun isAutoSortAfterTestEnabled(): Boolean = false
    }
}
