package com.example.shieldshare.ui.monitoring

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun MonitoringDashboardScreen(
        navController: NavController? = null,
        viewModel: MonitoringViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    LazyColumn(
            modifier =
                    Modifier.fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                            .padding(horizontal = 12.dp)
                            .padding(top = 12.dp)
                            .padding(bottom = 6.dp)
                            .safeDrawingPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors =
                            CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surface
                            ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                    shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                            text = "System Status",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                    )

                    Text(
                            text = "VPN Status: ${uiState.vpnStatus}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                    )

                    Text(
                            text =
                                    "Proxy Status: ${if (uiState.isProxyRunning) "RUNNING" else "STOPPED"}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        item {
            // Only show traffic stats if there's an active service session
            val hasActiveServiceSession =
                    remember(uiState.serviceSessions) {
                        uiState.serviceSessions.any { it.isActive }
                    }

            Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors =
                            CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surface
                            ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                    shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                                text = "Per-Device Traffic",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.SemiBold
                        )
                        if (hasActiveServiceSession && uiState.trafficStats.isNotEmpty()) {
                            Text(
                                    text =
                                            "${uiState.trafficStats.size} client${if (uiState.trafficStats.size != 1) "s" else ""}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    if (!hasActiveServiceSession || uiState.trafficStats.isEmpty()) {
                        Text(
                                text =
                                        "No devices connected yet. Traffic will appear here once clients connect through the proxy.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)
                        )
                    } else {
                        // Sort devices by total traffic (descending) - use remember to avoid
                        // recomputing
                        val sortedDevices =
                                remember(uiState.trafficStats) {
                                    uiState.trafficStats.sortedByDescending {
                                        it.totalBytesUp + it.totalBytesDown
                                    }
                                }

                        // Use Column instead of LazyColumn to avoid nested scrolling
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            sortedDevices.forEach { device ->
                                DeviceTrafficCard(
                                        device = device,
                                        quotaEnabled = uiState.quotaEnabled,
                                        quotaSnapshot = uiState.quotaSummaries[device.ipAddress],
                                        onResetClientQuota = { viewModel.resetClientQuota(it) }
                                )
                            }
                        }
                    }
                }
            }
        }

        // Persistent Data Section
        item {
            var expanded by remember { mutableStateOf(false) }

            Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors =
                            CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surface
                            ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                    shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Header - clickable to expand/collapse with rounded ripple
                    Row(
                            modifier =
                                    Modifier.fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable(
                                                    indication = rememberRipple(bounded = true),
                                                    interactionSource =
                                                            remember { MutableInteractionSource() }
                                            ) { expanded = !expanded },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                                text = "Historical Session Data",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(vertical = 4.dp)
                        )
                        Icon(
                                imageVector =
                                        if (expanded) Icons.Default.ExpandLess
                                        else Icons.Default.ExpandMore,
                                contentDescription = if (expanded) "Collapse" else "Expand",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }

                    // Simple conditional rendering - no animations to avoid lag
                    if (expanded) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            // Database Statistics
                            uiState.databaseStats?.let { stats ->
                                Column(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column {
                                            Text(
                                                    text = "Service Sessions",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color =
                                                            MaterialTheme.colorScheme
                                                                    .onSurfaceVariant
                                            )
                                            Text(
                                                    text = "${stats.totalSessions}",
                                                    style = MaterialTheme.typography.bodyLarge,
                                                    fontWeight = FontWeight.Bold,
                                                    fontFamily = FontFamily.Monospace
                                            )
                                        }
                                        Column {
                                            Text(
                                                    text = "Unique Clients",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color =
                                                            MaterialTheme.colorScheme
                                                                    .onSurfaceVariant
                                            )
                                            Text(
                                                    text = "${stats.uniqueClients}",
                                                    style = MaterialTheme.typography.bodyLarge,
                                                    fontWeight = FontWeight.Bold,
                                                    fontFamily = FontFamily.Monospace
                                            )
                                        }
                                    }
                                }
                                Divider()
                            }

                            // Service Sessions (only show completed sessions, not active ones)
                            val completedSessions =
                                    remember(uiState.serviceSessions) {
                                        uiState.serviceSessions.filter { !it.isActive }
                                    }

                            if (completedSessions.isEmpty()) {
                                Text(
                                        text =
                                                "No completed service sessions yet. Completed sessions will appear here after they end.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                                )
                            } else {
                                // For small numbers of sessions, use Column for natural sizing
                                // For many sessions, use LazyColumn with max height
                                if (completedSessions.size <= 3) {
                                    // Use Column for natural height when few sessions
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        completedSessions.forEach { session ->
                                            ServiceSessionCard(
                                                    session = session,
                                                    clientTraffic =
                                                            uiState.clientTrafficPerSession[
                                                                    session.sessionId],
                                                    onExpand = {
                                                        viewModel.loadClientTrafficForSession(
                                                                session.sessionId
                                                        )
                                                    }
                                            )
                                        }
                                    }
                                } else {
                                    // Use LazyColumn for better performance with many sessions
                                    // Use max height to prevent stretching too tall
                                    LazyColumn(
                                            modifier = Modifier.heightIn(max = 600.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp),
                                            contentPadding = PaddingValues(vertical = 4.dp)
                                    ) {
                                        items(items = completedSessions, key = { it.sessionId }) {
                                                session ->
                                            ServiceSessionCard(
                                                    session = session,
                                                    clientTraffic =
                                                            uiState.clientTrafficPerSession[
                                                                    session.sessionId],
                                                    onExpand = {
                                                        viewModel.loadClientTrafficForSession(
                                                                session.sessionId
                                                        )
                                                    }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Unique IP Traffic Summary Card
        item {
            var expanded by remember { mutableStateOf(false) }

            Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors =
                            CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surface
                            ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                    shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Header - clickable to expand/collapse with rounded ripple
                    Row(
                            modifier =
                                    Modifier.fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable(
                                                    indication = rememberRipple(bounded = true),
                                                    interactionSource =
                                                            remember { MutableInteractionSource() }
                                            ) { expanded = !expanded },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                                text = "Historical Usage by IP",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(vertical = 4.dp)
                        )
                        Icon(
                                imageVector =
                                        if (expanded) Icons.Default.ExpandLess
                                        else Icons.Default.ExpandMore,
                                contentDescription = if (expanded) "Collapse" else "Expand",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }

                    // Simple conditional rendering - no heavy animations
                    if (expanded) {
                        if (uiState.uniqueIpTrafficSummary.isEmpty()) {
                            Text(
                                    text = "No traffic data available yet.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)
                            )
                        } else {
                            // Sort by total traffic (descending)
                            val sortedIps =
                                    remember(uiState.uniqueIpTrafficSummary) {
                                        uiState.uniqueIpTrafficSummary.toList().sortedByDescending {
                                            it.second.first + it.second.second
                                        }
                                    }

                            // Use Column instead of LazyColumn to avoid nested scrolling
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                sortedIps.forEach { (ip, traffic) ->
                                    UniqueIpTrafficRow(
                                            ip = ip,
                                            bytesUp = traffic.first,
                                            bytesDown = traffic.second
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        item {
            Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors =
                            CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surface
                            ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                    shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                            text = "Performance Insights",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold
                    )
                    Text(
                            text =
                                    "Track host battery, CPU load, and throughput for the current session.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                            onClick = { navController?.navigate("performance") },
                            enabled = navController != null
                    ) { Text("Open performance screen") }
                }
            }
        }
    }
}

@Composable
private fun ServiceSessionCard(
        session: com.example.shieldshare.data.db.ServiceSessionEntity,
        clientTraffic: Map<String, Pair<Long, Long>>?,
        onExpand: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    // Calculate uptime
    val uptime =
            remember(session.startTime, session.endTime) {
                val endTime = session.endTime ?: System.currentTimeMillis()
                endTime - session.startTime
            }

    // Format dates
    val startTimeFormatted =
            remember(session.startTime) {
                val formatter =
                        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                                .withZone(ZoneId.systemDefault())
                formatter.format(Instant.ofEpochMilli(session.startTime))
            }
    val endTimeFormatted =
            remember(session.endTime) {
                session.endTime?.let {
                    val formatter =
                            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                                    .withZone(ZoneId.systemDefault())
                    formatter.format(Instant.ofEpochMilli(it))
                }
                        ?: "Active"
            }

    // Total bytes
    val totalBytes =
            remember(session.totalBytesUploaded, session.totalBytesDownloaded) {
                session.totalBytesUploaded + session.totalBytesDownloaded
            }

    Card(
            modifier = Modifier.fillMaxWidth(),
            colors =
                    CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
            shape = RoundedCornerShape(8.dp)
    ) {
        Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Header - clickable to expand with rounded ripple
            Row(
                    modifier =
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(
                                            indication = rememberRipple(bounded = true),
                                            interactionSource =
                                                    remember { MutableInteractionSource() }
                                    ) {
                                expanded = !expanded
                                if (expanded && clientTraffic == null) {
                                    onExpand()
                                }
                            },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                            text = "Service Session",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                            text = formatUptime(uptime),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace
                    )
                }
                Icon(
                        imageVector =
                                if (expanded) Icons.Default.ExpandLess
                                else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "Collapse" else "Expand",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Summary stats (always visible)
            Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                            text = "↑ Upload",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                            text = formatBytes(session.totalBytesUploaded),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                    )
                }
                Column {
                    Text(
                            text = "Total",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                            text = formatBytes(totalBytes),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                    )
                }
                Column {
                    Text(
                            text = "↓ Download",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                            text = formatBytes(session.totalBytesDownloaded),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                    )
                }
            }

            // Expanded content
            if (expanded) {
                Divider()

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Session details
                    Text(
                            text = "Session Details",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium
                    )

                    Column {
                        Column {
                            Text(
                                    text = "Started",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                    text = startTimeFormatted,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontFamily = FontFamily.Monospace
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Column {
                            Text(
                                    text = "Ended",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                    text = endTimeFormatted,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    Text(
                            text = "Clients: ${session.uniqueClients}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                    )

                    // Client traffic breakdown
                    if (clientTraffic != null) {
                        if (clientTraffic.isNotEmpty()) {
                            Divider()
                            Text(
                                    text = "Client Traffic",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Medium
                            )

                            val sortedClients =
                                    clientTraffic.toList().sortedByDescending {
                                        it.second.first + it.second.second
                                    }

                            sortedClients.forEach { (clientIp, traffic) ->
                                ClientTrafficRow(
                                        clientIp = clientIp,
                                        bytesUp = traffic.first,
                                        bytesDown = traffic.second
                                )
                            }
                        } else {
                            // Empty client traffic (no clients or all zeros)
                            Divider()
                            Text(
                                    text = "No client traffic data for this session.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }
                    } else if (expanded) {
                        // Loading indicator - only show if we're actually loading
                        Text(
                                text = "Loading client traffic...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ClientTrafficRow(clientIp: String, bytesUp: Long, bytesDown: Long) {
    val totalBytes = bytesUp + bytesDown

    Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                    text = clientIp,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary
            )
            Text(
                    text = "↑${formatBytes(bytesUp)} ↓${formatBytes(bytesDown)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace
            )
        }
        Text(
                text = formatBytes(totalBytes),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun UniqueIpTrafficRow(ip: String, bytesUp: Long, bytesDown: Long) {
    val totalBytes = bytesUp + bytesDown

    Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
    ) {
        // IP address - use smaller font and allow natural width
        Text(
                text = ip,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.width(8.dp))
        // Traffic stats - use smaller spacing and compact layout
        Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                    text = "↑${formatBytes(bytesUp)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontFamily = FontFamily.Monospace
            )
            Text(
                    text = formatBytes(totalBytes),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
            )
            Text(
                    text = "↓${formatBytes(bytesDown)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    fontFamily = FontFamily.Monospace
            )
        }
    }
}

// Helper function to format bytes in human-readable format
private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "${bytes}B"
    if (bytes < 1024 * 1024) return "%.1fKB".format(bytes / 1024.0)
    if (bytes < 1024 * 1024 * 1024) return "%.1fMB".format(bytes / (1024.0 * 1024.0))
    return "%.1fGB".format(bytes / (1024.0 * 1024.0 * 1024.0))
}

// Helper function to format speed (bytes per second) in human-readable format
private fun formatSpeed(bytesPerSecond: Double): String {
    val bps = bytesPerSecond
    return when {
        bps < 1024 -> "%.0f B/s".format(bps)
        bps < 1024 * 1024 -> "%.1f KB/s".format(bps / 1024.0)
        bps < 1024 * 1024 * 1024 -> "%.1f MB/s".format(bps / (1024.0 * 1024.0))
        else -> "%.2f GB/s".format(bps / (1024.0 * 1024.0 * 1024.0))
    }
}

// Helper function to format time since last seen
private fun formatTimeSince(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    val seconds = diff / 1000
    val minutes = seconds / 60
    val hours = minutes / 60

    return when {
        seconds < 60 -> "${seconds}s ago"
        minutes < 60 -> "${minutes}m ago"
        hours < 24 -> "${hours}h ago"
        else -> "${hours / 24}d ago"
    }
}

// Helper function to format uptime (duration in milliseconds)
private fun formatUptime(millis: Long): String {
    val totalSeconds = millis / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60

    return when {
        hours > 0 -> "${hours}h ${minutes}m ${seconds}s"
        minutes > 0 -> "${minutes}m ${seconds}s"
        else -> "${seconds}s"
    }
}

// Helper function to format date and time
private fun formatDateTime(timestamp: Long): String {
    val dateFormat =
            java.text.SimpleDateFormat("MMM dd, yyyy HH:mm:ss", java.util.Locale.getDefault())
    return dateFormat.format(java.util.Date(timestamp))
}

private fun formatShortTime(timestamp: Long): String {
    val dateFormat = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
    return dateFormat.format(java.util.Date(timestamp))
}

@Composable
private fun DeviceTrafficCard(
        device: com.example.shieldshare.managers.meter.ClientTrafficStats,
        quotaEnabled: Boolean,
        quotaSnapshot: DeviceQuotaSnapshot?,
        onResetClientQuota: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var showQuotaDialog by remember { mutableStateOf(false) }

    // Use remember to avoid recalculating on every recomposition
    val totalBytes =
            remember(device.totalBytesUp, device.totalBytesDown) {
                device.totalBytesUp + device.totalBytesDown
            }
    Card(
            modifier = Modifier.fillMaxWidth(),
            colors =
                    CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
            shape = RoundedCornerShape(8.dp)
    ) {
        Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Device header - clickable to expand with rounded ripple
            Row(
                    modifier =
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(
                                            indication = rememberRipple(bounded = true),
                                            interactionSource =
                                                    remember { MutableInteractionSource() }
                                    ) { expanded = !expanded },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                            text = device.ipAddress,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                            text = device.macAddress.ifEmpty { "Device ID: ${device.clientId}" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace
                    )
                }
                Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                ) {
                    if (quotaEnabled && quotaSnapshot != null) {
                        QuotaStatusChip(
                                snapshot = quotaSnapshot,
                                onRequestUnblock = {},
                                enableInteraction = false
                        )
                    }
                    Icon(
                            imageVector =
                                    if (expanded) Icons.Default.ExpandLess
                                    else Icons.Default.ExpandMore,
                            contentDescription = if (expanded) "Collapse" else "Expand",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Expanded content
            if (expanded) {
                Divider()

                // Traffic stats
                Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(horizontalAlignment = Alignment.Start, modifier = Modifier.weight(1f)) {
                        Text(
                                text = "↑ Upload",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                                text = formatBytes(device.totalBytesUp),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.weight(1f)
                    ) {
                        Text(
                                text = "Total",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                                text = formatBytes(totalBytes),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.secondary
                        )
                    }
                    Column(horizontalAlignment = Alignment.End, modifier = Modifier.weight(1f)) {
                        Text(
                                text = "↓ Download",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                                text = formatBytes(device.totalBytesDown),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }

                if (quotaEnabled && quotaSnapshot != null) {
                    QuotaUsageBar(snapshot = quotaSnapshot)
                }

                // Speed indicators
                Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(horizontalAlignment = Alignment.Start) {
                        Text(
                                text = "↑ ${formatSpeed(device.currentRateUp)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium,
                                fontFamily = FontFamily.Monospace
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                                text = "↓ ${formatSpeed(device.currentRateDown)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary,
                                fontWeight = FontWeight.Medium,
                                fontFamily = FontFamily.Monospace
                        )
                    }
                }

                // Connection info
                Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                                text = "Connections: ${device.connectionCount}",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Medium
                        )
                        if (device.activeConnections > 0) {
                            Text(
                                    text = "${device.activeConnections} active",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    val shouldShowReset =
                            quotaEnabled &&
                                    quotaSnapshot != null &&
                                    (quotaSnapshot.status == QuotaBadgeStatus.BLOCKED ||
                                            quotaSnapshot.usagePercentage >= 0.999)
                    if (shouldShowReset) {
                        ResetQuotaButton(onReset = { onResetClientQuota(device.ipAddress) })
                    }
                }
            }
        }

        if (showQuotaDialog && quotaSnapshot != null) {
            AlertDialog(
                    onDismissRequest = { showQuotaDialog = false },
                    title = { Text("Client quota status") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                    text =
                                            "IP: ${device.ipAddress}\nUsage: ${formatBytes(quotaSnapshot.usedBytes)} / ${formatBytes(quotaSnapshot.allocatedBytes)}",
                                    style = MaterialTheme.typography.bodyMedium
                            )
                            if (quotaSnapshot.blockedUntil != null &&
                                            quotaSnapshot.status == QuotaBadgeStatus.BLOCKED
                            ) {
                                Text(
                                        text =
                                                "Blocked until ${formatShortTime(quotaSnapshot.blockedUntil)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                    text =
                                            "Select \"Allow client\" to clear the block immediately. Usage is not reset, so the client may be blocked again if they stay above their quota.",
                                    style = MaterialTheme.typography.bodySmall
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(
                                onClick = {
                                    onResetClientQuota(device.ipAddress)
                                    showQuotaDialog = false
                                }
                        ) { Text("Allow client") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showQuotaDialog = false }) { Text("Cancel") }
                    }
            )
        }
    }
}

@Composable
private fun QuotaUsageBar(snapshot: DeviceQuotaSnapshot) {
    val cappedUsedBytes = min(snapshot.usedBytes, snapshot.allocatedBytes)
    val percent =
            if (snapshot.allocatedBytes > 0) {
                cappedUsedBytes.toDouble() / snapshot.allocatedBytes.toDouble()
            } else 0.0
    val clampedPercent = percent.coerceIn(0.0, 1.0)
    val percentLabel = (clampedPercent * 100).roundToInt()
    val barColor =
            when (snapshot.status) {
                QuotaBadgeStatus.ACTIVE -> MaterialTheme.colorScheme.primary
                QuotaBadgeStatus.WARNING -> MaterialTheme.colorScheme.tertiary
                QuotaBadgeStatus.BLOCKED -> MaterialTheme.colorScheme.error
            }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LinearProgressIndicator(
                progress = clampedPercent.toFloat(),
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                color = barColor,
                trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
        )
        Text(
                text =
                        when (snapshot.status) {
                            QuotaBadgeStatus.BLOCKED ->
                                    "${percentLabel}% of ${formatBytes(snapshot.allocatedBytes)} • Blocked"
                            QuotaBadgeStatus.WARNING ->
                                    "${percentLabel}% of ${formatBytes(snapshot.allocatedBytes)} • Near limit"
                            QuotaBadgeStatus.ACTIVE ->
                                    "${percentLabel}% of ${formatBytes(snapshot.allocatedBytes)} used"
                        },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun QuotaStatusChip(
        snapshot: DeviceQuotaSnapshot,
        onRequestUnblock: () -> Unit,
        enableInteraction: Boolean = true
) {
    val (containerColor, contentColor, label, clickable) =
            when (snapshot.status) {
                QuotaBadgeStatus.BLOCKED ->
                        Quadruple(
                                MaterialTheme.colorScheme.errorContainer,
                                MaterialTheme.colorScheme.onErrorContainer,
                                snapshot.blockedUntil?.let {
                                    "Blocked until ${formatShortTime(it)}"
                                }
                                        ?: "Blocked",
                                true
                        )
                QuotaBadgeStatus.WARNING -> {
                    val percent = (snapshot.usagePercentage * 100).coerceAtMost(999.0).roundToInt()
                    Quadruple(
                            MaterialTheme.colorScheme.tertiaryContainer,
                            MaterialTheme.colorScheme.onTertiaryContainer,
                            "$percent% used",
                            false
                    )
                }
                QuotaBadgeStatus.ACTIVE ->
                        Quadruple(
                                MaterialTheme.colorScheme.secondaryContainer,
                                MaterialTheme.colorScheme.onSecondaryContainer,
                                "Active",
                                false
                        )
            }

    val chipModifier =
            if (clickable && enableInteraction) {
                Modifier.clickable { onRequestUnblock() }
            } else {
                Modifier
            }

    Surface(
            color = containerColor,
            contentColor = contentColor,
            shape = RoundedCornerShape(50),
            tonalElevation = 0.dp,
            modifier = chipModifier
    ) {
        Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun ResetQuotaButton(onReset: () -> Unit) {
    Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            shape = RoundedCornerShape(50),
            tonalElevation = 0.dp,
            modifier = Modifier.clickable { onReset() }
    ) {
        Text(
                text = "Reset",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
        )
    }
}

private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

// Helper function to get current time
@Composable
private fun getCurrentTime(): String {
    val formatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    return formatter.format(Date())
}
