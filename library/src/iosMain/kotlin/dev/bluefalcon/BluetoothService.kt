package dev.bluefalcon

import platform.CoreBluetooth.CBService

actual class BluetoothService(val service: CBService) {
    actual val name: String?
        get() = service.UUID.UUIDString
}