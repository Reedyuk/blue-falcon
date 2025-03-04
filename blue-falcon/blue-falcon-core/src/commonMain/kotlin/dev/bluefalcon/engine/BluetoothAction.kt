package dev.bluefalcon.engine

import dev.bluefalcon.ServiceFilter
import dev.bluefalcon.Uuid

sealed class BluetoothAction {
    data class Scan(val filters: ServiceFilter? = null) : BluetoothAction()
    data class Connect(val device: String) : BluetoothAction()
    data class Disconnect(val device: String) : BluetoothAction()
//    data class WriteCharacteristic(val device: BluetoothDevice, val characteristic: BluetoothCharacteristic) : BluetoothAction()
}