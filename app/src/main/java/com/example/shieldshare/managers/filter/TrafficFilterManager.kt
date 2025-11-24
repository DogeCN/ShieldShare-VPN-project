package com.example.shieldshare.managers.filter

import android.util.Log
import com.example.shieldshare.data.prefs.AppPrefs
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages traffic filtering rules (port blocking, URL blocking)
 * 
 * SAFETY: All features are disabled by default. Zero impact when disabled.
 */
@Singleton
class TrafficFilterManager @Inject constructor(
    private val appPrefs: AppPrefs
) {
    companion object {
        private const val TAG = "TrafficFilterManager"
        
        // Preference keys
        private const val KEY_PORT_FILTERING_ENABLED = "port_filtering_enabled"
        private const val KEY_URL_BLOCKING_ENABLED = "url_blocking_enabled"
        private const val KEY_BLOCKED_PORTS = "blocked_ports"
        private const val KEY_BLOCKED_DOMAINS = "blocked_domains"
    }
    
    /**
     * Fast check to determine if any filtering is enabled
     * Returns false by default (all features disabled)
     * 
     * This is called first to avoid any processing when disabled.
     */
    fun isFilteringEnabled(): Boolean {
        return appPrefs.getBoolean(KEY_PORT_FILTERING_ENABLED, false) ||
               appPrefs.getBoolean(KEY_URL_BLOCKING_ENABLED, false)
    }
    
    /**
     * Check if a connection should be allowed
     * 
     * SAFETY: Returns ALLOW immediately if filtering is disabled (zero overhead)
     * 
     * @param host Target hostname
     * @param port Target port
     * @param clientIp Client IP address (for future per-client rules)
     * @return FilterResult indicating whether to allow or block
     */
    fun shouldAllowConnection(host: String, port: Int, clientIp: String): FilterResult {
        // FAST PATH: Early return if nothing enabled
        if (!isFilteringEnabled()) {
            return FilterResult.ALLOW
        }
        
        // Check port filtering if enabled
        if (appPrefs.getBoolean(KEY_PORT_FILTERING_ENABLED, false)) {
            if (isPortBlocked(port)) {
                Log.d(TAG, "Connection blocked: Port $port is in blocklist (client: $clientIp, host: $host)")
                return FilterResult.BLOCK("Port $port is blocked")
            }
        }
        
        // Check URL/domain blocking if enabled
        if (appPrefs.getBoolean(KEY_URL_BLOCKING_ENABLED, false)) {
            if (isDomainBlocked(host)) {
                Log.d(TAG, "Connection blocked: Domain $host is in blocklist (client: $clientIp, port: $port)")
                return FilterResult.BLOCK("Domain $host is blocked")
            }
        }
        
        return FilterResult.ALLOW
    }
    
    /**
     * Check if a port is blocked
     * Returns false by default (no ports blocked)
     */
    private fun isPortBlocked(port: Int): Boolean {
        val blockedPorts = getBlockedPorts()
        return blockedPorts.contains(port)
    }
    
    /**
     * Check if a domain is blocked
     * Supports exact match and subdomain matching
     * Returns false by default (no domains blocked)
     */
    private fun isDomainBlocked(host: String): Boolean {
        val blockedDomains = getBlockedDomains()
        if (blockedDomains.isEmpty()) return false
        
        // Check exact match
        if (blockedDomains.contains(host)) {
            return true
        }
        
        // Check subdomain match (e.g., "youtube.com" blocks "www.youtube.com", "m.youtube.com")
        return blockedDomains.any { domain ->
            host.endsWith(".$domain") || host == domain
        }
    }
    
    /**
     * Get list of blocked ports
     * Returns empty set by default (nothing blocked)
     */
    fun getBlockedPorts(): Set<Int> {
        val portStrings = appPrefs.getStringSet(KEY_BLOCKED_PORTS, emptySet())
        return portStrings.mapNotNull { it.toIntOrNull() }.toSet()
    }
    
    /**
     * Get list of blocked domains
     * Returns empty set by default (nothing blocked)
     */
    fun getBlockedDomains(): Set<String> {
        return appPrefs.getStringSet(KEY_BLOCKED_DOMAINS, emptySet())
    }
    
    /**
     * Update blocked ports
     */
    fun updateBlockedPorts(ports: Set<Int>) {
        val portStrings = ports.map { it.toString() }.toSet()
        appPrefs.putStringSet(KEY_BLOCKED_PORTS, portStrings)
        Log.d(TAG, "Updated blocked ports: $ports")
    }
    
    /**
     * Update blocked domains
     */
    fun updateBlockedDomains(domains: Set<String>) {
        appPrefs.putStringSet(KEY_BLOCKED_DOMAINS, domains)
        Log.d(TAG, "Updated blocked domains: $domains")
    }
    
    /**
     * Enable or disable port filtering
     */
    fun setPortFilteringEnabled(enabled: Boolean) {
        appPrefs.putBoolean(KEY_PORT_FILTERING_ENABLED, enabled)
        Log.d(TAG, "Port filtering ${if (enabled) "enabled" else "disabled"}")
    }
    
    /**
     * Enable or disable URL blocking
     */
    fun setUrlBlockingEnabled(enabled: Boolean) {
        appPrefs.putBoolean(KEY_URL_BLOCKING_ENABLED, enabled)
        Log.d(TAG, "URL blocking ${if (enabled) "enabled" else "disabled"}")
    }
    
    /**
     * Check if port filtering is enabled
     */
    fun isPortFilteringEnabled(): Boolean {
        return appPrefs.getBoolean(KEY_PORT_FILTERING_ENABLED, false)
    }
    
    /**
     * Check if URL blocking is enabled
     */
    fun isUrlBlockingEnabled(): Boolean {
        return appPrefs.getBoolean(KEY_URL_BLOCKING_ENABLED, false)
    }
}

