package com.example.shieldshare.managers.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class IpAddressProviderImpl(
    private val client: OkHttpClient
) : IpAddressProvider {

    private val endpoints = listOf(
        "https://api.ipify.org",
        "https://ifconfig.me/ip",
        "https://checkip.amazonaws.com",
        "https://icanhazip.com"
    )

    override suspend fun getPublicIp(): Result<String> = withContext(Dispatchers.IO) {
        for (url in endpoints) {
            try {
                val req = Request.Builder().url(url).build()
                client.newCall(req).execute().use { resp ->
                    val body = resp.body?.string()?.trim()
                    if (resp.isSuccessful && !body.isNullOrBlank()) {
                        val ip = body.lineSequence().first().trim()
                        if (IP_REGEX.matches(ip)) return@withContext Result.success(ip)
                    }
                }
            } catch (_: Exception) {
                // If fail change to next endpoint
            }
        }
        Result.failure(IllegalStateException("Failed to fetch public IP"))
    }

    private companion object {
        // Simplify the IP add
        val IP_REGEX = Regex("""^([0-9]{1,3}\.){3}[0-9]{1,3}$|^[0-9a-fA-F:]{3,}$""")
    }
}
