package com.example.shieldshare.managers.performance

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.os.Process
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.example.shieldshare.managers.meter.TrafficMeter
import com.example.shieldshare.managers.meter.TrafficTotals
import com.example.shieldshare.managers.proxy.ProxyServer
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
    private val trafficMeter: TrafficMeter,
    private val proxyServer: ProxyServer
) {

    companion object {
        private const val TAG = "PerformanceMonitor"
        private const val SAMPLE_INTERVAL_MS = 5_000L
        private const val MAX_HISTORY = 120 // ~10 minutes at 5s/sample
        // Default clock ticks per second (jiffy rate) - typically 100 Hz on Android
        private const val DEFAULT_CLK_TCK = 100L
    }
    
    private var clockTicksPerSecond: Long = DEFAULT_CLK_TCK

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
    private var lastCpuStatTimestamp: Long? = null
    private var lastCpuPercent: Double? = null
    private var lastProcessCpuTime: Long? = null
    private var lastProcessCpuTimestamp: Long? = null
    private var lastTotals: TrafficTotals? = null
    private var lastAggUp: Long? = null
    private var lastAggDown: Long? = null
    private var lastTimestamp: Long? = null
    private var sessionStamp: String? = null

    init {
        detectClockTicksPerSecond()
        startSampling()
    }
    
    /**
     * Try to detect the actual clock ticks per second (jiffy rate).
     * Falls back to DEFAULT_CLK_TCK (100 Hz) if detection fails.
     */
    private fun detectClockTicksPerSecond() {
        // Try to read from /proc/self/auxv (if accessible)
        // Or we could use sysconf(_SC_CLK_TCK) via JNI, but that's complex
        // For now, assume 100 Hz which is standard on Android
        clockTicksPerSecond = DEFAULT_CLK_TCK
        Log.d(TAG, "Using clock ticks per second: $clockTicksPerSecond Hz")
    }

    fun setExportEnabled(enabled: Boolean) {
        exportEnabled = enabled
        Log.i(TAG, "CSV export ${if (enabled) "ENABLED" else "DISABLED"}")
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
                val cpuPct =
                    if (cpuRaw >= 0f) {
                        cpuRaw
                    } else {
                        val fallback = (lastCpuPercent?.toFloat() ?: 0f)
                        Log.w(TAG, "CPU read failed; using fallback=$fallback")
                        fallback
                    }
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

            val activeConns =
                try {
                    proxyServer.getProxyInfo().activeConnections
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to read active connections", e)
                    0
                }

                val sample =
                    PerformanceSample(
                        timestamp = now,
                        batteryLevel = batteryPct,
                        isCharging = charging,
                        cpuPercent = cpuPct.toDouble(),
                    activeConnections = activeConns,
                        totalUploadBytes = aggUp,
                        totalDownloadBytes = aggDown,
                        uploadRateBps = uploadBps.toDouble(),
                        downloadRateBps = downloadBps.toDouble()
                    )
                _latestSample.value = sample
                if (cpuRaw >= 0f) {
                    lastCpuPercent = cpuPct.toDouble()
                }
                _history.value =
                    (_history.value + sample).takeLast(MAX_HISTORY)
                updateSummary(sample)

                if (exportEnabled) {
                    writeSample(sample)
                } else {
                    // Log once per minute if export is disabled
                    if (now % 60_000L < SAMPLE_INTERVAL_MS) {
                        Log.d(TAG, "CSV export is disabled - enable in Settings to write CSV files")
                    }
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
        // Try /proc/self/stat first (more reliable, updates frequently)
        val procStat = readProcSelfStat()
        if (procStat != null) {
            val prev = lastCpuStat
            val prevTs = lastCpuStatTimestamp
            val now = System.currentTimeMillis()
            lastCpuStat = procStat
            lastCpuStatTimestamp = now

            if (prev != null && prevTs != null) {
                // Process CPU time is in jiffies (clock ticks)
                // Calculate CPU percentage: (CPU time used / wall-clock time) * 100
                val cpuTimeDiffJiffies = (procStat.total - prev.total).coerceAtLeast(0L)
                val wallTimeDiffMs = (now - prevTs).coerceAtLeast(1L)
                // Convert jiffies to milliseconds: jiffies * (1000ms / clockTicksPerSecond)
                val msPerJiffy = 1000L / clockTicksPerSecond
                val cpuTimeDiffMs = cpuTimeDiffJiffies * msPerJiffy
                val usage = ((cpuTimeDiffMs.toFloat() / wallTimeDiffMs.toFloat()) * 100f).coerceIn(0f, 100f)
                Log.d(TAG, "readCpuLoad: /proc/self/stat usage=$usage% (cpuTime=${cpuTimeDiffMs}ms=${cpuTimeDiffJiffies}jiffies, wallTime=${wallTimeDiffMs}ms, clk_tck=${clockTicksPerSecond}Hz, prevTotal=${prev.total}, currTotal=${procStat.total})")
                return usage
            } else {
                Log.d(TAG, "readCpuLoad: first /proc/self/stat sample (total=${procStat.total})")
                return 0f
            }
        }

        // Fallback to Process.getElapsedCpuTime() (may not update frequently enough)
        val processCpu = readProcessCpuTime()
        if (processCpu != null) {
            val prevTime = lastProcessCpuTime
            val prevTs = lastProcessCpuTimestamp
            val now = System.currentTimeMillis()
            lastProcessCpuTime = processCpu
            lastProcessCpuTimestamp = now

            if (prevTime != null && prevTs != null) {
                val cpuTimeDiffMs = (processCpu - prevTime) / 1_000_000L // Convert ns to ms
                val wallTimeDiffMs = (now - prevTs).coerceAtLeast(1L)
                // CPU percentage = (CPU time / wall time) * 100
                // This gives the app's CPU usage percentage
                val usage = ((cpuTimeDiffMs.toFloat() / wallTimeDiffMs.toFloat()) * 100f).coerceIn(0f, 100f)
                Log.d(TAG, "readCpuLoad: Process.getElapsedCpuTime() usage=$usage% (cpuTime=${cpuTimeDiffMs}ms, wallTime=${wallTimeDiffMs}ms, prev=${prevTime}ns, curr=${processCpu}ns)")
                return usage
            } else {
                Log.d(TAG, "readCpuLoad: first Process.getElapsedCpuTime() sample (value=${processCpu}ns)")
                return 0f
            }
        }

        // Both methods failed - CPU unavailable
        Log.w(TAG, "readCpuLoad: All CPU reading methods failed, CPU unavailable")
        return -1f
    }

    /**
     * Try to read /proc/self/stat (app's own process CPU stats).
     * This is usually accessible even when /proc/stat is blocked.
     * Returns CPU time in jiffies (clock ticks).
     */
    private fun readProcSelfStat(): CpuStat? {
        return try {
            val firstLine = File("/proc/self/stat").bufferedReader().useLines { lines ->
                lines.firstOrNull()
            } ?: return null
            val parts = firstLine.split("\\s+".toRegex()).filter { it.isNotBlank() }
            // /proc/self/stat format: pid comm state ppid ... utime stime cutime cstime ...
            // utime (index 13) = user time in jiffies, stime (index 14) = system time in jiffies
            if (parts.size < 15) {
                Log.w(TAG, "readProcSelfStat: insufficient parts (${parts.size} < 15)")
                return null
            }
            val utime = parts[13].toLongOrNull() ?: return null
            val stime = parts[14].toLongOrNull() ?: return null
            val total = utime + stime
            Log.d(TAG, "readProcSelfStat: utime=${utime}jiffies, stime=${stime}jiffies, total=${total}jiffies")
            // For process-level stats, we use total = utime + stime, idle = 0
            // The percentage will be calculated based on how much CPU time the process used
            // compared to wall-clock time (handled in readCpuLoad)
            CpuStat(total = total, idle = 0L)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read /proc/self/stat", e)
            null
        }
    }

    /**
     * Fallback: Use Process.getElapsedCpuTime() to get app's CPU time.
     * Returns CPU time in nanoseconds, or null if unavailable.
     * Note: This method may not update frequently enough on some devices.
     */
    private fun readProcessCpuTime(): Long? {
        return try {
            val cpuTime = Process.getElapsedCpuTime()
            Log.d(TAG, "readProcessCpuTime: raw value=${cpuTime}ns (${cpuTime / 1_000_000L}ms)")
            cpuTime
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read Process.getElapsedCpuTime()", e)
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
            // Log periodically to confirm writes are happening
            if (sample.timestamp % 30_000L < SAMPLE_INTERVAL_MS) {
                Log.d(TAG, "Wrote CSV sample: CPU=${sample.cpuPercent}%, Battery=${sample.batteryLevel}%, Connections=${sample.activeConnections}")
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
            Log.i(TAG, "CSV files will be written to: ${perfDir.absolutePath}")
            if (batteryWriter == null) {
                val file = File(perfDir, battery)
                val writer = FileWriter(file, true).buffered()
                if (file.length() == 0L) writer.write("timestamp_ms,battery_pct,is_charging\n")
                writer.flush()
                batteryWriter = writer
                Log.i(TAG, "Created battery CSV: ${file.name}")
            }
            if (cpuWriter == null) {
                val file = File(perfDir, cpu)
                val writer = FileWriter(file, true).buffered()
                if (file.length() == 0L) writer.write("timestamp_ms,cpu_pct\n")
                writer.flush()
                cpuWriter = writer
                Log.i(TAG, "Created CPU CSV: ${file.name}")
            }
            if (throughputWriter == null) {
                val file = File(perfDir, throughput)
                val writer = FileWriter(file, true).buffered()
                if (file.length() == 0L) {
                    writer.write("timestamp_ms,upload_bps,download_bps,total_bytes_up,total_bytes_down\n")
                }
                writer.flush()
                throughputWriter = writer
                Log.i(TAG, "Created throughput CSV: ${file.name}")
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
 
