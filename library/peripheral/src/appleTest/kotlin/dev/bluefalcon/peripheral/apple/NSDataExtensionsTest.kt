package dev.bluefalcon.peripheral.apple

import platform.Foundation.NSData
import kotlin.test.Test
import kotlin.test.assertContentEquals

class NSDataExtensionsTest {
    @Test
    fun emptyDataConvertsToEmptyByteArray() {
        assertContentEquals(byteArrayOf(), NSData().toByteArray())
    }

    @Test
    fun conversionsDoNotShareMutableStorage() {
        val source = byteArrayOf(1, 2, 3)
        val data = source.toData()
        source[0] = 99

        val first = data.toByteArray()
        assertContentEquals(byteArrayOf(1, 2, 3), first)
        first[1] = 88
        assertContentEquals(byteArrayOf(1, 2, 3), data.toByteArray())
    }
}
