package com.xray.ang.domain.main

import com.xray.ang.data.repository.MainServerRepository
import com.xray.ang.dto.ProfileItem
import com.xray.ang.dto.SubscriptionCache
import com.xray.ang.dto.SubscriptionItem
import com.xray.ang.enums.EConfigType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ServerListSnapshotBuilderTest {
    @Test
    fun build_filtersServersAndAddsSubscriptionRemarksForAllGroup() {
        val repository = FakeMainServerRepository(
            subscriptions = mapOf(
                "sub1" to SubscriptionItem(remarks = "Group A"),
                "sub2" to SubscriptionItem(remarks = "Group B")
            ),
            serverIdsBySubscription = linkedMapOf(
                "sub1" to mutableListOf("a"),
                "sub2" to mutableListOf("b")
            ),
            profiles = mapOf(
                "a" to profile(remarks = "Alpha", server = "1.1.1.1", subscriptionId = "sub1"),
                "b" to profile(remarks = "Beta", server = "8.8.8.8", subscriptionId = "sub2")
            ),
            delays = mapOf("a" to 10L, "b" to 20L)
        )
        val builder = ServerListSnapshotBuilder(repository) { profile ->
            "${profile.server}:${profile.serverPort}"
        }

        val snapshot = builder.build(targetSubscriptionId = "", keyword = "alp")

        assertEquals(listOf("a"), snapshot.servers.map { it.guid })
        assertEquals("Group A", snapshot.servers.first().subscriptionRemarks)
        assertEquals(0, snapshot.positions.getValue("a"))
    }

    @Test
    fun resolveSelectedServerSnapshot_fallsBackToRepositoryWhenListDoesNotContainSelection() {
        val repository = FakeMainServerRepository(
            subscriptions = mapOf("sub1" to SubscriptionItem(remarks = "Group A")),
            serverIdsBySubscription = linkedMapOf("sub1" to mutableListOf("a")),
            profiles = mapOf("a" to profile(remarks = "Alpha", server = "1.1.1.1", subscriptionId = "sub1")),
            delays = mapOf("a" to 15L)
        )
        val builder = ServerListSnapshotBuilder(repository) { "resolved:${it.server}" }

        val snapshot = builder.resolveSelectedServerSnapshot("a", emptyList())

        assertNotNull(snapshot)
        assertEquals("resolved:1.1.1.1", snapshot?.displayAddress)
        assertEquals(15L, snapshot?.testDelayMillis)
        assertEquals("Group A", snapshot?.subscriptionRemarks)
    }

    @Test
    fun resolveSelectedServerSnapshot_returnsNullForBlankId() {
        val builder = ServerListSnapshotBuilder(FakeMainServerRepository())

        val snapshot = builder.resolveSelectedServerSnapshot("", emptyList())

        assertNull(snapshot)
    }

    @Test
    fun build_skipsDescriptionGenerationWhenKeywordMatchesDirectFields() {
        val repository = FakeMainServerRepository(
            serverIdsBySubscription = linkedMapOf("sub1" to mutableListOf("a")),
            profiles = mapOf(
                "a" to profile(remarks = "Alpha", server = "1.1.1.1", subscriptionId = "sub1")
            )
        )
        val descriptionCalls = AtomicInteger(0)
        val builder = ServerListSnapshotBuilder(repository) {
            descriptionCalls.incrementAndGet()
            "generated:${it.server}"
        }

        val snapshot = builder.build(targetSubscriptionId = "sub1", keyword = "alp")

        assertEquals(listOf("a"), snapshot.servers.map { it.guid })
        assertEquals(1, descriptionCalls.get())
        assertTrue(snapshot.servers.first().displayAddress.startsWith("generated:"))
    }

    private fun profile(
        remarks: String,
        server: String,
        subscriptionId: String
    ): ProfileItem {
        return ProfileItem.create(EConfigType.VMESS).apply {
            this.remarks = remarks
            this.server = server
            this.serverPort = "443"
            this.subscriptionId = subscriptionId
        }
    }

    private class FakeMainServerRepository(
        private val subscriptions: Map<String, SubscriptionItem> = emptyMap(),
        private val serverIdsBySubscription: LinkedHashMap<String, MutableList<String>> = linkedMapOf(),
        private val profiles: Map<String, ProfileItem> = emptyMap(),
        private val delays: Map<String, Long> = emptyMap()
    ) : MainServerRepository {
        override fun getCachedSubscriptionId(): String = ""

        override fun setCachedSubscriptionId(subscriptionId: String) = Unit

        override fun getSubscriptions(): List<SubscriptionCache> {
            return subscriptions.map { (guid, item) -> SubscriptionCache(guid, item) }
        }

        override fun getSubscription(subscriptionId: String): SubscriptionItem? = subscriptions[subscriptionId]

        override fun getSubscriptionIds(): List<String> = serverIdsBySubscription.keys.toList()

        override fun getAllServerIds(): MutableList<String> {
            return serverIdsBySubscription.values.flatten().toMutableList()
        }

        override fun getServerIds(subscriptionId: String): MutableList<String> {
            return serverIdsBySubscription[subscriptionId]?.toMutableList() ?: mutableListOf()
        }

        override fun saveServerIds(subscriptionId: String, serverIds: MutableList<String>) = Unit

        override fun getServer(guid: String): ProfileItem? = profiles[guid]

        override fun removeServer(guid: String) = Unit

        override fun removeAllServers(): Int = 0

        override fun removeInvalidServer(guid: String): Int = 0

        override fun getSelectedServerId(): String? = null

        override fun getServerDelayMillis(guid: String): Long? = delays[guid]

        override fun saveServerDelayMillis(guid: String, delayMillis: Long) = Unit

        override fun clearServerDelayResults(guids: List<String>) = Unit

        override fun getAllServerCount(): Int = getAllServerIds().size

        override fun getServerCount(subscriptionId: String): Int = getServerIds(subscriptionId).size

        override fun isGroupAllDisplayEnabled(): Boolean = true

        override fun isAutoRemoveInvalidAfterTestEnabled(): Boolean = false

        override fun isAutoSortAfterTestEnabled(): Boolean = false
    }
}
