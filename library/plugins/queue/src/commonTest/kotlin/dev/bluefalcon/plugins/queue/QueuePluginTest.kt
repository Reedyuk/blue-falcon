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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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

    private class FakeSession(maximumUpdateValueLength: Int?) : PeripheralSession {
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
            return NotificationResult.Sent
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
