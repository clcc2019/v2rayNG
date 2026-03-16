package com.xray.ang.viewmodel

import com.xray.ang.data.repository.RoutingRulesRepository
import com.xray.ang.dto.RulesetItem
import org.junit.Assert.assertEquals
import org.junit.Test

class RoutingSettingsViewModelTest {
    @Test
    fun reload_readsRulesFromRepository() {
        val repository = FakeRoutingRulesRepository(
            mutableListOf(
                RulesetItem(remarks = "A"),
                RulesetItem(remarks = "B")
            )
        )
        val viewModel = RoutingSettingsViewModel(repository)

        viewModel.reload()

        assertEquals(listOf("A", "B"), viewModel.getAll().map { it.remarks })
    }

    @Test
    fun swap_updatesInMemoryOrderAndPersistsOrder() {
        val repository = FakeRoutingRulesRepository(
            mutableListOf(
                RulesetItem(remarks = "A"),
                RulesetItem(remarks = "B")
            )
        )
        val viewModel = RoutingSettingsViewModel(repository)
        viewModel.reload()

        viewModel.swap(0, 1)

        assertEquals(listOf("B", "A"), viewModel.getAll().map { it.remarks })
        assertEquals(listOf(0 to 1), repository.swaps)
    }

    private class FakeRoutingRulesRepository(
        private val items: MutableList<RulesetItem>
    ) : RoutingRulesRepository {
        val swaps = mutableListOf<Pair<Int, Int>>()

        override fun getAll(): List<RulesetItem> = items.toList()

        override fun save(position: Int, item: RulesetItem) {
            items[position] = item
        }

        override fun swap(fromPosition: Int, toPosition: Int) {
            swaps += fromPosition to toPosition
            java.util.Collections.swap(items, fromPosition, toPosition)
        }
    }
}
