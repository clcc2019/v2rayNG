package com.xray.ang.viewmodel

import com.xray.ang.data.repository.PerAppProxyRepository
import com.xray.ang.domain.settings.SettingsChangeNotifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PerAppProxyViewModelTest {
    @Test
    fun addAll_persistsSelectionAndRequestsRestart() {
        val repository = FakePerAppProxyRepository(initial = linkedSetOf("pkg.a"))
        val notifier = FakeSettingsChangeNotifier()
        val viewModel = PerAppProxyViewModel(repository, notifier)

        viewModel.addAll(listOf("pkg.b", "pkg.c"))

        assertEquals(setOf("pkg.a", "pkg.b", "pkg.c"), viewModel.getAll())
        assertEquals(listOf(setOf("pkg.a", "pkg.b", "pkg.c")), repository.savedSnapshots)
        assertEquals(1, notifier.restartRequests)
    }

    @Test
    fun clear_doesNothingWhenAlreadyEmpty() {
        val repository = FakePerAppProxyRepository()
        val notifier = FakeSettingsChangeNotifier()
        val viewModel = PerAppProxyViewModel(repository, notifier)

        viewModel.clear()

        assertTrue(viewModel.getAll().isEmpty())
        assertTrue(repository.savedSnapshots.isEmpty())
        assertEquals(0, notifier.restartRequests)
    }

    @Test
    fun toggle_removesExistingPackage() {
        val repository = FakePerAppProxyRepository(initial = linkedSetOf("pkg.a"))
        val notifier = FakeSettingsChangeNotifier()
        val viewModel = PerAppProxyViewModel(repository, notifier)

        viewModel.toggle("pkg.a")

        assertFalse(viewModel.contains("pkg.a"))
        assertEquals(listOf(emptySet<String>()), repository.savedSnapshots)
        assertEquals(1, notifier.restartRequests)
    }

    private class FakePerAppProxyRepository(
        initial: Set<String> = emptySet()
    ) : PerAppProxyRepository {
        private var stored = initial.toMutableSet()
        val savedSnapshots = mutableListOf<Set<String>>()

        override fun getAll(): Set<String> = stored.toSet()

        override fun saveAll(packages: Set<String>) {
            stored = packages.toMutableSet()
            savedSnapshots += stored.toSet()
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
