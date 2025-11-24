@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.shieldshare.ui.advanced

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Block
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun DynamicQuotaCard(
        enabled: Boolean,
        multiplier: Float,
        averagePeriod: com.example.shieldshare.ui.advanced.AveragePeriod,
        globalAverage: Long,
        abuseStatus: Map<String, com.example.shieldshare.managers.consumption.AbuseCheckResult>,
        onEnabledChanged: (Boolean) -> Unit,
        onMultiplierChanged: (Float) -> Unit,
        onAveragePeriodChanged: (com.example.shieldshare.ui.advanced.AveragePeriod) -> Unit,
        onResetHistory: (String?) -> Unit,
        onRefreshAverages: () -> Unit,
        onBlockClient: (String) -> Unit
) {
        Card(
                modifier = Modifier.fillMaxWidth(),
                colors =
                        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                shape = RoundedCornerShape(12.dp)
        ) {
                Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                        Text(
                                text = "Data Leak & Anomaly Detection",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.SemiBold
                        )

                        Text(
                                text =
                                        "Monitors consumption RATE (bytes/hour) to detect sudden spikes and sustained high-rate transfers that may indicate data leaks, unauthorized access, or compromised devices. Detects patterns early, independent of quota limits.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        // Enable toggle
                        Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                        ) {
                                Text(
                                        text = "Enable Anomaly Detection",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium
                                )
                                Switch(checked = enabled, onCheckedChange = onEnabledChanged)
                        }

                        if (enabled) {
                                Divider()

                                // Multiplier input
                                // Global Baseline Rate Display
                                if (globalAverage > 0) {
                                        Text(
                                                text = "Global Baseline Rate (Fixed)",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                                text = "${formatBytes(globalAverage)}/hour",
                                                style = MaterialTheme.typography.bodyLarge,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.primary
                                        )
                                        Text(
                                                text =
                                                        "Fixed baseline rate (bytes/hour) established from first period. System monitors current rate vs baseline to detect sudden spikes (data leaks).",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Divider()
                                }

                                Text(
                                        text = "Rate Threshold Multiplier",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                ) {
                                        Slider(
                                                value = multiplier,
                                                onValueChange = onMultiplierChanged,
                                                valueRange = 1.0f..10.0f,
                                                steps = 17, // 0.5 increments
                                                modifier = Modifier.weight(1f)
                                        )
                                        Text(
                                                text = String.format("%.1fx", multiplier),
                                                style = MaterialTheme.typography.bodyMedium,
                                                modifier = Modifier.width(50.dp)
                                        )
                                }

                                Text(
                                        text =
                                                "Clients with consumption RATE exceeding ${multiplier}× their baseline rate for 15+ minutes will be flagged (detects sudden spikes and sustained data leaks).",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                if (globalAverage > 0) {
                                        val threshold = (globalAverage * multiplier).toLong()
                                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Text(
                                                        text =
                                                                "Rate threshold: ${formatBytes(threshold)}/hour",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.error,
                                                        fontWeight = FontWeight.Medium
                                                )
                                                Text(
                                                        text =
                                                                "Monitors bytes/hour rate. Flags when current rate > baseline rate × ${multiplier} for sustained period (data leak pattern).",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color =
                                                                MaterialTheme.colorScheme
                                                                        .onSurfaceVariant,
                                                        fontStyle =
                                                                androidx.compose.ui.text.font
                                                                        .FontStyle.Italic
                                                )
                                        }
                                }

                                Divider()

                                // Average Period selection
                                Text(
                                        text = "Averaging Period",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                var expanded by remember { mutableStateOf(false) }

                                ExposedDropdownMenuBox(
                                        expanded = expanded,
                                        onExpandedChange = { expanded = !expanded }
                                ) {
                                        OutlinedTextField(
                                                readOnly = true,
                                                value = averagePeriod.displayName,
                                                onValueChange = {},
                                                trailingIcon = {
                                                        Icon(
                                                                imageVector =
                                                                        Icons.Default.ArrowDropDown,
                                                                contentDescription = null
                                                        )
                                                },
                                                modifier = Modifier.fillMaxWidth().menuAnchor(),
                                                colors =
                                                        ExposedDropdownMenuDefaults
                                                                .outlinedTextFieldColors()
                                        )

                                        ExposedDropdownMenu(
                                                expanded = expanded,
                                                onDismissRequest = { expanded = false }
                                        ) {
                                                com.example.shieldshare.ui.advanced.AveragePeriod
                                                        .values()
                                                        .forEach { period ->
                                                                DropdownMenuItem(
                                                                        text = {
                                                                                Text(
                                                                                        period.displayName
                                                                                )
                                                                        },
                                                                        onClick = {
                                                                                onAveragePeriodChanged(
                                                                                        period
                                                                                )
                                                                                expanded = false
                                                                        },
                                                                        contentPadding =
                                                                                ExposedDropdownMenuDefaults
                                                                                        .ItemContentPadding
                                                                )
                                                        }
                                        }
                                }

                                Text(
                                        text =
                                                "Average consumption calculated over the last ${averagePeriod.days} day(s)",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                Divider()

                                // Abuse Status Display
                                if (abuseStatus.isNotEmpty()) {
                                        Text(
                                                text = "Client Abuse Status",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )

                                        val abusingClients =
                                                abuseStatus.filter { it.value.isAbusing }
                                        val normalClients =
                                                abuseStatus.filter { !it.value.isAbusing }

                                        // Show anomalous clients first (highlighted)
                                        if (abusingClients.isNotEmpty()) {
                                                Text(
                                                        text =
                                                                "Anomalous Activity Detected (${abusingClients.size})",
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        color = MaterialTheme.colorScheme.error,
                                                        fontWeight = FontWeight.Bold
                                                )
                                                abusingClients.forEach { (clientIp, result) ->
                                                        Card(
                                                                modifier = Modifier.fillMaxWidth(),
                                                                colors =
                                                                        CardDefaults.cardColors(
                                                                                containerColor =
                                                                                        MaterialTheme
                                                                                                .colorScheme
                                                                                                .errorContainer
                                                                        )
                                                        ) {
                                                                Column(
                                                                        modifier =
                                                                                Modifier.padding(
                                                                                        12.dp
                                                                                ),
                                                                        verticalArrangement =
                                                                                Arrangement
                                                                                        .spacedBy(
                                                                                                8.dp
                                                                                        )
                                                                ) {
                                                                        Row(
                                                                                modifier =
                                                                                        Modifier.fillMaxWidth(),
                                                                                horizontalArrangement =
                                                                                        Arrangement
                                                                                                .SpaceBetween,
                                                                                verticalAlignment =
                                                                                        Alignment
                                                                                                .CenterVertically
                                                                        ) {
                                                                                Column(
                                                                                        modifier =
                                                                                                Modifier.weight(
                                                                                                        1f
                                                                                                )
                                                                                ) {
                                                                                        Text(
                                                                                                text =
                                                                                                        clientIp,
                                                                                                style =
                                                                                                        MaterialTheme
                                                                                                                .typography
                                                                                                                .bodyMedium,
                                                                                                fontWeight =
                                                                                                        FontWeight
                                                                                                                .Bold
                                                                                        )
                                                                                        Text(
                                                                                                text =
                                                                                                        "Current Rate: ${formatBytes(result.clientAverage)}/hour (${String.format("%.1f", result.abuseRatio)}× baseline rate)",
                                                                                                style =
                                                                                                        MaterialTheme
                                                                                                                .typography
                                                                                                                .bodySmall,
                                                                                                color =
                                                                                                        MaterialTheme
                                                                                                                .colorScheme
                                                                                                                .onErrorContainer
                                                                                        )
                                                                                        Text(
                                                                                                text =
                                                                                                        "Baseline Rate: ${formatBytes(result.globalAverage)}/hour | Threshold: ${formatBytes(result.threshold)}/hour",
                                                                                                style =
                                                                                                        MaterialTheme
                                                                                                                .typography
                                                                                                                .bodySmall,
                                                                                                color =
                                                                                                        MaterialTheme
                                                                                                                .colorScheme
                                                                                                                .onErrorContainer
                                                                                        )
                                                                                        Text(
                                                                                                text =
                                                                                                        "Data leak detected - consumption rate spike sustained for 15+ minutes",
                                                                                                style =
                                                                                                        MaterialTheme
                                                                                                                .typography
                                                                                                                .bodySmall,
                                                                                                color =
                                                                                                        MaterialTheme
                                                                                                                .colorScheme
                                                                                                                .error,
                                                                                                fontWeight =
                                                                                                        FontWeight
                                                                                                                .Medium
                                                                                        )
                                                                                }

                                                                                // Block/Disconnect
                                                                                // Button
                                                                                Button(
                                                                                        onClick = {
                                                                                                onBlockClient(
                                                                                                        clientIp
                                                                                                )
                                                                                        },
                                                                                        colors =
                                                                                                ButtonDefaults
                                                                                                        .buttonColors(
                                                                                                                containerColor =
                                                                                                                        MaterialTheme
                                                                                                                                .colorScheme
                                                                                                                                .error
                                                                                                        ),
                                                                                        modifier =
                                                                                                Modifier.padding(
                                                                                                        start =
                                                                                                                8.dp
                                                                                                )
                                                                                ) {
                                                                                        Icon(
                                                                                                Icons.Default
                                                                                                        .Block,
                                                                                                contentDescription =
                                                                                                        "Block",
                                                                                                modifier =
                                                                                                        Modifier.size(
                                                                                                                18.dp
                                                                                                        )
                                                                                        )
                                                                                        Spacer(
                                                                                                modifier =
                                                                                                        Modifier.width(
                                                                                                                4.dp
                                                                                                        )
                                                                                        )
                                                                                        Text(
                                                                                                "Block",
                                                                                                style =
                                                                                                        MaterialTheme
                                                                                                                .typography
                                                                                                                .labelSmall
                                                                                        )
                                                                                }
                                                                        }
                                                                }
                                                        }
                                                        Spacer(modifier = Modifier.height(8.dp))
                                                }
                                        }

                                        // Show normal clients
                                        if (normalClients.isNotEmpty()) {
                                                Text(
                                                        text =
                                                                "✓ Normal Clients (${normalClients.size})",
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        color = MaterialTheme.colorScheme.primary,
                                                        fontWeight = FontWeight.Medium
                                                )
                                                normalClients.forEach { (clientIp, result) ->
                                                        Row(
                                                                modifier = Modifier.fillMaxWidth(),
                                                                horizontalArrangement =
                                                                        Arrangement.SpaceBetween,
                                                                verticalAlignment =
                                                                        Alignment.CenterVertically
                                                        ) {
                                                                Column(
                                                                        modifier =
                                                                                Modifier.weight(1f)
                                                                ) {
                                                                        Text(
                                                                                text = clientIp,
                                                                                style =
                                                                                        MaterialTheme
                                                                                                .typography
                                                                                                .bodyMedium
                                                                        )
                                                                        Text(
                                                                                text =
                                                                                        "Rate: ${formatBytes(result.clientAverage)}/hour (${String.format("%.1f", result.abuseRatio)}× baseline)",
                                                                                style =
                                                                                        MaterialTheme
                                                                                                .typography
                                                                                                .bodySmall,
                                                                                color =
                                                                                        MaterialTheme
                                                                                                .colorScheme
                                                                                                .onSurfaceVariant
                                                                        )
                                                                }
                                                        }
                                                }
                                        }
                                } else if (globalAverage > 0) {
                                        val threshold = (globalAverage * multiplier).toLong()
                                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Text(
                                                        text =
                                                                "✓ No data leaks detected. All clients are within normal rate patterns.",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.primary,
                                                        fontWeight = FontWeight.Medium
                                                )
                                                Text(
                                                        text =
                                                                "All clients' consumption rates are below threshold of ${formatBytes(threshold)}/hour (${multiplier}× baseline rate).",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color =
                                                                MaterialTheme.colorScheme
                                                                        .onSurfaceVariant
                                                )
                                        }
                                } else {
                                        Text(
                                                text =
                                                        "Establishing baseline rates... Detection will activate after baseline is established (monitoring consumption patterns).",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                }

                                Divider()

                                // Actions
                                Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                        OutlinedButton(
                                                onClick = onRefreshAverages,
                                                modifier = Modifier.weight(1f)
                                        ) { Text("Refresh Status") }
                                        OutlinedButton(
                                                onClick = { onResetHistory(null) },
                                                modifier = Modifier.weight(1f),
                                                colors =
                                                        ButtonDefaults.outlinedButtonColors(
                                                                contentColor =
                                                                        MaterialTheme.colorScheme
                                                                                .error
                                                        )
                                        ) { Text("Reset All History") }
                                }
                        }
                }
        }
}

private fun formatBytes(bytes: Long): String {
        return when {
                bytes < 1024 -> "$bytes B"
                bytes < 1024 * 1024 -> "${bytes / 1024} KB"
                bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
                else -> "${bytes / (1024 * 1024 * 1024)} GB"
        }
}
