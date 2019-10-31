package dev.bluefalcon

import platform.CoreBluetooth.CBPeripheral
import platform.CoreBluetooth.CBService

actual class BluetoothPeripheral(val bluetoothDevice: CBPeripheral, val rssiValue: Float?) {
    actual val name: String? = bluetoothDevice.name
    actual val rssi: Float? = rssiValue
    actual val services: List<BluetoothService>
        get() = bluetoothDevice.services?.map {
            BluetoothService(it as CBService)
        } ?: emptyList()
    actual val uuid: String
        get() = bluetoothDevice.identifier.UUIDString

}