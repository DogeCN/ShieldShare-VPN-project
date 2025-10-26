package com.example.shieldshare.managers.proxy

import android.util.Log
import com.example.shieldshare.managers.meter.TrafficMeter
import com.example.shieldshare.managers.meter.TrafficMeterSimple
import java.io.*
import java.net.*
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.SocketFactory

/**
 * HTTP/HTTPS Proxy Handler with STAGE 2 Traffic Metering
 * Handles HTTP CONNECT requests for HTTPS tunneling and regular HTTP requests
 * Collects detailed traffic data for Jialu's per-device monitoring
 * Ensures all outbound sockets are created via a VPN-bound SocketFactory
 */
class HttpProxyHandler(
        clientSocket: Socket,
        trafficMeter: TrafficMeter,
        private val socketFactory: SocketFactory,
        private val inOverride: InputStream? = null,
        private val trafficCallback: (bytesUp: Long, bytesDown: Long) -> Unit = { _, _ -> }
) : ProxyHandler(clientSocket, trafficMeter) {
    companion object {
        private const val TAG = "HttpProxyHandler"
        private const val BUFFER_SIZE = 8192
        private const val CONNECT_METHOD = "CONNECT"
        private const val HTTP_VERSION = "HTTP/1.1"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val bytesUp = AtomicLong(0)
    private val bytesDown = AtomicLong(0)
    
    // STAGE 2: Enhanced traffic tracking
    private val clientIp = clientSocket.remoteSocketAddress.toString().substringAfter("/").substringBefore(":")
    private var sessionId: String? = null
    private var userAgent: String? = null
    private val hostsAccessed = mutableSetOf<String>()

    /** Entry point from server */
    fun start() {
        scope.launch {
            try {
                // STAGE 2: Start traffic session
                sessionId = (trafficMeter as? TrafficMeterSimple)?.startSession(
                    clientIp = clientIp,
                    protocolType = "HTTP"
                )
                Log.i(TAG, "HTTP session started for client: $clientIp (Session: $sessionId)")
                handleRequest()
            } catch (e: Exception) {
                Log.e(TAG, "Error handling proxy request", e)
            } finally {
                cleanup()
            }
        }
    }

    /** If your base class calls this, keep it delegating to the same request handler. */
    override fun handleConnectionInternal() {
        scope.launch { handleRequest() }
    }

    private suspend fun handleRequest() = withContext(Dispatchers.IO) {
        val clientIn = inOverride ?: socket.getInputStream()
        val clientOut = socket.getOutputStream()
        val reader = BufferedReader(InputStreamReader(clientIn, Charsets.US_ASCII))
        val writer = PrintWriter(OutputStreamWriter(clientOut, Charsets.US_ASCII), true)

        // Read the first request line
        val requestLine = reader.readLine() ?: return@withContext
        Log.d(TAG, "Proxy request: $requestLine")

        val parts = requestLine.split(" ")
        if (parts.size < 3) {
            sendErrorResponse(writer, 400, "Bad Request")
            return@withContext
        }

        val method = parts[0]
        val url = parts[1]

        if (method.equals(CONNECT_METHOD, ignoreCase = true)) {
            handleConnectRequest(reader, writer, url)
        } else {
            handleHttpRequest(reader, writer, method, url)
        }
    }

    /** CONNECT method: establish a TCP tunnel and pump bytes both ways */
    private suspend fun handleConnectRequest(
        reader: BufferedReader,
        writer: PrintWriter,
        url: String
    ) = withContext(Dispatchers.IO) {
        Log.i(TAG, "HTTPS CONNECT request to: $url from $clientIp")

        val (host, port) = parseHostPort(url)
        if (host == null || port == -1) {
            sendErrorResponse(writer, 400, "Bad Request - Invalid host:port")
            return@withContext
        }

        // Track target host for session
        hostsAccessed.add(host)
        // (trafficMeter as? TrafficMeterSimple)?.updateSessionTarget(sessionId, host) // 如果你实现了的话

        // 200 Established, then raw tunnel
        writer.println("$HTTP_VERSION 200 Connection Established")
        writer.println("Proxy-Agent: ShieldShare/1.0")
        writer.println()
        writer.flush()

        // Create target socket via VPN-bound factory
        val targetSocket = connectTarget(host, port)
        try {
            val t1 = scope.launch {
                // client -> target (upload)
                tunnelData(
                    input = socket.getInputStream(),
                    output = targetSocket.getOutputStream(),
                    isUpload = true
                )
            }
            val t2 = scope.launch {
                // target -> client (download)
                tunnelData(
                    input = targetSocket.getInputStream(),
                    output = socket.getOutputStream(),
                    isUpload = false
                )
            }
            joinAll(t1, t2)
        } catch (e: Exception) {
            Log.e(TAG, "Failed CONNECT to $host:$port", e)
            // If the connection is failed
        } finally {
            safeClose(targetSocket)
        }
    }

    /** Plain HTTP proxying (no TLS termination) */
    private suspend fun handleHttpRequest(
        reader: BufferedReader,
        writer: PrintWriter,
        method: String,
        url: String
    ) = withContext(Dispatchers.IO) {
        Log.d(TAG, "Handling HTTP request: $method $url")

        // Read all headers (text-mode)
        val headers = mutableListOf<String>()
        var line: String?
        while (reader.readLine().also { line = it } != null && line!!.isNotEmpty()) {
            val h = line!!
            headers.add(h)
            if (h.lowercase().startsWith("user-agent:")) {
                userAgent = h.substringAfter(":").trim()
            }
        }

        // Parse URL and fall back to Host header if the request line was relative-form
        val (hostFromUrl, portFromUrl, pathFromUrl) = parseHttpUrl(url)
        val hostHeader = headers.firstOrNull { it.startsWith("Host:", ignoreCase = true) }
            ?.substringAfter(":")?.trim()
        val (host, port, path) = when {
            hostFromUrl != null -> Triple(hostFromUrl, portFromUrl, pathFromUrl)
            hostHeader != null -> {
                val (hh, hp) = splitHostPort(hostHeader, defaultPort = 80)
                Triple(hh, hp, url) // url was likely relative path
            }
            else -> Triple(null, -1, url)
        }

        if (host == null || port <= 0) {
            sendErrorResponse(writer, 400, "Bad Request - Invalid URL/Host")
            return@withContext
        }

        hostsAccessed.add(host)

        // Build request preface to forward to target
        val sb = StringBuilder()
        sb.append("$method $path $HTTP_VERSION\r\n")
        headers.forEach { h -> sb.append(h).append("\r\n") }
        sb.append("\r\n")
        val preface = sb.toString().toByteArray(Charsets.US_ASCII)

        // Forward to target via VPN-bound socket
        val targetSocket = connectTarget(host, port)
        try {
            val targetOut = BufferedOutputStream(targetSocket.getOutputStream())
            val targetIn = BufferedInputStream(targetSocket.getInputStream())
            val clientOut = BufferedOutputStream(socket.getOutputStream())

            // Send request line + headers
            forwardThroughVpn(preface, preface.size, targetOut)
            targetOut.flush()

            // Stream response bytes back to client
            val buffer = ByteArray(BUFFER_SIZE)
            var totalDown = 0L
            while (true) {
                val n = targetIn.read(buffer)
                if (n <= 0) break
                clientOut.write(buffer, 0, n)
                totalDown += n
            }
            clientOut.flush()

            // traffic accounting
            bytesDown.addAndGet(totalDown)
            trafficMeter.recordTraffic(clientIp, 0, totalDown)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle HTTP request to $host:$port", e)
            sendErrorResponse(writer, 502, "Bad Gateway")
        } finally {
            safeClose(targetSocket)
        }
    }

    /**
     * Copy bytes from input to output.
     * If isUpload = true, bytes are accounted as client -> target (↑).
     * If false, as target -> client (↓).
     */
    private suspend fun tunnelData(
        input: InputStream,
        output: OutputStream,
        isUpload: Boolean
    ) = withContext(Dispatchers.IO) {
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n <= 0) break
            forwardThroughVpn(buffer, n, output)
            total += n
        }
        output.flush()

        if (isUpload) {
            bytesUp.addAndGet(total)
            trafficMeter.recordTraffic(clientIp, total, 0)
            Log.d(TAG, "↑ Upload: $total bytes from $clientIp")
        } else {
            bytesDown.addAndGet(total)
            trafficMeter.recordTraffic(clientIp, 0, total)
            Log.d(TAG, "↓ Download: $total bytes to $clientIp")
        }
    }

    /**
     * Note: Under the current approach of using the system-level third-party VPN,
     * there is no need to “write into a VPN tunnel” here.
     * All outbound sockets are already bound to the VPN via activeNetwork.socketFactory.
     * This method is only for logging and policy control.
     */
    private suspend fun forwardThroughVpn(data: ByteArray, length: Int, output: OutputStream) {
        withContext(Dispatchers.IO) {
            output.write(data, 0, length) // output belongs to a VPN-bound target socket
        }
    }

    /** Create a target connection using the VPN-bound SocketFactory */
    private fun connectTarget(host: String, port: Int): Socket {
        return socketFactory.createSocket().apply {
            connect(InetSocketAddress(host, port), /* connect timeout */ 10_000)
            soTimeout = 30_000
        }
    }

    private fun sendErrorResponse(writer: PrintWriter, code: Int, message: String) {
        writer.println("$HTTP_VERSION $code $message")
        writer.println("Content-Type: text/plain")
        writer.println("Content-Length: ${message.length}")
        writer.println()
        writer.println(message)
        writer.flush()
    }

    private fun cleanup() {
        try {
            socket.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing client socket", e)
        }

        // STAGE 2: End traffic session and log summary
        sessionId?.let { sid ->
            (trafficMeter as? TrafficMeterSimple)?.endSession(sid)
        }

        val totalUp = bytesUp.get()
        val totalDown = bytesDown.get()
        val macAddress = (trafficMeter as? TrafficMeterSimple)?.mapIpToMac()
            ?.get(clientIp) ?: "unknown"

        Log.i(TAG, "**HTTP session ended** for $clientIp ($macAddress)")
        Log.i(TAG, "   ↑ **Total Upload**: ${formatBytes(totalUp)}")
        Log.i(TAG, "   ↓ **Total Download**: ${formatBytes(totalDown)}")
        Log.i(TAG, "   **Hosts Accessed**: ${hostsAccessed.size} - ${hostsAccessed.joinToString(", ")}")
        Log.i(TAG, "   **User Agent**: ${userAgent ?: "Unknown"}")

        trafficCallback(totalUp, totalDown)
        scope.cancel()
    }

    private fun parseHostPort(url: String): Pair<String?, Int> = try {
        val parts = url.split(":")
        if (parts.size == 2) parts[0] to parts[1].toInt() else null to -1
    } catch (_: Throwable) {
        null to -1
    }

    /** For absolute-form proxy requests (http://host:port/path). */
    private fun parseHttpUrl(url: String): Triple<String?, Int, String> = try {
        val uri = URI(url)
        val host = uri.host
        val port = if (uri.port != -1) uri.port else 80
        val path = (uri.rawPath ?: "/") +
                (if (uri.rawQuery != null) "?${uri.rawQuery}" else "")
        Triple(host, port, path)
    } catch (_: Throwable) {
        Triple(null, -1, url)
    }

    private fun splitHostPort(hostHeader: String, defaultPort: Int): Pair<String, Int> {
        val idx = hostHeader.lastIndexOf(':')
        return if (idx > 0 && idx < hostHeader.length - 1 && hostHeader.indexOf(']') == -1) {
            // simple host:port (no IPv6 bracket form)
            val h = hostHeader.substring(0, idx).trim()
            val p = hostHeader.substring(idx + 1).trim().toIntOrNull() ?: defaultPort
            h to p
        } else {
            hostHeader.trim() to defaultPort
        }
    }

    private fun safeClose(s: Socket?) = try { s?.close() } catch (_: Throwable) {}

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
        else -> "${bytes / (1024 * 1024 * 1024)} GB"
    }
}