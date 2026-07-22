package dev.bluefalcon.peripheral.apple

import dev.bluefalcon.core.toUuid
import dev.bluefalcon.peripheral.AdvertiseConfig
import dev.bluefalcon.peripheral.GattCharacteristicId
import dev.bluefalcon.peripheral.PeripheralCapabilities
import dev.bluefalcon.peripheral.PeripheralConfig
import dev.bluefalcon.peripheral.PeripheralLifecycleException
import dev.bluefalcon.peripheral.PeripheralSessionId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ApplePeripheralBackendLifecycleTest {

    @Test
    fun capabilitiesDescribeCoreBluetoothSemantics() {
        val backend = ApplePeripheralBackend(FakeApplePeripheralStack(), logger = null)

        assertEquals(
            PeripheralCapabilities(
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
            ),
            backend.capabilities,
        )
    }

    @Test
    fun startStopIsRestartableAndStopIsIdempotent() = runTest {
        val stack = FakeApplePeripheralStack()
        val backend = ApplePeripheralBackend(stack, logger = null)
        val sink = RecordingAppleBackendSink()
        val config = PeripheralConfig(AdvertiseConfig())

        backend.start(config, sink)
        backend.stop()
        backend.stop()
        backend.start(config, sink)

        assertEquals(2, stack.openConfigs.size)
        assertEquals(1, stack.stopAdvertisingCalls)
        assertEquals(1, stack.clearServicesCalls)
    }

    @Test
    fun failedStartRollsBackAndCanRetry() = runTest {
        val failure = IllegalStateException("powered off")
        val stack = FakeApplePeripheralStack().apply { openFailure = failure }
        val backend = ApplePeripheralBackend(stack, logger = null)
        val sink = RecordingAppleBackendSink()

        val thrown = assertFailsWith<IllegalStateException> {
            backend.start(PeripheralConfig(AdvertiseConfig()), sink)
        }
        assertEquals(failure, thrown)
        assertEquals(1, stack.stopAdvertisingCalls)
        assertEquals(1, stack.clearServicesCalls)

        stack.openFailure = null
        backend.start(PeripheralConfig(AdvertiseConfig()), sink)
        assertEquals(2, stack.openConfigs.size)
    }

    @Test
    fun closeIsTerminalAndIdempotent() = runTest {
        val stack = FakeApplePeripheralStack()
        val backend = ApplePeripheralBackend(stack, logger = null)

        backend.close()
        backend.close()

        assertEquals(1, stack.closeCalls)
        assertFailsWith<PeripheralLifecycleException> {
            backend.start(PeripheralConfig(AdvertiseConfig()), RecordingAppleBackendSink())
        }
    }

    @Test
    fun callbacksFromStoppedGenerationAreIgnored() = runTest {
        val stack = FakeApplePeripheralStack()
        val backend = ApplePeripheralBackend(stack, logger = null)
        val sink = RecordingAppleBackendSink()
        backend.start(PeripheralConfig(AdvertiseConfig()), sink)
        val oldListener = stack.listener
        backend.stop()

        oldListener.onEvent(
            AppleGattEvent.Subscribed(
                sessionId = SessionId,
                maximumUpdateValueLength = 180,
                characteristicId = CharacteristicId,
            ),
        )

        assertEquals(emptyList(), sink.openedSessions)
        assertEquals(emptyList(), sink.subscriptionUpdates)
    }

    private companion object {
        val SessionId = PeripheralSessionId("central")
        val CharacteristicId =
            GattCharacteristicId("00002a37-0000-1000-8000-00805f9b34fb".toUuid())
    }
}
