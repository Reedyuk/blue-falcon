package dev.bluefalcon.peripheral.android

import dev.bluefalcon.core.NoOpLogger
import dev.bluefalcon.peripheral.AdvertiseConfig
import dev.bluefalcon.peripheral.GattCharacteristicId
import dev.bluefalcon.peripheral.GattDescriptorId
import dev.bluefalcon.peripheral.GattResponseStatus
import dev.bluefalcon.peripheral.GattServiceId
import dev.bluefalcon.peripheral.PeripheralConfig
import dev.bluefalcon.peripheral.PeripheralSessionId
import dev.bluefalcon.peripheral.internal.BackendCharacteristicReadRequest
import dev.bluefalcon.peripheral.internal.BackendCharacteristicWriteRequest
import dev.bluefalcon.peripheral.internal.BackendDescriptorReadRequest
import dev.bluefalcon.peripheral.internal.BackendDescriptorWriteRequest
import dev.bluefalcon.peripheral.internal.BackendExecuteWriteRequest
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class AndroidPeripheralBackendRequestTest {
    @Test
    fun characteristicReadResponseTargetsOriginalAndroidRequest() = runTest {
        val fixture = startedFixture()
        fixture.stack.emit(
            AndroidGattEvent.CharacteristicRead(
                sessionId = SessionId,
                requestId = 9,
                serviceId = ServiceId,
                characteristicId = CharacteristicId,
                offset = 4,
            ),
        )

        val request = assertIs<BackendCharacteristicReadRequest>(fixture.sink.requests.single())
        assertEquals(SessionId, request.sessionId)
        assertEquals(ServiceId, request.serviceId)
        assertEquals(CharacteristicId, request.characteristicId)
        assertEquals(4, request.offset)
        val responseValue = byteArrayOf(7, 8)
        request.responder.respond(GattResponseStatus.Success, responseValue)
        responseValue[0] = 99

        val response = fixture.stack.responses.single()
        assertEquals(SessionId, response.sessionId)
        assertEquals(9, response.requestId)
        assertEquals(GattResponseStatus.Success, response.status)
        assertEquals(4, response.offset)
        assertContentEquals(byteArrayOf(7, 8), response.value)
    }

    @Test
    fun characteristicWriteCopiesFieldsAndOmitsResponderWhenNotNeeded() = runTest {
        val fixture = startedFixture()
        val source = byteArrayOf(1, 2, 3)
        val event = AndroidGattEvent.CharacteristicWrite(
            sessionId = SessionId,
            requestId = 10,
            serviceId = ServiceId,
            characteristicId = CharacteristicId,
            offset = 2,
            preparedWrite = false,
            responseNeeded = false,
            value = source,
        )
        source[0] = 99

        fixture.stack.emit(event)

        val request = assertIs<BackendCharacteristicWriteRequest>(fixture.sink.requests.single())
        assertEquals(SessionId, request.sessionId)
        assertEquals(ServiceId, request.serviceId)
        assertEquals(CharacteristicId, request.characteristicId)
        assertEquals(2, request.offset)
        assertEquals(false, request.preparedWrite)
        assertNull(request.responder)
        assertContentEquals(byteArrayOf(1, 2, 3), request.value)
        val exposed = request.value
        exposed[0] = 42
        assertContentEquals(byteArrayOf(1, 2, 3), request.value)
    }

    @Test
    fun responseNeededCharacteristicWriteHasResponder() = runTest {
        val fixture = startedFixture()
        fixture.stack.emit(
            AndroidGattEvent.CharacteristicWrite(
                sessionId = SessionId,
                requestId = 11,
                serviceId = ServiceId,
                characteristicId = CharacteristicId,
                offset = 1,
                preparedWrite = true,
                responseNeeded = true,
                value = byteArrayOf(5),
            ),
        )

        val request = assertIs<BackendCharacteristicWriteRequest>(fixture.sink.requests.single())
        assertEquals(true, request.preparedWrite)
        assertNotNull(request.responder)
    }

    @Test
    fun descriptorReadPreservesAttributeIdentity() = runTest {
        val fixture = startedFixture()
        fixture.stack.emit(
            AndroidGattEvent.DescriptorRead(
                sessionId = SessionId,
                requestId = 12,
                serviceId = ServiceId,
                characteristicId = CharacteristicId,
                descriptorId = DescriptorId,
                offset = 3,
            ),
        )

        val request = assertIs<BackendDescriptorReadRequest>(fixture.sink.requests.single())
        assertEquals(SessionId, request.sessionId)
        assertEquals(ServiceId, request.serviceId)
        assertEquals(CharacteristicId, request.characteristicId)
        assertEquals(DescriptorId, request.descriptorId)
        assertEquals(3, request.offset)
        assertNotNull(request.responder)
    }

    @Test
    fun descriptorWriteCopiesFieldsAndMapsResponderNullability() = runTest {
        val fixture = startedFixture()
        val source = byteArrayOf(6, 7)
        val event = AndroidGattEvent.DescriptorWrite(
            sessionId = SessionId,
            requestId = 13,
            serviceId = ServiceId,
            characteristicId = CharacteristicId,
            descriptorId = DescriptorId,
            offset = 5,
            preparedWrite = false,
            responseNeeded = false,
            value = source,
        )
        source[0] = 99

        fixture.stack.emit(event)

        val request = assertIs<BackendDescriptorWriteRequest>(fixture.sink.requests.single())
        assertEquals(SessionId, request.sessionId)
        assertEquals(ServiceId, request.serviceId)
        assertEquals(CharacteristicId, request.characteristicId)
        assertEquals(DescriptorId, request.descriptorId)
        assertEquals(5, request.offset)
        assertEquals(false, request.preparedWrite)
        assertNull(request.responder)
        assertContentEquals(byteArrayOf(6, 7), request.value)
    }

    @Test
    fun executeWritePreservesRequestAndExecuteFlag() = runTest {
        val fixture = startedFixture()
        fixture.stack.emit(
            AndroidGattEvent.ExecuteWrite(
                sessionId = SessionId,
                requestId = 14,
                execute = false,
            ),
        )

        val request = assertIs<BackendExecuteWriteRequest>(fixture.sink.requests.single())
        assertEquals(SessionId, request.sessionId)
        assertEquals(false, request.execute)
        request.responder.respond(GattResponseStatus.UnlikelyError, null)
        val response = fixture.stack.responses.single()
        assertEquals(14, response.requestId)
        assertEquals(0, response.offset)
        assertEquals(GattResponseStatus.UnlikelyError, response.status)
    }

    @Test
    fun rejectedPlatformResponsePublishesFailure() = runTest {
        val fixture = startedFixture()
        fixture.stack.acceptResponses = false
        fixture.stack.emit(
            AndroidGattEvent.CharacteristicRead(
                sessionId = SessionId,
                requestId = 15,
                serviceId = ServiceId,
                characteristicId = CharacteristicId,
                offset = 0,
            ),
        )

        val request = assertIs<BackendCharacteristicReadRequest>(fixture.sink.requests.single())
        request.responder.respond(GattResponseStatus.Success, null)

        assertEquals(1, fixture.stack.responses.size)
        assertEquals(1, fixture.sink.platformFailures.size)
    }

    @Test
    fun duplicateResponsesOnlyReachPlatformOnce() = runTest {
        val fixture = startedFixture()
        fixture.stack.emit(
            AndroidGattEvent.CharacteristicRead(
                sessionId = SessionId,
                requestId = 16,
                serviceId = ServiceId,
                characteristicId = CharacteristicId,
                offset = 0,
            ),
        )
        val responder =
            assertIs<BackendCharacteristicReadRequest>(fixture.sink.requests.single()).responder

        responder.respond(GattResponseStatus.Success, byteArrayOf(1))
        responder.respond(GattResponseStatus.UnlikelyError, null)

        assertEquals(1, fixture.stack.responses.size)
        assertEquals(GattResponseStatus.Success, fixture.stack.responses.single().status)
    }

    @Test
    fun notificationCccdCommitsOnlyAfterSuccessfulResponse() = runTest {
        val fixture = startedFixture()

        fixture.emitCccdWrite(requestId = 20, value = NotificationCccdValue)
        assertEquals(emptyList(), fixture.sink.subscriptionChanges)

        fixture.lastDescriptorWrite().responder?.respond(GattResponseStatus.Success, null)

        assertEquals(
            listOf(SessionId to setOf(CharacteristicId)),
            fixture.sink.subscriptionChanges,
        )
    }

    @Test
    fun indicationCccdCommitsSubscription() = runTest {
        val fixture = startedFixture()

        fixture.emitCccdWrite(requestId = 21, value = IndicationCccdValue)
        fixture.lastDescriptorWrite().responder?.respond(GattResponseStatus.Success, null)

        assertEquals(
            listOf(SessionId to setOf(CharacteristicId)),
            fixture.sink.subscriptionChanges,
        )
    }

    @Test
    fun disableCccdRemovesCommittedSubscription() = runTest {
        val fixture = startedFixture()
        fixture.emitCccdWrite(
            requestId = 22,
            value = NotificationCccdValue,
            responseNeeded = false,
        )
        fixture.sink.subscriptionChanges.clear()

        fixture.emitCccdWrite(requestId = 23, value = DisableCccdValue)
        assertEquals(emptyList(), fixture.sink.subscriptionChanges)
        fixture.lastDescriptorWrite().responder?.respond(GattResponseStatus.Success, null)

        assertEquals(
            listOf(SessionId to emptySet()),
            fixture.sink.subscriptionChanges,
        )
    }

    @Test
    fun rejectedCccdResponseDoesNotCommit() = runTest {
        val fixture = startedFixture()

        fixture.emitCccdWrite(requestId = 24, value = NotificationCccdValue)
        fixture.lastDescriptorWrite().responder?.respond(GattResponseStatus.WriteNotPermitted, null)

        assertEquals(emptyList(), fixture.sink.subscriptionChanges)
    }

    @Test
    fun noResponseCccdWriteCommitsImmediately() = runTest {
        val fixture = startedFixture()

        fixture.emitCccdWrite(
            requestId = 25,
            value = NotificationCccdValue,
            responseNeeded = false,
        )

        assertNull(fixture.lastDescriptorWrite().responder)
        assertEquals(
            listOf(SessionId to setOf(CharacteristicId)),
            fixture.sink.subscriptionChanges,
        )
    }

    @Test
    fun preparedCccdFragmentsCommitOnlyOnExecuteSuccess() = runTest {
        val fixture = startedFixture()

        fixture.stageCccdFragment(requestId = 26, offset = 0, value = byteArrayOf(1))
        fixture.stageCccdFragment(requestId = 27, offset = 1, value = byteArrayOf(0))
        assertEquals(emptyList(), fixture.sink.subscriptionChanges)

        fixture.emitExecuteWrite(requestId = 28, execute = true)
        fixture.lastExecuteWrite().responder.respond(GattResponseStatus.Success, null)

        assertEquals(
            listOf(SessionId to setOf(CharacteristicId)),
            fixture.sink.subscriptionChanges,
        )
    }

    @Test
    fun rejectedPreparedFragmentIsNotCommittedByExecute() = runTest {
        val fixture = startedFixture()
        fixture.emitCccdWrite(
            requestId = 29,
            value = NotificationCccdValue,
            preparedWrite = true,
        )
        fixture.lastDescriptorWrite().responder?.respond(
            GattResponseStatus.WriteNotPermitted,
            null,
        )

        fixture.emitExecuteWrite(requestId = 30, execute = true)
        fixture.lastExecuteWrite().responder.respond(GattResponseStatus.Success, null)

        assertEquals(emptyList(), fixture.sink.subscriptionChanges)
    }

    @Test
    fun rejectedLaterPreparedFragmentDiscardsPreviouslyStagedCccdValue() = runTest {
        val fixture = startedFixture()
        fixture.stageCccdFragment(
            requestId = 48,
            offset = 0,
            value = NotificationCccdValue,
        )
        fixture.emitCccdWrite(
            requestId = 49,
            value = IndicationCccdValue,
            preparedWrite = true,
        )
        fixture.lastDescriptorWrite().responder?.respond(
            GattResponseStatus.WriteNotPermitted,
            null,
        )

        fixture.emitExecuteWrite(requestId = 50, execute = true)
        fixture.lastExecuteWrite().responder.respond(GattResponseStatus.Success, null)

        assertEquals(emptyList(), fixture.sink.subscriptionChanges)
    }

    @Test
    fun expiredLaterPreparedFragmentDiscardsPreviouslyStagedCccdValue() = runTest {
        val fixture = startedFixture()
        fixture.stageCccdFragment(
            requestId = 51,
            offset = 0,
            value = NotificationCccdValue,
        )
        fixture.emitCccdWrite(
            requestId = 52,
            value = IndicationCccdValue,
            preparedWrite = true,
        )
        fixture.lastDescriptorWrite().responder?.respond(
            GattResponseStatus.UnlikelyError,
            null,
        )

        fixture.emitExecuteWrite(requestId = 53, execute = true)
        fixture.lastExecuteWrite().responder.respond(GattResponseStatus.Success, null)

        assertEquals(emptyList(), fixture.sink.subscriptionChanges)
    }

    @Test
    fun incompletePreparedCccdValueDoesNotCommit() = runTest {
        val fixture = startedFixture()
        fixture.stageCccdFragment(requestId = 45, offset = 0, value = byteArrayOf(1))

        fixture.emitExecuteWrite(requestId = 46, execute = true)
        fixture.lastExecuteWrite().responder.respond(GattResponseStatus.Success, null)
        assertEquals(emptyList(), fixture.sink.subscriptionChanges)

        fixture.stageCccdFragment(requestId = 47, offset = 0, value = IndicationCccdValue)
        fixture.emitExecuteWrite(requestId = 48, execute = true)
        fixture.lastExecuteWrite().responder.respond(GattResponseStatus.Success, null)
        assertEquals(
            listOf(SessionId to setOf(CharacteristicId)),
            fixture.sink.subscriptionChanges,
        )
    }

    @Test
    fun executeCancellationClearsPreparedCccdState() = runTest {
        val fixture = startedFixture()
        fixture.stageCccdFragment(requestId = 31, offset = 0, value = NotificationCccdValue)

        fixture.emitExecuteWrite(requestId = 32, execute = false)
        fixture.lastExecuteWrite().responder.respond(GattResponseStatus.Success, null)
        fixture.emitExecuteWrite(requestId = 33, execute = true)
        fixture.lastExecuteWrite().responder.respond(GattResponseStatus.Success, null)
        assertEquals(emptyList(), fixture.sink.subscriptionChanges)

        fixture.stageCccdFragment(requestId = 34, offset = 0, value = IndicationCccdValue)
        fixture.emitExecuteWrite(requestId = 35, execute = true)
        fixture.lastExecuteWrite().responder.respond(GattResponseStatus.Success, null)
        assertEquals(
            listOf(SessionId to setOf(CharacteristicId)),
            fixture.sink.subscriptionChanges,
        )
    }

    @Test
    fun expiredExecuteResponseClearsPreparedCccdState() = runTest {
        val fixture = startedFixture()
        fixture.stageCccdFragment(requestId = 36, offset = 0, value = NotificationCccdValue)

        fixture.emitExecuteWrite(requestId = 37, execute = true)
        fixture.lastExecuteWrite().responder.respond(GattResponseStatus.UnlikelyError, null)
        fixture.emitExecuteWrite(requestId = 38, execute = true)
        fixture.lastExecuteWrite().responder.respond(GattResponseStatus.Success, null)
        assertEquals(emptyList(), fixture.sink.subscriptionChanges)

        fixture.stageCccdFragment(requestId = 39, offset = 0, value = NotificationCccdValue)
        fixture.emitExecuteWrite(requestId = 40, execute = true)
        fixture.lastExecuteWrite().responder.respond(GattResponseStatus.Success, null)
        assertEquals(
            listOf(SessionId to setOf(CharacteristicId)),
            fixture.sink.subscriptionChanges,
        )
    }

    @Test
    fun disconnectClearsPreparedCccdState() = runTest {
        val fixture = startedFixture()
        fixture.stageCccdFragment(requestId = 41, offset = 0, value = NotificationCccdValue)

        fixture.stack.emit(AndroidGattEvent.Disconnected(SessionId, status = 0))
        fixture.stack.emit(AndroidGattEvent.Connected(SessionId))
        fixture.emitExecuteWrite(requestId = 42, execute = true)
        fixture.lastExecuteWrite().responder.respond(GattResponseStatus.Success, null)
        assertEquals(emptyList(), fixture.sink.subscriptionChanges)

        fixture.stageCccdFragment(requestId = 43, offset = 0, value = NotificationCccdValue)
        fixture.emitExecuteWrite(requestId = 44, execute = true)
        fixture.lastExecuteWrite().responder.respond(GattResponseStatus.Success, null)
        assertEquals(
            listOf(SessionId to setOf(CharacteristicId)),
            fixture.sink.subscriptionChanges,
        )
    }

    private suspend fun startedFixture(): RequestFixture {
        val stack = ControllableResponseStack()
        val sink = RecordingBackendSink()
        val backend = AndroidPeripheralBackend(stack, NoOpLogger)
        backend.start(
            PeripheralConfig(advertiseConfig = AdvertiseConfig()),
            sink,
        )
        stack.emit(AndroidGattEvent.Connected(SessionId))
        return RequestFixture(stack, sink, backend)
    }

    companion object {
        val SessionId = PeripheralSessionId("central")
        val ServiceId = GattServiceId(Uuid.parse("0000180d-0000-1000-8000-00805f9b34fb"))
        val CharacteristicId =
            GattCharacteristicId(Uuid.parse("00002a37-0000-1000-8000-00805f9b34fb"))
        val DescriptorId =
            GattDescriptorId(Uuid.parse("00002901-0000-1000-8000-00805f9b34fb"))
        val CccdId =
            GattDescriptorId(Uuid.parse("00002902-0000-1000-8000-00805f9b34fb"))
        val DisableCccdValue = byteArrayOf(0, 0)
        val NotificationCccdValue = byteArrayOf(1, 0)
        val IndicationCccdValue = byteArrayOf(2, 0)
    }
}

private data class RequestFixture(
    val stack: ControllableResponseStack,
    val sink: RecordingBackendSink,
    val backend: AndroidPeripheralBackend,
) {
    fun emitCccdWrite(
        requestId: Int,
        value: ByteArray,
        offset: Int = 0,
        preparedWrite: Boolean = false,
        responseNeeded: Boolean = true,
    ) {
        stack.emit(
            AndroidGattEvent.DescriptorWrite(
                sessionId = AndroidPeripheralBackendRequestTest.SessionId,
                requestId = requestId,
                serviceId = AndroidPeripheralBackendRequestTest.ServiceId,
                characteristicId = AndroidPeripheralBackendRequestTest.CharacteristicId,
                descriptorId = AndroidPeripheralBackendRequestTest.CccdId,
                offset = offset,
                preparedWrite = preparedWrite,
                responseNeeded = responseNeeded,
                value = value,
            ),
        )
    }

    fun stageCccdFragment(requestId: Int, offset: Int, value: ByteArray) {
        emitCccdWrite(
            requestId = requestId,
            value = value,
            offset = offset,
            preparedWrite = true,
        )
        lastDescriptorWrite().responder?.respond(GattResponseStatus.Success, null)
    }

    fun emitExecuteWrite(requestId: Int, execute: Boolean) {
        stack.emit(
            AndroidGattEvent.ExecuteWrite(
                sessionId = AndroidPeripheralBackendRequestTest.SessionId,
                requestId = requestId,
                execute = execute,
            ),
        )
    }

    fun lastDescriptorWrite(): BackendDescriptorWriteRequest =
        assertIs(sink.requests.last())

    fun lastExecuteWrite(): BackendExecuteWriteRequest =
        assertIs(sink.requests.last())
}

private class ControllableResponseStack(
    private val delegate: FakeAndroidBluetoothStack = FakeAndroidBluetoothStack(),
) : AndroidBluetoothStack by delegate {
    var acceptResponses = true

    val responses: List<AndroidGattResponse>
        get() = delegate.responses

    override fun sendResponse(response: AndroidGattResponse): Boolean {
        delegate.responses += response
        return acceptResponses
    }

    fun emit(event: AndroidGattEvent) = delegate.emit(event)
}
