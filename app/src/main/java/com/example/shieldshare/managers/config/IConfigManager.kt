package com.example.shieldshare.managers.config

/**
 * Configuration Manager Interface
 * Based on the CSV specification (IConfigManager)
 */
interface IConfigManager {
    fun getConfig(key: String): String?
    fun setConfig(key: String, value: String)
    fun getBooleanConfig(key: String): Boolean
    fun getIntConfig(key: String): Int
}
