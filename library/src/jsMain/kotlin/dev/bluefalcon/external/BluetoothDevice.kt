package dev.bluefalcon.external

import org.w3c.dom.events.EventTarget
import kotlin.js.Promise

//https://developer.mozilla.org/en-US/docs/Web/API/BluetoothDevice
abstract external class BluetoothDevice : EventTarget {
    val id: String
    val name: String?
    val gatt: BluetoothRemoteGATTServer?

    fun watchAdvertisements(): Promise<Unit>
    fun unwatchAdvertisements(): Promise<Unit>
    val watchingAdvertisements: Boolean
}


