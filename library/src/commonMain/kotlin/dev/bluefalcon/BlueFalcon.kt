package dev.bluefalcon

expect class BlueFalcon {
    fun connect(bluetoothPeripheral: BluetoothPeripheral)
    fun disconnect(bluetoothPeripheral: BluetoothPeripheral)
    fun scan()
}
