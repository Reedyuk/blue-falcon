package dev.bluefalcon.peripheral.apple

import platform.Foundation.NSData
import kotlin.test.Test
import kotlin.test.assertContentEquals

class NSDataExtensionsTest {
    @Test
    fun emptyDataConvertsToEmptyByteArray() {
        assertContentEquals(byteArrayOf(), NSData().toByteArray())
    }
}
