package com.example.bluefalconcomposemultiplatform.ble.presentation

import dev.bluefalcon.plugins.clone.DeviceClone
import dev.bluefalcon.plugins.broadcast.BroadcastState

data class BluetoothDeviceState(
    val devices: HashMap<String, EnhancedBluetoothPeripheral> = HashMap(),
    val isScanning: Boolean = false,
    val scanUuidFilter: String = "",
    val scanAdvertisementFilter: String = "",
    val selectedDeviceId: String? = null,
    /** JSON output from the most recent clone operation, shown in a dialog. */
    val cloneResultJson: String? = null,
    /** The most recent clone object — used when starting a broadcast. */
    val currentClone: DeviceClone? = null,
    /** State of the local BLE broadcast peripheral. */
    val broadcastState: BroadcastState = BroadcastState.Idle
)