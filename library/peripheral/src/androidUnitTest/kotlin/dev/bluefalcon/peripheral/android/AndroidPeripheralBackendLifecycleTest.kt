package dev.bluefalcon.peripheral.android

import dev.bluefalcon.core.NoOpLogger
import dev.bluefalcon.core.Uuid
import dev.bluefalcon.core.BluetoothPermissionException
import dev.bluefalcon.peripheral.AdvertiseConfig
import dev.bluefalcon.peripheral.GattServiceConfig
import dev.bluefalcon.peripheral.GattCharacteristicId
import dev.bluefalcon.peripheral.GattResponseStatus
import dev.bluefalcon.peripheral.GattServiceId
import dev.bluefalcon.peripheral.PeripheralConfig
import dev.bluefalcon.peripheral.PeripheralLifecycleException
import dev.bluefalcon.peripheral.PeripheralSessionId
import dev.bluefalcon.peripheral.PeripheralUnsupportedException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidPeripheralBackendLifecycleTest {
    @Test
    fun securityExceptionDuringPreflightIsMappedToBluetoothPermissionFailure() = runTest {
        val stack = FakeAndroidBluetoothStack().apply {
            validationFailure = SecurityException("permission revoked")
        }
        val backend = AndroidPeripheralBackend(stack, NoOpLogger)

        val failure = assertFailsWith<BluetoothPermissionException> {
            backend.start(configWithServices(), RecordingBackendSink())
        }

        assertIs<SecurityException>(failure.cause)
        assertTrue(stack.calls.isEmpty())
    }

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

        assertEquals(
            listOf("stopAdvertising", "clearServices", "closeGattServer"),
            stack.calls.takeLast(3),
        )
    }

    @Test
    fun cancellationDuringAdvertisingRollsBack() = runTest {
        val stack = FakeAndroidBluetoothStack().apply { suspendAdvertising = true }
        val backend = AndroidPeripheralBackend(stack, NoOpLogger, 10.seconds)
        val startJob = launch {
            backend.start(configWithServices(), RecordingBackendSink())
        }
        runCurrent()

        startJob.cancelAndJoin()

        assertTrue(startJob.isCancelled)
        assertEquals(
            listOf("stopAdvertising", "clearServices", "closeGattServer"),
            stack.calls.takeLast(3),
        )
    }

    @Test
    fun serviceAdditionTimeoutRollsBack() = runTest {
        val stack = FakeAndroidBluetoothStack().apply { suspendAddService = true }
        val backend = AndroidPeripheralBackend(stack, NoOpLogger, 1.seconds)

        assertFailsWith<TimeoutCancellationException> {
            backend.start(configWithServices("service-1"), RecordingBackendSink())
        }

        assertEquals(
            listOf("stopAdvertising", "clearServices", "closeGattServer"),
            stack.calls.takeLast(3),
        )
    }

    @Test
    fun stopDuringServiceAdditionPreventsLaterAdvertising() = runTest {
        val serviceGate = CompletableDeferred<Unit>()
        val stack = FakeAndroidBluetoothStack().apply { addServiceGate = serviceGate }
        val backend = AndroidPeripheralBackend(stack, NoOpLogger, 10.seconds)
        val startFailure = CompletableDeferred<Throwable?>()
        val start = launch {
            startFailure.complete(
                runCatching {
                    backend.start(configWithServices("service-1"), RecordingBackendSink())
                }.exceptionOrNull(),
            )
        }
        runCurrent()
        val stop = launch { backend.stop() }
        runCurrent()

        serviceGate.complete(Unit)
        stop.join()
        start.join()

        assertIs<PeripheralLifecycleException>(startFailure.await())
        assertFalse("advertise" in stack.calls)
        assertEquals(
            listOf("stopAdvertising", "clearServices", "closeGattServer"),
            stack.calls.takeLast(3),
        )
    }

    @Test
    fun cancelledStopDuringServiceAdditionStillCompletesShutdown() = runTest {
        val serviceGate = CompletableDeferred<Unit>()
        val stack = FakeAndroidBluetoothStack().apply { addServiceGate = serviceGate }
        val backend = AndroidPeripheralBackend(stack, NoOpLogger, 10.seconds)
        val start = launch {
            runCatching {
                backend.start(configWithServices("service-1"), RecordingBackendSink())
            }
        }
        runCurrent()
        val stop = launch { backend.stop() }
        runCurrent()

        stop.cancel()
        serviceGate.complete(Unit)

        withTimeout(1.seconds) {
            start.join()
            stop.join()
            stack.addServiceGate = null
            backend.start(configWithServices(), RecordingBackendSink())
        }
        assertTrue(stop.isCancelled)
        assertEquals(1, stack.calls.count { it == "stopAdvertising" })
        assertEquals(1, stack.calls.count { it == "closeGattServer" })
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

    @Test
    fun responseRequiredRequestDuringShutdownReceivesFailure() = runTest {
        val sessionId = PeripheralSessionId("late-central")
        val stack = FakeAndroidBluetoothStack().apply {
            eventsOnStopAdvertising += AndroidGattEvent.CharacteristicRead(
                sessionId = sessionId,
                requestId = 41,
                serviceId = GattServiceId(Uuid.parse("0000180d-0000-1000-8000-00805f9b34fb")),
                characteristicId = GattCharacteristicId(
                    Uuid.parse("00002a37-0000-1000-8000-00805f9b34fb"),
                ),
                offset = 3,
            )
            eventsOnStopAdvertising += AndroidGattEvent.CharacteristicWrite(
                sessionId = sessionId,
                requestId = 42,
                serviceId = GattServiceId(Uuid.parse("0000180d-0000-1000-8000-00805f9b34fb")),
                characteristicId = GattCharacteristicId(
                    Uuid.parse("00002a37-0000-1000-8000-00805f9b34fb"),
                ),
                offset = 0,
                preparedWrite = false,
                responseNeeded = false,
                value = byteArrayOf(1),
            )
        }
        val backend = AndroidPeripheralBackend(stack, NoOpLogger)
        backend.start(configWithServices(), RecordingBackendSink())

        backend.stop()

        assertEquals(1, stack.responses.size)
        with(stack.responses.single()) {
            assertEquals(sessionId, this.sessionId)
            assertEquals(41, requestId)
            assertEquals(GattResponseStatus.UnlikelyError, status)
            assertEquals(3, offset)
            assertEquals(null, value)
        }
    }

    private fun configWithServices(vararg serviceIds: String): PeripheralConfig =
        PeripheralConfig(
            advertiseConfig = AdvertiseConfig(
                services = serviceIds.map(::GattServiceConfig),
            ),
        )
}
