package com.example.bluefalconcomposemultiplatform.di

import dev.bluefalcon.core.BlueFalcon
import dev.bluefalcon.engine.macos.MacosEngine
import dev.bluefalcon.peripheral.BluetoothAdvertiser
import dev.bluefalcon.peripheral.apple.createBluetoothAdvertiser
import dev.bluefalcon.plugins.logging.LogLevel
import dev.bluefalcon.plugins.logging.LoggingPlugin
import dev.bluefalcon.plugins.nordicfota.NordicFotaPlugin
import dev.bluefalcon.plugins.retry.RetryPlugin

actual class AppModule {
    actual val fotaPlugin: NordicFotaPlugin = NordicFotaPlugin.create {
        chunkSize = 256
        autoConfirm = true
        autoReset = true
    }

    private val engine = MacosEngine()
    actual val advertiser: BluetoothAdvertiser = createBluetoothAdvertiser()

    actual val blueFalcon: BlueFalcon = BlueFalcon(
        engine = engine
    ).apply {
        plugins.install(LoggingPlugin(LoggingPlugin.Config().apply {
            level = LogLevel.DEBUG
            logDiscovery = true
            logConnections = true
            logGattOperations = true
        })) { }

        plugins.install(RetryPlugin(RetryPlugin.Config().apply {
            maxRetries = 3
            initialDelay = kotlin.time.Duration.parse("1s")
        })) { }

        plugins.install(fotaPlugin) { }
    }
}
