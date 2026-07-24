package dev.bluefalcon.peripheral

import dev.bluefalcon.core.toUuid
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class PeripheralApiShapeTest {

    @Test
    fun managerAndSessionContractsExposeOnlyCommonTypes() {
        val manager: BlueFalconPeripheral = RecordingPeripheral()
        val session: PeripheralSession = RecordingSession()

        assertEquals(PeripheralManagerState.Stopped, manager.state.value)
        assertEquals(PeripheralCapabilities.Unsupported, manager.capabilities)
        assertEquals(emptySet(), manager.sessions.value)
        manager.requests
        manager.events
        manager.notificationReadiness

        assertEquals(PeripheralSessionId("central-1"), session.id)
        assertEquals(SessionState.Active, session.state.value)
        assertEquals(emptySet(), session.subscriptions.value)
        assertNull(session.maximumUpdateValueLength.value)
        session.notificationReady
    }

    @Test
    fun characteristicWriteRequestOwnsCopiedValueAndSession() {
        val session = RecordingSession()
        val source = byteArrayOf(1, 2, 3)
        val request = GattCharacteristicWriteRequest(
            session = session,
            serviceId = GattServiceId(SERVICE_UUID.toUuid()),
            characteristicId = GattCharacteristicId(CHARACTERISTIC_UUID.toUuid()),
            offset = 4,
            value = source,
            preparedWrite = false,
            response = null,
        )

        source[0] = 9
        assertContentEquals(byteArrayOf(1, 2, 3), request.value)

        val exposed = request.value
        exposed[1] = 9
        assertContentEquals(byteArrayOf(1, 2, 3), request.value)
        assertEquals(session, request.session)
        assertEquals(session.id, request.sessionId)
        assertNull(request.descriptorId)
    }

    @Test
    fun responseRequiredRequestsExposeNonNullHandle() {
        val response = RecordingResponseHandle()
        val descriptorId = GattDescriptorId(DESCRIPTOR_UUID.toUuid())
        val descriptorRequest = GattDescriptorReadRequest(
            session = RecordingSession(),
            serviceId = GattServiceId(SERVICE_UUID.toUuid()),
            characteristicId = GattCharacteristicId(CHARACTERISTIC_UUID.toUuid()),
            descriptorId = descriptorId,
            offset = 0,
            response = response,
        )
        val readRequest = GattCharacteristicReadRequest(
            session = RecordingSession(),
            serviceId = GattServiceId(SERVICE_UUID.toUuid()),
            characteristicId = GattCharacteristicId(CHARACTERISTIC_UUID.toUuid()),
            offset = 0,
            response = response,
        )
        val executeRequest = GattExecuteWriteRequest(
            session = RecordingSession(),
            execute = true,
            response = response,
        )
        val requiredResponses: List<GattResponseHandle> = listOf(
            descriptorRequest.response,
            readRequest.response,
            executeRequest.response,
        )

        assertEquals(descriptorId, descriptorRequest.descriptorId)
        assertEquals(listOf(response, response, response), requiredResponses)
    }

    @Test
    fun preparedWriteRequiresResponseHandle() {
        assertFailsWith<IllegalArgumentException> {
            GattCharacteristicWriteRequest(
                session = RecordingSession(),
                serviceId = GattServiceId(SERVICE_UUID.toUuid()),
                characteristicId = GattCharacteristicId(CHARACTERISTIC_UUID.toUuid()),
                offset = 0,
                value = byteArrayOf(1),
                preparedWrite = true,
                response = null,
            )
        }
    }

    private class RecordingPeripheral : BlueFalconPeripheral {
        override val state: StateFlow<PeripheralManagerState> =
            MutableStateFlow(PeripheralManagerState.Stopped)
        override val capabilities: PeripheralCapabilities = PeripheralCapabilities.Unsupported
        override val sessions: StateFlow<Set<PeripheralSession>> = MutableStateFlow(emptySet())
        override val requests: Flow<GattServerRequest> = emptyFlow()
        override val events: Flow<PeripheralEvent> = emptyFlow()
        override val notificationReadiness: Flow<NotificationReadiness> = emptyFlow()

        override suspend fun start(config: PeripheralConfig) = Unit
        override suspend fun stop() = Unit
        override suspend fun close() = Unit
    }

    private class RecordingSession : PeripheralSession {
        override val id: PeripheralSessionId = PeripheralSessionId("central-1")
        override val state: StateFlow<SessionState> = MutableStateFlow(SessionState.Active)
        override val subscriptions: StateFlow<Set<GattCharacteristicId>> =
            MutableStateFlow(emptySet())
        override val maximumUpdateValueLength: StateFlow<Int?> = MutableStateFlow(null)
        override val notificationReady: Flow<Unit> = emptyFlow()

        override suspend fun notify(
            characteristic: GattCharacteristicId,
            value: ByteArray,
            mode: NotificationMode,
        ): NotificationResult = NotificationResult.Sent

        override suspend fun disconnect(): DisconnectResult = DisconnectResult.Disconnected
    }

    private class RecordingResponseHandle : GattResponseHandle {
        override suspend fun respond(
            status: GattResponseStatus,
            value: ByteArray?,
        ): GattResponseResult = GattResponseResult.Responded
    }

    private companion object {
        const val SERVICE_UUID = "0000180d-0000-1000-8000-00805f9b34fb"
        const val CHARACTERISTIC_UUID = "00002a37-0000-1000-8000-00805f9b34fb"
        const val DESCRIPTOR_UUID = "00002902-0000-1000-8000-00805f9b34fb"
    }
}
