package com.example.shieldshare.ui.advanced

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController

@Composable
fun AdvancedTrafficRegulationScreen(
    navController: NavController,
    viewModel: AdvancedTrafficRegulationViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Advanced Traffic Regulation") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 12.dp)
                .padding(top = 12.dp)
                .padding(bottom = 6.dp)
                .safeDrawingPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Port Filtering Card
            item {
                PortFilteringCard(
                    enabled = uiState.portFilteringEnabled,
                    blockedPorts = uiState.blockedPorts,
                    customPortInput = uiState.customPortInput,
                    onEnabledChanged = viewModel::updatePortFilteringEnabled,
                    onTogglePort = viewModel::togglePopularPort,
                    onPortInputChanged = viewModel::updateCustomPortInput,
                    onAddPort = { port ->
                        val portInt = port.toIntOrNull()
                        if (portInt != null && portInt in 1..65535) {
                            viewModel.addPort(portInt)
                        }
                    },
                    onRemovePort = viewModel::removePort
                )
            }

            // URL Blocking Card
            item {
                UrlBlockingCard(
                    enabled = uiState.urlBlockingEnabled,
                    blockedDomains = uiState.blockedDomains,
                    customDomainInput = uiState.customDomainInput,
                    onEnabledChanged = viewModel::updateUrlBlockingEnabled,
                    onDomainInputChanged = viewModel::updateCustomDomainInput,
                    onAddDomain = viewModel::addDomain,
                    onRemoveDomain = viewModel::removeDomain
                )
            }
            
            // Abuse Detection Card
            item {
                com.example.shieldshare.ui.advanced.DynamicQuotaCard(
                    enabled = uiState.abuseDetectionEnabled,
                    multiplier = uiState.abuseThresholdMultiplier,
                    averagePeriod = uiState.averagePeriod,
                    globalAverage = uiState.globalAverage,
                    abuseStatus = uiState.abuseStatus,
                    onEnabledChanged = viewModel::updateAbuseDetectionEnabled,
                    onMultiplierChanged = viewModel::updateAbuseThresholdMultiplier,
                    onAveragePeriodChanged = viewModel::updateAveragePeriod,
                    onResetHistory = viewModel::resetConsumptionHistory,
                    onRefreshAverages = viewModel::refreshAbuseStatus,
                    onBlockClient = viewModel::blockAbusiveClient
                )
            }
        }
    }
}

@Composable
fun PortFilteringCard(
    enabled: Boolean,
    blockedPorts: Set<Int>,
    customPortInput: String,
    onEnabledChanged: (Boolean) -> Unit,
    onTogglePort: (Int) -> Unit,
    onPortInputChanged: (String) -> Unit,
    onAddPort: (String) -> Unit,
    onRemovePort: (Int) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
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
                text = "Port Filtering",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )

            Text(
                text = "Block specific destination ports for all clients. Popular app ports are listed below.",
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
                    text = "Enable Port Filtering",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Switch(
                    checked = enabled,
                    onCheckedChange = onEnabledChanged
                )
            }

            if (enabled) {
                Divider()

                // Popular app ports
                Text(
                    text = "Popular App Ports",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                val popularPorts = listOf(
                    "WhatsApp" to listOf(443, 5222, 5228),
                    "YouTube" to listOf(443, 80),
                    "TikTok" to listOf(443, 80),
                    "Instagram" to listOf(443),
                    "Facebook" to listOf(443),
                    "Twitter/X" to listOf(443),
                    "Netflix" to listOf(443),
                    "Spotify" to listOf(443, 4070)
                )

                popularPorts.forEach { (appName, ports) ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = appName,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ports.forEach { port ->
                                FilterChip(
                                    selected = blockedPorts.contains(port),
                                    onClick = { onTogglePort(port) },
                                    label = { Text("$port") }
                                )
                            }
                        }
                    }
                }

                Divider()

                // Custom port input
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = customPortInput,
                        onValueChange = onPortInputChanged,
                        label = { Text("Custom Port") },
                        placeholder = { Text("Enter port (1-65535)") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    Button(
                        onClick = { onAddPort(customPortInput) },
                        enabled = customPortInput.toIntOrNull()?.let { it in 1..65535 } == true
                    ) {
                        Text("Add")
                    }
                }

                // Blocked ports list
                if (blockedPorts.isNotEmpty()) {
                    Text(
                        text = "Blocked Ports",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    blockedPorts.sorted().forEach { port ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Port $port",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            IconButton(onClick = { onRemovePort(port) }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Remove",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun UrlBlockingCard(
    enabled: Boolean,
    blockedDomains: Set<String>,
    customDomainInput: String,
    onEnabledChanged: (Boolean) -> Unit,
    onDomainInputChanged: (String) -> Unit,
    onAddDomain: (String) -> Unit,
    onRemoveDomain: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
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
                text = "URL/Domain Blocking",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )

            Text(
                text = "Block specific domains or URLs. Subdomains will also be blocked (e.g., blocking 'youtube.com' blocks 'www.youtube.com').",
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
                    text = "Enable URL Blocking",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Switch(
                    checked = enabled,
                    onCheckedChange = onEnabledChanged
                )
            }

            if (enabled) {
                Divider()

                // Domain input
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = customDomainInput,
                        onValueChange = onDomainInputChanged,
                        label = { Text("Domain or URL") },
                        placeholder = { Text("e.g., youtube.com") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    Button(
                        onClick = { onAddDomain(customDomainInput) },
                        enabled = customDomainInput.trim().isNotEmpty()
                    ) {
                        Text("Add")
                    }
                }

                // Blocked domains list
                if (blockedDomains.isNotEmpty()) {
                    Text(
                        text = "Blocked Domains",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    blockedDomains.sorted().forEach { domain ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = domain,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { onRemoveDomain(domain) }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Remove",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

