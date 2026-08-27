package dev.bluefalcon.plugins.retry

import dev.bluefalcon.core.plugin.RetryableOperation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest

/**
 * Tests for the [RetryPlugin]'s [RetryCapable] decision logic - i.e. that it produces the
 * expected exponential backoff delays and respects its configured retry budget and predicates.
 */
class RetryPluginTest {

    @Test
    fun `retries connect while attempts remain and applies exponential backoff`() = runTest {
        val plugin = RetryPlugin.create {
            maxRetries = 3
            initialDelay = 100.milliseconds
            backoffMultiplier = 2.0
            maxDelay = 10.seconds
        }
        val error = IllegalStateException("boom")

        val firstRetryDelay = plugin.retryDelay(RetryableOperation.CONNECT, attempt = 0, error)
        val secondRetryDelay = plugin.retryDelay(RetryableOperation.CONNECT, attempt = 1, error)
        val thirdRetryDelay = plugin.retryDelay(RetryableOperation.CONNECT, attempt = 2, error)

        assertEquals(100.milliseconds, firstRetryDelay)
        assertEquals(200.milliseconds, secondRetryDelay)
        // maxRetries = 3 means 3 total attempts (1 initial + 2 retries); attempt index 2 has
        // already exhausted the budget so no further retry should be requested.
        assertNull(thirdRetryDelay)
    }

    @Test
    fun `caps backoff delay at maxDelay`() = runTest {
        val plugin = RetryPlugin.create {
            maxRetries = 10
            initialDelay = 1.seconds
            backoffMultiplier = 10.0
            maxDelay = 5.seconds
        }
        val error = IllegalStateException("boom")

        val delay = plugin.retryDelay(RetryableOperation.CONNECT, attempt = 3, error)

        assertEquals(5.seconds, delay)
    }

    @Test
    fun `does not retry when disabled for that operation type`() = runTest {
        val plugin = RetryPlugin.create {
            maxRetries = 5
            retryConnect = false
        }
        val error = IllegalStateException("boom")

        assertNull(plugin.retryDelay(RetryableOperation.CONNECT, attempt = 0, error))
        // Reads remain enabled by default.
        assertEquals(500.milliseconds, plugin.retryDelay(RetryableOperation.READ, attempt = 0, error))
    }

    @Test
    fun `does not retry when retryOn predicate rejects the error`() = runTest {
        class RetryableError : Exception()

        val plugin = RetryPlugin.create {
            retryOn = { it is RetryableError }
        }

        assertNull(plugin.retryDelay(RetryableOperation.WRITE, attempt = 0, IllegalStateException()))
        assertEquals(500.milliseconds, plugin.retryDelay(RetryableOperation.WRITE, attempt = 0, RetryableError()))
    }

    @Test
    fun `maxRetries of one performs no retries`() = runTest {
        val plugin = RetryPlugin.create {
            maxRetries = 1
        }

        assertNull(plugin.retryDelay(RetryableOperation.CONNECT, attempt = 0, IllegalStateException()))
    }
}
