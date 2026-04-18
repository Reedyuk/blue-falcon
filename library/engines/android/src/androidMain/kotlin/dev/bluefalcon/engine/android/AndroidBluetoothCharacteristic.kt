package dev.bluefalcon.engine.android

import android.bluetooth.BluetoothGattCharacteristic
import android.os.Parcel
import android.os.Parcelable
import dev.bluefalcon.core.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlin.uuid.toKotlinUuid
import java.util.WeakHashMap

/**
 * Android implementation of BluetoothCharacteristic.
 * Wraps Android's BluetoothGattCharacteristic.
 */
class AndroidBluetoothCharacteristic(val characteristic: BluetoothGattCharacteristic) : 
    BluetoothCharacteristic, Parcelable {
    
    override val name: String?
        get() = characteristic.uuid.toString()
    
    override val value: ByteArray?
        get() = characteristic.value

    override val notifications: SharedFlow<ByteArray>
        get() = notificationFlowFor(characteristic).asSharedFlow()
    
    override val descriptors: List<BluetoothCharacteristicDescriptor>
        get() = characteristic.descriptors.map { AndroidBluetoothCharacteristicDescriptor(it) }
    
    override val service: BluetoothService?
        get() = AndroidBluetoothService(characteristic.service)
    
    override val uuid: Uuid
        get() = characteristic.uuid.toKotlinUuid()
    
    override val isNotifying: Boolean
        get() = (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 
                BluetoothGattCharacteristic.PROPERTY_NOTIFY
    
    internal val _descriptorsFlow = MutableStateFlow<List<BluetoothCharacteristicDescriptor>>(emptyList())

    internal fun emitNotification(value: ByteArray) {
        notificationFlowFor(characteristic).tryEmit(value.copyOf())
    }
    
    constructor(parcel: Parcel) : this(
        requireNotNull(parcel.readParcelable(BluetoothGattCharacteristic::class.java.classLoader))
    )
    
    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeParcelable(characteristic, flags)
    }
    
    override fun describeContents(): Int = 0
    
    override fun equals(other: Any?): Boolean =
        if (this === other) true
        else if (other !is AndroidBluetoothCharacteristic) false
        else characteristic.uuid == other.characteristic.uuid &&
                characteristic.service.uuid == other.characteristic.service.uuid
    
    override fun hashCode(): Int =
        31 * characteristic.uuid.hashCode() + characteristic.service.uuid.hashCode()
    
    companion object CREATOR : Parcelable.Creator<AndroidBluetoothCharacteristic> {
        private val notificationFlows = java.util.Collections.synchronizedMap(WeakHashMap<BluetoothGattCharacteristic, MutableSharedFlow<ByteArray>>())

        private fun notificationFlowFor(characteristic: BluetoothGattCharacteristic): MutableSharedFlow<ByteArray> =
            synchronized(notificationFlows) {
                notificationFlows.getOrPut(characteristic) {
                    MutableSharedFlow(extraBufferCapacity = 64)
                }
            }

        override fun createFromParcel(parcel: Parcel): AndroidBluetoothCharacteristic {
            return AndroidBluetoothCharacteristic(parcel)
        }
        
        override fun newArray(size: Int): Array<AndroidBluetoothCharacteristic?> {
            return arrayOfNulls(size)
        }
    }
}
