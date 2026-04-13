package dev.bluefalcon.engine.android

import android.bluetooth.BluetoothGatt
import dev.bluefalcon.core.ConnectionPriority

/**
 * Extension function to convert ConnectionPriority to Android's native constant.
 */
internal fun ConnectionPriority.toNative(): Int = when (this) {
    ConnectionPriority.Balanced -> BluetoothGatt.CONNECTION_PRIORITY_BALANCED
    ConnectionPriority.High -> BluetoothGatt.CONNECTION_PRIORITY_HIGH
    ConnectionPriority.Low -> BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER
}
