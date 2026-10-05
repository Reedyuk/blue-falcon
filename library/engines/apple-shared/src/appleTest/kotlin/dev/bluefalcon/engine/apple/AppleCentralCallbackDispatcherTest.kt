package dev.bluefalcon.engine.apple

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AppleCentralCallbackDispatcherTest {
    @Test
    fun `empty native notification snapshot reaches callback worker`() = runTest {
        val dispatcher = AppleCentralCallbackDispatcher(backgroundScope)
        val delivered = mutableListOf<ByteArray>()
        val value = snapshotCallbackPayload(NSData().toByteArray())!!
        assertTrue(dispatcher.dispatch { delivered += value })
        runCurrent()
        assertEquals(1, delivered.size)
        assertTrue(delivered.single().isEmpty())
    }

    @Test
    fun `entered delegate callback cannot recapture a reconnect epoch`() = runTest {
        val ownership = AppleNativeConnectionOwnership<Any>()
        val native = Any()
        val old = ownership.connected("peer", native)
        val delegate = AppleNativeConnectionCallbacks(old, ownership)
        val dispatcher = AppleCentralCallbackDispatcher(backgroundScope)
        val events = mutableListOf<String>()
        delegate.forward { captured ->
            // Reconnect after the delegate's initial check, before engine dispatch.
            ownership.disconnected(old)
            val current = ownership.connected("peer", native)
            assertEquals(old, captured)
            assertTrue(ownership.isActive(current))
            dispatcher.dispatch {
                if (ownership.isActive(captured)) events += "stale callback"
            }
        }
        runCurrent()
        assertTrue(events.isEmpty())
        delegate.forward { error("Old delegate must remain inactive") }
    }

    @Test
    fun `captured ownership cannot become a newer epoch even when native object is reused`() {
        val ownership = AppleNativeConnectionOwnership<Any>()
        val native = Any()
        val old = ownership.connected("peer", native)
        val current = ownership.connected("peer", native)
        assertFalse(ownership.isActive(old))
        assertTrue(ownership.isActive(current))
        assertEquals(current, ownership.capture("peer", native))
        assertFalse(ownership.disconnected(old))
        assertTrue(ownership.isActive(current))
    }

    @Test
    fun `queued callbacks keep their captured epoch across replacement`() = runTest {
        val ownership = AppleNativeConnectionOwnership<Any>()
        val native = Any()
        ownership.connected("peer", native)
        val captured = ownership.capture("peer", native)!!
        val dispatcher = AppleCentralCallbackDispatcher(backgroundScope)
        val release = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        dispatcher.dispatch { release.await() }
        for (kind in listOf("write", "read", "subscription", "notification", "disconnect")) {
            dispatcher.dispatch {
                if (ownership.isActive(captured)) events += kind
            }
        }
        runCurrent()
        val current = ownership.connected("peer", native)
        release.complete(Unit)
        runCurrent()
        assertTrue(events.isEmpty())
        assertTrue(ownership.isActive(current))
        dispatcher.dispatch {
            if (ownership.isActive(current)) events += "current notification"
        }
        runCurrent()
        assertEquals(listOf("current notification"), events)
    }


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
