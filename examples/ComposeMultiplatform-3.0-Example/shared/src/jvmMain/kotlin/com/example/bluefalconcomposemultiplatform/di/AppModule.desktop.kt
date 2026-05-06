package com.example.bluefalconcomposemultiplatform.di

import dev.bluefalcon.core.BlueFalcon
import dev.bluefalcon.core.BlueFalconEngine
import dev.bluefalcon.engine.macos.jvm.MacosJvmEngine
import dev.bluefalcon.engine.rpi.RpiEngine
import dev.bluefalcon.engine.windows.WindowsEngine
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
