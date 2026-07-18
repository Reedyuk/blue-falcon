package dev.bluefalcon.peripheral.android

import dev.bluefalcon.core.NoOpLogger
import dev.bluefalcon.peripheral.AdvertiseConfig
import dev.bluefalcon.peripheral.GattServiceConfig
import dev.bluefalcon.peripheral.PeripheralConfig
import dev.bluefalcon.peripheral.PeripheralLifecycleException
import dev.bluefalcon.peripheral.PeripheralSessionId
import dev.bluefalcon.peripheral.PeripheralUnsupportedException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidPeripheralBackendLifecycleTest {
    @Test
    fun startAddsServicesSequentiallyBeforeAdvertising() = runTest {
        val stack = FakeAndroidBluetoothStack()
        val backend = AndroidPeripheralBackend(stack, NoOpLogger, 10.seconds)

        backend.start(configWithServices("service-1", "service-2"), RecordingBackendSink())

        assertEquals(
            listOf("open", "service:service-1", "service:service-2", "advertise"),
            stack.calls,
        )
    }

    @Test
    fun advertisingFailureRollsBackAndAllowsRestart() = runTest {
        val stack = FakeAndroidBluetoothStack().apply {
            advertisingFailure = IllegalStateException("advertising failed")
        }
        val backend = AndroidPeripheralBackend(stack, NoOpLogger, 10.seconds)

        assertFailsWith<IllegalStateException> {
            backend.start(configWithServices("service-1"), RecordingBackendSink())
        }
        assertTrue(stack.calls.containsAll(listOf("stopAdvertising", "closeGattServer")))

        stack.advertisingFailure = null
        backend.start(configWithServices("service-1"), RecordingBackendSink())
        assertEquals(2, stack.calls.count { it == "open" })
    }

    @Test
    fun unsupportedCapabilitiesAreRejectedBeforeOpeningStack() = runTest {
        val missingServer = FakeAndroidBluetoothStack().apply {
            capabilities = AndroidStackCapabilities(false, true)
        }
        val missingAdvertising = FakeAndroidBluetoothStack().apply {
            capabilities = AndroidStackCapabilities(true, false)
        }

        assertFailsWith<PeripheralUnsupportedException> {
            AndroidPeripheralBackend(missingServer, NoOpLogger)
                .start(configWithServices(), RecordingBackendSink())
        }
        assertFailsWith<PeripheralUnsupportedException> {
            AndroidPeripheralBackend(missingAdvertising, NoOpLogger)
                .start(configWithServices(), RecordingBackendSink())
        }
        assertTrue(missingServer.calls.isEmpty())
        assertTrue(missingAdvertising.calls.isEmpty())
    }

    @Test
    fun cancellationDuringServiceAdditionRollsBackAndCanRestart() = runTest {
        val stack = FakeAndroidBluetoothStack().apply { suspendAddService = true }
        val backend = AndroidPeripheralBackend(stack, NoOpLogger, 10.seconds)
        val startJob = launch {
            backend.start(configWithServices("service-1"), RecordingBackendSink())
        }
        runCurrent()

        startJob.cancelAndJoin()

        assertTrue(startJob.isCancelled)
        assertEquals(1, stack.calls.count { it == "stopAdvertising" })
        assertEquals(1, stack.calls.count { it == "closeGattServer" })
        stack.suspendAddService = false
        backend.start(configWithServices("service-1"), RecordingBackendSink())
    }

    @Test
    fun advertisingTimeoutRollsBack() = runTest {
        val stack = FakeAndroidBluetoothStack().apply { suspendAdvertising = true }
        val backend = AndroidPeripheralBackend(stack, NoOpLogger, 1.seconds)

        assertFailsWith<TimeoutCancellationException> {
            backend.start(configWithServices(), RecordingBackendSink())
        }

        assertTrue(stack.calls.takeLast(2) == listOf("stopAdvertising", "closeGattServer"))
    }

    @Test
    fun stopIsIdempotentAndBackendCanRestart() = runTest {
        val stack = FakeAndroidBluetoothStack()
        val backend = AndroidPeripheralBackend(stack, NoOpLogger)
        backend.start(configWithServices(), RecordingBackendSink())

        backend.stop()
        backend.stop()

        assertEquals(1, stack.calls.count { it == "stopAdvertising" })
        assertEquals(1, stack.calls.count { it == "closeGattServer" })
        backend.start(configWithServices(), RecordingBackendSink())
        assertEquals(2, stack.calls.count { it == "open" })
    }

    @Test
    fun closeIsIdempotentAndTerminal() = runTest {
        val stack = FakeAndroidBluetoothStack()
        val backend = AndroidPeripheralBackend(stack, NoOpLogger)
        backend.start(configWithServices(), RecordingBackendSink())

        backend.close()
        backend.close()

        assertEquals(1, stack.calls.count { it == "stopAdvertising" })
        assertEquals(1, stack.calls.count { it == "closeGattServer" })
        assertFailsWith<PeripheralLifecycleException> {
            backend.start(configWithServices(), RecordingBackendSink())
        }
    }

    @Test
    fun eventFromEarlierListenerIsIgnoredAfterRestart() = runTest {
        val stack = FakeAndroidBluetoothStack()
        val backend = AndroidPeripheralBackend(stack, NoOpLogger)
        backend.start(configWithServices(), RecordingBackendSink())
        backend.stop()
        val currentSink = RecordingBackendSink()
        backend.start(configWithServices(), currentSink)

        stack.emitFrom(
            listenerIndex = 0,
            AndroidGattEvent.Connected(PeripheralSessionId("stale-central")),
        )

        assertEquals(0, currentSink.callbackCount)
    }

    private fun configWithServices(vararg serviceIds: String): PeripheralConfig =
        PeripheralConfig(
            advertiseConfig = AdvertiseConfig(
                services = serviceIds.map(::GattServiceConfig),
            ),
        )
}
