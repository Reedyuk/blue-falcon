package com.example.bluefalconcomposemultiplatform.ble.presentation

data class BluetoothDeviceState(
    val devices: HashMap<String, EnhancedBluetoothPeripheral> = HashMap(),
    val isScanning: Boolean = false
)