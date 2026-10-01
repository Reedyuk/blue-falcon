package com.example.bluefalconcomposemultiplatform.mesh.domain

/**
 * Pure, example-owned merge logic for the mesh chat's "ledger": the set of known participants and
 * the deduplicated, timestamp-ordered list of chat messages (ADR 0016).
 *
 * [MeshNode][dev.bluefalcon.plugins.mesh.MeshNode] already deduplicates at the transport level by
 * [dev.bluefalcon.plugins.mesh.MeshMessageId], but the ledger keeps its own dedup keyed by the
 * envelope's own [MeshEnvelope.ChatMessage.messageId]/[MeshEnvelope.UserJoined.userId] so that
 * replaying a [MeshEnvelope.LedgerSync] snapshot (which can legitimately repeat entries the node
 * already has) is always safe to merge.
 */
data class MeshLedger(
    val users: Map<String, MeshEnvelope.UserJoined> = emptyMap(),
    val messages: Map<String, MeshEnvelope.ChatMessage> = emptyMap(),
) {
    /** Messages ordered newest-first, matching the chat UI's reverseLayout list. */
    val messagesNewestFirst: List<MeshEnvelope.ChatMessage>
        get() = messages.values.sortedByDescending { it.timestamp }

    fun merge(envelope: MeshEnvelope): MeshLedger = when (envelope) {
        is MeshEnvelope.UserJoined -> mergeUser(envelope)
        is MeshEnvelope.ChatMessage -> mergeMessage(envelope)
        is MeshEnvelope.LedgerSync -> {
            var merged = this
            envelope.users.forEach { merged = merged.mergeUser(it) }
            envelope.recentMessages.forEach { merged = merged.mergeMessage(it) }
            merged
        }
        // Carries no ledger state of its own - handled as a side effect (triggering a
        // syncLedgerToNeighbors() reply) by the view model, not the pure merge logic.
        is MeshEnvelope.LedgerRequest -> this
    }

    private fun mergeUser(user: MeshEnvelope.UserJoined): MeshLedger {
        // Keep the most recent announcement for a given user (e.g. after a rejoin).
        val existing = users[user.userId]
        if (existing != null && existing.timestamp >= user.timestamp) return this
        return copy(users = users + (user.userId to user))
    }

    private fun mergeMessage(message: MeshEnvelope.ChatMessage): MeshLedger {
        // A ChatMessage always implies its sender is a known participant, regardless of whether
        // that sender's explicit UserJoined/LedgerSync ever successfully arrived (e.g. it was
        // broadcast before any neighbor link was fully ready - see ADR 0016). Without this, a
        // peer's messages could show up in the chat while they never appeared in the
        // participants list.
        var merged = mergeUser(
            MeshEnvelope.UserJoined(
                userId = message.userId,
                displayName = message.displayName,
                timestamp = message.timestamp,
            ),
        )
        if (merged.messages.containsKey(message.messageId)) return merged
        return merged.copy(messages = merged.messages + (message.messageId to message))
    }

    /** A snapshot suitable for broadcasting as a [MeshEnvelope.LedgerSync], capped at [maxMessages]. */
    fun toSyncSnapshot(maxMessages: Int = 50): MeshEnvelope.LedgerSync = MeshEnvelope.LedgerSync(
        users = users.values.toList(),
        recentMessages = messagesNewestFirst.take(maxMessages),
    )
}
