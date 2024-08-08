package dev.bluefalcon

object PrintLnLogger: Logger {
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