package com.example.bluefalconcomposemultiplatform.ble.data

import dev.bluefalcon.BluetoothDevice
import dev.bluefalcon.BluetoothPeripheral

sealed interface DeviceEvent {
    data class OnDeviceDiscovered(val macId: String, val device: BluetoothDevice): DeviceEvent
    data class OnDeviceConnected(val macId: String): DeviceEvent
    data class OnDeviceDisconnected(val macId: String): DeviceEvent
}