package com.example.shieldshare.ui.performance

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material.ripple.rememberRipple
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.shieldshare.managers.performance.PerformanceSample
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PerformanceScreen(
    viewModel: PerformanceViewModel = hiltViewModel(),
    onBack: () -> Unit
) {
    val uiState = viewModel.uiState

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Performance Insights") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            item {
                PerformanceSummaryCard(uiState.value)
            }
            item {
                ExpandablePerformanceSection(
                    title = "Battery Impact",
                    content = {
                        BatterySectionContent(uiState.value)
                    }
                )
            }
            item {
                ExpandablePerformanceSection(
                    title = "CPU & Connections",
                    content = {
                        CpuSectionContent(uiState.value)
                    }
                )
            }
            item {
                ExpandablePerformanceSection(
                    title = "Network Throughput",
                    content = {
                        NetworkSectionContent(uiState.value)
                    }
                )
            }
        }
    }
}

@Composable
private fun PerformanceSummaryCard(uiState: PerformanceUiState) {
    val summary = uiState.summary ?: return
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Session started ${formatTime(summary.sessionStartTime)}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Samples collected: ${summary.sampleCount}",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "Battery drop: ${summary.sessionStartBattery - summary.latestSample.batteryLevel}%",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "Avg CPU: ${summary.averageCpuPercent.roundToInt()}% • Peak CPU: ${summary.peakCpuPercent.roundToInt()}%",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun BatterySectionContent(uiState: PerformanceUiState) {
    val summary = uiState.summary ?: return
    val latest = summary.latestSample
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        StatRow(
            label = "Current level",
            value = "${latest.batteryLevel}%",
            trailing = if (latest.isCharging) "(charging)" else ""
        )
        StatRow(
            label = "Drop per hour",
            value = "${"%.1f".format(summary.estimatedBatteryDropPerHour)}%"
        )
        StatRow(
            label = "Start level",
            value = "${summary.sessionStartBattery}%"
        )
    }
}

@Composable
private fun CpuSectionContent(uiState: PerformanceUiState) {
    val summary = uiState.summary ?: return
    val latest = summary.latestSample
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        StatRow(
            label = "Current CPU",
            value = "${latest.cpuPercent.roundToInt()}%"
        )
        StatRow(
            label = "Average CPU",
            value = "${summary.averageCpuPercent.roundToInt()}%"
        )
        StatRow(
            label = "Peak CPU",
            value = "${summary.peakCpuPercent.roundToInt()}%"
        )
        StatRow(
            label = "Active connections",
            value = latest.activeConnections.toString()
        )
    }
}

@Composable
private fun NetworkSectionContent(uiState: PerformanceUiState) {
    val latest = uiState.summary?.latestSample ?: return
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        StatRow(label = "Upload rate", value = formatRate(latest.uploadRateBps))
        StatRow(label = "Download rate", value = formatRate(latest.downloadRateBps))
        StatRow(label = "Total uploaded", value = formatBytes(latest.totalUploadBytes))
        StatRow(label = "Total downloaded", value = formatBytes(latest.totalDownloadBytes))
    }
}

@Composable
private fun ExpandablePerformanceSection(
    title: String,
    content: @Composable () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(
                        indication = rememberRipple(bounded = true),
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                    ) { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand"
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    content()
                }
            }
        }
    }
}

@Composable
private fun StatRow(label: String, value: String, trailing: String = "") {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "$value $trailing".trim(),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

private fun formatBytes(bytes: Long): String {
    val kb = 1024.0
    val mb = kb * 1024
    val gb = mb * 1024
    return when {
        bytes >= gb -> "%.2f GB".format(bytes / gb)
        bytes >= mb -> "%.2f MB".format(bytes / mb)
        bytes >= kb -> "%.2f KB".format(bytes / kb)
        else -> "$bytes B"
    }
}

private fun formatRate(bps: Double): String {
    val kb = 1024.0
    val mb = kb * 1024
    return when {
        bps >= mb -> "%.2f MB/s".format(bps / mb)
        bps >= kb -> "%.2f KB/s".format(bps / kb)
        else -> "%.0f B/s".format(bps)
    }
}

private fun formatTime(timestamp: Long): String {
    val formatter = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
    return formatter.format(Date(timestamp))
}

