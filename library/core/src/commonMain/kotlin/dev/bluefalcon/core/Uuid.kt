package dev.bluefalcon.core

import kotlin.uuid.Uuid as KotlinUuid

/**
 * Cross-platform UUID representation for Bluetooth services and characteristics
 */
typealias Uuid = KotlinUuid

/**
 * Helper extensions for UUID
 */
fun String.toUuid(): Uuid = KotlinUuid.parse(this)

fun Uuid.toShortString(): String {
    // Return short form for standard Bluetooth UUIDs (e.g., "180A" for "0000180A-0000-1000-8000-00805F9B34FB")
    val full = this.toString()
    return if (full.startsWith("0000") && full.endsWith("-0000-1000-8000-00805f9b34fb")) {
        full.substring(4, 8)
    } else {
        full
    }
}
