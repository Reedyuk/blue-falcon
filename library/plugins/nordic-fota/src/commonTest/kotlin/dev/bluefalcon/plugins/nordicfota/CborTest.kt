package dev.bluefalcon.plugins.nordicfota

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CborTest {

    @Test
    fun encodeEmptyMap() {
        val result = CborEncoder.encodeMap(emptyMap())
        // CBOR empty map: 0xA0
        assertEquals(1, result.size)
        assertEquals(0xA0.toByte(), result[0])
    }

    @Test
    fun encodeMapWithStringValue() {
        val result = CborEncoder.encodeMap(mapOf("d" to "hello"))
        assertNotNull(result)
        assertTrue(result.isNotEmpty())

        // Decode back and verify
        val decoded = CborDecoder.decodeMap(result)
        assertNotNull(decoded)
        assertEquals("hello", decoded["d"])
    }

    @Test
    fun encodeMapWithIntValue() {
        val result = CborEncoder.encodeMap(mapOf("off" to 0))
        assertNotNull(result)

        val decoded = CborDecoder.decodeMap(result)
        assertNotNull(decoded)
        assertEquals(0L, decoded["off"])
    }

    @Test
    fun encodeMapWithByteArrayValue() {
        val data = byteArrayOf(0x01, 0x02, 0x03)
        val result = CborEncoder.encodeMap(mapOf("data" to data))
        assertNotNull(result)

        val decoded = CborDecoder.decodeMap(result)
        assertNotNull(decoded)
        val decodedData = decoded["data"] as ByteArray
        assertTrue(data.contentEquals(decodedData))
    }

    @Test
    fun encodeMapWithBooleanValue() {
        val result = CborEncoder.encodeMap(mapOf("confirm" to true))
        assertNotNull(result)

        val decoded = CborDecoder.decodeMap(result)
        assertNotNull(decoded)
        assertEquals(true, decoded["confirm"])
    }

    @Test
    fun encodeMapWithMultipleEntries() {
        val result = CborEncoder.encodeMap(
            mapOf(
                "off" to 100,
                "data" to byteArrayOf(0xAA.toByte()),
                "len" to 1024
            )
        )
        assertNotNull(result)

        val decoded = CborDecoder.decodeMap(result)
        assertNotNull(decoded)
        assertEquals(100L, decoded["off"])
        assertEquals(1024L, decoded["len"])
        val decodedData = decoded["data"] as ByteArray
        assertEquals(1, decodedData.size)
        assertEquals(0xAA.toByte(), decodedData[0])
    }

    @Test
    fun decodeInvalidDataReturnsNull() {
        assertNull(CborDecoder.decodeMap(byteArrayOf()))
        assertNull(CborDecoder.decodeMap(byteArrayOf(0xFF.toByte())))
    }

    @Test
    fun roundTripLargeOffset() {
        val result = CborEncoder.encodeMap(mapOf("off" to 65535))
        val decoded = CborDecoder.decodeMap(result)
        assertNotNull(decoded)
        assertEquals(65535L, decoded["off"])
    }
}
