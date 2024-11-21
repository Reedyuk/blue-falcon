package com.example.bluefalconcomposemultiplatform.ble.presentation

import dev.bluefalcon.BluetoothCharacteristic

sealed interface UiEvent {
    object OnScanClick: UiEvent
    data class OnConnectClick(val macId: String): UiEvent
    data class OnDisconnectClick(val macId: String): UiEvent

    data class OnReadCharacteristic(val macId: String, val characteristic: BluetoothCharacteristic): UiEvent
    data class OnWriteCharacteristic(val macId: String, val characteristic: BluetoothCharacteristic, val value: String): UiEvent
}