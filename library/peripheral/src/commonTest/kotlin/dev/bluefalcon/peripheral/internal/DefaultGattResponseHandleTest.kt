package dev.bluefalcon.peripheral.internal

import dev.bluefalcon.peripheral.GattResponseResult
import dev.bluefalcon.peripheral.GattResponseStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DefaultGattResponseHandleTest {

    @Test
    fun firstResponseWinsAndLaterResponseIsRejected() = runTest {
        val responses = mutableListOf<GattResponseStatus>()
        val handle = DefaultGattResponseHandle { status, _ -> responses += status }

        assertEquals(
            GattResponseResult.Responded,
            handle.respond(GattResponseStatus.Success),
        )
        assertEquals(
            GattResponseResult.AlreadyResponded,
            handle.respond(GattResponseStatus.UnlikelyError),
        )
        assertEquals(listOf(GattResponseStatus.Success), responses)
    }

    @Test
    fun concurrentResponsesInvokePlatformExactlyOnce() = runTest {
        var responseCount = 0
        val handle = DefaultGattResponseHandle { _, _ -> responseCount++ }

        val results = listOf(
            async { handle.respond(GattResponseStatus.Success) },
            async { handle.respond(GattResponseStatus.Success) },
        ).awaitAll()

        assertEquals(1, results.count { it == GattResponseResult.Responded })
        assertEquals(1, results.count { it == GattResponseResult.AlreadyResponded })
        assertEquals(1, responseCount)
    }

    @Test
    fun expiredHandleRejectsApplicationResponse() = runTest {
        var responseCount = 0
        val handle = DefaultGattResponseHandle { _, _ -> responseCount++ }

        assertTrue(handle.expire())
        assertEquals(
            GattResponseResult.Expired,
            handle.respond(GattResponseStatus.Success),
        )
        assertFalse(handle.expire())
        assertEquals(0, responseCount)
    }

    @Test
    fun responseValueIsCopiedBeforePlatformInvocation() = runTest {
        var received: ByteArray? = null
        val source = byteArrayOf(1, 2, 3)
        val handle = DefaultGattResponseHandle { _, value ->
            source[0] = 9
            received = value
        }

        handle.respond(GattResponseStatus.Success, source)

        assertContentEquals(byteArrayOf(1, 2, 3), received)
    }
}
