package com.example.shieldshare.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.shieldshare.ui.theme.ThemeMode
import com.example.shieldshare.managers.proxy.ProxyPortManager

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    onThemeChanged: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Show validation error snackbar
    LaunchedEffect(uiState.validationError) {
        uiState.validationError?.let { error ->
            snackbarHostState.showSnackbar(
                message = error,
                duration = SnackbarDuration.Short
            )
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
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
                            text = "Note: Restart proxy server for changes to take effect",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                    )

                    // HTTP/HTTPS Proxy Toggle
                    Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                    text = "HTTP/HTTPS Proxy",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                            )
                            Text(
                                    text = "Port ${ProxyPortManager.HTTP_PORT}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                                checked = uiState.httpHttpsEnabled,
                                onCheckedChange = { enabled ->
                                    viewModel.updateHttpHttpsEnabled(enabled)
                                },
                                enabled = uiState.socks5Enabled || !uiState.httpHttpsEnabled // Disable only when SOCKS5 is off and HTTP/HTTPS is on
                        )
                    }

                    // SOCKS5 Proxy Toggle
                    Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                    text = "SOCKS5 Proxy",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                            )
                            Text(
                                    text = "Port ${ProxyPortManager.SOCKS5_PORT}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                                checked = uiState.socks5Enabled,
                                onCheckedChange = { enabled ->
                                    viewModel.updateSocks5Enabled(enabled)
                                },
                                enabled = uiState.httpHttpsEnabled || !uiState.socks5Enabled // Disable only when HTTP/HTTPS is off and SOCKS5 is on
                        )
                    }

                    Divider()

                    // Authentication Toggle
                    Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                                text = "Enable Authentication",
                                style = MaterialTheme.typography.bodyMedium
                        )
                        Switch(
                                checked = uiState.authEnabled,
                                onCheckedChange = { enabled ->
                                    viewModel.updateAuthEnabled(enabled)
                                }
                        )
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

                    // Theme selection dropdown
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                                text = "Theme",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        ThemeDropdown(
                                selectedTheme = uiState.themeMode,
                                onThemeSelected = viewModel::updateThemeMode
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                                text = "Enable Notifications",
                                style = MaterialTheme.typography.bodyMedium
                        )
                        Switch(
                                checked = uiState.notificationsEnabled,
                                onCheckedChange = { enabled ->
                                    viewModel.updateNotificationsEnabled(enabled)
                                }
                        )
                    }
                }
            }
        }

        item {
            Button(
                onClick = {
                    viewModel.saveSettings()
                    onThemeChanged() // Trigger theme reload after saving
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Save Settings")
            }
        }
        }
    }
}

@Composable
fun ThemeDropdown(
    selectedTheme: ThemeMode,
    onThemeSelected: (ThemeMode) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            readOnly = true,
            value = selectedTheme.displayName,
            onValueChange = {},
            trailingIcon = {
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = null
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors()
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            ThemeMode.values().forEach { theme ->
                DropdownMenuItem(
                    text = { Text(theme.displayName) },
                    onClick = {
                        onThemeSelected(theme)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                )
            }
        }
    }
}

private val ThemeMode.displayName: String
    get() = when (this) {
        ThemeMode.SYSTEM -> "Follow system"
        ThemeMode.LIGHT -> "Light"
        ThemeMode.DARK -> "Dark"
    }
