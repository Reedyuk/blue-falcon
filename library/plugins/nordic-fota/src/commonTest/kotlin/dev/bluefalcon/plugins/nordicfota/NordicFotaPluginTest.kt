package dev.bluefalcon.plugins.nordicfota

import dev.bluefalcon.core.plugin.PluginConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class NordicFotaPluginTest {

    @Test
    fun createWithDefaultConfig() {
        val plugin = NordicFotaPlugin.create()
        assertEquals(FotaState.Idle, plugin.state.value)
    }

    @Test
    fun createWithCustomConfig() {
        val plugin = NordicFotaPlugin.create {
            chunkSize = 128
            autoConfirm = false
            autoReset = false
            imageIndex = 1
        }
        assertEquals(FotaState.Idle, plugin.state.value)
    }

    @Test
    fun startUpdateWithEmptyDataSetsError() {
        val plugin = NordicFotaPlugin.create()
        val peripheral = TestPeripheral("test-device")
        val messages = plugin.startUpdate(peripheral, byteArrayOf())

        assertTrue(messages.isEmpty())
        val state = plugin.state.value as FotaState.Error
        assertEquals("Firmware data is empty", state.error)
    }

    @Test
    fun startUpdateGeneratesMessages() {
        val plugin = NordicFotaPlugin.create { chunkSize = 50 }
        val peripheral = TestPeripheral("test-device")
        val firmwareData = ByteArray(120) { it.toByte() }

        val messages = plugin.startUpdate(peripheral, firmwareData)

        // 120 bytes / 50 bytes per chunk = 3 chunks (50 + 50 + 20)
        assertEquals(3, messages.size)

        // State should be uploading
        val state = plugin.state.value as FotaState.Uploading
        assertEquals(0, state.offset)
        assertEquals(120, state.totalBytes)
    }

    @Test
    fun generateUploadMessagesCorrectCount() {
        val plugin = NordicFotaPlugin.create { chunkSize = 100 }
        val firmwareData = ByteArray(250) { 0 }

        val messages = plugin.generateUploadMessages(firmwareData)
        // 250 / 100 = 3 chunks (100 + 100 + 50)
        assertEquals(3, messages.size)
    }

    @Test
    fun generateUploadMessagesExactMultiple() {
        val plugin = NordicFotaPlugin.create { chunkSize = 50 }
        val firmwareData = ByteArray(100) { 0 }

        val messages = plugin.generateUploadMessages(firmwareData)
        // 100 / 50 = 2 chunks exactly
        assertEquals(2, messages.size)
    }

    @Test
    fun generateUploadMessagesSingleChunk() {
        val plugin = NordicFotaPlugin.create { chunkSize = 256 }
        val firmwareData = ByteArray(100) { 0 }

        val messages = plugin.generateUploadMessages(firmwareData)
        assertEquals(1, messages.size)
    }

    @Test
    fun processResponseUploadContinues() {
        val plugin = NordicFotaPlugin.create {
            chunkSize = 50
            autoConfirm = false
        }
        val peripheral = TestPeripheral("test-device")
        val firmwareData = ByteArray(100) { it.toByte() }
        plugin.startUpdate(peripheral, firmwareData)

        // Simulate device acknowledging first 50 bytes
        val response = buildUploadResponse(nextOffset = 50)
        val nextMessage = plugin.processResponse(response)

        // Should get next chunk to write
        assertNotNull(nextMessage)

        val state = plugin.state.value as FotaState.Uploading
        assertEquals(50, state.offset)
    }

    @Test
    fun processResponseUploadCompleteWithAutoConfirm() {
        val plugin = NordicFotaPlugin.create {
            chunkSize = 256
            autoConfirm = true
            autoReset = false
        }
        val peripheral = TestPeripheral("test-device")
        val firmwareData = ByteArray(100) { it.toByte() }
        plugin.startUpdate(peripheral, firmwareData)

        // Simulate device acknowledging all bytes
        val response = buildUploadResponse(nextOffset = 100)
        val nextMessage = plugin.processResponse(response)

        // Should get confirm message
        assertNotNull(nextMessage)

        val state = plugin.state.value
        assertTrue(state is FotaState.Confirming)
    }

    @Test
    fun processResponseUploadCompleteWithoutAutoConfirm() {
        val plugin = NordicFotaPlugin.create {
            chunkSize = 256
            autoConfirm = false
        }
        val peripheral = TestPeripheral("test-device")
        val firmwareData = ByteArray(100) { it.toByte() }
        plugin.startUpdate(peripheral, firmwareData)

        val response = buildUploadResponse(nextOffset = 100)
        val nextMessage = plugin.processResponse(response)

        // Should be null — caller needs to manually confirm
        assertNull(nextMessage)
    }

    @Test
    fun processResponseErrorSetsErrorState() {
        val plugin = NordicFotaPlugin.create()
        val peripheral = TestPeripheral("test-device")
        plugin.startUpdate(peripheral, ByteArray(100))

        val response = buildErrorResponse(returnCode = 5)
        val nextMessage = plugin.processResponse(response)

        assertNull(nextMessage)
        val state = plugin.state.value as FotaState.Error
        assertTrue(state.error.contains("return code 5"))
    }

    @Test
    fun processResponseInvalidDataSetsError() {
        val plugin = NordicFotaPlugin.create()
        val nextMessage = plugin.processResponse(byteArrayOf(0, 1))

        assertNull(nextMessage)
        val state = plugin.state.value
        assertTrue(state is FotaState.Error)
    }

    @Test
    fun processResponseConfirmWithAutoReset() {
        val plugin = NordicFotaPlugin.create {
            autoConfirm = true
            autoReset = true
        }
        val peripheral = TestPeripheral("test-device")
        plugin.startUpdate(peripheral, ByteArray(10))

        // Simulate upload complete → confirm response → should get reset
        val uploadResponse = buildUploadResponse(nextOffset = 10)
        plugin.processResponse(uploadResponse)

        val confirmResponse = buildImageStateResponse()
        val resetMessage = plugin.processResponse(confirmResponse)

        assertNotNull(resetMessage)
        val state = plugin.state.value
        assertTrue(state is FotaState.Resetting)
    }

    @Test
    fun processResponseResetCompletesUpdate() {
        val plugin = NordicFotaPlugin.create {
            autoConfirm = true
            autoReset = true
        }
        val peripheral = TestPeripheral("test-device")
        plugin.startUpdate(peripheral, ByteArray(10))

        // Walk through the entire state machine
        plugin.processResponse(buildUploadResponse(nextOffset = 10))
        plugin.processResponse(buildImageStateResponse())

        val resetResponse = buildResetResponse()
        val next = plugin.processResponse(resetResponse)

        assertNull(next)
        val state = plugin.state.value
        assertTrue(state is FotaState.Complete)
    }

    @Test
    fun cancelUpdateResetsState() {
        val plugin = NordicFotaPlugin.create()
        val peripheral = TestPeripheral("test-device")
        plugin.startUpdate(peripheral, ByteArray(100))

        assertTrue(plugin.state.value is FotaState.Uploading)

        plugin.cancelUpdate()
        assertEquals(FotaState.Idle, plugin.state.value)
    }

    @Test
    fun callbackReceivesStateChanges() {
        val plugin = NordicFotaPlugin.create()
        val states = mutableListOf<FotaState>()
        val callback = object : FotaCallback {
            override fun onStateChanged(state: FotaState) {
                states.add(state)
            }
        }
        plugin.addCallback(callback)

        val peripheral = TestPeripheral("test-device")
        plugin.startUpdate(peripheral, ByteArray(100))

        assertTrue(states.isNotEmpty())
        assertTrue(states.first() is FotaState.Uploading)
    }

    @Test
    fun callbackReceivesProgressUpdates() {
        val plugin = NordicFotaPlugin.create { chunkSize = 50 }
        val progressUpdates = mutableListOf<Pair<Int, Int>>()
        val callback = object : FotaCallback {
            override fun onUploadProgress(bytesSent: Int, totalBytes: Int) {
                progressUpdates.add(bytesSent to totalBytes)
            }
        }
        plugin.addCallback(callback)

        val peripheral = TestPeripheral("test-device")
        plugin.startUpdate(peripheral, ByteArray(100))

        // Simulate partial upload response
        plugin.processResponse(buildUploadResponse(nextOffset = 50))

        assertTrue(progressUpdates.isNotEmpty())
        assertEquals(50, progressUpdates.last().first)
        assertEquals(100, progressUpdates.last().second)
    }

    @Test
    fun callbackReceivesCompleteEvent() {
        val plugin = NordicFotaPlugin.create {
            autoConfirm = true
            autoReset = true
        }
        var completed = false
        val callback = object : FotaCallback {
            override fun onComplete() {
                completed = true
            }
        }
        plugin.addCallback(callback)

        val peripheral = TestPeripheral("test-device")
        plugin.startUpdate(peripheral, ByteArray(10))

        plugin.processResponse(buildUploadResponse(nextOffset = 10))
        plugin.processResponse(buildImageStateResponse())
        plugin.processResponse(buildResetResponse())

        assertTrue(completed)
    }

    @Test
    fun callbackReceivesErrorEvent() {
        val plugin = NordicFotaPlugin.create()
        var errorMessage: String? = null
        val callback = object : FotaCallback {
            override fun onError(error: String, cause: Throwable?) {
                errorMessage = error
            }
        }
        plugin.addCallback(callback)

        val peripheral = TestPeripheral("test-device")
        plugin.startUpdate(peripheral, ByteArray(100))
        plugin.processResponse(buildErrorResponse(returnCode = 3))

        val capturedError = errorMessage
        assertNotNull(capturedError)
        assertTrue(capturedError.contains("return code 3"))
    }

    @Test
    fun removeCallbackStopsNotifications() {
        val plugin = NordicFotaPlugin.create()
        var callCount = 0
        val callback = object : FotaCallback {
            override fun onStateChanged(state: FotaState) {
                callCount++
            }
        }

        plugin.addCallback(callback)
        val peripheral = TestPeripheral("test-device")
        plugin.startUpdate(peripheral, ByteArray(10))
        val firstCount = callCount

        plugin.removeCallback(callback)
        plugin.cancelUpdate()

        // Should not have received the Idle state change after removal
        assertEquals(firstCount, callCount)
    }

    @Test
    fun effectiveChunkSizeRespectsConfiguredMax() {
        val plugin = NordicFotaPlugin.create { chunkSize = 128 }
        // Large MTU should still be capped at configured chunkSize
        assertEquals(128, plugin.effectiveChunkSize(512))
    }

    @Test
    fun effectiveChunkSizeRespectsSmallMTU() {
        val plugin = NordicFotaPlugin.create { chunkSize = 256 }
        // Small MTU (23 default) minus overhead (40) should limit the chunk
        val result = plugin.effectiveChunkSize(23)
        assertTrue(result < 256)
        assertTrue(result >= 1)
    }

    @Test
    fun uploadingStateProgressCalculation() {
        val uploading = FotaState.Uploading(offset = 50, totalBytes = 100)
        assertEquals(0.5f, uploading.progress)
    }

    @Test
    fun uploadingStateProgressZeroTotal() {
        val uploading = FotaState.Uploading(offset = 0, totalBytes = 0)
        assertEquals(0f, uploading.progress)
    }

    @Test
    fun smpServiceUuidConstant() {
        assertEquals("8d53dc1d-1db7-4cd3-868b-8a527460aa84", NordicFotaPlugin.SMP_SERVICE_UUID)
    }

    @Test
    fun smpCharacteristicUuidConstant() {
        assertEquals("da2e7828-fbce-4e01-ae9e-261174997c48", NordicFotaPlugin.SMP_CHARACTERISTIC_UUID)
    }

    @Test
    fun installNordicFotaDsl() {
        val plugin = installNordicFota {
            chunkSize = 512
            autoConfirm = false
        }
        assertNotNull(plugin)
        assertEquals(FotaState.Idle, plugin.state.value)
    }

    // --- Test helpers ---

    private fun buildUploadResponse(nextOffset: Int): ByteArray {
        val cborPayload = CborEncoder.encodeMap(mapOf("rc" to 0, "off" to nextOffset))
        val header = ByteArray(SmpConstants.HEADER_SIZE)
        header[0] = SmpConstants.OP_WRITE_RESPONSE.toByte()
        header[2] = (cborPayload.size shr 8).toByte()
        header[3] = cborPayload.size.toByte()
        header[4] = 0
        header[5] = SmpConstants.GROUP_IMAGE.toByte()
        header[6] = 0
        header[7] = SmpConstants.IMAGE_UPLOAD.toByte()
        return header + cborPayload
    }

    private fun buildImageStateResponse(): ByteArray {
        val cborPayload = CborEncoder.encodeMap(mapOf("rc" to 0))
        val header = ByteArray(SmpConstants.HEADER_SIZE)
        header[0] = SmpConstants.OP_WRITE_RESPONSE.toByte()
        header[2] = (cborPayload.size shr 8).toByte()
        header[3] = cborPayload.size.toByte()
        header[5] = SmpConstants.GROUP_IMAGE.toByte()
        header[7] = SmpConstants.IMAGE_STATE.toByte()
        return header + cborPayload
    }

    private fun buildResetResponse(): ByteArray {
        val cborPayload = CborEncoder.encodeMap(mapOf("rc" to 0))
        val header = ByteArray(SmpConstants.HEADER_SIZE)
        header[0] = SmpConstants.OP_WRITE_RESPONSE.toByte()
        header[2] = (cborPayload.size shr 8).toByte()
        header[3] = cborPayload.size.toByte()
        header[5] = SmpConstants.GROUP_OS.toByte()
        header[7] = SmpConstants.OS_RESET.toByte()
        return header + cborPayload
    }

    private fun buildErrorResponse(returnCode: Int): ByteArray {
        val cborPayload = CborEncoder.encodeMap(mapOf("rc" to returnCode))
        val header = ByteArray(SmpConstants.HEADER_SIZE)
        header[0] = SmpConstants.OP_WRITE_RESPONSE.toByte()
        header[2] = (cborPayload.size shr 8).toByte()
        header[3] = cborPayload.size.toByte()
        header[5] = SmpConstants.GROUP_IMAGE.toByte()
        header[7] = SmpConstants.IMAGE_UPLOAD.toByte()
        return header + cborPayload
    }
}

/**
 * Simple test peripheral for unit testing without platform dependencies.
 */
private data class TestPeripheral(
    override val name: String?,
    override val uuid: String = "test-uuid-${name?.hashCode() ?: 0}",
    override val rssi: Float? = -50f,
    override val mtuSize: Int? = 23,
    override val services: List<dev.bluefalcon.core.BluetoothService> = emptyList(),
    override val characteristics: List<dev.bluefalcon.core.BluetoothCharacteristic> = emptyList()
) : dev.bluefalcon.core.BluetoothPeripheral
