package dev.bluefalcon

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.os.Parcel
import android.os.Parcelable
import kotlinx.coroutines.flow.MutableStateFlow

actual class BluetoothCharacteristic(val characteristic: BluetoothGattCharacteristic) : Parcelable {
    actual val name: String?
        get() = characteristic.uuid.toString()
    actual val value: ByteArray?
        get() = characteristic.value
    actual val descriptors: List<BluetoothCharacteristicDescriptor>
        get() = characteristic.descriptors

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


}

actual typealias BluetoothCharacteristicDescriptor = BluetoothGattDescriptor