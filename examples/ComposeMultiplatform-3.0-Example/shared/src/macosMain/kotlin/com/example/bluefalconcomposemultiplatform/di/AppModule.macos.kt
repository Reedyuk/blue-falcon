package com.example.bluefalconcomposemultiplatform.di

import com.example.bluefalconcomposemultiplatform.peripheral.PeripheralExampleRuntime
import dev.bluefalcon.core.BlueFalcon
import dev.bluefalcon.engine.macos.MacosEngine
import dev.bluefalcon.peripheral.BluetoothAdvertiser
import dev.bluefalcon.peripheral.apple.createBluetoothAdvertiser
import dev.bluefalcon.peripheral.apple.createBlueFalconPeripheral
import dev.bluefalcon.plugins.logging.LogLevel
import dev.bluefalcon.plugins.logging.LoggingPlugin
import dev.bluefalcon.plugins.nordicfota.NordicFotaPlugin
import dev.bluefalcon.plugins.proximity.ProximityPlugin
import dev.bluefalcon.plugins.proximity.SmoothingStrategy
import dev.bluefalcon.plugins.queue.QueuePlugin
import dev.bluefalcon.plugins.retry.RetryPlugin
import dev.bluefalcon.plugins.bonding.BondingPlugin

actual class AppModule {
    actual val fotaPlugin: NordicFotaPlugin = NordicFotaPlugin.create {
        chunkSize = 256
        autoConfirm = true
        autoReset = true
    }

<<<<<<< HEAD
    actual val proximityPlugin: ProximityPlugin = ProximityPlugin.create {
        smoothing = SmoothingStrategy.Kalman()
        immediateThreshold = -50f
        nearThreshold = -75f
    }
=======
    actual val bondingPlugin: BondingPlugin = BondingPlugin.create()
>>>>>>> origin/master

    private val engine = MacosEngine()
    actual val advertiser: BluetoothAdvertiser = createBluetoothAdvertiser()
    private val peripheralManager = createBlueFalconPeripheral()
    actual val peripheralRuntime: PeripheralExampleRuntime? = PeripheralExampleRuntime(
        manager = peripheralManager,
        queue = peripheralManager.plugins.install(QueuePlugin) {
            maxPendingItemsPerSession = 64
            maxPendingBytes = 64 * 1024
        },
    )

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

<<<<<<< HEAD
        // Install Proximity plugin for RSSI smoothing and distance estimation
        plugins.install(proximityPlugin) { }
=======
        // Install bonding plugin and bind to this BlueFalcon instance
        plugins.install(bondingPlugin) { }
        bondingPlugin.bind(this)
>>>>>>> origin/master
    }
}
