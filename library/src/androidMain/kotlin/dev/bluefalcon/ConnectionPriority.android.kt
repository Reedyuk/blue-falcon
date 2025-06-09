package dev.bluefalcon

import android.bluetooth.BluetoothGatt.CONNECTION_PRIORITY_BALANCED
import android.bluetooth.BluetoothGatt.CONNECTION_PRIORITY_HIGH
import android.bluetooth.BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER

actual fun ConnectionPriority.toNative(): Int = when (this) {
    ConnectionPriority.Balanced -> CONNECTION_PRIORITY_BALANCED
    ConnectionPriority.High -> CONNECTION_PRIORITY_HIGH
    ConnectionPriority.Low -> CONNECTION_PRIORITY_LOW_POWER
}
