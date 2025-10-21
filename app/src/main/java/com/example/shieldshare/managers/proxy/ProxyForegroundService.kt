package com.example.shieldshare.managers.proxy

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.shieldshare.R
import com.example.shieldshare.managers.meter.TrafficMeter
import com.example.shieldshare.managers.meter.TrafficMeterNoop
import java.io.*
import java.net.*
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*

class ProxyForegroundService : Service() {
    companion object {
        private const val TAG = "ProxyForegroundService"
        private const val HTTP_PROXY_PORT = 8080
        private const val SOCKS5_PROXY_PORT = 1080
        private const val NOTIFICATION_ID = 2
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var httpServerSocket: ServerSocket? = null
    private var socks5ServerSocket: ServerSocket? = null
    private val activeConnections = AtomicInteger(0)
    private val pacFileGenerator = PacFileGenerator()
    private val trafficMeter: TrafficMeter =
            TrafficMeterNoop() // TODO: JIALU - Inject proper TrafficMeterImpl implementation

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startProxyServers()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopProxyServers()
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val mgr = getSystemService(NotificationManager::class.java)
            val id = "proxy"
            if (mgr.getNotificationChannel(id) == null) {
                mgr.createNotificationChannel(
                        NotificationChannel(id, "Proxy", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
    }

    private fun createNotification() =
            NotificationCompat.Builder(this, "proxy")
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setContentTitle("ShieldShare Proxy")
                    .setContentText(
                            "Proxy server running on ports $HTTP_PROXY_PORT (HTTP) and $SOCKS5_PROXY_PORT (SOCKS5)"
                    )
                    .setOngoing(true)
                    .build()

    private fun startProxyServers() {
        serviceScope.launch {
            try {
                // Start HTTP/HTTPS proxy server
                startHttpProxyServer()

                // Start SOCKS5 proxy server
                startSocks5ProxyServer()

                Log.i(TAG, "Proxy servers started successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start proxy servers", e)
            }
        }
    }

    private suspend fun startHttpProxyServer() =
            withContext(Dispatchers.IO) {
                try {
                    httpServerSocket = ServerSocket(HTTP_PROXY_PORT)
                    Log.i(TAG, "HTTP proxy server listening on port $HTTP_PROXY_PORT")

                    while (isActive && !httpServerSocket!!.isClosed) {
                        try {
                            val clientSocket = httpServerSocket!!.accept()
                            activeConnections.incrementAndGet()

                            // Handle special requests
                            if (isPacFileRequest(clientSocket)) {
                                handlePacFileRequest(clientSocket)
                            } else if (isConfigPageRequest(clientSocket)) {
                                handleConfigPageRequest(clientSocket)
                            } else {
                                // Handle regular proxy requests
                                val handler =
                                        HttpProxyHandler(clientSocket, trafficMeter) {
                                                bytesUp,
                                                bytesDown ->
                                            Log.d(
                                                    TAG,
                                                    "HTTP proxy traffic: up=$bytesUp, down=$bytesDown"
                                            )
                                            activeConnections.decrementAndGet()
                                        }
                                handler.start()
                            }
                        } catch (e: SocketException) {
                            if (!httpServerSocket!!.isClosed) {
                                Log.e(TAG, "Error accepting HTTP proxy connection", e)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "HTTP proxy server error", e)
                }
            }

    private suspend fun startSocks5ProxyServer() =
            withContext(Dispatchers.IO) {
                try {
                    socks5ServerSocket = ServerSocket(SOCKS5_PROXY_PORT)
                    Log.i(TAG, "SOCKS5 proxy server listening on port $SOCKS5_PROXY_PORT")

                    while (isActive && !socks5ServerSocket!!.isClosed) {
                        try {
                            val clientSocket = socks5ServerSocket!!.accept()
                            activeConnections.incrementAndGet()

                            val handler =
                                    Socks5ProxyHandler(clientSocket, trafficMeter) {
                                            bytesUp,
                                            bytesDown ->
                                        Log.d(
                                                TAG,
                                                "SOCKS5 proxy traffic: up=$bytesUp, down=$bytesDown"
                                        )
                                        activeConnections.decrementAndGet()
                                    }
                            handler.start()
                        } catch (e: SocketException) {
                            if (!socks5ServerSocket!!.isClosed) {
                                Log.e(TAG, "Error accepting SOCKS5 proxy connection", e)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "SOCKS5 proxy server error", e)
                }
            }

    private fun isPacFileRequest(socket: Socket): Boolean {
        return try {
            val input = socket.getInputStream()
            val reader = BufferedReader(InputStreamReader(input))
            val requestLine = reader.readLine()
            requestLine?.contains("/proxy.pac") == true
        } catch (e: Exception) {
            false
        }
    }

    private fun handlePacFileRequest(socket: Socket) {
        try {
            val output = socket.getOutputStream()
            val writer = PrintWriter(output, true)

            val pacContent = pacFileGenerator.generatePacFile()

            writer.println("HTTP/1.1 200 OK")
            writer.println("Content-Type: application/x-ns-proxy-autoconfig")
            writer.println("Content-Length: ${pacContent.length}")
            writer.println("Cache-Control: no-cache")
            writer.println()
            writer.println(pacContent)
            writer.flush()

            Log.d(TAG, "Served PAC file to client")
        } catch (e: Exception) {
            Log.e(TAG, "Error serving PAC file", e)
        } finally {
            try {
                socket.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing PAC file socket", e)
            }
        }
    }

    private fun stopProxyServers() {
        try {
            httpServerSocket?.close()
            socks5ServerSocket?.close()
            Log.i(TAG, "Proxy servers stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping proxy servers", e)
        }
    }

    private fun isConfigPageRequest(socket: Socket): Boolean {
        return try {
            val input = socket.getInputStream()
            val reader = BufferedReader(InputStreamReader(input))
            val requestLine = reader.readLine()
            requestLine?.contains("/configure") == true
        } catch (e: Exception) {
            false
        }
    }

    private fun handleConfigPageRequest(socket: Socket) {
        try {
            val output = socket.getOutputStream()
            val writer = PrintWriter(output, true)

            val configPage = generateConfigPage()

            writer.println("HTTP/1.1 200 OK")
            writer.println("Content-Type: text/html; charset=UTF-8")
            writer.println("Content-Length: ${configPage.length}")
            writer.println("Cache-Control: no-cache")
            writer.println()
            writer.println(configPage)
            writer.flush()

            Log.d(TAG, "Served configuration page to client")
        } catch (e: Exception) {
            Log.e(TAG, "Error serving configuration page", e)
        } finally {
            try {
                socket.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing configuration page socket", e)
            }
        }
    }

    private fun generateConfigPage(): String {
        return """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>ShieldShare Proxy Configuration</title>
    <style>
        body {
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
            margin: 0;
            padding: 20px;
            background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
            color: #333;
            min-height: 100vh;
        }
        .container {
            max-width: 600px;
            margin: 0 auto;
            background: white;
            border-radius: 16px;
            padding: 30px;
            box-shadow: 0 20px 40px rgba(0,0,0,0.1);
        }
        .header {
            text-align: center;
            margin-bottom: 30px;
        }
        .logo {
            font-size: 2.5em;
            font-weight: bold;
            color: #667eea;
            margin-bottom: 10px;
        }
        .subtitle {
            color: #666;
            font-size: 1.1em;
        }
        .device-section {
            margin: 25px 0;
            padding: 20px;
            border: 2px solid #f0f0f0;
            border-radius: 12px;
            background: #fafafa;
        }
        .device-title {
            font-size: 1.3em;
            font-weight: bold;
            color: #333;
            margin-bottom: 15px;
            display: flex;
            align-items: center;
        }
        .device-icon {
            font-size: 1.5em;
            margin-right: 10px;
        }
        .step {
            margin: 15px 0;
            padding: 15px;
            background: white;
            border-radius: 8px;
            border-left: 4px solid #667eea;
        }
        .step-number {
            font-weight: bold;
            color: #667eea;
            margin-bottom: 8px;
        }
        .proxy-info {
            background: #e8f4fd;
            padding: 15px;
            border-radius: 8px;
            margin: 20px 0;
            text-align: center;
        }
        .proxy-url {
            font-family: monospace;
            background: #f0f0f0;
            padding: 8px 12px;
            border-radius: 4px;
            margin: 5px 0;
            word-break: break-all;
        }
        .button {
            background: #667eea;
            color: white;
            border: none;
            padding: 12px 24px;
            border-radius: 8px;
            font-size: 1em;
            cursor: pointer;
            margin: 10px 5px;
            text-decoration: none;
            display: inline-block;
        }
        .button:hover {
            background: #5a6fd8;
        }
        .note {
            background: #fff3cd;
            border: 1px solid #ffeaa7;
            padding: 15px;
            border-radius: 8px;
            margin: 20px 0;
        }
    </style>
</head>
<body>
    <div class="container">
        <div class="header">
            <div class="logo">🛡️ ShieldShare</div>
            <div class="subtitle">Proxy Configuration Assistant</div>
        </div>

        <div class="proxy-info">
            <h3>📡 Proxy Server Information</h3>
            <div class="proxy-url" id="proxyUrl">Loading...</div>
            <div class="proxy-url" id="pacUrl">Loading...</div>
        </div>

        <div class="device-section">
            <div class="device-title">
                <span class="device-icon">📱</span>
                iOS (iPhone/iPad)
            </div>
            <div class="step">
                <div class="step-number">Step 1:</div>
                Go to <strong>Settings → Wi-Fi</strong>
            </div>
            <div class="step">
                <div class="step-number">Step 2:</div>
                Tap the <strong>(i)</strong> icon next to your connected Wi-Fi network
            </div>
            <div class="step">
                <div class="step-number">Step 3:</div>
                Scroll down to <strong>Configure Proxy</strong> → Select <strong>Manual</strong>
            </div>
            <div class="step">
                <div class="step-number">Step 4:</div>
                Enter the proxy server details from above and tap <strong>Save</strong>
            </div>
        </div>

        <div class="device-section">
            <div class="device-title">
                <span class="device-icon">🤖</span>
                Android
            </div>
            <div class="step">
                <div class="step-number">Step 1:</div>
                Go to <strong>Settings → Wi-Fi</strong>
            </div>
            <div class="step">
                <div class="step-number">Step 2:</div>
                Long press your connected Wi-Fi network → <strong>Modify</strong>
            </div>
            <div class="step">
                <div class="step-number">Step 3:</div>
                Tap <strong>Advanced options</strong> → <strong>Proxy</strong> → <strong>Manual</strong>
            </div>
            <div class="step">
                <div class="step-number">Step 4:</div>
                Enter the proxy server details from above and tap <strong>Save</strong>
            </div>
        </div>

        <div class="device-section">
            <div class="device-title">
                <span class="device-icon">🪟</span>
                Windows
            </div>
            <div class="step">
                <div class="step-number">Step 1:</div>
                Go to <strong>Settings → Network & Internet → Proxy</strong>
            </div>
            <div class="step">
                <div class="step-number">Step 2:</div>
                Turn on <strong>Use a proxy server</strong>
            </div>
            <div class="step">
                <div class="step-number">Step 3:</div>
                Enter the proxy server details from above
            </div>
        </div>

        <div class="device-section">
            <div class="device-title">
                <span class="device-icon">🍎</span>
                macOS
            </div>
            <div class="step">
                <div class="step-number">Step 1:</div>
                Go to <strong>System Preferences → Network</strong>
            </div>
            <div class="step">
                <div class="step-number">Step 2:</div>
                Select your Wi-Fi connection → <strong>Advanced</strong>
            </div>
            <div class="step">
                <div class="step-number">Step 3:</div>
                Go to <strong>Proxies</strong> tab → Check <strong>Web Proxy (HTTP)</strong>
            </div>
            <div class="step">
                <div class="step-number">Step 4:</div>
                Enter the proxy server details from above
            </div>
        </div>

        <div class="note">
            <strong>💡 Tip:</strong> After configuring the proxy, test it by visiting a website. If it works, you're connected through the ShieldShare proxy!
        </div>

        <div style="text-align: center; margin-top: 30px;">
            <button class="button" onclick="copyProxyInfo()">📋 Copy Proxy Info</button>
            <button class="button" onclick="testConnection()">🔗 Test Connection</button>
        </div>
    </div>

    <script>
        // Auto-detect device and show relevant instructions
        function detectDevice() {
            const userAgent = navigator.userAgent;
            const sections = document.querySelectorAll('.device-section');
            
            // Hide all sections initially
            sections.forEach(section => section.style.display = 'none');
            
            if (/iPhone|iPad|iPod/.test(userAgent)) {
                sections[0].style.display = 'block'; // iOS
            } else if (/Android/.test(userAgent)) {
                sections[1].style.display = 'block'; // Android
            } else if (/Windows/.test(userAgent)) {
                sections[2].style.display = 'block'; // Windows
            } else if (/Macintosh|Mac OS X/.test(userAgent)) {
                sections[3].style.display = 'block'; // macOS
            } else {
                // Show all sections if unknown
                sections.forEach(section => section.style.display = 'block');
            }
        }

        function copyProxyInfo() {
            const proxyUrl = document.getElementById('proxyUrl').textContent;
            const pacUrl = document.getElementById('pacUrl').textContent;
            const text = 'Proxy Server: ' + proxyUrl + '\\nPAC File: ' + pacUrl;
            
            navigator.clipboard.writeText(text).then(() => {
                alert('Proxy information copied to clipboard!');
            });
        }

        function testConnection() {
            // Test if proxy is working by making a request
            fetch('/test', { method: 'GET' })
                .then(response => {
                    if (response.ok) {
                        alert('✅ Connection successful! Proxy is working.');
                    } else {
                        alert('❌ Connection failed. Please check your proxy settings.');
                    }
                })
                .catch(() => {
                    alert('❌ Connection failed. Please check your proxy settings.');
                });
        }

        // Initialize page
        window.onload = function() {
            detectDevice();
            
            // Set proxy URLs (these would be dynamically set by the server)
            const currentHost = window.location.host;
            document.getElementById('proxyUrl').textContent = 'http://' + currentHost;
            document.getElementById('pacUrl').textContent = 'http://' + currentHost + '/proxy.pac';
        };
    </script>
</body>
</html>
        """.trimIndent()
    }
}
