package dev.bluefalcon

import kotlinx.coroutines.flow.MutableStateFlow
import platform.CoreBluetooth.CBPeripheral
import platform.CoreBluetooth.CBService

actual class BluetoothPeripheralImpl(actual override val device: CBPeripheral, val rssiValue: Float?): BluetoothPeripheral {
    actual constructor(device: NativeBluetoothDevice): this(device, null)

    actual override val name: String? get() = device.name
    actual override var rssi: Float? = rssiValue
    actual override var mtuSize: Int? = null
    actual override val uuid: String = device.identifier.UUIDString

    actual override val _servicesFlow = MutableStateFlow<List<BluetoothService>>(emptyList())

    override fun toString(): String = uuid

    override fun hashCode(): Int = uuid.hashCode()
    override fun equals(other: Any?): Boolean {
        if (other == null || other !is BluetoothPeripheralImpl) return false
        return other.uuid == uuid
    }

    actual override val services: Map<Uuid, BluetoothService>
        get() = device.services
            ?.filterIsInstance<CBService>()
            ?.map { service -> BluetoothService(service) }
            ?.associateBy { it.uuid }
            ?: emptyMap()

    actual override val characteristics: Map<Uuid, List<BluetoothCharacteristic>>
        get() = services.values
            .flatMap { service -> service.characteristics }
            .groupBy { characteristic -> characteristic.uuid } // Group by characteristic UUID
            .mapValues { entry -> entry.value }
}