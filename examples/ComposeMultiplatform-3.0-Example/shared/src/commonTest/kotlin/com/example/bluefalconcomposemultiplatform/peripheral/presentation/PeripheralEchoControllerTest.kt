package com.example.bluefalconcomposemultiplatform.peripheral.presentation

import com.example.bluefalconcomposemultiplatform.peripheral.EchoGatt
import com.example.bluefalconcomposemultiplatform.peripheral.PeripheralExampleRuntime
import dev.bluefalcon.peripheral.BlueFalconPeripheral
import dev.bluefalcon.peripheral.CharacteristicProperty
import dev.bluefalcon.peripheral.DisconnectResult
import dev.bluefalcon.peripheral.GattCharacteristicId
import dev.bluefalcon.peripheral.GattServerRequest
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PeripheralEchoControllerTest {

    @Test
    fun unsupportedRuntimeDisablesActions() = runTest {
        val controller = PeripheralEchoController(
            runtime = null,
            scope = backgroundScope,
        )

        assertFalse(controller.state.value.supported)
        assertFalse(controller.state.value.canStart)
        assertFalse(controller.state.value.canStop)
        assertFalse(controller.state.value.canSend)
    }

    @Test
    fun startUsesEchoConfigAndStopCallsManager() = runTest {
        val manager = FakePeripheral()
        val controller = PeripheralEchoController(
            runtime = PeripheralExampleRuntime(manager, FakeQueue()),
            scope = backgroundScope,
        )

        controller.start()

        val firstConfig = assertNotNull(manager.startConfigs.singleOrNull())
        assertEquals("Blue Falcon Echo", firstConfig.advertiseConfig.localName)
        assertEquals(
            listOf(EchoGatt.serviceUuid),
            firstConfig.advertiseConfig.serviceUuids,
        )
        assertEquals(EchoGatt.restorationIdentifier, firstConfig.restorationIdentifier)

        val service = firstConfig.advertiseConfig.services.single()
        assertEquals(EchoGatt.serviceUuid, service.uuid)

        val characteristic = service.characteristics.single()
        assertEquals(EchoGatt.characteristicUuid, characteristic.uuid)
        assertEquals(
            setOf(
                CharacteristicProperty.READ,
                CharacteristicProperty.WRITE,
                CharacteristicProperty.WRITE_NO_RESPONSE,
                CharacteristicProperty.NOTIFY,
                CharacteristicProperty.INDICATE,
            ),
            characteristic.properties,
        )
        assertContentEquals(
            "Hello from Blue Falcon".encodeToByteArray(),
            characteristic.initialValue,
        )

        controller.stop()
        controller.start()

        assertEquals(1, manager.stopCalls)
        assertEquals(2, manager.startConfigs.size)
        assertSame(firstConfig, manager.startConfigs.last())
    }

    @Test
    fun lifecycleFailuresAreCaughtAndLogStaysBounded() = runTest {
        val manager = FakePeripheral()
        val controller = PeripheralEchoController(
            runtime = PeripheralExampleRuntime(manager, FakeQueue()),
            scope = backgroundScope,
        )

        manager.startFailure = IllegalStateException("start unavailable")
        controller.start()

        assertEquals(
            listOf("Start failed: start unavailable"),
            controller.state.value.log,
        )

        manager.stopFailure = IllegalStateException("stop unavailable")
        repeat(101) {
            controller.stop()
        }

        assertEquals(100, controller.state.value.log.size)
        assertTrue(
            controller.state.value.log.all { message ->
                message == "Stop failed: stop unavailable"
            },
        )
    }

    @Test
    fun startFailureRemainsStoppableAndRecoversToStopped() = runTest {
        val manager = FakePeripheral()
        val controller = PeripheralEchoController(
            runtime = PeripheralExampleRuntime(manager, FakeQueue()),
            scope = backgroundScope,
        )
        manager.mutableSessions.value = setOf(
            FakeSession(initialSubscriptions = setOf(EchoGatt.characteristicId)),
        )
        runCurrent()
        val failure = IllegalStateException("start unavailable")

        manager.startFailure = failure
        controller.start()
        runCurrent()

        val failedState = assertIs<PeripheralManagerState.Failed>(
            controller.state.value.managerState,
        )
        assertSame(failure, failedState.cause)
        assertEquals(
            listOf("Start failed: start unavailable"),
            controller.state.value.log,
        )
        assertTrue(controller.state.value.canStop)
        assertFalse(controller.state.value.canStart)
        assertEquals(1, controller.state.value.subscribedSessionCount)
        assertFalse(controller.state.value.canSend)

        manager.startFailure = null
        controller.stop()
        runCurrent()

        assertEquals(PeripheralManagerState.Stopped, controller.state.value.managerState)
        assertTrue(controller.state.value.canStart)
        assertFalse(controller.state.value.canStop)
    }

    @Test
    fun stopFailureRemainsStoppableAndRetryRecovers() = runTest {
        val manager = FakePeripheral()
        val controller = PeripheralEchoController(
            runtime = PeripheralExampleRuntime(manager, FakeQueue()),
            scope = backgroundScope,
        )
        manager.mutableSessions.value = setOf(
            FakeSession(initialSubscriptions = setOf(EchoGatt.characteristicId)),
        )
        controller.start()
        runCurrent()
        assertTrue(controller.state.value.canSend)
        val failure = IllegalStateException("stop unavailable")

        manager.stopFailure = failure
        controller.stop()
        runCurrent()

        val failedState = assertIs<PeripheralManagerState.Failed>(
            controller.state.value.managerState,
        )
        assertSame(failure, failedState.cause)
        assertEquals(
            listOf("Stop failed: stop unavailable"),
            controller.state.value.log,
        )
        assertTrue(controller.state.value.canStop)
        assertFalse(controller.state.value.canStart)
        assertEquals(1, controller.state.value.subscribedSessionCount)
        assertFalse(controller.state.value.canSend)

        manager.stopFailure = null
        controller.stop()
        runCurrent()

        assertEquals(PeripheralManagerState.Stopped, controller.state.value.managerState)
        assertTrue(controller.state.value.canStart)
        assertFalse(controller.state.value.canStop)
    }

    @Test
    fun startErrorPropagatesWithoutLogging() = runTest {
        val manager = FakePeripheral()
        val controller = PeripheralEchoController(
            runtime = PeripheralExampleRuntime(manager, FakeQueue()),
            scope = backgroundScope,
        )
        val startError = AssertionError("fatal start")
        manager.startFailure = startError

        val thrownStartError = assertFailsWith<AssertionError> {
            controller.start()
        }

        assertSame(startError, thrownStartError)
        assertTrue(controller.state.value.log.isEmpty())
    }

    @Test
    fun stopErrorPropagatesWithoutLogging() = runTest {
        val manager = FakePeripheral()
        val controller = PeripheralEchoController(
            runtime = PeripheralExampleRuntime(manager, FakeQueue()),
            scope = backgroundScope,
        )
        controller.start()
        val stopError = AssertionError("fatal stop")
        manager.stopFailure = stopError

        val thrownStopError = assertFailsWith<AssertionError> {
            controller.stop()
        }

        assertSame(stopError, thrownStopError)
        assertTrue(controller.state.value.log.isEmpty())
    }

    @Test
    fun startCancellationPropagatesWithoutLogging() = runTest {
        val manager = FakePeripheral()
        val controller = PeripheralEchoController(
            runtime = PeripheralExampleRuntime(manager, FakeQueue()),
            scope = backgroundScope,
        )
        val cancellation = CancellationException("cancel start")
        manager.startFailure = cancellation

        val thrownCancellation = assertFailsWith<CancellationException> {
            controller.start()
        }

        assertSame(cancellation, thrownCancellation)
        assertTrue(controller.state.value.log.isEmpty())
    }

    @Test
    fun stopCancellationPropagatesWithoutLogging() = runTest {
        val manager = FakePeripheral()
        val controller = PeripheralEchoController(
            runtime = PeripheralExampleRuntime(manager, FakeQueue()),
            scope = backgroundScope,
        )
        controller.start()
        val cancellation = CancellationException("cancel stop")
        manager.stopFailure = cancellation

        val thrownCancellation = assertFailsWith<CancellationException> {
            controller.stop()
        }

        assertSame(cancellation, thrownCancellation)
        assertTrue(controller.state.value.log.isEmpty())
    }

    @Test
    fun sessionsAndManagerStateRemainReactive() = runTest {
        val manager = FakePeripheral()
        val session = FakeSession(
            initialSubscriptions = setOf(EchoGatt.characteristicId),
        )
        val controller = PeripheralEchoController(
            runtime = PeripheralExampleRuntime(manager, FakeQueue()),
            scope = backgroundScope,
        )

        manager.mutableState.value = PeripheralManagerState.Running
        manager.mutableSessions.value = setOf(session)
        runCurrent()

        assertEquals(PeripheralManagerState.Running, controller.state.value.managerState)
        assertEquals(1, controller.state.value.sessionCount)
        assertEquals(1, controller.state.value.subscribedSessionCount)
        assertTrue(controller.state.value.canStop)
        assertTrue(controller.state.value.canSend)

        session.mutableSubscriptions.value = emptySet()
        runCurrent()

        assertEquals(1, controller.state.value.sessionCount)
        assertEquals(0, controller.state.value.subscribedSessionCount)
        assertFalse(controller.state.value.canSend)

        session.mutableSubscriptions.value = setOf(EchoGatt.characteristicId)
        runCurrent()

        assertEquals(1, controller.state.value.subscribedSessionCount)
        assertTrue(controller.state.value.canSend)
    }

    @Test
    fun replacingSessionsStopsObservingRemovedSubscriptions() = runTest {
        val manager = FakePeripheral()
        val removed = FakeSession(
            id = PeripheralSessionId("removed"),
            initialSubscriptions = setOf(EchoGatt.characteristicId),
        )
        val retained = FakeSession(
            id = PeripheralSessionId("retained"),
            initialSubscriptions = setOf(EchoGatt.characteristicId),
        )
        val replacement = FakeSession(
            id = PeripheralSessionId("replacement"),
        )
        val controller = PeripheralEchoController(
            runtime = PeripheralExampleRuntime(manager, FakeQueue()),
            scope = backgroundScope,
        )

        manager.mutableSessions.value = setOf(removed, retained)
        runCurrent()

        assertEquals(2, controller.state.value.sessionCount)
        assertEquals(2, controller.state.value.subscribedSessionCount)

        manager.mutableSessions.value = setOf(retained, replacement)
        runCurrent()

        assertEquals(2, controller.state.value.sessionCount)
        assertEquals(1, controller.state.value.subscribedSessionCount)

        removed.mutableSubscriptions.value = emptySet()
        runCurrent()
        removed.mutableSubscriptions.value = setOf(EchoGatt.characteristicId)
        runCurrent()

        assertEquals(1, controller.state.value.subscribedSessionCount)

        replacement.mutableSubscriptions.value = setOf(EchoGatt.characteristicId)
        runCurrent()

        assertEquals(2, controller.state.value.subscribedSessionCount)
    }
}

private class FakePeripheral : BlueFalconPeripheral {
    val mutableState = MutableStateFlow<PeripheralManagerState>(
        PeripheralManagerState.Stopped,
    )
    override val state: StateFlow<PeripheralManagerState> = mutableState.asStateFlow()

    override val capabilities: PeripheralCapabilities = PeripheralCapabilities.Unsupported
    override val plugins: PeripheralPluginRegistry = UnsupportedPluginRegistry

    val mutableSessions = MutableStateFlow<Set<PeripheralSession>>(emptySet())
    override val sessions: StateFlow<Set<PeripheralSession>> =
        mutableSessions.asStateFlow()

    private val requestChannel = Channel<GattServerRequest>(Channel.BUFFERED)
    override val requests: Flow<GattServerRequest> = requestChannel.receiveAsFlow()
    override val events: Flow<PeripheralEvent> = emptyFlow()
    override val notificationReadiness: Flow<NotificationReadiness> = emptyFlow()
    override val notificationReadinessState: StateFlow<NotificationReadinessState> =
        MutableStateFlow(NotificationReadinessState()).asStateFlow()

    val startConfigs = mutableListOf<PeripheralConfig>()
    var stopCalls = 0
    var startFailure: Throwable? = null
    var stopFailure: Throwable? = null

    override suspend fun start(config: PeripheralConfig) {
        startFailure?.let { cause ->
            mutableState.value = PeripheralManagerState.Failed(cause)
            throw cause
        }
        startConfigs += config
        mutableState.value = PeripheralManagerState.Running
    }

    override suspend fun stop() {
        stopFailure?.let { cause ->
            mutableState.value = PeripheralManagerState.Failed(cause)
            throw cause
        }
        stopCalls += 1
        mutableState.value = PeripheralManagerState.Stopped
    }

    override suspend fun close() {
        mutableState.value = PeripheralManagerState.Closed
    }
}

private class FakeQueue(
    var result: QueueSendResult = QueueSendResult.Sent,
) : PeripheralQueue {
    val sendCalls = mutableListOf<SendCall>()

    override suspend fun send(
        session: PeripheralSession,
        characteristic: GattCharacteristicId,
        value: ByteArray,
        mode: NotificationMode,
    ): QueueSendResult {
        sendCalls += SendCall(
            session = session,
            characteristic = characteristic,
            value = value.copyOf(),
            mode = mode,
        )
        return result
    }
}

private data class SendCall(
    val session: PeripheralSession,
    val characteristic: GattCharacteristicId,
    val value: ByteArray,
    val mode: NotificationMode,
)

private class FakeSession(
    override val id: PeripheralSessionId = PeripheralSessionId("session-1"),
    initialSubscriptions: Set<GattCharacteristicId> = emptySet(),
) : PeripheralSession {
    private val mutableState = MutableStateFlow<SessionState>(SessionState.Active)
    override val state: StateFlow<SessionState> = mutableState.asStateFlow()

    val mutableSubscriptions = MutableStateFlow(initialSubscriptions)
    override val subscriptions: StateFlow<Set<GattCharacteristicId>> =
        mutableSubscriptions.asStateFlow()

    override val maximumUpdateValueLength: StateFlow<Int?> =
        MutableStateFlow<Int?>(null).asStateFlow()
    override val notificationReady: Flow<Unit> = emptyFlow()

    val notifyCalls = mutableListOf<NotifyCall>()

    override suspend fun notify(
        characteristic: GattCharacteristicId,
        value: ByteArray,
        mode: NotificationMode,
    ): NotificationResult {
        notifyCalls += NotifyCall(
            characteristic = characteristic,
            value = value.copyOf(),
            mode = mode,
        )
        return NotificationResult.Sent
    }

    override suspend fun disconnect(): DisconnectResult {
        mutableState.value = SessionState.Closed
        return DisconnectResult.Disconnected
    }
}

private data class NotifyCall(
    val characteristic: GattCharacteristicId,
    val value: ByteArray,
    val mode: NotificationMode,
)

private object UnsupportedPluginRegistry : PeripheralPluginRegistry {
    override fun <C : PeripheralPluginConfig, T> install(
        factory: PeripheralPluginFactory<C, T>,
        configure: C.() -> Unit,
    ): T = error("Plugins are not installed because the queue is injected")
}
