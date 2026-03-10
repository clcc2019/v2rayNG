package com.v2ray.ang.ui

import android.app.Activity
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
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

    fun showConfirmDialog(
        activity: Activity,
        @StringRes messageResId: Int = R.string.del_config_comfirm,
        onConfirmed: () -> Unit
    ) {
        AlertDialog.Builder(activity)
            .setMessage(messageResId)
            .setPositiveButton(android.R.string.ok) { _, _ -> onConfirmed() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
