package com.example.shieldshare.managers.proxy

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
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
import com.example.shieldshare.managers.vpn.vpnAwareSocketFactory
import javax.net.SocketFactory

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
                            "Proxy server running on ports $HTTP_PROXY_PORT (HTTP/HTTPS) and $SOCKS5_PROXY_PORT (SOCKS5)"
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

    private suspend fun startHttpProxyServer() = withContext(Dispatchers.IO) {
        try {
            httpServerSocket = ServerSocket(HTTP_PROXY_PORT).apply { reuseAddress = true }
            Log.i(TAG, "HTTP proxy server listening on port $HTTP_PROXY_PORT")

            while (isActive && !httpServerSocket!!.isClosed) {
                val clientSocket = try {
                    httpServerSocket!!.accept()
                } catch (e: SocketException) {
                    if (!httpServerSocket!!.isClosed) {
                        Log.e(TAG, "HTTP accept failed", e)
                    }
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "HTTP accept error", e)
                    continue
                }

                activeConnections.incrementAndGet()

                // 独立协程处理每个连接
                serviceScope.launch(Dispatchers.IO) {
                    try {
                        clientSocket.tcpNoDelay = true

                        val rawIn = BufferedInputStream(clientSocket.getInputStream())
                        val pb = PushbackInputStream(rawIn, 8192)
                        val (firstLine, firstBytes) = readFirstRequestLine(pb)

                        val line = firstLine ?: ""
                        val localAddr = clientSocket.localAddress?.hostAddress ?: "127.0.0.1"

                        // 路由：/proxy.pac 或 /configure
                        val isPac = Regex("""\s/+(?i:proxy\.pac)(?:\s|\?|$)""")
                            .containsMatchIn(line)
                        val isConfigure = Regex("""\s/+(?i:configure)(?:\s|\?|$)""")
                            .containsMatchIn(line)

                        if (isPac) {
                            // 生成最简 PAC（HTTP 优先，其次 SOCKS5，再不行 DIRECT）
                            val pac = """
                            function FindProxyForURL(url, host) {
                                return "PROXY $localAddr:$HTTP_PROXY_PORT; SOCKS5 $localAddr:$SOCKS5_PROXY_PORT; DIRECT";
                            }
                        """.trimIndent().toByteArray(Charsets.UTF_8)

                            writeHttpResponse(
                                socket = clientSocket,
                                status = "200 OK",
                                contentType = "application/x-ns-proxy-autoconfig; charset=utf-8",
                                body = pac
                            )
                            return@launch
                        }

                        if (isConfigure) {
                            val pacUrl = "http://$localAddr:$HTTP_PROXY_PORT/proxy.pac"
                            val html = """
                            <!doctype html>
                            <html>
                            <head><meta charset="utf-8"><title>ShieldShare Configure</title></head>
                            <body>
                              <h1>ShieldShare Proxy</h1>
                              <p>Auto Proxy URL (PAC): <a href="$pacUrl">$pacUrl</a></p>
                              <p>HTTP/HTTPS Proxy: $localAddr:$HTTP_PROXY_PORT</p>
                              <p>SOCKS5 Proxy: $localAddr:$SOCKS5_PROXY_PORT</p>
                            </body>
                            </html>
                        """.trimIndent().toByteArray(Charsets.UTF_8)

                            writeHttpResponse(
                                socket = clientSocket,
                                status = "200 OK",
                                contentType = "text/html; charset=utf-8",
                                body = html
                            )
                            return@launch
                        }

                        // 普通 HTTP/HTTPS 代理流量：把已读请求行推回去交给真正的代理处理
                        if (firstBytes.isNotEmpty()) {
                            pb.unread(firstBytes)
                        }

                        val sf: SocketFactory = applicationContext.vpnAwareSocketFactory(strict = true)

                        // Get VPN Network object for DNS resolution
                        val vpnNetwork = try {
                            val cm = applicationContext.getSystemService(ConnectivityManager::class.java)
                            val net = cm.activeNetwork
                            val caps = net?.let { cm.getNetworkCapabilities(it) }
                            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) {
                                net
                            } else {
                                null
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to get VPN network: ${e.message}")
                            null
                        }

                        val handler: ProxyHandler =
                            HttpProxyHandler(
                                clientSocket = clientSocket,
                                trafficMeter = trafficMeter,
                                socketFactory = sf,
                                vpnNetwork = vpnNetwork,
                                inOverride = pb
                            ) { bytesUp, bytesDown ->
                                Log.d(TAG, "HTTP proxy traffic: up=$bytesUp, down=$bytesDown")
                                activeConnections.decrementAndGet()
                            }

                        // 在当前协程中执行连接处理
                        handler.handleConnection()
                    } catch (e: Exception) {
                        Log.e(TAG, "HTTP connection error", e)
                        runCatching { clientSocket.close() }
                            .onFailure { ce -> Log.w(TAG, "HTTP client close failed", ce) }
                        // 仅当未通过回调减少连接计数时，这里兜底减少
                        activeConnections.decrementAndGet()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start HTTP proxy server", e)
        }
    }

    private suspend fun startSocks5ProxyServer() = withContext(Dispatchers.IO) {
        try {
            socks5ServerSocket = ServerSocket(SOCKS5_PROXY_PORT)
            Log.i(TAG, "SOCKS5 proxy server listening on port $SOCKS5_PROXY_PORT")

            while (isActive && !socks5ServerSocket!!.isClosed) {
                try {
                    val clientSocket = socks5ServerSocket!!.accept()
                    activeConnections.incrementAndGet()

                    // 为 SOCKS5 分支补齐 socketFactory
                    val sf: SocketFactory = applicationContext.vpnAwareSocketFactory(strict = true)

                    // Get VPN Network object for DNS resolution
                    val vpnNetwork = try {
                        val cm = applicationContext.getSystemService(ConnectivityManager::class.java)
                        val net = cm.activeNetwork
                        val caps = net?.let { cm.getNetworkCapabilities(it) }
                        if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) {
                            net
                        } else {
                            null
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to get VPN network: ${e.message}")
                        null
                    }

                    val handler: ProxyHandler =
                        Socks5ProxyHandler(
                            clientSocket = clientSocket,
                            trafficMeter = trafficMeter,
                            socketFactory = sf,
                            vpnNetwork = vpnNetwork
                        ) { bytesUp, bytesDown ->
                            Log.d(TAG, "SOCKS5 proxy traffic: up=$bytesUp, down=$bytesDown")
                            activeConnections.decrementAndGet()
                        }

                    // 使用协程跑模板方法，而不是调用不存在的 start()
                    serviceScope.launch { handler.handleConnection() }
                } catch (e: SocketException) {
                    if (!socks5ServerSocket!!.isClosed) {
                        Log.e(TAG, "SOCKS5 accept failed", e)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "SOCKS5 handler error", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start SOCKS5 proxy server", e)
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


    private fun readFirstRequestLine(pb: PushbackInputStream): Pair<String?, ByteArray> {
        val baos = ByteArrayOutputStream()
        val sb = StringBuilder()
        var prevCR = false

        while (true) {
            val b = pb.read()
            if (b == -1) break
            baos.write(b)

            if (b == '\n'.code) break
            if (prevCR && b != '\n'.code) break

            if (b != '\r'.code && b != '\n'.code) {
                sb.append(b.toChar())
            }
            prevCR = (b == '\r'.code)
            // 简单保护：首行超长则提前停止
            if (baos.size() >= 4096) break
        }

        val line = sb.toString().ifBlank { null }
        return line to baos.toByteArray()
    }

    private fun writeHttpResponse(
        socket: Socket,
        status: String,
        contentType: String,
        body: ByteArray
    ) {
        try {
            val out = BufferedOutputStream(socket.getOutputStream())
            val header = buildString {
                append("HTTP/1.1 ").append(status).append("\r\n")
                append("Content-Type: ").append(contentType).append("\r\n")
                append("Content-Length: ").append(body.size).append("\r\n")
                append("Connection: close\r\n")
                append("Cache-Control: no-cache, no-store, must-revalidate\r\n")
                append("Pragma: no-cache\r\n")
                append("Expires: 0\r\n")
                append("\r\n")
            }.toByteArray(Charsets.UTF_8)

            out.write(header)
            out.write(body)
            out.flush()
        } catch (e: Exception) {
            Log.w(TAG, "writeHttpResponse failed", e)
        } finally {
            runCatching { socket.close() }
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
