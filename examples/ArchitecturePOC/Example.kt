package dev.bluefalcon.example

import dev.bluefalcon.core.*
import dev.bluefalcon.core.plugin.*

/**
 * Proof of Concept Example - demonstrating the new plugin-based engine architecture
 * 
 * This example shows how the new API would be used. Once engines are fully implemented,
 * this code would actually work.
 */

// Example 1: Basic usage with Android engine
fun example1_basicUsage(context: Any /* Android Context */) {
    // Create BlueFalcon with Android engine and logging plugin
    val blueFalcon = BlueFalcon {
        // Uncomment when AndroidEngine is complete:
        // engine = AndroidEngine(context as Context, logger = PrintLnLogger)
        
        // Install logging plugin
        install(LoggingPlugin()) {
            // Plugin configuration would go here
        }
    }
    
    // Use the client
    // suspend {
    //     blueFalcon.scan()
    //     blueFalcon.peripherals.collect { peripherals ->
    //         println("Found ${peripherals.size} devices")
    //     }
    // }
}

// Example 2: Custom plugin
class LoggingPlugin : BlueFalconPlugin {
    override fun install(client: BlueFalconClient, config: PluginConfig) {
        println("LoggingPlugin installed")
    }
    
    override suspend fun onBeforeScan(call: ScanCall): ScanCall {
        println("[LOG] Starting scan with ${call.filters.size} filters")
        return call
    }
    
    override suspend fun onAfterScan(call: ScanCall) {
        println("[LOG] Scan started")
    }
    
    override suspend fun onBeforeConnect(call: ConnectCall): ConnectCall {
        println("[LOG] Connecting to ${call.peripheral.name ?: call.peripheral.uuid}")
        return call
    }
    
    override suspend fun onAfterConnect(call: ConnectCall, result: Result<Unit>) {
        if (result.isSuccess) {
            println("[LOG] Connected successfully")
        } else {
            println("[LOG] Connection failed: ${result.exceptionOrNull()?.message}")
        }
    }
}

// Example 3: Nordic OTA Plugin (conceptual)
class NordicOTAPlugin : BlueFalconPlugin {
    override fun install(client: BlueFalconClient, config: PluginConfig) {
        println("Nordic OTA Plugin installed")
    }
    
    suspend fun updateFirmware(
        peripheral: BluetoothPeripheral,
        firmwareData: ByteArray,
        onProgress: (Int) -> Unit
    ) {
        println("Starting firmware update for ${peripheral.name}")
        // Implementation would:
        // 1. Enter bootloader mode
        // 2. Send firmware packets
        // 3. Verify and reboot
        onProgress(100)
    }
}

// Example 4: Subscribing to characteristic notifications
suspend fun example4_notifications(blueFalcon: BlueFalcon, peripheral: BluetoothPeripheral) {
    // After connecting and discovering services…
    val characteristic = peripheral.characteristics.firstOrNull()
        ?: return

    // Enable notifications on the remote device
    blueFalcon.notifyCharacteristic(peripheral, characteristic, true)

    // Collect incoming notification payloads via the per-characteristic Flow
    kotlinx.coroutines.coroutineScope {
        kotlinx.coroutines.launch {
            characteristic.notifications.collect { value ->
                val hex = value.joinToString(" ") { "%02X".format(it) }
                println("[Notification] ${characteristic.uuid}: $hex")
            }
        }
    }
}

// Example 5: Plugin that reacts to notification payloads
class NotificationLoggingPlugin : BlueFalconPlugin {
    override fun install(client: BlueFalconClient, config: PluginConfig) {
        println("NotificationLoggingPlugin installed")
    }

    override suspend fun onNotificationReceived(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        value: ByteArray
    ) {
        val hex = value.joinToString(" ") { "%02X".format(it) }
        println("[Plugin] Notification from ${peripheral.name}: ${characteristic.uuid} = $hex")
    }
}

// Example 6: Multiple engines for different platforms
fun example6_multiplatformSetup() {
    /*
    val blueFalcon = when {
        Platform.isAndroid -> BlueFalcon {
            engine = AndroidEngine(androidContext)
        }
        Platform.isIOS -> BlueFalcon {
            engine = IOSEngine()
        }
        Platform.isJVM -> BlueFalcon {
            engine = WindowsEngine() // or LinuxEngine()
        }
        Platform.isJS -> BlueFalcon {
            engine = JSEngine()
        }
        else -> error("Unsupported platform")
    }
    */
}
