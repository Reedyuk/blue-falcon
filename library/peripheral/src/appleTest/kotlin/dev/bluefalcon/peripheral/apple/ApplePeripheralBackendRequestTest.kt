package dev.bluefalcon.peripheral.apple

import dev.bluefalcon.core.toUuid
import dev.bluefalcon.peripheral.AdvertiseConfig
import dev.bluefalcon.peripheral.GattCharacteristicId
import dev.bluefalcon.peripheral.GattResponseStatus
import dev.bluefalcon.peripheral.GattServiceId
import dev.bluefalcon.peripheral.PeripheralConfig
import dev.bluefalcon.peripheral.PeripheralSessionId
import dev.bluefalcon.peripheral.internal.BackendCharacteristicReadRequest
import dev.bluefalcon.peripheral.internal.BackendCharacteristicWriteBatchRequest
import dev.bluefalcon.peripheral.internal.BackendCharacteristicWriteRequest
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ApplePeripheralBackendRequestTest {

    @Test
    fun readOpensSessionAndResponsePreservesPlatformIdentity() = runTest {
        val stack = FakeApplePeripheralStack()
        val sink = RecordingAppleBackendSink()
        val backend = ApplePeripheralBackend(stack, logger = null)
        backend.start(Config, sink)

        stack.emit(readEvent())

        assertEquals(
            listOf<Pair<PeripheralSessionId, Int?>>(SessionId to MaximumLength),
            sink.openedSessions,
        )
        val request = assertIs<BackendCharacteristicReadRequest>(sink.requests.single())
        assertEquals(SessionId, request.sessionId)
        assertEquals(ServiceId, request.serviceId)
        assertEquals(CharacteristicId, request.characteristicId)
        assertEquals(4, request.offset)

        val value = byteArrayOf(1, 2, 3)
        request.responder.respond(GattResponseStatus.Success, value)
        value[0] = 99
        request.responder.respond(GattResponseStatus.UnlikelyError, null)

        val response = stack.responses.single()
        assertEquals(SessionId, response.sessionId)
        assertEquals(Token, response.requestToken)
        assertEquals(GattResponseStatus.Success, response.status)
        assertContentEquals(byteArrayOf(1, 2, 3), response.value)
    }

    @Test
    fun oneElementWritePublishesSingleWriteRequest() = runTest {
        val stack = FakeApplePeripheralStack()
        val sink = RecordingAppleBackendSink()
        val backend = ApplePeripheralBackend(stack, logger = null)
        backend.start(Config, sink)

        stack.emit(
            AppleGattEvent.CharacteristicWrite(
                sessionId = SessionId,
                maximumUpdateValueLength = MaximumLength,
                requestToken = Token,
                write = write(CharacteristicId, byteArrayOf(5, 6), offset = 2),
            ),
        )

        val request = assertIs<BackendCharacteristicWriteRequest>(sink.requests.single())
        assertEquals(false, request.preparedWrite)
        assertEquals(2, request.offset)
        assertContentEquals(byteArrayOf(5, 6), request.value)
        assertTrue(request.responder != null)
    }

    @Test
    fun multipleWritesPublishOneAtomicBatchAndOneResponse() = runTest {
        val stack = FakeApplePeripheralStack()
        val sink = RecordingAppleBackendSink()
        val backend = ApplePeripheralBackend(stack, logger = null)
        backend.start(Config, sink)

        stack.emit(
            AppleGattEvent.CharacteristicWriteBatch(
                sessionId = SessionId,
                maximumUpdateValueLength = MaximumLength,
                requestToken = Token,
                writes = listOf(
                    write(CharacteristicId, byteArrayOf(1), offset = 0),
                    write(OtherCharacteristicId, byteArrayOf(2, 3), offset = 1),
                ),
            ),
        )

        val request = assertIs<BackendCharacteristicWriteBatchRequest>(sink.requests.single())
        assertEquals(2, request.writes.size)
        assertEquals(CharacteristicId, request.writes[0].characteristicId)
        assertEquals(OtherCharacteristicId, request.writes[1].characteristicId)
        assertContentEquals(byteArrayOf(2, 3), request.writes[1].value)

        request.responder.respond(GattResponseStatus.Success, null)
        request.responder.respond(GattResponseStatus.UnlikelyError, null)

        assertEquals(1, stack.responses.size)
        assertEquals(Token, stack.responses.single().requestToken)
    }

    @Test
    fun rejectedPlatformResponseReportsFailure() = runTest {
        val stack = FakeApplePeripheralStack().apply { sendResponseResult = false }
        val sink = RecordingAppleBackendSink()
        val backend = ApplePeripheralBackend(stack, logger = null)
        backend.start(Config, sink)
        stack.emit(readEvent())

        val request = assertIs<BackendCharacteristicReadRequest>(sink.requests.single())
        request.responder.respond(GattResponseStatus.Success, byteArrayOf(7))

        assertIs<AppleGattResponseException>(sink.platformFailures.single())
    }

    @Test
    fun staleGenerationRequestIsRejectedAndNotDelivered() = runTest {
        val stack = FakeApplePeripheralStack()
        val firstSink = RecordingAppleBackendSink()
        val backend = ApplePeripheralBackend(stack, logger = null)
        backend.start(Config, firstSink)
        val staleListener = stack.listeners.single()
        backend.stop()
        val currentSink = RecordingAppleBackendSink()
        backend.start(Config, currentSink)

        staleListener.onEvent(readEvent())

        assertTrue(currentSink.requests.isEmpty())
        assertEquals(GattResponseStatus.UnlikelyError, stack.responses.single().status)
        assertEquals(Token, stack.responses.single().requestToken)
    }

    @Test
    fun emptyPlatformBatchIsRejectedAtBoundary() {
        assertFailsWith<IllegalArgumentException> {
            AppleGattEvent.CharacteristicWriteBatch(
                sessionId = SessionId,
                maximumUpdateValueLength = MaximumLength,
                requestToken = Token,
                writes = emptyList(),
            )
        }
    }

    @Test
    fun reentrantCallbackCannotOvertakeOriginalRequest() = runTest {
        val stack = FakeApplePeripheralStack()
        val sink = RecordingAppleBackendSink()
        val backend = ApplePeripheralBackend(stack, logger = null)
        backend.start(Config, sink)
        sink.onSessionOpenedCallback = {
            stack.emit(
                AppleGattEvent.Subscribed(
                    SessionId,
                    MaximumLength,
                    CharacteristicId,
                ),
            )
        }

        stack.emit(readEvent())

        assertEquals(
            listOf("sessionOpened", "request", "subscriptionsChanged"),
            sink.eventNames,
        )
    }

    private fun readEvent() = AppleGattEvent.CharacteristicRead(
        sessionId = SessionId,
        maximumUpdateValueLength = MaximumLength,
        requestToken = Token,
        serviceId = ServiceId,
        characteristicId = CharacteristicId,
        offset = 4,
    )

    private fun write(
        characteristicId: GattCharacteristicId,
        value: ByteArray,
        offset: Int,
    ) = AppleCharacteristicWrite(
        serviceId = ServiceId,
        characteristicId = characteristicId,
        offset = offset,
        value = value,
    )

    private companion object {
        val Config = PeripheralConfig(AdvertiseConfig())
        val SessionId = PeripheralSessionId("central")
        val ServiceId = GattServiceId("0000180d-0000-1000-8000-00805f9b34fb".toUuid())
        val CharacteristicId =
            GattCharacteristicId("00002a37-0000-1000-8000-00805f9b34fb".toUuid())
        val OtherCharacteristicId =
            GattCharacteristicId("00002a38-0000-1000-8000-00805f9b34fb".toUuid())
        val Token = AppleRequestToken(7)
        const val MaximumLength = 180
    }
}
