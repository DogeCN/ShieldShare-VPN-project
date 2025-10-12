package com.example.shieldshare.managers.error

/** Handles application errors, logging, and crash reporting */
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
