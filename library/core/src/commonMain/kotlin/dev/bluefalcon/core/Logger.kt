package dev.bluefalcon.core

/**
 * Logger interface for Blue Falcon operations
 */
interface Logger {
    fun error(message: String, cause: Throwable? = null)
    fun warn(message: String, cause: Throwable? = null)
    fun info(message: String, cause: Throwable? = null)
    fun debug(message: String, cause: Throwable? = null)
}

/**
 * Default println-based logger implementation
 */
object PrintLnLogger : Logger {
    override fun error(message: String, cause: Throwable?) {
        println("e: $message ${cause ?: ""}")
    }

    override fun warn(message: String, cause: Throwable?) {
        println("w: $message ${cause ?: ""}")
    }

    override fun info(message: String, cause: Throwable?) {
        println("i: $message ${cause ?: ""}")
    }

    override fun debug(message: String, cause: Throwable?) {
        println("d: $message ${cause ?: ""}")
    }
}

/**
 * No-op logger that discards all log messages
 */
object NoOpLogger : Logger {
    override fun error(message: String, cause: Throwable?) {}
    override fun warn(message: String, cause: Throwable?) {}
    override fun info(message: String, cause: Throwable?) {}
    override fun debug(message: String, cause: Throwable?) {}
}
