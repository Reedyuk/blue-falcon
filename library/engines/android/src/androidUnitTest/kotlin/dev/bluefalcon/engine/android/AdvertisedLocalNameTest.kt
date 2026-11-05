package dev.bluefalcon.engine.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AdvertisedLocalNameTest {
    private fun ad(type: Int, value: String): ByteArray {
        val bytes = value.encodeToByteArray()
        return byteArrayOf((bytes.size + 1).toByte(), type.toByte()) + bytes
    }

    private val flags = byteArrayOf(0x02, 0x01, 0x06)

    @Test fun `complete name is read`() {
        assertEquals("Sensor-A1", parseAdvertisedLocalName(flags + ad(0x09, "Sensor-A1")))
    }

    @Test fun `complete name wins over shortened name in any order`() {
        assertEquals("Sensor-A1", parseAdvertisedLocalName(ad(0x09, "Sensor-A1") + ad(0x08, "Sen")))
        assertEquals("Sensor-A1", parseAdvertisedLocalName(ad(0x08, "Sen") + ad(0x09, "Sensor-A1")))
    }

    @Test fun `shortened name is used without complete name`() {
        assertEquals("Sen", parseAdvertisedLocalName(flags + ad(0x08, "Sen")))
    }

    @Test fun `trailing zero padding is removed`() {
        assertEquals("Sensor", parseAdvertisedLocalName(ad(0x09, "Sensor") + byteArrayOf(0, 0, 0)))
        assertEquals("Sensor", parseAdvertisedLocalName(ad(0x09, "Sensor\u0000\u0000")))
    }

    @Test fun `zero padding complete name falls back to shortened name`() {
        assertEquals("Sen", parseAdvertisedLocalName(ad(0x09, "\u0000\u0000") + ad(0x08, "Sen")))
    }

    @Test fun `parser stops at zero length or overrun but keeps a name found before`() {
        assertEquals("Sen", parseAdvertisedLocalName(ad(0x08, "Sen") + byteArrayOf(0) + ad(0x09, "Sensor-A1")))
        assertEquals("Sen", parseAdvertisedLocalName(ad(0x08, "Sen") + byteArrayOf(0x09, 0x09, 0x42)))
    }

    @Test fun `invalid UTF-8 does not throw`() {
        val name = parseAdvertisedLocalName(byteArrayOf(0x03, 0x09, 0xC3.toByte(), 0x28))
        assertEquals(2, name?.length)
    }

    @Test fun `missing, empty or truncated name gives null`() {
        assertNull(parseAdvertisedLocalName(flags))
        assertNull(parseAdvertisedLocalName(byteArrayOf()))
        assertNull(parseAdvertisedLocalName(byteArrayOf(0x01, 0x09)))
        assertNull(parseAdvertisedLocalName(byteArrayOf(0x09, 0x09, 0x42)))
    }
}
