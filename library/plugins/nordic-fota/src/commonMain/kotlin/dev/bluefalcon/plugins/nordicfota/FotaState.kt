package dev.bluefalcon.plugins.nordicfota

/**
 * Represents the state of a firmware update operation.
 */
sealed class FotaState {
    /**
     * No firmware update is in progress.
     */
    data object Idle : FotaState()

    /**
     * Firmware image data is being uploaded to the device.
     *
     * @param offset Current byte offset in the firmware image
     * @param totalBytes Total firmware image size in bytes
     */
    data class Uploading(val offset: Int, val totalBytes: Int) : FotaState() {
        /** Upload progress as a percentage (0.0 to 1.0) */
        val progress: Float
            get() = if (totalBytes > 0) offset.toFloat() / totalBytes.toFloat() else 0f
    }

    /**
     * Upload is complete, confirming the firmware image on the device.
     */
    data object Confirming : FotaState()

    /**
     * Firmware is confirmed, device is being reset to apply the update.
     */
    data object Resetting : FotaState()

    /**
     * Device has reset and firmware is being validated.
     */
    data object Validating : FotaState()

    /**
     * Firmware update completed successfully.
     */
    data object Complete : FotaState()

    /**
     * Firmware update failed.
     *
     * @param error Description of the failure
     * @param cause The underlying exception, if any
     */
    data class Error(val error: String, val cause: Throwable? = null) : FotaState()
}

/**
 * Callback interface for firmware update progress and status events.
 */
interface FotaCallback {
    /**
     * Called when the FOTA state changes.
     *
     * @param state The new firmware update state
     */
    fun onStateChanged(state: FotaState) {}

    /**
     * Called periodically during upload with progress information.
     *
     * @param bytesSent Number of bytes sent so far
     * @param totalBytes Total firmware image size in bytes
     */
    fun onUploadProgress(bytesSent: Int, totalBytes: Int) {}

    /**
     * Called when the firmware update completes successfully.
     */
    fun onComplete() {}

    /**
     * Called when the firmware update fails.
     *
     * @param error Description of the failure
     * @param cause The underlying exception, if any
     */
    fun onError(error: String, cause: Throwable? = null) {}
}
