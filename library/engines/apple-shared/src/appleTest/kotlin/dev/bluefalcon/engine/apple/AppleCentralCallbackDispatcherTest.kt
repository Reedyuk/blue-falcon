package dev.bluefalcon.engine.apple

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AppleCentralCallbackDispatcherTest {

    @Test
    fun `delegate callbacks are processed serially in delivery order`() = runTest {
        val dispatcher = AppleCentralCallbackDispatcher(backgroundScope)
        val releaseFirst = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()

        assertTrue(
            dispatcher.dispatch {
                order += "first-start"
                releaseFirst.await()
                order += "first-end"
            }
        )
        assertTrue(
            dispatcher.dispatch {
                order += "second"
            }
        )

        runCurrent()
        assertEquals(listOf("first-start"), order)

        releaseFirst.complete(Unit)
        runCurrent()
        assertEquals(listOf("first-start", "first-end", "second"), order)
    }

    @Test
    fun `one failed callback does not stop later delegate callbacks`() = runTest {
        val dispatcher = AppleCentralCallbackDispatcher(backgroundScope)
        val order = mutableListOf<String>()

        dispatcher.dispatch {
            order += "failed"
            error("callback failed")
        }
        dispatcher.dispatch {
            order += "next"
        }

        runCurrent()

        assertEquals(listOf("failed", "next"), order)
    }

    @Test
    fun `callback payload snapshot is independent from mutable native buffer`() {
        val nativeBuffer = byteArrayOf(1, 2, 3)

        val snapshot = snapshotCallbackPayload(nativeBuffer)
        nativeBuffer[0] = 9

        assertTrue(snapshot!!.contentEquals(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `native ownership rejects stale callback and stale disconnect after replacement`() {
        val ownership = AppleNativeConnectionOwnership<Any>()
        val oldPeripheral = Any()
        val newPeripheral = Any()

        ownership.connected("peer", oldPeripheral)
        ownership.connected("peer", newPeripheral)

        assertFalse(ownership.isActive("peer", oldPeripheral))
        assertTrue(ownership.isActive("peer", newPeripheral))
        assertFalse(ownership.disconnected("peer", oldPeripheral))
        assertTrue(ownership.isActive("peer", newPeripheral))
        assertTrue(ownership.disconnected("peer", newPeripheral))
        assertFalse(ownership.isActive("peer", newPeripheral))
    }
}
