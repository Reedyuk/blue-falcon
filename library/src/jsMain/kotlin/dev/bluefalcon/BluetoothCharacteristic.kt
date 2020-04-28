package dev.bluefalcon

actual class BluetoothCharacteristic {
    actual val name: String?
        get() = TODO("not implemented") //To change initializer of created properties use File | Settings | File Templates.
    actual val value: ByteArray?
        get() = TODO("not implemented") //To change initializer of created properties use File | Settings | File Templates.
    actual val descriptors: List<BluetoothCharacteristicDescriptor>
        get() = TODO("not implemented")
}

actual class BluetoothCharacteristicDescriptor