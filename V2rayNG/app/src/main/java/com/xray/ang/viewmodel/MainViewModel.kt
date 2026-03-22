package com.xray.ang.viewmodel

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
import com.xray.ang.AngApplication
import com.xray.ang.AppConfig
import com.xray.ang.R
import com.xray.ang.data.repository.DefaultMainServerRepository
import com.xray.ang.data.repository.MainServerRepository
import com.xray.ang.domain.main.MainServiceEvent
import com.xray.ang.domain.main.MainServiceEventInterpreter
import com.xray.ang.domain.main.MainServerTestCoordinator
import com.xray.ang.domain.main.MainServerListSnapshot
import com.xray.ang.domain.main.ServerDelayResultParser
import com.xray.ang.domain.main.ServerListSnapshotBuilder
import com.xray.ang.domain.main.ServerTestTarget
import com.xray.ang.dto.GroupMapItem
import com.xray.ang.dto.ProfileItem
import com.xray.ang.dto.ServersCache
import com.xray.ang.dto.SubscriptionCache
import com.xray.ang.dto.SubscriptionUpdateResult
import com.xray.ang.handler.MmkvManager
import com.xray.ang.handler.AngConfigManager
import com.xray.ang.handler.SettingsManager
import com.xray.ang.handler.V2rayConfigManager
import com.xray.ang.util.MessageUtil
import com.xray.ang.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

class MainViewModel(
    application: Application,
    private val mainServerRepository: MainServerRepository,
    private val serverTestCoordinator: MainServerTestCoordinator
) : AndroidViewModel(application) {
    constructor(application: Application) : this(
        application = application,
        mainServerRepository = DefaultMainServerRepository,
        serverTestCoordinator = MainServerTestCoordinator()
    )

    companion object {
        private const val TCPING_UI_BATCH_SIZE = 12
        private const val LIST_UPDATE_THROTTLE_MS = 96L
        private const val RELOAD_SCHEDULE_DEBOUNCE_MS = 96L
        private const val GROUP_LOAD_DEBOUNCE_MS = 96L
        private const val FILTER_DEBOUNCE_MS = 150L
    }

    private var tcpingCollectorJob: Job? = null

    data class ServiceFeedback(
        @param:StringRes val messageResId: Int,
        val style: Style
    ) {
        enum class Style {
            SUCCESS,
            ERROR,
            NEUTRAL
        }
    }

    data class ServicePhaseFeedback(
        @param:StringRes val messageResId: Int,
        val state: State
    ) {
        enum class State {
            STARTING,
            STOPPING
        }
    }

    private var serverList = mutableListOf<String>()
    private val serverPositions = mutableMapOf<String, Int>()
    private val serverListVersion = AtomicInteger(0)
    private val groupListVersion = AtomicInteger(0)
    private val snapshotBuilder = ServerListSnapshotBuilder(mainServerRepository)
    var subscriptionId: String = mainServerRepository.getCachedSubscriptionId()
    var keywordFilter = ""
    val serversCache = mutableListOf<ServersCache>()
    private var selectedServerSnapshot: ServersCache? = null
    val isRunning by lazy { MutableLiveData<Boolean>() }
    val updateListAction by lazy { MutableLiveData<ServerListUpdate>() }
    val updateGroupsAction by lazy { MutableLiveData<List<GroupMapItem>>() }
    val updateTestResultAction by lazy { MutableLiveData<String>() }
    val updateConnectionTestAction by lazy { MutableLiveData<String>() }
    val updateConnectionCardAction by lazy { MutableLiveData<Int>() }
    val serviceFeedbackAction by lazy { MutableLiveData<ServiceFeedback>() }
    val servicePhaseAction by lazy { MutableLiveData<ServicePhaseFeedback>() }
    private var tcpingTestJob: Job? = null
    private var prewarmJob: Job? = null
    private var reloadJob: Job? = null
    private var filterJob: Job? = null
    private var reloadJobScheduled: Job? = null
    private var isReloadScheduledImmediate = false
    private var subscriptionsJobScheduled: Job? = null
    private var subscriptionsLoadJob: Job? = null
    private var pendingListUpdateJob: Job? = null
    private val pendingListUpdateLock = Any()
    private val pendingListUpdates = LinkedHashSet<Int>()
    private val groupCountLock = Any()
    private var cachedGroupIds: List<String> = emptyList()
    private var cachedGroupCounts: Map<String, Int> = emptyMap()
    private var isGroupCountCacheDirty = true
    private var connectionCardRefreshVersion = 0
    private var isReceiverRegistered = false

    sealed class ServerListUpdate {
        data class Full(val reason: Reason = Reason.GENERAL) : ServerListUpdate() {
            enum class Reason {
                GENERAL,
                FILTER,
                RELOAD
            }
        }

        data class Single(val position: Int) : ServerListUpdate()
        data class Batch(val positions: List<Int>) : ServerListUpdate()
    }

    private fun clearPendingListUpdates() {
        synchronized(pendingListUpdateLock) {
            pendingListUpdateJob?.cancel()
            pendingListUpdateJob = null
            pendingListUpdates.clear()
        }
    }

    private fun scheduleListUpdate(position: Int) {
        scheduleListUpdate(listOf(position))
    }

    private fun scheduleListUpdate(positions: Collection<Int>) {
        synchronized(pendingListUpdateLock) {
            for (position in positions) {
                if (position >= 0) {
                    pendingListUpdates.add(position)
                }
            }
            if (pendingListUpdateJob?.isActive != true && pendingListUpdates.isNotEmpty()) {
                pendingListUpdateJob = viewModelScope.launch(Dispatchers.Main.immediate) {
                    while (true) {
                        delay(LIST_UPDATE_THROTTLE_MS)
                        val pending = synchronized(pendingListUpdateLock) {
                            if (pendingListUpdates.isEmpty()) {
                                pendingListUpdateJob = null
                                return@launch
                            }
                            val snapshot = pendingListUpdates.toList()
                            pendingListUpdates.clear()
                            snapshot
                        }
                        updateListAction.value = when (pending.size) {
                            1 -> ServerListUpdate.Single(pending.first())
                            else -> ServerListUpdate.Batch(pending)
                        }
                    }
                }
            }
        }
    }

    /**
     * Refer to the official documentation for [registerReceiver](https://developer.android.com/reference/androidx/core/content/ContextCompat#registerReceiver(android.content.Context,android.content.BroadcastReceiver,android.content.IntentFilter,int):
     * `registerReceiver(Context, BroadcastReceiver, IntentFilter, int)`.
     */
    fun startListenBroadcast() {
        if (isReceiverRegistered) {
            return
        }
        isRunning.value = false
        val mFilter = IntentFilter(AppConfig.BROADCAST_ACTION_ACTIVITY)
        ContextCompat.registerReceiver(getApplication(), mMsgReceiver, mFilter, Utils.receiverFlags())
        MessageUtil.sendMsg2Service(getApplication(), AppConfig.MSG_REGISTER_CLIENT, "")
        isReceiverRegistered = true
    }

    /**
     * Called when the ViewModel is cleared.
     */
    override fun onCleared() {
        if (isReceiverRegistered) {
            runCatching {
                getApplication<AngApplication>().unregisterReceiver(mMsgReceiver)
            }
            isReceiverRegistered = false
        }
        tcpingTestJob?.cancel()
        reloadJob?.cancel()
        filterJob?.cancel()
        reloadJobScheduled?.cancel()
        subscriptionsJobScheduled?.cancel()
        subscriptionsLoadJob?.cancel()
        pendingListUpdateJob?.cancel()
        pendingListUpdates.clear()
        serverTestCoordinator.cancelTcping()
        Log.i(AppConfig.TAG, "Main ViewModel is cleared")
        super.onCleared()
    }

    /**
     * Reloads the server list based on current subscription filter.
     */
    fun reloadServerList(immediate: Boolean = false) {
        val activeScheduledReload = reloadJobScheduled?.takeIf { it.isActive }
        if (activeScheduledReload != null) {
            if (!immediate || isReloadScheduledImmediate) {
                return
            }
            activeScheduledReload.cancel()
        }
        reloadJobScheduled?.cancel()
        isReloadScheduledImmediate = immediate
        reloadJobScheduled = viewModelScope.launch(Dispatchers.Main.immediate) {
            try {
                if (!isReloadScheduledImmediate) {
                    delay(RELOAD_SCHEDULE_DEBOUNCE_MS)
                }
                reloadServerListInternal()
            } finally {
                if (reloadJobScheduled === this) {
                    reloadJobScheduled = null
                    isReloadScheduledImmediate = false
                }
            }
        }
    }

    private fun reloadServerListInternal() {
        val requestVersion = serverListVersion.incrementAndGet()
        val targetSubscriptionId = subscriptionId
        val targetKeyword = keywordFilter.trim()
        if (targetSubscriptionId.isEmpty()) {
            snapshotBuilder.clearSubscriptionRemarksCache()
        }

        reloadJob?.cancel()
        reloadJob = viewModelScope.launch(Dispatchers.Default) {
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
                clearPendingListUpdates()
                updateListAction.value = ServerListUpdate.Full(ServerListUpdate.Full.Reason.RELOAD)
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
        mainServerRepository.removeServer(guid)
        refreshGroupTabs()
        val index = getPosition(guid)
        if (index >= 0) {
            serversCache.removeAt(index)
            rebuildServerPositions()
        }
        if (selectedServerSnapshot?.guid == guid) {
            selectedServerSnapshot = null
            updateConnectionCardAction.postValue(++connectionCardRefreshVersion)
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

        mainServerRepository.saveServerIds(subscriptionId, serverList)
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
        val result = if (subscriptionId.isEmpty()) {
            AngConfigManager.updateConfigViaSubAll()
        } else {
            val subItem = mainServerRepository.getSubscription(subscriptionId) ?: return SubscriptionUpdateResult()
            AngConfigManager.updateConfigViaSub(SubscriptionCache(subscriptionId, subItem))
        }
        if (result.configCount > 0) {
            refreshGroupTabs()
        }
        return result
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
        serverTestCoordinator.cancelTcping()
        tcpingCollectorJob?.cancel()
        val visibleServerGuids = currentVisibleServerGuids()
        mainServerRepository.clearServerDelayResults(visibleServerGuids)
        updateVisibleServerDelays(visibleServerGuids, 0L)
        clearPendingListUpdates()
        updateListAction.value = ServerListUpdate.Full(ServerListUpdate.Full.Reason.GENERAL)

        val targets = buildTcpingTargets()
        if (targets.isEmpty()) {
            return
        }

        val resultChannel = Channel<Pair<String, Long>>(Channel.UNLIMITED)
        tcpingCollectorJob = launchTcpingResultCollector(resultChannel)
        tcpingTestJob = viewModelScope.launch {
            try {
                serverTestCoordinator.runTcping(targets) { result ->
                    mainServerRepository.saveServerDelayMillis(result.guid, result.delayMillis)
                    resultChannel.trySend(result.guid to result.delayMillis)
                }
            } finally {
                resultChannel.close()
                tcpingCollectorJob?.join()
                tcpingCollectorJob = null
            }
        }
    }

    /**
     * Tests the real ping for all servers.
     */
    fun testAllRealPing() {
        serverTestCoordinator.cancelRealPing(getApplication())
        val visibleServerGuids = currentVisibleServerGuids()
        mainServerRepository.clearServerDelayResults(visibleServerGuids)
        updateVisibleServerDelays(visibleServerGuids, 0L)
        clearPendingListUpdates()
        updateListAction.value = ServerListUpdate.Full(ServerListUpdate.Full.Reason.GENERAL)

        viewModelScope.launch(Dispatchers.Default) {
            if (serversCache.isEmpty()) {
                return@launch
            }
            serverTestCoordinator.requestRealPing(
                getApplication(),
                subscriptionId = subscriptionId,
                visibleServerGuids = visibleServerGuids,
                isKeywordFiltering = keywordFilter.isNotEmpty()
            )
        }
    }

    /**
     * Tests the real ping for the current server.
     */
    fun testCurrentServerRealPing() {
        serverTestCoordinator.requestCurrentServerRealPing(getApplication())
    }

    fun testServerTcping(guid: String) {
        val position = getPosition(guid)
        val server = serversCache.getOrNull(position)
        val serverAddress = server?.profile?.server ?: return
        val serverPort = server.profile.serverPort?.toIntOrNull() ?: return

        viewModelScope.launch {
            val result = serverTestCoordinator.testTcping(
                ServerTestTarget(guid, serverAddress, serverPort)
            )
            mainServerRepository.saveServerDelayMillis(result.guid, result.delayMillis)
            updateServerDelay(result.guid, result.delayMillis)
            notifyConnectionCardChangedIfSelected(guid)
            val position = getPosition(guid)
            if (position >= 0) {
                scheduleListUpdate(position)
            }
        }
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
            mainServerRepository.setCachedSubscriptionId(subscriptionId)
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
    fun loadSubscriptions(context: Context, immediate: Boolean = false) {
        subscriptionsJobScheduled?.cancel()
        subscriptionsLoadJob?.cancel()
        subscriptionsJobScheduled = viewModelScope.launch(Dispatchers.Main.immediate) {
            if (!immediate) {
                delay(GROUP_LOAD_DEBOUNCE_MS)
            }
            val appContext = context.applicationContext
            val requestVersion = groupListVersion.incrementAndGet()
            val currentSubscriptionId = subscriptionId

            subscriptionsLoadJob = viewModelScope.launch(Dispatchers.Default) {
                try {
                    val subscriptions = mainServerRepository.getSubscriptions()
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
                            mainServerRepository.setCachedSubscriptionId(subscriptionId)
                            reloadServerList()
                        }
                        updateGroupsAction.value = groups
                    }
                } finally {
                    if (subscriptionsLoadJob === this) {
                        subscriptionsLoadJob = null
                    }
                }
            }
        }
    }

    private fun buildSubscriptions(context: Context, subscriptions: List<SubscriptionCache>): List<GroupMapItem> {
        val subscriptionIds = subscriptions.map { it.guid }
        val serverCountsBySubscriptionId = synchronized(groupCountLock) {
            if (!isGroupCountCacheDirty && cachedGroupIds == subscriptionIds) {
                cachedGroupCounts
            } else {
                val counts = mainServerRepository.getServerCounts(subscriptionIds)
                cachedGroupIds = subscriptionIds
                cachedGroupCounts = counts
                isGroupCountCacheDirty = false
                counts
            }
        }

        val groups = mutableListOf<GroupMapItem>()
        if (subscriptions.size > 1
            && mainServerRepository.isGroupAllDisplayEnabled()
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

    fun renameSubscriptionGroup(groupId: String, remarks: String): Boolean {
        val trimmed = remarks.trim()
        if (groupId.isBlank() || trimmed.isBlank()) return false
        val subscription = mainServerRepository.getSubscription(groupId) ?: return false
        if (subscription.remarks == trimmed) return true
        subscription.remarks = trimmed
        MmkvManager.encodeSubscriptionDirect(groupId, subscription)
        snapshotBuilder.clearSubscriptionRemarksCache()
        refreshGroupTabs(immediate = true)
        reloadServerList(immediate = true)
        return true
    }

    fun prewarmSelectedConfig(guid: String? = mainServerRepository.getSelectedServerId()) {
        val targetGuid = guid?.takeIf { it.isNotBlank() } ?: mainServerRepository.getSelectedServerId()
        if (targetGuid.isNullOrBlank()) {
            return
        }
        val knownProfile = selectedServerSnapshot
            ?.takeIf { it.guid == targetGuid }
            ?.profile
        prewarmJob?.cancel()
        prewarmJob = viewModelScope.launch(Dispatchers.Default) {
            V2rayConfigManager.prewarmConfig(
                context = getApplication<AngApplication>(),
                guid = targetGuid,
                knownProfile = knownProfile
            )
        }
    }

    fun onSelectedServerChanged(guid: String) {
        selectedServerSnapshot = selectedServerForGuid(guid)
        updateConnectionCardAction.postValue(++connectionCardRefreshVersion)
    }

    fun getSelectedServerSnapshot(): ServersCache? = selectedServerSnapshot

    fun refreshGroupTabs(immediate: Boolean = true) {
        invalidateGroupCountCache()
        loadSubscriptions(getApplication<AngApplication>(), immediate = immediate)
    }

    fun countServers(subId: String): Int {
        val cached = synchronized(groupCountLock) {
            if (!isGroupCountCacheDirty) cachedGroupCounts else null
        }
        if (subId.isEmpty()) {
            if (!cached.isNullOrEmpty()) {
                return cached.values.sum()
            }
            return mainServerRepository.getAllServerCount()
        }
        cached?.get(subId)?.let { return it }
        return mainServerRepository.getServerCount(subId)
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

        duplicateGuids.forEach(mainServerRepository::removeServer)
        if (duplicateGuids.isNotEmpty()) {
            refreshGroupTabs()
        }
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
                mainServerRepository.removeAllServers()
            } else {
                val visibleServerGuids = currentVisibleServerGuids()
                visibleServerGuids.forEach(mainServerRepository::removeServer)
                visibleServerGuids.size
            }
        if (count > 0) {
            refreshGroupTabs()
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
            count += mainServerRepository.removeInvalidServer("")
        } else {
            currentVisibleServerGuids().forEach { guid ->
                count += mainServerRepository.removeInvalidServer(guid)
            }
        }
        if (count > 0) {
            refreshGroupTabs()
        }
        return count
    }

    /**
     * Sorts servers by their test results.
     */
    fun sortByTestResults() {
        invalidateServerListRequests()
        if (subscriptionId.isEmpty()) {
            mainServerRepository.getSubscriptionIds().forEach { guid ->
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
        val serverListToSort = mainServerRepository.getServerIds(subId)

        serverListToSort.forEach { key ->
            val delay = mainServerRepository.getServerDelayMillis(key) ?: 0L
            serverDelays.add(ServerDelay(key, if (delay <= 0L) 999999 else delay))
        }
        serverDelays.sortBy { it.testDelayMillis }

        val sortedServerList = serverDelays.map { it.guid }.toMutableList()

        // Save the sorted list for this subscription
        mainServerRepository.saveServerIds(subId, sortedServerList)
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
        val trimmed = keyword.trim()
        if (trimmed == keywordFilter) {
            return
        }
        filterJob?.cancel()
        filterJob = viewModelScope.launch(Dispatchers.Main.immediate) {
            delay(FILTER_DEBOUNCE_MS)
            val latest = trimmed
            if (latest == keywordFilter) {
                return@launch
            }
            keywordFilter = latest
            reloadServerList()
        }
    }

    fun onTestsFinished() {
        viewModelScope.launch(Dispatchers.Default) {
            if (mainServerRepository.isAutoRemoveInvalidAfterTestEnabled()) {
                removeInvalidServer()
            }

            if (mainServerRepository.isAutoSortAfterTestEnabled()) {
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
        if (selectedServerSnapshot?.guid == guid) {
            selectedServerSnapshot = serversCache[position]
        }
    }

    private fun notifyConnectionCardChangedIfSelected(guid: String) {
        if (guid != mainServerRepository.getSelectedServerId()) {
            return
        }
        updateConnectionCardAction.postValue(++connectionCardRefreshVersion)
    }

    private fun saveSelectedServerDelayResult(content: String?) {
        val selectedGuid = mainServerRepository.getSelectedServerId().orEmpty()
        if (selectedGuid.isBlank()) {
            return
        }

        val delayMillis = ServerDelayResultParser.parseDelayResult(content) ?: return

        mainServerRepository.saveServerDelayMillis(selectedGuid, delayMillis)
        updateServerDelay(selectedGuid, delayMillis)
        notifyConnectionCardChangedIfSelected(selectedGuid)
        val position = getPosition(selectedGuid)
        if (position >= 0) {
            scheduleListUpdate(position)
        }
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
                if (selectedServerSnapshot?.guid == guid) {
                    selectedServerSnapshot = serversCache[position]
                }
            }
        }
    }

    private fun launchTcpingResultCollector(channel: Channel<Pair<String, Long>>): Job {
        return viewModelScope.launch(Dispatchers.Main.immediate) {
            val pending = ArrayList<Pair<String, Long>>(TCPING_UI_BATCH_SIZE)
            while (isActive) {
                val first = channel.receiveCatching().getOrNull() ?: break
                pending.add(first)
                while (pending.size < TCPING_UI_BATCH_SIZE) {
                    val next = channel.tryReceive().getOrNull() ?: break
                    pending.add(next)
                }
                if (pending.isEmpty()) continue
                val positionsToUpdate = ArrayList<Int>(pending.size)
                val selectedGuid = mainServerRepository.getSelectedServerId().orEmpty()
                var selectedUpdated = false
                pending.forEach { (guid, delay) ->
                    updateServerDelay(guid, delay)
                    if (!selectedUpdated && selectedGuid.isNotEmpty() && guid == selectedGuid) {
                        updateConnectionCardAction.value = ++connectionCardRefreshVersion
                        selectedUpdated = true
                    }
                    val position = getPosition(guid)
                    if (position >= 0) {
                        positionsToUpdate.add(position)
                    }
                }
                scheduleListUpdate(positionsToUpdate.distinct())
                pending.clear()
            }
        }
    }

    private fun currentVisibleServerGuids(): List<String> {
        return ArrayList<String>(serversCache.size).apply {
            serversCache.forEach { add(it.guid) }
        }
    }

    private fun buildTcpingTargets(): List<ServerTestTarget> {
        return buildList(serversCache.size) {
            serversCache.forEach { item ->
                val serverAddress = item.profile.server ?: return@forEach
                val serverPort = item.profile.serverPort?.toIntOrNull() ?: return@forEach
                add(ServerTestTarget(item.guid, serverAddress, serverPort))
            }
        }
    }

    private fun invalidateServerListRequests() {
        serverListVersion.incrementAndGet()
    }

    private fun invalidateGroupCountCache() {
        synchronized(groupCountLock) {
            cachedGroupIds = emptyList()
            cachedGroupCounts = emptyMap()
            isGroupCountCacheDirty = true
        }
    }

    private fun buildServerListSnapshot(targetSubscriptionId: String, keyword: String): MainServerListSnapshot {
        return snapshotBuilder.build(targetSubscriptionId, keyword)
    }

    private fun applyServerListSnapshot(snapshot: MainServerListSnapshot) {
        serverList = snapshot.serverGuids
        serversCache.clear()
        serversCache.addAll(snapshot.servers)
        serverPositions.clear()
        serverPositions.putAll(snapshot.positions)
        val changed = snapshot.selectedServer != selectedServerSnapshot
        selectedServerSnapshot = snapshot.selectedServer
        if (changed) {
            updateConnectionCardAction.postValue(++connectionCardRefreshVersion)
        }
    }

    private fun selectedServerForGuid(guid: String): ServersCache? {
        if (guid.isBlank()) {
            return null
        }
        serverPositions[guid]?.let(serversCache::getOrNull)?.let { return it }
        return snapshotBuilder.resolveSelectedServerSnapshot(guid, emptyList())
    }

    private fun handleServiceEvent(event: MainServiceEvent) {
        when (event) {
            MainServiceEvent.ServiceRunning -> {
                isRunning.value = true
            }

            MainServiceEvent.ServiceNotRunning -> {
                isRunning.value = false
            }

            is MainServiceEvent.ServiceStarting -> {
                event.messageResId?.let {
                    servicePhaseAction.value = ServicePhaseFeedback(it, ServicePhaseFeedback.State.STARTING)
                }
            }

            MainServiceEvent.ServiceStartSuccess -> {
                serviceFeedbackAction.value = ServiceFeedback(
                    messageResId = R.string.connection_started,
                    style = ServiceFeedback.Style.SUCCESS
                )
                isRunning.value = true
            }

            is MainServiceEvent.ServiceStartFailure -> {
                serviceFeedbackAction.value = ServiceFeedback(
                    messageResId = event.errorResId ?: R.string.connection_start_failed,
                    style = ServiceFeedback.Style.ERROR
                )
                isRunning.value = false
            }

            is MainServiceEvent.ServiceStopping -> {
                event.messageResId?.let {
                    servicePhaseAction.value = ServicePhaseFeedback(it, ServicePhaseFeedback.State.STOPPING)
                }
            }

            MainServiceEvent.ServiceStopSuccess -> {
                serviceFeedbackAction.value = ServiceFeedback(
                    messageResId = R.string.connection_stopped,
                    style = ServiceFeedback.Style.NEUTRAL
                )
                isRunning.value = false
            }

            is MainServiceEvent.DelayTestSuccess -> {
                updateConnectionTestAction.value = event.content
                saveSelectedServerDelayResult(event.content)
            }

            is MainServiceEvent.ConfigTestSuccess -> {
                mainServerRepository.saveServerDelayMillis(event.guid, event.delayMillis)
                updateServerDelay(event.guid, event.delayMillis)
                notifyConnectionCardChangedIfSelected(event.guid)
                val position = getPosition(event.guid)
                if (position >= 0) {
                    scheduleListUpdate(position)
                }
            }

            is MainServiceEvent.ConfigTestNotify -> {
                updateTestResultAction.value =
                    getApplication<AngApplication>().getString(R.string.connection_runing_task_left, event.content)
            }

            is MainServiceEvent.ConfigTestFinish -> {
                if (event.status == "0") {
                    onTestsFinished()
                }
            }
        }
    }

    private val mMsgReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            MainServiceEventInterpreter.interpret(intent)?.let(::handleServiceEvent)
        }
    }
}
