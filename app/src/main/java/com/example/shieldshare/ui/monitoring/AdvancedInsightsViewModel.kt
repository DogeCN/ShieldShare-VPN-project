package com.example.shieldshare.ui.monitoring

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.shieldshare.managers.meter.TrafficMeter
import com.example.shieldshare.managers.performance.PerformanceMonitor
import com.example.shieldshare.managers.performance.PerformanceSample
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

@HiltViewModel
class AdvancedInsightsViewModel @Inject constructor(
    private val performanceMonitor: PerformanceMonitor,
    private val trafficMeter: TrafficMeter
) : ViewModel() {

    private val _uiState = MutableStateFlow(AdvancedInsightsUiState())
    val uiState: StateFlow<AdvancedInsightsUiState> = _uiState.asStateFlow()

    private val maxPoints = 60 // ~5 minutes at 5s/sample
    private var prevAggUp: Long? = null
    private var prevAggDown: Long? = null
    private var prevAggTs: Long? = null

    private fun <T> appendBounded(list: List<T>, value: T): List<T> {
        val mutable = list.toMutableList()
        mutable.add(value)
        if (mutable.size > maxPoints) {
            mutable.removeAt(0)
        }
        return mutable
    }

    init {
        viewModelScope.launch {
            performanceMonitor.samples.collect { sample ->
                sample?.let { addSample(it) }
            }
        }
        viewModelScope.launch {
            while (true) {
                try {
                    val stats = trafficMeter.getCurrentStats()
                    val top =
                        stats.sortedByDescending { it.totalBytesUp + it.totalBytesDown }
                            .take(5)
                            .map { s ->
                                TopConsumer(
                                    label = s.ipAddress,
                                    uploadBytes = s.totalBytesUp,
                                    downloadBytes = s.totalBytesDown
                                )
                            }
                    val now = System.currentTimeMillis()
                    val aggUp = stats.sumOf { it.totalBytesUp }
                    val aggDown = stats.sumOf { it.totalBytesDown }
                    val prevUp = prevAggUp
                    val prevDown = prevAggDown
                    val prevTs = prevAggTs

                    var uploadBps: Double? = null
                    var downloadBps: Double? = null
                    if (prevUp != null && prevDown != null && prevTs != null) {
                        val deltaMs = (now - prevTs).coerceAtLeast(1L)
                        val deltaUp = (aggUp - prevUp).coerceAtLeast(0)
                        val deltaDown = (aggDown - prevDown).coerceAtLeast(0)
                        uploadBps = (deltaUp * 1000.0) / deltaMs.toDouble()
                        downloadBps = (deltaDown * 1000.0) / deltaMs.toDouble()

                        _uiState.value =
                            _uiState.value.copy(
                                uploadSeries =
                                    appendBounded(
                                        _uiState.value.uploadSeries,
                                        ChartPoint(now, uploadBps.toFloat())
                                    ),
                                downloadSeries =
                                    appendBounded(
                                        _uiState.value.downloadSeries,
                                        ChartPoint(now, downloadBps.toFloat())
                                    ),
                                aggUploadBps = uploadBps,
                                aggDownloadBps = downloadBps,
                                topConsumers = top
                            )
                    } else {
                        _uiState.value =
                            _uiState.value.copy(
                                topConsumers = top
                            )
                    }

                    prevAggUp = aggUp
                    prevAggDown = aggDown
                    prevAggTs = now
                } catch (_: Exception) {
                }
                delay(3_000)
            }
        }
    }

    private fun addSample(sample: PerformanceSample) {
        // Skip obviously invalid readings (e.g., initial -1 CPU)
        val batteryOk = sample.batteryLevel >= 0

        _uiState.value =
            _uiState.value.copy(
                batterySeries =
                    if (batteryOk) appendBounded(
                        _uiState.value.batterySeries,
                        ChartPoint(sample.timestamp, sample.batteryLevel.toFloat())
                    )
                    else _uiState.value.batterySeries,
                cpuSeries =
                    appendBounded(
                        _uiState.value.cpuSeries,
                        ChartPoint(sample.timestamp, sample.cpuPercent.toFloat().coerceAtLeast(0f))
                    ),
                uploadSeries = appendBounded(_uiState.value.uploadSeries, ChartPoint(sample.timestamp, sample.uploadRateBps.toFloat())),
                downloadSeries = appendBounded(_uiState.value.downloadSeries, ChartPoint(sample.timestamp, sample.downloadRateBps.toFloat())),
                latestSample = sample
            )
    }
}

data class AdvancedInsightsUiState(
    val batterySeries: List<ChartPoint> = emptyList(),
    val cpuSeries: List<ChartPoint> = emptyList(),
    val uploadSeries: List<ChartPoint> = emptyList(),
    val downloadSeries: List<ChartPoint> = emptyList(),
    val latestSample: PerformanceSample? = null,
    val topConsumers: List<TopConsumer> = emptyList(),
    val aggUploadBps: Double? = null,
    val aggDownloadBps: Double? = null
)

data class ChartPoint(
    val timestampMs: Long,
    val value: Float
)

data class TopConsumer(
    val label: String,
    val uploadBytes: Long,
    val downloadBytes: Long
)

