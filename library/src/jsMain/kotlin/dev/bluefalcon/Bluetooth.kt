package dev.bluefalcon

import kotlin.js.Promise

external class Bluetooth {
    val referringDevice: BluetoothDevice?
    fun getDevices(): Promise<List<BluetoothDevice>>
    fun requestDevice(options: BluetoothOptions?): Promise<BluetoothDevice>
}

data class BluetoothOptions(
    val acceptAllDevices: Boolean
)
