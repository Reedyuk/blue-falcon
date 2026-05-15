package com.example.bluefalconcomposemultiplatform.di

import android.content.Context
import dev.bluefalcon.core.BlueFalcon
import dev.bluefalcon.core.BluetoothAdvertiser
import dev.bluefalcon.engine.android.AndroidEngine
import dev.bluefalcon.engine.android.createAdvertiser
import dev.bluefalcon.plugins.logging.LoggingPlugin
import dev.bluefalcon.plugins.logging.LogLevel
import dev.bluefalcon.plugins.retry.RetryPlugin
import dev.bluefalcon.plugins.nordicfota.NordicFotaPlugin

actual class AppModule(
    private val context: Context
) {
    actual val fotaPlugin: NordicFotaPlugin = NordicFotaPlugin.create {
        chunkSize = 256
        autoConfirm = true
        autoReset = true
    }

    private val engine = AndroidEngine(context)
    actual val advertiser: BluetoothAdvertiser = engine.createAdvertiser()

    actual val blueFalcon: BlueFalcon = BlueFalcon(
        engine = engine
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
