package com.example.bluefalconcomposemultiplatform.di

import com.example.bluefalconcomposemultiplatform.peripheral.PeripheralExampleRuntime
import dev.bluefalcon.core.BlueFalcon
import dev.bluefalcon.core.BlueFalconEngine
import dev.bluefalcon.engine.macos.jvm.MacosJvmEngine
import dev.bluefalcon.engine.rpi.RpiEngine
import dev.bluefalcon.engine.windows.WindowsEngine
import dev.bluefalcon.peripheral.BluetoothAdvertiser
import dev.bluefalcon.peripheral.NoOpBluetoothAdvertiser
import dev.bluefalcon.plugins.logging.LogLevel
import dev.bluefalcon.plugins.logging.LoggingPlugin
import dev.bluefalcon.plugins.nordicfota.NordicFotaPlugin
import dev.bluefalcon.plugins.proximity.ProximityPlugin
import dev.bluefalcon.plugins.proximity.SmoothingStrategy
import dev.bluefalcon.plugins.retry.RetryPlugin
import dev.bluefalcon.plugins.bonding.BondingPlugin

actual class AppModule {
    actual val fotaPlugin: NordicFotaPlugin = NordicFotaPlugin.create {
        chunkSize = 256
        autoConfirm = true
        autoReset = true
    }

    actual val bondingPlugin: BondingPlugin = BondingPlugin.create()

    actual val proximityPlugin: ProximityPlugin = ProximityPlugin.create {
        smoothing = SmoothingStrategy.Kalman()
        immediateThreshold = -50f
        nearThreshold = -75f
    }

    // Desktop JVM engines do not support the peripheral/advertising role
    actual val advertiser: BluetoothAdvertiser = NoOpBluetoothAdvertiser()
    actual val peripheralRuntime: PeripheralExampleRuntime? = null

    actual val blueFalcon: BlueFalcon = BlueFalcon(
        engine = createDesktopEngine()
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

        // Install bonding plugin and bind to this BlueFalcon instance
        plugins.install(bondingPlugin) { }
        bondingPlugin.bind(this)

        // Install Proximity plugin for RSSI smoothing and distance estimation
        plugins.install(proximityPlugin) { }
    }
}

private fun createDesktopEngine(): BlueFalconEngine {
    val os = System.getProperty("os.name").lowercase()
    return when {
        os.contains("win") -> WindowsEngine()
        os.contains("linux") -> RpiEngine()
        os.contains("mac") -> MacosJvmEngine()
        else -> throw UnsupportedOperationException("No JVM BLE engine available for OS: '$os'")
    }
}
