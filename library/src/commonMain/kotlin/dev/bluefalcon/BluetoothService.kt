package dev.bluefalcon

expect class BluetoothService {
    val name: String?
    val characteristics: List<BluetoothCharacteristic>
}