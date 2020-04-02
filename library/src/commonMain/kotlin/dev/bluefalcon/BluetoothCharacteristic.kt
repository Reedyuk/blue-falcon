package dev.bluefalcon

expect class BluetoothCharacteristic {
    val name: String?
    val value: String?
    val descriptors: List<BluetoothCharacteristicDescriptor>
}

expect class BluetoothCharacteristicDescriptor