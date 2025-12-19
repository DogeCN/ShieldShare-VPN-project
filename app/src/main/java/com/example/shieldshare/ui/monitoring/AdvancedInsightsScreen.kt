package com.example.shieldshare.ui.monitoring

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun AdvancedInsightsScreen(
    viewModel: AdvancedInsightsViewModel = hiltViewModel(),
    onBack: (() -> Unit)? = null
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Advanced Insights") },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Live rolling metrics (cleared when you exit).",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            SparklineCard(
                title = "Battery %",
                subtitle =
                    uiState.latestSample?.batteryLevel?.takeIf { it >= 0 }?.let { "$it%" }
                        ?: "Collecting…",
                series = uiState.batterySeries,
                lineColor = MaterialTheme.colorScheme.primary
            )

            SparklineCard(
                title = "CPU % (App)",
                subtitle =
                    uiState.latestSample?.cpuPercent?.takeIf { it >= 0 }
                        ?.let { "%.1f%%".format(it) } ?: "Collecting…",
                series = uiState.cpuSeries,
                lineColor = MaterialTheme.colorScheme.tertiary
            )

            SparklineCardDual(
                title = "Throughput (B/s)",
                subtitle =
                    uiState.aggUploadBps?.let { up ->
                        val down = uiState.aggDownloadBps ?: 0.0
                        "↑${formatBps(up.toLong())}  ↓${formatBps(down.toLong())}"
                    } ?: "Collecting…",
                seriesA = uiState.uploadSeries,
                seriesB = uiState.downloadSeries,
                colorA = MaterialTheme.colorScheme.primary,
                colorB = MaterialTheme.colorScheme.error
            )

            TopConsumersCard(consumers = uiState.topConsumers)
        }
    }
}

@Composable
private fun SparklineCard(
    title: String,
    subtitle: String,
    series: List<ChartPoint>,
    lineColor: Color
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .padding(top = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                if (series.isEmpty()) {
                    Text(
                        text = "Collecting samples…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Sparkline(series, lineColor)
                }
            }
        }
    }
}

@Composable
private fun SparklineCardDual(
    title: String,
    subtitle: String,
    seriesA: List<ChartPoint>,
    seriesB: List<ChartPoint>,
    colorA: Color,
    colorB: Color
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .padding(top = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                val enough = seriesA.isNotEmpty() || seriesB.isNotEmpty()
                if (!enough) {
                    Text(
                        text = "Collecting samples…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    SparklineDual(seriesA, seriesB, colorA, colorB)
                }
            }
        }
    }
}

@Composable
private fun TopConsumersCard(consumers: List<TopConsumer>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text = "Top Consumers", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (consumers.isEmpty()) {
                Text(
                    text = "No active clients yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                val maxBytes =
                    consumers.maxOf { it.uploadBytes + it.downloadBytes }.coerceAtLeast(1L).toDouble()
                consumers.forEach { consumer ->
                    val total = consumer.uploadBytes + consumer.downloadBytes
                    val pct = (total / maxBytes).coerceIn(0.0, 1.0)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = consumer.label,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "${formatBytesShort(total)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        LinearProgressIndicator(
                            progress = pct.toFloat(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                        )
                        Text(
                            text = "↑${formatBytesShort(consumer.uploadBytes)}  ↓${formatBytesShort(consumer.downloadBytes)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Sparkline(series: List<ChartPoint>, color: Color) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val minVal = series.minOfOrNull { it.value } ?: return@Canvas
        val maxVal = series.maxOfOrNull { it.value } ?: return@Canvas
        val range = if (maxVal - minVal < 0.001f) 1f else maxVal - minVal

        val path = Path()
        series.forEachIndexed { index, point ->
            val x = (index.toFloat() / (series.lastIndex.coerceAtLeast(1))) * size.width
            val normalizedY = (point.value - minVal) / range
            val y = size.height - (normalizedY * size.height)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color = color, style = Stroke(width = 3f))
    }
}

@Composable
private fun SparklineDual(seriesA: List<ChartPoint>, seriesB: List<ChartPoint>, colorA: Color, colorB: Color) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val combined = seriesA + seriesB
        val minVal = combined.minOfOrNull { it.value } ?: return@Canvas
        val maxVal = combined.maxOfOrNull { it.value } ?: return@Canvas
        val range = max(maxVal - minVal, 1f)

        fun drawSeries(series: List<ChartPoint>, color: Color) {
            if (series.size < 2) return
            val path = Path()
            series.forEachIndexed { index, point ->
                val x = (index.toFloat() / (series.lastIndex.coerceAtLeast(1))) * size.width
                val normalizedY = (point.value - minVal) / range
                val y = size.height - (normalizedY * size.height)
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color = color, style = Stroke(width = 3f))
        }

        drawSeries(seriesA, colorA)
        drawSeries(seriesB, colorB)
    }
}

private fun formatBps(bps: Long): String {
    val abs = max(0L, bps)
    return when {
        abs < 1024 -> "${abs} B/s"
        abs < 1024 * 1024 -> "%.1f KB/s".format(abs / 1024f)
        abs < 1024 * 1024 * 1024 -> "%.2f MB/s".format(abs / (1024f * 1024f))
        else -> "%.2f GB/s".format(abs / (1024f * 1024f * 1024f))
    }
}

private fun formatBytesShort(bytes: Long): String {
    val kb = 1024.0
    val mb = kb * 1024
    val gb = mb * 1024
    return when {
        bytes >= gb -> "%.2f GB".format(bytes / gb)
        bytes >= mb -> "%.2f MB".format(bytes / mb)
        bytes >= kb -> "%.1f KB".format(bytes / kb)
        else -> "$bytes B"
    }
}

