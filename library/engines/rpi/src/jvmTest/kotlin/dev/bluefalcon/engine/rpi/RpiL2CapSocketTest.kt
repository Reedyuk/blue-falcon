package dev.bluefalcon.engine.rpi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for the L2CAP CoC SDU-length framing in [RpiL2CapSocket].
 *
 * BlueZ surfaces the 2-octet little-endian SDU-length L-field to userspace on
 * the raw socket, so the socket must prepend it on write ([RpiL2CapSocket.frameSdu])
 * and strip it on read ([RpiL2CapSocket.stripSduLength]).
 */
class RpiL2CapSocketTest {

    @Test
    fun `frameSdu prepends the 2-byte little-endian payload length`() {
        val payload = byteArrayOf(0x10, 0x20, 0x30)
        val sdu = RpiL2CapSocket.frameSdu(payload)

        assertEquals(payload.size + 2, sdu.size)
        // length lo, length hi, then the untouched payload
        assertEquals(0x03, sdu[0].toInt() and 0xFF)
        assertEquals(0x00, sdu[1].toInt() and 0xFF)
        assertTrue(payload.contentEquals(sdu.copyOfRange(2, sdu.size)))
    }

    @Test
    fun `frameSdu encodes lengths above 255 as little-endian`() {
        val payload = ByteArray(300) { it.toByte() }
        val sdu = RpiL2CapSocket.frameSdu(payload)

        // 300 == 0x012C -> lo = 0x2C, hi = 0x01
        assertEquals(0x2C, sdu[0].toInt() and 0xFF)
        assertEquals(0x01, sdu[1].toInt() and 0xFF)
        assertEquals(302, sdu.size)
    }

    @Test
    fun `frameSdu of an empty payload is just the zero length field`() {
        val sdu = RpiL2CapSocket.frameSdu(ByteArray(0))

        assertEquals(2, sdu.size)
        assertEquals(0x00, sdu[0].toInt() and 0xFF)
        assertEquals(0x00, sdu[1].toInt() and 0xFF)
    }

    @Test
    fun `stripSduLength drops the 2-byte length field and returns the payload`() {
        val sdu = byteArrayOf(0x03, 0x00, 0x10, 0x20, 0x30)
        val payload = RpiL2CapSocket.stripSduLength(sdu)

        assertTrue(byteArrayOf(0x10, 0x20, 0x30).contentEquals(payload))
    }

    @Test
    fun `stripSduLength of frameSdu round-trips the payload byte-for-byte`() {
        val payload = "hello l2cap".encodeToByteArray()
        val roundTripped = RpiL2CapSocket.stripSduLength(RpiL2CapSocket.frameSdu(payload))

        assertTrue(payload.contentEquals(roundTripped))
    }

    @Test
    fun `stripSduLength of a bare length field yields an empty payload`() {
        val payload = RpiL2CapSocket.stripSduLength(byteArrayOf(0x00, 0x00))

        assertEquals(0, payload?.size)
    }

    @Test
    fun `stripSduLength of a buffer shorter than the length field is null`() {
        assertNull(RpiL2CapSocket.stripSduLength(byteArrayOf(0x01)))
        assertNull(RpiL2CapSocket.stripSduLength(ByteArray(0)))
    }
}
