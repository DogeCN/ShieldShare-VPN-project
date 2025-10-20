package com.example.shieldshare.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsState()

    LazyColumn(
            modifier =
                    Modifier.fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                            .padding(16.dp)
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
                            text = "VPN Settings",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold
                    )

                    Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                                value = uiState.vpnServerAddress,
                                onValueChange = viewModel::updateVpnServerAddress,
                                label = { Text("VPN IP Address") },
                                modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(onClick = { viewModel.refreshVpnIp() }) {
                            Text("Refresh")
                        }
                    }

                    OutlinedTextField(
                            value = uiState.vpnUsername,
                            onValueChange = viewModel::updateVpnUsername,
                            label = { Text("Username") },
                            modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                            value = uiState.vpnPassword,
                            onValueChange = viewModel::updateVpnPassword,
                            label = { Text("Password") },
                            modifier = Modifier.fillMaxWidth()
                    )
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
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                            text = "Proxy Settings",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold
                    )

                    OutlinedTextField(
                            value = uiState.proxyPort.toString(),
                            onValueChange = { viewModel.updateProxyPort(it.toIntOrNull() ?: 8080) },
                            label = { Text("Port") },
                            modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                                checked = uiState.authEnabled,
                                onCheckedChange = viewModel::updateAuthEnabled
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Enable Authentication")
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
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                            text = "App Settings",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold
                    )

                    Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                                checked = uiState.darkMode,
                                onCheckedChange = viewModel::updateDarkMode
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Dark Mode")
                    }

                    Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                                checked = uiState.notificationsEnabled,
                                onCheckedChange = viewModel::updateNotificationsEnabled
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Enable Notifications")
                    }

                    Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                                checked = uiState.databaseEncryption,
                                onCheckedChange = viewModel::updateDatabaseEncryption
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Enable Database Encryption")
                    }
                }
            }
        }

        item {
            Button(onClick = viewModel::saveSettings, modifier = Modifier.fillMaxWidth()) {
                Text("Save Settings")
            }
        }
    }
}
