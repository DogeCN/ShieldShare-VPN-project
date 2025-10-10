package com.example.shieldshare.managers.logging

import com.example.shieldshare.managers.error.LogLevel

/**
 * Logger Interface
 * Based on the CSV specification (ILogger)
 */
interface ILogger {
    fun log(level: LogLevel, message: String)
    fun logDebug(message: String)
    fun logError(message: String, throwable: Throwable?)
    fun logPerformance(operation: String, duration: Long)
}
