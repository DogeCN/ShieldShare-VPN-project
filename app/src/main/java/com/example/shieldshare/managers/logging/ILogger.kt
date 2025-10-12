package com.example.shieldshare.managers.logging

import com.example.shieldshare.managers.error.LogLevel

/** Provides structured logging functionality with different log levels and performance tracking */
interface ILogger {
    fun log(level: LogLevel, message: String)
    fun logDebug(message: String)
    fun logError(message: String, throwable: Throwable?)
    fun logPerformance(operation: String, duration: Long)
}
