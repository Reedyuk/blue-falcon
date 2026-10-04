package dev.bluefalcon.engine.apple

import dev.bluefalcon.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.*
import kotlin.test.*

@OptIn(ExperimentalForeignApi::class)
class AppleSocketLifecycleTest {
    @Test fun closeDetachesBothRealNativeStreamsAndRejectsFurtherIO() = runTest {
        val input = NSInputStream(data = byteArrayOf(1, 2).toData())
        val output = NSOutputStream.outputStreamToMemory()
        val socket = AppleL2CapSocket(input, output, 42, Peer)
        assertNotNull(input.delegate)
        assertNotNull(output.delegate)
        socket.close(); socket.close()
        assertFalse(socket.isOpen)
        assertNull(input.delegate)
        assertNull(output.delegate)
        assertEquals(NSStreamStatusClosed, input.streamStatus)
        assertEquals(NSStreamStatusClosed, output.streamStatus)
        assertFailsWith<L2capException> { socket.write(byteArrayOf(3)) }
    }
    @Test fun terminalNativeStreamEventAlsoDetachesTheOtherStream() {
        val input = NSInputStream(data = byteArrayOf(1).toData())
        val output = NSOutputStream.outputStreamToMemory()
        val socket = AppleL2CapSocket(input, output, 42, Peer)
        val callback = input.delegate!!
        callback.stream(input, NSStreamEventErrorOccurred)
        assertFalse(socket.isOpen)
        assertNull(output.delegate)
        assertEquals(NSStreamStatusClosed, output.streamStatus)
        callback.stream(input, NSStreamEventHasBytesAvailable)
        assertFalse(socket.isOpen)
    }
    @Test fun earlySocketFailureSurvivesOwnerRemovalAndRepeatedEngineClose() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val owner = AppleEngineLifecycle(scope) { }
        val input = NSInputStream(data = byteArrayOf(1).toData())
        val output = NSOutputStream.outputStreamToMemory()
        val failure = IllegalStateException("injected input close failure")
        val socket = AppleL2CapSocket(input, output, 42, Peer, closeNativeStream = { stream ->
            stream.close()
            if (stream === input) throw failure
        })
        var retained = true
        socket.onClosed = { cause -> owner.rememberCleanupFailure(cause); retained = false }
        assertSame(failure, assertFailsWith<IllegalStateException> { socket.close() })
        assertFalse(retained)
        assertSame(failure, assertFailsWith<IllegalStateException> { owner.close() })
        assertSame(failure, assertFailsWith<IllegalStateException> { owner.close() })
    }
    @Test fun closeReleasesBlockingNativeWriteBeforeWaitingForIODrain() = runTest {
        val entered = CompletableDeferred<Unit>()
        val condition = NSCondition()
        var released = false
        fun release() { condition.lock(); released = true; condition.broadcast(); condition.unlock() }
        val input = NSInputStream(data = byteArrayOf(1).toData())
        val output = NSOutputStream.outputStreamToMemory()
        val socket = AppleL2CapSocket(input, output, 42, Peer,
            closeNativeStream = { stream -> stream.close(); release() },
            writeNative = { bytes, offset ->
                entered.complete(Unit)
                condition.lock()
                try { while (!released) condition.wait() } finally { condition.unlock() }
                (bytes.size - offset).toLong()
            })
        val write = async(Dispatchers.Default) { socket.write(byteArrayOf(3)) }
        entered.await()
        val closing = async(Dispatchers.Default) { socket.close() }
        try { withContext(Dispatchers.Default) { withTimeout(2_000) { closing.await() } } }
        finally { release() }
        write.await()
        assertFalse(socket.isOpen)
        assertNull(input.delegate); assertNull(output.delegate)
    }
    @Test fun cancellingBlockedWriteClosesNativeStreamsAndRetiresFailure() = runTest {
        val entered = CompletableDeferred<Unit>()
        val condition = NSCondition()
        var released = false
        fun release() { condition.lock(); released = true; condition.broadcast(); condition.unlock() }
        val input = NSInputStream(data = byteArrayOf(1).toData())
        val output = NSOutputStream.outputStreamToMemory()
        val owner = AppleEngineLifecycle(CoroutineScope(SupervisorJob() + Dispatchers.Default)) { }
        val failure = IllegalStateException("cancel cleanup failure")
        val socket = AppleL2CapSocket(input, output, 42, Peer,
            closeNativeStream = { stream -> stream.close(); release(); if (stream === input) throw failure },
            writeNative = { bytes, offset ->
                entered.complete(Unit); condition.lock()
                try { while (!released) condition.wait() } finally { condition.unlock() }
                (bytes.size - offset).toLong()
            })
        var retained = true
        socket.onClosed = { cause -> owner.rememberCleanupFailure(cause); retained = false }
        val write = launch(Dispatchers.Default) { runCatching { socket.write(byteArrayOf(3)) } }
        entered.await()
        try {
            write.cancel()
            withContext(Dispatchers.Default) { withTimeout(2_000) { write.join() } }
            assertFalse(socket.isOpen); assertFalse(retained)
            assertNull(input.delegate); assertNull(output.delegate)
            assertSame(failure, assertFailsWith<IllegalStateException> { owner.close() })
        } finally { release(); runCatching { socket.close() }; write.cancelAndJoin() }
    }
    @Test fun failedConstructionClosesAlreadyOpenedAndUnopenedStreams() = runTest {
        val input = NSInputStream(data = byteArrayOf(1).toData())
        val output = NSOutputStream.outputStreamToMemory()
        val failure = IllegalStateException("output open failed")
        val cleanupFailure = IllegalStateException("partial constructor cleanup failed")
        val owner = AppleEngineLifecycle(CoroutineScope(SupervisorJob() + Dispatchers.Default)) { }
        val closed = mutableListOf<NSStream>()
        assertSame(failure, assertFailsWith<IllegalStateException> {
            AppleL2CapSocket(input, output, 42, Peer, closeNativeStream = { closed += it; it.close(); if (it === input) throw cleanupFailure }, onCleanupFailure = owner::rememberCleanupFailure, openNativeStream = { stream ->
                if (stream === output) throw failure else stream.open()
            })
        })
        assertNull(input.delegate); assertNull(output.delegate)
        assertEquals(NSStreamStatusClosed, input.streamStatus)
        assertEquals(NSStreamStatusNotOpen, output.streamStatus)
        assertEquals(listOf(input, output), closed)
        assertSame(cleanupFailure, assertFailsWith<IllegalStateException> { owner.close() })
    }
    @Test fun structuredChildFailureAlsoClosesBlockingNativeWrite() = runTest {
        val entered = CompletableDeferred<Unit>()
        val condition = NSCondition()
        var released = false
        fun release() { condition.lock(); released = true; condition.broadcast(); condition.unlock() }
        val input = NSInputStream(data = byteArrayOf(1).toData())
        val output = NSOutputStream.outputStreamToMemory()
        val socket = AppleL2CapSocket(input, output, 42, Peer,
            closeNativeStream = { it.close(); release() },
            writeNative = { bytes, offset ->
                entered.complete(Unit); condition.lock()
                try { while (!released) condition.wait() } finally { condition.unlock() }
                (bytes.size - offset).toLong()
            })
        var retired = false
        socket.onClosed = { retired = true }
        val result = CompletableDeferred<Throwable?>()
        val writer = launch(Dispatchers.Default) {
            result.complete(runCatching {
                coroutineScope {
                    launch { entered.await(); error("structured child failed") }
                    socket.write(byteArrayOf(3))
                }
            }.exceptionOrNull())
        }
        entered.await()
        try {
            withContext(Dispatchers.Default) { withTimeout(2_000) { writer.join() } }
            assertEquals("structured child failed", result.await()?.message)
            assertTrue(retired); assertFalse(socket.isOpen)
            assertNull(input.delegate); assertNull(output.delegate)
        } finally { release(); socket.close(); writer.cancelAndJoin() }
    }
    private object Peer : BluetoothPeripheral {
        override val name: String? = "peer"
        override val uuid = "peer"
        override val rssi: Float? = null
        override val mtuSize: Int? = null
        override val services = emptyList<BluetoothService>()
        override val characteristics = emptyList<BluetoothCharacteristic>()
    }
}
