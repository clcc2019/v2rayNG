package com.v2ray.ang.service

import android.content.Context
import android.util.Log
import com.v2ray.ang.AppConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ProcessService {
    private var process: Process? = null
    private val processScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var waitJob: Job? = null

    /**
     * Runs a process with the given command.
     * @param context The context.
     * @param cmd The command to run.
     */
    fun runProcess(context: Context, cmd: MutableList<String>) {
        Log.i(AppConfig.TAG, cmd.toString())

        try {
            val proBuilder = ProcessBuilder(cmd)
            proBuilder.redirectErrorStream(true)
            process = proBuilder
                .directory(context.filesDir)
                .start()

            val runningProcess = process
            waitJob?.cancel()
            waitJob = processScope.launch {
                delay(50L)
                Log.i(AppConfig.TAG, "runProcess check")
                runningProcess?.waitFor()
                Log.i(AppConfig.TAG, "runProcess exited")
                if (process === runningProcess) {
                    process = null
                }
            }
            Log.i(AppConfig.TAG, process.toString())

        } catch (e: Exception) {
            Log.e(AppConfig.TAG, e.toString(), e)
        }
    }

    /**
     * Stops the running process.
     */
    fun stopProcess() {
        try {
            Log.i(AppConfig.TAG, "runProcess destroy")
            waitJob?.cancel()
            waitJob = null
            process?.destroy()
            process = null
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to destroy process", e)
        }
    }
}
