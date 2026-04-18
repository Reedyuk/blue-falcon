package com.example.bluefalconcomposemultiplatform.ble.presentation

import dev.bluefalcon.core.BluetoothPeripheral
import dev.bluefalcon.plugins.nordicfota.FotaState

data class EnhancedBluetoothPeripheral(
    val connected: Boolean,
    val peripheral: BluetoothPeripheral,
    val updateCount: Long = 0,
    val mtuStatus: String? = null,
    val fotaState: FotaState = FotaState.Idle,
    /** Latest notification payload per characteristic UUID (hex-encoded). */
    val notificationData: Map<String, String> = emptyMap()
)