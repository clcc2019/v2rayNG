package com.xray.ang.viewmodel

import androidx.lifecycle.ViewModel
import com.xray.ang.data.repository.DefaultPerAppProxyRepository
import com.xray.ang.data.repository.PerAppProxyRepository
import com.xray.ang.domain.settings.DefaultSettingsChangeNotifier
import com.xray.ang.domain.settings.SettingsChangeNotifier

class PerAppProxyViewModel(
    private val perAppProxyRepository: PerAppProxyRepository,
    private val settingsChangeNotifier: SettingsChangeNotifier
) : ViewModel() {
    constructor() : this(
        perAppProxyRepository = DefaultPerAppProxyRepository,
        settingsChangeNotifier = DefaultSettingsChangeNotifier
    )

    private val blacklist: MutableSet<String> =
        perAppProxyRepository.getAll().toMutableSet()

    fun contains(packageName: String): Boolean = blacklist.contains(packageName)

    fun getAll(): Set<String> = blacklist.toSet()

    fun add(packageName: String): Boolean = updateBlacklist { add(packageName) }

    fun remove(packageName: String): Boolean = updateBlacklist { remove(packageName) }

    fun toggle(packageName: String) {
        updateBlacklist {
            if (remove(packageName)) {
                true
            } else {
                add(packageName)
            }
        }
    }

    fun containsAll(packages: Collection<String>): Boolean = packages.all(blacklist::contains)

    fun addAll(packages: Collection<String>) {
        updateBlacklist { addAll(packages) }
    }

    fun removeAll(packages: Collection<String>) {
        updateBlacklist { removeAll(packages) }
    }

    fun clear() {
        updateBlacklist {
            if (isEmpty()) {
                false
            } else {
                clear()
                true
            }
        }
    }

    fun toggleAll(packages: Collection<String>) {
        updateBlacklist {
            var changed = false
            packages.forEach { packageName ->
                changed = if (remove(packageName)) {
                    true
                } else {
                    add(packageName) || changed
                }
            }
            changed
        }
    }

    fun replaceAll(packages: Collection<String>) {
        val updated = packages.toHashSet()
        updateBlacklist {
            if (this == updated) {
                return@updateBlacklist false
            }
            clear()
            addAll(updated)
            true
        }
    }

    private inline fun updateBlacklist(action: MutableSet<String>.() -> Boolean): Boolean {
        val changed = blacklist.action()
        if (changed) {
            save()
        }
        return changed
    }

    private fun save() {
        perAppProxyRepository.saveAll(blacklist)
        settingsChangeNotifier.requestRestartService()
    }
}
