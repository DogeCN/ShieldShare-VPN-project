package com.example.shieldshare.managers.error

/**
 * Error Handler Interface
 * Based on the CSV specification (IErrorHandler)
 */
interface IErrorHandler {
    fun handleError(error: Throwable, context: String)
    fun logError(level: LogLevel, message: String)
    fun reportCrash(exception: Exception)
}

enum class LogLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR,
    FATAL
}
