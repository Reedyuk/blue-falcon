package dev.bluefalcon.engine.apple

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
    fun `terminal retirement remains admissible after callback payload storage is full`() = runTest {
        val dispatcher = AppleCentralCallbackDispatcher(backgroundScope, 2, 512)
        val release = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        assertTrue(dispatcher.dispatch(512) { release.await() })
        runCurrent()
        assertTrue(dispatcher.dispatch { order += "queued data" })
        assertFalse(dispatcher.dispatch {})
        assertTrue(dispatcher.dispatchTerminal { order += "terminal cleanup" })
        release.complete(Unit); runCurrent()
        assertEquals(listOf("terminal cleanup", "queued data"), order)
        assertEquals(0, dispatcher.status.value.retainedCallbacks)
        dispatcher.close()
        assertFalse(dispatcher.dispatchTerminal {})
    }

    @Test fun `terminal slots include active cleanup and close discards bounded pending cleanups`() = runTest {
        val dispatcher = AppleCentralCallbackDispatcher(backgroundScope, maximumTerminalCallbacks = 2)
        val release = CompletableDeferred<Unit>()
        assertTrue(dispatcher.dispatchTerminal { release.await() })
        runCurrent()
        assertTrue(dispatcher.dispatchTerminal {})
        assertFalse(dispatcher.dispatchTerminal {})
        assertEquals(2, dispatcher.status.value.retainedTerminalCallbacks)
        dispatcher.close()
        assertEquals(0, dispatcher.status.value.retainedTerminalCallbacks)
        assertFalse(dispatcher.dispatchTerminal {})
    }

    @Test fun `rejected stale callback does not retire replacement token`() = runTest {
        val dispatcher = AppleCentralCallbackDispatcher(backgroundScope, maximumCallbacks = 1)
        val ownership = AppleNativeConnectionOwnership<Any>()
        val old = ownership.connected("peer", Any())
        val replacement = ownership.connected("peer", Any())
        assertTrue(dispatcher.dispatch {})
        assertFalse(dispatcher.dispatchOwned(old, ownership, onRejected = { error("Old callback retired replacement") }) {})
        assertTrue(ownership.isActive(replacement))
        dispatcher.close()
    }

    @Test
    fun `payload byte admission includes stalled callback and is observable`() = runTest {
        val dispatcher = AppleCentralCallbackDispatcher(backgroundScope, 10, 1024)
        val release = CompletableDeferred<Unit>()
        val first = ByteArray(512)
        assertTrue(dispatcher.dispatch(first.size) { release.await(); first[0] = 1 })
        runCurrent()
        val second = ByteArray(512)
        assertTrue(dispatcher.dispatch(second.size) { second[0] = 1 })
        assertFalse(dispatcher.dispatch(1) {})
        assertFalse(dispatcher.dispatch(1025) {})
        assertEquals(2, dispatcher.status.value.retainedCallbacks)
        assertEquals(1024, dispatcher.status.value.retainedPayloadBytes)
        assertEquals(2, dispatcher.status.value.rejectedCallbacks)
        release.complete(Unit)
        runCurrent()
        assertEquals(0, dispatcher.status.value.retainedCallbacks)
        assertEquals(0, dispatcher.status.value.retainedPayloadBytes)
        assertTrue(dispatcher.dispatch(1024) {})
    }

    @Test
    fun `failed and reentrant callbacks release admission without a staging queue`() = runTest {
        val dispatcher = AppleCentralCallbackDispatcher(backgroundScope, 1, 512)
        assertTrue(dispatcher.dispatch(512) {
            assertFalse(dispatcher.dispatch {})
            error("malformed callback")
        })
        runCurrent()
        assertEquals(0, dispatcher.status.value.retainedCallbacks)
        assertEquals(0, dispatcher.status.value.retainedPayloadBytes)
        assertEquals(1, dispatcher.status.value.rejectedCallbacks)
        assertTrue(dispatcher.dispatch(512) {})
        runCurrent()
    }

    @Test
    fun `close releases running and queued payloads and rejects later ingress`() = runTest {
        val dispatcher = AppleCentralCallbackDispatcher(backgroundScope, 2, 1024)
        val never = CompletableDeferred<Unit>()
        assertTrue(dispatcher.dispatch(512) { never.await() })
        runCurrent()
        assertTrue(dispatcher.dispatch(512) { error("Queued callback must be discarded") })
        dispatcher.close()
        dispatcher.close()
        assertTrue(dispatcher.status.value.closed)
        assertEquals(0, dispatcher.status.value.retainedCallbacks)
        assertEquals(0, dispatcher.status.value.retainedPayloadBytes)
        assertFalse(dispatcher.dispatch {})
    }

    @Test
    fun `cancelled parent before worker starts still closes ingress`() = runTest {
        val parent = Job()
        val dispatcher = AppleCentralCallbackDispatcher(CoroutineScope(coroutineContext + parent))
        assertTrue(dispatcher.dispatch(512) { error("Worker must not start") })
        parent.cancel()
        runCurrent()
        assertTrue(dispatcher.status.value.closed)
        assertEquals(0, dispatcher.status.value.retainedPayloadBytes)
        assertEquals(0, dispatcher.status.value.retainedCallbacks)
        assertFalse(dispatcher.dispatch {})
    }

    @Test
    fun `stalled callback worker cannot retain arbitrary callback items`() = runTest {
        val dispatcher = AppleCentralCallbackDispatcher(backgroundScope)
        val release = CompletableDeferred<Unit>()
        assertTrue(dispatcher.dispatch { release.await() })
        runCurrent()
        var accepted = 1
        repeat(10_000) {
            if (dispatcher.dispatch {}) accepted++
        }
        assertTrue(accepted <= 256, "Retained $accepted callbacks while the worker was stalled")
        release.complete(Unit)
        runCurrent()
        assertTrue(dispatcher.dispatch {})
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
