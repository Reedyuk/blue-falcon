package dev.bluefalcon.peripheral.android

import dev.bluefalcon.core.NoOpLogger
import dev.bluefalcon.core.toUuid
import dev.bluefalcon.peripheral.*
import dev.bluefalcon.peripheral.internal.DefaultBlueFalconPeripheral
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertIs
import kotlinx.coroutines.async
import kotlin.time.TestTimeSource
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class PeripheralIncarnationRegressionTest {
    @Test fun queuedFailedDisconnectRetainsRetiredOwner() = runTest {
        val stack = FakeAndroidBluetoothStack()
        val backend = AndroidPeripheralBackend(stack, NoOpLogger)
        val id = PeripheralSessionId("same")
        val recorder = RecordingBackendSink()
        var first = true
        var oldToken: dev.bluefalcon.peripheral.internal.BackendSessionToken? = null
        val closedOwners = mutableListOf<dev.bluefalcon.peripheral.internal.BackendSessionToken>()
        val causes = mutableListOf<Throwable?>()
        val sink = object : dev.bluefalcon.peripheral.internal.PeripheralBackendEventSink by recorder {
            override fun onSessionOpened(token: dev.bluefalcon.peripheral.internal.BackendSessionToken, maximumUpdateValueLength: Int?) {
                if (!first) return
                first = false
                oldToken = token
                stack.emit(AndroidGattEvent.Disconnected(id, 133))
                stack.emit(AndroidGattEvent.Connected(id))
            }
            override fun onSessionClosed(token: dev.bluefalcon.peripheral.internal.BackendSessionToken, cause: Throwable?) { closedOwners += token; causes += cause }
        }
        backend.start(PeripheralConfig(AdvertiseConfig()), sink)
        stack.emit(AndroidGattEvent.Connected(id))
        assertEquals(listOf(oldToken), closedOwners)
        assertIs<AndroidConnectionStateException>(causes.single())
        assertTrue(recorder.platformFailures.isEmpty(), "Retired disconnect failure must not publish as manager-wide failure")
        backend.close()
    }

    @Test fun queuedOldDescriptorCannotCreatePublicRequestOrSubscription() = runTest {
        val stack = FakeAndroidBluetoothStack()
        val backend = AndroidPeripheralBackend(stack, NoOpLogger)
        val id = PeripheralSessionId("same")
        val service = GattServiceId("180d".toUuid())
        val characteristic = GattCharacteristicId("2a37".toUuid())
        var first = true
        val bridged = object : dev.bluefalcon.peripheral.internal.PeripheralBackend by backend {
            override suspend fun start(config: PeripheralConfig, eventSink: dev.bluefalcon.peripheral.internal.PeripheralBackendEventSink) {
                backend.start(config, object : dev.bluefalcon.peripheral.internal.PeripheralBackendEventSink by eventSink {
                    override fun onSessionOpened(token: dev.bluefalcon.peripheral.internal.BackendSessionToken, maximumUpdateValueLength: Int?) {
                        if (first) {
                            first = false
                            stack.emit(AndroidGattEvent.DescriptorWrite(id, 7, service, characteristic, GattDescriptorId("2902".toUuid()), 0, false, false, byteArrayOf(1, 0)))
                            stack.emit(AndroidGattEvent.Disconnected(id, 0))
                            stack.emit(AndroidGattEvent.Connected(id))
                        }
                        eventSink.onSessionOpened(token, maximumUpdateValueLength)
                    }
                })
            }
        }
        val manager = DefaultBlueFalconPeripheral(bridged, UnconfinedTestDispatcher(testScheduler))
        val received = mutableListOf<GattServerRequest>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { manager.requests.collect { received += it } }
        manager.start(PeripheralConfig(AdvertiseConfig()))
        stack.emit(AndroidGattEvent.Connected(id))
        runCurrent()
        assertTrue(received.isEmpty(), "Retired descriptor must not be republished with a synthetic or replacement common session")
        assertTrue(manager.sessions.value.single().subscriptions.value.isEmpty())
        manager.close()
        collector.cancel()
    }

    @Test fun queuedDescriptorWriteRetainsItsOriginalToken() = runTest {
        val stack = FakeAndroidBluetoothStack()
        val backend = AndroidPeripheralBackend(stack, NoOpLogger)
        val id = PeripheralSessionId("same")
        val service = GattServiceId("180d".toUuid())
        val characteristic = GattCharacteristicId("2a37".toUuid())
        val recorder = RecordingBackendSink()
        var first = true
        var oldToken: dev.bluefalcon.peripheral.internal.BackendSessionToken? = null
        val requests = mutableListOf<dev.bluefalcon.peripheral.internal.BackendSessionToken>()
        val changes = mutableListOf<dev.bluefalcon.peripheral.internal.BackendSessionToken>()
        val sink = object : dev.bluefalcon.peripheral.internal.PeripheralBackendEventSink by recorder {
            override fun onSessionOpened(token: dev.bluefalcon.peripheral.internal.BackendSessionToken, maximumUpdateValueLength: Int?) {
                if (!first) return
                first = false
                oldToken = token
                // Stall the delivery owner with reentrant native events. Their immutable
                // owners are captured now, but publication resumes after replacement.
                stack.emit(AndroidGattEvent.DescriptorWrite(id, 7, service, characteristic, GattDescriptorId("2902".toUuid()), 0, false, false, byteArrayOf(1, 0)))
                stack.emit(AndroidGattEvent.Disconnected(id, 0))
                stack.emit(AndroidGattEvent.Connected(id))
            }
            override fun onRequest(token: dev.bluefalcon.peripheral.internal.BackendSessionToken, request: dev.bluefalcon.peripheral.internal.BackendGattServerRequest) { requests += token }
            override fun onSubscriptionsChanged(token: dev.bluefalcon.peripheral.internal.BackendSessionToken, subscriptions: Set<GattCharacteristicId>) { changes += token }
        }
        backend.start(PeripheralConfig(AdvertiseConfig()), sink)
        stack.emit(AndroidGattEvent.Connected(id))
        assertEquals(listOf(oldToken), requests, "Descriptor request must retain native owner instead of falling through ID lookup")
        assertEquals(listOf(oldToken), changes, "CCCD publication must retain native owner instead of falling through ID lookup")
        assertTrue(recorder.requests.isEmpty())
        assertTrue(recorder.subscriptionChanges.isEmpty())
        backend.close()
    }

    @Test fun oldNotificationSentCannotClearNewConnectionPendingNotification() = runTest {
        val stack = FakeAndroidBluetoothStack()
        val backend = AndroidPeripheralBackend(stack, NoOpLogger)
        val id = PeripheralSessionId("same-id")
        val service = GattServiceId("0000180d-0000-1000-8000-00805f9b34fb".toUuid())
        val characteristic = GattCharacteristicId("00002a37-0000-1000-8000-00805f9b34fb".toUuid())
        val config = PeripheralConfig(AdvertiseConfig(services = listOf(GattServiceConfig(service.uuid.toString(), listOf(GattCharacteristicConfig(characteristic.uuid.toString(), setOf(CharacteristicProperty.NOTIFY)))))))
        backend.start(config, RecordingBackendSink())
        fun subscribe() { stack.emit(AndroidGattEvent.DescriptorWrite(id, 8, service, characteristic, GattDescriptorId("00002902-0000-1000-8000-00805f9b34fb".toUuid()), 0, false, false, byteArrayOf(1, 0))) }
        stack.emit(AndroidGattEvent.Connected(id)); subscribe()
        assertEquals(NotificationResult.Sent, backend.notify(id, characteristic, byteArrayOf(1), NotificationMode.Notification))
        stack.emit(AndroidGattEvent.Disconnected(id, 0))
        stack.emit(AndroidGattEvent.Connected(id)); subscribe()
        assertIs<NotificationResult.Failed>(backend.notify(id, characteristic, byteArrayOf(2), NotificationMode.Notification))
        // A delayed completion for the old accepted update has only an address in Android's callback.
        stack.emit(AndroidGattEvent.NotificationSent(id, 0))
        assertEquals(NotificationResult.Sent, backend.notify(id, characteristic, byteArrayOf(3), NotificationMode.Notification))
        val result = backend.notify(id, characteristic, byteArrayOf(4), NotificationMode.Notification)
        backend.close()
        assertEquals(NotificationResult.Busy, result, "Old callback cleared the new connection's in-flight update")
    }
    @Test fun oldPublicSessionCannotNotifyReconnectedIncarnation() = runTest {
        val stack = FakeAndroidBluetoothStack()
        val backend = AndroidPeripheralBackend(stack, NoOpLogger)
        val manager = DefaultBlueFalconPeripheral(backend, coroutineContext)
        val id = PeripheralSessionId("same-id")
        val service = GattServiceId("0000180d-0000-1000-8000-00805f9b34fb".toUuid())
        val characteristic = GattCharacteristicId("00002a37-0000-1000-8000-00805f9b34fb".toUuid())
        manager.start(PeripheralConfig(AdvertiseConfig(services = listOf(GattServiceConfig(service.uuid.toString(), listOf(GattCharacteristicConfig(characteristic.uuid.toString(), setOf(CharacteristicProperty.NOTIFY))))))))
        stack.emit(AndroidGattEvent.Connected(id))
        runCurrent()
        val old = manager.sessions.value.single()
        stack.emit(AndroidGattEvent.Disconnected(id, 0))
        stack.emit(AndroidGattEvent.Connected(id))
        stack.emit(AndroidGattEvent.DescriptorWrite(id, 8, service, characteristic, GattDescriptorId("00002902-0000-1000-8000-00805f9b34fb".toUuid()), 0, false, false, byteArrayOf(1, 0)))
        val result = withContext(UnconfinedTestDispatcher(testScheduler)) {
            assertEquals(DisconnectResult.AlreadyDisconnected, old.disconnect())
            old.notify(characteristic, byteArrayOf(42))
        }
        manager.close()
        assertEquals(NotificationResult.Disconnected, result, "Old public session routed by ID into replacement")
        assertEquals(0, stack.notifications.size)
        assertTrue(stack.disconnectedSessions.isEmpty())
    }
    @Test fun oldResponderCannotSendIntoRestartedServer() = runTest {
        val stack = FakeAndroidBluetoothStack()
        val backend = AndroidPeripheralBackend(stack, NoOpLogger)
        val sink = RecordingBackendSink()
        backend.start(PeripheralConfig(AdvertiseConfig()), sink)
        val id = PeripheralSessionId("same-id")
        stack.emit(AndroidGattEvent.Connected(id))
        stack.emit(AndroidGattEvent.CharacteristicRead(id, 7, GattServiceId("0000180d-0000-1000-8000-00805f9b34fb".toUuid()), GattCharacteristicId("00002a37-0000-1000-8000-00805f9b34fb".toUuid()), 0))
        val oldResponse = requireNotNull(sink.requests.single().responder)
        backend.stop()
        backend.start(PeripheralConfig(AdvertiseConfig()), RecordingBackendSink())
        stack.emit(AndroidGattEvent.Connected(id))
        oldResponse.respond(GattResponseStatus.Success, byteArrayOf(91))
        backend.close()
        assertEquals(0, stack.responses.size, "Old generation responder reached current stack")
    }
    @Test fun connectionBeforeAdvertisingCompletionRemainsUsable() = runTest {
        val gate = CompletableDeferred<Unit>()
        val stack = FakeAndroidBluetoothStack().apply { advertisingGate = gate }
        val backend = AndroidPeripheralBackend(stack, NoOpLogger)
        val sink = RecordingBackendSink()
        val start = launch { backend.start(PeripheralConfig(AdvertiseConfig()), sink) }
        runCurrent()
        val id = PeripheralSessionId("early-central")
        stack.emit(AndroidGattEvent.Connected(id))
        gate.complete(Unit)
        start.join()
        stack.emit(AndroidGattEvent.CharacteristicRead(id, 1, GattServiceId("0000180d-0000-1000-8000-00805f9b34fb".toUuid()), GattCharacteristicId("00002a37-0000-1000-8000-00805f9b34fb".toUuid()), 0))
        backend.close()
        assertEquals(1, sink.openedSessions.size, "Connection while Starting was discarded")
        assertEquals(1, sink.requests.size)
    }
    @Test fun startupStagesRequestsAndMtuInArrivalOrder() = runTest {
        val gate = CompletableDeferred<Unit>()
        val stack = FakeAndroidBluetoothStack().apply { advertisingGate = gate }
        val backend = AndroidPeripheralBackend(stack, NoOpLogger)
        val sink = RecordingBackendSink()
        val start = launch { backend.start(PeripheralConfig(AdvertiseConfig()), sink) }
        runCurrent()
        val id = PeripheralSessionId("early")
        val service = GattServiceId("180d".toUuid())
        val characteristic = GattCharacteristicId("2a37".toUuid())
        stack.emit(AndroidGattEvent.Connected(id))
        stack.emit(AndroidGattEvent.MtuChanged(id, 100))
        stack.emit(AndroidGattEvent.CharacteristicRead(id, 1, service, characteristic, 0))
        stack.emit(AndroidGattEvent.CharacteristicRead(id, 2, service, characteristic, 5))
        assertEquals(0, sink.callbackCount)
        gate.complete(Unit)
        start.join()
        assertEquals(listOf<Pair<PeripheralSessionId, Int?>>(id to 20), sink.openedSessions)
        assertEquals(listOf<Pair<PeripheralSessionId, Int?>>(id to 97), sink.maximumLengths)
        assertEquals(listOf(0, 5), sink.requests.map { (it as dev.bluefalcon.peripheral.internal.BackendCharacteristicReadRequest).offset })
        backend.close()
    }
    @Test fun failedStartupDiscardsStagedRequestsAndRejectsOldListener() = runTest {
        val gate = CompletableDeferred<Unit>()
        val stack = FakeAndroidBluetoothStack().apply { advertisingGate = gate }
        val backend = AndroidPeripheralBackend(stack, NoOpLogger)
        val sink = RecordingBackendSink()
        val start = async { runCatching { backend.start(PeripheralConfig(AdvertiseConfig()), sink) } }
        runCurrent()
        val id = PeripheralSessionId("early")
        stack.emit(AndroidGattEvent.Connected(id))
        stack.emit(AndroidGattEvent.CharacteristicRead(id, 1, GattServiceId("180d".toUuid()), GattCharacteristicId("2a37".toUuid()), 0))
        gate.completeExceptionally(IllegalStateException("advertising failed"))
        assertTrue(start.await().isFailure)
        assertEquals(0, sink.callbackCount)
        stack.advertisingGate = null
        val replacement = RecordingBackendSink()
        backend.start(PeripheralConfig(AdvertiseConfig()), replacement)
        stack.emitFrom(0, AndroidGattEvent.Connected(id))
        stack.emitFrom(0, AndroidGattEvent.NotificationSent(id, 0))
        assertEquals(0, replacement.callbackCount)
        stack.emit(AndroidGattEvent.Connected(id))
        assertEquals(1, replacement.openedSessions.size)
        backend.close()
    }
    @Test fun startupCallbackStorageHasAnItemBoundAndFailureCleanup() = runTest {
        val gate = CompletableDeferred<Unit>()
        val stack = FakeAndroidBluetoothStack().apply { advertisingGate = gate }
        val backend = AndroidPeripheralBackend(stack, NoOpLogger)
        val sink = RecordingBackendSink()
        val start = async { runCatching { backend.start(PeripheralConfig(AdvertiseConfig()), sink) } }
        runCurrent()
        repeat(257) { stack.emit(AndroidGattEvent.Connected(PeripheralSessionId("early-$it"))) }
        gate.complete(Unit)
        assertTrue(start.await().isFailure)
        assertEquals(0, sink.callbackCount)
        assertTrue("closeGattServer" in stack.calls)
        backend.close()
    }
    @Test fun lostOldNotificationCompletionFailsBoundedlyAndRequiresFreshRun() = runTest {
        val clock = TestTimeSource()
        val stack = FakeAndroidBluetoothStack()
        val backend = AndroidPeripheralBackend(stack, NoOpLogger, operationTimeout = 1.seconds, timeSource = clock)
        val sink = RecordingBackendSink()
        val id = PeripheralSessionId("same")
        val service = GattServiceId("180d".toUuid())
        val characteristic = GattCharacteristicId("2a37".toUuid())
        val config = PeripheralConfig(AdvertiseConfig(services = listOf(GattServiceConfig(service.uuid.toString(), listOf(GattCharacteristicConfig(characteristic.uuid.toString(), setOf(CharacteristicProperty.NOTIFY)))))))
        fun connect() {
            stack.emit(AndroidGattEvent.Connected(id))
            stack.emit(AndroidGattEvent.DescriptorWrite(id, 8, service, characteristic, GattDescriptorId("2902".toUuid()), 0, false, false, byteArrayOf(1, 0)))
        }
        backend.start(config, sink)
        connect()
        assertEquals(NotificationResult.Sent, backend.notify(id, characteristic, byteArrayOf(1), NotificationMode.Notification))
        stack.emit(AndroidGattEvent.Disconnected(id, 0))
        connect()
        assertIs<NotificationResult.Failed>(backend.notify(id, characteristic, byteArrayOf(2), NotificationMode.Notification))
        clock += 2.seconds
        val failed = assertIs<NotificationResult.Failed>(backend.notify(id, characteristic, byteArrayOf(2), NotificationMode.Notification))
        assertIs<AndroidNotificationCompletionAmbiguousException>(failed.cause)
        stack.emit(AndroidGattEvent.NotificationSent(id, 0))
        assertTrue(sink.readiness.isEmpty())
        assertIs<NotificationResult.Failed>(backend.notify(id, characteristic, byteArrayOf(2), NotificationMode.Notification))
        backend.stop()
        backend.start(config, sink)
        connect()
        assertEquals(NotificationResult.Sent, backend.notify(id, characteristic, byteArrayOf(3), NotificationMode.Notification))
        stack.emitFrom(0, AndroidGattEvent.NotificationSent(id, 0))
        assertEquals(NotificationResult.Busy, backend.notify(id, characteristic, byteArrayOf(4), NotificationMode.Notification))
        backend.close()
    }
    @Test fun responderIsFencedAcrossSameIdReconnectWithinRun() = runTest {
        val stack = FakeAndroidBluetoothStack()
        val backend = AndroidPeripheralBackend(stack, NoOpLogger)
        val sink = RecordingBackendSink()
        backend.start(PeripheralConfig(AdvertiseConfig()), sink)
        val id = PeripheralSessionId("same")
        stack.emit(AndroidGattEvent.Connected(id))
        stack.emit(AndroidGattEvent.CharacteristicRead(id, 1, GattServiceId("180d".toUuid()), GattCharacteristicId("2a37".toUuid()), 0))
        val old = requireNotNull(sink.requests.single().responder)
        stack.emit(AndroidGattEvent.Disconnected(id, 0))
        stack.emit(AndroidGattEvent.Connected(id))
        old.respond(GattResponseStatus.Success, byteArrayOf(9))
        assertTrue(stack.responses.isEmpty())
        backend.close()
    }

    @Test fun completionAfterDeadlineCannotReopenAmbiguousLane() = runTest {
        val clock = TestTimeSource()
        val stack = FakeAndroidBluetoothStack()
        val backend = AndroidPeripheralBackend(stack, NoOpLogger, operationTimeout = 1.seconds, timeSource = clock)
        val sink = RecordingBackendSink()
        val id = PeripheralSessionId("same")
        val service = GattServiceId("180d".toUuid())
        val characteristic = GattCharacteristicId("2a37".toUuid())
        backend.start(PeripheralConfig(AdvertiseConfig(services = listOf(GattServiceConfig(service.uuid.toString(), listOf(GattCharacteristicConfig(characteristic.uuid.toString(), setOf(CharacteristicProperty.NOTIFY))))))), sink)
        stack.emit(AndroidGattEvent.Connected(id))
        stack.emit(AndroidGattEvent.DescriptorWrite(id, 8, service, characteristic, GattDescriptorId("2902".toUuid()), 0, false, false, byteArrayOf(1, 0)))
        assertEquals(NotificationResult.Sent, backend.notify(id, characteristic, byteArrayOf(1), NotificationMode.Notification))
        clock += 2.seconds
        // No retry has observed expiry yet. A late callback must still terminalize this lane.
        stack.emit(AndroidGattEvent.NotificationSent(id, 0))
        assertTrue(sink.readiness.isEmpty())
        assertIs<NotificationResult.Failed>(backend.notify(id, characteristic, byteArrayOf(2), NotificationMode.Notification))
        backend.close()
    }

    @Test fun oldNotificationDeadlineCannotExpireNextAcceptedOperation() = runTest {
        val clock = TestTimeSource()
        val stack = FakeAndroidBluetoothStack()
        val backend = AndroidPeripheralBackend(stack, NoOpLogger, operationTimeout = 1.seconds, timeSource = clock, watchdogDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        val id = PeripheralSessionId("same")
        val service = GattServiceId("180d".toUuid())
        val characteristic = GattCharacteristicId("2a37".toUuid())
        val sink = RecordingBackendSink()
        backend.start(PeripheralConfig(AdvertiseConfig(services = listOf(GattServiceConfig(service.uuid.toString(), listOf(GattCharacteristicConfig(characteristic.uuid.toString(), setOf(CharacteristicProperty.NOTIFY))))))), sink)
        stack.emit(AndroidGattEvent.Connected(id))
        stack.emit(AndroidGattEvent.DescriptorWrite(id, 8, service, characteristic, GattDescriptorId("2902".toUuid()), 0, false, false, byteArrayOf(1, 0)))
        assertEquals(NotificationResult.Sent, backend.notify(id, characteristic, byteArrayOf(1), NotificationMode.Notification))
        runCurrent()
        testScheduler.advanceTimeBy(500); runCurrent()
        clock += kotlin.time.Duration.parse("500ms")
        stack.emit(AndroidGattEvent.NotificationSent(id, 0))
        assertEquals(NotificationResult.Sent, backend.notify(id, characteristic, byteArrayOf(2), NotificationMode.Notification))
        sink.readiness.clear()
        runCurrent()
        testScheduler.advanceTimeBy(600); runCurrent()
        assertTrue(sink.readiness.isEmpty(), "Old operation watchdog published readiness for its successor")
        clock += kotlin.time.Duration.parse("600ms")
        assertEquals(NotificationResult.Busy, backend.notify(id, characteristic, byteArrayOf(3), NotificationMode.Notification))
        clock += kotlin.time.Duration.parse("500ms")
        assertIs<NotificationResult.Failed>(backend.notify(id, characteristic, byteArrayOf(4), NotificationMode.Notification))
        backend.close()
    }

    @Test fun startupPayloadStorageHasAByteBound() = runTest {
        val gate = CompletableDeferred<Unit>()
        val stack = FakeAndroidBluetoothStack().apply { advertisingGate = gate }
        val backend = AndroidPeripheralBackend(stack, NoOpLogger)
        val sink = RecordingBackendSink()
        val start = async { runCatching { backend.start(PeripheralConfig(AdvertiseConfig()), sink) } }
        runCurrent()
        val id = PeripheralSessionId("early")
        stack.emit(AndroidGattEvent.Connected(id))
        stack.emit(AndroidGattEvent.CharacteristicWrite(id, 1, GattServiceId("180d".toUuid()), GattCharacteristicId("2a37".toUuid()), 0, false, true, ByteArray(65537)))
        gate.complete(Unit)
        assertTrue(start.await().isFailure)
        assertEquals(0, sink.callbackCount)
        backend.close()
    }

    @Test fun nativeRequestTargetIsCapturedAndStaleCallbacksCannotMutateReplacement() = runTest {
        val stack = FakeAndroidBluetoothStack()
        val backend = AndroidPeripheralBackend(stack, NoOpLogger)
        val sink = RecordingBackendSink()
        val id = PeripheralSessionId("same")
        val service = GattServiceId("180d".toUuid())
        val characteristic = GattCharacteristicId("2a37".toUuid())
        class Target : AndroidSessionTarget {
            var current = true
            val responses = mutableListOf<AndroidGattResponse>()
            override fun isCurrent() = current
            override fun sendResponse(response: AndroidGattResponse): Boolean { responses += response; return true }
            override fun notify(request: AndroidNotificationRequest) = AndroidNotificationStartResult.Accepted
            override fun disconnect() = true
        }
        backend.start(PeripheralConfig(AdvertiseConfig()), sink)
        val first = Target()
        stack.emit(AndroidGattEvent.Connected(id, first))
        stack.emit(AndroidGattEvent.CharacteristicRead(id, 1, service, characteristic, 0, first))
        val old = requireNotNull(sink.requests.single().responder)
        first.current = false
        stack.emit(AndroidGattEvent.Disconnected(id, 0, first))
        val second = Target()
        stack.emit(AndroidGattEvent.Connected(id, second))
        stack.emit(AndroidGattEvent.MtuChanged(id, 200, first))
        stack.emit(AndroidGattEvent.CharacteristicRead(id, 2, service, characteristic, 0, first))
        old.respond(GattResponseStatus.Success, byteArrayOf(1))
        assertTrue(first.responses.isEmpty())
        assertTrue(second.responses.isEmpty())
        assertTrue(sink.maximumLengths.isEmpty())
        assertEquals(1, sink.requests.size)
        stack.emit(AndroidGattEvent.CharacteristicRead(id, 3, service, characteristic, 0, second))
        requireNotNull(sink.requests.last().responder).respond(GattResponseStatus.Success, byteArrayOf(2))
        assertEquals(3, second.responses.single().requestId)
        assertTrue(stack.responses.isEmpty(), "Responder dynamically used the stack's current target")
        backend.close()
    }

    @Test fun missingNotificationCallbackWakesBusyWaiterWithTerminalFailure() = runTest {
        val stack = FakeAndroidBluetoothStack()
        val backend = AndroidPeripheralBackend(stack, NoOpLogger, operationTimeout = 1.seconds,
            watchdogDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        val sink = RecordingBackendSink()
        val id = PeripheralSessionId("same")
        val service = GattServiceId("180d".toUuid())
        val characteristic = GattCharacteristicId("2a37".toUuid())
        backend.start(PeripheralConfig(AdvertiseConfig(services = listOf(GattServiceConfig(service.uuid.toString(), listOf(GattCharacteristicConfig(characteristic.uuid.toString(), setOf(CharacteristicProperty.NOTIFY))))))), sink)
        stack.emit(AndroidGattEvent.Connected(id))
        stack.emit(AndroidGattEvent.DescriptorWrite(id, 8, service, characteristic, GattDescriptorId("2902".toUuid()), 0, false, false, byteArrayOf(1, 0)))
        assertEquals(NotificationResult.Sent, backend.notify(id, characteristic, byteArrayOf(1), NotificationMode.Notification))
        assertEquals(NotificationResult.Busy, backend.notify(id, characteristic, byteArrayOf(2), NotificationMode.Notification))
        runCurrent()
        testScheduler.advanceTimeBy(1001)
        runCurrent()
        assertEquals(listOf<NotificationReadiness>(NotificationReadiness.Session(id)), sink.readiness)
        assertIs<NotificationResult.Failed>(backend.notify(id, characteristic, byteArrayOf(2), NotificationMode.Notification))
        stack.emit(AndroidGattEvent.NotificationSent(id, 0))
        assertEquals(1, sink.readiness.size)
        assertIs<NotificationResult.Failed>(backend.notify(id, characteristic, byteArrayOf(3), NotificationMode.Notification))
        backend.close()
    }

    @Test fun oldWatchdogDoesNotPublishReplacementReadinessAndRestartCancelsIt() = runTest {
        val stack = FakeAndroidBluetoothStack()
        val backend = AndroidPeripheralBackend(stack, NoOpLogger, operationTimeout = 1.seconds,
            watchdogDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        val sink = RecordingBackendSink()
        val id = PeripheralSessionId("same")
        val service = GattServiceId("180d".toUuid())
        val characteristic = GattCharacteristicId("2a37".toUuid())
        val config = PeripheralConfig(AdvertiseConfig(services = listOf(GattServiceConfig(service.uuid.toString(), listOf(GattCharacteristicConfig(characteristic.uuid.toString(), setOf(CharacteristicProperty.NOTIFY)))))))
        fun connect() {
            stack.emit(AndroidGattEvent.Connected(id))
            stack.emit(AndroidGattEvent.DescriptorWrite(id, 8, service, characteristic, GattDescriptorId("2902".toUuid()), 0, false, false, byteArrayOf(1, 0)))
        }
        backend.start(config, sink); connect()
        assertEquals(NotificationResult.Sent, backend.notify(id, characteristic, byteArrayOf(1), NotificationMode.Notification))
        runCurrent()
        stack.emit(AndroidGattEvent.Disconnected(id, 0)); connect()
        assertIs<NotificationResult.Failed>(backend.notify(id, characteristic, byteArrayOf(2), NotificationMode.Notification))
        testScheduler.advanceTimeBy(1001); runCurrent()
        assertTrue(sink.readiness.isEmpty())
        backend.stop(); backend.start(config, sink); connect()
        assertEquals(NotificationResult.Sent, backend.notify(id, characteristic, byteArrayOf(3), NotificationMode.Notification))
        runCurrent()
        stack.emitFrom(0, AndroidGattEvent.NotificationSent(id, 0))
        assertEquals(NotificationResult.Busy, backend.notify(id, characteristic, byteArrayOf(4), NotificationMode.Notification))
        backend.stop()
        testScheduler.advanceTimeBy(1001); runCurrent()
        assertTrue(sink.readiness.isEmpty())
        backend.close()
    }

}
