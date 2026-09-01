package dev.bluefalcon.core

/**
 * A Bluetooth adapter (radio) attached to the host machine.
 *
 * Hosts such as Windows desktops can expose more than one adapter, in which case an
 * application may want to pick which radio Blue Falcon uses. Mobile platforms expose a
 * single, non-selectable radio.
 *
 * @property identifier Platform specific identifier of the adapter. On Windows this is the
 *   `DeviceInformation` id of the radio. Pass this to [BlueFalconEngine.selectAdapter].
 * @property name Human readable adapter name, or the identifier when the platform does not
 *   provide one.
 * @property address The adapter's own Bluetooth address in `AA:BB:CC:DD:EE:FF` form, or null
 *   when the platform does not expose it.
 * @property isDefault Whether the platform considers this the default adapter.
 * @property isLowEnergySupported Whether the adapter supports Bluetooth Low Energy.
 */
data class BluetoothAdapter(
    val identifier: String,
    val name: String,
    val address: String? = null,
    val isDefault: Boolean = false,
    val isLowEnergySupported: Boolean = true,
)

/**
 * Outcome of a [BlueFalconEngine.selectAdapter] request.
 */
sealed interface AdapterSelectionResult {
    /** The adapter was selected and will be used for subsequent operations. */
    data class Selected(
        val adapter: BluetoothAdapter,
    ) : AdapterSelectionResult

    /** No adapter with the requested identifier is present on the host. */
    data object NotFound : AdapterSelectionResult

    /** The platform does not support choosing between adapters. */
    data object Unsupported : AdapterSelectionResult

    /** The platform rejected the selection. */
    data class Failed(
        val cause: Throwable?,
    ) : AdapterSelectionResult
}
