package dev.bluefalcon.plugins.queue

import dev.bluefalcon.core.toUuid
import dev.bluefalcon.peripheral.BlueFalconPeripheral
import dev.bluefalcon.peripheral.DisconnectResult
import dev.bluefalcon.peripheral.GattCharacteristicId
import dev.bluefalcon.peripheral.GattServerRequest
import dev.bluefalcon.peripheral.NotificationMode
import dev.bluefalcon.peripheral.NotificationReadiness
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    private class FakePeripheral(session: PeripheralSession) : BlueFalconPeripheral {
        override val state: StateFlow<PeripheralManagerState> =
            MutableStateFlow(PeripheralManagerState.Running)
        override val capabilities = PeripheralCapabilities.Unsupported
        override val plugins: PeripheralPluginRegistry = UnsupportedPluginRegistry
        override val sessions: StateFlow<Set<PeripheralSession>> = MutableStateFlow(setOf(session))
        override val requests: Flow<GattServerRequest> = emptyFlow()
        override val events: Flow<PeripheralEvent> = emptyFlow()
        override val notificationReadiness: Flow<NotificationReadiness> = emptyFlow()

        override suspend fun start(config: PeripheralConfig) = Unit
        override suspend fun stop() = Unit
        override suspend fun close() = Unit
    }

    private class FakeSession(
        maximumUpdateValueLength: Int?,
        private val notificationResult: NotificationResult = NotificationResult.Sent,
    ) : PeripheralSession {
        override val id = PeripheralSessionId("central-1")
        override val state: StateFlow<SessionState> = MutableStateFlow(SessionState.Active)
        override val subscriptions: StateFlow<Set<GattCharacteristicId>> =
            MutableStateFlow(emptySet())
        override val maximumUpdateValueLength: StateFlow<Int?> =
            MutableStateFlow(maximumUpdateValueLength)
        override val notificationReady: Flow<Unit> = emptyFlow()
        val calls = mutableListOf<ByteArray>()

        override suspend fun notify(
            characteristic: GattCharacteristicId,
            value: ByteArray,
            mode: NotificationMode,
        ): NotificationResult {
            calls += value.copyOf()
            return notificationResult
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
