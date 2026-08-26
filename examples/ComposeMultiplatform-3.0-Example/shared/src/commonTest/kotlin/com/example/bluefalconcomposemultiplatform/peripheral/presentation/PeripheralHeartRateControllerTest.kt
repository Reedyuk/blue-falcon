package com.example.bluefalconcomposemultiplatform.peripheral.presentation

import com.example.bluefalconcomposemultiplatform.peripheral.ClientCharacteristicConfigurationDescriptor
import com.example.bluefalconcomposemultiplatform.peripheral.HeartRateGatt
import dev.bluefalcon.core.toUuid
import com.example.bluefalconcomposemultiplatform.peripheral.PeripheralExampleRuntime
import dev.bluefalcon.peripheral.BlueFalconPeripheral
import dev.bluefalcon.peripheral.CharacteristicProperty
import dev.bluefalcon.peripheral.DisconnectResult
import dev.bluefalcon.peripheral.GattCharacteristicId
import dev.bluefalcon.peripheral.GattCharacteristicReadRequest
import dev.bluefalcon.peripheral.GattCharacteristicWriteRequest
import dev.bluefalcon.peripheral.GattDescriptorId
import dev.bluefalcon.peripheral.GattDescriptorWriteRequest
import dev.bluefalcon.peripheral.GattResponseHandle
import dev.bluefalcon.peripheral.GattResponseResult
import dev.bluefalcon.peripheral.GattResponseStatus
import dev.bluefalcon.peripheral.GattServerRequest
import dev.bluefalcon.peripheral.GattServiceId
import dev.bluefalcon.peripheral.NotificationMode
import dev.bluefalcon.peripheral.NotificationReadiness
import dev.bluefalcon.peripheral.NotificationReadinessState
import dev.bluefalcon.peripheral.NotificationResult
import dev.bluefalcon.peripheral.PeripheralCapabilities
import dev.bluefalcon.peripheral.PeripheralConfig
import dev.bluefalcon.peripheral.PeripheralEvent
import dev.bluefalcon.peripheral.PeripheralManagerState
import dev.bluefalcon.peripheral.PeripheralPluginConfig
import dev.bluefalcon.peripheral.PeripheralPluginFactory
import dev.bluefalcon.peripheral.PeripheralPluginRegistry
import dev.bluefalcon.peripheral.PeripheralSession
import dev.bluefalcon.peripheral.PeripheralSessionId
import dev.bluefalcon.peripheral.SessionState
import dev.bluefalcon.plugins.queue.PeripheralQueue
import dev.bluefalcon.plugins.queue.QueueSendResult
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalUuidApi::class)
class PeripheralHeartRateControllerTest {

    @Test
    fun unsupportedRuntimeDisablesActions() = runTest {
        val controller = PeripheralHeartRateController(
            runtime = null,
            scope = backgroundScope,
        )

        assertFalse(controller.state.value.supported)
        assertFalse(controller.state.value.canStart)
        assertFalse(controller.state.value.canStop)
        assertEquals(PeripheralProfile.HEART_RATE_MONITOR, controller.state.value.profile)
    }

    @Test
    fun startAdvertisesHeartRateProfile() = runTest {
        val manager = HeartRateFakePeripheral()
        val controller = PeripheralHeartRateController(
            runtime = PeripheralExampleRuntime(manager, HeartRateFakeQueue()),
            scope = backgroundScope,
        )

        controller.start()

        val config = manager.startConfigs.single()
        assertEquals("Blue Falcon Heart Rate", config.advertiseConfig.localName)
        assertEquals(listOf(HeartRateGatt.serviceUuid), config.advertiseConfig.serviceUuids)

        val service = config.advertiseConfig.services.single()
        assertEquals(HeartRateGatt.serviceUuid, service.uuid)

        val heartRateMeasurement = service.characteristics.single { characteristic ->
            characteristic.uuid == HeartRateGatt.heartRateMeasurementUuid
        }
        assertEquals(setOf(CharacteristicProperty.NOTIFY), heartRateMeasurement.properties)

        val bodySensorLocation = service.characteristics.single { characteristic ->
            characteristic.uuid == HeartRateGatt.bodySensorLocationUuid
        }
        assertEquals(setOf(CharacteristicProperty.READ), bodySensorLocation.properties)
        assertContentEquals(
            byteArrayOf(HeartRateGatt.BODY_SENSOR_LOCATION_CHEST),
            bodySensorLocation.initialValue,
        )

        val controlPoint = service.characteristics.single { characteristic ->
            characteristic.uuid == HeartRateGatt.heartRateControlPointUuid
        }
        assertEquals(setOf(CharacteristicProperty.WRITE), controlPoint.properties)
    }

    @Test
    fun bodySensorLocationReadRequiresBondingBeforeSucceeding() = runTest {
        val manager = HeartRateFakePeripheral()
        val controller = PeripheralHeartRateController(
            runtime = PeripheralExampleRuntime(manager, HeartRateFakeQueue()),
            scope = backgroundScope,
            initialBondingRequired = true,
        )
        manager.mutableState.value = PeripheralManagerState.Running
        val session = HeartRateFakeSession()
        manager.mutableSessions.value = setOf(session)
        runCurrent()

        val firstResponse = HeartRateRecordingResponseHandle()
        manager.requestsChannel.send(
            GattCharacteristicReadRequest(
                session = session,
                serviceId = HeartRateGatt.serviceId,
                characteristicId = HeartRateGatt.bodySensorLocationId,
                offset = 0,
                response = firstResponse,
            ),
        )
        runCurrent()

        assertEquals(
            GattResponseStatus.InsufficientAuthentication,
            firstResponse.singleStatus,
        )
        assertEquals(0, controller.state.value.bondedSessionCount)

        val secondResponse = HeartRateRecordingResponseHandle()
        manager.requestsChannel.send(
            GattCharacteristicReadRequest(
                session = session,
                serviceId = HeartRateGatt.serviceId,
                characteristicId = HeartRateGatt.bodySensorLocationId,
                offset = 0,
                response = secondResponse,
            ),
        )
        runCurrent()

        assertEquals(GattResponseStatus.Success, secondResponse.singleStatus)
        assertContentEquals(
            byteArrayOf(HeartRateGatt.BODY_SENSOR_LOCATION_CHEST),
            secondResponse.singleValue,
        )
        assertEquals(1, controller.state.value.bondedSessionCount)

        val thirdResponse = HeartRateRecordingResponseHandle()
        manager.requestsChannel.send(
            GattCharacteristicReadRequest(
                session = session,
                serviceId = HeartRateGatt.serviceId,
                characteristicId = HeartRateGatt.bodySensorLocationId,
                offset = 0,
                response = thirdResponse,
            ),
        )
        runCurrent()

        assertEquals(GattResponseStatus.Success, thirdResponse.singleStatus)
        assertEquals(1, controller.state.value.bondedSessionCount)
    }

    @Test
    fun bondingNotEnforcedAllowsImmediateRead() = runTest {
        val manager = HeartRateFakePeripheral()
        val controller = PeripheralHeartRateController(
            runtime = PeripheralExampleRuntime(manager, HeartRateFakeQueue()),
            scope = backgroundScope,
            initialBondingRequired = false,
        )
        val session = HeartRateFakeSession()
        manager.mutableSessions.value = setOf(session)
        runCurrent()

        assertFalse(controller.state.value.bondingRequired)

        val response = HeartRateRecordingResponseHandle()
        manager.requestsChannel.send(
            GattCharacteristicReadRequest(
                session = session,
                serviceId = HeartRateGatt.serviceId,
                characteristicId = HeartRateGatt.bodySensorLocationId,
                offset = 0,
                response = response,
            ),
        )
        runCurrent()

        assertEquals(GattResponseStatus.Success, response.singleStatus)
        assertContentEquals(
            byteArrayOf(HeartRateGatt.BODY_SENSOR_LOCATION_CHEST),
            response.singleValue,
        )
        assertEquals(0, controller.state.value.bondedSessionCount)
    }

    @Test
    fun bondingRequirementCanOnlyBeChangedWhileStopped() = runTest {
        val manager = HeartRateFakePeripheral()
        val controller = PeripheralHeartRateController(
            runtime = PeripheralExampleRuntime(manager, HeartRateFakeQueue()),
            scope = backgroundScope,
        )

        controller.setBondingRequired(false)
        assertFalse(controller.state.value.bondingRequired)

        controller.start()
        runCurrent()

        controller.setBondingRequired(true)
        assertFalse(controller.state.value.bondingRequired)
    }

    @Test
    fun heartRateMeasurementReadIsRejected() = runTest {
        val manager = HeartRateFakePeripheral()
        PeripheralHeartRateController(
            runtime = PeripheralExampleRuntime(manager, HeartRateFakeQueue()),
            scope = backgroundScope,
        )
        val session = HeartRateFakeSession()
        manager.mutableSessions.value = setOf(session)
        runCurrent()

        val response = HeartRateRecordingResponseHandle()
        manager.requestsChannel.send(
            GattCharacteristicReadRequest(
                session = session,
                serviceId = HeartRateGatt.serviceId,
                characteristicId = HeartRateGatt.heartRateMeasurementId,
                offset = 0,
                response = response,
            ),
        )
        runCurrent()

        assertEquals(GattResponseStatus.ReadNotPermitted, response.singleStatus)
    }

    @Test
    fun heartRateMeasurementReadRequiresBondingWhenEnabled() = runTest {
        val manager = HeartRateFakePeripheral()
        val controller = PeripheralHeartRateController(
            runtime = PeripheralExampleRuntime(manager, HeartRateFakeQueue()),
            scope = backgroundScope,
            initialBondOnHeartRateRead = true,
        )
        manager.mutableState.value = PeripheralManagerState.Running
        val session = HeartRateFakeSession()
        manager.mutableSessions.value = setOf(session)
        runCurrent()

        val firstResponse = HeartRateRecordingResponseHandle()
        manager.requestsChannel.send(
            GattCharacteristicReadRequest(
                session = session,
                serviceId = HeartRateGatt.serviceId,
                characteristicId = HeartRateGatt.heartRateMeasurementId,
                offset = 0,
                response = firstResponse,
            ),
        )
        runCurrent()

        assertEquals(
            GattResponseStatus.InsufficientAuthentication,
            firstResponse.singleStatus,
        )
        assertEquals(0, controller.state.value.bondedSessionCount)

        val secondResponse = HeartRateRecordingResponseHandle()
        manager.requestsChannel.send(
            GattCharacteristicReadRequest(
                session = session,
                serviceId = HeartRateGatt.serviceId,
                characteristicId = HeartRateGatt.heartRateMeasurementId,
                offset = 0,
                response = secondResponse,
            ),
        )
        runCurrent()

        assertEquals(GattResponseStatus.Success, secondResponse.singleStatus)
        assertContentEquals(
            byteArrayOf(0x00, controller.state.value.heartRateBpm.toByte()),
            secondResponse.singleValue,
        )
        assertEquals(1, controller.state.value.bondedSessionCount)
    }

    @Test
    fun bondOnHeartRateReadNotEnforcedKeepsRejectingReads() = runTest {
        val manager = HeartRateFakePeripheral()
        val controller = PeripheralHeartRateController(
            runtime = PeripheralExampleRuntime(manager, HeartRateFakeQueue()),
            scope = backgroundScope,
            initialBondOnHeartRateRead = false,
        )
        val session = HeartRateFakeSession()
        manager.mutableSessions.value = setOf(session)
        runCurrent()

        assertFalse(controller.state.value.bondOnHeartRateRead)

        val response = HeartRateRecordingResponseHandle()
        manager.requestsChannel.send(
            GattCharacteristicReadRequest(
                session = session,
                serviceId = HeartRateGatt.serviceId,
                characteristicId = HeartRateGatt.heartRateMeasurementId,
                offset = 0,
                response = response,
            ),
        )
        runCurrent()

        assertEquals(GattResponseStatus.ReadNotPermitted, response.singleStatus)
        assertEquals(0, controller.state.value.bondedSessionCount)
    }

    @Test
    fun bondOnHeartRateReadCanOnlyBeChangedWhileStopped() = runTest {
        val manager = HeartRateFakePeripheral()
        val controller = PeripheralHeartRateController(
            runtime = PeripheralExampleRuntime(manager, HeartRateFakeQueue()),
            scope = backgroundScope,
        )

        controller.setBondOnHeartRateRead(true)
        assertTrue(controller.state.value.bondOnHeartRateRead)

        controller.start()
        runCurrent()

        controller.setBondOnHeartRateRead(false)
        assertTrue(controller.state.value.bondOnHeartRateRead)
    }

    @Test
    fun heartRateMeasurementCccdWriteIsAccepted() = runTest {
        val manager = HeartRateFakePeripheral()
        PeripheralHeartRateController(
            runtime = PeripheralExampleRuntime(manager, HeartRateFakeQueue()),
            scope = backgroundScope,
        )
        val session = HeartRateFakeSession()
        manager.mutableSessions.value = setOf(session)
        runCurrent()

        val response = HeartRateRecordingResponseHandle()
        manager.requestsChannel.send(
            GattDescriptorWriteRequest(
                session = session,
                serviceId = HeartRateGatt.serviceId,
                characteristicId = HeartRateGatt.heartRateMeasurementId,
                descriptorId = ClientCharacteristicConfigurationDescriptor.id,
                offset = 0,
                value = byteArrayOf(0x01, 0x00),
                preparedWrite = false,
                response = response,
            ),
        )
        runCurrent()

        // Accepting this write is what lets the peripheral framework register the
        // central's notification subscription; rejecting it (as previously happened
        // for every descriptor write) breaks heart rate notifications on platforms -
        // like Android - that surface CCCD writes to the app.
        assertEquals(GattResponseStatus.Success, response.singleStatus)
    }

    @Test
    fun heartRateMeasurementCccdWriteRequiresBondingWhenEnabled() = runTest {
        val manager = HeartRateFakePeripheral()
        val controller = PeripheralHeartRateController(
            runtime = PeripheralExampleRuntime(manager, HeartRateFakeQueue()),
            scope = backgroundScope,
            initialBondingRequired = true,
        )
        manager.mutableState.value = PeripheralManagerState.Running
        val session = HeartRateFakeSession()
        manager.mutableSessions.value = setOf(session)
        runCurrent()

        // Standard Bluetooth behaviour for a security-gated attribute on an unbonded
        // link: the connection stays up and the server rejects the operation with
        // InsufficientAuthentication (not a disconnect, not a silent no-op), which
        // prompts the platform Bluetooth stack to bond and retry automatically.
        val firstResponse = HeartRateRecordingResponseHandle()
        manager.requestsChannel.send(
            GattDescriptorWriteRequest(
                session = session,
                serviceId = HeartRateGatt.serviceId,
                characteristicId = HeartRateGatt.heartRateMeasurementId,
                descriptorId = ClientCharacteristicConfigurationDescriptor.id,
                offset = 0,
                value = byteArrayOf(0x01, 0x00),
                preparedWrite = false,
                response = firstResponse,
            ),
        )
        runCurrent()

        assertEquals(
            GattResponseStatus.InsufficientAuthentication,
            firstResponse.singleStatus,
        )
        assertEquals(0, controller.state.value.bondedSessionCount)

        val secondResponse = HeartRateRecordingResponseHandle()
        manager.requestsChannel.send(
            GattDescriptorWriteRequest(
                session = session,
                serviceId = HeartRateGatt.serviceId,
                characteristicId = HeartRateGatt.heartRateMeasurementId,
                descriptorId = ClientCharacteristicConfigurationDescriptor.id,
                offset = 0,
                value = byteArrayOf(0x01, 0x00),
                preparedWrite = false,
                response = secondResponse,
            ),
        )
        runCurrent()

        assertEquals(GattResponseStatus.Success, secondResponse.singleStatus)
        assertEquals(1, controller.state.value.bondedSessionCount)
    }

    @Test
    fun unknownDescriptorWriteIsUnsupported() = runTest {
        val manager = HeartRateFakePeripheral()
        PeripheralHeartRateController(
            runtime = PeripheralExampleRuntime(manager, HeartRateFakeQueue()),
            scope = backgroundScope,
        )
        val session = HeartRateFakeSession()
        manager.mutableSessions.value = setOf(session)
        runCurrent()

        val response = HeartRateRecordingResponseHandle()
        manager.requestsChannel.send(
            GattDescriptorWriteRequest(
                session = session,
                serviceId = HeartRateGatt.serviceId,
                characteristicId = HeartRateGatt.heartRateMeasurementId,
                descriptorId = GattDescriptorId("00002901-0000-1000-8000-00805f9b34fb".toUuid()),
                offset = 0,
                value = byteArrayOf(0x00),
                preparedWrite = false,
                response = response,
            ),
        )
        runCurrent()

        assertEquals(GattResponseStatus.RequestNotSupported, response.singleStatus)
    }

    @Test
    fun controlPointResetSucceedsAndUnsupportedOpcodeIsRejected() = runTest {
        val manager = HeartRateFakePeripheral()
        PeripheralHeartRateController(
            runtime = PeripheralExampleRuntime(manager, HeartRateFakeQueue()),
            scope = backgroundScope,
        )
        val session = HeartRateFakeSession()
        manager.mutableSessions.value = setOf(session)
        runCurrent()

        val resetResponse = HeartRateRecordingResponseHandle()
        manager.requestsChannel.send(
            GattCharacteristicWriteRequest(
                session = session,
                serviceId = HeartRateGatt.serviceId,
                characteristicId = HeartRateGatt.heartRateControlPointId,
                offset = 0,
                value = byteArrayOf(HeartRateGatt.CONTROL_POINT_RESET_ENERGY_EXPENDED),
                preparedWrite = false,
                response = resetResponse,
            ),
        )
        runCurrent()
        assertEquals(GattResponseStatus.Success, resetResponse.singleStatus)

        val unsupportedResponse = HeartRateRecordingResponseHandle()
        manager.requestsChannel.send(
            GattCharacteristicWriteRequest(
                session = session,
                serviceId = HeartRateGatt.serviceId,
                characteristicId = HeartRateGatt.heartRateControlPointId,
                offset = 0,
                value = byteArrayOf(0x02),
                preparedWrite = false,
                response = unsupportedResponse,
            ),
        )
        runCurrent()
        assertEquals(GattResponseStatus.RequestNotSupported, unsupportedResponse.singleStatus)
    }

    @Test
    fun unknownHandleIsRejected() = runTest {
        val manager = HeartRateFakePeripheral()
        PeripheralHeartRateController(
            runtime = PeripheralExampleRuntime(manager, HeartRateFakeQueue()),
            scope = backgroundScope,
        )
        val session = HeartRateFakeSession()
        manager.mutableSessions.value = setOf(session)
        runCurrent()

        val response = HeartRateRecordingResponseHandle()
        manager.requestsChannel.send(
            GattCharacteristicReadRequest(
                session = session,
                serviceId = GattServiceId("00002a19-0000-1000-8000-00805f9b34fb".toUuid()),
                characteristicId = HeartRateGatt.bodySensorLocationId,
                offset = 0,
                response = response,
            ),
        )
        runCurrent()

        assertEquals(GattResponseStatus.InvalidHandle, response.singleStatus)
    }

    @Test
    fun simulationNotifiesSubscribedSessionsUntilToggledOff() = runTest {
        val manager = HeartRateFakePeripheral()
        val queue = HeartRateFakeQueue()
        val controller = PeripheralHeartRateController(
            runtime = PeripheralExampleRuntime(manager, queue),
            scope = backgroundScope,
        )
        controller.start()
        val session = HeartRateFakeSession(
            initialSubscriptions = setOf(HeartRateGatt.heartRateMeasurementId),
        )
        manager.mutableSessions.value = setOf(session)
        runCurrent()

        assertTrue(controller.state.value.canToggleHeartRateSimulation)
        controller.toggleHeartRateSimulation()
        runCurrent()
        assertTrue(controller.state.value.simulatingHeartRate)

        advanceTimeBy(1.seconds)
        runCurrent()

        assertTrue(queue.sendCalls.isNotEmpty())
        assertEquals(
            HeartRateGatt.heartRateMeasurementId,
            queue.sendCalls.first().characteristic,
        )

        controller.toggleHeartRateSimulation()
        runCurrent()
        assertFalse(controller.state.value.simulatingHeartRate)
    }
}

private class HeartRateRecordingResponseHandle : GattResponseHandle {
    private val recordedResponses = mutableListOf<HeartRateRecordedResponse>()

    val singleStatus: GattResponseStatus
        get() = recordedResponses.single().status
    val singleValue: ByteArray?
        get() = recordedResponses.single().value?.copyOf()

    override suspend fun respond(
        status: GattResponseStatus,
        value: ByteArray?,
    ): GattResponseResult {
        recordedResponses += HeartRateRecordedResponse(status, value?.copyOf())
        return GattResponseResult.Responded
    }
}

private data class HeartRateRecordedResponse(
    val status: GattResponseStatus,
    private val copiedValue: ByteArray?,
) {
    val value: ByteArray?
        get() = copiedValue?.copyOf()
}

private class HeartRateFakePeripheral(
    override val capabilities: PeripheralCapabilities = HEART_RATE_SUPPORTED_TEST_CAPABILITIES,
) : BlueFalconPeripheral {
    val mutableState = MutableStateFlow<PeripheralManagerState>(
        PeripheralManagerState.Stopped,
    )
    override val state: StateFlow<PeripheralManagerState> = mutableState.asStateFlow()

    override val plugins: PeripheralPluginRegistry = HeartRateUnsupportedPluginRegistry

    val mutableSessions = MutableStateFlow<Set<PeripheralSession>>(emptySet())
    override val sessions: StateFlow<Set<PeripheralSession>> =
        mutableSessions.asStateFlow()

    val requestsChannel = Channel<GattServerRequest>(Channel.UNLIMITED)
    override val requests: Flow<GattServerRequest> = requestsChannel.receiveAsFlow()
    override val events: Flow<PeripheralEvent> = emptyFlow()
    override val notificationReadiness: Flow<NotificationReadiness> = emptyFlow()
    override val notificationReadinessState: StateFlow<NotificationReadinessState> =
        MutableStateFlow(NotificationReadinessState()).asStateFlow()

    val startConfigs = mutableListOf<PeripheralConfig>()
    var stopCalls = 0

    override suspend fun start(config: PeripheralConfig) {
        startConfigs += config
        mutableState.value = PeripheralManagerState.Running
    }

    override suspend fun stop() {
        stopCalls += 1
        mutableState.value = PeripheralManagerState.Stopped
    }

    override suspend fun close() {
        mutableState.value = PeripheralManagerState.Closed
    }
}

private class HeartRateFakeQueue(
    var result: QueueSendResult = QueueSendResult.Sent,
) : PeripheralQueue {
    val sendCalls = mutableListOf<HeartRateSendCall>()

    override suspend fun send(
        session: PeripheralSession,
        characteristic: GattCharacteristicId,
        value: ByteArray,
        mode: NotificationMode,
    ): QueueSendResult {
        sendCalls += HeartRateSendCall(session, characteristic, value.copyOf(), mode)
        return result
    }
}

private data class HeartRateSendCall(
    val session: PeripheralSession,
    val characteristic: GattCharacteristicId,
    val value: ByteArray,
    val mode: NotificationMode,
)

private class HeartRateFakeSession(
    id: PeripheralSessionId = PeripheralSessionId("session-1"),
    initialSubscriptions: Set<GattCharacteristicId> = emptySet(),
) : PeripheralSession {
    override val id: PeripheralSessionId = id

    private val mutableState = MutableStateFlow<SessionState>(SessionState.Active)
    override val state: StateFlow<SessionState> = mutableState.asStateFlow()

    val mutableSubscriptions = MutableStateFlow(initialSubscriptions)
    override val subscriptions: StateFlow<Set<GattCharacteristicId>> =
        mutableSubscriptions.asStateFlow()

    override val maximumUpdateValueLength: StateFlow<Int?> =
        MutableStateFlow<Int?>(null).asStateFlow()
    override val notificationReady: Flow<Unit> = emptyFlow()

    override suspend fun notify(
        characteristic: GattCharacteristicId,
        value: ByteArray,
        mode: NotificationMode,
    ): NotificationResult = NotificationResult.Sent

    override suspend fun disconnect(): DisconnectResult {
        mutableState.value = SessionState.Closed
        return DisconnectResult.Disconnected
    }
}

private val HEART_RATE_SUPPORTED_TEST_CAPABILITIES = PeripheralCapabilities(
    localGattServer = true,
    connectableAdvertising = true,
    multiCentral = true,
    targetedNotifications = true,
    notificationReadiness = true,
    maximumUpdateValueLength = true,
    forcedDisconnect = false,
    connectionLifecycleVisibility = false,
    preparedWrites = false,
    stateRestoration = true,
)

private object HeartRateUnsupportedPluginRegistry : PeripheralPluginRegistry {
    override fun <C : PeripheralPluginConfig, T> install(
        factory: PeripheralPluginFactory<C, T>,
        configure: C.() -> Unit,
    ): T {
        throw UnsupportedOperationException("Plugins are not used in this test")
    }
}
