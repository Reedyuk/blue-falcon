package com.example.bluefalconcomposemultiplatform.di

import dev.bluefalcon.core.BlueFalcon
import dev.bluefalcon.engine.ios.IosEngine
import dev.bluefalcon.plugins.logging.LoggingPlugin
import dev.bluefalcon.plugins.logging.LogLevel
import dev.bluefalcon.plugins.retry.RetryPlugin
import dev.bluefalcon.plugins.nordicfota.NordicFotaPlugin

actual class AppModule {
    actual val fotaPlugin: NordicFotaPlugin = NordicFotaPlugin.create {
        chunkSize = 256
        autoConfirm = true
        autoReset = true
    }

    actual val blueFalcon: BlueFalcon = BlueFalcon(
        engine = IosEngine()
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

        // Install Nordic FOTA plugin
        plugins.install(fotaPlugin) { }
    }
}
