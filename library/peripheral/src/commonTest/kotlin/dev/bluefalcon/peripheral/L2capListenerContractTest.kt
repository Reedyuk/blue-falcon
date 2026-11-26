package dev.bluefalcon.peripheral

import dev.bluefalcon.core.InternalBlueFalconApi
import dev.bluefalcon.core.L2capException
import dev.bluefalcon.core.L2capInboundBuffer
import dev.bluefalcon.peripheral.fake.FakePeripheralBackend
import dev.bluefalcon.peripheral.internal.DefaultBlueFalconPeripheral
import dev.bluefalcon.peripheral.internal.DefaultL2capListener
import dev.bluefalcon.peripheral.internal.MaximumL2capListeners
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class, InternalBlueFalconApi::class)
class L2capListenerContractTest {

    @Test
    fun publishIsRejectedUnlessRunning() = runTest {
        val backend = FakePeripheralBackend()
        val peripheral = DefaultBlueFalconPeripheral(backend, coroutineContext)

        assertFailsWith<PeripheralLifecycleException> { peripheral.publishL2capChannel() }

        peripheral.start(TestConfig)
        peripheral.stop()
        assertFailsWith<PeripheralLifecycleException> { peripheral.publishL2capChannel() }
        assertTrue(backend.l2capServers.isEmpty())
        peripheral.close()
    }

    @Test
    fun unsupportedPlatformFailsWithoutCallingBackend() = runTest {
        val backend = FakePeripheralBackend(capabilities = PeripheralCapabilities.Unsupported)
        val peripheral = DefaultBlueFalconPeripheral(backend, coroutineContext)
        peripheral.start(TestConfig)

        assertFailsWith<L2capException> { peripheral.publishL2capChannel() }
        assertTrue(backend.l2capServers.isEmpty())
        peripheral.close()
    }

    @Test
    fun defaultInterfaceImplementationReportsUnsupported() = runTest {
        val peripheral = object : BlueFalconPeripheral by DefaultBlueFalconPeripheral(FakePeripheralBackend()) {
            override suspend fun publishL2capChannel(secure: Boolean): L2capListener =
                super<BlueFalconPeripheral>.publishL2capChannel(secure)
        }
        assertFailsWith<L2capException> { peripheral.publishL2capChannel() }
    }

    @Test
    fun publishedListenerReportsPsmAndDeliversAcceptedSockets() = runTest {
        val backend = FakePeripheralBackend()
        val peripheral = DefaultBlueFalconPeripheral(backend, coroutineContext)
        peripheral.start(TestConfig)

        val listener = peripheral.publishL2capChannel(secure = false)
        val server = backend.l2capServers.single()
        assertEquals(server.psm, listener.psm)
        assertFalse(listener.secure)
        assertFalse(server.secure)

        val accepted = server.accept("central-1")
        val delivered = listener.sockets.first()

        assertSame(accepted, delivered)
        assertEquals(PeripheralSessionId("central-1"), delivered.sessionId)
        assertEquals("central-1", delivered.peripheral.uuid)
        assertTrue(delivered.peripheral.services.isEmpty())
        peripheral.close()
    }

    @Test
    fun stopClosesListenerServerAndEverySocket() = runTest {
        val backend = FakePeripheralBackend()
        val peripheral = DefaultBlueFalconPeripheral(backend, coroutineContext)
        peripheral.start(TestConfig)
        val listener = peripheral.publishL2capChannel()
        val server = backend.l2capServers.single()
        val delivered = server.accept("central-1")
        listener.sockets.first()
        val queued = server.accept("central-2")
        val collected = async { listener.sockets.toList() }
        runCurrent()

        peripheral.stop()

        assertFalse(listener.isOpen)
        assertTrue(server.isClosed)
        assertFalse(delivered.isOpen)
        assertFalse(queued.isOpen)
        // The collector already owned the second socket; stop still closed it and completed the stream.
        assertEquals(listOf<L2capServerSocket>(queued), collected.await())
        peripheral.close()
    }

    @Test
    fun closeClosesListenersLikeStop() = runTest {
        val backend = FakePeripheralBackend()
        val peripheral = DefaultBlueFalconPeripheral(backend, coroutineContext)
        peripheral.start(TestConfig)
        val listener = peripheral.publishL2capChannel()
        val socket = backend.l2capServers.single().accept("central-1")

        peripheral.close()

        assertFalse(listener.isOpen)
        assertTrue(backend.l2capServers.single().isClosed)
        assertFalse(socket.isOpen)
    }

    @Test
    fun listenerCloseIsIdempotentAndFreesManagerCapacity() = runTest {
        val backend = FakePeripheralBackend()
        val peripheral = DefaultBlueFalconPeripheral(backend, coroutineContext)
        peripheral.start(TestConfig)
        val listeners = List(MaximumL2capListeners) { peripheral.publishL2capChannel() }

        assertFailsWith<L2capException> { peripheral.publishL2capChannel() }

        listeners.first().close()
        listeners.first().close()
        assertEquals(1, backend.l2capServers.first().closeCalls)
        peripheral.publishL2capChannel()
        assertEquals(MaximumL2capListeners + 1, backend.l2capServers.size)
        peripheral.close()
    }

    @Test
    fun socketsBeyondCapacityAreClosedImmediately() = runTest {
        val listener = DefaultL2capListener(secure = true, maximumSockets = 2)
        val server = dev.bluefalcon.peripheral.fake.FakeL2capServer(psm = 0x81, secure = true, sink = listener)
        listener.attach(server)

        val first = server.accept("a")
        val second = server.accept("b")
        val rejected = server.accept("c")

        assertTrue(first.isOpen)
        assertTrue(second.isOpen)
        assertFalse(rejected.isOpen)
        assertEquals(2, listener.openSocketCount)

        // A closed socket frees capacity for the next central.
        first.close()
        assertEquals(1, listener.openSocketCount)
        assertTrue(server.accept("d").isOpen)
        listener.close()
    }

    @Test
    fun serverFailureClosesListenerAndFailsSocketStream() = runTest {
        val backend = FakePeripheralBackend()
        val peripheral = DefaultBlueFalconPeripheral(backend, coroutineContext)
        peripheral.start(TestConfig)
        val listener = peripheral.publishL2capChannel()
        val server = backend.l2capServers.single()
        val socket = server.accept("central-1")
        listener.sockets.first()
        val failure = CompletableDeferred<Throwable>()
        launch(UnconfinedTestDispatcher(testScheduler)) {
            try {
                listener.sockets.collect { }
            } catch (cause: Throwable) {
                failure.complete(cause)
            }
        }

        server.fail(IllegalStateException("adapter off"))

        assertTrue(failure.await() is L2capException)
        assertFalse(listener.isOpen)
        assertFalse(socket.isOpen)
        assertTrue(server.isClosed)
        // A failed listener no longer counts against the manager limit.
        repeat(MaximumL2capListeners) { peripheral.publishL2capChannel() }
        peripheral.close()
    }

    @Test
    fun publishFailureDoesNotRegisterListener() = runTest {
        val backend = FakePeripheralBackend().apply { l2capPublishFailure = L2capException("refused") }
        val peripheral = DefaultBlueFalconPeripheral(backend, coroutineContext)
        peripheral.start(TestConfig)

        assertFailsWith<L2capException> { peripheral.publishL2capChannel() }

        backend.l2capPublishFailure = null
        repeat(MaximumL2capListeners) { peripheral.publishL2capChannel() }
        peripheral.close()
    }

    @Test
    fun inboundBufferKeepsChunksUntilFirstCollectorThenFinishes() = runTest {
        var finished = false
        val inbound = L2capInboundBuffer(backgroundScope, capacity = 4, onInputFinished = { finished = true })

        assertTrue(inbound.trySend(byteArrayOf(1)))
        assertTrue(inbound.trySend(byteArrayOf(2)))
        inbound.finish()
        runCurrent()
        assertFalse(finished)

        val chunks = inbound.incoming.take(2).toList()

        assertContentEquals(byteArrayOf(1), chunks[0])
        assertContentEquals(byteArrayOf(2), chunks[1])
        runCurrent()
        assertTrue(finished)
    }

    @Test
    fun inboundBufferRejectsWhenFull() = runTest {
        val inbound = L2capInboundBuffer(backgroundScope, capacity = 1)

        assertTrue(inbound.trySend(byteArrayOf(1)))
        // The forwarder may hold one chunk while it waits for a subscriber, so the bound is capacity + 1.
        inbound.trySend(byteArrayOf(2))
        assertFalse(inbound.trySend(byteArrayOf(3)))
    }

    @Test
    fun capabilitiesDefaultToNoL2capServer() {
        assertFalse(PeripheralCapabilities.Unsupported.l2capServer)
    }

    private companion object {
        val TestConfig = PeripheralConfig(advertiseConfig = AdvertiseConfig())
    }
}
