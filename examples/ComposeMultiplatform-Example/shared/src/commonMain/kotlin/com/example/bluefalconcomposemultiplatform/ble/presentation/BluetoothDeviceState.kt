package com.example.bluefalconcomposemultiplatform.ble.presentation

import dev.bluefalcon.BluetoothPeripheral

data class BluetoothDeviceState(
    val devices: HashMap<String, EnhancedBluetoothPeripheral> = HashMap()
)