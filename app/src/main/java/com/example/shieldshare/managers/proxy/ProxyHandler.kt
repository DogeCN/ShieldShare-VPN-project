package com.example.shieldshare.managers.proxy

import com.example.shieldshare.managers.meter.TrafficMeter
import java.net.Socket

/**
 * Abstract base class for proxy handlers using Template Method pattern for connection processing
 */
abstract class ProxyHandler(
        protected val socket: Socket,
        protected val trafficMeter: TrafficMeter
) {
    /** Template method that defines the algorithm structure */
    fun handleConnection() {
        if (authenticate(socket)) {
            handleConnectionInternal()
        } else {
            // Send authentication error response
            sendAuthenticationError()
        }
    }

    /** Abstract method to be implemented by subclasses */
    protected abstract fun handleConnectionInternal()

    /** Hook method for authentication (can be overridden) */
    protected open fun authenticate(client: Socket): Boolean = true

    /**
     * Hook method for recording metrics TODO: JIALU - This method is ready for traffic metering
     * integration
     */
    protected fun recordMetrics(bytesUp: Long, bytesDown: Long) {
        // This will be called by subclasses to record traffic
        // The actual implementation depends on the traffic meter
        // TODO: JIALU - Implement actual traffic recording logic here
    }

    /** Send authentication error response */
    protected open fun sendAuthenticationError() {
        // Default implementation - can be overridden
    }
    
    /** Check if the handler's socket is still valid/connected */
    fun isSocketValid(): Boolean {
        return try {
            !socket.isClosed && socket.isConnected
        } catch (e: Exception) {
            false
        }
    }
}
