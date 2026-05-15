package dev.bluefalcon.plugins.clone

import dev.bluefalcon.core.plugin.PluginConfig

/**
 * Configuration for the DeviceClonePlugin.
 */
class CloneConfig : PluginConfig() {
    /**
     * Whether to read characteristic values during cloning.
     * If false, only the GATT structure (UUIDs, names) is captured without reading values.
     */
    var readCharacteristicValues: Boolean = true

    /**
     * Whether to read descriptor values during cloning.
     * If false, descriptors are listed but their values are not read.
     */
    var readDescriptorValues: Boolean = true

    /**
     * Whether to include advertisement data in the clone.
     * Advertisement data is captured from the scan/discovery phase.
     */
    var includeAdvertisementData: Boolean = true

    /**
     * Platform identifier string included in the clone metadata.
     * Defaults to "Unknown" — callers should set this to their platform (e.g., "Android", "iOS").
     */
    var platform: String = "Unknown"

    /**
     * Optional callback for clone progress reporting.
     */
    var callback: CloneCallback? = null

    /**
     * Provider function for generating timestamps.
     * Defaults to an empty string. Users should provide an ISO-8601 timestamp provider
     * appropriate for their platform (e.g., using kotlinx-datetime or platform APIs).
     *
     * Example with kotlinx-datetime:
     * ```
     * timestampProvider = { Clock.System.now().toString() }
     * ```
     */
    var timestampProvider: () -> String = { "" }
}

/**
 * Callback interface for receiving clone progress updates.
 */
interface CloneCallback {
    /**
     * Called to report clone progress.
     * @param current Number of items processed so far
     * @param total Total number of items to process
     * @param message Human-readable progress description
     */
    fun onCloneProgress(current: Int, total: Int, message: String)

    /**
     * Called when the clone operation completes successfully.
     * @param clone The resulting device clone
     */
    fun onCloneComplete(clone: DeviceClone)

    /**
     * Called when the clone operation encounters an error.
     * @param error The exception that occurred
     */
    fun onCloneError(error: Throwable)
}
