package dev.bluefalcon.engine.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AndroidGattOwnershipTest {
    @Test
    fun `late connection and data callbacks cannot replace or affect the current GATT`() {
        val ownership = AndroidGattOwnership<Any>()
        val old = Any()
        val current = Any()
        val closed = mutableListOf<Any>()
        val events = mutableListOf<String>()
        ownership.track("address", old)
        ownership.track("address", current)?.let(closed::add)

        assertFalse(ownership.withCurrent(old) {
            ownership.track("address", it)?.let(closed::add)
            events += "connected"
        })
        assertFalse(ownership.withCurrent(old) { events += "disconnected" })
        assertFalse(ownership.withCurrent(old) { events += "notification" })
        assertFalse(ownership.forget(old))
        assertEquals(listOf(old), closed)
        assertTrue(events.isEmpty())
        assertSame(current, ownership.snapshot().single())
        assertTrue(ownership.withCurrent(current) { events += "current notification" })
        assertEquals(listOf("current notification"), events)
    }

    @Test
    fun `forgetting one device preserves other devices and rejects closed callbacks`() {
        val ownership = AndroidGattOwnership<Any>()
        val first = Any()
        val second = Any()
        ownership.track("first", first)
        ownership.track("second", second)
        assertTrue(ownership.forget(first))
        assertFalse(ownership.forget(first))
        assertFalse(ownership.withCurrent(first) { error("closed callback") })
        assertFalse(ownership.withCurrent(null) { error("null callback") })
        assertTrue(ownership.withCurrent(second) { assertSame(second, it) })
        assertEquals(listOf(second), ownership.snapshot())
    }
}
