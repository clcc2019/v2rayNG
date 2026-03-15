package com.v2ray.ang.domain.main

import android.content.Intent
import androidx.annotation.StringRes
import com.v2ray.ang.AppConfig
import com.v2ray.ang.extension.serializable
import java.io.Serializable

sealed interface MainServiceEvent : Serializable {
    data object ServiceRunning : MainServiceEvent
    data object ServiceNotRunning : MainServiceEvent
    data object ServiceStartSuccess : MainServiceEvent
    data class ServiceStartFailure(@StringRes val errorResId: Int?) : MainServiceEvent
    data object ServiceStopSuccess : MainServiceEvent
    data class DelayTestSuccess(val content: String?) : MainServiceEvent
    data class ConfigTestSuccess(val guid: String, val delayMillis: Long) : MainServiceEvent
    data class ConfigTestNotify(val content: String?) : MainServiceEvent
    data class ConfigTestFinish(val status: String?) : MainServiceEvent
}

object MainServiceEventInterpreter {
    fun interpret(intent: Intent?): MainServiceEvent? {
        return when (intent?.getIntExtra("key", 0)) {
            AppConfig.MSG_STATE_RUNNING -> MainServiceEvent.ServiceRunning
            AppConfig.MSG_STATE_NOT_RUNNING -> MainServiceEvent.ServiceNotRunning
            AppConfig.MSG_STATE_START_SUCCESS -> MainServiceEvent.ServiceStartSuccess
            AppConfig.MSG_STATE_START_FAILURE -> {
                val errorResId = intent.getIntExtra("content", 0).takeIf { it != 0 }
                MainServiceEvent.ServiceStartFailure(errorResId)
            }
            AppConfig.MSG_STATE_STOP_SUCCESS -> MainServiceEvent.ServiceStopSuccess
            AppConfig.MSG_MEASURE_DELAY_SUCCESS -> {
                MainServiceEvent.DelayTestSuccess(intent.getStringExtra("content"))
            }
            AppConfig.MSG_MEASURE_CONFIG_SUCCESS -> {
                intent.serializable<Pair<String, Long>>("content")?.let { (guid, delayMillis) ->
                    MainServiceEvent.ConfigTestSuccess(guid, delayMillis)
                }
            }
            AppConfig.MSG_MEASURE_CONFIG_NOTIFY -> {
                MainServiceEvent.ConfigTestNotify(intent.getStringExtra("content"))
            }
            AppConfig.MSG_MEASURE_CONFIG_FINISH -> {
                MainServiceEvent.ConfigTestFinish(intent.getStringExtra("content"))
            }
            else -> null
        }
    }
}
