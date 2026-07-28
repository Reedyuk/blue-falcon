package dev.bluefalcon.engine.apple

import dev.bluefalcon.core.CharacteristicWriteKey
import dev.bluefalcon.core.CharacteristicWriteResult
import dev.bluefalcon.core.CharacteristicWriteType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AppleCentralWriteTest {

    @Test
    fun `maximum lengths are queried independently for each write type`() = runTest {
        val peer = FakeWriteTarget()
        val controller = AppleCentralWriteController(backgroundScope)

        controller.connected(peer)

        assertEquals(
            listOf(
                CharacteristicWriteType.WithResponse,
                CharacteristicWriteType.WithoutResponse,
            ),
            peer.maximumLengthQueries,
        )
        assertEquals(
            128,
            controller.capabilities.value.getValue(
                CharacteristicWriteKey(peer.peripheralUuid, CharacteristicWriteType.WithResponse)
            ).maximumLength,
        )
        assertEquals(
            244,
            controller.capabilities.value.getValue(
                CharacteristicWriteKey(
                    peer.peripheralUuid,
                    CharacteristicWriteType.WithoutResponse,
                )
            ).maximumLength,
        )
    }

    @Test
    fun `without-response backpressure stores and writes no payload`() = runTest {
        val peer = FakeWriteTarget(canSendWithoutResponse = false)
        val controller = AppleCentralWriteController(backgroundScope)
        controller.connected(peer)

        val result = controller.write(
            peer,
            byteArrayOf(1, 2, 3),
            CharacteristicWriteType.WithoutResponse,
        )

        assertEquals(CharacteristicWriteResult.Backpressured, result)
        assertTrue(peer.writes.isEmpty())
    }

    @Test
    fun `ready without-response write copies payload and uses native mode`() = runTest {
        val peer = FakeWriteTarget()
        val controller = AppleCentralWriteController(backgroundScope)
        controller.connected(peer)
        val payload = byteArrayOf(1, 2, 3)

        val result = controller.write(
            peer,
            payload,
            CharacteristicWriteType.WithoutResponse,
        )
        payload[0] = 9

        assertEquals(CharacteristicWriteResult.Sent, result)
        assertEquals(CharacteristicWriteType.WithoutResponse, peer.writes.single().type)
        assertTrue(peer.writes.single().payload.contentEquals(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `oversized payload is rejected before native write`() = runTest {
        val peer = FakeWriteTarget(maximumWithResponse = 2)
        val controller = AppleCentralWriteController(backgroundScope)
        controller.connected(peer)

        val result = controller.write(
            peer,
            byteArrayOf(1, 2, 3),
            CharacteristicWriteType.WithResponse,
        )

        assertEquals(CharacteristicWriteResult.PayloadTooLarge(2), result)
        assertTrue(peer.writes.isEmpty())
    }

    @Test
    fun `with-response write completes only from matching callback`() = runTest {
        val peer = FakeWriteTarget()
        val controller = AppleCentralWriteController(backgroundScope)
        controller.connected(peer)
        val result = async {
            controller.write(
                peer,
                byteArrayOf(1),
                CharacteristicWriteType.WithResponse,
            )
        }
        runCurrent()

        assertFalse(result.isCompleted)
        assertFalse(
            controller.onCharacteristicWritten(
                peripheralUuid = peer.peripheralUuid,
                characteristicUuid = "other",
                failure = null,
            )
        )
        assertFalse(result.isCompleted)
        assertTrue(
            controller.onCharacteristicWritten(
                peripheralUuid = peer.peripheralUuid,
                characteristicUuid = peer.characteristicUuid,
                failure = null,
            )
        )
        assertEquals(CharacteristicWriteResult.Sent, result.await())
    }

    @Test
    fun `callback error and disconnect preserve typed outcomes`() = runTest {
        val peer = FakeWriteTarget()
        val controller = AppleCentralWriteController(backgroundScope)
        controller.connected(peer)
        val failed = async {
            controller.write(
                peer,
                byteArrayOf(1),
                CharacteristicWriteType.WithResponse,
            )
        }
        runCurrent()
        controller.onCharacteristicWritten(
            peer.peripheralUuid,
            peer.characteristicUuid,
            IllegalStateException("native failure"),
        )
        assertIs<CharacteristicWriteResult.Failed>(failed.await())

        val disconnected = async {
            controller.write(
                peer,
                byteArrayOf(2),
                CharacteristicWriteType.WithResponse,
            )
        }
        runCurrent()
        controller.disconnected(peer.peripheralUuid)
        assertEquals(CharacteristicWriteResult.Disconnected, disconnected.await())
        assertNull(
            controller.capabilities.value[
                CharacteristicWriteKey(
                    peer.peripheralUuid,
                    CharacteristicWriteType.WithResponse,
                )
            ]
        )
    }

    @Test
    fun `readiness callback updates durable state and emits edge hint`() = runTest {
        val peer = FakeWriteTarget(canSendWithoutResponse = false)
        val controller = AppleCentralWriteController(backgroundScope)
        controller.connected(peer)
        val ready = async(UnconfinedTestDispatcher(testScheduler)) {
            controller.ready.first()
        }

        peer.canSendWithoutResponse = true
        controller.onReadyToSendWithoutResponse(peer)

        assertEquals(
            CharacteristicWriteKey(
                peer.peripheralUuid,
                CharacteristicWriteType.WithoutResponse,
            ),
            ready.await().key,
        )
        assertTrue(
            controller.capabilities.value.getValue(
                CharacteristicWriteKey(
                    peer.peripheralUuid,
                    CharacteristicWriteType.WithoutResponse,
                )
            ).ready
        )
    }

    @Test
    fun `disconnected target returns disconnected without native call`() = runTest {
        val peer = FakeWriteTarget(connected = false)
        val controller = AppleCentralWriteController(backgroundScope)

        val result = controller.write(
            peer,
            byteArrayOf(1),
            CharacteristicWriteType.WithoutResponse,
        )

        assertEquals(CharacteristicWriteResult.Disconnected, result)
        assertTrue(peer.writes.isEmpty())
    }

    private class FakeWriteTarget(
        override val peripheralUuid: String = "peripheral-a",
        override val characteristicUuid: String = "characteristic-a",
        override var connected: Boolean = true,
        override var canSendWithoutResponse: Boolean = true,
        private val maximumWithResponse: Int = 128,
        private val maximumWithoutResponse: Int = 244,
    ) : AppleCentralWriteTarget {
        val maximumLengthQueries = mutableListOf<CharacteristicWriteType>()
        val writes = mutableListOf<Write>()

        override fun maximumWriteValueLength(writeType: CharacteristicWriteType): Int {
            maximumLengthQueries += writeType
            return when (writeType) {
                CharacteristicWriteType.WithResponse -> maximumWithResponse
                CharacteristicWriteType.WithoutResponse -> maximumWithoutResponse
            }
        }

        override fun writeValue(
            payload: ByteArray,
            writeType: CharacteristicWriteType,
        ) {
            writes += Write(payload.copyOf(), writeType)
        }
    }

    private data class Write(
        val payload: ByteArray,
        val type: CharacteristicWriteType,
    )
}
