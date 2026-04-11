package dev.bluefalcon.core.mocks

import dev.bluefalcon.core.*

/**
 * Fake implementation of BluetoothPeripheral for testing
 */
data class FakePeripheral(
    override val name: String?,
    override val uuid: String = "fake-uuid-${name?.hashCode() ?: 0}",
    override val rssi: Float? = -50f,
    override val mtuSize: Int? = 23,
    override val services: List<BluetoothService> = emptyList(),
    override val characteristics: List<BluetoothCharacteristic> = emptyList()
) : BluetoothPeripheral

/**
 * Fake implementation of BluetoothService for testing
 */
data class FakeService(
    override val uuid: Uuid,
    override val name: String? = null,
    override val characteristics: List<BluetoothCharacteristic> = emptyList()
) : BluetoothService

/**
 * Fake implementation of BluetoothCharacteristic for testing
 */
data class FakeCharacteristic(
    override val uuid: Uuid,
    override val name: String? = null,
    override var value: ByteArray? = null,
    override val descriptors: List<BluetoothCharacteristicDescriptor> = emptyList(),
    override val isNotifying: Boolean = false,
    override val service: BluetoothService? = null
) : BluetoothCharacteristic {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        
        other as FakeCharacteristic
        
        if (uuid != other.uuid) return false
        if (name != other.name) return false
        if (value != null) {
            if (other.value == null) return false
            if (!value.contentEquals(other.value)) return false
        } else if (other.value != null) return false
        if (isNotifying != other.isNotifying) return false
        
        return true
    }
    
    override fun hashCode(): Int {
        var result = uuid.hashCode()
        result = 31 * result + (name?.hashCode() ?: 0)
        result = 31 * result + (value?.contentHashCode() ?: 0)
        result = 31 * result + isNotifying.hashCode()
        return result
    }
}

/**
 * Fake implementation of BluetoothCharacteristicDescriptor for testing
 */
data class FakeDescriptor(
    override val uuid: Uuid,
    override val name: String? = null,
    override var value: ByteArray? = null,
    override val characteristic: BluetoothCharacteristic? = null
) : BluetoothCharacteristicDescriptor {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        
        other as FakeDescriptor
        
        if (uuid != other.uuid) return false
        if (name != other.name) return false
        if (value != null) {
            if (other.value == null) return false
            if (!value.contentEquals(other.value)) return false
        } else if (other.value != null) return false
        
        return true
    }
    
    override fun hashCode(): Int {
        var result = uuid.hashCode()
        result = 31 * result + (name?.hashCode() ?: 0)
        result = 31 * result + (value?.contentHashCode() ?: 0)
        return result
    }
}
