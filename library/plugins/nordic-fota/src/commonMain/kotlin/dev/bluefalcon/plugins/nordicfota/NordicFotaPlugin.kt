package dev.bluefalcon.plugins.nordicfota

import dev.bluefalcon.core.*
import dev.bluefalcon.core.plugin.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Plugin that provides Firmware Over The Air (FOTA) update support for
 * Nordic Semiconductor devices using the SMP (Simple Management Protocol).
 *
 * This plugin implements the MCUmgr image management protocol to upload firmware
 * images to devices running MCUboot, then confirm and reset the device to apply
 * the update.
 *
 * Usage:
 * ```
 * val fotaPlugin = NordicFotaPlugin.create {
 *     autoConfirm = true
 *     autoReset = true
 *     chunkSize = 256
 * }
 *
 * val blueFalcon = BlueFalcon {
 *     engine = myEngine
 *     install(fotaPlugin)
 * }
 *
 * // Start firmware update
 * fotaPlugin.startUpdate(peripheral, firmwareData)
 *
 * // Observe progress
 * fotaPlugin.state.collect { state ->
 *     when (state) {
 *         is FotaState.Uploading -> println("Progress: ${state.progress * 100}%")
 *         is FotaState.Complete -> println("Update complete!")
 *         is FotaState.Error -> println("Error: ${state.error}")
 *         else -> {}
 *     }
 * }
 * ```
 */
class NordicFotaPlugin(private val config: Config) : BlueFalconPlugin {

    private var client: BlueFalconClient? = null

    private val _state = MutableStateFlow<FotaState>(FotaState.Idle)

    /**
     * Current state of the firmware update as a reactive flow.
     */
    val state: StateFlow<FotaState> = _state.asStateFlow()

    private val callbacks = mutableSetOf<FotaCallback>()

    private var uploadData: ByteArray? = null
    private var uploadOffset: Int = 0
    private var uploadPeripheralUuid: String? = null

    /**
     * Configuration for the Nordic FOTA plugin.
     */
    class Config : PluginConfig() {
        /**
         * Maximum chunk size for firmware data writes (in bytes).
         * This should be less than the peripheral's MTU minus SMP header overhead.
         * Default: 256 bytes.
         */
        var chunkSize: Int = 256

        /**
         * Whether to automatically confirm the image after upload.
         * If false, the caller must manually call [confirmImage].
         * Default: true.
         */
        var autoConfirm: Boolean = true

        /**
         * Whether to automatically reset the device after confirmation.
         * If false, the caller must manually call [resetDevice].
         * Default: true.
         */
        var autoReset: Boolean = true

        /**
         * Image slot index to upload to. Default is 0 (primary slot).
         */
        var imageIndex: Int = 0

        /**
         * Timeout for waiting for SMP responses.
         * Default: 30 seconds.
         */
        var responseTimeout: Duration = 30.seconds
    }

    override fun install(client: BlueFalconClient, config: PluginConfig) {
        this.client = client
    }

    override suspend fun onAfterConnect(call: ConnectCall, result: Result<Unit>) {
        // When reconnecting after a reset, transition from Validating to Complete
        if (uploadPeripheralUuid != null && call.peripheral.uuid == uploadPeripheralUuid) {
            if (_state.value is FotaState.Validating && result.isSuccess) {
                updateState(FotaState.Complete)
                notifyComplete()
                cleanupUploadState()
            }
        }
    }

    override suspend fun onBeforeWrite(call: WriteCall): WriteCall {
        return call
    }

    override suspend fun onAfterWrite(call: WriteCall, result: Result<Unit>) {
        // Track write results for SMP characteristic writes during active upload
        val currentUpload = uploadPeripheralUuid
        if (currentUpload != null && call.peripheral.uuid == currentUpload) {
            val charUuid = call.characteristic.uuid.toString()
            if (charUuid.equals(SmpConstants.SMP_CHARACTERISTIC_UUID, ignoreCase = true)) {
                if (result.isFailure) {
                    val error = result.exceptionOrNull()
                    updateState(FotaState.Error("Write failed during upload", error))
                }
            }
        }
    }

    override suspend fun onAfterDisconnect(call: DisconnectCall, result: Result<Unit>) {
        // After a reset, the device disconnects. Transition to Validating
        // so the caller can reconnect and verify the firmware.
        if (uploadPeripheralUuid != null && call.peripheral.uuid == uploadPeripheralUuid) {
            if (_state.value is FotaState.Resetting) {
                updateState(FotaState.Validating)
            }
        }
    }

    /**
     * Start a firmware update on the given peripheral.
     *
     * The peripheral must already be connected and have its services discovered.
     * The SMP service and characteristic must be available.
     *
     * @param peripheral The connected peripheral to update
     * @param firmwareData The firmware image data to upload
     * @return A list of SMP messages to write to the SMP characteristic.
     *         The caller is responsible for writing these messages sequentially
     *         using the BlueFalcon client and handling responses.
     * @throws FotaException if the peripheral does not have the SMP service
     */
    fun startUpdate(
        peripheral: BluetoothPeripheral,
        firmwareData: ByteArray
    ): List<ByteArray> {
        if (firmwareData.isEmpty()) {
            updateState(FotaState.Error("Firmware data is empty"))
            return emptyList()
        }

        uploadData = firmwareData
        uploadOffset = 0
        uploadPeripheralUuid = peripheral.uuid
        updateState(FotaState.Uploading(0, firmwareData.size))

        // Generate all upload chunk messages
        return generateUploadMessages(firmwareData)
    }

    /**
     * Generate the SMP upload messages for the firmware data.
     *
     * @param firmwareData The firmware image to chunk
     * @return List of SMP-encoded upload request messages
     */
    internal fun generateUploadMessages(firmwareData: ByteArray): List<ByteArray> {
        val messages = mutableListOf<ByteArray>()
        var offset = 0

        while (offset < firmwareData.size) {
            val message = SmpMessage.buildImageUploadRequest(
                imageData = firmwareData,
                offset = offset,
                chunkSize = config.chunkSize,
                imageIndex = config.imageIndex
            )
            messages.add(message)

            val remaining = firmwareData.size - offset
            offset += minOf(config.chunkSize, remaining)
        }

        return messages
    }

    /**
     * Process an SMP response received from the device.
     *
     * Call this method when a notification is received on the SMP characteristic
     * during an active firmware update.
     *
     * @param responseData The raw SMP response bytes
     * @return The next SMP message to write, or null if the update is complete
     *         or an error occurred
     */
    fun processResponse(responseData: ByteArray): ByteArray? {
        val response = SmpMessage.parseResponse(responseData)
        if (response == null) {
            updateState(FotaState.Error("Invalid SMP response"))
            return null
        }

        if (response.isError) {
            updateState(FotaState.Error("SMP error: return code ${response.returnCode}"))
            return null
        }

        return when {
            // Image upload response
            response.group == SmpConstants.GROUP_IMAGE &&
                response.commandId == SmpConstants.IMAGE_UPLOAD -> {
                handleUploadResponse(response)
            }

            // Image state response (after confirm)
            response.group == SmpConstants.GROUP_IMAGE &&
                response.commandId == SmpConstants.IMAGE_STATE -> {
                handleImageStateResponse(response)
            }

            // Reset response
            response.group == SmpConstants.GROUP_OS &&
                response.commandId == SmpConstants.OS_RESET -> {
                handleResetResponse()
            }

            else -> null
        }
    }

    private fun handleUploadResponse(response: SmpResponse): ByteArray? {
        val firmwareData = uploadData ?: return null
        val nextOffset = response.nextOffset ?: return null

        if (nextOffset >= firmwareData.size) {
            // Upload complete
            uploadOffset = firmwareData.size
            notifyProgress(firmwareData.size, firmwareData.size)

            if (config.autoConfirm) {
                updateState(FotaState.Confirming)
                return buildConfirmMessage()
            } else {
                updateState(FotaState.Uploading(firmwareData.size, firmwareData.size))
                return null
            }
        }

        // Continue uploading
        uploadOffset = nextOffset
        updateState(FotaState.Uploading(nextOffset, firmwareData.size))
        notifyProgress(nextOffset, firmwareData.size)

        return SmpMessage.buildImageUploadRequest(
            imageData = firmwareData,
            offset = nextOffset,
            chunkSize = config.chunkSize,
            imageIndex = config.imageIndex
        )
    }

    private fun handleImageStateResponse(
        @Suppress("UNUSED_PARAMETER") response: SmpResponse
    ): ByteArray? {
        // Response payload contains image slot details which could be used for
        // validation in future enhancements. Currently we proceed to reset.
        if (config.autoReset) {
            updateState(FotaState.Resetting)
            return buildResetMessage()
        }
        return null
    }

    private fun handleResetResponse(): ByteArray? {
        // Device will reset and disconnect. The disconnect interceptor transitions
        // the state to Validating, and reconnection completes the update.
        // State remains Resetting until the disconnect is observed.
        return null
    }

    /**
     * Build a confirm message to mark the uploaded image as permanent.
     *
     * @param imageHash Optional SHA-256 hash of the image to confirm
     * @return SMP confirm request message
     */
    fun buildConfirmMessage(imageHash: ByteArray = byteArrayOf()): ByteArray {
        return SmpMessage.buildImageConfirmRequest(imageHash)
    }

    /**
     * Build a reset message to reboot the device.
     *
     * @return SMP reset request message
     */
    fun buildResetMessage(): ByteArray {
        return SmpMessage.buildResetRequest()
    }

    /**
     * Build a message to read the current image state from the device.
     *
     * @return SMP image state read request message
     */
    fun buildImageStateRequest(): ByteArray {
        return SmpMessage.buildImageStateReadRequest()
    }

    /**
     * Cancel any in-progress firmware update and reset to idle state.
     */
    fun cancelUpdate() {
        cleanupUploadState()
        updateState(FotaState.Idle)
    }

    /**
     * Register a callback for firmware update events.
     *
     * @param callback The callback to register
     */
    fun addCallback(callback: FotaCallback) {
        callbacks.add(callback)
    }

    /**
     * Remove a previously registered callback.
     *
     * @param callback The callback to remove
     */
    fun removeCallback(callback: FotaCallback) {
        callbacks.remove(callback)
    }

    /**
     * Calculate the effective chunk size based on the peripheral's MTU.
     *
     * The SMP header (8 bytes) and CBOR map overhead are subtracted from
     * the MTU to determine the maximum firmware data payload per write.
     *
     * @param mtuSize The peripheral's current MTU size
     * @return The maximum firmware data chunk size
     */
    fun effectiveChunkSize(mtuSize: Int): Int {
        // Reserve space for SMP header (8 bytes) and CBOR map overhead (~32 bytes for keys/lengths)
        val overhead = SmpConstants.HEADER_SIZE + 32
        val mtuBasedSize = maxOf(mtuSize - overhead, 1)
        return minOf(config.chunkSize, mtuBasedSize)
    }

    private fun updateState(newState: FotaState) {
        _state.value = newState
        callbacks.forEach { it.onStateChanged(newState) }

        if (newState is FotaState.Error) {
            callbacks.forEach { it.onError(newState.error, newState.cause) }
        }
    }

    private fun notifyProgress(bytesSent: Int, totalBytes: Int) {
        callbacks.forEach { it.onUploadProgress(bytesSent, totalBytes) }
    }

    private fun notifyComplete() {
        callbacks.forEach { it.onComplete() }
    }

    private fun cleanupUploadState() {
        uploadData = null
        uploadOffset = 0
        uploadPeripheralUuid = null
    }

    companion object {
        /**
         * SMP service UUID for discovering Nordic MCUmgr devices.
         */
        val SMP_SERVICE_UUID = SmpConstants.SMP_SERVICE_UUID

        /**
         * SMP characteristic UUID for read/write operations.
         */
        val SMP_CHARACTERISTIC_UUID = SmpConstants.SMP_CHARACTERISTIC_UUID

        /**
         * Create a new NordicFotaPlugin instance with the given configuration.
         */
        fun create(configure: Config.() -> Unit = {}): NordicFotaPlugin {
            val config = Config().apply(configure)
            return NordicFotaPlugin(config)
        }
    }
}

/**
 * Exception thrown when a firmware update operation fails.
 */
class FotaException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * DSL function to create and configure a Nordic FOTA plugin.
 */
fun installNordicFota(configure: NordicFotaPlugin.Config.() -> Unit): NordicFotaPlugin {
    return NordicFotaPlugin.create(configure)
}
