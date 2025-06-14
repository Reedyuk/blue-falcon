package dev.bluefalcon

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.os.Parcel
import android.os.Parcelable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.uuid.toKotlinUuid

actual class BluetoothCharacteristic(val characteristic: BluetoothGattCharacteristic) : Parcelable {
    actual val name: String?
        get() = characteristic.uuid.toString()
    actual val value: ByteArray?
        get() = characteristic.value
    actual val descriptors: List<BluetoothCharacteristicDescriptor>
        get() = characteristic.descriptors
    actual val service: BluetoothService? get() = BluetoothService(characteristic.service)

    internal actual val _descriptorsFlow = MutableStateFlow<List<BluetoothCharacteristicDescriptor>>(emptyList())

    constructor(parcel: Parcel) : this(
        requireNotNull(parcel.readParcelable(BluetoothGattCharacteristic::class.java.classLoader))
    ) {
    }

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeParcelable(characteristic, flags)
    }

    override fun describeContents(): Int {
        return 0
    }

    companion object CREATOR : Parcelable.Creator<BluetoothCharacteristic> {
        override fun createFromParcel(parcel: Parcel): BluetoothCharacteristic {
            return BluetoothCharacteristic(parcel)
        }

        override fun newArray(size: Int): Array<BluetoothCharacteristic?> {
            return arrayOfNulls(size)
        }
    }

    override fun equals(other: Any?): Boolean =
        if (this === other) true
        else if (other !is BluetoothCharacteristic) false
        else characteristic.uuid == other.characteristic.uuid &&
                characteristic.service.uuid == other.characteristic.service.uuid

    override fun hashCode(): Int =
        31 * characteristic.uuid.hashCode() + characteristic.service.uuid.hashCode()

    actual val uuid: Uuid
        get() = characteristic.uuid.toKotlinUuid()

    actual val isNotifying: Boolean
        get() = (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) == BluetoothGattCharacteristic.PROPERTY_NOTIFY
}

actual typealias BluetoothCharacteristicDescriptor = BluetoothGattDescriptor