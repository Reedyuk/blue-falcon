package dev.bluefalcon.plugins.clone

import kotlinx.serialization.Serializable

/**
 * A complete snapshot of a BLE peripheral device including advertisement data
 * and the full GATT table (services, characteristics, descriptors).
 */
@Serializable
data class DeviceClone(
    /** Platform-specific peripheral identifier (MAC address on Android, UUID on iOS) */
    val peripheralId: String,
    /** Advertised device name, if available */
    val peripheralName: String?,
    /** ISO-8601 timestamp of when the clone was captured */
    val capturedAt: String,
    /** Platform on which the clone was captured (e.g., "Android", "iOS", "macOS") */
    val platform: String,
    /** RSSI at time of capture in dBm */
    val rssi: Float? = null,
    /** Negotiated MTU size at time of capture */
    val mtuSize: Int? = null,
    /** Advertisement data captured during scan/discovery */
    val advertisement: AdvertisementClone = AdvertisementClone(),
    /** All discovered GATT services with their characteristics and descriptors */
    val services: List<ServiceClone> = emptyList()
)

/**
 * Snapshot of BLE advertisement data.
 */
@Serializable
data class AdvertisementClone(
    /** Local name from advertisement packet */
    val localName: String? = null,
    /** Raw manufacturer-specific data bytes (Base64-encoded in JSON) */
    val manufacturerData: ByteArray? = null,
    /** Service UUIDs advertised in scan response */
    val serviceUuids: List<String> = emptyList(),
    /** TX Power Level from advertisement packet */
    val txPowerLevel: Int? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AdvertisementClone) return false
        return localName == other.localName &&
            (manufacturerData?.contentEquals(other.manufacturerData) ?: (other.manufacturerData == null)) &&
            serviceUuids == other.serviceUuids &&
            txPowerLevel == other.txPowerLevel
    }

    override fun hashCode(): Int {
        var result = localName?.hashCode() ?: 0
        result = 31 * result + (manufacturerData?.contentHashCode() ?: 0)
        result = 31 * result + serviceUuids.hashCode()
        result = 31 * result + (txPowerLevel ?: 0)
        return result
    }
}

/**
 * Snapshot of a GATT service.
 */
@Serializable
data class ServiceClone(
    /** Service UUID as string */
    val uuid: String,
    /** Human-readable service name, if known */
    val name: String? = null,
    /** Characteristics within this service */
    val characteristics: List<CharacteristicClone> = emptyList()
)

/**
 * Snapshot of a GATT characteristic.
 */
@Serializable
data class CharacteristicClone(
    /** Characteristic UUID as string */
    val uuid: String,
    /** Human-readable characteristic name, if known */
    val name: String? = null,
    /** Current value at time of capture (Base64-encoded in JSON) */
    val value: ByteArray? = null,
    /** Whether notifications were enabled at time of capture */
    val isNotifying: Boolean = false,
    /** Descriptors for this characteristic */
    val descriptors: List<DescriptorClone> = emptyList()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CharacteristicClone) return false
        return uuid == other.uuid &&
            name == other.name &&
            (value?.contentEquals(other.value) ?: (other.value == null)) &&
            isNotifying == other.isNotifying &&
            descriptors == other.descriptors
    }

    override fun hashCode(): Int {
        var result = uuid.hashCode()
        result = 31 * result + (name?.hashCode() ?: 0)
        result = 31 * result + (value?.contentHashCode() ?: 0)
        result = 31 * result + isNotifying.hashCode()
        result = 31 * result + descriptors.hashCode()
        return result
    }
}

/**
 * Snapshot of a GATT characteristic descriptor.
 */
@Serializable
data class DescriptorClone(
    /** Descriptor UUID as string */
    val uuid: String,
    /** Descriptor value at time of capture (Base64-encoded in JSON) */
    val value: ByteArray? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DescriptorClone) return false
        return uuid == other.uuid &&
            (value?.contentEquals(other.value) ?: (other.value == null))
    }

    override fun hashCode(): Int {
        var result = uuid.hashCode()
        result = 31 * result + (value?.contentHashCode() ?: 0)
        return result
    }
}
