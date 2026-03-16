package com.xray.ang.service

import android.content.Context
import android.util.Log
import com.xray.ang.AppConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader

class ProcessService {
    companion object {
        private const val MAX_PROCESS_LOG_LINES = 50
        private const val MAX_PROCESS_LOG_LINE_LENGTH = 300
    }

    private var process: Process? = null
    private val processScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var waitJob: Job? = null
    private var outputJob: Job? = null

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
            outputJob?.cancel()
            outputJob = processScope.launch {
                try {
                    val reader = BufferedReader(InputStreamReader(runningProcess?.inputStream))
                    reader.useLines { lines ->
                        var logged = 0
                        lines.forEach { line ->
                            if (logged < MAX_PROCESS_LOG_LINES) {
                                val preview = if (line.length > MAX_PROCESS_LOG_LINE_LENGTH) {
                                    line.substring(0, MAX_PROCESS_LOG_LINE_LENGTH) + "..."
                                } else {
                                    line
                                }
                                Log.d(AppConfig.TAG, "process> $preview")
                                logged++
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(AppConfig.TAG, "Failed to drain process output", e)
                }
            }
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
            outputJob?.cancel()
            outputJob = null
            process?.destroy()
            if (process?.isAlive == true) {
                process?.destroyForcibly()
            }
            process = null
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to destroy process", e)
        }
    }
}
