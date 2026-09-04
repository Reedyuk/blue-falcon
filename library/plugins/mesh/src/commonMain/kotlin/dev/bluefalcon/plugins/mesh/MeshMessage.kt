package dev.bluefalcon.plugins.mesh

import kotlin.jvm.JvmInline

/**
 * Unique identifier for a mesh message, used for deduplication.
 *
 * The ID should be unique enough to prevent collision across the mesh network
 * within the configured TTL window. Typically derived from a random value or
 * content hash combined with origin information.
 */
@JvmInline
value class MeshMessageId(val value: String) {
    init {
        require(value.isNotBlank()) { "Mesh message ID must not be blank" }
    }

    companion object {
        /**
         * Generate a random mesh message ID.
         */
        fun random(): MeshMessageId = MeshMessageId(
            kotlin.uuid.Uuid.random().toString()
        )
    }
}

/**
 * A message that can be relayed across a BLE mesh network.
 *
 * Each message carries:
 * - A unique [id] for deduplication across nodes
 * - The [originUuid] of the node that first created/sent the message
 * - A [hopCount] incremented at each relay to prevent infinite loops
 * - The actual [payload] bytes being transported
 *
 * @property id Unique identifier for deduplication
 * @property originUuid UUID of the node that originally sent this message
 * @property hopCount Number of hops this message has traveled (0 = originated locally)
 * @property payload The actual data being transported
 */
data class MeshMessage(
    val id: MeshMessageId,
    val originUuid: String,
    val hopCount: Int,
    val payload: ByteArray,
) {
    init {
        require(hopCount >= 0) { "Hop count must not be negative" }
    }

    /**
     * Create a new message with an incremented hop count for relaying.
     */
    fun withIncrementedHopCount(): MeshMessage = copy(hopCount = hopCount + 1)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as MeshMessage

        if (id != other.id) return false
        if (originUuid != other.originUuid) return false
        if (hopCount != other.hopCount) return false
        if (!payload.contentEquals(other.payload)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + originUuid.hashCode()
        result = 31 * result + hopCount
        result = 31 * result + payload.contentHashCode()
        return result
    }
}
