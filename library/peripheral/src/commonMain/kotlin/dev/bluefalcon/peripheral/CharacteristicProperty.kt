package dev.bluefalcon.peripheral

/**
 * GATT characteristic properties used when building a local GATT server.
 *
 * Maps to platform equivalents:
 *   Android – BluetoothGattCharacteristic.PROPERTY_*
 *   Apple   – CBCharacteristicProperties
 */
enum class CharacteristicProperty {
    READ,
    WRITE,
    WRITE_NO_RESPONSE,
    NOTIFY,
    INDICATE,
    SIGNED_WRITE,
    EXTENDED_PROPERTIES
}
