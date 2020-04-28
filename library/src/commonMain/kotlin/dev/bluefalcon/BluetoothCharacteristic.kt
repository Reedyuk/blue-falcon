package dev.bluefalcon

expect class BluetoothCharacteristic {
    val name: String?
    val value: ByteArray?
    val descriptors: List<BluetoothCharacteristicDescriptor>
}

expect class BluetoothCharacteristicDescriptor