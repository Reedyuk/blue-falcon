package dev.bluefalcon

import platform.CoreBluetooth.CBUUID

actual data class ServiceFilter(
    val serviceUuids: List<CBUUID> = emptyList(),
)
