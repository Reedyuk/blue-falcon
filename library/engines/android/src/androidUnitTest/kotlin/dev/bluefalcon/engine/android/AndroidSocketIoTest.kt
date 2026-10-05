package dev.bluefalcon.engine.android

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidSocketIoTest {
    @Test fun `EOF closes native socket exactly once`() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.IO)
        val closes = AtomicInteger()
        val io = AndroidSocketIo(ByteArrayInputStream(byteArrayOf()), ByteArrayOutputStream(), scope, { closes.incrementAndGet() })
        withTimeout(5_000) { io.join() }
        assertFalse(io.isOpen)
        assertEquals(1, closes.get())
        io.close()
        assertEquals(1, closes.get())
        scope.cancel()
    }

    @Test fun `read error closes native socket`() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.IO)
        val closes = AtomicInteger()
        val input = object : InputStream() { override fun read(): Int = throw IOException("peer dropped") }
        val io = AndroidSocketIo(input, ByteArrayOutputStream(), scope, { closes.incrementAndGet() })
        withTimeout(5_000) { io.join() }
        assertFalse(io.isOpen)
        assertEquals(1, closes.get())
        scope.cancel()
    }

    @Test fun `parent cancellation closes native socket to unblock its reader`() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.IO)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closes = AtomicInteger()
        val input = object : InputStream() {
            override fun read(): Int { entered.countDown(); release.await(); return -1 }
        }
        val io = AndroidSocketIo(input, ByteArrayOutputStream(), scope, { closes.incrementAndGet(); release.countDown() })
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        scope.cancel()
        try {
            withTimeout(5_000) { io.join() }
            assertEquals(1, closes.get())
            assertFalse(io.isOpen)
        } finally { release.countDown(); io.close() }
    }

    @Test fun `write error closes native socket and reader`() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.IO)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closes = AtomicInteger()
        val input = object : InputStream() {
            override fun read(): Int { entered.countDown(); release.await(); return -1 }
        }
        val output = object : OutputStream() { override fun write(value: Int) = throw IOException("broken pipe") }
        val io = AndroidSocketIo(input, output, scope, { closes.incrementAndGet(); release.countDown() })
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        try {
            kotlin.test.assertFailsWith<IOException> { io.write(byteArrayOf(1)) }
            assertFalse(io.isOpen)
            assertEquals(1, closes.get())
            withTimeout(5_000) { io.join() }
        } finally { io.close(); release.countDown(); scope.cancel() }
    }
}
