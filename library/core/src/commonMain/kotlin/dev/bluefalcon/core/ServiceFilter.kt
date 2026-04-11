package dev.bluefalcon.core

/**
 * Filter for scanning BLE devices by service UUID
 */
data class ServiceFilter(
    val uuid: Uuid
)
