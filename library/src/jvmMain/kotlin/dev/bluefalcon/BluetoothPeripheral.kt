package dev.bluefalcon

actual class BluetoothPeripheral {
    actual val name: String?
        get() = TODO("Not yet implemented")
    actual val uuid: String
        get() = TODO("Not yet implemented")
    actual var rssi: Float?
        get() = TODO("Not yet implemented")
        set(value) {}
    actual val services: List<BluetoothService>
        get() = TODO("Not yet implemented")
}