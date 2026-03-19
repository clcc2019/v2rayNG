package com.xray.ang.handler

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.content.ContextCompat
import com.xray.ang.AppConfig
import com.xray.ang.R
import com.xray.ang.contracts.ServiceControl
import com.xray.ang.dto.ConfigResult
import com.xray.ang.dto.ProfileItem
import com.xray.ang.enums.EConfigType
import com.xray.ang.extension.toast
import com.xray.ang.service.V2RayProxyOnlyService
import com.xray.ang.service.V2RayVpnService
import com.xray.ang.util.MessageUtil
import com.xray.ang.util.Utils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import java.lang.ref.SoftReference
import java.util.concurrent.atomic.AtomicBoolean

object V2RayServiceManager {
    private const val RESTART_START_DELAY_MS = 120L
    private const val RESTART_MAX_WAIT_MS = 3_000L
    private const val RESTART_WAIT_INTERVAL_MS = 50L
    private const val RESTART_WAIT_INTERVAL_MAX_MS = 200L
    private const val STOP_TIMEOUT_MS = 5_000L

    private data class PendingRestartRequest(
        val context: Context,
        val guid: String
    )

    private data class PendingStartProfile(
        val guid: String,
        val profile: ProfileItem
    )

    private data class PreparedStartRequest(
        val guid: String,
        val profile: ProfileItem
    )

    private enum class LifecycleState {
        IDLE,
        STARTING,
        RUNNING,
        STOPPING,
        RESTART_PENDING,
        FAILED
    }

    private val coreController: CoreController = V2RayNativeManager.newCoreController(CoreCallback())
    private val mMsgReceive = ReceiveMessageHandler()
    private val restartScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var restartJob: Job? = null
    private var stopTimeoutJob: Job? = null
    private var measureDelayJob: Job? = null
    private var currentConfig: ProfileItem? = null
    private var pendingConfigGuid: String? = null
    private var pendingRestartRequest: PendingRestartRequest? = null
    private val pendingStartProfileLock = Any()
    private var pendingStartProfile: PendingStartProfile? = null
    private val lifecycleLock = Any()
    private val receiverRegistered = AtomicBoolean(false)
    private val stopInProgress = AtomicBoolean(false)
    private var lifecycleState = LifecycleState.IDLE

    var serviceControl: SoftReference<ServiceControl>? = null
        set(value) {
            field = value
            V2RayNativeManager.initCoreEnv(value?.get()?.getService())
        }

    fun bindServiceControl(control: ServiceControl) {
        serviceControl = SoftReference(control)
    }

    /**
     * Starts the V2Ray service from a toggle action.
     * @param context The context from which the service is started.
     * @return True if the service was started successfully, false otherwise.
     */
    fun startVServiceFromToggle(context: Context): Boolean {
        val guid = MmkvManager.getSelectServer()
        if (guid.isNullOrEmpty()) {
            context.toast(R.string.app_tile_first_use)
            return false
        }
        startVService(context, guid)
        return true
    }

    /**
     * Starts the V2Ray service.
     * @param context The context from which the service is started.
     * @param guid The GUID of the server configuration to use (optional).
     */
    fun startVService(context: Context, guid: String? = null) {
        val targetGuid = resolveStartGuid(context, guid) ?: return
        clearPendingRestart()
        if (isServiceTearingDown()) {
            queuePendingRestart(context, targetGuid)
            updateLifecycleState(LifecycleState.RESTART_PENDING)
            sendStartingPhase(context, R.string.connection_restart_queued)
            return
        }
        when (currentLifecycleState()) {
            LifecycleState.STOPPING -> {
                if (hasLiveServiceControl()) {
                    queuePendingRestart(context, targetGuid)
                    updateLifecycleState(LifecycleState.RESTART_PENDING)
                    sendStartingPhase(context, R.string.connection_restart_queued)
                    return
                }
                Log.i(AppConfig.TAG, "Recovering from stale STOPPING state (service process likely dead)")
                resetStaleState()
            }

            LifecycleState.RESTART_PENDING -> {
                if (hasLiveServiceControl()) {
                    queuePendingRestart(context, targetGuid)
                    sendStartingPhase(context, R.string.connection_restart_queued)
                    return
                }
                Log.i(AppConfig.TAG, "Recovering from stale RESTART_PENDING state (service process likely dead)")
                resetStaleState()
            }

            LifecycleState.STARTING -> {
                if (hasLiveServiceControl()) {
                    sendStartingPhase(context, R.string.connection_starting)
                    return
                }
                Log.i(AppConfig.TAG, "Recovering from stale STARTING state (service process likely dead)")
                resetStaleState()
            }

            LifecycleState.RUNNING -> {
                if (hasLiveServiceControl() || coreController.isRunning) {
                    MessageUtil.sendMsg2UI(context, AppConfig.MSG_STATE_RUNNING, "")
                    return
                }
                Log.i(AppConfig.TAG, "Recovering from stale RUNNING state (service process likely dead)")
                resetStaleState()
            }

            LifecycleState.IDLE,
            LifecycleState.FAILED -> {
                clearPendingRestart()
            }
        }
        if (isServiceActive()) {
            queuePendingRestart(context, targetGuid)
            updateLifecycleState(LifecycleState.RESTART_PENDING)
            sendStartingPhase(context, R.string.connection_restart_queued)
            return
        }
        startContextService(context, targetGuid)
    }

    /**
     * Restarts the V2Ray service without a fixed blocking delay.
     * It starts as soon as the core is fully stopped (or timeout reached).
     */
    fun restartVService(context: Context, guid: String? = null) {
        val targetGuid = resolveStartGuid(context, guid) ?: return
        queuePendingRestart(context, targetGuid)
        updateLifecycleState(LifecycleState.RESTART_PENDING)
        sendStartingPhase(context, R.string.connection_restart_queued)
        restartScope.launch(Dispatchers.Default) {
            V2rayConfigManager.prewarmConfig(context.applicationContext, targetGuid)
        }
        if (isServiceActive()) {
            if (hasLiveServiceControl()) {
                requestServiceStop(context.applicationContext)
                return
            }
            Log.i(AppConfig.TAG, "Restart: service appears active but no live control, resetting stale state")
            resetStaleState()
            queuePendingRestart(context, targetGuid)
        }
        startContextService(context, targetGuid)
    }

    /**
     * Stops the V2Ray service.
     * @param context The context from which the service is stopped.
     */
    fun stopVService(context: Context) {
        clearPendingRestart()
        clearPendingStartProfile()
        when (currentLifecycleState()) {
            LifecycleState.IDLE,
            LifecycleState.FAILED -> {
                MessageUtil.sendMsg2UI(context, AppConfig.MSG_STATE_NOT_RUNNING, "")
                return
            }

            LifecycleState.STOPPING -> return
            else -> Unit
        }
        updateLifecycleState(LifecycleState.STOPPING)
        requestServiceStop(context)
        scheduleStopTimeout(context.applicationContext)
    }

    private fun scheduleStopTimeout(context: Context) {
        stopTimeoutJob?.cancel()
        stopTimeoutJob = restartScope.launch {
            delay(STOP_TIMEOUT_MS)
            if (currentLifecycleState() == LifecycleState.STOPPING) {
                Log.w(AppConfig.TAG, "Stop timed out after ${STOP_TIMEOUT_MS}ms, forcing state reset")
                serviceControl = null
                stopInProgress.set(false)
                updateLifecycleState(LifecycleState.IDLE)
                MessageUtil.sendMsg2UI(context, AppConfig.MSG_STATE_STOP_SUCCESS, "")
            }
        }
    }

    private fun requestServiceStop(context: Context) {
        val control = serviceControl?.get()
        if (control != null) {
            Log.i(AppConfig.TAG, "Stop Service directly via serviceControl")
            control.stopService()
            return
        }

        Log.i(AppConfig.TAG, "Stop Service via broadcast fallback")
        MessageUtil.sendMsg2Service(context, AppConfig.MSG_STATE_STOP, "")
    }

    /**
     * Checks if the V2Ray service is running.
     * @return True if the service is running, false otherwise.
     */
    fun isRunning() = coreController.isRunning

    /**
     * Gets the name of the currently running server.
     * @return The name of the running server.
     */
    fun getRunningServerName() = currentConfig?.remarks.orEmpty()

    /**
     * Starts the context service for V2Ray.
     * Chooses between VPN service or Proxy-only service based on user settings.
     * @param context The context from which the service is started.
     */
    private fun startContextService(context: Context, guid: String) {
        if (isServiceTearingDown()) {
            queuePendingRestart(context, guid)
            updateLifecycleState(LifecycleState.RESTART_PENDING)
            sendStartingPhase(context, R.string.connection_restart_queued)
            return
        }
        if (isServiceActive()) {
            if (coreController.isRunning) {
                MessageUtil.sendMsg2UI(context, AppConfig.MSG_STATE_RUNNING, "")
            } else {
                queuePendingRestart(context, guid)
                updateLifecycleState(LifecycleState.RESTART_PENDING)
                sendStartingPhase(context, R.string.connection_restart_queued)
            }
            return
        }

        val request = prepareStartRequest(guid)
        if (request == null) {
            abortStartRequest(context)
            return
        }

        pendingConfigGuid = request.guid
        synchronized(pendingStartProfileLock) {
            pendingStartProfile = PendingStartProfile(request.guid, request.profile)
        }
        MmkvManager.setSelectServer(request.guid)
        updateLifecycleState(LifecycleState.STARTING)
        val intent = if (SettingsManager.isVpnMode()) {
            Intent(context.applicationContext, V2RayVpnService::class.java)
        } else {
            Intent(context.applicationContext, V2RayProxyOnlyService::class.java)
        }
        try {
            clearPendingRestart()
            ContextCompat.startForegroundService(context, intent)
        } catch (e: Exception) {
            updateLifecycleState(LifecycleState.FAILED)
            clearPendingRestart()
            clearPendingStartProfile()
            Log.e(AppConfig.TAG, "Failed to start foreground service", e)
            abortStartRequest(context)
        }
    }

    fun consumePendingStartProfile(guid: String): ProfileItem? {
        synchronized(pendingStartProfileLock) {
            val pending = pendingStartProfile ?: return null
            if (pending.guid != guid) {
                pendingStartProfile = null
                pendingConfigGuid = null
                return null
            }
            pendingStartProfile = null
            return pending.profile.copy()
        }
    }

    fun onServiceStopCompleted() {
        stopTimeoutJob?.cancel()
        stopTimeoutJob = null
        currentConfig = null
        pendingConfigGuid = null
        updateLifecycleState(LifecycleState.STOPPING)
    }

    fun onServiceDestroyed(control: ServiceControl) {
        stopTimeoutJob?.cancel()
        stopTimeoutJob = null
        if (serviceControl?.get() === control) {
            serviceControl = null
        }
        currentConfig = null
        updateLifecycleState(LifecycleState.IDLE)
        schedulePendingRestart()
    }

    fun shouldTerminateDaemonProcess(): Boolean {
        return pendingRestartRequest == null && isServiceActive().not()
    }

    /**
     * Refer to the official documentation for [registerReceiver](https://developer.android.com/reference/androidx/core/content/ContextCompat#registerReceiver(android.content.Context,android.content.BroadcastReceiver,android.content.IntentFilter,int):
     * `registerReceiver(Context, BroadcastReceiver, IntentFilter, int)`.
     * Starts the V2Ray core service.
     */
    fun startCoreLoop(vpnInterface: ParcelFileDescriptor?, prebuiltConfig: ConfigResult? = null): Boolean {
        if (coreController.isRunning) {
            return false
        }

        stopInProgress.set(false)
        updateLifecycleState(LifecycleState.STARTING)

        val service = getService() ?: return false
        val guid = prebuiltConfig?.guid ?: pendingConfigGuid ?: MmkvManager.getSelectServer()
        if (guid.isNullOrEmpty()) {
            MessageUtil.sendMsg2UI(service, AppConfig.MSG_STATE_START_FAILURE, "")
            return false
        }
        val config = prebuiltConfig?.profile?.takeIf { prebuiltConfig.guid == guid } ?: MmkvManager.decodeServerConfig(guid)
        if (config == null) {
            MessageUtil.sendMsg2UI(service, AppConfig.MSG_STATE_START_FAILURE, "")
            return false
        }

        val configStartAt = SystemClock.elapsedRealtime()
        val result = if (prebuiltConfig != null
            && prebuiltConfig.status
            && prebuiltConfig.guid == guid
            && prebuiltConfig.content.isNotBlank()
        ) {
            prebuiltConfig
        } else {
            V2rayConfigManager.getV2rayConfig(service, guid)
        }
        val configElapsed = SystemClock.elapsedRealtime() - configStartAt
        if (!result.status) {
            val errorResId = result.errorResId ?: 0
            MessageUtil.sendMsg2UI(
                service,
                AppConfig.MSG_STATE_START_FAILURE,
                if (errorResId > 0) errorResId else ""
            )
            return false
        }
        Log.i(AppConfig.TAG, "V2Ray config build finished in ${configElapsed}ms")

        try {
            val mFilter = IntentFilter(AppConfig.BROADCAST_ACTION_SERVICE)
            mFilter.addAction(Intent.ACTION_SCREEN_ON)
            mFilter.addAction(Intent.ACTION_SCREEN_OFF)
            mFilter.addAction(Intent.ACTION_USER_PRESENT)
            ContextCompat.registerReceiver(service, mMsgReceive, mFilter, Utils.receiverFlags())
            receiverRegistered.set(true)
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to register broadcast receiver", e)
            MessageUtil.sendMsg2UI(service, AppConfig.MSG_STATE_START_FAILURE, "")
            return false
        }

        currentConfig = result.profile ?: config
        pendingConfigGuid = null
        val tunFd = 0

        try {
            val loopStartAt = SystemClock.elapsedRealtime()
            coreController.startLoop(result.content, tunFd)
            Log.i(AppConfig.TAG, "V2Ray core startLoop finished in ${SystemClock.elapsedRealtime() - loopStartAt}ms")
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to start Core loop", e)
            MessageUtil.sendMsg2UI(service, AppConfig.MSG_STATE_START_FAILURE, "")
            updateLifecycleState(LifecycleState.FAILED)
            cleanupStartArtifacts(service)
            return false
        }

        if (coreController.isRunning == false) {
            MessageUtil.sendMsg2UI(service, AppConfig.MSG_STATE_START_FAILURE, "")
            updateLifecycleState(LifecycleState.FAILED)
            cleanupStartArtifacts(service)
            return false
        }

        try {
            updateLifecycleState(LifecycleState.RUNNING)
            MessageUtil.sendMsg2UI(service, AppConfig.MSG_STATE_START_SUCCESS, "")
            NotificationManager.showNotification(currentConfig)
            NotificationManager.startSpeedNotification(currentConfig)
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to startup service", e)
            return false
        }
        return true
    }

    /**
     * Stops the V2Ray core service.
     * Unregisters broadcast receivers, stops notifications, and shuts down plugins.
     * @return True if the core was stopped successfully, false otherwise.
     */
    fun stopCoreLoop(): Boolean {
        val service = getService() ?: return false

        if (!stopInProgress.compareAndSet(false, true)) {
            Log.d(AppConfig.TAG, "V2Ray core stop already in progress, skipping duplicate request")
            return true
        }

        val startAt = SystemClock.elapsedRealtime()
        updateLifecycleState(LifecycleState.STOPPING)

        try {
            measureDelayJob?.cancel()
            measureDelayJob = null
            if (coreController.isRunning) {
                try {
                    coreController.stopLoop()
                } catch (e: Exception) {
                    Log.e(AppConfig.TAG, "Failed to stop V2Ray loop", e)
                }
            }

            NotificationManager.cancelNotification()
            unregisterMessageReceiver(service)
            Log.i(AppConfig.TAG, "V2Ray core stop finished in ${SystemClock.elapsedRealtime() - startAt}ms")
            return true
        } finally {
            stopInProgress.set(false)
        }
    }

    /**
     * Queries the statistics for a given tag and link.
     * @param tag The tag to query.
     * @param link The link to query.
     * @return The statistics value.
     */
    fun queryStats(tag: String, link: String): Long {
        return coreController.queryStats(tag, link)
    }

    /**
     * Measures the connection delay for the current V2Ray configuration.
     * Tests with primary URL first, then falls back to alternative URL if needed.
     * Also fetches remote IP information if the delay test was successful.
     */
    private fun measureV2rayDelay() {
        if (coreController.isRunning == false) {
            return
        }

        if (measureDelayJob?.isActive == true) {
            return
        }

        measureDelayJob = restartScope.launch(Dispatchers.IO) {
            val service = getService() ?: return@launch
            var time = -1L
            var errorStr = ""

            try {
                time = coreController.measureDelay(SettingsManager.getDelayTestUrl())
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "Failed to measure delay with primary URL", e)
                errorStr = e.message?.substringAfter("\":") ?: "empty message"
            }
            if (time == -1L) {
                try {
                    time = coreController.measureDelay(SettingsManager.getDelayTestUrl(true))
                } catch (e: Exception) {
                    Log.e(AppConfig.TAG, "Failed to measure delay with alternative URL", e)
                    errorStr = e.message?.substringAfter("\":") ?: "empty message"
                }
            }

            val result = if (time >= 0) {
                service.getString(R.string.connection_test_available, time)
            } else {
                service.getString(R.string.connection_test_error, errorStr)
            }
            MessageUtil.sendMsg2UI(service, AppConfig.MSG_MEASURE_DELAY_SUCCESS, result)

            // Only fetch IP info if the delay test was successful
            if (time >= 0) {
                SpeedtestManager.getRemoteIPInfo()?.let { ip ->
                    MessageUtil.sendMsg2UI(service, AppConfig.MSG_MEASURE_DELAY_SUCCESS, "$result\n$ip")
                }
            }
        }
    }

    /**
     * Gets the current service instance.
     * @return The current service instance, or null if not available.
     */
    private fun getService(): Service? {
        return serviceControl?.get()?.getService()
    }

    /**
     * Core callback handler implementation for handling V2Ray core events.
     * Handles startup, shutdown, socket protection, and status emission.
     */
    private class CoreCallback : CoreCallbackHandler {
        /**
         * Called when V2Ray core starts up.
         * @return 0 for success, any other value for failure.
         */
        override fun startup(): Long {
            return 0
        }

        /**
         * Called when V2Ray core shuts down.
         * @return 0 for success, any other value for failure.
         */
        override fun shutdown(): Long {
            val serviceControl = serviceControl?.get() ?: return -1
            return try {
                serviceControl.stopService()
                0
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "Failed to stop service in callback", e)
                -1
            }
        }

        /**
         * Called when V2Ray core emits status information.
         * @param l Status code.
         * @param s Status message.
         * @return Always returns 0.
         */
        override fun onEmitStatus(l: Long, s: String?): Long {
            return 0
        }
    }

    /**
     * Broadcast receiver for handling messages sent to the service.
     * Handles registration, service control, and screen events.
     */
    private class ReceiveMessageHandler : BroadcastReceiver() {
        /**
         * Handles received broadcast messages.
         * Processes service control messages and screen state changes.
         * @param ctx The context in which the receiver is running.
         * @param intent The intent being received.
         */
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val serviceControl = serviceControl?.get() ?: return
            when (intent?.getIntExtra("key", 0)) {
                AppConfig.MSG_REGISTER_CLIENT -> {
                    if (coreController.isRunning) {
                        MessageUtil.sendMsg2UI(serviceControl.getService(), AppConfig.MSG_STATE_RUNNING, "")
                    } else {
                        MessageUtil.sendMsg2UI(serviceControl.getService(), AppConfig.MSG_STATE_NOT_RUNNING, "")
                    }
                }

                AppConfig.MSG_UNREGISTER_CLIENT -> {
                    // nothing to do
                }

                AppConfig.MSG_STATE_START -> {
                    // nothing to do
                }

                AppConfig.MSG_STATE_STOP -> {
                    Log.i(AppConfig.TAG, "Stop Service")
                    serviceControl.stopService()
                }

                AppConfig.MSG_STATE_RESTART -> {
                    Log.i(AppConfig.TAG, "Restart Service")
                    restartVService(serviceControl.getService())
                }

                AppConfig.MSG_MEASURE_DELAY -> {
                    measureV2rayDelay()
                }
            }

            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    Log.i(AppConfig.TAG, "SCREEN_OFF, stop querying stats")
                    NotificationManager.stopSpeedNotification(currentConfig)
                }

                Intent.ACTION_SCREEN_ON -> {
                    Log.i(AppConfig.TAG, "SCREEN_ON, start querying stats")
                    NotificationManager.startSpeedNotification(currentConfig)
                }
            }
        }
    }

    private fun cleanupStartArtifacts(service: Service) {
        NotificationManager.cancelNotification()
        unregisterMessageReceiver(service)
    }

    fun sendStartingPhase(service: Service, messageResId: Int) {
        updateLifecycleState(
            if (currentLifecycleState() == LifecycleState.RESTART_PENDING) LifecycleState.RESTART_PENDING else LifecycleState.STARTING
        )
        MessageUtil.sendMsg2UI(service, AppConfig.MSG_STATE_STARTING, messageResId)
    }

    fun sendStartingPhase(context: Context, messageResId: Int) {
        MessageUtil.sendMsg2UI(context, AppConfig.MSG_STATE_STARTING, messageResId)
    }

    fun sendStoppingPhase(service: Service, messageResId: Int) {
        updateLifecycleState(LifecycleState.STOPPING)
        MessageUtil.sendMsg2UI(service, AppConfig.MSG_STATE_STOPPING, messageResId)
    }

    fun sendStopSuccess(service: Service) {
        MessageUtil.sendMsg2UI(service, AppConfig.MSG_STATE_STOP_SUCCESS, "")
    }

    private fun resolveStartGuid(context: Context, guid: String?): String? {
        val targetGuid = guid ?: MmkvManager.getSelectServer()
        if (!targetGuid.isNullOrEmpty()) {
            return targetGuid
        }

        MessageUtil.sendMsg2UI(context, AppConfig.MSG_STATE_START_FAILURE, "")
        return null
    }

    private fun prepareStartRequest(guid: String): PreparedStartRequest? {
        val profile = MmkvManager.decodeServerConfig(guid) ?: return null
        if (!isStartableProfile(profile)) {
            return null
        }
        return PreparedStartRequest(guid, profile.copy())
    }

    private fun isStartableProfile(profile: ProfileItem): Boolean {
        return profile.configType == EConfigType.CUSTOM
            || profile.configType == EConfigType.POLICYGROUP
            || Utils.isValidUrl(profile.server)
            || Utils.isPureIpAddress(profile.server.orEmpty())
    }

    private fun abortStartRequest(context: Context) {
        clearPendingRestart()
        clearPendingStartProfile()
        pendingConfigGuid = null
        MessageUtil.sendMsg2UI(context, AppConfig.MSG_STATE_START_FAILURE, "")
    }

    private fun unregisterMessageReceiver(service: Service) {
        if (!receiverRegistered.compareAndSet(true, false)) {
            return
        }

        try {
            service.unregisterReceiver(mMsgReceive)
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to unregister broadcast receiver", e)
        }
    }

    private fun queuePendingRestart(context: Context, guid: String) {
        pendingRestartRequest = PendingRestartRequest(context.applicationContext, guid)
        pendingConfigGuid = guid
        MmkvManager.setSelectServer(guid)
    }

    private fun clearPendingRestart() {
        restartJob?.cancel()
        restartJob = null
        pendingRestartRequest = null
    }

    private fun clearPendingStartProfile() {
        synchronized(pendingStartProfileLock) {
            pendingStartProfile = null
        }
        pendingConfigGuid = null
    }

    private fun schedulePendingRestart() {
        val request = pendingRestartRequest ?: return
        restartJob?.cancel()
        restartJob = restartScope.launch {
            delay(RESTART_START_DELAY_MS)
            val deadline = SystemClock.elapsedRealtime() + RESTART_MAX_WAIT_MS
            var interval = RESTART_WAIT_INTERVAL_MS
            while (isActive && isServiceActive() && SystemClock.elapsedRealtime() < deadline) {
                delay(interval)
                interval = (interval * 2).coerceAtMost(RESTART_WAIT_INTERVAL_MAX_MS)
            }
            if (!isActive || pendingRestartRequest != request) {
                return@launch
            }
            if (isServiceActive()) {
                Log.w(AppConfig.TAG, "Pending restart timed out, forcing stale reference cleanup")
                serviceControl = null
                stopInProgress.set(false)
                if (isServiceActive()) {
                    clearPendingRestart()
                    updateLifecycleState(LifecycleState.FAILED)
                    MessageUtil.sendMsg2UI(request.context, AppConfig.MSG_STATE_START_FAILURE, "")
                    Log.w(AppConfig.TAG, "Pending restart abandoned after forced cleanup")
                    return@launch
                }
            }
            startContextService(request.context, request.guid)
        }
    }

    private fun isServiceActive(): Boolean {
        val lifecycleActive = currentLifecycleState() != LifecycleState.IDLE
            && currentLifecycleState() != LifecycleState.FAILED
        return lifecycleActive
            || coreController.isRunning
            || serviceControl?.get() != null
            || stopInProgress.get()
    }

    private fun currentLifecycleState(): LifecycleState {
        return synchronized(lifecycleLock) {
            lifecycleState
        }
    }

    private fun updateLifecycleState(newState: LifecycleState) {
        synchronized(lifecycleLock) {
            lifecycleState = newState
        }
    }

    private fun hasLiveServiceControl(): Boolean {
        return serviceControl?.get() != null
    }

    private fun resetStaleState() {
        stopTimeoutJob?.cancel()
        stopTimeoutJob = null
        serviceControl = null
        stopInProgress.set(false)
        clearPendingRestart()
        clearPendingStartProfile()
        currentConfig = null
        pendingConfigGuid = null
        updateLifecycleState(LifecycleState.IDLE)
    }

    private fun isServiceTearingDown(): Boolean {
        return coreController.isRunning == false && serviceControl?.get() != null
    }
}
