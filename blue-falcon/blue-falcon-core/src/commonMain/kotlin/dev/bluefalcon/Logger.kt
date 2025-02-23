package dev.bluefalcon

interface Logger {
    fun error(message: String, cause: Throwable? = null)
    fun warn(message: String, cause: Throwable? = null)
    fun info(message: String, cause: Throwable? = null)
    fun debug(message: String, cause: Throwable? = null)
}

