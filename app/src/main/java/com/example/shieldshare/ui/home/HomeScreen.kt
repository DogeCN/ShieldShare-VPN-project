package com.example.shieldshare.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiTethering
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
import androidx.compose.material.icons.filled.Refresh
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
                                                .padding(horizontal = 12.dp)
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

                                // IP Addresses Section
                                IpAddressRow(
                                        localIp = uiState.localIpAddress,
                                        publicIp = uiState.publicIpAddress,
                                        loading = uiState.isFetchingIp,
                                        onRefresh = { viewModel.refreshIp() }
                                )
                        }

                        // Controls and stats
                        Column(
                                modifier =
                                        Modifier.align(Alignment.BottomCenter)
                                                .fillMaxWidth()
                                                .padding(bottom = 16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                                // VPN Status Card
                                VpnStatusCard(
                                        isConnected = uiState.isVpnConnected,
                                        onClick = {
                                                scope.launch {
                                                        viewModel.startVpn()
                                                }
                                        }
                                )

                                // Status card
                                StatusCard(
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
                                                                "HTTP/HTTPS ${uiState.httpPort} · SOCKS5 ${uiState.socks5Port}"
                                                        else "Not running",
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
fun VpnStatusCard(isConnected: Boolean, onClick: () -> Unit) {
        Card(
                modifier = Modifier.fillMaxWidth().height(210.dp),
                colors =
                        CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.onTertiary
                        ),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                shape = RoundedCornerShape(20.dp)
        ) {
                Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                        // Status Icon
                        Icon(
                                imageVector = Icons.Default.Security,
                                contentDescription = null,
                                modifier = Modifier.size(45.dp),
                                tint =
                                        if (isConnected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        // Status Text
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                        text = if (isConnected) "VPN Protected" else "No VPN Detected",
                                        style = MaterialTheme.typography.headlineSmall,
                                        fontWeight = FontWeight.Bold,
                                        color =
                                                if (isConnected)
                                                        MaterialTheme.colorScheme.primary
                                                else
                                                        MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                        text =
                                                if (isConnected)
                                                        "Your connection is secured"
                                                else
                                                        "Connect a VPN to get started",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center
                                )
                        }

                        // Action Button
                        Button(
                                onClick = onClick,
                                modifier = Modifier.fillMaxWidth(),
                                colors =
                                        ButtonDefaults.buttonColors(
                                                containerColor =
                                                        if (isConnected)
                                                                MaterialTheme.colorScheme.error
                                                        else MaterialTheme.colorScheme.primary
                                        ),
                                shape = RoundedCornerShape(12.dp)
                        ) {
                                Text(
                                        text = if (isConnected) "Open VPN Settings" else "Setup VPN",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold
                                )
                        }
                }
        }
}

@Composable
fun StatusCard(
        uploadSpeed: String,
        downloadSpeed: String,
        connections: Int,
        latency: String
) {
        Card(
                modifier = Modifier.fillMaxWidth().height(70.dp),
                colors =
                        CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.onTertiary
                        ),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                shape = RoundedCornerShape(20.dp)
        ) {

                        // Stats
                        Row( modifier = Modifier.fillMaxWidth().padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically,
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

@Composable
fun ControlCard(
        modifier: Modifier = Modifier,
        icon: ImageVector,
        title: String,
        subtitle: String,
        isActive: Boolean,
        onStart: () -> Unit,
        onStop: () -> Unit,
) {
        Card(
                modifier = modifier,
                colors =
                        CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.onTertiary
                        ),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                shape = RoundedCornerShape(20.dp)
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
                                Spacer(modifier = Modifier.height(10.dp))
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
private fun IpAddressRow(localIp: String?, publicIp: String?, loading: Boolean, onRefresh: () -> Unit) {
        Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
        ) {
                // Left side - IP addresses
                Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                        // Local IP Row
                        Row(
                                verticalAlignment = Alignment.CenterVertically
                        ) {
                                Text(text = "Local IP", style = MaterialTheme.typography.bodyMedium)
                                Spacer(Modifier.width(12.dp))
                                if (loading) {
                                        CircularProgressIndicator(
                                                modifier = Modifier.size(18.dp),
                                                strokeWidth = 2.dp
                                        )
                                } else {
                                        Text(text = localIp ?: "—", style = MaterialTheme.typography.bodyMedium)
                                }
                        }
                        
                        // Public IP Row
                        Row(
                                verticalAlignment = Alignment.CenterVertically
                        ) {
                                Text(text = "Public IP", style = MaterialTheme.typography.bodyMedium)
                                Spacer(Modifier.width(12.dp))
                                if (loading) {
                                        CircularProgressIndicator(
                                                modifier = Modifier.size(18.dp),
                                                strokeWidth = 2.dp
                                        )
                                } else {
                                        Text(text = publicIp ?: "—", style = MaterialTheme.typography.bodyMedium)
                                }
                        }
                }
                
                // Right side - Refresh icon
                IconButton(
                        onClick = onRefresh, 
                        enabled = !loading,
                        modifier = Modifier.size(32.dp)
                ) {
                        Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Refresh IP addresses",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                        )
                }
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
                                        remember(
                                                uiState.localIpAddress,
                                                uiState.configPortalPort,
                                                uiState.isProxyRunning
                                        ) {
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
                                                        text = "Manual Proxy Setup:",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        fontWeight = FontWeight.Bold,
                                                        color =
                                                                MaterialTheme.colorScheme
                                                                        .onSurfaceVariant
                                                )

                                                Text(
                                                        text =
                                                                buildString {
                                                                    appendLine("Server: ${viewModel.getHotspotIp()}")
                                                                    appendLine("HTTP/HTTPS: ${uiState.httpPort}")
                                                                    append("SOCKS5: ${uiState.socks5Port}")
                                                                },
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
                                                                "Alternative: PAC Auto-Configuration",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        fontWeight = FontWeight.Bold,
                                                        color =
                                                                MaterialTheme.colorScheme
                                                                        .onSurfaceVariant
                                                )

                                                Text(
                                                        text =
                                                                "PAC URL: ${
                                                                        uiState.pacUrl
                                                                                ?: "http://${viewModel.getHotspotIp()}:${uiState.configPortalPort}/proxy.pac"
                                                                }",
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
                shape = RoundedCornerShape(20.dp)
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
