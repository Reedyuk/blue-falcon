package com.example.bluefalconcomposemultiplatform.mesh.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MeshLedgerTest {

    private fun user(id: String, name: String, timestamp: Long) =
        MeshEnvelope.UserJoined(userId = id, displayName = name, timestamp = timestamp)

    private fun message(id: String, userId: String, text: String, timestamp: Long) =
        MeshEnvelope.ChatMessage(
            messageId = id,
            userId = userId,
            displayName = "ignored",
            timestamp = timestamp,
            text = text,
        )

    @Test
    fun `merging a UserJoined adds the user`() {
        val ledger = MeshLedger().merge(user("u1", "Alice", 100))

        assertEquals(1, ledger.users.size)
        assertEquals("Alice", ledger.users.getValue("u1").displayName)
    }

    @Test
    fun `a stale rejoin does not overwrite a newer announcement`() {
        val ledger = MeshLedger()
            .merge(user("u1", "Alice", 200))
            .merge(user("u1", "AliceOld", 100))

        assertEquals("Alice", ledger.users.getValue("u1").displayName)
    }

    @Test
    fun `a newer rejoin overwrites an older announcement`() {
        val ledger = MeshLedger()
            .merge(user("u1", "Alice", 100))
            .merge(user("u1", "AliceRenamed", 200))

        assertEquals("AliceRenamed", ledger.users.getValue("u1").displayName)
    }

    @Test
    fun `duplicate chat messages are deduplicated by messageId`() {
        val ledger = MeshLedger()
            .merge(message("m1", "u1", "hi", 100))
            .merge(message("m1", "u1", "hi-duplicate-relay", 100))

        assertEquals(1, ledger.messages.size)
        assertEquals("hi", ledger.messages.getValue("m1").text)
    }

    @Test
    fun `a chat message from an unknown sender registers them as a participant`() {
        val envelope = MeshEnvelope.ChatMessage(
            messageId = "m1",
            userId = "u1",
            displayName = "Alice",
            timestamp = 100,
            text = "hi",
        )

        val ledger = MeshLedger().merge(envelope)

        assertEquals("Alice", ledger.users.getValue("u1").displayName)
    }

    @Test
    fun `a chat message does not overwrite a newer explicit UserJoined rename`() {
        val ledger = MeshLedger()
            .merge(user("u1", "AliceRenamed", 200))
            .merge(message("m1", "u1", "hi", 100))

        assertEquals("AliceRenamed", ledger.users.getValue("u1").displayName)
    }

    @Test
    fun `messagesNewestFirst orders by timestamp descending`() {
        val ledger = MeshLedger()
            .merge(message("m1", "u1", "first", 100))
            .merge(message("m2", "u1", "second", 200))

        assertEquals(listOf("m2", "m1"), ledger.messagesNewestFirst.map { it.messageId })
    }

    @Test
    fun `merging a LedgerSync snapshot merges all users and messages`() {
        val snapshot = MeshEnvelope.LedgerSync(
            users = listOf(user("u1", "Alice", 100), user("u2", "Bob", 150)),
            recentMessages = listOf(message("m1", "u1", "hi", 100)),
        )

        val ledger = MeshLedger().merge(snapshot)

        assertEquals(setOf("u1", "u2"), ledger.users.keys)
        assertTrue(ledger.messages.containsKey("m1"))
    }

    @Test
    fun `toSyncSnapshot caps messages at maxMessages`() {
        var ledger = MeshLedger()
        repeat(5) { index ->
            ledger = ledger.merge(message("m$index", "u1", "msg $index", index.toLong()))
        }

        val snapshot = ledger.toSyncSnapshot(maxMessages = 2)

        assertEquals(2, snapshot.recentMessages.size)
        // Newest-first: the two highest timestamps (m4, m3) should be kept.
        assertEquals(listOf("m4", "m3"), snapshot.recentMessages.map { it.messageId })
    }

    @Test
    fun `envelope encode and decode round-trips`() {
        val envelope = message("m1", "u1", "hello mesh", 100)

        val decoded = MeshEnvelope.decodeOrNull(MeshEnvelope.encode(envelope))

        assertEquals(envelope, decoded)
    }

    @Test
    fun `merging a LedgerRequest is a no-op`() {
        val ledger = MeshLedger()
            .merge(user("u1", "Alice", 100))
            .merge(MeshEnvelope.LedgerRequest(requesterId = "u2"))

        assertEquals(1, ledger.users.size)
        assertEquals(0, ledger.messages.size)
    }

    @Test
    fun `LedgerRequest encode and decode round-trips`() {
        val envelope = MeshEnvelope.LedgerRequest(requesterId = "u1")

        val decoded = MeshEnvelope.decodeOrNull(MeshEnvelope.encode(envelope))

        assertEquals(envelope, decoded)
    }

    @Test
    fun `decodeOrNull returns null for garbage bytes`() {
        assertEquals(null, MeshEnvelope.decodeOrNull(byteArrayOf(1, 2, 3)))
    }
}
