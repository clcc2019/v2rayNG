package com.xray.ang.viewmodel

import androidx.lifecycle.ViewModel
import com.xray.ang.AppConfig
import com.xray.ang.AppConfig.ANG_PACKAGE
import java.io.IOException

class LogcatViewModel : ViewModel() {
    private val lock = Any()
    private val logsetsAll: MutableList<String> = mutableListOf()
    private var filteredLogs: List<String> = emptyList()
    private var currentFilter: String = ""

    fun getAll(): List<String> = synchronized(lock) { filteredLogs }

    fun loadLogcat() {
        try {
            val lst = LinkedHashSet<String>()
            lst.add("logcat")
            lst.add("-d")
            lst.add("-v")
            lst.add("time")
            lst.add("-s")
            lst.add("GoLog,${ANG_PACKAGE},AndroidRuntime,System.err")
            val process = Runtime.getRuntime().exec(lst.toTypedArray())
            val allText = process.inputStream.bufferedReader().use { it.readLines() }.reversed()

            val filterSnapshot = synchronized(lock) { currentFilter }
            val filtered = if (filterSnapshot.isEmpty()) {
                allText
            } else {
                allText.filter { it.contains(filterSnapshot, ignoreCase = true) }
            }
            synchronized(lock) {
                logsetsAll.clear()
                logsetsAll.addAll(allText)
                if (currentFilter == filterSnapshot) {
                    filteredLogs = filtered
                } else {
                    filteredLogs = if (currentFilter.isEmpty()) {
                        logsetsAll.toList()
                    } else {
                        logsetsAll.filter { it.contains(currentFilter, ignoreCase = true) }
                    }
                }
            }
        } catch (e: IOException) {
            android.util.Log.e(AppConfig.TAG, "Failed to get logcat", e)
        }
    }

    fun clearLogcat() {
        try {
            val lst = LinkedHashSet<String>()
            lst.add("logcat")
            lst.add("-c")
            val process = Runtime.getRuntime().exec(lst.toTypedArray())
            process.waitFor()

            synchronized(lock) {
                logsetsAll.clear()
                filteredLogs = emptyList()
            }
        } catch (e: IOException) {
            android.util.Log.e(AppConfig.TAG, "Failed to clear logcat", e)
        }
    }

    fun filter(content: String?) {
        val nextFilter = content?.trim().orEmpty()
        val snapshot: List<String>
        synchronized(lock) {
            if (nextFilter == currentFilter) {
                return
            }
            currentFilter = nextFilter
            snapshot = logsetsAll.toList()
        }
        val filtered = if (nextFilter.isEmpty()) {
            snapshot
        } else {
            snapshot.filter { it.contains(nextFilter, ignoreCase = true) }
        }
        synchronized(lock) {
            if (currentFilter == nextFilter) {
                filteredLogs = filtered
            }
        }
    }
}
