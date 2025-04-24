package com.example.bluefalconcomposemultiplatform.ble.presentation

import dev.bluefalcon.BTCharacteristic

sealed interface UiEvent {
    object OnScanClick: UiEvent
    data class OnConnectClick(val macId: String): UiEvent
    data class OnDisconnectClick(val macId: String): UiEvent
    data class OnShowDetailsClick(val macId: String): UiEvent

    data class OnExportDetailsClick(val macId: String): UiEvent

    data class OnReadCharacteristic(val macId: String, val characteristic: BTCharacteristic): UiEvent
    data class OnWriteCharacteristic(val macId: String, val characteristic: BTCharacteristic, val value: String): UiEvent
}