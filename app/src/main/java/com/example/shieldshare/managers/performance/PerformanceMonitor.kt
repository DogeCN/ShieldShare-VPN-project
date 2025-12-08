package com.example.shieldshare.managers.performance

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.example.shieldshare.managers.meter.TrafficMeter
import com.example.shieldshare.managers.meter.TrafficTotals
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Singleton
class PerformanceMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val trafficMeter: TrafficMeter
) {

    companion object {
        private const val TAG = "PerformanceMonitor"
        private const val SAMPLE_INTERVAL_MS = 5_000L
        private const val MAX_HISTORY = 120 // ~10 minutes at 5s/sample
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _latestSample = MutableStateFlow<PerformanceSample?>(null)
    val samples: StateFlow<PerformanceSample?> = _latestSample

    private val _history = MutableStateFlow<List<PerformanceSample>>(emptyList())
    val history: StateFlow<List<PerformanceSample>> = _history

    private val _summary = MutableStateFlow<PerformanceSummary?>(null)
    val summary: StateFlow<PerformanceSummary?> = _summary

    private var exportEnabled = false
    private var exportTreeUri: String? = null
    private var batteryWriter: BufferedWriter? = null
    private var cpuWriter: BufferedWriter? = null
    private var throughputWriter: BufferedWriter? = null

    private var lastCpuStat: CpuStat? = null
    private var lastCpuPercent: Double? = null
    private var lastTotals: TrafficTotals? = null
    private var lastAggUp: Long? = null
    private var lastAggDown: Long? = null
    private var lastTimestamp: Long? = null
    private var sessionStamp: String? = null

    init {
        startSampling()
    }

    fun setExportEnabled(enabled: Boolean) {
        exportEnabled = enabled
        if (!enabled) {
            closeWriters()
        }
    }

    fun setExportDestination(treeUri: String?) {
        exportTreeUri = treeUri
        closeWriters() // force writers to be recreated at the new destination
    }

    private fun startSampling() {
        scope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                val batteryPct = readBatteryPct()
                val cpuRaw = readCpuLoad()
                val cpuPct = if (cpuRaw >= 0f) cpuRaw else (lastCpuPercent?.toFloat() ?: 0f)
                val charging = isCharging()

                val stats = trafficMeter.getCurrentStats()
                val aggUp = stats.sumOf { it.totalBytesUp }
                val aggDown = stats.sumOf { it.totalBytesDown }
                val prevAggUp = lastAggUp
                val prevAggDown = lastAggDown
                val prevTs = lastTimestamp

                val uploadBps: Long
                val downloadBps: Long
                if (prevAggUp != null && prevAggDown != null && prevTs != null) {
                    val deltaMs = (now - prevTs).coerceAtLeast(1L)
                    val deltaUp = (aggUp - prevAggUp).coerceAtLeast(0)
                    val deltaDown = (aggDown - prevAggDown).coerceAtLeast(0)
                    uploadBps = (deltaUp * 1000L) / deltaMs
                    downloadBps = (deltaDown * 1000L) / deltaMs
                } else {
                    uploadBps = 0
                    downloadBps = 0
                }

                val sample =
                    PerformanceSample(
                        timestamp = now,
                        batteryLevel = batteryPct,
                        isCharging = charging,
                        cpuPercent = cpuPct.toDouble(),
                        activeConnections = 0,
                        totalUploadBytes = aggUp,
                        totalDownloadBytes = aggDown,
                        uploadRateBps = uploadBps.toDouble(),
                        downloadRateBps = downloadBps.toDouble()
                    )
                _latestSample.value = sample
                lastCpuPercent = cpuPct.toDouble()
                _history.value =
                    (_history.value + sample).takeLast(MAX_HISTORY)
                updateSummary(sample)

                if (exportEnabled) {
                    writeSample(sample)
                }

                lastAggUp = aggUp
                lastAggDown = aggDown
                lastTimestamp = now
                delay(SAMPLE_INTERVAL_MS)
            }
        }
    }

    private fun readBatteryPct(): Int {
        return try {
            val ifilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus: Intent? = context.registerReceiver(null, ifilter)
            val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level >= 0 && scale > 0) ((level.toFloat() / scale.toFloat()) * 100f).toInt() else -1
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read battery pct", e)
            -1
        }
    }

    private fun readCpuLoad(): Float {
        return try {
            val stat = readProcStat() ?: return -1f
            val prev = lastCpuStat
            lastCpuStat = stat
            if (prev == null) return -1f

            val totalDiff = (stat.total - prev.total).toFloat().coerceAtLeast(1f)
            val idleDiff = (stat.idle - prev.idle).toFloat().coerceAtLeast(0f)
            val usage = ((totalDiff - idleDiff) / totalDiff) * 100f
            usage.coerceIn(0f, 100f)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read CPU load", e)
            -1f
        }
    }

    private fun readProcStat(): CpuStat? {
        return try {
            val firstLine = File("/proc/stat").bufferedReader().useLines { lines ->
                lines.firstOrNull { it.startsWith("cpu ") }
            } ?: return null
            val parts = firstLine.split("\\s+".toRegex()).filter { it.isNotBlank() }
            if (parts.size < 8) return null
            val user = parts[1].toLong()
            val nice = parts[2].toLong()
            val system = parts[3].toLong()
            val idle = parts[4].toLong()
            val iowait = parts[5].toLong()
            val irq = parts[6].toLong()
            val softIrq = parts[7].toLong()
            val steal = parts.getOrNull(8)?.toLongOrNull() ?: 0L
            val guest = parts.getOrNull(9)?.toLongOrNull() ?: 0L
            val guestNice = parts.getOrNull(10)?.toLongOrNull() ?: 0L

            val idleAll = idle + iowait
            val nonIdle = user + nice + system + irq + softIrq + steal + guest + guestNice
            val total = idleAll + nonIdle
            CpuStat(total = total, idle = idleAll)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read /proc/stat", e)
            null
        }
    }

    private fun isCharging(): Boolean {
        return try {
            val statusIntent =
                context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val plugged = statusIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
            plugged != 0
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read charging state", e)
            false
        }
    }

    private fun updateSummary(latest: PerformanceSample) {
        val historySnapshot = _history.value
        if (historySnapshot.isEmpty()) return

        val first = historySnapshot.first()
        val avgCpu = historySnapshot.map { it.cpuPercent }.average()
        val peakCpu = historySnapshot.maxOf { it.cpuPercent }
        val elapsedHours =
            ((latest.timestamp - first.timestamp).coerceAtLeast(1L)).toDouble() / 3_600_000.0
        val batteryDrop = (first.batteryLevel - latest.batteryLevel).coerceAtLeast(0)
        val dropPerHour = if (elapsedHours > 0) batteryDrop / elapsedHours else 0.0

        _summary.value =
            PerformanceSummary(
                sessionStartTime = first.timestamp,
                sessionStartBattery = first.batteryLevel,
                latestSample = latest,
                averageCpuPercent = avgCpu,
                peakCpuPercent = peakCpu,
                estimatedBatteryDropPerHour = dropPerHour,
                sampleCount = historySnapshot.size
            )
    }

    private fun writeSample(sample: PerformanceSample) {
        try {
            ensureWriters()
            val lineBattery = "${sample.timestamp},${sample.batteryLevel},${sample.isCharging}\n"
            val lineCpu = "${sample.timestamp},${sample.cpuPercent}\n"
            val lineThroughput =
                "${sample.timestamp},${sample.uploadRateBps},${sample.downloadRateBps},${sample.totalUploadBytes},${sample.totalDownloadBytes}\n"

            batteryWriter?.apply {
                write(lineBattery)
                flush()
            }
            cpuWriter?.apply {
                write(lineCpu)
                flush()
            }
            throughputWriter?.apply {
                write(lineThroughput)
                flush()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write perf sample", e)
        }
    }

    private fun ensureWriters() {
        if (batteryWriter != null && cpuWriter != null && throughputWriter != null) return
        val stamp =
            sessionStamp
                ?: SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
                    .format(Date().time)
                    .also { sessionStamp = it }

        val battery = "battery-$stamp.csv"
        val cpu = "cpu-$stamp.csv"
        val throughput = "throughput-$stamp.csv"

        val treeUriString = exportTreeUri
        if (treeUriString.isNullOrBlank()) {
            val perfDir = File(context.filesDir, "perf")
            if (!perfDir.exists()) perfDir.mkdirs()
            if (batteryWriter == null) {
                val file = File(perfDir, battery)
                val writer = FileWriter(file, true).buffered()
                if (file.length() == 0L) writer.write("timestamp_ms,battery_pct,is_charging\n")
                writer.flush()
                batteryWriter = writer
            }
            if (cpuWriter == null) {
                val file = File(perfDir, cpu)
                val writer = FileWriter(file, true).buffered()
                if (file.length() == 0L) writer.write("timestamp_ms,cpu_pct\n")
                writer.flush()
                cpuWriter = writer
            }
            if (throughputWriter == null) {
                val file = File(perfDir, throughput)
                val writer = FileWriter(file, true).buffered()
                if (file.length() == 0L) {
                    writer.write("timestamp_ms,upload_bps,download_bps,total_bytes_up,total_bytes_down\n")
                }
                writer.flush()
                throughputWriter = writer
            }
            return
        }

        try {
            val treeUri = android.net.Uri.parse(treeUriString)
            val tree = DocumentFile.fromTreeUri(context, treeUri)
            if (tree == null || !tree.isDirectory || !tree.canWrite()) {
                Log.w(TAG, "Export tree not writable, falling back to sandbox")
                exportTreeUri = null
                return ensureWriters()
            }

            fun openCsv(name: String, header: String): BufferedWriter {
                val existing = tree.findFile(name)
                val fileDoc =
                    existing
                        ?: tree.createFile("text/csv", name)
                        ?: throw IllegalStateException("Unable to create $name")
                val append = fileDoc.length() > 0
                val out =
                    context.contentResolver.openOutputStream(fileDoc.uri, "wa")
                        ?: throw IllegalStateException("Unable to open $name")
                val writer = out.bufferedWriter()
                if (!append) writer.write(header)
                writer.flush()
                return writer
            }

            if (batteryWriter == null) {
                batteryWriter = openCsv(battery, "timestamp_ms,battery_pct,is_charging\n")
            }
            if (cpuWriter == null) {
                cpuWriter = openCsv(cpu, "timestamp_ms,cpu_pct\n")
            }
            if (throughputWriter == null) {
                throughputWriter =
                    openCsv(
                        throughput,
                        "timestamp_ms,upload_bps,download_bps,total_bytes_up,total_bytes_down\n"
                    )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set up export writers, falling back to sandbox", e)
            exportTreeUri = null
            closeWriters()
            ensureWriters()
        }
    }

    private fun closeWriters() {
        try {
            batteryWriter?.close()
            cpuWriter?.close()
            throughputWriter?.close()
        } catch (_: Exception) {
        } finally {
            batteryWriter = null
            cpuWriter = null
            throughputWriter = null
            sessionStamp = null
        }
    }
}

private data class CpuStat(
    val total: Long,
    val idle: Long
)
 
