package dev.bluefalcon.plugins.mesh

/**
 * Result type for mesh message framing operations.
 */
sealed interface MeshFrameResult {
    /**
     * Successfully parsed a complete message.
     */
    data class Complete(val message: MeshMessage) : MeshFrameResult

    /**
     * Received a fragment; more data expected to complete the message.
     */
    data class Incomplete(val bytesReceived: Int) : MeshFrameResult

    /**
     * Invalid frame data encountered.
     */
    data class Invalid(val reason: String) : MeshFrameResult
}

/**
 * Handles framing and chunking of mesh messages for BLE transport.
 *
 * BLE characteristics have limited payload sizes (typically 20-512 bytes depending
 * on MTU negotiation). This class handles:
 * - Serializing [MeshMessage] to bytes with header (version, flags, id, origin, hopCount, length)
 * - Chunking large payloads across multiple characteristic writes/notifications
 * - Reassembling chunked payloads back into complete [MeshMessage]s
 *
 * Frame format (header + payload):
 * ```
 * [0]     : Version (1 byte, currently 0x01)
 * [1]     : Flags (1 byte, bit 0 = isFragment, bit 1 = isLastFragment)
 * [2-3]   : Fragment index (2 bytes, big-endian, 0 for non-fragmented)
 * [4-39]  : Message ID (36 bytes, UUID string as UTF-8)
 * [40-75] : Origin UUID (36 bytes, UUID string as UTF-8)
 * [76]    : Hop count (1 byte)
 * [77-80] : Total payload length (4 bytes, big-endian)
 * [81+]   : Payload data (variable, up to MTU - header size per fragment)
 * ```
 */
internal class MeshFramer(
    private val maxFrameSize: Int,
) {
    init {
        require(maxFrameSize >= HEADER_SIZE + 1) {
            "Max frame size must accommodate header + at least 1 byte payload"
        }
    }

    companion object {
        const val VERSION: Byte = 0x01
        const val HEADER_SIZE = 81

        // Flag bits
        const val FLAG_FRAGMENTED: Byte = 0x01
        const val FLAG_LAST_FRAGMENT: Byte = 0x02
    }

    private val maxPayloadPerFrame = maxFrameSize - HEADER_SIZE

    // Reassembly state keyed by message ID
    private val reassemblyBuffers = mutableMapOf<MeshMessageId, ReassemblyBuffer>()

    private data class ReassemblyBuffer(
        val originUuid: String,
        val hopCount: Int,
        val totalLength: Int,
        val fragments: MutableMap<Int, ByteArray> = mutableMapOf(),
    ) {
        fun isComplete(): Boolean {
            val receivedBytes = fragments.values.sumOf { it.size }
            return receivedBytes >= totalLength
        }

        fun assemble(): ByteArray {
            val result = ByteArray(totalLength)
            var offset = 0
            fragments.keys.sorted().forEach { index ->
                val fragment = fragments[index]!!
                fragment.copyInto(result, offset)
                offset += fragment.size
            }
            return result
        }
    }

    /**
     * Serialize a [MeshMessage] into one or more frames.
     *
     * @return List of byte arrays, each suitable for a single characteristic write/notification
     */
    fun frame(message: MeshMessage): List<ByteArray> {
        val payload = message.payload

        if (payload.size <= maxPayloadPerFrame) {
            // Single frame, no fragmentation
            return listOf(createFrame(message, 0, false, true, payload))
        }

        // Multiple frames needed
        val frames = mutableListOf<ByteArray>()
        var offset = 0
        var fragmentIndex = 0

        while (offset < payload.size) {
            val remaining = payload.size - offset
            val chunkSize = minOf(remaining, maxPayloadPerFrame)
            val chunk = payload.copyOfRange(offset, offset + chunkSize)
            val isLast = offset + chunkSize >= payload.size

            frames.add(createFrame(message, fragmentIndex, true, isLast, chunk))

            offset += chunkSize
            fragmentIndex++
        }

        return frames
    }

    private fun createFrame(
        message: MeshMessage,
        fragmentIndex: Int,
        isFragment: Boolean,
        isLast: Boolean,
        payloadChunk: ByteArray,
    ): ByteArray {
        val frame = ByteArray(HEADER_SIZE + payloadChunk.size)

        // Version
        frame[0] = VERSION

        // Flags
        var flags: Byte = 0
        if (isFragment) flags = (flags.toInt() or FLAG_FRAGMENTED.toInt()).toByte()
        if (isLast) flags = (flags.toInt() or FLAG_LAST_FRAGMENT.toInt()).toByte()
        frame[1] = flags

        // Fragment index (big-endian)
        frame[2] = ((fragmentIndex shr 8) and 0xFF).toByte()
        frame[3] = (fragmentIndex and 0xFF).toByte()

        // Message ID (36 bytes UTF-8)
        val idBytes = message.id.value.encodeToByteArray()
        idBytes.copyInto(frame, 4, 0, minOf(idBytes.size, 36))

        // Origin UUID (36 bytes UTF-8)
        val originBytes = message.originUuid.encodeToByteArray()
        originBytes.copyInto(frame, 40, 0, minOf(originBytes.size, 36))

        // Hop count
        frame[76] = message.hopCount.toByte()

        // Total payload length (big-endian)
        val totalLen = message.payload.size
        frame[77] = ((totalLen shr 24) and 0xFF).toByte()
        frame[78] = ((totalLen shr 16) and 0xFF).toByte()
        frame[79] = ((totalLen shr 8) and 0xFF).toByte()
        frame[80] = (totalLen and 0xFF).toByte()

        // Payload chunk
        payloadChunk.copyInto(frame, HEADER_SIZE)

        return frame
    }

    /**
     * Parse a received frame and return the result.
     *
     * Call this for each characteristic write received. Returns [MeshFrameResult.Complete]
     * when a full message has been reassembled, [MeshFrameResult.Incomplete] when waiting
     * for more fragments, or [MeshFrameResult.Invalid] on parse errors.
     */
    fun parse(frame: ByteArray): MeshFrameResult {
        if (frame.size < HEADER_SIZE) {
            return MeshFrameResult.Invalid("Frame too short: ${frame.size} < $HEADER_SIZE")
        }

        // Version check
        if (frame[0] != VERSION) {
            return MeshFrameResult.Invalid("Unknown version: ${frame[0]}")
        }

        // Parse header
        val flags = frame[1]
        val isFragment = (flags.toInt() and FLAG_FRAGMENTED.toInt()) != 0
        val isLast = (flags.toInt() and FLAG_LAST_FRAGMENT.toInt()) != 0

        val fragmentIndex = ((frame[2].toInt() and 0xFF) shl 8) or (frame[3].toInt() and 0xFF)

        val idBytes = frame.copyOfRange(4, 40)
        val idString = idBytes.decodeToString().trimEnd('\u0000')
        if (idString.isBlank()) {
            return MeshFrameResult.Invalid("Empty message ID")
        }
        val messageId = MeshMessageId(idString)

        val originBytes = frame.copyOfRange(40, 76)
        val originUuid = originBytes.decodeToString().trimEnd('\u0000')
        if (originUuid.isBlank()) {
            return MeshFrameResult.Invalid("Empty origin UUID")
        }

        val hopCount = frame[76].toInt() and 0xFF

        val totalLength = ((frame[77].toInt() and 0xFF) shl 24) or
                ((frame[78].toInt() and 0xFF) shl 16) or
                ((frame[79].toInt() and 0xFF) shl 8) or
                (frame[80].toInt() and 0xFF)

        val payloadChunk = frame.copyOfRange(HEADER_SIZE, frame.size)

        if (!isFragment) {
            // Single-frame message
            val message = MeshMessage(
                id = messageId,
                originUuid = originUuid,
                hopCount = hopCount,
                payload = payloadChunk,
            )
            return MeshFrameResult.Complete(message)
        }

        // Fragmented message - add to reassembly buffer
        val buffer = reassemblyBuffers.getOrPut(messageId) {
            ReassemblyBuffer(
                originUuid = originUuid,
                hopCount = hopCount,
                totalLength = totalLength,
            )
        }

        buffer.fragments[fragmentIndex] = payloadChunk

        return if (isLast && buffer.isComplete()) {
            reassemblyBuffers.remove(messageId)
            val message = MeshMessage(
                id = messageId,
                originUuid = buffer.originUuid,
                hopCount = buffer.hopCount,
                payload = buffer.assemble(),
            )
            MeshFrameResult.Complete(message)
        } else {
            MeshFrameResult.Incomplete(bytesReceived = buffer.fragments.values.sumOf { it.size })
        }
    }

    /**
     * Clear all pending reassembly buffers.
     */
    fun clearReassemblyState() {
        reassemblyBuffers.clear()
    }
}
