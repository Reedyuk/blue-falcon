package dev.bluefalcon

import android.bluetooth.BluetoothDevice
import android.os.Parcel
import android.os.Parcelable
import kotlinx.coroutines.flow.MutableStateFlow

actual class BluetoothPeripheral(val bluetoothDevice: BluetoothDevice) : Parcelable {
    actual val name: String?
        get() = bluetoothDevice.name ?: bluetoothDevice.address
    actual val services: Map<String, BluetoothService>
        get() = _servicesFlow.value.associateBy { it.uuid }
    actual val uuid: String
        get() = bluetoothDevice.address

    actual var rssi: Float? = null
    actual var mtuSize: Int? = null

    internal actual val _servicesFlow = MutableStateFlow<List<BluetoothService>>(emptyList())

    constructor(parcel: Parcel) : this(requireNotNull(parcel.readParcelable(BluetoothDevice::class.java.classLoader))) {
        rssi = parcel.readValue(Float::class.java.classLoader) as? Float
    }

    override fun toString(): String = uuid

    override fun hashCode(): Int = uuid.hashCode()

    override fun equals(other: Any?): Boolean {
        if (other == null || other !is BluetoothPeripheral) return false
        return other.uuid == uuid
    }

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeParcelable(bluetoothDevice, flags)
        parcel.writeValue(rssi)
    }

    override fun describeContents(): Int {
        return 0
    }

    companion object CREATOR : Parcelable.Creator<BluetoothPeripheral> {
        override fun createFromParcel(parcel: Parcel): BluetoothPeripheral {
            return BluetoothPeripheral(parcel)
        }

        override fun newArray(size: Int): Array<BluetoothPeripheral?> {
            return arrayOfNulls(size)
        }
    }

    actual val characteristics: Map<String, BluetoothCharacteristic>
        get() = services.values
            .flatMap { it.characteristics }
            .associateBy { it.uuid }
}