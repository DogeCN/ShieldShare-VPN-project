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
import com.example.shieldshare.managers.proxy.ProxyPortManager

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
                            text = "Proxy Settings",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold
                    )

                    Text(
                            text =
                                    "HTTP/HTTPS ${ProxyPortManager.HTTP_PORT}, SOCKS5 ${ProxyPortManager.SOCKS5_PORT}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
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
