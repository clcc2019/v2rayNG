package com.xray.ang.handler

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.xray.ang.AppConfig
import com.xray.ang.R
import com.xray.ang.dto.IPAPIInfo
import com.xray.ang.util.HttpUtil
import com.xray.ang.util.JsonUtil
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnknownHostException

object SpeedtestManager {
    data class TcpingOptions(
        val attempts: Int = DEFAULT_TCPING_ATTEMPTS,
        val connectTimeoutMillis: Int = DEFAULT_TCPING_CONNECT_TIMEOUT_MS
    )

    val INTERACTIVE_TCPING_OPTIONS = TcpingOptions(
        attempts = 1,
        connectTimeoutMillis = 1500
    )

    private const val DEFAULT_TCPING_ATTEMPTS = 2
    private const val DEFAULT_TCPING_CONNECT_TIMEOUT_MS = 3000

    private val tcpTestingSockets = ArrayList<Socket?>()

    /**
     * Measures the TCP connection time to a given URL and port.
     *
     * @param url The URL to connect to.
     * @param port The port to connect to.
     * @return The connection time in milliseconds, or -1 if the connection failed.
     */
    suspend fun tcping(url: String, port: Int): Long {
        return tcping(url, port, TcpingOptions())
    }

    suspend fun tcping(url: String, port: Int, options: TcpingOptions): Long {
        var time = -1L
        val attempts = options.attempts.coerceAtLeast(1)
        val connectTimeoutMillis = options.connectTimeoutMillis.coerceAtLeast(1)
        for (attempt in 0 until attempts) {
            val one = socketConnectTime(url, port, connectTimeoutMillis)
            if (!currentCoroutineContext().isActive) {
                break
            }
            if (one != -1L && (time == -1L || one < time)) {
                time = one
            }
        }
        return time
    }

    /**
     * Measures the time taken to establish a TCP connection to a given URL and port.
     *
     * @param url The URL to connect to.
     * @param port The port to connect to.
     * @return The connection time in milliseconds, or -1 if the connection failed.
     */
    fun socketConnectTime(
        url: String,
        port: Int,
        connectTimeoutMillis: Int = DEFAULT_TCPING_CONNECT_TIMEOUT_MS
    ): Long {
        val socket = Socket()
        try {
            synchronized(this) {
                tcpTestingSockets.add(socket)
            }
            val start = SystemClock.elapsedRealtime()
            socket.connect(InetSocketAddress(url, port), connectTimeoutMillis)
            return SystemClock.elapsedRealtime() - start
        } catch (e: UnknownHostException) {
            Log.e(AppConfig.TAG, "Unknown host: $url", e)
        } catch (e: IOException) {
            Log.e(AppConfig.TAG, "socketConnectTime IOException: $e")
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to establish socket connection to $url:$port", e)
        } finally {
            synchronized(this) {
                tcpTestingSockets.remove(socket)
            }
            runCatching {
                socket.close()
            }
        }
        return -1
    }

    /**
     * Closes all TCP sockets that are currently being tested.
     */
    fun closeAllTcpSockets() {
        synchronized(this) {
            tcpTestingSockets.forEach {
                it?.close()
            }
            tcpTestingSockets.clear()
        }
    }

    /**
     * Tests the connection to a given URL and port.
     *
     * @param context The Context in which the test is running.
     * @param port The port to connect to.
     * @return A pair containing the elapsed time in milliseconds and the result message.
     */
    fun testConnection(context: Context, port: Int): Pair<Long, String> {
        var result: String
        var elapsed = -1L

        val conn = HttpUtil.createProxyConnection(SettingsManager.getDelayTestUrl(), port, 15000, 15000) ?: return Pair(elapsed, "")
        try {
            val start = SystemClock.elapsedRealtime()
            val code = conn.responseCode
            elapsed = SystemClock.elapsedRealtime() - start

            result = when (code) {
                204 -> context.getString(R.string.connection_test_available, elapsed)
                200 if conn.contentLengthLong == 0L -> context.getString(R.string.connection_test_available, elapsed)
                else -> throw IOException(
                    context.getString(R.string.connection_test_error_status_code, code)
                )
            }
        } catch (e: IOException) {
            Log.e(AppConfig.TAG, "Connection test IOException", e)
            result = context.getString(R.string.connection_test_error, e.message)
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Connection test Exception", e)
            result = context.getString(R.string.connection_test_error, e.message)
        } finally {
            conn.disconnect()
        }

        return Pair(elapsed, result)
    }

    fun getRemoteIPInfo(): String? {
        val url = MmkvManager.decodeSettingsString(AppConfig.PREF_IP_API_URL)
            .takeIf { !it.isNullOrBlank() } ?: AppConfig.IP_API_URL

        val httpPort = SettingsManager.getHttpPort()
        val content = HttpUtil.getUrlContent(url, 5000, httpPort) ?: return null
        return parseRemoteIPInfo(content)
    }

    internal fun parseRemoteIPInfo(content: String): String? {
        parseCloudflareTrace(content)?.let { trace ->
            val ip = trace["ip"]?.takeIf { it.isNotBlank() } ?: return null
            val location = listOfNotNull(
                trace["loc"]?.takeIf { it.isNotBlank() },
                trace["colo"]?.takeIf { it.isNotBlank() }
            ).joinToString(" ")
            val details = listOfNotNull(
                trace["http"]?.takeIf { it.isNotBlank() }?.let { "http=$it" },
                trace["tls"]?.takeIf { it.isNotBlank() }?.let { "tls=$it" }
            )
            val locationPart = "(${location.ifBlank { "unknown" }}) $ip"
            return if (details.isEmpty()) {
                locationPart
            } else {
                "$locationPart · ${details.joinToString(" · ")}"
            }
        }

        return parseLegacyJsonIpInfo(content)
    }

    private fun parseCloudflareTrace(content: String): Map<String, String>? {
        val trace = buildMap {
            content.lineSequence().forEach { line ->
                val separatorIndex = line.indexOf('=')
                if (separatorIndex <= 0) return@forEach
                val key = line.substring(0, separatorIndex).trim()
                val value = line.substring(separatorIndex + 1).trim()
                if (key.isNotEmpty() && value.isNotEmpty()) {
                    put(key, value)
                }
            }
        }
        return trace.takeIf { !it["ip"].isNullOrBlank() }
    }

    private fun parseLegacyJsonIpInfo(content: String): String? {
        val ipInfo = runCatching {
            JsonUtil.fromJson(content, IPAPIInfo::class.java)
        }.getOrNull() ?: return null

        val ip = listOf(
            ipInfo.ip,
            ipInfo.clientIp,
            ipInfo.ip_addr,
            ipInfo.query
        ).firstOrNull { !it.isNullOrBlank() }

        val country = listOf(
            ipInfo.country_code,
            ipInfo.country,
            ipInfo.countryCode,
            ipInfo.location?.country_code
        ).firstOrNull { !it.isNullOrBlank() }

        return "(${country ?: "unknown"}) ${ip ?: "unknown"}"
    }
}
