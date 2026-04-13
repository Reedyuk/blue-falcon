package dev.bluefalcon

/**
 * Platform-agnostic BLE disconnect reason.
 *
 * Each platform maps its native codes (GATT status on Android, CBError on iOS)
 * to this common data class. `null` means a clean, voluntary disconnect.
 *
 * ## Code ranges
 * - **≥ 0**: Platform-specific codes (GATT status on Android, CBError code on iOS)
 * - **< 0**: Internal BlueFalcon codes (not from the BLE stack):
 *   - `-1`: Bluetooth adapter turned off
 *   - `-2`: Disconnect timeout (forced close)
 *
 * @param code Status code (platform-specific if ≥ 0, internal if < 0)
 * @param message Human-readable description of the disconnect reason
 */
data class BleDisconnectReason(
    val code: Int,
    val message: String,
)
