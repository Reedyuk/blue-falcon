package dev.bluefalcon.engine.apple

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * ADR 0014: a read must suspend until it is resolved from the specific
 * `didUpdateValueForCharacteristic` callback that correlates to it, and must not be
 * satisfied by an unrelated notification for a different characteristic, nor drop that
 * unrelated notification.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppleCentralReadTest {

    @Test
    fun `read completes only from matching callback`() = runTest {
        val target = FakeReadTarget()
        val controller = AppleCentralWriteController(backgroundScope)
        controller.connected(target)

        val read = async { controller.read(target) }
        runCurrent()

        assertFalse(read.isCompleted)
        assertTrue(target.reads.isEmpty().not())
        assertFalse(
            controller.onCharacteristicValueReceived(
                peripheralUuid = target.peripheralUuid,
                characteristicUuid = "other-characteristic",
                value = byteArrayOf(9),
                failure = null,
            )
        )
        assertFalse(read.isCompleted)
        assertTrue(
            controller.onCharacteristicValueReceived(
                peripheralUuid = target.peripheralUuid,
                characteristicUuid = target.characteristicUuid,
                value = byteArrayOf(1, 2, 3),
                failure = null,
            )
        )
        val outcome = read.await()
        assertIs<AppleReadOutcome.Success>(outcome)
        assertTrue(outcome.value.contentEquals(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `read surfaces native callback errors and disconnects`() = runTest {
        val target = FakeReadTarget()
        val controller = AppleCentralWriteController(backgroundScope)
        controller.connected(target)

        val failed = async { controller.read(target) }
        runCurrent()
        controller.onCharacteristicValueReceived(
            peripheralUuid = target.peripheralUuid,
            characteristicUuid = target.characteristicUuid,
            value = null,
            failure = IllegalStateException("native failure"),
        )
        assertIs<AppleReadOutcome.Failed>(failed.await())

        val disconnected = async { controller.read(target) }
        runCurrent()
        controller.disconnected(target.peripheralUuid)
        assertEquals(AppleReadOutcome.Disconnected, disconnected.await())
    }

    @Test
    fun `disconnected target returns disconnected without native call`() = runTest {
        val target = FakeReadTarget(connected = false)
        val controller = AppleCentralWriteController(backgroundScope)

        val outcome = controller.read(target)

        assertEquals(AppleReadOutcome.Disconnected, outcome)
        assertTrue(target.reads.isEmpty())
    }

    @Test
    fun `callback captured for old generation cannot complete reconnected read`() = runTest {
        val target = FakeReadTarget()
        val controller = AppleCentralWriteController(backgroundScope)
        val oldConnection = controller.connected(target)
        controller.disconnected(target.peripheralUuid)
        controller.connected(target)
        val result = async { controller.read(target) }
        runCurrent()

        assertFalse(
            controller.onCharacteristicValueReceived(
                connection = oldConnection,
                characteristicUuid = target.characteristicUuid,
                value = byteArrayOf(1),
                failure = null,
            )
        )
        assertFalse(result.isCompleted)
        assertTrue(
            controller.onCharacteristicValueReceived(
                peripheralUuid = target.peripheralUuid,
                characteristicUuid = target.characteristicUuid,
                value = byteArrayOf(2),
                failure = null,
            )
        )
        val outcome = result.await()
        assertIs<AppleReadOutcome.Success>(outcome)
        assertTrue(outcome.value.contentEquals(byteArrayOf(2)))
    }

    private class FakeReadTarget(
        override val peripheralUuid: String = "peripheral-a",
        override val characteristicUuid: String =
            appleCharacteristicIdentity("service-a", "characteristic-a"),
        override var connected: Boolean = true,
    ) : AppleCentralReadTarget, AppleCentralWritePeer {
        val reads = mutableListOf<Unit>()

        override val canSendWithoutResponse: Boolean = true

        override fun maximumWriteValueLength(writeType: dev.bluefalcon.core.CharacteristicWriteType): Int = 128

        override fun readValue() {
            reads += Unit
        }
    }
}
