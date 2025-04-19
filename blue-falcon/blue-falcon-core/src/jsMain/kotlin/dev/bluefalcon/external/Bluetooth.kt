package dev.bluefalcon.external

import org.w3c.dom.events.EventTarget
import kotlin.js.Promise

external class Bluetooth: EventTarget {
    val referringDevice: BluetoothDevice?
    fun getDevices(): Promise<Array<BluetoothDevice>>
    fun requestDevice(options: BluetoothOptions?): Promise<BluetoothDevice>
}
