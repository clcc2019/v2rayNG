package com.v2ray.ang.viewmodel

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.AssetManager
import android.util.Log
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.AngApplication
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.dto.GroupMapItem
import com.v2ray.ang.dto.ProfileItem
import com.v2ray.ang.dto.ServersCache
import com.v2ray.ang.dto.SubscriptionCache
import com.v2ray.ang.dto.SubscriptionUpdateResult
import com.v2ray.ang.dto.TestServiceMessage
import com.v2ray.ang.extension.serializable
import com.v2ray.ang.extension.nullIfBlank
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SpeedtestManager
import com.v2ray.ang.handler.V2rayConfigManager
import com.v2ray.ang.util.MessageUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

class MainViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private val TCPING_PARALLELISM = Runtime.getRuntime().availableProcessors().coerceAtLeast(2) * 2
    }

    data class ServiceFeedback(
        @StringRes val messageResId: Int,
        val style: Style
    ) {
        enum class Style {
            SUCCESS,
            ERROR,
            NEUTRAL
        }
    }

    private data class TcpingTarget(
        val guid: String,
        val serverAddress: String,
        val serverPort: Int
    )

    private data class ServerListSnapshot(
        val serverGuids: MutableList<String>,
        val servers: List<ServersCache>,
        val positions: Map<String, Int>
    )

    private var serverList = mutableListOf<String>() // MmkvManager.decodeServerList()
    private val serverPositions = mutableMapOf<String, Int>()
    private val serverListVersion = AtomicInteger(0)
    private val groupListVersion = AtomicInteger(0)
    var subscriptionId: String = MmkvManager.decodeSettingsString(AppConfig.CACHE_SUBSCRIPTION_ID, "").orEmpty()
    var keywordFilter = ""
    val serversCache = mutableListOf<ServersCache>()
    val isRunning by lazy { MutableLiveData<Boolean>() }
    val updateListAction by lazy { MutableLiveData<Int>() }
    val updateGroupsAction by lazy { MutableLiveData<List<GroupMapItem>>() }
    val updateTestResultAction by lazy { MutableLiveData<String>() }
    val serviceFeedbackAction by lazy { MutableLiveData<ServiceFeedback>() }
    private val tcpingDispatcher = Dispatchers.IO.limitedParallelism(TCPING_PARALLELISM)
    private var tcpingTestJob: Job? = null
    private var prewarmJob: Job? = null

    /**
     * Refer to the official documentation for [registerReceiver](https://developer.android.com/reference/androidx/core/content/ContextCompat#registerReceiver(android.content.Context,android.content.BroadcastReceiver,android.content.IntentFilter,int):
     * `registerReceiver(Context, BroadcastReceiver, IntentFilter, int)`.
     */
    fun startListenBroadcast() {
        isRunning.value = false
        val mFilter = IntentFilter(AppConfig.BROADCAST_ACTION_ACTIVITY)
        ContextCompat.registerReceiver(getApplication(), mMsgReceiver, mFilter, Utils.receiverFlags())
        MessageUtil.sendMsg2Service(getApplication(), AppConfig.MSG_REGISTER_CLIENT, "")
    }

    /**
     * Called when the ViewModel is cleared.
     */
    override fun onCleared() {
        getApplication<AngApplication>().unregisterReceiver(mMsgReceiver)
        tcpingTestJob?.cancel()
        SpeedtestManager.closeAllTcpSockets()
        Log.i(AppConfig.TAG, "Main ViewModel is cleared")
        super.onCleared()
    }

    /**
     * Reloads the server list based on current subscription filter.
     */
    fun reloadServerList() {
        val requestVersion = serverListVersion.incrementAndGet()
        val targetSubscriptionId = subscriptionId
        val targetKeyword = keywordFilter.trim()

        viewModelScope.launch(Dispatchers.Default) {
            val snapshot = buildServerListSnapshot(targetSubscriptionId, targetKeyword)
            withContext(Dispatchers.Main) {
                val currentKeyword = keywordFilter.trim()
                if (requestVersion != serverListVersion.get()
                    || targetSubscriptionId != subscriptionId
                    || targetKeyword != currentKeyword
                ) {
                    return@withContext
                }

                applyServerListSnapshot(snapshot)
                updateListAction.value = -1
                prewarmSelectedConfig()
            }
        }
    }

    /**
     * Removes a server by its GUID.
     * @param guid The GUID of the server to remove.
     */
    fun removeServer(guid: String) {
        invalidateServerListRequests()
        serverList.remove(guid)
        MmkvManager.removeServer(guid)
        val index = getPosition(guid)
        if (index >= 0) {
            serversCache.removeAt(index)
            rebuildServerPositions()
        }
    }

    /**
     * Swaps the positions of two servers.
     * @param fromPosition The initial position of the server.
     * @param toPosition The target position of the server.
     */
    fun swapServer(fromPosition: Int, toPosition: Int) {
        if (subscriptionId.isEmpty()) {
            return
        }
        if (fromPosition !in serverList.indices || toPosition !in serverList.indices) {
            return
        }

        invalidateServerListRequests()
        Collections.swap(serverList, fromPosition, toPosition)
        Collections.swap(serversCache, fromPosition, toPosition)
        rebuildServerPositions()

        MmkvManager.encodeServerList(serverList, subscriptionId)
    }

    /**
     * Updates the cache of servers.
     */
    @Synchronized
    fun updateCache() {
        applyServerListSnapshot(buildServerListSnapshot(subscriptionId, keywordFilter.trim()))
    }

    /**
     * Updates the configuration via subscription for all servers.
     * @return Detailed result of the subscription update operation.
     */
    fun updateConfigViaSubAll(): SubscriptionUpdateResult {
        if (subscriptionId.isEmpty()) {
            return AngConfigManager.updateConfigViaSubAll()
        } else {
            val subItem = MmkvManager.decodeSubscription(subscriptionId) ?: return SubscriptionUpdateResult()
            return AngConfigManager.updateConfigViaSub(SubscriptionCache(subscriptionId, subItem))
        }
    }

    /**
     * Exports all servers.
     * @return The number of exported servers.
     */
    fun exportAllServer(): Int {
        val serverListCopy =
            if (subscriptionId.isEmpty() && keywordFilter.isEmpty()) {
                serverList
            } else {
                currentVisibleServerGuids()
            }

        val ret = AngConfigManager.shareNonCustomConfigsToClipboard(
            getApplication<AngApplication>(),
            serverListCopy
        )
        return ret
    }

    /**
     * Tests the TCP ping for all servers.
     */
    fun testAllTcping() {
        tcpingTestJob?.cancel()
        SpeedtestManager.closeAllTcpSockets()
        val visibleServerGuids = currentVisibleServerGuids()
        MmkvManager.clearAllTestDelayResults(visibleServerGuids)
        updateVisibleServerDelays(visibleServerGuids, 0L)
        updateListAction.value = -1

        val targets = buildTcpingTargets()
        if (targets.isEmpty()) {
            return
        }

        tcpingTestJob = viewModelScope.launch {
            coroutineScope {
                targets.forEach { target ->
                    launch(tcpingDispatcher) {
                        val testResult = SpeedtestManager.tcping(target.serverAddress, target.serverPort)
                        MmkvManager.encodeServerTestDelayMillis(target.guid, testResult)
                        updateServerDelay(target.guid, testResult)
                        withContext(Dispatchers.Main) {
                            updateListAction.value = getPosition(target.guid)
                        }
                    }
                }
            }
        }
    }

    /**
     * Tests the real ping for all servers.
     */
    fun testAllRealPing() {
        MessageUtil.sendMsg2TestService(
            getApplication(),
            TestServiceMessage(key = AppConfig.MSG_MEASURE_CONFIG_CANCEL)
        )
        val visibleServerGuids = currentVisibleServerGuids()
        MmkvManager.clearAllTestDelayResults(visibleServerGuids)
        updateVisibleServerDelays(visibleServerGuids, 0L)
        updateListAction.value = -1

        viewModelScope.launch(Dispatchers.Default) {
            if (serversCache.isEmpty()) {
                return@launch
            }
            MessageUtil.sendMsg2TestService(
                getApplication(),
                TestServiceMessage(
                    key = AppConfig.MSG_MEASURE_CONFIG,
                    subscriptionId = subscriptionId,
                    serverGuids = if (keywordFilter.isNotEmpty()) visibleServerGuids else emptyList()
                )
            )
        }
    }

    /**
     * Tests the real ping for the current server.
     */
    fun testCurrentServerRealPing() {
        MessageUtil.sendMsg2Service(getApplication(), AppConfig.MSG_MEASURE_DELAY, "")
    }

    /**
     * Changes the subscription ID.
     * @param id The new subscription ID.
     */
    fun subscriptionIdChanged(id: String) {
        ensureSubscriptionLoaded(id, forceReload = true)
    }

    fun ensureSubscriptionLoaded(id: String, forceReload: Boolean = false) {
        val changed = subscriptionId != id
        if (changed) {
            subscriptionId = id
            MmkvManager.encodeSettings(AppConfig.CACHE_SUBSCRIPTION_ID, subscriptionId)
        }
        if (changed || forceReload) {
            reloadServerList()
        }
    }

    /**
     * Gets the subscriptions.
     * @param context The context.
     * @return A pair of lists containing the subscription IDs and remarks.
     */
    fun loadSubscriptions(context: Context) {
        val appContext = context.applicationContext
        val requestVersion = groupListVersion.incrementAndGet()
        val currentSubscriptionId = subscriptionId

        viewModelScope.launch(Dispatchers.Default) {
            val subscriptions = MmkvManager.decodeSubscriptions()
            val resolvedSubscriptionId = currentSubscriptionId.takeIf { current ->
                current.isEmpty() || subscriptions.any { it.guid == current }
            }.orEmpty()
            val groups = buildSubscriptions(appContext, subscriptions)

            withContext(Dispatchers.Main) {
                if (requestVersion != groupListVersion.get()) {
                    return@withContext
                }
                if (subscriptionId != resolvedSubscriptionId) {
                    subscriptionId = resolvedSubscriptionId
                    MmkvManager.encodeSettings(AppConfig.CACHE_SUBSCRIPTION_ID, subscriptionId)
                    reloadServerList()
                }
                updateGroupsAction.value = groups
            }
        }
    }

    private fun buildSubscriptions(context: Context, subscriptions: List<SubscriptionCache>): List<GroupMapItem> {
        val serverCountsBySubscriptionId = subscriptions.associate { it.guid to MmkvManager.decodeServerList(it.guid).size }

        val groups = mutableListOf<GroupMapItem>()
        if (subscriptions.size > 1
            && MmkvManager.decodeSettingsBool(AppConfig.PREF_GROUP_ALL_DISPLAY)
        ) {
            groups.add(
                GroupMapItem(
                    id = "",
                    remarks = context.getString(R.string.filter_config_all),
                    count = serverCountsBySubscriptionId.values.sum()
                )
            )
        }
        subscriptions.forEach { sub ->
            groups.add(
                GroupMapItem(
                    id = sub.guid,
                    remarks = sub.subscription.remarks,
                    count = serverCountsBySubscriptionId[sub.guid] ?: 0
                )
            )
        }
        return groups
    }

    fun prewarmSelectedConfig(guid: String? = MmkvManager.getSelectServer()) {
        if (guid.isNullOrBlank()) {
            return
        }
        prewarmJob?.cancel()
        prewarmJob = viewModelScope.launch(Dispatchers.Default) {
            V2rayConfigManager.prewarmConfig(getApplication<AngApplication>(), guid)
        }
    }

    /**
     * Gets the position of a server by its GUID.
     * @param guid The GUID of the server.
     * @return The position of the server.
     */
    fun getPosition(guid: String): Int {
        return serverPositions[guid] ?: -1
    }

    /**
     * Removes duplicate servers.
     * @return The number of removed servers.
     */
    fun removeDuplicateServer(): Int {
        invalidateServerListRequests()
        val seenProfiles = HashSet<ProfileItem>(serversCache.size)
        val duplicateGuids = ArrayList<String>()

        serversCache.forEach { server ->
            if (!seenProfiles.add(server.profile)) {
                duplicateGuids += server.guid
            }
        }

        duplicateGuids.forEach(MmkvManager::removeServer)
        return duplicateGuids.size
    }

    /**
     * Removes all servers.
     * @return The number of removed servers.
     */
    fun removeAllServer(): Int {
        invalidateServerListRequests()
        val count =
            if (subscriptionId.isEmpty() && keywordFilter.isEmpty()) {
                MmkvManager.removeAllServer()
            } else {
                val visibleServerGuids = currentVisibleServerGuids()
                visibleServerGuids.forEach(MmkvManager::removeServer)
                visibleServerGuids.size
            }
        return count
    }

    /**
     * Removes invalid servers.
     * @return The number of removed servers.
     */
    fun removeInvalidServer(): Int {
        invalidateServerListRequests()
        var count = 0
        if (subscriptionId.isEmpty() && keywordFilter.isEmpty()) {
            count += MmkvManager.removeInvalidServer("")
        } else {
            currentVisibleServerGuids().forEach { guid ->
                count += MmkvManager.removeInvalidServer(guid)
            }
        }
        return count
    }

    /**
     * Sorts servers by their test results.
     */
    fun sortByTestResults() {
        invalidateServerListRequests()
        if (subscriptionId.isEmpty()) {
            MmkvManager.decodeSubsList().forEach { guid ->
                sortByTestResultsForSub(guid)
            }
        } else {
            sortByTestResultsForSub(subscriptionId)
        }
    }

    /**
     * Sorts servers by their test results for a specific subscription.
     * @param subId The subscription ID to sort servers for.
     */
    private fun sortByTestResultsForSub(subId: String) {
        data class ServerDelay(var guid: String, var testDelayMillis: Long)

        val serverDelays = mutableListOf<ServerDelay>()
        val serverListToSort = MmkvManager.decodeServerList(subId)

        serverListToSort.forEach { key ->
            val delay = MmkvManager.getServerTestDelayMillis(key) ?: 0L
            serverDelays.add(ServerDelay(key, if (delay <= 0L) 999999 else delay))
        }
        serverDelays.sortBy { it.testDelayMillis }

        val sortedServerList = serverDelays.map { it.guid }.toMutableList()

        // Save the sorted list for this subscription
        MmkvManager.encodeServerList(sortedServerList, subId)
    }


    /**
     * Initializes assets.
     * @param assets The asset manager.
     */
    fun initAssets(assets: AssetManager) {
        viewModelScope.launch(Dispatchers.Default) {
            SettingsManager.initAssets(getApplication<AngApplication>(), assets)
        }
    }

    /**
     * Filters the configuration by a keyword.
     * @param keyword The keyword to filter by.
     */
    fun filterConfig(keyword: String) {
        if (keyword == keywordFilter) {
            return
        }
        keywordFilter = keyword
        reloadServerList()
    }

    fun onTestsFinished() {
        viewModelScope.launch(Dispatchers.Default) {
            if (MmkvManager.decodeSettingsBool(AppConfig.PREF_AUTO_REMOVE_INVALID_AFTER_TEST)) {
                removeInvalidServer()
            }

            if (MmkvManager.decodeSettingsBool(AppConfig.PREF_AUTO_SORT_AFTER_TEST)) {
                sortByTestResults()
            }

            withContext(Dispatchers.Main) {
                reloadServerList()
            }
        }
    }

    private fun rebuildServerPositions() {
        serverPositions.clear()
        serversCache.forEachIndexed { index, server ->
            serverPositions[server.guid] = index
        }
    }

    private fun updateServerDelay(guid: String, delayMillis: Long) {
        val position = getPosition(guid)
        if (position !in serversCache.indices) {
            return
        }
        val server = serversCache[position]
        if (server.testDelayMillis == delayMillis) {
            return
        }
        serversCache[position] = server.copy(testDelayMillis = delayMillis)
    }

    private fun updateVisibleServerDelays(guids: List<String>, delayMillis: Long) {
        guids.forEach { guid ->
            val position = getPosition(guid)
            if (position !in serversCache.indices) {
                return@forEach
            }
            val server = serversCache[position]
            if (server.testDelayMillis != delayMillis) {
                serversCache[position] = server.copy(testDelayMillis = delayMillis)
            }
        }
    }

    private fun currentVisibleServerGuids(): List<String> {
        return ArrayList<String>(serversCache.size).apply {
            serversCache.forEach { add(it.guid) }
        }
    }

    private fun buildTcpingTargets(): List<TcpingTarget> {
        return buildList(serversCache.size) {
            serversCache.forEach { item ->
                val serverAddress = item.profile.server ?: return@forEach
                val serverPort = item.profile.serverPort?.toIntOrNull() ?: return@forEach
                add(TcpingTarget(item.guid, serverAddress, serverPort))
            }
        }
    }

    private fun matchesKeyword(profile: ProfileItem, displayAddress: String, keyword: String): Boolean {
        return profile.remarks.contains(keyword, ignoreCase = true)
                || displayAddress.contains(keyword, ignoreCase = true)
                || profile.server.orEmpty().contains(keyword, ignoreCase = true)
    }

    private fun invalidateServerListRequests() {
        serverListVersion.incrementAndGet()
    }

    private fun buildServerListSnapshot(targetSubscriptionId: String, keyword: String): ServerListSnapshot {
        val targetServerList = if (targetSubscriptionId.isEmpty()) {
            MmkvManager.decodeAllServerList()
        } else {
            MmkvManager.decodeServerList(targetSubscriptionId)
        }

        val servers = ArrayList<ServersCache>(targetServerList.size)
        val positions = HashMap<String, Int>(targetServerList.size)
        targetServerList.forEach { guid ->
            val profile = MmkvManager.decodeServerConfig(guid) ?: return@forEach
            val displayAddress = profile.description.nullIfBlank() ?: AngConfigManager.generateDescription(profile)
            if (keyword.isNotEmpty() && !matchesKeyword(profile, displayAddress, keyword)) {
                return@forEach
            }
            val testDelayMillis = MmkvManager.getServerTestDelayMillis(guid) ?: 0L
            positions[guid] = servers.size
            servers += ServersCache(guid, profile, displayAddress, testDelayMillis)
        }
        return ServerListSnapshot(
            serverGuids = targetServerList,
            servers = servers,
            positions = positions
        )
    }

    private fun applyServerListSnapshot(snapshot: ServerListSnapshot) {
        serverList = snapshot.serverGuids
        serversCache.clear()
        serversCache.addAll(snapshot.servers)
        serverPositions.clear()
        serverPositions.putAll(snapshot.positions)
    }

    private val mMsgReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.getIntExtra("key", 0)) {
                AppConfig.MSG_STATE_RUNNING -> {
                    isRunning.value = true
                }

                AppConfig.MSG_STATE_NOT_RUNNING -> {
                    isRunning.value = false
                }

                AppConfig.MSG_STATE_START_SUCCESS -> {
                    serviceFeedbackAction.value = ServiceFeedback(
                        messageResId = R.string.connection_started,
                        style = ServiceFeedback.Style.SUCCESS
                    )
                    isRunning.value = true
                }

                AppConfig.MSG_STATE_START_FAILURE -> {
                    serviceFeedbackAction.value = ServiceFeedback(
                        messageResId = R.string.connection_start_failed,
                        style = ServiceFeedback.Style.ERROR
                    )
                    isRunning.value = false
                }

                AppConfig.MSG_STATE_STOP_SUCCESS -> {
                    serviceFeedbackAction.value = ServiceFeedback(
                        messageResId = R.string.connection_stopped,
                        style = ServiceFeedback.Style.NEUTRAL
                    )
                    isRunning.value = false
                }

                AppConfig.MSG_MEASURE_DELAY_SUCCESS -> {
                    updateTestResultAction.value = intent.getStringExtra("content")
                }

                AppConfig.MSG_MEASURE_CONFIG_SUCCESS -> {
                    val resultPair = intent.serializable<Pair<String, Long>>("content") ?: return
                    MmkvManager.encodeServerTestDelayMillis(resultPair.first, resultPair.second)
                    updateServerDelay(resultPair.first, resultPair.second)
                    updateListAction.value = getPosition(resultPair.first)
                }

                AppConfig.MSG_MEASURE_CONFIG_NOTIFY -> {
                    val content = intent.getStringExtra("content")
                    updateTestResultAction.value =
                        getApplication<AngApplication>().getString(R.string.connection_runing_task_left, content)
                }

                AppConfig.MSG_MEASURE_CONFIG_FINISH -> {
                    val content = intent.getStringExtra("content")
                    if (content == "0") {
                        onTestsFinished()
                    }
                }
            }
        }
    }
}
