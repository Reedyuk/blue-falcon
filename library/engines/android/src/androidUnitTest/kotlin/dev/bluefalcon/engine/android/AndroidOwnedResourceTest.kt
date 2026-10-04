package dev.bluefalcon.engine.android

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFails

class AndroidOwnedResourceTest {
    private class Native {
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        val closes = AtomicInteger()
        fun connect() { entered.countDown(); released.await() }
        fun close() { closes.incrementAndGet(); released.countDown() }
    }

    @Test fun `destroy during native connect closes pending socket and joins opener`() = runBlocking { supervisorScope {
        val lifetime = AndroidEngineLifecycle(Job())
        val scope = CoroutineScope(lifetime.job + Dispatchers.IO)
        val native = Native()
        val opening = async {
            openAndroidOwnedResource(lifetime, scope, { native }, { it.connect() }, { it.close() })
        }
        kotlinx.coroutines.yield()
        assertTrue(native.entered.await(5, TimeUnit.SECONDS))
        try {
            withTimeout(5_000) { lifetime.close() }
            assertFails { withTimeout(5_000) { opening.await() } }
            assertEquals(1, native.closes.get())
            assertTrue(lifetime.job.isCompleted)
        } finally { native.released.countDown(); opening.cancel(); lifetime.destroy() }
    } }

    @Test fun `caller cancellation closes socket while native connect is blocked`() = runBlocking { supervisorScope {
        val lifetime = AndroidEngineLifecycle(Job())
        val scope = CoroutineScope(lifetime.job + Dispatchers.IO)
        val native = Native()
        val opening = async {
            openAndroidOwnedResource(lifetime, scope, { native }, { it.connect() }, { it.close() })
        }
        kotlinx.coroutines.yield()
        assertTrue(native.entered.await(5, TimeUnit.SECONDS))
        try {
            opening.cancel()
            withTimeout(5_000) { opening.join() }
            assertEquals(1, native.closes.get())
        } finally { native.released.countDown(); lifetime.close() }
    } }

    @Test fun `native close failure remains observable after resource retirement`() = runBlocking {
        val lifetime = AndroidEngineLifecycle(Job())
        val scope = CoroutineScope(lifetime.job + Dispatchers.IO)
        val owner = openAndroidOwnedResource(lifetime, scope, { Any() }, {}, { error("native close failed") })
        kotlin.test.assertFailsWith<IllegalStateException> { owner.close() }
        val failure = kotlin.test.assertFailsWith<IllegalStateException> { lifetime.close() }
        assertEquals("native close failed", failure.cause?.message)
        assertTrue(lifetime.job.isCompleted)
        val repeated = kotlin.test.assertFailsWith<IllegalStateException> { lifetime.close() }
        kotlin.test.assertSame(failure, repeated)
    }

    @Test fun `native connect failure closes newly created socket`() = runBlocking { supervisorScope {
        val lifetime = AndroidEngineLifecycle(Job())
        val scope = CoroutineScope(lifetime.job + Dispatchers.IO)
        val native = Native()
        assertFails {
            openAndroidOwnedResource(lifetime, scope, { native }, { error("connect failed") }, { it.close() })
        }
        assertEquals(1, native.closes.get())
        lifetime.close()
        assertEquals(1, native.closes.get())
    } }
}
