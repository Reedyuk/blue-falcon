package dev.bluefalcon.external

import org.w3c.dom.events.EventTarget
import kotlin.js.Promise

abstract external class BluetoothRemoteGATTService: EventTarget {
    val uuid: String
    val isPrimary: Boolean
    val device: BluetoothDevice

    fun getCharacteristic(bluetoothCharacteristicUUID: String): Promise<BluetoothRemoteGATTCharacteristic>
    //Promise<BluetoothGATTCharacteristic> getCharacteristic(BluetoothCharacteristicUUID characteristic);
    fun getCharacteristics(bluetoothCharacteristicUUID: String?): Promise<Array<BluetoothRemoteGATTCharacteristic>>
//    Promise<sequence<BluetoothGATTCharacteristic>> getCharacteristics(optional BluetoothCharacteristicUUID characteristic);
//    Promise<BluetoothGATTService> getIncludedService(BluetoothServiceUUID service);
//    Promise<sequence<BluetoothGATTService>> getIncludedServices(optional BluetoothServiceUUID service);
}