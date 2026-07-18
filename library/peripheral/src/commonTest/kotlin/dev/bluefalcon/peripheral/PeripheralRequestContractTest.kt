package dev.bluefalcon.peripheral

import dev.bluefalcon.core.toUuid
import dev.bluefalcon.peripheral.fake.FakePeripheralBackend
import dev.bluefalcon.peripheral.internal.DefaultBlueFalconPeripheral
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class PeripheralRequestContractTest {

    @Test
    fun inboundWriteIsCopiedAndContainsOwningSession() = runTest {
        val (backend, peripheral) = startedPeripheral()
        val requestDeferred = async(UnconfinedTestDispatcher(testScheduler)) {
            peripheral.requests.first()
        }
        val source = byteArrayOf(1, 2, 3)

        backend.emitCharacteristicWrite(
            sessionId = SessionId,
            serviceId = ServiceId,
            characteristicId = CharacteristicId,
            value = source,
            responseRequired = false,
        )
        source[0] = 99
        runCurrent()

        val request = assertIs<GattCharacteristicWriteRequest>(requestDeferred.await())
        assertEquals(SessionId, request.sessionId)
        assertEquals(SessionId, request.session.id)
        assertContentEquals(byteArrayOf(1, 2, 3), request.value)
        assertNull(request.response)
    }

    @Test
    fun overflowImmediatelyRejectsResponseRequiredRequest() = runTest {
        val (backend, peripheral) = startedPeripheral(requestCapacity = 1)
        val firstResponder = backend.emitCharacteristicRead(
            SessionId,
            ServiceId,
            CharacteristicId,
        )
        val rejectedResponder = backend.emitCharacteristicRead(
            SessionId,
            ServiceId,
            CharacteristicId,
        )

        runCurrent()

        assertEquals(emptyList(), firstResponder.responses)
        assertEquals(1, rejectedResponder.responses.size)
        assertEquals(
            GattResponseStatus.UnlikelyError,
            rejectedResponder.responses.single().status,
        )
        assertNull(rejectedResponder.responses.single().value)
        assertEquals(
            PeripheralEvent.RequestDropped(SessionId, GattRequestType.CharacteristicRead),
            peripheral.events.first(),
        )
    }

    @Test
    fun noResponseWriteOverflowEmitsDropWithoutPlatformResponse() = runTest {
        val (backend, peripheral) = startedPeripheral(requestCapacity = 1)
        backend.emitCharacteristicRead(SessionId, ServiceId, CharacteristicId)

        val responder = backend.emitCharacteristicWrite(
            sessionId = SessionId,
            serviceId = ServiceId,
            characteristicId = CharacteristicId,
            value = byteArrayOf(1),
            responseRequired = false,
        )
        runCurrent()

        assertNull(responder)
        assertEquals(
            PeripheralEvent.RequestDropped(SessionId, GattRequestType.CharacteristicWrite),
            peripheral.events.first(),
        )
    }

    @Test
    fun unhandledRequestExpiresWithFallbackAndTimeoutEvent() = runTest {
        val (backend, peripheral) = startedPeripheral(responseDeadlineMillis = 100)
        val requestDeferred = async(UnconfinedTestDispatcher(testScheduler)) {
            peripheral.requests.first()
        }
        val eventDeferred = async(UnconfinedTestDispatcher(testScheduler)) {
            peripheral.events.first()
        }
        val responder = backend.emitCharacteristicRead(
            SessionId,
            ServiceId,
            CharacteristicId,
        )
        runCurrent()
        val request = requestDeferred.await()

        advanceTimeBy(99)
        runCurrent()
        assertEquals(emptyList(), responder.responses)

        advanceTimeBy(1)
        runCurrent()

        assertEquals(GattResponseStatus.UnlikelyError, responder.responses.single().status)
        assertEquals(
            PeripheralEvent.ResponseTimedOut(SessionId, GattRequestType.CharacteristicRead),
            eventDeferred.await(),
        )
        assertEquals(
            GattResponseResult.Expired,
            request.response?.respond(GattResponseStatus.Success),
        )
    }

    @Test
    fun responseDeadlineStartsAtCallbackWhileLifecycleProcessingIsBlocked() = runTest {
        val (backend, peripheral) = startedPeripheral(responseDeadlineMillis = 100)
        backend.blockStopUntilReleased()
        val stopJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            peripheral.stop()
        }

        val responder = backend.emitCharacteristicRead(
            SessionId,
            ServiceId,
            CharacteristicId,
        )
        advanceTimeBy(100)
        runCurrent()

        assertEquals(GattResponseStatus.UnlikelyError, responder.responses.single().status)

        backend.releaseStop()
        stopJob.join()
    }

    @Test
    fun applicationResponseBeforeDeadlinePreventsFallback() = runTest {
        val (backend, peripheral) = startedPeripheral(responseDeadlineMillis = 100)
        val requestDeferred = async(UnconfinedTestDispatcher(testScheduler)) {
            peripheral.requests.first()
        }
        val responder = backend.emitCharacteristicRead(
            SessionId,
            ServiceId,
            CharacteristicId,
        )
        runCurrent()
        val request = requestDeferred.await()

        assertEquals(
            GattResponseResult.Responded,
            request.response?.respond(GattResponseStatus.Success, byteArrayOf(4, 5)),
        )
        advanceTimeBy(100)
        runCurrent()

        assertEquals(1, responder.responses.size)
        assertEquals(GattResponseStatus.Success, responder.responses.single().status)
        assertContentEquals(byteArrayOf(4, 5), responder.responses.single().value)
        assertNull(withTimeoutOrNull(1.milliseconds) { peripheral.events.first() })
    }

    @Test
    fun sessionCloseExpiresPendingResponseWithoutSendingFallback() = runTest {
        val (backend, peripheral) = startedPeripheral()
        val requestDeferred = async(UnconfinedTestDispatcher(testScheduler)) {
            peripheral.requests.first()
        }
        val responder = backend.emitCharacteristicRead(
            SessionId,
            ServiceId,
            CharacteristicId,
        )
        runCurrent()
        val request = requestDeferred.await()

        backend.closeSession(SessionId)
        runCurrent()

        assertEquals(
            GattResponseResult.Expired,
            request.response?.respond(GattResponseStatus.Success),
        )
        assertEquals(emptyList(), responder.responses)
    }

    @Test
    fun firstReadRequestCreatesItsOwningSession() = runTest {
        val backend = FakePeripheralBackend()
        val peripheral = DefaultBlueFalconPeripheral(backend, coroutineContext)
        peripheral.start(PeripheralConfig(AdvertiseConfig()))
        val requestDeferred = async(UnconfinedTestDispatcher(testScheduler)) {
            withTimeoutOrNull(1.milliseconds) { peripheral.requests.first() }
        }

        backend.emitCharacteristicRead(SessionId, ServiceId, CharacteristicId)
        runCurrent()

        val request = assertNotNull(requestDeferred.await())
        assertEquals(SessionId, request.sessionId)
        assertEquals(SessionId, peripheral.sessions.value.single().id)
    }

    @Test
    fun lateRequestFromStoppedBackendIsRejectedInsteadOfHanging() = runTest {
        val (backend, peripheral) = startedPeripheral()
        peripheral.stop()

        val responder = backend.emitCharacteristicRead(
            SessionId,
            ServiceId,
            CharacteristicId,
        )
        runCurrent()

        assertEquals(1, responder.responses.size)
        assertEquals(GattResponseStatus.UnlikelyError, responder.responses.single().status)
    }

    @Test
    fun pendingResponsePreventsInactivityEvictionUntilItTerminates() = runTest {
        val backend = FakePeripheralBackend(
            capabilities = PeripheralCapabilities.Unsupported.copy(
                localGattServer = true,
                connectionLifecycleVisibility = false,
            ),
        )
        val peripheral = DefaultBlueFalconPeripheral(backend, coroutineContext)
        peripheral.start(
            PeripheralConfig(
                advertiseConfig = AdvertiseConfig(),
                responseDeadline = 200.milliseconds,
                inactiveSessionTimeout = 50.milliseconds,
            ),
        )
        val requestDeferred = async(UnconfinedTestDispatcher(testScheduler)) {
            peripheral.requests.first()
        }
        backend.emitCharacteristicRead(SessionId, ServiceId, CharacteristicId)
        runCurrent()
        val request = requestDeferred.await()

        advanceTimeBy(100)
        runCurrent()
        assertEquals(SessionState.Active, request.session.state.value)

        request.response?.respond(GattResponseStatus.Success)
        runCurrent()
        advanceTimeBy(49)
        runCurrent()
        assertEquals(SessionState.Active, request.session.state.value)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(SessionState.Closed, request.session.state.value)
        assertEquals(emptySet(), peripheral.sessions.value)
    }

    private suspend fun kotlinx.coroutines.test.TestScope.startedPeripheral(
        requestCapacity: Int = 64,
        responseDeadlineMillis: Long = 30_000,
    ): Pair<FakePeripheralBackend, DefaultBlueFalconPeripheral> {
        val backend = FakePeripheralBackend()
        val peripheral = DefaultBlueFalconPeripheral(
            backend = backend,
            coroutineContext = coroutineContext,
            requestCapacity = requestCapacity,
        )
        peripheral.start(
            PeripheralConfig(
                advertiseConfig = AdvertiseConfig(),
                responseDeadline = responseDeadlineMillis.milliseconds,
            ),
        )
        backend.openSession(SessionId)
        runCurrent()
        return backend to peripheral
    }

    private companion object {
        val SessionId = PeripheralSessionId("central-1")
        val ServiceId = GattServiceId(
            "0000180d-0000-1000-8000-00805f9b34fb".toUuid(),
        )
        val CharacteristicId = GattCharacteristicId(
            "00002a37-0000-1000-8000-00805f9b34fb".toUuid(),
        )
    }
}
