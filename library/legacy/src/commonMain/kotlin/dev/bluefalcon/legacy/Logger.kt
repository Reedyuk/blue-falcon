package dev.bluefalcon.legacy

/**
 * Logger interface for backward compatibility
 */
interface Logger {
    fun log(message: String)
}

/**
 * Default logger that uses println
 */
object PrintLnLogger : Logger {
    override fun log(message: String) {
        println("BlueFalcon: $message")
    }
}
