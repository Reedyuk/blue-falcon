package com.example.bluefalconcomposemultiplatform.mesh.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Application-level chat protocol carried as opaque bytes inside [dev.bluefalcon.plugins.mesh.MeshMessage.payload].
 *
 * `blue-falcon-plugin-mesh` (ADR 0011) only relays opaque [ByteArray] payloads with transport-level
 * dedup/hop-count metadata. Everything chat-specific - who sent a message, when, and the running
 * ledger of known participants - is an example-level concern layered on top, per ADR 0016.
 */
@Serializable
sealed interface MeshEnvelope {

    /** Announces that a user has joined the mesh under [displayName]. */
    @Serializable
    @SerialName("user_joined")
    data class UserJoined(
        val userId: String,
        val displayName: String,
        val timestamp: Long,
    ) : MeshEnvelope

    /** A single timestamped chat message, attributed to [userId]/[displayName]. */
    @Serializable
    @SerialName("chat_message")
    data class ChatMessage(
        val messageId: String,
        val userId: String,
        val displayName: String,
        val timestamp: Long,
        val text: String,
    ) : MeshEnvelope

    /**
     * A pull request asking any receiving neighbor to reply with its own [LedgerSync] snapshot.
     * Broadcast when a node (re)joins or detects a new neighbor, so catching up on
     * history/participants doesn't depend solely on the other side's periodic/edge-triggered
     * pushes landing in time (ADR 0016).
     */
    @Serializable
    @SerialName("ledger_request")
    data class LedgerRequest(val requesterId: String) : MeshEnvelope

    /**
     * A snapshot of this node's locally known ledger, rebroadcast whenever a new neighbor
     * connects so late joiners catch up on participants/history (ADR 0016). Carried onward by
     * [dev.bluefalcon.plugins.mesh.MeshNode]'s existing flood-with-dedup relay like any other message.
     */
    @Serializable
    @SerialName("ledger_sync")
    data class LedgerSync(
        val users: List<UserJoined>,
        val recentMessages: List<ChatMessage>,
    ) : MeshEnvelope

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            classDiscriminator = "type"
        }

        fun encode(envelope: MeshEnvelope): ByteArray =
            json.encodeToString(serializer(), envelope).encodeToByteArray()

        /** Returns `null` for payloads that are not a valid [MeshEnvelope] (e.g. garbled bytes). */
        fun decodeOrNull(payload: ByteArray): MeshEnvelope? = runCatching {
            json.decodeFromString(serializer(), payload.decodeToString())
        }.getOrNull()
    }
}
