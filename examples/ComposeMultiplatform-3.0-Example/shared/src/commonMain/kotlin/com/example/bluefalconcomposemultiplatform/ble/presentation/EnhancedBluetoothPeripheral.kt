package com.example.bluefalconcomposemultiplatform.ble.presentation

import dev.bluefalcon.core.BluetoothPeripheral

data class EnhancedBluetoothPeripheral(
    val connected: Boolean,
    val peripheral: BluetoothPeripheral,
    val updateCount: Long = 0,
    val mtuStatus: String? = null
)