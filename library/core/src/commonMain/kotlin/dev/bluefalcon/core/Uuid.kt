package dev.bluefalcon.core

import kotlin.uuid.Uuid as KotlinUuid

/**
 * Cross-platform UUID representation for Bluetooth services and characteristics
 */
typealias Uuid = KotlinUuid

/**
 * Bluetooth Base UUID: 0000xxxx-0000-1000-8000-00805F9B34FB
 * Short UUIDs (16-bit or 32-bit) are expanded using this base
 */
private const val BLUETOOTH_BASE_UUID = "-0000-1000-8000-00805f9b34fb"

/**
 * Helper extensions for UUID
 */
fun String.toUuid(): Uuid {
    // Handle short-form Bluetooth UUIDs
    val normalizedString = when (this.length) {
        4 -> "0000$this$BLUETOOTH_BASE_UUID" // 16-bit UUID (e.g., "1800")
        8 -> "$this$BLUETOOTH_BASE_UUID"     // 32-bit UUID (e.g., "00001800")
        else -> this                          // Already full UUID
    }
    return KotlinUuid.parse(normalizedString)
}

fun Uuid.toShortString(): String {
    // Return short form for standard Bluetooth UUIDs (e.g., "180A" for "0000180A-0000-1000-8000-00805F9B34FB")
    val full = this.toString()
    return if (full.startsWith("0000") && full.endsWith("-0000-1000-8000-00805f9b34fb")) {
        full.substring(4, 8)
    } else {
        full
    }
}
