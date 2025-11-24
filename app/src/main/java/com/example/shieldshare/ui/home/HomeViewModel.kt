package com.example.shieldshare.ui.home

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.shieldshare.data.prefs.AppPrefs
import com.example.shieldshare.managers.hotspot.HotspotManager
import com.example.shieldshare.managers.network.IpAddressProvider
import com.example.shieldshare.managers.proxy.ProxyConfig
import com.example.shieldshare.managers.proxy.ProxyPortManager
import com.example.shieldshare.managers.proxy.ProxyServer
import com.example.shieldshare.managers.proxy.ProxyType
import com.example.shieldshare.managers.vpn.VpnManager
import com.example.shieldshare.managers.vpn.VpnStatus
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@HiltViewModel
class HomeViewModel
@Inject
constructor(
        private val vpnManager: VpnManager,
        private val proxyServer: ProxyServer,
        private val hotspotManager: HotspotManager,
        private val appPrefs: AppPrefs,
        private val ipProvider: IpAddressProvider,
        private val trafficMeter: com.example.shieldshare.managers.meter.TrafficMeter,
        private val trafficRepository: com.example.shieldshare.data.repository.TrafficRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private var ipAutoJob: Job? = null
    private val ipRefreshIntervalMs = 30_000L // fresh every 30 sec
    
    // Service Session tracking (starts when both proxy and VPN are active)
    private var serviceSessionStartTime: Long? = null
    private var serviceSessionUpdateJob: Job? = null
    private var currentServiceSessionId: String? = null

    init {
        viewModelScope.launch { refreshIp() }
        
        // On app launch, end any active service sessions from previous app instance
        // This ensures we always start fresh, even if VPN and proxy are already running
        viewModelScope.launch {
            try {
                val activeSession = trafficRepository.getActiveServiceSession()
                if (activeSession != null) {
                    Log.i("HomeViewModel", "Found active session from previous app instance, ending it: ${activeSession.sessionId}")
                    trafficRepository.endServiceSession(activeSession.sessionId)
                }
                
                // After ending any active session, check if both VPN and proxy are already running
                // If so, start a new session (this handles the case where app was closed but services kept running)
                delay(1000) // Small delay to ensure state is updated
                val proxyInfo = proxyServer.getProxyInfo()
                val vpnStatus = vpnManager.getConnectionStatus()
                val bothActive = proxyInfo.isRunning && vpnStatus == VpnStatus.CONNECTED
                
                if (bothActive) {
                    // Update UI state to reflect current status
                    _uiState.value = _uiState.value.copy(
                        isProxyRunning = proxyInfo.isRunning,
                        isVpnConnected = true,
                        httpPort = proxyInfo.httpPort,
                        httpsPort = proxyInfo.httpsPort,
                        socks5Port = proxyInfo.socks5Port,
                        proxyType = proxyInfo.proxyType,
                        pacUrl = proxyInfo.pacFileUrl
                    )
                    // This will start a new service session
                    updateServiceSessionState()
                }
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Error ending active session on app launch", e)
            }
        }

        // Observe VPN status changes
        viewModelScope.launch {
            vpnManager.subscribeToStatusChanges().collect { status ->
                val connected = (status == VpnStatus.CONNECTED)
                _uiState.value =
                        _uiState.value.copy(
                                vpnStatus = status.name,
                                isVpnConnected = status == VpnStatus.CONNECTED,
                                isVpnConnecting = status == VpnStatus.CONNECTING
                        )
                
                // Update service session state
                updateServiceSessionState()
                
                if (connected) {
                    refreshIp() // fresh IP
                    if (ipAutoJob?.isActive != true) {
                        ipAutoJob =
                                viewModelScope.launch {
                                    // fresh it when it start
                                    refreshIp()
                                    // fresh it as time we set
                                    while (isActive) {
                                        delay(ipRefreshIntervalMs)
                                        refreshIp()
                                    }
                                }
                    }
                } else {
                    // when disconnect, fresh the IP address
                    ipAutoJob?.cancel()
                    _uiState.value = _uiState.value.copy(localIpAddress = null, publicIpAddress = null)
                }
            }
        }

        // Start monitoring hotspot status
        startHotspotMonitoring()
    }

    fun startVpn() {
        viewModelScope.launch {
            try {
                val config = com.example.shieldshare.managers.vpn.VpnConfig()
                val result = vpnManager.connectVpn(config)
                result.fold(
                        onSuccess = { connection ->
                            Log.i("HomeViewModel", "VPN connected: $connection")
                        },
                        onFailure = { error ->
                            Log.e("HomeViewModel", "Failed to connect VPN", error)
                        }
                )
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Exception connecting VPN", e)
            }
        }
    }

    fun stopVpn() {
        viewModelScope.launch {
            try {
                val result = vpnManager.disconnectVpn()
                result.fold(
                        onSuccess = { Log.i("HomeViewModel", "VPN disconnected") },
                        onFailure = { error ->
                            Log.e("HomeViewModel", "Failed to disconnect VPN", error)
                        }
                )
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Exception disconnecting VPN", e)
            }
        }
    }

    fun startProxyServer() {
        viewModelScope.launch {
            try {
                val authEnabled = appPrefs.getBoolean("auth_enabled", false)
                val proxyUsername =
                        appPrefs.getString("proxy_username", "")?.takeIf { it.isNotBlank() }
                val proxyPassword =
                        appPrefs.getString("proxy_password", "")?.takeIf { it.isNotBlank() }
                val effectiveAuthEnabled =
                        authEnabled && proxyUsername != null && proxyPassword != null

                if (authEnabled && !effectiveAuthEnabled) {
                    Log.w(
                            "HomeViewModel",
                            "Proxy authentication enabled but credentials missing; starting without auth"
                    )
                }
                val httpHttpsEnabled = appPrefs.getBoolean("http_https_enabled", true)
                val socks5Enabled = appPrefs.getBoolean("socks5_enabled", true)

                // Determine proxy type based on enabled protocols
                val proxyType = when {
                    httpHttpsEnabled && socks5Enabled -> ProxyType.BOTH
                    httpHttpsEnabled -> ProxyType.HTTP_HTTPS
                    socks5Enabled -> ProxyType.SOCKS5
                    else -> {
                        // If both are disabled, default to BOTH to ensure at least one protocol is available
                        Log.w("HomeViewModel", "Both protocols disabled, defaulting to BOTH")
                        ProxyType.BOTH
                    }
                }

                val config =
                        ProxyConfig(
                                authEnabled = effectiveAuthEnabled,
                                authUsername = proxyUsername,
                                authPassword = proxyPassword,
                                allowedClients = emptyList(),
                                proxyType = proxyType
                        )

                val result = proxyServer.startProxy(config)
                result.fold(
                        onSuccess = { proxyInstance ->
                            Log.i(
                                    "HomeViewModel",
                                    "Proxy servers started. HTTP/HTTPS ${config.httpPort}, SOCKS5 ${config.socks5Port}, auth=${config.authEnabled}"
                            )
                            val hotspotIp = hotspotManager.getHotspotIpAddress()
                            val pacUrl =
                                    hotspotIp?.let {
                                        "http://$it:${ProxyPortManager.CONFIG_PORT}/proxy.pac"
                                    }
                            // Initialize speed tracking when proxy starts
                            val initialStats = trafficMeter.getCurrentStats()
                            previousTotalBytesUp = initialStats.sumOf { it.totalBytesUp }
                            previousTotalBytesDown = initialStats.sumOf { it.totalBytesDown }
                            lastStatsUpdateTime = System.currentTimeMillis()
                            
                            _uiState.value =
                                    _uiState.value.copy(
                                            isProxyRunning = true,
                                            httpPort = config.httpPort,
                                            httpsPort = config.httpsPort,
                                            socks5Port = config.socks5Port,
                                            proxyType = proxyType,
                                            configPortalPort = ProxyPortManager.CONFIG_PORT,
                                            pacUrl = pacUrl,
                                            uploadSpeed = "0 B/s", // Will update immediately via startStatsUpdates
                                            downloadSpeed = "0 B/s" // Will update immediately via startStatsUpdates
                                    )

                            // Update service session state
                            updateServiceSessionState()
                            
                            // Start real-time stats updates
                            startStatsUpdates()
                        },
                        onFailure = { error ->
                            Log.e("HomeViewModel", "Failed to start proxy server", error)
                        }
                )
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Exception starting proxy server", e)
            }
        }
    }

    fun stopProxyServer() {
        viewModelScope.launch {
            try {
                val result = proxyServer.stopProxy()
                result.fold(
                        onSuccess = {
                            Log.i("HomeViewModel", "Proxy server stopped")
                            // Reset speed tracking
                            previousTotalBytesUp = 0
                            previousTotalBytesDown = 0
                            lastStatsUpdateTime = System.currentTimeMillis()
                            
                            _uiState.value =
                                    _uiState.value.copy(
                                            isProxyRunning = false,
                                            pacUrl = null,
                                            uploadSpeed = "0 B/s",
                                            downloadSpeed = "0 B/s"
                                    )
                            
                            // Update service session state
                            updateServiceSessionState()
                        },
                        onFailure = { error ->
                            Log.e("HomeViewModel", "Failed to stop proxy server", error)
                        }
                )
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Exception stopping proxy server", e)
            }
        }
    }

    fun openHotspotSettings() {
        hotspotManager.guideUserToEnableHotspot()
    }

    fun getHotspotIp(): String {
        return hotspotManager.getHotspotIpAddress() ?: "Not available"
    }

    // Track previous traffic totals for speed calculation
    private var previousTotalBytesUp: Long = 0
    private var previousTotalBytesDown: Long = 0
    private var lastStatsUpdateTime: Long = System.currentTimeMillis()

    fun startStatsUpdates() {
        viewModelScope.launch {
            while (isActive) {
                if (_uiState.value.isProxyRunning) {
                    try {
                        // Get real traffic data from TrafficMeter
                        val currentStats = trafficMeter.getCurrentStats()
                        
                        // Calculate total traffic across all clients
                        val currentTotalBytesUp = currentStats.sumOf { it.totalBytesUp }
                        val currentTotalBytesDown = currentStats.sumOf { it.totalBytesDown }
                        
                        // Calculate time delta
                        val currentTime = System.currentTimeMillis()
                        val timeDelta = (currentTime - lastStatsUpdateTime).coerceAtLeast(100) // At least 100ms
                        
                        // Calculate speeds (bytes per second)
                        val bytesUpDiff = currentTotalBytesUp - previousTotalBytesUp
                        val bytesDownDiff = currentTotalBytesDown - previousTotalBytesDown
                        
                        val uploadSpeedBps = (bytesUpDiff * 1000.0) / timeDelta
                        val downloadSpeedBps = (bytesDownDiff * 1000.0) / timeDelta
                        
                        // Format speeds
                        val uploadSpeed = formatSpeed(uploadSpeedBps)
                        val downloadSpeed = formatSpeed(downloadSpeedBps)
                        
                        _uiState.value = _uiState.value.copy(
                                uploadSpeed = uploadSpeed,
                                downloadSpeed = downloadSpeed
                        )
                        
                        // Update previous values for next calculation
                        previousTotalBytesUp = currentTotalBytesUp
                        previousTotalBytesDown = currentTotalBytesDown
                        lastStatsUpdateTime = currentTime
                    } catch (e: Exception) {
                        Log.e("HomeViewModel", "Error updating stats", e)
                    }
                } else {
                    // Reset when proxy stops
                    previousTotalBytesUp = 0
                    previousTotalBytesDown = 0
                    lastStatsUpdateTime = System.currentTimeMillis()
                }
                delay(2000) // Update every 2 seconds
            }
        }
    }
    
    /**
     * Format bytes per second to human-readable speed string.
     */
    private fun formatSpeed(bytesPerSecond: Double): String {
        return when {
            bytesPerSecond < 1024 -> "%.0f B/s".format(bytesPerSecond)
            bytesPerSecond < 1024 * 1024 -> "%.1f KB/s".format(bytesPerSecond / 1024.0)
            bytesPerSecond < 1024 * 1024 * 1024 -> "%.1f MB/s".format(bytesPerSecond / (1024.0 * 1024.0))
            else -> "%.2f GB/s".format(bytesPerSecond / (1024.0 * 1024.0 * 1024.0))
        }
    }
    
    /**
     * Update service session state based on proxy and VPN status.
     * Service session starts when both are active, ends when either stops.
     */
    private fun updateServiceSessionState() {
        val currentState = _uiState.value
        val bothActive = currentState.isProxyRunning && currentState.isVpnConnected
        
        if (bothActive && serviceSessionStartTime == null) {
            // Reset per-device traffic stats FIRST to ensure clean state for new session
            // This prevents new session traffic from accumulating on previous session data
            // Must happen BEFORE marking session as started to avoid showing old data
            trafficMeter.resetCurrentSessionStats()
            
            // Start service session AFTER stats are cleared
            val sessionId = java.util.UUID.randomUUID().toString()
            currentServiceSessionId = sessionId
            serviceSessionStartTime = System.currentTimeMillis()
            startServiceSessionTimer()
            
            // Persist service session to database
            viewModelScope.launch {
                trafficRepository.startServiceSession(sessionId)
            }
            
            Log.i("HomeViewModel", "Service session started: $sessionId")
        } else if (!bothActive && serviceSessionStartTime != null) {
            // End service session
            val sessionId = currentServiceSessionId
            serviceSessionStartTime = null
            currentServiceSessionId = null
            serviceSessionUpdateJob?.cancel()
            serviceSessionUpdateJob = null
            _uiState.value = _uiState.value.copy(serviceSessionUptime = null)
            
            // Persist service session end to database
            if (sessionId != null) {
                viewModelScope.launch {
                    trafficRepository.endServiceSession(sessionId)
                }
            }
            
            Log.i("HomeViewModel", "Service session ended: $sessionId")
        }
    }
    
    /**
     * Start timer to update service session uptime display.
     */
    private fun startServiceSessionTimer() {
        serviceSessionUpdateJob?.cancel()
        serviceSessionUpdateJob = viewModelScope.launch {
            while (isActive && serviceSessionStartTime != null) {
                val uptime = serviceSessionStartTime?.let { startTime ->
                    System.currentTimeMillis() - startTime
                }
                _uiState.value = _uiState.value.copy(serviceSessionUptime = uptime)
                delay(1000) // Update every second
            }
        }
    }
    
    /**
     * Format uptime in milliseconds to human-readable string (e.g., "2h 15m 30s").
     */
    private fun formatUptime(millis: Long): String {
        val totalSeconds = millis / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        
        return when {
            hours > 0 -> "${hours}h ${minutes}m ${seconds}s"
            minutes > 0 -> "${minutes}m ${seconds}s"
            else -> "${seconds}s"
        }
    }

    fun startHotspotMonitoring() {
        viewModelScope.launch {
            while (isActive) {
                try {
                    // Check hotspot state
                    val hotspotState = hotspotManager.detectHotspotState()
                    val isEnabled =
                            hotspotState ==
                                    com.example.shieldshare.managers.hotspot.HotspotState.ENABLED

                    // Get connected clients from proxy server (IP-based counting)
                    val proxyInfo = proxyServer.getProxyInfo()
                    val clientCount = proxyInfo.activeConnections

                    Log.d(
                            "HomeViewModel",
                            "Hotspot state: $hotspotState, enabled: $isEnabled, clients: $clientCount (from proxy server)"
                    )

                    val wasProxyRunning = _uiState.value.isProxyRunning
                    _uiState.value =
                            _uiState.value.copy(
                                    isHotspotEnabled = isEnabled,
                                    hotspotClients = clientCount,
                                    activeConnections = clientCount, // Update active connections too
                                    isProxyRunning = proxyInfo.isRunning,
                                    httpPort = proxyInfo.httpPort,
                                    httpsPort = proxyInfo.httpsPort,
                                    socks5Port = proxyInfo.socks5Port,
                                    proxyType = proxyInfo.proxyType,
                                    pacUrl = proxyInfo.pacFileUrl,
                                    configPortalPort = ProxyPortManager.CONFIG_PORT
                            )
                    
                    // Update service session if proxy state changed
                    if (wasProxyRunning != proxyInfo.isRunning) {
                        updateServiceSessionState()
                    }

                    // Auto-manage proxy based on hotspot state
                    val currentState = _uiState.value
                    if (isEnabled && !currentState.isProxyRunning) {
                        // Hotspot is on but proxy is off - start proxy
                        Log.d("HomeViewModel", "Hotspot enabled, starting proxy automatically")
                        startProxyServer()
                    } else if (!isEnabled && currentState.isProxyRunning) {
                        // Hotspot is off but proxy is on - stop proxy
                        Log.d("HomeViewModel", "Hotspot disabled, stopping proxy automatically")
                        stopProxyServer()
                    }
                } catch (e: Exception) {
                    Log.e("HomeViewModel", "Error monitoring hotspot", e)
                }
                delay(3000) // Check every 3 seconds
            }
        }
    }

    fun refreshIp() =
            viewModelScope.launch {
                if (_uiState.value.isFetchingIp) return@launch
                _uiState.value = _uiState.value.copy(isFetchingIp = true)

                // Get local IP
                val localIp = hotspotManager.getHotspotIpAddress()
                
                // Get public IP
                val publicIpResult = ipProvider.getPublicIp()
                val publicIp = if (publicIpResult.isSuccess) {
                    publicIpResult.getOrNull()
                } else {
                    null
                }
                
                _uiState.value = _uiState.value.copy(
                    isFetchingIp = false, 
                    localIpAddress = localIp ?: "Not available",
                    publicIpAddress = publicIp ?: "Not available"
                )
            }

    fun generateQRCode(): ImageBitmap? {
        val currentState = _uiState.value
        val portalPort = currentState.configPortalPort

        if (!currentState.isProxyRunning) {
            Log.w("HomeViewModel", "Cannot generate QR code: proxy is not running")
            return null
        }

        if (portalPort <= 0) {
            Log.w("HomeViewModel", "Cannot generate QR code: configuration portal port not available")
            return null
        }

        try {
            // Get the actual hotspot IP address dynamically
            val hotspotIp = hotspotManager.getHotspotIpAddress()
            if (hotspotIp == null) {
                Log.w("HomeViewModel", "Cannot generate QR code: hotspot IP not available")
                return null
            }

            // Create QR code that opens auto-configuration webpage
            val qrContent = "http://$hotspotIp:$portalPort/configure"

            // Generate QR code bitmap
            val writer = QRCodeWriter()
            val hints = mapOf(EncodeHintType.MARGIN to 1)
            val bitMatrix = writer.encode(qrContent, BarcodeFormat.QR_CODE, 400, 400, hints)

            val width = bitMatrix.width
            val height = bitMatrix.height
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)

            for (x in 0 until width) {
                for (y in 0 until height) {
                    bitmap.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
                }
            }

            return bitmap.asImageBitmap()
        } catch (e: Exception) {
            Log.e("HomeViewModel", "Failed to generate QR code", e)
            return null
        }
    }
    
    /**
     * Called when the ViewModel is being cleared (e.g., when app is closed).
     * End any active service session to persist it to the database.
     */
    override fun onCleared() {
        super.onCleared()
        
        // End active service session if one exists
        val sessionId = currentServiceSessionId
        if (sessionId != null) {
            Log.i("HomeViewModel", "App closing, ending service session: $sessionId")
            // Use runBlocking to ensure the session is ended before the ViewModel is destroyed
            // This is safe here because onCleared is called on the main thread
            kotlinx.coroutines.runBlocking {
                try {
                    trafficRepository.endServiceSession(sessionId)
                } catch (e: Exception) {
                    Log.e("HomeViewModel", "Error ending service session on app close", e)
                }
            }
        }
        
        // Cancel all jobs
        serviceSessionUpdateJob?.cancel()
        ipAutoJob?.cancel()
    }
}

data class HomeUiState(
        val vpnStatus: String = "DISCONNECTED",
        val isVpnConnected: Boolean = false,
        val isVpnConnecting: Boolean = false,
        val isProxyRunning: Boolean = false,
        val httpPort: Int = ProxyPortManager.HTTP_PORT,
        val httpsPort: Int = ProxyPortManager.HTTPS_PORT,
        val socks5Port: Int = ProxyPortManager.SOCKS5_PORT,
        val proxyType: ProxyType = ProxyType.BOTH,
        val configPortalPort: Int = ProxyPortManager.CONFIG_PORT,
        val pacUrl: String? = null,
        val activeConnections: Int = 0,
        val dataTransferred: String = "0 MB",
        val uploadSpeed: String = "0 KB/s",
        val downloadSpeed: String = "0 KB/s",
        val serviceSessionUptime: Long? = null, // Uptime in milliseconds, null if session not active
        val isHotspotEnabled: Boolean = false,
        val hotspotClients: Int = 0,
        val localIpAddress: String? = null,
        val publicIpAddress: String? = null,
        val isFetchingIp: Boolean = false
)
