package dev.bluefalcon.engine.android

import android.bluetooth.BluetoothDevice
import android.os.Parcel
import android.os.Parcelable
import dev.bluefalcon.core.*
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Android implementation of BluetoothPeripheral.
 * Wraps Android's BluetoothDevice and provides access to BLE services and characteristics.
 */
class AndroidBluetoothPeripheral(val device: BluetoothDevice) : BluetoothPeripheral, Parcelable {
    
    override val name: String?
        get() = device.name ?: device.address
    
    override val services: List<BluetoothService>
        get() = _servicesFlow.value
    
    override val uuid: String
        get() = device.address
    
    override var rssi: Float? = null
    override var mtuSize: Int? = null
    
    internal val _servicesFlow = MutableStateFlow<List<BluetoothService>>(emptyList())
    
    override val characteristics: List<BluetoothCharacteristic>
        get() = services.flatMap { it.characteristics }
    
    constructor(parcel: Parcel) : this(requireNotNull(parcel.readParcelable(BluetoothDevice::class.java.classLoader))) {
        rssi = parcel.readValue(Float::class.java.classLoader) as? Float
    }
    
    override fun toString(): String = uuid
    
    override fun hashCode(): Int = uuid.hashCode()
    
    override fun equals(other: Any?): Boolean {
        if (other == null || other !is AndroidBluetoothPeripheral) return false
        return other.uuid == uuid
    }
    
    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeParcelable(device, flags)
        parcel.writeValue(rssi)
    }
    
    override fun describeContents(): Int = 0
    
    companion object CREATOR : Parcelable.Creator<AndroidBluetoothPeripheral> {
        override fun createFromParcel(parcel: Parcel): AndroidBluetoothPeripheral {
            return AndroidBluetoothPeripheral(parcel)
        }
        
        override fun newArray(size: Int): Array<AndroidBluetoothPeripheral?> {
            return arrayOfNulls(size)
        }
    }
}
