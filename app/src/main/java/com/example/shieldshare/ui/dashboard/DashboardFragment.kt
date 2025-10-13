package com.example.shieldshare.ui.dashboard

import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.shieldshare.managers.proxy.ProxyConfig
import com.example.shieldshare.managers.proxy.ProxyType
import kotlinx.coroutines.launch

@Composable
fun DashboardScreen(
    onNavigateToSettings: () -> Unit,
    viewModel: DashboardViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val scope = rememberCoroutineScope()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = "ShieldShare VPN",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "VPN Control",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { 
                                scope.launch { 
                                    viewModel.startVpn() 
                                } 
                            },
                            modifier = Modifier.weight(1f),
                            enabled = !uiState.isVpnConnecting
                        ) {
                            Text("Start VPN")
                        }
                        
                        Button(
                            onClick = { 
                                scope.launch { 
                                    viewModel.stopVpn() 
                                } 
                            },
                            modifier = Modifier.weight(1f),
                            enabled = uiState.isVpnConnected
                        ) {
                            Text("Stop VPN")
                        }
                    }
                    
                    Text(
                        text = "VPN Status: ${uiState.vpnStatus}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Proxy Server",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { 
                                scope.launch { 
                                    viewModel.startProxyServer() 
                                } 
                            },
                            modifier = Modifier.weight(1f),
                            enabled = !uiState.isProxyRunning
                        ) {
                            Text(if (uiState.isProxyRunning) "Proxy Running" else "Start Proxy")
                        }
                        
                        Button(
                            onClick = { 
                                scope.launch { 
                                    viewModel.stopProxyServer() 
                                } 
                            },
                            modifier = Modifier.weight(1f),
                            enabled = uiState.isProxyRunning
                        ) {
                            Text("Stop Proxy")
                        }
                    }
                    
                    if (uiState.isProxyRunning) {
                        Text(
                            text = "Proxy running on port ${uiState.proxyPort}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Traffic Statistics",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    
                    Text(
                        text = "Active Connections: ${uiState.activeConnections}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    
                    Text(
                        text = "Data Transferred: ${uiState.dataTransferred}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        item {
            Button(
                onClick = onNavigateToSettings,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Settings")
            }
        }
    }
}
