package dev.bluefalcon.core

/**
 * Configuration for a local GATT service to be hosted by [BluetoothAdvertiser].
 *
 * @property uuid       128-bit service UUID string.
 * @property characteristics Characteristics to expose on this service.
 */
data class GattServiceConfig(
    val uuid: String,
    val characteristics: List<GattCharacteristicConfig> = emptyList()
)

/**
 * Configuration for a single GATT characteristic within a [GattServiceConfig].
 *
 * @property uuid         128-bit characteristic UUID string.
 * @property properties   Allowed operations (read, write, notify, etc.).
 * @property permissions  Raw platform permission bitmask; 0 means derive from [properties].
 * @property initialValue Initial byte-array value returned on read, or null for an empty value.
 * @property descriptors  Descriptors to include (e.g. CCCD for notify/indicate).
 */
data class GattCharacteristicConfig(
    val uuid: String,
    val properties: Set<CharacteristicProperty>,
    val permissions: Int = 0,
    val initialValue: ByteArray? = null,
    val descriptors: List<GattDescriptorConfig> = emptyList()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GattCharacteristicConfig) return false
        return uuid == other.uuid &&
                properties == other.properties &&
                permissions == other.permissions &&
                (initialValue?.contentEquals(other.initialValue ?: return false) ?: (other.initialValue == null)) &&
                descriptors == other.descriptors
    }

    override fun hashCode(): Int {
        var result = uuid.hashCode()
        result = 31 * result + properties.hashCode()
        result = 31 * result + permissions
        result = 31 * result + (initialValue?.contentHashCode() ?: 0)
        result = 31 * result + descriptors.hashCode()
        return result
    }
}

/**
 * Configuration for a GATT descriptor attached to a [GattCharacteristicConfig].
 *
 * @property uuid         128-bit descriptor UUID string.
 * @property permissions  Raw platform permission bitmask.
 * @property initialValue Initial value, or null.
 */
data class GattDescriptorConfig(
    val uuid: String,
    val permissions: Int = 0,
    val initialValue: ByteArray? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GattDescriptorConfig) return false
        return uuid == other.uuid &&
                permissions == other.permissions &&
                (initialValue?.contentEquals(other.initialValue ?: return false) ?: (other.initialValue == null))
    }

    override fun hashCode(): Int {
        var result = uuid.hashCode()
        result = 31 * result + permissions
        result = 31 * result + (initialValue?.contentHashCode() ?: 0)
        return result
    }
}
