package com.example.bluefalconcomposemultiplatform.ble.presentation

import dev.bluefalcon.core.BluetoothCharacteristic
import dev.bluefalcon.core.BluetoothCharacteristicDescriptor

sealed interface UiEvent {
    object OnScanClick: UiEvent
    object OnStopScanClick: UiEvent
    data class OnConnectClick(val macId: String): UiEvent
    data class OnDisconnectClick(val macId: String): UiEvent
    data class OnDeviceSelected(val macId: String): UiEvent
    object OnNavigateBack: UiEvent
    data class OnRefreshDevice(val macId: String): UiEvent

    data class OnReadCharacteristic(val macId: String, val characteristic: BluetoothCharacteristic): UiEvent
    data class OnWriteCharacteristic(val macId: String, val characteristic: BluetoothCharacteristic, val value: String): UiEvent
    data class OnToggleNotify(val macId: String, val characteristic: BluetoothCharacteristic): UiEvent
    data class OnChangeMtu(val macId: String, val mtuSize: Int): UiEvent
    data class OnReadDescriptor(
        val macId: String,
        val characteristic: BluetoothCharacteristic,
        val descriptor: BluetoothCharacteristicDescriptor
    ): UiEvent
}