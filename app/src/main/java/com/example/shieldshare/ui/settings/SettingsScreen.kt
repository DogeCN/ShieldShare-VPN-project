package com.example.shieldshare.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.example.shieldshare.managers.proxy.ProxyPortManager
import com.example.shieldshare.ui.theme.ThemeMode

@Composable
fun SettingsScreen(
        navController: NavController? = null,
        viewModel: SettingsViewModel = hiltViewModel(),
        onThemeChanged: () -> Unit = {}
) {
        val uiState by viewModel.uiState.collectAsState()
        val snackbarHostState = remember { SnackbarHostState() }
        var showClearConfirmationDialog by remember { mutableStateOf(false) }
        var showQuotaConfigDialog by remember { mutableStateOf(false) }

        // Local state for quota dialog
        var quotaBandwidthText by remember { mutableStateOf("") }
        var quotaBlockHoursText by remember {
                mutableStateOf(uiState.quotaBlockDurationHours.toString())
        }

        // Auto-detect bandwidth when dialog opens (if not already set)
        LaunchedEffect(showQuotaConfigDialog) {
                if (showQuotaConfigDialog) {
                        // If user already has a value, use it; otherwise auto-detect
                        if (uiState.quotaTotalBandwidthMb > 0) {
                                quotaBandwidthText = uiState.quotaTotalBandwidthMb.toString()
                        } else {
                                // Auto-detect bandwidth
                                val detected = viewModel.detectBandwidth()
                                quotaBandwidthText = detected?.toString() ?: ""
                        }
                        quotaBlockHoursText = uiState.quotaBlockDurationHours.toString()
                }
        }

        // Show validation error snackbar
        LaunchedEffect(uiState.validationError) {
                uiState.validationError?.let { error ->
                        snackbarHostState.showSnackbar(
                                message = error,
                                duration = SnackbarDuration.Short
                        )
                }
        }

        // Show clear database success snackbar
        LaunchedEffect(uiState.clearDatabaseSuccess) {
                if (uiState.clearDatabaseSuccess) {
                        snackbarHostState.showSnackbar(
                                message = "Traffic data cleared successfully",
                                duration = SnackbarDuration.Short
                        )
                        viewModel.resetClearDatabaseState()
                }
        }

        // Show clear database error snackbar
        LaunchedEffect(uiState.clearDatabaseError) {
                uiState.clearDatabaseError?.let { error ->
                        snackbarHostState.showSnackbar(
                                message = error,
                                duration = SnackbarDuration.Long
                        )
                        viewModel.resetClearDatabaseState()
                }
        }

        Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { paddingValues ->
                LazyColumn(
                        modifier =
                                Modifier.fillMaxSize()
                                        .padding(paddingValues)
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
                                                        containerColor =
                                                                MaterialTheme.colorScheme.surface
                                                ),
                                        elevation =
                                                CardDefaults.cardElevation(defaultElevation = 4.dp),
                                        shape = RoundedCornerShape(12.dp)
                                ) {
                                        Column(
                                                modifier = Modifier.padding(16.dp),
                                                verticalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                                Text(
                                                        text = "Proxy Settings",
                                                        style =
                                                                MaterialTheme.typography
                                                                        .headlineSmall,
                                                        fontWeight = FontWeight.SemiBold
                                                )

                                                Text(
                                                        text =
                                                                "Note: Restart proxy server for changes to take effect",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.primary,
                                                )

                                                // HTTP/HTTPS Proxy Toggle
                                                Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement =
                                                                Arrangement.SpaceBetween,
                                                        verticalAlignment =
                                                                Alignment.CenterVertically
                                                ) {
                                                        Column(modifier = Modifier.weight(1f)) {
                                                                Text(
                                                                        text = "HTTP/HTTPS Proxy",
                                                                        style =
                                                                                MaterialTheme
                                                                                        .typography
                                                                                        .bodyMedium,
                                                                        fontWeight =
                                                                                FontWeight.Medium
                                                                )
                                                                Text(
                                                                        text =
                                                                                "Port ${ProxyPortManager.HTTP_PORT}",
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
                                                        Switch(
                                                                checked = uiState.httpHttpsEnabled,
                                                                onCheckedChange = { enabled ->
                                                                        viewModel
                                                                                .updateHttpHttpsEnabled(
                                                                                        enabled
                                                                                )
                                                                },
                                                                enabled =
                                                                        uiState.socks5Enabled ||
                                                                                !uiState.httpHttpsEnabled // Disable only when
                                                                // SOCKS5 is off and
                                                                // HTTP/HTTPS is on
                                                                )
                                                }

                                                // SOCKS5 Proxy Toggle
                                                Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement =
                                                                Arrangement.SpaceBetween,
                                                        verticalAlignment =
                                                                Alignment.CenterVertically
                                                ) {
                                                        Column(modifier = Modifier.weight(1f)) {
                                                                Text(
                                                                        text = "SOCKS5 Proxy",
                                                                        style =
                                                                                MaterialTheme
                                                                                        .typography
                                                                                        .bodyMedium,
                                                                        fontWeight =
                                                                                FontWeight.Medium
                                                                )
                                                                Text(
                                                                        text =
                                                                                "Port ${ProxyPortManager.SOCKS5_PORT}",
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
                                                        Switch(
                                                                checked = uiState.socks5Enabled,
                                                                onCheckedChange = { enabled ->
                                                                        viewModel
                                                                                .updateSocks5Enabled(
                                                                                        enabled
                                                                                )
                                                                },
                                                                enabled =
                                                                        uiState.httpHttpsEnabled ||
                                                                                !uiState.socks5Enabled // Disable only when
                                                                // HTTP/HTTPS is off and
                                                                // SOCKS5 is on
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

                    if (uiState.authEnabled) {
                        OutlinedTextField(
                                value = uiState.authUsername,
                                onValueChange = { viewModel.updateAuthUsername(it) },
                                label = { Text("Proxy Username") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                        )
                        OutlinedTextField(
                                value = uiState.authPassword,
                                onValueChange = { viewModel.updateAuthPassword(it) },
                                label = { Text("Proxy Password") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                        )
                        Text(
                                text = "Credentials are stored securely on this device. Share them with clients that should use the proxy.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
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
                                                        containerColor =
                                                                MaterialTheme.colorScheme.surface
                                                ),
                                        elevation =
                                                CardDefaults.cardElevation(defaultElevation = 4.dp),
                                        shape = RoundedCornerShape(12.dp)
                                ) {
                                        Column(
                                                modifier = Modifier.padding(16.dp),
                                                verticalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                                Text(
                                                        text = "App Settings",
                                                        style =
                                                                MaterialTheme.typography
                                                                        .headlineSmall,
                                                        fontWeight = FontWeight.SemiBold
                                                )

                                                // Theme selection dropdown
                                                Column(modifier = Modifier.fillMaxWidth()) {
                                                        Text(
                                                                text = "Theme",
                                                                style =
                                                                        MaterialTheme.typography
                                                                                .labelMedium,
                                                                color =
                                                                        MaterialTheme.colorScheme
                                                                                .onSurfaceVariant
                                                        )
                                                        Spacer(modifier = Modifier.height(8.dp))

                                                        ThemeDropdown(
                                                                selectedTheme = uiState.themeMode,
                                                                onThemeSelected =
                                                                        viewModel::updateThemeMode
                                                        )
                                                }

                                                Spacer(modifier = Modifier.height(4.dp))

                                                Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement =
                                                                Arrangement.SpaceBetween,
                                                        verticalAlignment =
                                                                Alignment.CenterVertically
                                                ) {
                                                        Text(
                                                                text = "Enable Notifications",
                                                                style =
                                                                        MaterialTheme.typography
                                                                                .bodyMedium
                                                        )
                                                        Switch(
                                                                checked =
                                                                        uiState.notificationsEnabled,
                                                                onCheckedChange = { enabled ->
                                                                        viewModel
                                                                                .updateNotificationsEnabled(
                                                                                        enabled
                                                                                )
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
                                                        containerColor =
                                                                MaterialTheme.colorScheme.surface
                                                ),
                                        elevation =
                                                CardDefaults.cardElevation(defaultElevation = 4.dp),
                                        shape = RoundedCornerShape(12.dp)
                                ) {
                                        Column(
                                                modifier = Modifier.padding(16.dp),
                                                verticalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                                Text(
                                                        text = "Traffic Quota & Regulations",
                                                        style =
                                                                MaterialTheme.typography
                                                                        .headlineSmall,
                                                        fontWeight = FontWeight.SemiBold
                                                )

                                                Text(
                                                        text =
                                                                "Limit bandwidth usage per client. Quotas are shared equally among all connected clients.",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color =
                                                                MaterialTheme.colorScheme
                                                                        .onSurfaceVariant
                                                )

                                                // Enable Quota Toggle
                                                Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement =
                                                                Arrangement.SpaceBetween,
                                                        verticalAlignment =
                                                                Alignment.CenterVertically
                                                ) {
                                                        Column(modifier = Modifier.weight(1f)) {
                                                                Text(
                                                                        text =
                                                                                "Enable Traffic Quota",
                                                                        style =
                                                                                MaterialTheme
                                                                                        .typography
                                                                                        .bodyMedium,
                                                                        fontWeight =
                                                                                FontWeight.Medium
                                                                )
                                                                Text(
                                                                        text =
                                                                                if (uiState.quotaEnabled &&
                                                                                                uiState.quotaTotalBandwidthMb >
                                                                                                        0
                                                                                ) {
                                                                                        "${uiState.quotaTotalBandwidthMb} MB total, ${uiState.quotaBlockDurationHours}h block"
                                                                                } else {
                                                                                        "Limit bandwidth usage per client"
                                                                                },
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
                                                        Switch(
                                                                checked = uiState.quotaEnabled,
                                                                onCheckedChange = { enabled ->
                                                                        viewModel
                                                                                .updateQuotaEnabled(
                                                                                        enabled
                                                                                )
                                                                        if (enabled) {
                                                                                showQuotaConfigDialog =
                                                                                        true
                                                                        }
                                                                }
                                                        )
                                                }

                                                // Configure button (only show when enabled)
                                                if (uiState.quotaEnabled) {
                                                        Row(
                                                                modifier = Modifier.fillMaxWidth(),
                                                                horizontalArrangement =
                                                                        Arrangement.spacedBy(8.dp)
                                                        ) {
                                                                Button(
                                                                        onClick = {
                                                                                showQuotaConfigDialog =
                                                                                        true
                                                                        },
                                                                        modifier =
                                                                                Modifier.weight(1f),
                                                                        shape =
                                                                                RoundedCornerShape(
                                                                                        8.dp
                                                                                )
                                                                ) { Text("Configure Quota") }

                                                                // Advanced button (red background)
                                                                Button(
                                                                        onClick = {
                                                                                navController
                                                                                        ?.navigate(
                                                                                                "advanced-traffic-regulation"
                                                                                        )
                                                                        },
                                                                        modifier =
                                                                                Modifier.weight(1f),
                                                                        shape =
                                                                                RoundedCornerShape(
                                                                                        8.dp
                                                                                ),
                                                                        colors =
                                                                                ButtonDefaults
                                                                                        .buttonColors(
                                                                                                containerColor =
                                                                                                        MaterialTheme
                                                                                                                .colorScheme
                                                                                                                .error
                                                                                        ),
                                                                        enabled =
                                                                                navController !=
                                                                                        null
                                                                ) { Text("Advanced") }
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
                                                        containerColor =
                                                                MaterialTheme.colorScheme.surface
                                                ),
                                        elevation =
                                                CardDefaults.cardElevation(defaultElevation = 4.dp),
                                        shape = RoundedCornerShape(12.dp)
                                ) {
                                        Column(
                                                modifier = Modifier.padding(16.dp),
                                                verticalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                                Text(
                                                        text = "Data Management",
                                                        style =
                                                                MaterialTheme.typography
                                                                        .headlineSmall,
                                                        fontWeight = FontWeight.SemiBold
                                                )

                                                Text(
                                                        text =
                                                                "Clear all stored traffic data from the database. This action cannot be undone.",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color =
                                                                MaterialTheme.colorScheme
                                                                        .onSurfaceVariant
                                                )

                                                Button(
                                                        onClick = {
                                                                showClearConfirmationDialog = true
                                                        },
                                                        modifier = Modifier.fillMaxWidth(),
                                                        shape = RoundedCornerShape(8.dp),
                                                        colors =
                                                                ButtonDefaults.buttonColors(
                                                                        containerColor =
                                                                                MaterialTheme
                                                                                        .colorScheme
                                                                                        .error
                                                                ),
                                                        enabled = !uiState.isClearingDatabase
                                                ) {
                                                        if (uiState.isClearingDatabase) {
                                                                CircularProgressIndicator(
                                                                        modifier =
                                                                                Modifier.size(
                                                                                        20.dp
                                                                                ),
                                                                        color =
                                                                                MaterialTheme
                                                                                        .colorScheme
                                                                                        .onError,
                                                                        strokeWidth = 2.dp
                                                                )
                                                                Spacer(
                                                                        modifier =
                                                                                Modifier.width(8.dp)
                                                                )
                                                                Text("Clearing...")
                                                        } else {
                                                                Icon(
                                                                        imageVector =
                                                                                Icons.Default
                                                                                        .Delete,
                                                                        contentDescription = null,
                                                                        modifier =
                                                                                Modifier.size(20.dp)
                                                                )
                                                                Spacer(
                                                                        modifier =
                                                                                Modifier.width(8.dp)
                                                                )
                                                                Text("Clear Traffic Data")
                                                        }
                                                }
                                        }
                                }
                        }

                        item {
                                Button(
                                        onClick = {
                                                viewModel.saveSettings()
                                                onThemeChanged() // Trigger theme reload after
                                                // saving
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(12.dp)
                                ) { Text("Save Settings") }
                        }
                }
        }

        // Quota configuration dialog
        if (showQuotaConfigDialog) {
                AlertDialog(
                        onDismissRequest = { showQuotaConfigDialog = false },
                        title = {
                                Text(
                                        text = "Configure Traffic Quota",
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold
                                )
                        },
                        text = {
                                Column(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                        Text(
                                                text =
                                                        "Quotas are shared equally among all connected clients (including host).",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )

                                        OutlinedTextField(
                                                value = quotaBandwidthText,
                                                onValueChange = { quotaBandwidthText = it },
                                                label = { Text("Total Bandwidth (MB)") },
                                                placeholder = {
                                                        Text("Auto-detected or enter manually")
                                                },
                                                modifier = Modifier.fillMaxWidth(),
                                                supportingText = {
                                                        Text(
                                                                if (quotaBandwidthText.isEmpty()) {
                                                                        "Bandwidth will be auto-detected when dialog opens"
                                                                } else {
                                                                        "Total bandwidth to share among all clients (auto-detected, you can change it)"
                                                                }
                                                        )
                                                },
                                                singleLine = true
                                        )

                                        OutlinedTextField(
                                                value = quotaBlockHoursText,
                                                onValueChange = { quotaBlockHoursText = it },
                                                label = { Text("Block Duration (Hours)") },
                                                modifier = Modifier.fillMaxWidth(),
                                                supportingText = {
                                                        Text(
                                                                "How long to block clients after quota exceeded (0 = no blocking, 1-168 hours)"
                                                        )
                                                },
                                                singleLine = true
                                        )
                                }
                        },
                        confirmButton = {
                                Button(
                                        onClick = {
                                                val bandwidth =
                                                        quotaBandwidthText.toLongOrNull() ?: 0
                                                val blockHours =
                                                        quotaBlockHoursText.toIntOrNull() ?: 0
                                                if (bandwidth > 0 &&
                                                                blockHours >= 0 &&
                                                                blockHours <= 168
                                                ) {
                                                        viewModel.updateQuotaSettings(
                                                                bandwidth,
                                                                blockHours
                                                        )
                                                        showQuotaConfigDialog = false
                                                }
                                        },
                                        enabled =
                                                quotaBandwidthText.toLongOrNull()?.let { it > 0 } ==
                                                        true &&
                                                        quotaBlockHoursText.toIntOrNull()?.let {
                                                                it in 0..168
                                                        } == true
                                ) { Text("Save") }
                        },
                        dismissButton = {
                                TextButton(onClick = { showQuotaConfigDialog = false }) {
                                        Text("Cancel")
                                }
                        },
                        containerColor = MaterialTheme.colorScheme.surface
                )
        }

        // Confirmation dialog for clearing database
        if (showClearConfirmationDialog) {
                AlertDialog(
                        onDismissRequest = { showClearConfirmationDialog = false },
                        title = {
                                Text(
                                        text = "Clear Traffic Data",
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold
                                )
                        },
                        text = {
                                Text(
                                        text =
                                                "Are you sure you want to delete all traffic data? This will permanently remove:\n\n" +
                                                        "• All traffic records\n" +
                                                        "• All session history\n" +
                                                        "• All client statistics\n\n" +
                                                        "This action cannot be undone.",
                                        style = MaterialTheme.typography.bodyMedium
                                )
                        },
                        confirmButton = {
                                Button(
                                        onClick = {
                                                showClearConfirmationDialog = false
                                                viewModel.clearTrafficData()
                                        },
                                        colors =
                                                ButtonDefaults.buttonColors(
                                                        containerColor =
                                                                MaterialTheme.colorScheme.error
                                                )
                                ) { Text("Clear All Data") }
                        },
                        dismissButton = {
                                TextButton(onClick = { showClearConfirmationDialog = false }) {
                                        Text("Cancel")
                                }
                        },
                        containerColor = MaterialTheme.colorScheme.surface
                )
        }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeDropdown(selectedTheme: ThemeMode, onThemeSelected: (ThemeMode) -> Unit) {
        var expanded by remember { mutableStateOf(false) }

        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
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
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                        colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors()
                )

                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        ThemeMode.values().forEach { theme ->
                                DropdownMenuItem(
                                        text = { Text(theme.displayName) },
                                        onClick = {
                                                onThemeSelected(theme)
                                                expanded = false
                                        },
                                        contentPadding =
                                                ExposedDropdownMenuDefaults.ItemContentPadding
                                )
                        }
                }
        }
}

private val ThemeMode.displayName: String
        get() =
                when (this) {
                        ThemeMode.SYSTEM -> "Follow system"
                        ThemeMode.LIGHT -> "Light"
                        ThemeMode.DARK -> "Dark"
                }
