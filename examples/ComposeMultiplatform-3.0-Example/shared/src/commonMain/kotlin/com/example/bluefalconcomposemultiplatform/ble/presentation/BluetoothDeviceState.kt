package com.example.bluefalconcomposemultiplatform.ble.presentation

data class BluetoothDeviceState(
    val devices: HashMap<String, EnhancedBluetoothPeripheral> = HashMap(),
    val isScanning: Boolean = false,
    val selectedDeviceId: String? = null,
    /** JSON output from the most recent clone operation, shown in a dialog. */
    val cloneResultJson: String? = null
)