package dev.bluefalcon.engine.apple

import dev.bluefalcon.core.*
import kotlinx.cinterop.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import platform.CoreBluetooth.*
import platform.Foundation.NSData
import platform.posix.memcpy

/**
 * Apple implementation of BluetoothPeripheral
 */
class AppleBluetoothPeripheral(
    val cbPeripheral: CBPeripheral,
    override var rssi: Float? = null
) : BluetoothPeripheral {
    
    override val name: String? get() = cbPeripheral.name
    override val uuid: String = cbPeripheral.identifier.UUIDString
    override var mtuSize: Int? = null
    
    override val services: List<BluetoothService>
        get() = cbPeripheral.services
            ?.mapNotNull { it as? CBService }
            ?.map { AppleBluetoothService(it) }
            ?: emptyList()
    
    override val characteristics: List<BluetoothCharacteristic>
        get() = services.flatMap { it.characteristics }
    
    override fun toString(): String = uuid
    
    override fun hashCode(): Int = uuid.hashCode()
    
    override fun equals(other: Any?): Boolean {
        if (other == null || other !is AppleBluetoothPeripheral) return false
        return other.uuid == uuid
    }
}

/**
 * Apple implementation of BluetoothService
 */
class AppleBluetoothService(
    val cbService: CBService
) : BluetoothService {
    
    override val uuid: Uuid = cbService.UUID.UUIDString.toUuid()
    override val name: String? get() = cbService.UUID.UUIDString
    
    override val characteristics: List<BluetoothCharacteristic>
        get() = cbService.characteristics
            ?.mapNotNull { it as? CBCharacteristic }
            ?.map { AppleBluetoothCharacteristic(it, this) }
            ?: emptyList()
}

/**
 * Apple implementation of BluetoothCharacteristic
 */
@OptIn(ExperimentalForeignApi::class)
class AppleBluetoothCharacteristic(
    val cbCharacteristic: CBCharacteristic,
    override val service: BluetoothService?
) : BluetoothCharacteristic {
    
    override val uuid: Uuid = cbCharacteristic.UUID.UUIDString.toUuid()
    override val name: String? get() = cbCharacteristic.UUID.UUIDString
    
    override val value: ByteArray?
        get() = cbCharacteristic.value?.let { data ->
            ByteArray(data.length.toInt()).apply {
                usePinned {
                    memcpy(it.addressOf(0), data.bytes, data.length)
                }
            }
        }

    override val notifications: SharedFlow<ByteArray>
        get() = NotificationFlowStore.flowFor(cbCharacteristic).asSharedFlow()
    
    override val descriptors: List<BluetoothCharacteristicDescriptor>
        get() = cbCharacteristic.descriptors
            ?.mapNotNull { it as? CBDescriptor }
            ?.map { AppleBluetoothCharacteristicDescriptor(it, this) }
            ?: emptyList()
    
    override val isNotifying: Boolean
        get() = cbCharacteristic.isNotifying

    internal fun emitNotification(value: ByteArray) {
        NotificationFlowStore.emit(cbCharacteristic, value)
    }

    private object NotificationFlowStore {
        private val flows = mutableMapOf<String, MutableSharedFlow<ByteArray>>()

        fun flowFor(characteristic: CBCharacteristic): MutableSharedFlow<ByteArray> =
            flows.getOrPut(notificationKey(characteristic)) {
                MutableSharedFlow(extraBufferCapacity = 64)
            }

        fun emit(characteristic: CBCharacteristic, value: ByteArray) {
            flowFor(characteristic).tryEmit(value.copyOf())
        }

        private fun notificationKey(characteristic: CBCharacteristic): String {
            val peripheralId = characteristic.service?.peripheral?.identifier?.UUIDString ?: "unknown"
            val serviceId = characteristic.service?.UUID?.UUIDString ?: "unknown"
            val characteristicId = characteristic.UUID.UUIDString
            return "$peripheralId/$serviceId/$characteristicId"
        }
    }
}

/**
 * Apple implementation of BluetoothCharacteristicDescriptor
 */
class AppleBluetoothCharacteristicDescriptor(
    val cbDescriptor: CBDescriptor,
    override val characteristic: BluetoothCharacteristic?
) : BluetoothCharacteristicDescriptor {
    
    override val uuid: Uuid = cbDescriptor.UUID.UUIDString.toUuid()
    
    override val value: ByteArray?
        get() = (cbDescriptor.value as? NSData)?.toByteArray()
}

/**
 * Convert CBUUID to Uuid
 */
fun CBUUID.toUuid(): Uuid {
    return this.UUIDString.toUuid()
}
