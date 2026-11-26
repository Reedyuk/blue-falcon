package dev.bluefalcon.peripheral

/**
 * Configuration passed to [BluetoothAdvertiser.startAdvertising].
 *
 * Describes both the advertisement packet and the GATT service tree to host.
 *
 * Platform notes:
 * - Android: [localName] is **not** advertised. Android cannot set a custom local name per
 *   advertiser; the only name it can put on air is the system Bluetooth adapter name (for example
 *   "Alice's Pixel"). Blue Falcon sends that name only when [includeSystemDeviceName] is `true`.
 *   All other fields are supported.
 * - iOS and macOS: Core Bluetooth advertises only [localName] and [serviceUuids] (iOS in the
 *   background: only [serviceUuids]). The Apple peripheral manager fails `start` with
 *   [IllegalArgumentException] when [manufacturerData], [serviceData] or [includeTxPower] is set,
 *   instead of silently dropping them. [includeSystemDeviceName] is ignored.
 * - Windows: advertisement-only (no GATT server); [services] are ignored.
 *
 * @property localName        Advertised local name on Apple platforms, or null to omit. Ignored on
 *   Android (see [includeSystemDeviceName]).
 * @property serviceUuids     Service UUIDs to include in the advertisement packet.
 * @property manufacturerData Map of manufacturer ID (company ID) to raw payload bytes.
 * @property services         GATT services to host on the local server.
 * @property includeTxPower   Whether to include TX power level in the advertisement (Android/macOS).
 * @property includeSystemDeviceName Android only: whether to put the system Bluetooth adapter name
 *   in the scan response. Defaults to `false` because the adapter name usually identifies the user
 *   or their device. Has no effect on Apple platforms, where Core Bluetooth advertises only
 *   [localName].
 * @property serviceData      Map of service UUID to service data payload. **Android only** for
 *   advertising: Core Bluetooth does not support `CBAdvertisementDataServiceDataKey` when
 *   advertising, so Apple platforms reject a non-empty map at start. Keys use the same UUID
 *   format as [serviceUuids].
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
    val includeSystemDeviceName: Boolean = false,
    val serviceData: Map<String, ByteArray> = emptyMap(),
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
        includeSystemDeviceName = false,
        serviceData = emptyMap(),
        advertiseMode = AdvertiseMode.Balanced,
        txPowerLevel = AdvertiseTxPower.Medium,
    )

    /** Keeps the constructor signature from before advertise settings were added (#332). */
    @Deprecated("Binary compatibility only", level = DeprecationLevel.HIDDEN)
    constructor(
        localName: String?,
        serviceUuids: List<String>,
        manufacturerData: Map<Int, ByteArray>,
        services: List<GattServiceConfig>,
        includeTxPower: Boolean,
        includeSystemDeviceName: Boolean,
        serviceData: Map<String, ByteArray>,
    ) : this(
        localName = localName,
        serviceUuids = serviceUuids,
        manufacturerData = manufacturerData,
        services = services,
        includeTxPower = includeTxPower,
        includeSystemDeviceName = includeSystemDeviceName,
        serviceData = serviceData,
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
