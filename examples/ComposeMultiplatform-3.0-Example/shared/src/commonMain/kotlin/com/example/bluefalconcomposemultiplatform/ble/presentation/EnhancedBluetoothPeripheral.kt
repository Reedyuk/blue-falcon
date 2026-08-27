package com.example.bluefalconcomposemultiplatform.ble.presentation

import dev.bluefalcon.core.BluetoothPeripheral
import dev.bluefalcon.plugins.nordicfota.FotaState
import dev.bluefalcon.plugins.proximity.ProximityZone

data class EnhancedBluetoothPeripheral(
    val connected: Boolean,
    val peripheral: BluetoothPeripheral,
    /** True while a connect/disconnect operation is in flight for this device. */
    val connecting: Boolean = false,
    val updateCount: Long = 0,
    val mtuStatus: String? = null,
    val fotaState: FotaState = FotaState.Idle,
    /** Latest notification payload per characteristic UUID (hex-encoded). */
    val notificationData: Map<String, String> = emptyMap(),
    /** Whether a clone operation is in progress. */
    val cloneInProgress: Boolean = false,
    /** Smoothed RSSI from ProximityPlugin (Kalman-filtered), falls back to peripheral.rssi on first discovery. */
    val rssi: Float? = null,
    /** Proximity zone classification from ProximityPlugin (Immediate, Near, Far, Unknown). */
    val proximityZone: ProximityZone = ProximityZone.Unknown,
    /** Estimated distance in meters from ProximityPlugin (using log-distance path-loss model). */
    val estimatedDistanceMeters: Double? = null,
    /** Manufacturer-specific data from scan advertisement: company ID → hex payload string. */
    val manufacturerData: Map<Int, String> = emptyMap(),
    /**
     * Human-readable reason the last connect attempt failed or the peripheral unexpectedly
     * disconnected, derived from [dev.bluefalcon.core.DisconnectReason] via
     * [dev.bluefalcon.core.BlueFalcon.connectionStates]. `null` while connected/connecting or if
     * the peripheral has never failed/dropped.
     */
    val connectionError: String? = null
)