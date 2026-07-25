package dev.bluefalcon.plugins.queue

import dev.bluefalcon.core.toUuid
import dev.bluefalcon.peripheral.BlueFalconPeripheral
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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class QueuePluginTest {

    @Test
    fun knownMaximumRejectsOversizeValueWithoutCallingSession() = runTest {
        val session = FakeSession(maximumUpdateValueLength = 2)
        val peripheral = FakePeripheral(session)
        val plugin = QueuePlugin.create(QueuePlugin.createConfig())
        val queue = plugin.install(peripheral, backgroundScope)

        val result = queue.send(session, CharacteristicId, byteArrayOf(1, 2, 3))

        assertEquals(QueueSendResult.PayloadTooLarge, result)
        assertTrue(session.calls.isEmpty())
        plugin.close()
    }

    @Test
    fun pluginRejectsNonPositiveLimits() {
        assertFailsWith<IllegalArgumentException> {
            QueuePlugin.create(
                QueuePlugin.createConfig().apply { maxPendingItemsPerSession = 0 },
            )
        }
        assertFailsWith<IllegalArgumentException> {
            QueuePlugin.create(
                QueuePlugin.createConfig().apply { maxPendingBytes = 0 },
            )
        }
    }

    @Test
    fun oneSessionDrainsValuesInFifoOrderAndCopiesEachPayload() = runTest {
        val session = FakeSession(maximumUpdateValueLength = 20)
        val peripheral = FakePeripheral(session)
        val plugin = QueuePlugin.create(QueuePlugin.createConfig())
        val queue = plugin.install(peripheral, backgroundScope)
        val first = byteArrayOf(1)

        val one = async(start = CoroutineStart.UNDISPATCHED) {
            queue.send(session, CharacteristicId, first)
        }
        val two = async(start = CoroutineStart.UNDISPATCHED) {
            queue.send(session, CharacteristicId, byteArrayOf(2))
        }
        first[0] = 99
        runCurrent()

        assertEquals(QueueSendResult.Sent, one.await())
        assertEquals(QueueSendResult.Sent, two.await())
        assertEquals(2, session.calls.size)
        assertContentEquals(byteArrayOf(1), session.calls[0])
        assertContentEquals(byteArrayOf(2), session.calls[1])
        plugin.close()
    }

    @Test
    fun notifyTerminalResultMapsWithoutRetrying() = runTest {
        val session = FakeSession(
            maximumUpdateValueLength = 20,
            notificationResult = NotificationResult.Unsupported,
        )
        val peripheral = FakePeripheral(session)
        val plugin = QueuePlugin.create(QueuePlugin.createConfig())
        val queue = plugin.install(peripheral, backgroundScope)

        assertEquals(
            QueueSendResult.Unsupported,
            queue.send(session, CharacteristicId, byteArrayOf(1)),
        )
        assertEquals(1, session.calls.size)
        plugin.close()
    }

    @Test
    fun sessionsTakeTurnsAcrossEachSchedulingPass() = runTest {
        val submissions = mutableListOf<String>()
        val first = FakeSession(
            20,
            id = PeripheralSessionId("central-a"),
            label = "a",
            submissions = submissions,
        )
        val second = FakeSession(
            20,
            id = PeripheralSessionId("central-b"),
            label = "b",
            submissions = submissions,
        )
        val peripheral = FakePeripheral(first, second)
        val plugin = QueuePlugin.create(QueuePlugin.createConfig())
        val queue = plugin.install(peripheral, backgroundScope)

        val a1 = async(start = CoroutineStart.UNDISPATCHED) {
            queue.send(first, CharacteristicId, byteArrayOf(1))
        }
        val a2 = async(start = CoroutineStart.UNDISPATCHED) {
            queue.send(first, CharacteristicId, byteArrayOf(2))
        }
        val b1 = async(start = CoroutineStart.UNDISPATCHED) {
            queue.send(second, CharacteristicId, byteArrayOf(3))
        }
        runCurrent()

        assertEquals(listOf("a:1", "b:3", "a:2"), submissions)
        listOf(a1, a2, b1).forEach {
            assertEquals(QueueSendResult.Sent, it.await())
        }
        plugin.close()
    }

    @Test
    fun busyHeadWaitsForMatchingReadiness() = runTest {
        val session = FakeSession(
            maximumUpdateValueLength = 20,
            notificationResults = listOf(NotificationResult.Busy, NotificationResult.Sent),
        )
        val peripheral = FakePeripheral(session)
        val plugin = QueuePlugin.create(QueuePlugin.createConfig())
        val queue = plugin.install(peripheral, backgroundScope)
        val sending = async(start = CoroutineStart.UNDISPATCHED) {
            queue.send(session, CharacteristicId, byteArrayOf(1))
        }
        runCurrent()

        peripheral.emitReadiness(
            NotificationReadiness.Session(PeripheralSessionId("unrelated")),
        )
        runCurrent()
        assertFalse(sending.isCompleted)
        assertEquals(1, session.calls.size)

        peripheral.emitReadiness(NotificationReadiness.Session(session.id))
        runCurrent()
        assertTrue(sending.isCompleted)
        assertEquals(QueueSendResult.Sent, sending.await())
        assertEquals(2, session.calls.size)
        plugin.close()
    }

    @Test
    fun readinessBetweenBusyAttemptAndBlockingIsNotLost() = runTest {
        val session = FakeSession(
            maximumUpdateValueLength = 20,
            notificationResults = listOf(NotificationResult.Busy, NotificationResult.Sent),
        )
        val peripheral = FakePeripheral(session)
        session.onNotify = {
            if (session.calls.size == 1) {
                peripheral.emitReadiness(NotificationReadiness.Session(session.id))
                yield()
            }
        }
        val plugin = QueuePlugin.create(QueuePlugin.createConfig())
        val queue = plugin.install(peripheral, backgroundScope)

        val sending = async(start = CoroutineStart.UNDISPATCHED) {
            queue.send(session, CharacteristicId, byteArrayOf(1))
        }
        runCurrent()

        assertTrue(sending.isCompleted)
        assertEquals(QueueSendResult.Sent, sending.await())
        assertEquals(2, session.calls.size)
        plugin.close()
    }

    @Test
    fun cancellingQueuedCallerRemovesItsUnsubmittedItem() = runTest {
        val session = FakeSession(
            maximumUpdateValueLength = 20,
            notificationResults = listOf(NotificationResult.Busy, NotificationResult.Sent),
        )
        val peripheral = FakePeripheral(session)
        val plugin = QueuePlugin.create(QueuePlugin.createConfig())
        val queue = plugin.install(peripheral, backgroundScope)
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            queue.send(session, CharacteristicId, byteArrayOf(1))
        }
        runCurrent()
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            queue.send(session, CharacteristicId, byteArrayOf(2))
        }

        second.cancel()
        runCurrent()
        peripheral.emitReadiness(NotificationReadiness.Session(session.id))
        runCurrent()

        assertTrue(first.isCompleted)
        assertEquals(QueueSendResult.Sent, first.await())
        assertEquals(2, session.calls.size)
        assertContentEquals(byteArrayOf(1), session.calls[0])
        assertContentEquals(byteArrayOf(1), session.calls[1])
        plugin.close()
    }

    @Test
    fun removedSessionCompletesQueuedItemsAsDisconnected() = runTest {
        val session = FakeSession(
            maximumUpdateValueLength = 20,
            notificationResults = listOf(NotificationResult.Busy),
        )
        val peripheral = FakePeripheral(session)
        val plugin = QueuePlugin.create(QueuePlugin.createConfig())
        val queue = plugin.install(peripheral, backgroundScope)
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            queue.send(session, CharacteristicId, byteArrayOf(1))
        }
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            queue.send(session, CharacteristicId, byteArrayOf(2))
        }
        runCurrent()

        peripheral.remove(session)
        runCurrent()

        assertTrue(first.isCompleted)
        assertTrue(second.isCompleted)
        assertEquals(QueueSendResult.Disconnected, first.await())
        assertEquals(QueueSendResult.Disconnected, second.await())
        plugin.close()
    }

    @Test
    fun closingPluginCompletesEveryPendingItemAsDisconnected() = runTest {
        val session = FakeSession(
            maximumUpdateValueLength = 20,
            notificationResults = listOf(NotificationResult.Busy),
        )
        val peripheral = FakePeripheral(session)
        val plugin = QueuePlugin.create(QueuePlugin.createConfig())
        val queue = plugin.install(peripheral, backgroundScope)
        val sending = async(start = CoroutineStart.UNDISPATCHED) {
            queue.send(session, CharacteristicId, byteArrayOf(1))
        }
        runCurrent()

        plugin.close()
        runCurrent()

        assertTrue(sending.isCompleted)
        assertEquals(QueueSendResult.Disconnected, sending.await())
        plugin.close()
    }

    @Test
    fun queueRejectsNewestItemWhenPerSessionLimitIsReached() = runTest {
        val session = FakeSession(
            maximumUpdateValueLength = 20,
            notificationResults = listOf(NotificationResult.Busy),
        )
        val peripheral = FakePeripheral(session)
        val plugin = QueuePlugin.create(
            QueuePlugin.createConfig().apply { maxPendingItemsPerSession = 1 },
        )
        val queue = plugin.install(peripheral, backgroundScope)
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            queue.send(session, CharacteristicId, byteArrayOf(1))
        }
        runCurrent()

        assertEquals(
            QueueSendResult.QueueFull,
            queue.send(session, CharacteristicId, byteArrayOf(2)),
        )
        first.cancel()
        runCurrent()
        plugin.close()
    }

    @Test
    fun queueRejectsNewestItemWhenTotalByteBudgetIsReached() = runTest {
        val firstSession = FakeSession(
            id = PeripheralSessionId("first"),
            maximumUpdateValueLength = 20,
            notificationResults = listOf(NotificationResult.Busy),
        )
        val secondSession = FakeSession(
            id = PeripheralSessionId("second"),
            maximumUpdateValueLength = 20,
        )
        val peripheral = FakePeripheral(firstSession, secondSession)
        val plugin = QueuePlugin.create(
            QueuePlugin.createConfig().apply { maxPendingBytes = 2 },
        )
        val queue = plugin.install(peripheral, backgroundScope)
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            queue.send(firstSession, CharacteristicId, byteArrayOf(1, 2))
        }
        runCurrent()

        assertEquals(
            QueueSendResult.QueueFull,
            queue.send(secondSession, CharacteristicId, byteArrayOf(3)),
        )
        first.cancel()
        runCurrent()
        plugin.close()
    }

    @Test
    fun emptyPayloadsStillConsumeTheGlobalBudget() = runTest {
        val firstSession = FakeSession(
            id = PeripheralSessionId("first"),
            maximumUpdateValueLength = 20,
            notificationResults = listOf(NotificationResult.Busy),
        )
        val secondSession = FakeSession(
            id = PeripheralSessionId("second"),
            maximumUpdateValueLength = 20,
        )
        val peripheral = FakePeripheral(firstSession, secondSession)
        val plugin = QueuePlugin.create(
            QueuePlugin.createConfig().apply { maxPendingBytes = 1 },
        )
        val queue = plugin.install(peripheral, backgroundScope)
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            queue.send(firstSession, CharacteristicId, byteArrayOf())
        }
        runCurrent()

        assertEquals(
            QueueSendResult.QueueFull,
            queue.send(secondSession, CharacteristicId, byteArrayOf()),
        )
        first.cancel()
        runCurrent()
        plugin.close()
    }

    @Test
    fun replacingSessionInstanceWithSameIdDisconnectsOldQueue() = runTest {
        val sharedId = PeripheralSessionId("reused-id")
        val oldSession = FakeSession(
            id = sharedId,
            maximumUpdateValueLength = 20,
            notificationResults = listOf(NotificationResult.Busy),
        )
        val newSession = FakeSession(
            id = sharedId,
            maximumUpdateValueLength = 20,
        )
        val peripheral = FakePeripheral(oldSession)
        val plugin = QueuePlugin.create(QueuePlugin.createConfig())
        val queue = plugin.install(peripheral, backgroundScope)
        val oldSending = async(start = CoroutineStart.UNDISPATCHED) {
            queue.send(oldSession, CharacteristicId, byteArrayOf(1))
        }
        runCurrent()

        peripheral.replace(oldSession, newSession)
        runCurrent()

        assertTrue(oldSending.isCompleted)
        assertEquals(QueueSendResult.Disconnected, oldSending.await())
        assertEquals(
            QueueSendResult.Sent,
            queue.send(newSession, CharacteristicId, byteArrayOf(2)),
        )
        plugin.close()
    }

    @Test
    fun managerReadinessResumesBusySession() = runTest {
        val session = FakeSession(
            maximumUpdateValueLength = 20,
            notificationResults = listOf(NotificationResult.Busy, NotificationResult.Sent),
        )
        val peripheral = FakePeripheral(session)
        val plugin = QueuePlugin.create(QueuePlugin.createConfig())
        val queue = plugin.install(peripheral, backgroundScope)
        val sending = async(start = CoroutineStart.UNDISPATCHED) {
            queue.send(session, CharacteristicId, byteArrayOf(1))
        }
        runCurrent()

        peripheral.emitReadiness(NotificationReadiness.Manager)
        runCurrent()

        assertTrue(sending.isCompleted)
        assertEquals(QueueSendResult.Sent, sending.await())
        plugin.close()
    }

    @Test
    fun thrownNotifyFailureCompletesCallerWithTypedFailure() = runTest {
        val failure = IllegalStateException("stack failed")
        val session = FakeSession(
            maximumUpdateValueLength = 20,
            notifyFailure = failure,
        )
        val peripheral = FakePeripheral(session)
        val plugin = QueuePlugin.create(QueuePlugin.createConfig())
        val queue = plugin.install(peripheral, backgroundScope)
        val sending = async(start = CoroutineStart.UNDISPATCHED) {
            queue.send(session, CharacteristicId, byteArrayOf(1))
        }
        runCurrent()

        assertTrue(sending.isCompleted)
        val result = sending.await() as QueueSendResult.Failed
        assertSame(failure, result.cause)
        plugin.close()
    }

    private class FakePeripheral(vararg initialSessions: PeripheralSession) : BlueFalconPeripheral {
        private val readiness = Channel<NotificationReadiness>(Channel.UNLIMITED)
        private val mutableSessions = MutableStateFlow(initialSessions.toSet())
        private val mutableReadinessState = MutableStateFlow(NotificationReadinessState())
        private var nextSessionReadinessEpoch = 0L
        override val state: StateFlow<PeripheralManagerState> =
            MutableStateFlow(PeripheralManagerState.Running)
        override val capabilities = PeripheralCapabilities.Unsupported
        override val plugins: PeripheralPluginRegistry = UnsupportedPluginRegistry
        override val sessions: StateFlow<Set<PeripheralSession>> = mutableSessions
        override val requests: Flow<GattServerRequest> = emptyFlow()
        override val events: Flow<PeripheralEvent> = emptyFlow()
        override val notificationReadiness: Flow<NotificationReadiness> = readiness.receiveAsFlow()
        override val notificationReadinessState: StateFlow<NotificationReadinessState> =
            mutableReadinessState

        fun emitReadiness(value: NotificationReadiness) {
            val current = mutableReadinessState.value
            mutableReadinessState.value = when (value) {
                NotificationReadiness.Manager -> current.copy(
                    managerEpoch = current.managerEpoch + 1L,
                )

                is NotificationReadiness.Session -> {
                    val nextEpoch = ++nextSessionReadinessEpoch
                    current.copy(
                        sessionEpochs = current.sessionEpochs +
                            (value.sessionId to nextEpoch),
                    )
                }
            }
            readiness.trySend(value).getOrThrow()
        }

        fun remove(session: PeripheralSession) {
            mutableSessions.value = mutableSessions.value - session
            mutableReadinessState.value = mutableReadinessState.value.copy(
                sessionEpochs = mutableReadinessState.value.sessionEpochs - session.id,
            )
        }

        fun replace(old: PeripheralSession, new: PeripheralSession) {
            mutableSessions.value = mutableSessions.value - old + new
            mutableReadinessState.value = mutableReadinessState.value.copy(
                sessionEpochs = mutableReadinessState.value.sessionEpochs - old.id,
            )
        }

        override suspend fun start(config: PeripheralConfig) = Unit
        override suspend fun stop() = Unit
        override suspend fun close() = Unit
    }

    private class FakeSession(
        maximumUpdateValueLength: Int?,
        notificationResult: NotificationResult = NotificationResult.Sent,
        notificationResults: List<NotificationResult> = emptyList(),
        private val notifyFailure: Throwable? = null,
        override val id: PeripheralSessionId = PeripheralSessionId("central-1"),
        private val label: String = "session",
        private val submissions: MutableList<String>? = null,
    ) : PeripheralSession {
        private val results = ArrayDeque<NotificationResult>().apply {
            addAll(notificationResults)
        }
        private val fallbackResult = notificationResults.lastOrNull() ?: notificationResult
        override val state: StateFlow<SessionState> = MutableStateFlow(SessionState.Active)
        override val subscriptions: StateFlow<Set<GattCharacteristicId>> =
            MutableStateFlow(emptySet())
        override val maximumUpdateValueLength: StateFlow<Int?> =
            MutableStateFlow(maximumUpdateValueLength)
        override val notificationReady: Flow<Unit> = emptyFlow()
        val calls = mutableListOf<ByteArray>()
        var onNotify: suspend () -> Unit = {}

        override suspend fun notify(
            characteristic: GattCharacteristicId,
            value: ByteArray,
            mode: NotificationMode,
        ): NotificationResult {
            calls += value.copyOf()
            submissions?.add("$label:${value.firstOrNull()}")
            onNotify()
            notifyFailure?.let { throw it }
            return results.removeFirstOrNull() ?: fallbackResult
        }

        override suspend fun disconnect(): DisconnectResult = DisconnectResult.Disconnected
    }

    private object UnsupportedPluginRegistry : PeripheralPluginRegistry {
        override fun <C : PeripheralPluginConfig, T> install(
            factory: PeripheralPluginFactory<C, T>,
            configure: C.() -> Unit,
        ): T = error("Not used by queue tests")
    }

    private companion object {
        val CharacteristicId = GattCharacteristicId(
            "00002a37-0000-1000-8000-00805f9b34fb".toUuid(),
        )
    }
}
