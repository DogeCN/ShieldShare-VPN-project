package com.example.shieldshare.managers.config

/** Manages application configuration settings and preferences */
interface IConfigManager {
    fun getConfig(key: String): String?
    fun setConfig(key: String, value: String)
    fun getBooleanConfig(key: String): Boolean
    fun getIntConfig(key: String): Int
}
