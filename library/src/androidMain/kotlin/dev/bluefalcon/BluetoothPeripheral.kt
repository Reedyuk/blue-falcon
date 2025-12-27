package dev.bluefalcon

import android.bluetooth.BluetoothDevice
import android.os.Parcel
import android.os.Parcelable
import kotlinx.coroutines.flow.MutableStateFlow

actual class BluetoothPeripheralImpl actual constructor(actual override val device: NativeBluetoothDevice) :
    BluetoothPeripheral, Parcelable {
    actual override val name: String?
        get() = device.name ?: device.address
    actual override val services: Map<Uuid, BluetoothService>
        get() = _servicesFlow.value.associateBy { it.uuid }
    actual override val uuid: String
        get() = device.address

    actual override var rssi: Float? = null
    actual override var mtuSize: Int? = null

    actual override val _servicesFlow = MutableStateFlow<List<BluetoothService>>(emptyList())

    constructor(parcel: Parcel) : this(requireNotNull(parcel.readParcelable(BluetoothDevice::class.java.classLoader))) {
        rssi = parcel.readValue(Float::class.java.classLoader) as? Float
    }

    override fun toString(): String = uuid

    override fun hashCode(): Int = uuid.hashCode()

    override fun equals(other: Any?): Boolean {
        if (other == null || other !is BluetoothPeripheralImpl) return false
        return other.uuid == uuid
    }

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeParcelable(device, flags)
        parcel.writeValue(rssi)
    }

    override fun describeContents(): Int {
        return 0
    }

    companion object CREATOR : Parcelable.Creator<BluetoothPeripheralImpl> {
        override fun createFromParcel(parcel: Parcel): BluetoothPeripheralImpl {
            return BluetoothPeripheralImpl(parcel)
        }

        override fun newArray(size: Int): Array<BluetoothPeripheralImpl?> {
            return arrayOfNulls(size)
        }
    }

    actual override val characteristics: Map<Uuid, List<BluetoothCharacteristic>>
        get() = services.values
            .flatMap { service -> service.characteristics }
            .groupBy { characteristic -> characteristic.uuid } // Group by characteristic UUID
            .mapValues { entry -> entry.value }
}