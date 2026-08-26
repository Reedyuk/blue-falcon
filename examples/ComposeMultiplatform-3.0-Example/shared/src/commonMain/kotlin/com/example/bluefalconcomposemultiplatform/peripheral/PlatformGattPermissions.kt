package com.example.bluefalconcomposemultiplatform.peripheral

/**
 * Raw, platform-specific GATT permission bitmasks that request OS-enforced encryption
 * (and therefore bonding) for a characteristic or descriptor.
 *
 * `GattCharacteristicConfig.permissions`/`GattDescriptorConfig.permissions` are passed
 * straight through to the underlying platform APIs (Android's `BluetoothGattCharacteristic`/
 * `BluetoothGattDescriptor` bitmask, Apple's `CBAttributePermissions` bitmask), and those two
 * bitmasks are not interchangeable. Setting an Android-only value from `commonMain` would be
 * silently wrong on Apple platforms (and vice versa), so this `expect`/`actual` indirection
 * lets each platform supply its own correct value.
 *
 * When these permissions are set on the real GATT attribute, the platform Bluetooth stack
 * itself demands encryption/bonding before the request reaches the app (triggering a real,
 * persisted bond that shows up in system Settings), instead of the app having to fake an
 * [dev.bluefalcon.peripheral.GattResponseStatus.InsufficientAuthentication] response.
 */
expect object PlatformGattPermissions {
    /** Permission bitmask for a characteristic/descriptor readable only over an encrypted link. */
    val readEncrypted: Int

    /** Permission bitmask for a characteristic/descriptor writable only over an encrypted link. */
    val writeEncrypted: Int
}
