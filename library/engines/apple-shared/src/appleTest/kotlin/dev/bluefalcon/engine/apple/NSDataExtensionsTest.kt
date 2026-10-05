package dev.bluefalcon.engine.apple

import platform.Foundation.NSData
import kotlin.test.Test
import kotlin.test.assertContentEquals

class NSDataExtensionsTest {
    @Test
    fun emptyNativeValueConvertsToEmptyByteArray() {
        assertContentEquals(byteArrayOf(), NSData().toByteArray())
    }

    @Test
    fun oneByteNativeValueIsCopiedExactly() {
        assertContentEquals(byteArrayOf(0x80.toByte()), byteArrayOf(0x80.toByte()).toData().toByteArray())
    }

    @Test
    fun largerNativeValueIsCopiedExactly() {
        val source = ByteArray(512) { it.toByte() }
        assertContentEquals(source, source.toData().toByteArray())
    }

    @Test
    fun conversionDoesNotShareMutableStorage() {
        val source = byteArrayOf(1, 2, 3)
        val native = source.toData()
        source[0] = 99
        val first = native.toByteArray()
        assertContentEquals(byteArrayOf(1, 2, 3), first)
        first[1] = 88
        assertContentEquals(byteArrayOf(1, 2, 3), native.toByteArray())
    }
}
