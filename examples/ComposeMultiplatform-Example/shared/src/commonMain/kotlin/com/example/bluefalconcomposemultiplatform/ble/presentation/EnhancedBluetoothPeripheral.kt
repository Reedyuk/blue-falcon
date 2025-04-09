package com.example.bluefalconcomposemultiplatform.ble.presentation

import dev.bluefalcon.BluetoothDevice
import dev.bluefalcon.BluetoothPeripheral

data class EnhancedBluetoothPeripheral(
    val connected: Boolean,
//    val peripheral: BluetoothPeripheral
    val peripheral: BluetoothDevice
)