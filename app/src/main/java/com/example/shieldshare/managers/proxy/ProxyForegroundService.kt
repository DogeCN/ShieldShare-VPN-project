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
import kotlinx.coroutines.*
import java.io.*
import java.net.*
import java.util.concurrent.atomic.AtomicInteger

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
    private val trafficMeter: TrafficMeter = TrafficMeterNoop() // TODO: Inject proper implementation

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

    private fun createNotification() = NotificationCompat.Builder(this, "proxy")
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .setContentTitle("ShieldShare Proxy")
        .setContentText("Proxy server running on ports $HTTP_PROXY_PORT (HTTP) and $SOCKS5_PROXY_PORT (SOCKS5)")
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
            httpServerSocket = ServerSocket(HTTP_PROXY_PORT)
            Log.i(TAG, "HTTP proxy server listening on port $HTTP_PROXY_PORT")

            while (isActive && !httpServerSocket!!.isClosed) {
                try {
                    val clientSocket = httpServerSocket!!.accept()
                    activeConnections.incrementAndGet()
                    
                    // Handle PAC file requests
                    if (isPacFileRequest(clientSocket)) {
                        handlePacFileRequest(clientSocket)
                    } else {
                        // Handle regular proxy requests
                        val handler = HttpProxyHandler(clientSocket, trafficMeter) { bytesUp, bytesDown ->
                            Log.d(TAG, "HTTP proxy traffic: up=$bytesUp, down=$bytesDown")
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

    private suspend fun startSocks5ProxyServer() = withContext(Dispatchers.IO) {
        try {
            socks5ServerSocket = ServerSocket(SOCKS5_PROXY_PORT)
            Log.i(TAG, "SOCKS5 proxy server listening on port $SOCKS5_PROXY_PORT")

            while (isActive && !socks5ServerSocket!!.isClosed) {
                try {
                    val clientSocket = socks5ServerSocket!!.accept()
                    activeConnections.incrementAndGet()
                    
                    val handler = Socks5ProxyHandler(clientSocket, trafficMeter) { bytesUp, bytesDown ->
                        Log.d(TAG, "SOCKS5 proxy traffic: up=$bytesUp, down=$bytesDown")
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
}
