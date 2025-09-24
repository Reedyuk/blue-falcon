package dev.bluefalcon.external

import org.khronos.webgl.DataView
import org.w3c.dom.events.EventTarget
import kotlin.js.Promise

abstract external class BluetoothRemoteGATTCharacteristic : EventTarget {
    val uuid: String
    val value: DataView?

//    fun getDescriptor(descriptor: BluetoothDescriptorUUID): Promise<BluetoothRemoteGATTDescriptor>
//    fun getDescriptors(): Promise<Array<BluetoothRemoteGATTDescriptor>>

    fun readValue(): Promise<DataView>
    fun writeValue(value: BufferSource): Promise<Unit>

    fun startNotifications(): Promise<BluetoothRemoteGATTCharacteristic>
    fun stopNotifications(): Promise<BluetoothRemoteGATTCharacteristic>
}

internal typealias BufferSource = ByteArray