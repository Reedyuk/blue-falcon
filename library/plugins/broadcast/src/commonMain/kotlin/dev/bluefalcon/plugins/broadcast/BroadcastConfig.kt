package dev.bluefalcon.plugins.broadcast

import dev.bluefalcon.peripheral.CharacteristicProperty

/**
 * Configuration for [DeviceBroadcastPlugin].
 *
 * @property defaultCharacteristicProperties
 *     GATT properties applied to every hosted characteristic.
 *     Defaults to READ + WRITE + WRITE_NO_RESPONSE + NOTIFY, which suits most broadcast scenarios.
 *     NOTIFY is also auto-added for any characteristic that had `isNotifying = true` at capture
 *     time, or that carries a CCCD descriptor (UUID 0x2902).
 *
 * @property overrideLocalName
 *     Advertised device name. When null (default) the name from the clone is used
 *     (`advertisement.localName` falling back to `peripheralName`).
 *
 * @property includeManufacturerData
 *     Whether to include manufacturer-specific data in the advertisement packet.
 *     The clone's raw manufacturer data bytes are parsed as `[companyId (2 LE bytes)] + payload`.
 *     Defaults to true. Note: iOS silently ignores manufacturer data in the advertisement packet.
 *
 * @property includeTxPower
 *     Whether to request TX power level in the advertisement (Android / macOS). Defaults to false.
 */
data class BroadcastConfig(
    val defaultCharacteristicProperties: Set<CharacteristicProperty> = setOf(
        CharacteristicProperty.READ,
        CharacteristicProperty.WRITE,
        CharacteristicProperty.WRITE_NO_RESPONSE,
        CharacteristicProperty.NOTIFY
    ),
    val overrideLocalName: String? = null,
    val includeManufacturerData: Boolean = true,
    val includeTxPower: Boolean = false
)
