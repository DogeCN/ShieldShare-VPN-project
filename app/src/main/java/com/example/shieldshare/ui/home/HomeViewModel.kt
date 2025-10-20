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
        private val ipProvider: IpAddressProvider
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private var ipAutoJob: Job? = null
    private val ipRefreshIntervalMs = 30_000L // fresh every 30 sec

    init {
        viewModelScope.launch { refreshIp() }

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
                    _uiState.update { it.copy(ipAddress = null) }
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
                // Load proxy settings from AppPrefs
                val proxyPort = appPrefs.getInt("proxy_port", 8080)
                val authEnabled = appPrefs.getBoolean("auth_enabled", false)

                val config =
                        ProxyConfig(
                                port = proxyPort,
                                authEnabled = authEnabled,
                                allowedClients = emptyList(),
                                proxyType = ProxyType.BOTH
                        )

                val result = proxyServer.startProxy(config)
                result.fold(
                        onSuccess = { proxyInstance ->
                            Log.i(
                                    "HomeViewModel",
                                    "Proxy server started on port ${config.port}: $proxyInstance"
                            )
                            _uiState.value =
                                    _uiState.value.copy(
                                            isProxyRunning = true,
                                            proxyPort = config.port,
                                            uploadSpeed = "0 KB/s",
                                            downloadSpeed = "0 KB/s",
                                            latency = "0ms"
                                    )

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
                            _uiState.value =
                                    _uiState.value.copy(isProxyRunning = false, proxyPort = 0)
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

    fun startStatsUpdates() {
        viewModelScope.launch {
            while (isActive) {
                if (_uiState.value.isProxyRunning) {
                    // Simulate real-time stats updates
                    val uploadSpeed = "${(10..500).random()} KB/s"
                    val downloadSpeed = "${(50..1000).random()} KB/s"
                    val latency = "${(20..100).random()}ms"

                    _uiState.update {
                        it.copy(
                                uploadSpeed = uploadSpeed,
                                downloadSpeed = downloadSpeed,
                                latency = latency
                        )
                    }
                }
                delay(2000) // Update every 2 seconds
            }
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

                    // Get connected clients
                    val clients = hotspotManager.getHotspotClients()
                    val clientCount = clients.size

                    Log.d(
                            "HomeViewModel",
                            "Hotspot state: $hotspotState, enabled: $isEnabled, clients: $clientCount"
                    )

                    _uiState.update {
                        it.copy(
                                isHotspotEnabled = isEnabled,
                                hotspotClients = clientCount,
                                activeConnections = clientCount // Update active connections too
                        )
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
                _uiState.update { it.copy(isFetchingIp = true) }

                // Get hotspot IP instead of public IP
                val hotspotIp = hotspotManager.getHotspotIpAddress()
                _uiState.update {
                    it.copy(isFetchingIp = false, ipAddress = hotspotIp ?: "Not available")
                }
            }

    fun generateQRCode(): ImageBitmap? {
        val currentState = _uiState.value
        val proxyPort = currentState.proxyPort

        if (proxyPort == 0) {
            Log.w("HomeViewModel", "Cannot generate QR code: proxy port not available")
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
            val qrContent = "http://$hotspotIp:${proxyPort + 1}/configure"

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
}

data class HomeUiState(
        val vpnStatus: String = "DISCONNECTED",
        val isVpnConnected: Boolean = false,
        val isVpnConnecting: Boolean = false,
        val isProxyRunning: Boolean = false,
        val proxyPort: Int = 0,
        val activeConnections: Int = 0,
        val dataTransferred: String = "0 MB",
        val uploadSpeed: String = "0 KB/s",
        val downloadSpeed: String = "0 KB/s",
        val latency: String = "0ms",
        val isHotspotEnabled: Boolean = false,
        val hotspotClients: Int = 0,
        val ipAddress: String? = null,
        val isFetchingIp: Boolean = false
)
