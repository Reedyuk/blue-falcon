package dev.bluefalcon.plugins.nordicfota

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class SmpMessageTest {

    @Test
    fun buildRequestHasCorrectHeaderSize() {
        SmpMessage.resetSequenceNumber()
        val message = SmpMessage.buildRequest(
            op = SmpConstants.OP_READ,
            group = SmpConstants.GROUP_IMAGE,
            commandId = SmpConstants.IMAGE_STATE
        )
        // Header (8 bytes) + empty CBOR map (1 byte for 0xA0)
        assertTrue(message.size >= SmpConstants.HEADER_SIZE)
    }

    @Test
    fun buildRequestHeaderFields() {
        SmpMessage.resetSequenceNumber()
        val message = SmpMessage.buildRequest(
            op = SmpConstants.OP_WRITE,
            group = SmpConstants.GROUP_IMAGE,
            commandId = SmpConstants.IMAGE_UPLOAD
        )

        assertEquals(SmpConstants.OP_WRITE.toByte(), message[0]) // op
        assertEquals(0.toByte(), message[1]) // flags
        // bytes 2-3: payload length (big-endian)
        assertEquals(0.toByte(), message[4]) // group high byte
        assertEquals(SmpConstants.GROUP_IMAGE.toByte(), message[5]) // group low byte
        assertEquals(0.toByte(), message[6]) // sequence number (first call)
        assertEquals(SmpConstants.IMAGE_UPLOAD.toByte(), message[7]) // command ID
    }

    @Test
    fun buildRequestSequenceNumberIncrements() {
        SmpMessage.resetSequenceNumber()
        val msg1 = SmpMessage.buildRequest(op = 0, group = 0, commandId = 0)
        val msg2 = SmpMessage.buildRequest(op = 0, group = 0, commandId = 0)
        val msg3 = SmpMessage.buildRequest(op = 0, group = 0, commandId = 0)

        assertEquals(0.toByte(), msg1[6])
        assertEquals(1.toByte(), msg2[6])
        assertEquals(2.toByte(), msg3[6])
    }

    @Test
    fun buildImageUploadFirstChunkIncludesLength() {
        SmpMessage.resetSequenceNumber()
        val imageData = ByteArray(100) { it.toByte() }
        val message = SmpMessage.buildImageUploadRequest(
            imageData = imageData,
            offset = 0,
            chunkSize = 50
        )

        // Parse the CBOR payload
        val payload = message.copyOfRange(SmpConstants.HEADER_SIZE, message.size)
        val decoded = CborDecoder.decodeMap(payload)
        assertNotNull(decoded)
        assertEquals(0L, decoded["off"])
        assertEquals(100L, decoded["len"])
        assertEquals(0L, decoded["image"])
        val data = decoded["data"] as ByteArray
        assertEquals(50, data.size)
    }

    @Test
    fun buildImageUploadSubsequentChunkExcludesLength() {
        SmpMessage.resetSequenceNumber()
        val imageData = ByteArray(100) { it.toByte() }
        val message = SmpMessage.buildImageUploadRequest(
            imageData = imageData,
            offset = 50,
            chunkSize = 50
        )

        val payload = message.copyOfRange(SmpConstants.HEADER_SIZE, message.size)
        val decoded = CborDecoder.decodeMap(payload)
        assertNotNull(decoded)
        assertEquals(50L, decoded["off"])
        assertNull(decoded["len"]) // len only in first chunk
        val data = decoded["data"] as ByteArray
        assertEquals(50, data.size)
        // Verify data content starts at offset 50
        assertEquals(50.toByte(), data[0])
    }

    @Test
    fun buildImageUploadLastChunkHandlesRemainder() {
        SmpMessage.resetSequenceNumber()
        val imageData = ByteArray(75) { it.toByte() }
        val message = SmpMessage.buildImageUploadRequest(
            imageData = imageData,
            offset = 50,
            chunkSize = 50
        )

        val payload = message.copyOfRange(SmpConstants.HEADER_SIZE, message.size)
        val decoded = CborDecoder.decodeMap(payload)
        assertNotNull(decoded)
        val data = decoded["data"] as ByteArray
        assertEquals(25, data.size) // Only 25 bytes remaining
    }

    @Test
    fun buildImageConfirmRequest() {
        SmpMessage.resetSequenceNumber()
        val message = SmpMessage.buildImageConfirmRequest()

        assertEquals(SmpConstants.OP_WRITE.toByte(), message[0])
        assertEquals(SmpConstants.GROUP_IMAGE.toByte(), message[5])
        assertEquals(SmpConstants.IMAGE_STATE.toByte(), message[7])

        val payload = message.copyOfRange(SmpConstants.HEADER_SIZE, message.size)
        val decoded = CborDecoder.decodeMap(payload)
        assertNotNull(decoded)
        assertEquals(true, decoded["confirm"])
    }

    @Test
    fun buildResetRequest() {
        SmpMessage.resetSequenceNumber()
        val message = SmpMessage.buildResetRequest()

        assertEquals(SmpConstants.OP_WRITE.toByte(), message[0])
        assertEquals(SmpConstants.GROUP_OS.toByte(), message[5])
        assertEquals(SmpConstants.OS_RESET.toByte(), message[7])
    }

    @Test
    fun buildEchoRequest() {
        SmpMessage.resetSequenceNumber()
        val message = SmpMessage.buildEchoRequest("test")

        assertEquals(SmpConstants.OP_WRITE.toByte(), message[0])
        assertEquals(SmpConstants.GROUP_OS.toByte(), message[5])
        assertEquals(SmpConstants.OS_ECHO.toByte(), message[7])

        val payload = message.copyOfRange(SmpConstants.HEADER_SIZE, message.size)
        val decoded = CborDecoder.decodeMap(payload)
        assertNotNull(decoded)
        assertEquals("test", decoded["d"])
    }

    @Test
    fun parseResponseValidMessage() {
        SmpMessage.resetSequenceNumber()
        // Build a response manually
        val cborPayload = CborEncoder.encodeMap(mapOf("rc" to 0))
        val header = ByteArray(SmpConstants.HEADER_SIZE)
        header[0] = SmpConstants.OP_WRITE_RESPONSE.toByte()
        header[1] = 0
        header[2] = (cborPayload.size shr 8).toByte()
        header[3] = cborPayload.size.toByte()
        header[4] = 0
        header[5] = SmpConstants.GROUP_IMAGE.toByte()
        header[6] = 0
        header[7] = SmpConstants.IMAGE_UPLOAD.toByte()

        val response = SmpMessage.parseResponse(header + cborPayload)
        assertNotNull(response)
        assertEquals(SmpConstants.OP_WRITE_RESPONSE, response.operation)
        assertEquals(SmpConstants.GROUP_IMAGE, response.group)
        assertEquals(SmpConstants.IMAGE_UPLOAD, response.commandId)
        assertFalse(response.isError)
        assertEquals(0, response.returnCode)
    }

    @Test
    fun parseResponseWithError() {
        val cborPayload = CborEncoder.encodeMap(mapOf("rc" to 3))
        val header = ByteArray(SmpConstants.HEADER_SIZE)
        header[0] = SmpConstants.OP_WRITE_RESPONSE.toByte()
        header[2] = (cborPayload.size shr 8).toByte()
        header[3] = cborPayload.size.toByte()

        val response = SmpMessage.parseResponse(header + cborPayload)
        assertNotNull(response)
        assertTrue(response.isError)
        assertEquals(3, response.returnCode)
    }

    @Test
    fun parseResponseTooShortReturnsNull() {
        assertNull(SmpMessage.parseResponse(byteArrayOf(0, 1, 2)))
    }

    @Test
    fun parseResponseWithOffset() {
        val cborPayload = CborEncoder.encodeMap(mapOf("rc" to 0, "off" to 256))
        val header = ByteArray(SmpConstants.HEADER_SIZE)
        header[0] = SmpConstants.OP_WRITE_RESPONSE.toByte()
        header[2] = (cborPayload.size shr 8).toByte()
        header[3] = cborPayload.size.toByte()
        header[5] = SmpConstants.GROUP_IMAGE.toByte()
        header[7] = SmpConstants.IMAGE_UPLOAD.toByte()

        val response = SmpMessage.parseResponse(header + cborPayload)
        assertNotNull(response)
        assertEquals(256, response.nextOffset)
    }

    @Test
    fun buildImageStateReadRequest() {
        SmpMessage.resetSequenceNumber()
        val message = SmpMessage.buildImageStateReadRequest()

        assertEquals(SmpConstants.OP_READ.toByte(), message[0])
        assertEquals(SmpConstants.GROUP_IMAGE.toByte(), message[5])
        assertEquals(SmpConstants.IMAGE_STATE.toByte(), message[7])
    }
}
