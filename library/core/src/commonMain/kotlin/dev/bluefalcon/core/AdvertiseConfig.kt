package dev.bluefalcon.core

/**
 * Configuration passed to [BluetoothAdvertiser.startAdvertising].
 *
 * Describes both the advertisement packet and the GATT service tree to host.
 *
 * Platform notes:
 * - Android: all fields are supported.
 * - iOS (foreground): only [localName] and [serviceUuids] are included in the
 *   advertisement packet; [manufacturerData] is silently dropped by the OS.
 * - iOS (background): only [serviceUuids] are included.
 * - macOS: all fields are supported.
 * - Windows: advertisement-only (no GATT server); [services] are ignored.
 *
 * @property localName        Advertised device name, or null to omit.
 * @property serviceUuids     Service UUIDs to include in the advertisement packet.
 * @property manufacturerData Map of manufacturer ID (company ID) to raw payload bytes.
 * @property services         GATT services to host on the local server.
 * @property includeTxPower   Whether to include TX power level in the advertisement (Android/macOS).
 */
data class AdvertiseConfig(
    val localName: String? = null,
    val serviceUuids: List<String> = emptyList(),
    val manufacturerData: Map<Int, ByteArray> = emptyMap(),
    val services: List<GattServiceConfig> = emptyList(),
    val includeTxPower: Boolean = false
)
