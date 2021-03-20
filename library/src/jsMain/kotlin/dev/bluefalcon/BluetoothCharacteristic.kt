package dev.bluefalcon

import dev.bluefalcon.external.BluetoothRemoteGATTCharacteristic
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array

actual class BluetoothCharacteristic(val characteristic: BluetoothRemoteGATTCharacteristic) {
    actual val name: String?
        get() = characteristic.uuid
    actual val value: ByteArray?
        get() = characteristic.value?.buffer.toByteArray()
            //characteristic.value.toString().encodeToByteArray()
    actual val descriptors: List<BluetoothCharacteristicDescriptor>
        get() = TODO("not implemented")

    val stringValue get() = value?.decodeToString()
}

actual class BluetoothCharacteristicDescriptor

fun ArrayBuffer?.toByteArray(): ByteArray? = this?.run { Int8Array(this).unsafeCast<ByteArray>() }