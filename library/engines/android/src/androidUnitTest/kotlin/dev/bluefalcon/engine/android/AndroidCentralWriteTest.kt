package dev.bluefalcon.engine.android

import dev.bluefalcon.core.CharacteristicWriteKey
import dev.bluefalcon.core.CharacteristicWriteResult
import dev.bluefalcon.core.CharacteristicWriteType
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidCentralWriteTest {

    @Test
    fun `connection publishes default write limits for both write types`() {
        val state = AndroidCentralWriteState()

        val generation = state.onConnected("peer-a")

        assertEquals(1, generation)
        CharacteristicWriteType.entries.forEach { writeType ->
            assertEquals(
                20,
                state.capabilities.value[
                    CharacteristicWriteKey("peer-a", writeType)
                ]?.maximumLength,
            )
            assertTrue(
                state.capabilities.value[
                    CharacteristicWriteKey("peer-a", writeType)
                ]?.ready == true,
            )
        }
    }

    @Test
    fun `successful MTU update is scoped to matching connection generation`() {
        val state = AndroidCentralWriteState()
        val peerAGeneration = state.onConnected("peer-a")
        state.onConnected("peer-b")

        state.onMtuChanged(
            peripheralUuid = "peer-a",
            generation = peerAGeneration,
            mtu = 247,
            successful = true,
        )

        CharacteristicWriteType.entries.forEach { writeType ->
            assertEquals(
                244,
                state.capabilities.value[
                    CharacteristicWriteKey("peer-a", writeType)
                ]?.maximumLength,
            )
            assertEquals(
                20,
                state.capabilities.value[
                    CharacteristicWriteKey("peer-b", writeType)
                ]?.maximumLength,
            )
        }

        state.onMtuChanged("peer-a", peerAGeneration - 1, mtu = 100, successful = true)
        state.onMtuChanged("peer-a", peerAGeneration, mtu = 100, successful = false)
        assertEquals(
            244,
            state.capabilities.value[
                CharacteristicWriteKey(
                    "peer-a",
                    CharacteristicWriteType.WithoutResponse,
                )
            ]?.maximumLength,
        )
    }

    @Test
    fun `busy and ready transitions update durable state and emit retry hint`() = runTest {
        val state = AndroidCentralWriteState()
        val generation = state.onConnected("peer-a")
        val expectedKey = CharacteristicWriteKey(
            "peer-a",
            CharacteristicWriteType.WithoutResponse,
        )
        val readyEvent = async {
            state.writeReady.first { it.key == expectedKey }
        }
        runCurrent()

        state.onBusy("peer-a", generation)
        assertFalse(state.capabilities.value.getValue(expectedKey).ready)

        state.onReady("peer-a", generation)

        assertTrue(state.capabilities.value.getValue(expectedKey).ready)
        assertEquals(expectedKey, readyEvent.await().key)
    }

    @Test
    fun `validation reports backpressure size and disconnect without submitting`() {
        val state = AndroidCentralWriteState()
        val generation = state.onConnected("peer-a")

        assertNull(
            state.validateWrite(
                "peer-a",
                generation,
                CharacteristicWriteType.WithoutResponse,
                payloadSize = 20,
            ),
        )
        assertEquals(
            CharacteristicWriteResult.PayloadTooLarge(20),
            state.validateWrite(
                "peer-a",
                generation,
                CharacteristicWriteType.WithoutResponse,
                payloadSize = 21,
            ),
        )

        state.onBusy("peer-a", generation)
        assertEquals(
            CharacteristicWriteResult.Backpressured,
            state.validateWrite(
                "peer-a",
                generation,
                CharacteristicWriteType.WithoutResponse,
                payloadSize = 1,
            ),
        )

        state.onDisconnected("peer-a", generation)
        assertEquals(
            CharacteristicWriteResult.Disconnected,
            state.validateWrite(
                "peer-a",
                generation,
                CharacteristicWriteType.WithoutResponse,
                payloadSize = 1,
            ),
        )
        assertTrue(state.capabilities.value.isEmpty())
    }

    @Test
    fun `late callbacks after disconnect cannot restore write capabilities`() {
        val state = AndroidCentralWriteState()
        val generation = state.onConnected("peer-a")

        state.onDisconnected("peer-a", generation)
        state.onMtuChanged("peer-a", generation, mtu = 247, successful = true)
        state.onReady("peer-a", generation)

        assertTrue(state.capabilities.value.isEmpty())
        assertEquals(
            CharacteristicWriteResult.Disconnected,
            state.validateWrite(
                "peer-a",
                generation,
                CharacteristicWriteType.WithoutResponse,
                payloadSize = 1,
            ),
        )
    }

    @Test
    fun `gate outcomes map to typed write results`() {
        assertEquals(
            CharacteristicWriteResult.Sent,
            CentralGattOperationOutcome.Success(0).toWriteResult(),
        )
        assertTrue(
            CentralGattOperationOutcome.StatusFailure(133)
                .toWriteResult() is CharacteristicWriteResult.Failed,
        )
        assertTrue(
            CentralGattOperationOutcome.Rejected(IllegalStateException())
                .toWriteResult() is CharacteristicWriteResult.Failed,
        )
        assertTrue(
            CentralGattOperationOutcome.TimedOut
                .toWriteResult() is CharacteristicWriteResult.Failed,
        )
        assertEquals(
            CharacteristicWriteResult.Disconnected,
            CentralGattOperationOutcome.Disconnected.toWriteResult(),
        )
    }
}
