package com.example.bluefalconcomposemultiplatform.di

import android.content.Context
import dev.bluefalcon.core.BlueFalcon
import dev.bluefalcon.engine.android.AndroidEngine
import dev.bluefalcon.plugins.logging.LoggingPlugin
import dev.bluefalcon.plugins.logging.LogLevel
import dev.bluefalcon.plugins.retry.RetryPlugin

actual class AppModule(
    private val context: Context
) {
    actual val blueFalcon: BlueFalcon = BlueFalcon(
        engine = AndroidEngine(context)
    ).apply {
        // Install logging plugin for debugging
        plugins.install(LoggingPlugin(LoggingPlugin.Config().apply {
            level = LogLevel.DEBUG
            logDiscovery = true
            logConnections = true
            logGattOperations = true
        })) { }
        
        // Install retry plugin for better reliability
        plugins.install(RetryPlugin(RetryPlugin.Config().apply {
            maxRetries = 3
            initialDelay = kotlin.time.Duration.parse("1s")
        })) { }
    }
}
