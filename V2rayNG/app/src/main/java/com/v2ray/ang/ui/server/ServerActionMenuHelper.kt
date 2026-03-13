package com.v2ray.ang.ui

import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import com.v2ray.ang.R

object ServerActionMenuHelper {
    fun configure(menuInflater: MenuInflater, menu: Menu, editGuid: String, isRunning: Boolean) {
        menuInflater.inflate(R.menu.action_server, menu)
        val delButton = menu.findItem(R.id.del_config)
        val saveButton = menu.findItem(R.id.save_config)

        if (editGuid.isNotEmpty()) {
            if (isRunning) {
                delButton.isVisible = false
                saveButton.isVisible = false
            }
        } else {
            delButton.isVisible = false
        }
    }

    fun handleMenuItem(item: MenuItem, onDelete: () -> Unit, onSave: () -> Unit): Boolean? {
        return when (item.itemId) {
            R.id.del_config -> {
                onDelete()
                true
            }
            R.id.save_config -> {
                onSave()
                true
            }
            else -> null
        }
    }
}
