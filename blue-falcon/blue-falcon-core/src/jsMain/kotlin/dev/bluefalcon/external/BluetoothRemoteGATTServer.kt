package dev.bluefalcon.external

import kotlin.js.Promise

external interface BluetoothRemoteGATTServer {
    val device: BluetoothDevice
    val connected: Boolean
    fun connect(): Promise<BluetoothRemoteGATTServer>
    fun disconnect()
    @JsName("getPrimaryService")
    fun getPrimaryService(bluetoothServiceUUID: String): Promise<BluetoothRemoteGATTService>
    @JsName("getPrimaryServices")
    fun getPrimaryServices(bluetoothServiceUUID: String?): Promise<Array<BluetoothRemoteGATTService>>
}