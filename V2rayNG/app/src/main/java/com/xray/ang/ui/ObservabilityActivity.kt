package com.xray.ang.ui

import android.os.Bundle
import android.os.SystemClock
import androidx.lifecycle.lifecycleScope
import com.google.gson.JsonObject
import com.xray.ang.AppConfig
import com.xray.ang.R
import com.xray.ang.databinding.ActivityObservabilityBinding
import com.xray.ang.handler.MmkvManager
import com.xray.ang.util.JsonUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale

class ObservabilityActivity : BaseActivity() {
    private val binding by lazy { ActivityObservabilityBinding.inflate(layoutInflater) }
    private var metricsJob: Job? = null
    private var lastTotals: Pair<Long, Long>? = null
    private var lastSampleElapsed: Long = 0L
    private var lastSampleWall: Long = 0L
    private val history = ArrayList<TrafficSample>()
    private var lastRateUp: Long = 0L
    private var lastRateDown: Long = 0L
    private var bucketStartAt: Long = 0L
    private var bucketUpSum: Long = 0L
    private var bucketDownSum: Long = 0L
    private var bucketCount: Long = 0L
    private var selectedRange: Range = Range.ONE_HOUR

    private data class TrafficSample(
        val timestamp: Long,
        val upRate: Long,
        val downRate: Long
    )

    private enum class Range(val durationMs: Long, val maxPoints: Int) {
        ONE_HOUR(60L * 60 * 1000, 60),
        SIX_HOURS(6L * 60 * 60 * 1000, 72),
        ONE_DAY(24L * 60 * 60 * 1000, 96),
        SEVEN_DAYS(7L * 24 * 60 * 60 * 1000, 120)
    }

    private val maxHistoryMs = 7L * 24 * 60 * 60 * 1000
    private val bucketDurationMs = 30L * 1000
    private val timeFormatShort by lazy { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    private val timeFormatLong by lazy { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    private val defaultSubtitle by lazy { getString(R.string.observability_traffic_subtitle) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentViewWithToolbar(binding.root, showHomeAsUp = true, title = getString(R.string.title_observability))
        initMetricsToggle()
        initRangeChips()
        loadHistory()
        renderChart()
        renderTrafficValues()
        binding.chartTraffic.setOnPointFocusListener { point ->
            binding.tvTrafficSubtitle.text = buildPointSubtitle(point)
        }
        postScreenContentEnterMotion(binding.root)
    }

    override fun onStart() {
        super.onStart()
        updateMetricsPolling()
    }

    override fun onStop() {
        super.onStop()
        metricsJob?.cancel()
        metricsJob = null
    }

    private fun initMetricsToggle() {
        binding.switchMetrics.isChecked = MmkvManager.decodeSettingsBool(AppConfig.PREF_METRICS_ENABLED, false)
        binding.switchMetrics.setOnCheckedChangeListener { _, isChecked ->
            MmkvManager.encodeSettings(AppConfig.PREF_METRICS_ENABLED, isChecked)
            updateMetricsPolling()
        }
        binding.rowMetrics.setOnClickListener {
            binding.switchMetrics.isChecked = !binding.switchMetrics.isChecked
        }
    }

    private fun updateMetricsPolling() {
        val enabled = binding.switchMetrics.isChecked
        if (!enabled) {
            metricsJob?.cancel()
            metricsJob = null
            resetTrafficChart()
            return
        }
        if (metricsJob?.isActive == true) {
            return
        }
        metricsJob = lifecycleScope.launchWhenStarted {
            while (isActive) {
                val sampleElapsed = SystemClock.elapsedRealtime()
                val sampleWall = System.currentTimeMillis()
                val totals = withContext(Dispatchers.IO) { fetchTrafficTotals() }
                if (totals != null) {
                    val previousTotals = lastTotals
                    if (previousTotals != null) {
                        val elapsedMs = sampleElapsed - lastSampleElapsed
                        val totalsReset = totals.first < previousTotals.first || totals.second < previousTotals.second
                        val gapTooLarge = elapsedMs > 10_000L
                        if (elapsedMs <= 0L || gapTooLarge || totalsReset) {
                            lastTotals = totals
                            lastSampleElapsed = sampleElapsed
                            lastSampleWall = sampleWall
                            lastRateUp = 0L
                            lastRateDown = 0L
                            resetBucket()
                            renderTrafficValues()
                            continue
                        }
                        val safeElapsed = elapsedMs.coerceAtLeast(300L)
                        val deltaUp = (totals.first - previousTotals.first).coerceAtLeast(0L)
                        val deltaDown = (totals.second - previousTotals.second).coerceAtLeast(0L)
                        val rateUp = (deltaUp * 1000L) / safeElapsed
                        val rateDown = (deltaDown * 1000L) / safeElapsed
                        lastRateUp = rateUp
                        lastRateDown = rateDown
                        lastSampleWall = sampleWall
                        accumulateSample(sampleWall, rateUp, rateDown)
                        renderTrafficValues()
                    }
                    lastTotals = totals
                    lastSampleElapsed = sampleElapsed
                }
                delay(1200L)
            }
        }
    }

    private suspend fun fetchTrafficTotals(): Pair<Long, Long>? {
        val url = URL("http://${AppConfig.METRICS_LISTEN_DEFAULT}/debug/vars")
        val conn = (url.openConnection() as? HttpURLConnection) ?: return null
        return try {
            conn.connectTimeout = 1200
            conn.readTimeout = 1200
            conn.requestMethod = "GET"
            val body = conn.inputStream.bufferedReader().use(BufferedReader::readText)
            val json = JsonUtil.parseString(body) ?: return null
            parseStats(json)
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun parseStats(json: JsonObject): Pair<Long, Long>? {
        val stats = json.getAsJsonObject("stats") ?: return null
        var uplink = 0L
        var downlink = 0L
        fun visit(obj: JsonObject) {
            for ((key, value) in obj.entrySet()) {
                if (value.isJsonObject) {
                    visit(value.asJsonObject)
                    continue
                }
                if (!value.isJsonPrimitive) continue
                val number = value.asJsonPrimitive.asLongOrNull() ?: continue
                when (key) {
                    "uplink" -> uplink += number
                    "downlink" -> downlink += number
                }
            }
        }
        visit(stats)
        return Pair(uplink, downlink)
    }

    private fun accumulateSample(sampleAt: Long, rateUp: Long, rateDown: Long) {
        if (bucketStartAt == 0L) {
            bucketStartAt = sampleAt
        }
        bucketUpSum += rateUp
        bucketDownSum += rateDown
        bucketCount += 1
        val avgUp = if (bucketCount > 0) bucketUpSum / bucketCount else 0L
        val avgDown = if (bucketCount > 0) bucketDownSum / bucketCount else 0L
        if (sampleAt - bucketStartAt >= bucketDurationMs) {
            history.add(TrafficSample(sampleAt, avgUp, avgDown))
            trimHistory()
            persistHistory()
            bucketStartAt = sampleAt
            bucketUpSum = 0L
            bucketDownSum = 0L
            bucketCount = 0L
        }
        renderChart()
    }

    private fun renderChart() {
        val now = System.currentTimeMillis()
        val rangeStart = now - selectedRange.durationMs
        val inProgress = currentBucketSample()
        val combined = if (inProgress != null) history + inProgress else history
        val rangeSamples = combined.filter { it.timestamp >= rangeStart }
        if (rangeSamples.isEmpty()) {
            binding.chartTraffic.setSeries(emptyList())
            return
        }
        val downsampled = downsampleSamples(rangeSamples, selectedRange.maxPoints)
        binding.chartTraffic.setSeries(
            downsampled.map {
                com.xray.ang.ui.widget.TrafficLineChartView.TrafficPoint(
                    timestamp = it.timestamp,
                    upRate = it.upRate,
                    downRate = it.downRate
                )
            }
        )
    }

    private fun resetTrafficChart() {
        lastTotals = null
        lastSampleElapsed = 0L
        lastSampleWall = 0L
        lastRateUp = 0L
        lastRateDown = 0L
        resetBucket()
        history.clear()
        binding.chartTraffic.setSeries(emptyList())
        binding.chartTraffic.clearFocusPoint()
        binding.tvTrafficSubtitle.text = defaultSubtitle
        renderTrafficValues()
    }

    private fun renderTrafficValues() {
        binding.tvUplinkValue.text = formatRate(lastRateUp)
        binding.tvDownlinkValue.text = formatRate(lastRateDown)
    }

    private fun formatRate(bytesPerSec: Long): String {
        if (bytesPerSec <= 0L) return "--"
        val kb = 1024.0
        val mb = kb * 1024.0
        val gb = mb * 1024.0
        val value = bytesPerSec.toDouble()
        return when {
            value >= gb -> String.format("%.1f GB/s", value / gb)
            value >= mb -> String.format("%.1f MB/s", value / mb)
            value >= kb -> String.format("%.0f KB/s", value / kb)
            else -> String.format("%d B/s", bytesPerSec)
        }
    }

    private fun initRangeChips() {
        binding.chipRange1h.setOnClickListener { selectRange(Range.ONE_HOUR) }
        binding.chipRange6h.setOnClickListener { selectRange(Range.SIX_HOURS) }
        binding.chipRange24h.setOnClickListener { selectRange(Range.ONE_DAY) }
        binding.chipRange7d.setOnClickListener { selectRange(Range.SEVEN_DAYS) }
        selectRange(Range.ONE_HOUR)
    }

    private fun selectRange(range: Range) {
        selectedRange = range
        binding.chipRange1h.isSelected = range == Range.ONE_HOUR
        binding.chipRange6h.isSelected = range == Range.SIX_HOURS
        binding.chipRange24h.isSelected = range == Range.ONE_DAY
        binding.chipRange7d.isSelected = range == Range.SEVEN_DAYS
        binding.chartTraffic.clearFocusPoint()
        binding.tvTrafficSubtitle.text = defaultSubtitle
        renderChart()
    }

    private fun loadHistory() {
        val json = MmkvManager.decodeSettingsString(AppConfig.CACHE_OBSERVABILITY_TRAFFIC_HISTORY)
        if (!json.isNullOrBlank()) {
            val entries = JsonUtil.fromJson(json, Array<TrafficSample>::class.java)?.toList().orEmpty()
            history.clear()
            history.addAll(entries)
        }
        trimHistory()
    }

    private fun persistHistory() {
        MmkvManager.encodeSettings(AppConfig.CACHE_OBSERVABILITY_TRAFFIC_HISTORY, JsonUtil.toJson(history))
    }

    private fun trimHistory() {
        val cutoff = System.currentTimeMillis() - maxHistoryMs
        if (history.isEmpty()) return
        var index = 0
        while (index < history.size && history[index].timestamp < cutoff) {
            index++
        }
        if (index > 0) {
            history.subList(0, index).clear()
        }
    }

    private fun downsampleSamples(values: List<TrafficSample>, targetCount: Int): List<TrafficSample> {
        if (values.size <= targetCount) return values
        val bucketSize = (values.size.toFloat() / targetCount).coerceAtLeast(1f)
        val result = ArrayList<TrafficSample>(targetCount)
        var start = 0f
        while (start < values.size && result.size < targetCount) {
            val end = minOf(values.size, (start + bucketSize).toInt().coerceAtLeast(start.toInt() + 1))
            var sumUp = 0L
            var sumDown = 0L
            var count = 0
            for (i in start.toInt() until end) {
                sumUp += values[i].upRate
                sumDown += values[i].downRate
                count++
            }
            val ts = values[start.toInt()].timestamp
            result.add(
                TrafficSample(
                    timestamp = ts,
                    upRate = if (count > 0) sumUp / count else 0L,
                    downRate = if (count > 0) sumDown / count else 0L
                )
            )
            start += bucketSize
        }
        return result
    }

    private fun currentBucketSample(): TrafficSample? {
        if (bucketCount <= 0L || lastSampleWall <= 0L) return null
        val avgUp = bucketUpSum / bucketCount
        val avgDown = bucketDownSum / bucketCount
        val timestamp = lastSampleWall
        return TrafficSample(timestamp, avgUp, avgDown)
    }

    private fun resetBucket() {
        bucketStartAt = 0L
        bucketUpSum = 0L
        bucketDownSum = 0L
        bucketCount = 0L
    }

    private fun buildPointSubtitle(point: com.xray.ang.ui.widget.TrafficLineChartView.TrafficPoint): String {
        val time = if (selectedRange == Range.SEVEN_DAYS) {
            timeFormatLong.format(point.timestamp)
        } else {
            timeFormatShort.format(point.timestamp)
        }
        val up = formatRate(point.upRate)
        val down = formatRate(point.downRate)
        return "$time · ↑ $up · ↓ $down"
    }

    private fun com.google.gson.JsonPrimitive.asLongOrNull(): Long? {
        return try {
            if (isNumber) asLong else asString.toLongOrNull()
        } catch (_: Exception) {
            null
        }
    }
}
