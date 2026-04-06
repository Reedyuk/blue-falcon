package dev.bluefalcon

import com.monkopedia.sdbus.ObjectPath
import kotlinx.coroutines.flow.MutableStateFlow

actual class NativeBluetoothDevice(val objectPath: ObjectPath) {
    val address: String
        get() {
            // Path is like /org/bluez/hci0/dev_40_4C_CA_42_4F_EA
            val devPart = objectPath.value.substringAfterLast("/dev_", "")
            return devPart.replace("_", ":").ifEmpty { objectPath.value }
        }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NativeBluetoothDevice) return false
        return objectPath == other.objectPath
    }

    override fun hashCode(): Int = objectPath.hashCode()
    override fun toString(): String = address
}

actual class BluetoothPeripheralImpl actual constructor(
    actual override val device: NativeBluetoothDevice
) : BluetoothPeripheral {

    internal var _name: String? = null

    actual override val name: String?
        get() = _name ?: device.address

    actual override val uuid: String
        get() = device.address

    actual override var rssi: Float? = null
    actual override var mtuSize: Int? = null

    actual override val _servicesFlow = MutableStateFlow<List<BluetoothService>>(emptyList())

    actual override val services: Map<Uuid, BluetoothService>
        get() = _servicesFlow.value.associateBy { it.uuid }

    actual override val characteristics: Map<Uuid, List<BluetoothCharacteristic>>
        get() = services.values
            .flatMap { it.characteristics }
            .groupBy { it.uuid }

    override fun toString(): String = uuid
    override fun hashCode(): Int = uuid.hashCode()
    override fun equals(other: Any?): Boolean {
        if (other == null || other !is BluetoothPeripheralImpl) return false
        return other.uuid == uuid
    }
}
