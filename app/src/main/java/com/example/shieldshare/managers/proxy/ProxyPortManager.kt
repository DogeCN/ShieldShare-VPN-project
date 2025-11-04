package com.example.shieldshare.managers.proxy

import android.util.Log

/**
 * Manages proxy port configuration to prevent conflicts and crashes Based on team feedback,
 * simplifies port selection to avoid complexity
 */
object ProxyPortManager {
    private const val TAG = "ProxyPortManager"

    // Reserved, non-customisable ports for the proxy stack
    const val HTTP_PORT = 8080
    const val HTTPS_PORT = 8080
    const val SOCKS5_PORT = 1080
    const val CONFIG_PORT = 8081

    /**
     * Validate that a proxy configuration is using the standard ports.
     * Any deviation is logged so callers can fall back to defaults.
     */
    fun validatePortConfiguration(config: ProxyConfig): Boolean {
        val valid =
                config.httpPort == HTTP_PORT &&
                        config.httpsPort == HTTPS_PORT &&
                        config.socks5Port == SOCKS5_PORT

        if (!valid) {
            Log.w(TAG, "Non-standard proxy ports detected; ShieldShare forces fixed defaults.")
        }
        return valid
    }

    /** User-facing recommendations for documentation or UI surfaces. */
    fun getPortRecommendations(): Map<String, String> {
        return mapOf(
                "HTTP/HTTPS Proxy" to "Port $HTTP_PORT",
                "SOCKS5 Proxy" to "Port $SOCKS5_PORT",
                "Auto Config Portal" to "Port $CONFIG_PORT (PAC + QR landing page)"
        )
    }
}
