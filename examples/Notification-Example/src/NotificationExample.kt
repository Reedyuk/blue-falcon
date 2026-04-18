package dev.bluefalcon.example.notification

import dev.bluefalcon.core.*
import dev.bluefalcon.core.plugin.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Example: Subscribing to BLE Characteristic Notifications
 *
 * Blue Falcon 3.0 provides two complementary ways to consume notification
 * payloads coming from a subscribed characteristic:
 *
 *   1. **Per-characteristic Flow** – `characteristic.notifications: SharedFlow<ByteArray>`
 *      Ideal for piping data into a UI component or a coroutine pipeline.
 *
 *   2. **Plugin hook** – `BlueFalconPlugin.onNotificationReceived(...)`
 *      Ideal for cross-cutting concerns such as logging, metrics, protocol decoding, etc.
 *
 * Both approaches deliver the raw `ByteArray` payload as an explicit argument, avoiding
 * the race condition where `characteristic.value` can be overwritten by the next packet
 * before the consumer reads it.
 */

// =====================================================
// Example 1: Collecting notifications via characteristic Flow
// =====================================================

/**
 * Subscribe to a characteristic and print every notification payload.
 *
 * Usage:
 * ```
 * val blueFalcon = BlueFalcon { engine = AndroidEngine(context) }
 * // after connecting and discovering services…
 * val heartRateChar = peripheral.characteristics.first { it.uuid == heartRateUuid }
 * collectNotifications(blueFalcon, peripheral, heartRateChar)
 * ```
 */
fun CoroutineScope.collectNotifications(
    blueFalcon: BlueFalcon,
    peripheral: BluetoothPeripheral,
    characteristic: BluetoothCharacteristic
) {
    // 1. Enable notifications on the remote device
    launch {
        blueFalcon.notifyCharacteristic(peripheral, characteristic, true)
    }

    // 2. Collect incoming payloads from the characteristic's SharedFlow
    launch {
        characteristic.notifications.collect { value ->
            val hex = value.joinToString(" ") { "%02X".format(it) }
            println("[Flow] Notification on ${characteristic.uuid}: $hex (${value.size} bytes)")
        }
    }
}

// =====================================================
// Example 2: Collecting from the engine-level SharedFlow
// =====================================================

/**
 * The engine exposes a single stream of *all* notification events across every
 * subscribed characteristic on every connected peripheral. This can be useful when
 * you need a centralised consumer (e.g. a packet router).
 */
fun CoroutineScope.collectAllEngineNotifications(blueFalcon: BlueFalcon) {
    launch {
        blueFalcon.engine.characteristicNotifications.collect { notification ->
            val hex = notification.value.joinToString(" ") { "%02X".format(it) }
            println(
                "[Engine] ${notification.peripheral.name ?: notification.peripheral.uuid} → " +
                    "${notification.characteristic.uuid}: $hex"
            )
        }
    }
}

// =====================================================
// Example 3: Plugin that reacts to notifications
// =====================================================

/**
 * A plugin that counts received notifications per characteristic and optionally
 * invokes a user-supplied callback on every packet.
 */
class NotificationLoggerPlugin(
    private val onPacket: ((BluetoothPeripheral, BluetoothCharacteristic, ByteArray) -> Unit)? = null
) : BlueFalconPlugin {

    private val counters = mutableMapOf<String, Long>()

    override fun install(client: BlueFalconClient, config: PluginConfig) {
        println("[NotificationLogger] Plugin installed")
    }

    override suspend fun onNotificationReceived(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        value: ByteArray
    ) {
        val key = "${peripheral.uuid}/${characteristic.uuid}"
        val count = (counters[key] ?: 0) + 1
        counters[key] = count

        val hex = value.joinToString(" ") { "%02X".format(it) }
        println("[NotificationLogger] #$count ${characteristic.uuid}: $hex")

        onPacket?.invoke(peripheral, characteristic, value)
    }

    /** Returns the total number of notifications received for the given characteristic. */
    fun countFor(peripheralUuid: String, characteristicUuid: String): Long =
        counters["$peripheralUuid/$characteristicUuid"] ?: 0
}

// =====================================================
// Example 4: Full wiring – scan, connect, subscribe, observe
// =====================================================

/**
 * End-to-end example that scans for a device, connects, discovers services,
 * enables notifications on a target characteristic, and prints every payload.
 *
 * Replace `targetDeviceName` and `targetCharacteristicUuid` with values for your device.
 */
suspend fun fullNotificationExample(engine: BlueFalconEngine) {
    val loggerPlugin = NotificationLoggerPlugin { peripheral, characteristic, value ->
        println("[callback] Got ${value.size} bytes from ${peripheral.name}")
    }

    val blueFalcon = BlueFalcon {
        this.engine = engine
        install(loggerPlugin)
    }

    // 1. Start scanning
    blueFalcon.scan()

    // 2. Wait for a peripheral to appear
    val peripheral = blueFalcon.peripherals
        .mapNotNull { set -> set.firstOrNull { it.name != null } }
        .first()
    blueFalcon.stopScanning()

    // 3. Connect and discover services
    blueFalcon.connect(peripheral)
    blueFalcon.discoverServices(peripheral)

    // 4. Pick a notifiable characteristic (first one found, for demo purposes)
    val characteristic = peripheral.characteristics.firstOrNull()
        ?: error("No characteristics found")

    // 5. Subscribe to notifications via Flow
    coroutineScope {
        launch {
            characteristic.notifications.collect { value ->
                val hex = value.joinToString(" ") { "%02X".format(it) }
                println("[Flow] ${characteristic.uuid}: $hex")
            }
        }

        // 6. Enable notifications on the remote device
        blueFalcon.notifyCharacteristic(peripheral, characteristic, true)

        // Let it run for 30 seconds, then unsubscribe
        delay(30_000)
        blueFalcon.notifyCharacteristic(peripheral, characteristic, false)
    }

    // 7. Disconnect
    blueFalcon.disconnect(peripheral)

    println("Total packets received: ${loggerPlugin.countFor(peripheral.uuid, characteristic.uuid.toString())}")
}

// =====================================================
// Example 5: Heart-rate monitor pattern
// =====================================================

/**
 * Demonstrates a realistic scenario: connecting to a Heart Rate Monitor,
 * enabling notifications on the Heart Rate Measurement characteristic,
 * and mapping the raw bytes into a domain model.
 */
data class HeartRateSample(val bpm: Int, val timestamp: Long = System.currentTimeMillis())

fun Flow<ByteArray>.asHeartRate(): Flow<HeartRateSample> = map { bytes ->
    // BLE Heart Rate Measurement format:
    //   byte 0: flags (bit 0 = 0 → UINT8 HR, bit 0 = 1 → UINT16 HR)
    //   byte 1 (+ byte 2 if UINT16): heart rate value
    val flags = bytes[0].toInt() and 0xFF
    val bpm = if (flags and 0x01 == 0) {
        bytes[1].toInt() and 0xFF
    } else {
        (bytes[1].toInt() and 0xFF) or ((bytes[2].toInt() and 0xFF) shl 8)
    }
    HeartRateSample(bpm)
}

/**
 * Usage:
 * ```
 * // After connecting and discovering services on a HR monitor:
 * val hrChar = peripheral.characteristics.first {
 *     it.uuid.toString().startsWith("00002a37")
 * }
 * blueFalcon.notifyCharacteristic(peripheral, hrChar, true)
 *
 * hrChar.notifications
 *     .asHeartRate()
 *     .collect { sample ->
 *         println("Heart rate: ${sample.bpm} bpm")
 *     }
 * ```
 */
