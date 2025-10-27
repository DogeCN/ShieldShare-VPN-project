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
                            verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                                checked = uiState.notificationsEnabled,
                                onCheckedChange = viewModel::updateNotificationsEnabled
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Enable Notifications")
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
