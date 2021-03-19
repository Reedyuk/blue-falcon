package dev.bluefalcon

import kotlin.js.Promise

//https://developer.mozilla.org/en-US/docs/Web/API/BluetoothDevice
data class BluetoothDevice(
    val id: String,
    val name: String?,
    val gatt: BluetoothRemoteGATTServer?
)

interface BluetoothRemoteGATTServer {
    val device: BluetoothDevice
    val connected: Boolean
    fun connect(): Promise<BluetoothRemoteGATTServer>
    fun disconnect()
    @JsName("getPrimaryService")
    fun getPrimaryService(bluetoothServiceUUID: String): Promise<BluetoothRemoteGATTService>
    @JsName("getPrimaryServices")
    fun getPrimaryServices(bluetoothServiceUUID: String?): Promise<List<BluetoothRemoteGATTService>>
}

interface BluetoothRemoteGATTService {
    val uuid: String
    val isPrimary: Boolean
    val device: BluetoothDevice

    fun getCharacteristic(bluetoothCharacteristicUUID: String): Promise<Any>
    //Promise<BluetoothGATTCharacteristic> getCharacteristic(BluetoothCharacteristicUUID characteristic);
    fun getCharacteristics(bluetoothCharacteristicUUID: String?): Promise<List<Any>>
//    Promise<sequence<BluetoothGATTCharacteristic>> getCharacteristics(optional BluetoothCharacteristicUUID characteristic);
//    Promise<BluetoothGATTService> getIncludedService(BluetoothServiceUUID service);
//    Promise<sequence<BluetoothGATTService>> getIncludedServices(optional BluetoothServiceUUID service);
};