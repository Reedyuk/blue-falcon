package com.example.bluefalconcomposemultiplatform.ble.data

import dev.bluefalcon.BluetoothCharacteristic
import dev.bluefalcon.BluetoothCharacteristicDescriptor
import dev.bluefalcon.BluetoothPeripheral

sealed interface DeviceEvent {
    data class OnDeviceConnected(val macId: String): DeviceEvent
    data class OnDeviceDisconnected(val macId: String): DeviceEvent
    data class OnServicesDiscovered(val macId: String, val peripheral: BluetoothPeripheral): DeviceEvent
    data class OnCharacteristicValueChanged(
        val macId: String,
        val characteristic: BluetoothCharacteristic
    ): DeviceEvent
    data class OnNotificationStateChanged(
        val macId: String,
        val characteristic: BluetoothCharacteristic
    ): DeviceEvent
    data class OnRssiUpdated(val macId: String): DeviceEvent
    data class OnMtuUpdated(val macId: String, val status: Int): DeviceEvent
    data class OnDescriptorRead(
        val macId: String,
        val descriptor: BluetoothCharacteristicDescriptor
    ): DeviceEvent
    data class OnWriteCharacteristicResult(
        val macId: String,
        val characteristic: BluetoothCharacteristic,
        val success: Boolean
    ): DeviceEvent
}