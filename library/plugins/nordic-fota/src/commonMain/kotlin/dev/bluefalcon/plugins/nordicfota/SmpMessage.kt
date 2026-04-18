package dev.bluefalcon.plugins.nordicfota

/**
 * Builds and parses SMP (Simple Management Protocol) messages.
 *
 * An SMP message consists of an 8-byte header followed by a CBOR-encoded
 * payload. The header layout is:
 *
 * ```
 * Byte 0:    Operation (read/write/read-response/write-response)
 * Byte 1:    Flags (reserved, set to 0)
 * Byte 2-3:  Payload length (big-endian uint16)
 * Byte 4-5:  Group ID (big-endian uint16)
 * Byte 6:    Sequence number (uint8)
 * Byte 7:    Command ID (uint8)
 * ```
 */
internal object SmpMessage {

    private var sequenceNumber: Byte = 0

    /**
     * Build an SMP request message.
     *
     * @param op Operation type (read=0, write=2)
     * @param group SMP group ID
     * @param commandId Command ID within the group
     * @param payload CBOR-encoded payload map entries
     * @return Complete SMP message bytes (header + payload)
     */
    fun buildRequest(
        op: Int,
        group: Int,
        commandId: Int,
        payload: Map<String, Any> = emptyMap()
    ): ByteArray {
        val cborPayload = if (payload.isEmpty()) {
            // Empty CBOR map: 0xBF 0xFF (indefinite-length) or 0xA0 (definite, 0 items)
            byteArrayOf(0xA0.toByte())
        } else {
            CborEncoder.encodeMap(payload)
        }

        val header = ByteArray(SmpConstants.HEADER_SIZE)
        header[0] = op.toByte()
        header[1] = 0 // flags
        header[2] = (cborPayload.size shr 8).toByte()
        header[3] = cborPayload.size.toByte()
        header[4] = (group shr 8).toByte()
        header[5] = group.toByte()
        header[6] = nextSequenceNumber()
        header[7] = commandId.toByte()

        return header + cborPayload
    }

    /**
     * Build an image upload request for a single chunk.
     *
     * @param imageData The full firmware image data
     * @param offset Current upload offset (byte position)
     * @param chunkSize Maximum payload chunk size
     * @param imageIndex Image slot index (default 0)
     * @return SMP message for this upload chunk
     */
    fun buildImageUploadRequest(
        imageData: ByteArray,
        offset: Int,
        chunkSize: Int,
        imageIndex: Int = 0
    ): ByteArray {
        val remaining = imageData.size - offset
        val actualChunkSize = minOf(chunkSize, remaining)
        val chunk = imageData.copyOfRange(offset, offset + actualChunkSize)

        val payload = mutableMapOf<String, Any>(
            "off" to offset,
            "data" to chunk
        )

        // Include total image length and image index in the first chunk.
        // The device validates the image integrity after the full upload completes.
        if (offset == 0) {
            payload["len"] = imageData.size
            payload["image"] = imageIndex
        }

        return buildRequest(
            op = SmpConstants.OP_WRITE,
            group = SmpConstants.GROUP_IMAGE,
            commandId = SmpConstants.IMAGE_UPLOAD,
            payload = payload
        )
    }

    /**
     * Build a request to read the current image state (list of images on the device).
     */
    fun buildImageStateReadRequest(): ByteArray {
        return buildRequest(
            op = SmpConstants.OP_READ,
            group = SmpConstants.GROUP_IMAGE,
            commandId = SmpConstants.IMAGE_STATE
        )
    }

    /**
     * Build a request to confirm (mark as permanent) the image in a given slot.
     *
     * @param imageHash Optional SHA-256 hash of the image to confirm. When empty,
     *                  the device confirms the currently test-booted image.
     *                  When provided, confirms a specific image identified by hash.
     */
    fun buildImageConfirmRequest(imageHash: ByteArray = byteArrayOf()): ByteArray {
        val payload = mutableMapOf<String, Any>(
            "confirm" to true
        )
        if (imageHash.isNotEmpty()) {
            payload["hash"] = imageHash
        }
        return buildRequest(
            op = SmpConstants.OP_WRITE,
            group = SmpConstants.GROUP_IMAGE,
            commandId = SmpConstants.IMAGE_STATE,
            payload = payload
        )
    }

    /**
     * Build a device reset request.
     */
    fun buildResetRequest(): ByteArray {
        return buildRequest(
            op = SmpConstants.OP_WRITE,
            group = SmpConstants.GROUP_OS,
            commandId = SmpConstants.OS_RESET
        )
    }

    /**
     * Build an echo request (useful for testing SMP connectivity).
     *
     * @param message Echo message string
     */
    fun buildEchoRequest(message: String): ByteArray {
        return buildRequest(
            op = SmpConstants.OP_WRITE,
            group = SmpConstants.GROUP_OS,
            commandId = SmpConstants.OS_ECHO,
            payload = mapOf("d" to message)
        )
    }

    /**
     * Parse an SMP response message.
     *
     * @param data Raw response bytes (header + CBOR payload)
     * @return Parsed response or null if invalid
     */
    fun parseResponse(data: ByteArray): SmpResponse? {
        if (data.size < SmpConstants.HEADER_SIZE) return null

        val op = data[0].toInt() and 0xFF
        val payloadLength = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
        val group = ((data[4].toInt() and 0xFF) shl 8) or (data[5].toInt() and 0xFF)
        val sequence = data[6].toInt() and 0xFF
        val commandId = data[7].toInt() and 0xFF

        val payload = if (data.size > SmpConstants.HEADER_SIZE) {
            val cborData = data.copyOfRange(SmpConstants.HEADER_SIZE, data.size)
            CborDecoder.decodeMap(cborData)
        } else {
            emptyMap()
        }

        return SmpResponse(
            operation = op,
            group = group,
            sequence = sequence,
            commandId = commandId,
            payloadLength = payloadLength,
            payload = payload ?: emptyMap()
        )
    }

    private fun nextSequenceNumber(): Byte {
        val current = sequenceNumber
        sequenceNumber = ((sequenceNumber + 1) % 256).toByte()
        return current
    }

    /**
     * Reset the sequence number counter (primarily for testing).
     */
    internal fun resetSequenceNumber() {
        sequenceNumber = 0
    }
}

/**
 * Parsed SMP response.
 */
data class SmpResponse(
    val operation: Int,
    val group: Int,
    val sequence: Int,
    val commandId: Int,
    val payloadLength: Int,
    val payload: Map<String, Any>
) {
    /**
     * Check if the response indicates an error.
     * SMP error responses contain an "rc" (return code) field with a non-zero value.
     */
    val isError: Boolean
        get() = (payload["rc"] as? Long)?.let { it != 0L } ?: false

    /**
     * Get the return code from the response.
     */
    val returnCode: Int
        get() = (payload["rc"] as? Long)?.toInt() ?: 0

    /**
     * Get the upload offset from an image upload response.
     * The device acknowledges each chunk by returning the next expected offset.
     */
    val nextOffset: Int?
        get() = (payload["off"] as? Long)?.toInt()
}
