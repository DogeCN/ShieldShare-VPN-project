package com.example.shieldshare.managers.performance

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.example.shieldshare.managers.meter.TrafficMeter
import com.example.shieldshare.managers.proxy.ProxyServer
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Session-scoped performance monitor that samples battery, CPU, connectivity and throughput
 * metrics without persisting them. All state is cleared whenever the app process dies.
 */
@Singleton
class PerformanceMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val proxyServer: ProxyServer,
    private val trafficMeter: TrafficMeter
) {
    companion object {
        private const val TAG = "PerformanceMonitor"
        private const val SAMPLE_INTERVAL_MS = 5_000L
        private const val MAX_SAMPLES = 240 // ~20 minutes at 5s interval
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _samples = MutableStateFlow<List<PerformanceSample>>(emptyList())
    val samples: StateFlow<List<PerformanceSample>> = _samples.asStateFlow()

    private val _summary = MutableStateFlow<PerformanceSummary?>(null)
    val summary: StateFlow<PerformanceSummary?> = _summary.asStateFlow()

    private var baselineSample: PerformanceSample? = null
    private var peakCpuPercent = 0.0

    init {
        startSampling()
    }

    private fun startSampling() {
        scope.launch {
            var lastCpuTimeMs = Process.getElapsedCpuTime()
            var lastWallTimeMs = SystemClock.elapsedRealtime()
            var lastUploadBytes = 0L
            var lastDownloadBytes = 0L

            while (isActive) {
                val now = System.currentTimeMillis()
                val (batteryPercent, isCharging) = readBatterySnapshot()
                val proxyInfo = proxyServer.getProxyInfo()

                val allStats = trafficMeter.getCurrentStats()
                val totalUpload = allStats.sumOf { it.totalBytesUp }
                val totalDownload = allStats.sumOf { it.totalBytesDown }

                val elapsedCpu = Process.getElapsedCpuTime()
                val elapsedWall = SystemClock.elapsedRealtime()
                val cpuDelta = (elapsedCpu - lastCpuTimeMs).coerceAtLeast(0L)
                val wallDelta = (elapsedWall - lastWallTimeMs).coerceAtLeast(1L)
                val cpuPercent = ((cpuDelta.toDouble() / wallDelta.toDouble()) /
                    Runtime.getRuntime().availableProcessors().coerceAtLeast(1)) * 100.0

                val uploadRate = if (wallDelta > 0) {
                    (totalUpload - lastUploadBytes).coerceAtLeast(0L) * 1000.0 / wallDelta.toDouble()
                } else 0.0
                val downloadRate = if (wallDelta > 0) {
                    (totalDownload - lastDownloadBytes).coerceAtLeast(0L) * 1000.0 / wallDelta.toDouble()
                } else 0.0

                val sample = PerformanceSample(
                    timestamp = now,
                    batteryLevel = batteryPercent,
                    isCharging = isCharging,
                    cpuPercent = cpuPercent.coerceAtMost(100.0),
                    activeConnections = proxyInfo.activeConnections,
                    totalUploadBytes = totalUpload,
                    totalDownloadBytes = totalDownload,
                    uploadRateBps = uploadRate,
                    downloadRateBps = downloadRate
                )

                peakCpuPercent = max(peakCpuPercent, sample.cpuPercent)
                if (baselineSample == null) {
                    baselineSample = sample
                }

                appendSample(sample)
                updateSummary(sample)

                Log.d(
                    TAG,
                    "Sample -> Battery=${sample.batteryLevel}% (charging=${sample.isCharging}), " +
                        "CPU=${"%.1f".format(sample.cpuPercent)}%, " +
                        "Conn=${sample.activeConnections}, " +
                        "Up=${"%.2f".format(sample.uploadRateBps / 1024)}KB/s, " +
                        "Down=${"%.2f".format(sample.downloadRateBps / 1024)}KB/s"
                )

                lastCpuTimeMs = elapsedCpu
                lastWallTimeMs = elapsedWall
                lastUploadBytes = totalUpload
                lastDownloadBytes = totalDownload

                delay(SAMPLE_INTERVAL_MS)
            }
        }
    }

    private fun appendSample(sample: PerformanceSample) {
        val newList = (_samples.value + sample).takeLast(MAX_SAMPLES)
        _samples.value = newList
    }

    private fun updateSummary(latest: PerformanceSample) {
        val baseline = baselineSample ?: latest
        val sampleCount = _samples.value.size
        val avgCpu = if (sampleCount > 0) {
            _samples.value.map { it.cpuPercent }.average()
        } else latest.cpuPercent.toDouble()

        val elapsedMs = (latest.timestamp - baseline.timestamp).coerceAtLeast(1L)
        val batteryDelta = (baseline.batteryLevel - latest.batteryLevel).coerceAtLeast(0)
        val hours = elapsedMs / 3_600_000.0
        val dropPerHour = if (hours > 0) batteryDelta / hours else 0.0

        _summary.value = PerformanceSummary(
            sessionStartTime = baseline.timestamp,
            sessionStartBattery = baseline.batteryLevel,
            latestSample = latest,
            averageCpuPercent = avgCpu,
            peakCpuPercent = peakCpuPercent,
            estimatedBatteryDropPerHour = dropPerHour,
            sampleCount = sampleCount
        )
    }

    private fun readBatterySnapshot(): Pair<Int, Boolean> {
        val intent = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) {
            ((level / scale.toFloat()) * 100).roundToInt()
        } else {
            0
        }
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        return percent to charging
    }

    fun clear() {
        baselineSample = null
        peakCpuPercent = 0.0
        _samples.value = emptyList()
        _summary.value = null
    }
}

