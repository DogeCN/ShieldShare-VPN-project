package com.example.shieldshare.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.shieldshare.R
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(viewModel: HomeViewModel = hiltViewModel()) {
        val uiState by viewModel.uiState.collectAsState()
        val scope = rememberCoroutineScope()

        Box(modifier = Modifier.fillMaxSize()) {
                // Gradient background
                Box(
                        modifier =
                                Modifier.fillMaxSize()
                                        .background(
                                                brush =
                                                        Brush.verticalGradient(
                                                                colors =
                                                                        listOf(
                                                                                MaterialTheme
                                                                                        .colorScheme
                                                                                        .primary
                                                                                        .copy(
                                                                                                alpha =
                                                                                                        0.12f
                                                                                        ),
                                                                                MaterialTheme
                                                                                        .colorScheme
                                                                                        .secondary
                                                                                        .copy(
                                                                                                alpha =
                                                                                                        0.06f
                                                                                        ),
                                                                                MaterialTheme
                                                                                        .colorScheme
                                                                                        .background
                                                                        )
                                                        )
                                        )
                )

                // World map background
                Image(
                        painter = painterResource(id = R.drawable.world),
                        contentDescription = null,
                        colorFilter =
                                ColorFilter.tint(
                                        MaterialTheme.colorScheme.tertiary.copy(alpha = 0.1f)
                                ),
                        modifier = Modifier.fillMaxSize().offset(y = (-180).dp),
                        contentScale = ContentScale.Fit
                )

                // Main content
                Box(
                        modifier =
                                Modifier.fillMaxSize()
                                        .padding(horizontal = 20.dp)
                                        .safeDrawingPadding(),
                ) {
                        // Header
                        Column(
                                modifier =
                                        Modifier.fillMaxSize()
                                                .padding(horizontal = 20.dp)
                                                .safeDrawingPadding(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                                Spacer(modifier = Modifier.height(6.dp))

                                Text(
                                        text = "ShieldShare VPN",
                                        style = MaterialTheme.typography.headlineMedium,
                                        fontWeight = FontWeight.ExtraBold,
                                        textAlign = TextAlign.Center,
                                        color = MaterialTheme.colorScheme.onBackground,
                                        modifier = Modifier.fillMaxWidth()
                                )

                                Text(
                                        text = "Shareable • Fast • Private",
                                        style = MaterialTheme.typography.bodyMedium,
                                        textAlign = TextAlign.Center,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.fillMaxWidth()
                                )

                                // TODO: timer is hardcoded, replace when infrastructure is complete
                                if (uiState.isVpnConnected) {
                                        Text(
                                                text = "00:00:00",
                                                style = MaterialTheme.typography.headlineSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onBackground
                                        )
                                }
                        }

                        // Shows IP Address
                        IpAddressRow(
                                ip = uiState.ipAddress,
                                loading = uiState.isFetchingIp,
                                onRefresh = { viewModel.refreshIp() }
                        )

                        // Controls and stats
                        Column(
                                modifier =
                                        Modifier.align(Alignment.BottomCenter)
                                                .fillMaxWidth()
                                                .padding(bottom = 16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                                // Button
                                ConnectButton(
                                        isConnected = uiState.isVpnConnected,
                                        isConnecting = uiState.isVpnConnecting,
                                        onClick = {
                                                scope.launch {
                                                        if (uiState.isVpnConnected) {
                                                                viewModel.stopVpn()
                                                        } else {
                                                                viewModel.startVpn()
                                                        }
                                                }
                                        }
                                )

                                // Status card
                                StatusCard(
                                        isConnected = uiState.isVpnConnected,
                                        uploadSpeed = uiState.uploadSpeed,
                                        downloadSpeed = uiState.downloadSpeed,
                                        connections = uiState.activeConnections,
                                        latency = uiState.latency
                                )

                                // Control cards
                                Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                        // Proxy Server Card with Status Indicator
                                        var showQrDialog by remember { mutableStateOf(false) }

                                        ProxyStatusCard(
                                                modifier = Modifier.weight(1f).height(160.dp),
                                                icon = Icons.Default.Wifi,
                                                title = "Proxy Server",
                                                subtitle =
                                                        if (!uiState.isHotspotEnabled)
                                                                "Hotspot required"
                                                        else if (uiState.isProxyRunning)
                                                                "Port ${uiState.proxyPort}"
                                                        else "Not running",
                                                isActive = uiState.isProxyRunning,
                                                isDisabled = !uiState.isHotspotEnabled,
                                                onQrClick = { showQrDialog = true }
                                        )

                                        // QR Code Dialog
                                        if (showQrDialog) {
                                                QrCodeDialog(
                                                        onDismiss = { showQrDialog = false },
                                                        viewModel = viewModel,
                                                        uiState = uiState
                                                )
                                        }

                                        // Hotspot Card
                                        ControlCard(
                                                modifier = Modifier.weight(1f).height(160.dp),
                                                icon = Icons.Default.WifiTethering,
                                                title = "Hotspot",
                                                subtitle =
                                                        if (uiState.isHotspotEnabled)
                                                                "${uiState.hotspotClients} clients"
                                                        else "Not configured",
                                                isActive = uiState.isHotspotEnabled,
                                                onStart = { viewModel.openHotspotSettings() },
                                                onStop = { viewModel.openHotspotSettings() }
                                        )
                                }
                        }
                }
        }
}

@Composable
fun ConnectButton(isConnected: Boolean, isConnecting: Boolean, onClick: () -> Unit) {
        val buttonText =
                when {
                        isConnecting -> "Connecting..."
                        isConnected -> "Disconnect"
                        else -> "Connect VPN"
                }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // Main button
                Button(
                        onClick = onClick,
                        modifier =
                                Modifier.size(200.dp)
                                        .clip(CircleShape)
                                        .border(
                                                width = 18.dp,
                                                color =
                                                        if (isConnected)
                                                                MaterialTheme.colorScheme.onPrimary
                                                                        .copy(
                                                                                alpha = 0.2f
                                                                        ) // Light border when
                                                        // connected
                                                        else
                                                                MaterialTheme.colorScheme.primary
                                                                        .copy(alpha = 0.1f),
                                                shape = CircleShape
                                        )
                                        .clip(CircleShape),
                        colors =
                                ButtonDefaults.buttonColors(
                                        containerColor =
                                                if (isConnected) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.surfaceVariant
                                ),
                        enabled = !isConnecting
                ) {
                        Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                        ) {
                                Icon(
                                        imageVector = Icons.Default.PowerSettingsNew,
                                        contentDescription = null,
                                        modifier = Modifier.size(40.dp),
                                        tint =
                                                if (isConnected) MaterialTheme.colorScheme.onPrimary
                                                else MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                        text = buttonText,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color =
                                                if (isConnected) MaterialTheme.colorScheme.onPrimary
                                                else MaterialTheme.colorScheme.primary,
                                        textAlign = TextAlign.Center
                                )
                        }
                }
        }
}

@Composable
fun StatusCard(
        isConnected: Boolean,
        uploadSpeed: String,
        downloadSpeed: String,
        connections: Int,
        latency: String
) {
        Card(
                modifier = Modifier.fillMaxWidth(),
                colors =
                        CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.onTertiary
                        ),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                shape = RoundedCornerShape(20.dp)
        ) {
                Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                ) {
                        // Status indicator
                        Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                        ) {
                                Icon(
                                        imageVector =
                                                if (isConnected) Icons.Default.Security
                                                else Icons.Outlined.Security,
                                        contentDescription = null,
                                        tint =
                                                if (isConnected) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.outline,
                                        modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                        text = if (isConnected) "Connected" else "Not Connected",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color =
                                                if (isConnected) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Stats
                        Row(
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                        ) {
                                // Upload
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                                text = "Upload",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                                text = uploadSpeed,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onBackground,
                                                fontWeight = FontWeight.Medium
                                        )
                                }

                                // Vertical Bar
                                Box(
                                        modifier =
                                                Modifier.width(1.dp)
                                                        .height(40.dp)
                                                        .background(
                                                                MaterialTheme.colorScheme.outline
                                                        )
                                )

                                // Download
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                                text = "Download",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                                text = downloadSpeed,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onBackground,
                                                fontWeight = FontWeight.Medium
                                        )
                                }

                                // Vertical Bar
                                Box(
                                        modifier =
                                                Modifier.width(1.dp)
                                                        .height(40.dp)
                                                        .background(
                                                                MaterialTheme.colorScheme.outline
                                                        )
                                )

                                // Connections
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                                text = "Connections",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                                text = connections.toString(),
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onBackground,
                                                fontWeight = FontWeight.Medium
                                        )
                                }

                                // Vertical Bar
                                Box(
                                        modifier =
                                                Modifier.width(1.dp)
                                                        .height(40.dp)
                                                        .background(
                                                                MaterialTheme.colorScheme.outline
                                                        )
                                )

                                // Latency
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                                text = "Latency",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                                text = latency,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onBackground,
                                                fontWeight = FontWeight.Medium
                                        )
                                }
                        }
                }
        }
}

@Composable
fun ControlCard(
        modifier: Modifier = Modifier,
        icon: ImageVector,
        title: String,
        subtitle: String,
        isActive: Boolean,
        onStart: () -> Unit,
        onStop: () -> Unit,
        isDisabled: Boolean = false
) {
        Card(
                modifier = modifier,
                colors =
                        CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.onTertiary
                        ),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                shape = RoundedCornerShape(16.dp)
        ) {
                Box(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        // Reserve space for alignment (same as ProxyStatusCard)
                        Box(modifier = Modifier.align(Alignment.TopEnd).size(32.dp)) {
                                // Empty space for alignment - no QR icon for Hotspot
                        }

                        Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                                Icon(
                                        imageVector = icon,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                        text = title,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(3.dp))
                                Text(
                                        text = subtitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Button(
                                        onClick = if (isActive) onStop else onStart,
                                        colors =
                                                ButtonDefaults.buttonColors(
                                                        containerColor =
                                                                if (isActive)
                                                                        MaterialTheme.colorScheme
                                                                                .error
                                                                else
                                                                        MaterialTheme.colorScheme
                                                                                .primary
                                                ),
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.fillMaxWidth()
                                ) {
                                        Text(
                                                text = if (isActive) "Stop" else "Start",
                                                color = MaterialTheme.colorScheme.onPrimary,
                                                fontWeight = FontWeight.Medium
                                        )
                                }
                        }
                }
        }
}

@Composable
private fun IpAddressRow(ip: String?, loading: Boolean, onRefresh: () -> Unit) {
        Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 100.dp),
                verticalAlignment = Alignment.CenterVertically
        ) {
                Text(text = "IP Address", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.width(12.dp))
                if (loading) {
                        CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                        )
                } else {
                        Text(text = ip ?: "—", style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onRefresh, enabled = !loading) { Text("Refresh") }
        }
}

@Composable
fun QrCodeDialog(onDismiss: () -> Unit, viewModel: HomeViewModel, uiState: HomeUiState) {
        AlertDialog(
                onDismissRequest = onDismiss,
                title = {
                        Text(
                                text = "Client Configuration",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.SemiBold
                        )
                },
                text = {
                        Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                                Text(
                                        text =
                                                "Scan this QR code to get proxy configuration instructions",
                                        style = MaterialTheme.typography.bodyMedium,
                                        textAlign = TextAlign.Center,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                // QR Code Display
                                val qrCode =
                                        remember(uiState.ipAddress, uiState.proxyPort) {
                                                viewModel.generateQRCode()
                                        }

                                qrCode?.let { qrBitmap ->
                                        Image(
                                                bitmap = qrBitmap,
                                                contentDescription = "Proxy Configuration QR Code",
                                                modifier =
                                                        Modifier.size(250.dp)
                                                                .clip(RoundedCornerShape(8.dp))
                                                                .border(
                                                                        1.dp,
                                                                        MaterialTheme.colorScheme
                                                                                .outline,
                                                                        RoundedCornerShape(8.dp)
                                                                )
                                        )
                                }

                                // Manual Configuration Information
                                Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        colors =
                                                CardDefaults.cardColors(
                                                        containerColor =
                                                                MaterialTheme.colorScheme
                                                                        .surfaceVariant
                                                ),
                                        shape = RoundedCornerShape(8.dp)
                                ) {
                                        Column(
                                                modifier = Modifier.padding(12.dp),
                                                verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                                Text(
                                                        text = "📱 Manual Proxy Setup:",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        fontWeight = FontWeight.Bold,
                                                        color =
                                                                MaterialTheme.colorScheme
                                                                        .onSurfaceVariant
                                                )

                                                Text(
                                                        text =
                                                                "Server: ${viewModel.getHotspotIp()}\nPort: ${uiState.proxyPort}",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.primary,
                                                        fontFamily =
                                                                androidx.compose.ui.text.font
                                                                        .FontFamily.Monospace
                                                )

                                                Text(
                                                        text =
                                                                "Configure these settings in your device's Wi-Fi proxy settings",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color =
                                                                MaterialTheme.colorScheme
                                                                        .onSurfaceVariant
                                                )

                                                Spacer(modifier = Modifier.height(8.dp))

                                                Text(
                                                        text =
                                                                "🔄 Alternative: PAC Auto-Configuration",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        fontWeight = FontWeight.Bold,
                                                        color =
                                                                MaterialTheme.colorScheme
                                                                        .onSurfaceVariant
                                                )

                                                Text(
                                                        text =
                                                                "PAC URL: http://${viewModel.getHotspotIp()}:${uiState.proxyPort}/proxy.pac",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.primary,
                                                        fontFamily =
                                                                androidx.compose.ui.text.font
                                                                        .FontFamily.Monospace
                                                )

                                                Text(
                                                        text =
                                                                "Use this URL in your device's Proxy Auto-Configuration settings for automatic setup",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color =
                                                                MaterialTheme.colorScheme
                                                                        .onSurfaceVariant
                                                )
                                        }
                                }
                        }
                },
                confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
        )
}

@Composable
fun ProxyStatusCard(
        modifier: Modifier = Modifier,
        icon: ImageVector,
        title: String,
        subtitle: String,
        isActive: Boolean,
        isDisabled: Boolean = false,
        onQrClick: () -> Unit = {}
) {
        Card(
                modifier = modifier,
                colors =
                        CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.onTertiary
                        ),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                shape = RoundedCornerShape(16.dp)
        ) {
                Box(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        // Reserve space for alignment (same as ControlCard)
                        Box(modifier = Modifier.align(Alignment.TopEnd).size(32.dp)) {
                                // Empty space for alignment
                        }

                        Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                                Icon(
                                        imageVector = icon,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                        text = title,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(3.dp))
                                Text(
                                        text = subtitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(8.dp))

                                // QR Code Icon (replaces status indicator)
                                IconButton(
                                        onClick = { if (!isDisabled) onQrClick() },
                                        enabled = !isDisabled,
                                        modifier = Modifier.size(48.dp)
                                ) {
                                        Icon(
                                                imageVector = Icons.Default.QrCode,
                                                contentDescription =
                                                        if (isDisabled)
                                                                "QR Code disabled - Hotspot required"
                                                        else "Show QR Code",
                                                tint =
                                                        if (isDisabled)
                                                                MaterialTheme.colorScheme
                                                                        .onSurfaceVariant
                                                        else MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(32.dp)
                                        )
                                }
                        }
                }
        }
}
