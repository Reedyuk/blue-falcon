package dev.bluefalcon.peripheral

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
 * @property advertiseMode    Android only: advertising interval (`AdvertiseSettings.setAdvertiseMode`).
 *   Default [AdvertiseMode.Balanced], the previous hardcoded value. Ignored on Apple platforms,
 *   where Core Bluetooth chooses the interval.
 * @property txPowerLevel     Android only: transmit power (`AdvertiseSettings.setTxPowerLevel`).
 *   Default [AdvertiseTxPower.Medium], the previous hardcoded value. Ignored on Apple platforms.
 */
data class AdvertiseConfig(
    val localName: String? = null,
    val serviceUuids: List<String> = emptyList(),
    val manufacturerData: Map<Int, ByteArray> = emptyMap(),
    val services: List<GattServiceConfig> = emptyList(),
    val includeTxPower: Boolean = false,
    val advertiseMode: AdvertiseMode = AdvertiseMode.Balanced,
    val txPowerLevel: AdvertiseTxPower = AdvertiseTxPower.Medium,
) {
    /** Keeps the previous JVM constructor signature for callers compiled against older releases. */
    @Deprecated("Binary compatibility only", level = DeprecationLevel.HIDDEN)
    constructor(
        localName: String?,
        serviceUuids: List<String>,
        manufacturerData: Map<Int, ByteArray>,
        services: List<GattServiceConfig>,
        includeTxPower: Boolean,
    ) : this(
        localName = localName,
        serviceUuids = serviceUuids,
        manufacturerData = manufacturerData,
        services = services,
        includeTxPower = includeTxPower,
        advertiseMode = AdvertiseMode.Balanced,
        txPowerLevel = AdvertiseTxPower.Medium,
    )
}

/** Advertising interval, mapped to `AdvertiseSettings.ADVERTISE_MODE_*` on Android. */
enum class AdvertiseMode {
    /** About 1 s interval; lowest power. Recommended in the background. */
    LowPower,

    /** About 250 ms interval. */
    Balanced,

    /** About 100 ms interval; fastest discovery, highest power. */
    LowLatency,
}

/** Transmit power, mapped to `AdvertiseSettings.ADVERTISE_TX_POWER_*` on Android. */
enum class AdvertiseTxPower {
    UltraLow,
    Low,
    Medium,
    High,
}
