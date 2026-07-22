package dev.bluefalcon.peripheral.android

import android.bluetooth.BluetoothGatt
import dev.bluefalcon.core.NoOpLogger
import dev.bluefalcon.peripheral.AdvertiseConfig
import dev.bluefalcon.peripheral.CharacteristicProperty
import dev.bluefalcon.peripheral.DisconnectResult
import dev.bluefalcon.peripheral.GattCharacteristicId
import dev.bluefalcon.peripheral.GattCharacteristicConfig
import dev.bluefalcon.peripheral.GattDescriptorId
import dev.bluefalcon.peripheral.GattResponseStatus
import dev.bluefalcon.peripheral.GattServiceId
import dev.bluefalcon.peripheral.GattServiceConfig
import dev.bluefalcon.peripheral.NotificationMode
import dev.bluefalcon.peripheral.NotificationReadiness
import dev.bluefalcon.peripheral.NotificationResult
import dev.bluefalcon.peripheral.PeripheralConfig
import dev.bluefalcon.peripheral.PeripheralSessionId
import dev.bluefalcon.peripheral.internal.BackendDescriptorWriteRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class AndroidPeripheralBackendNotificationTest {
    @Test
    fun notificationValidatesSessionSubscriptionAndBackpressure() = runTest {
        val fixture = startedFixture()
        val value = byteArrayOf(1, 2, 3)

        assertEquals(
            NotificationResult.Disconnected,
            fixture.backend.notify(UnknownSession, CharacteristicId, value, NotificationMode.Notification),
        )
        assertEquals(
            NotificationResult.Unsupported,
            fixture.backend.notify(SessionId, CharacteristicId, value, NotificationMode.Notification),
        )

        fixture.subscribe(SessionId, NotificationMode.Notification)
        assertEquals(
            NotificationResult.Sent,
            fixture.backend.notify(SessionId, CharacteristicId, value, NotificationMode.Notification),
        )
        value[0] = 99
        assertContentEquals(byteArrayOf(1, 2, 3), fixture.stack.notifications.single().value)
        assertEquals(
            NotificationResult.Busy,
            fixture.backend.notify(SessionId, CharacteristicId, byteArrayOf(4), NotificationMode.Notification),
        )

        fixture.stack.emit(
            AndroidGattEvent.NotificationSent(SessionId, BluetoothGatt.GATT_SUCCESS),
        )
        assertEquals(
            listOf<NotificationReadiness>(NotificationReadiness.Session(SessionId)),
            fixture.sink.readiness,
        )
    }

    @Test
    fun notificationRejectsPayloadAboveNegotiatedLimit() = runTest {
        val fixture = startedFixture()
        fixture.subscribe(SessionId, NotificationMode.Notification)
        fixture.stack.emit(AndroidGattEvent.MtuChanged(SessionId, mtu = 5))

        val result = fixture.backend.notify(
            SessionId,
            CharacteristicId,
            byteArrayOf(1, 2, 3),
            NotificationMode.Notification,
        )

        assertIs<NotificationResult.Failed>(result)
        assertEquals(emptyList(), fixture.stack.notifications)
    }

    @Test
    fun notificationModeMustMatchCccdMode() = runTest {
        val fixture = startedFixture()
        fixture.subscribe(SessionId, NotificationMode.Indication)

        assertEquals(
            NotificationResult.Unsupported,
            fixture.backend.notify(
                SessionId,
                CharacteristicId,
                byteArrayOf(1),
                NotificationMode.Notification,
            ),
        )
        assertEquals(emptyList(), fixture.stack.notifications)
    }

    @Test
    fun characteristicPropertyMustSupportRequestedNotificationMode() = runTest {
        val fixture = startedFixture(setOf(CharacteristicProperty.READ))
        fixture.subscribe(SessionId, NotificationMode.Notification)

        assertEquals(
            NotificationResult.Unsupported,
            fixture.backend.notify(
                SessionId,
                CharacteristicId,
                byteArrayOf(1),
                NotificationMode.Notification,
            ),
        )
        assertEquals(emptyList(), fixture.stack.notifications)
    }

    @Test
    fun rejectedNotificationClearsPendingAndReturnsFailure() = runTest {
        val fixture = startedFixture()
        fixture.subscribe(SessionId, NotificationMode.Notification)
        val failure = IllegalStateException("rejected")
        fixture.stack.notificationResult = AndroidNotificationStartResult.Rejected(failure)

        val first = fixture.backend.notify(
            SessionId,
            CharacteristicId,
            byteArrayOf(1),
            NotificationMode.Notification,
        )
        val second = fixture.backend.notify(
            SessionId,
            CharacteristicId,
            byteArrayOf(2),
            NotificationMode.Notification,
        )

        assertSame(failure, assertIs<NotificationResult.Failed>(first).cause)
        assertSame(failure, assertIs<NotificationResult.Failed>(second).cause)
    }

    @Test
    fun notificationExceptionClearsPendingAndReturnsFailure() = runTest {
        val fixture = startedFixture()
        fixture.subscribe(SessionId, NotificationMode.Notification)
        val failure = SecurityException("missing permission")
        fixture.stack.notificationFailure = failure

        val first = fixture.backend.notify(
            SessionId,
            CharacteristicId,
            byteArrayOf(1),
            NotificationMode.Notification,
        )
        val second = fixture.backend.notify(
            SessionId,
            CharacteristicId,
            byteArrayOf(2),
            NotificationMode.Notification,
        )

        assertSame(failure, assertIs<NotificationResult.Failed>(first).cause)
        assertSame(failure, assertIs<NotificationResult.Failed>(second).cause)
    }

    @Test
    fun rejectedCancellationIsRethrownAndClearsPending() = runTest {
        val fixture = startedFixture()
        fixture.subscribe(SessionId, NotificationMode.Notification)
        val cancellation = CancellationException("cancelled")
        fixture.stack.notificationResult = AndroidNotificationStartResult.Rejected(cancellation)

        val thrown = assertFailsWith<CancellationException> {
            fixture.backend.notify(
                SessionId,
                CharacteristicId,
                byteArrayOf(1),
                NotificationMode.Notification,
            )
        }
        fixture.stack.notificationResult = AndroidNotificationStartResult.Accepted

        assertSame(cancellation, thrown)
        assertEquals(
            NotificationResult.Sent,
            fixture.backend.notify(
                SessionId,
                CharacteristicId,
                byteArrayOf(2),
                NotificationMode.Notification,
            ),
        )
    }

    @Test
    fun failedNotificationCallbackClearsPendingAndPublishesFailure() = runTest {
        val fixture = startedFixture()
        fixture.subscribe(SessionId, NotificationMode.Notification)
        assertEquals(
            NotificationResult.Sent,
            fixture.backend.notify(
                SessionId,
                CharacteristicId,
                byteArrayOf(1),
                NotificationMode.Notification,
            ),
        )

        fixture.stack.emit(AndroidGattEvent.NotificationSent(SessionId, status = 133))

        assertEquals(
            listOf<NotificationReadiness>(NotificationReadiness.Session(SessionId)),
            fixture.sink.readiness,
        )
        assertEquals(1, fixture.sink.platformFailures.size)
        assertEquals(
            NotificationResult.Sent,
            fixture.backend.notify(
                SessionId,
                CharacteristicId,
                byteArrayOf(2),
                NotificationMode.Notification,
            ),
        )
    }

    @Test
    fun notificationCallbackAfterDisconnectStillPublishesReadiness() = runTest {
        val fixture = startedFixture()
        fixture.subscribe(SessionId, NotificationMode.Notification)
        assertEquals(
            NotificationResult.Sent,
            fixture.backend.notify(
                SessionId,
                CharacteristicId,
                byteArrayOf(1),
                NotificationMode.Notification,
            ),
        )
        fixture.stack.emit(AndroidGattEvent.Disconnected(SessionId, BluetoothGatt.GATT_SUCCESS))
        fixture.sink.readiness.clear()

        fixture.stack.emit(AndroidGattEvent.NotificationSent(SessionId, BluetoothGatt.GATT_SUCCESS))

        assertEquals(
            listOf<NotificationReadiness>(NotificationReadiness.Session(SessionId)),
            fixture.sink.readiness,
        )
    }

    @Test
    fun notificationBackpressureIsIndependentPerSession() = runTest {
        val fixture = startedFixture()
        fixture.stack.emit(AndroidGattEvent.Connected(OtherSession))
        fixture.subscribe(SessionId, NotificationMode.Notification)
        fixture.subscribe(OtherSession, NotificationMode.Notification)

        assertEquals(
            NotificationResult.Sent,
            fixture.backend.notify(
                SessionId,
                CharacteristicId,
                byteArrayOf(1),
                NotificationMode.Notification,
            ),
        )
        assertEquals(
            NotificationResult.Sent,
            fixture.backend.notify(
                OtherSession,
                CharacteristicId,
                byteArrayOf(2),
                NotificationMode.Notification,
            ),
        )
        assertEquals(listOf(SessionId, OtherSession), fixture.stack.notifications.map { it.sessionId })
    }

    @Test
    fun concurrentStopWaitsForReservedNotificationPlatformCall() = runTest {
        val fixture = startedFixture()
        fixture.subscribe(SessionId, NotificationMode.Notification)
        val notifyEntered = CountDownLatch(1)
        val releaseNotify = CountDownLatch(1)
        fixture.stack.beforeNotify = {
            notifyEntered.countDown()
            check(releaseNotify.await(5, TimeUnit.SECONDS))
        }

        val notification = async(Dispatchers.Default) {
            fixture.backend.notify(
                SessionId,
                CharacteristicId,
                byteArrayOf(1),
                NotificationMode.Notification,
            )
        }
        assertTrue(notifyEntered.await(5, TimeUnit.SECONDS))
        val stop = async(Dispatchers.Default) { fixture.backend.stop() }

        val teardownStartedWhileNotifyWasBlocked =
            fixture.stack.teardownStarted.await(500, TimeUnit.MILLISECONDS)
        releaseNotify.countDown()

        assertFalse(teardownStartedWhileNotifyWasBlocked)
        assertEquals(NotificationResult.Sent, notification.await())
        stop.await()
        assertTrue(fixture.stack.teardownStarted.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun disconnectTargetsSessionAndWaitsForPlatformCallback() = runTest {
        val fixture = startedFixture()

        assertEquals(DisconnectResult.AlreadyDisconnected, fixture.backend.disconnect(UnknownSession))
        assertEquals(DisconnectResult.Disconnected, fixture.backend.disconnect(SessionId))
        assertEquals(listOf(SessionId), fixture.stack.disconnectedSessions)
        assertEquals(emptyList(), fixture.sink.closedSessions)

        fixture.stack.emit(AndroidGattEvent.Disconnected(SessionId, BluetoothGatt.GATT_SUCCESS))
        assertEquals(
            listOf<Pair<PeripheralSessionId, Throwable?>>(SessionId to null),
            fixture.sink.closedSessions,
        )
    }

    @Test
    fun rejectedDisconnectReturnsFailureWithoutClosingSession() = runTest {
        val fixture = startedFixture()
        fixture.stack.disconnectAccepted = false

        val result = fixture.backend.disconnect(SessionId)

        assertIs<DisconnectResult.Failed>(result)
        assertEquals(emptyList(), fixture.sink.closedSessions)
    }

    @Test
    fun disconnectExceptionReturnsFailure() = runTest {
        val fixture = startedFixture()
        val failure = SecurityException("missing permission")
        fixture.stack.disconnectFailure = failure

        val result = assertIs<DisconnectResult.Failed>(fixture.backend.disconnect(SessionId))

        assertSame(failure, result.cause)
        assertEquals(emptyList(), fixture.sink.closedSessions)
    }

    private suspend fun startedFixture(
        properties: Set<CharacteristicProperty> = setOf(
            CharacteristicProperty.NOTIFY,
            CharacteristicProperty.INDICATE,
        ),
    ): NotificationFixture {
        val stack = ControllableNotificationStack()
        val sink = RecordingBackendSink()
        val backend = AndroidPeripheralBackend(stack, NoOpLogger)
        backend.start(
            PeripheralConfig(
                advertiseConfig = AdvertiseConfig(
                    services = listOf(
                        GattServiceConfig(
                            uuid = ServiceId.uuid.toString(),
                            characteristics = listOf(
                                GattCharacteristicConfig(
                                    uuid = CharacteristicId.uuid.toString(),
                                    properties = properties,
                                ),
                            ),
                        ),
                    ),
                ),
            ),
            sink,
        )
        stack.emit(AndroidGattEvent.Connected(SessionId))
        return NotificationFixture(stack, sink, backend)
    }

    companion object {
        val SessionId = PeripheralSessionId("central")
        val OtherSession = PeripheralSessionId("other")
        val UnknownSession = PeripheralSessionId("unknown")
        val ServiceId = GattServiceId(Uuid.parse("0000180d-0000-1000-8000-00805f9b34fb"))
        val CharacteristicId =
            GattCharacteristicId(Uuid.parse("00002a37-0000-1000-8000-00805f9b34fb"))
        val CccdId = GattDescriptorId(Uuid.parse("00002902-0000-1000-8000-00805f9b34fb"))
    }
}

private data class NotificationFixture(
    val stack: ControllableNotificationStack,
    val sink: RecordingBackendSink,
    val backend: AndroidPeripheralBackend,
) {
    fun subscribe(sessionId: PeripheralSessionId, mode: NotificationMode) {
        stack.emit(
            AndroidGattEvent.DescriptorWrite(
                sessionId = sessionId,
                requestId = 1,
                serviceId = AndroidPeripheralBackendNotificationTest.ServiceId,
                characteristicId = AndroidPeripheralBackendNotificationTest.CharacteristicId,
                descriptorId = AndroidPeripheralBackendNotificationTest.CccdId,
                offset = 0,
                preparedWrite = false,
                responseNeeded = true,
                value = when (mode) {
                    NotificationMode.Notification -> byteArrayOf(1, 0)
                    NotificationMode.Indication -> byteArrayOf(2, 0)
                },
            ),
        )
        assertIs<BackendDescriptorWriteRequest>(sink.requests.last())
            .responder
            ?.respond(GattResponseStatus.Success, null)
    }
}

private class ControllableNotificationStack(
    private val delegate: FakeAndroidBluetoothStack = FakeAndroidBluetoothStack(),
) : AndroidBluetoothStack by delegate {
    var notificationResult: AndroidNotificationStartResult = AndroidNotificationStartResult.Accepted
    var notificationFailure: Throwable? = null
    var disconnectAccepted = true
    var disconnectFailure: Throwable? = null
    var beforeNotify: (() -> Unit)? = null
    val teardownStarted = CountDownLatch(1)

    val notifications: List<AndroidNotificationRequest> get() = delegate.notifications
    val disconnectedSessions: List<PeripheralSessionId> get() = delegate.disconnectedSessions

    override fun notify(request: AndroidNotificationRequest): AndroidNotificationStartResult {
        delegate.notifications += request
        beforeNotify?.invoke()
        notificationFailure?.let { throw it }
        return notificationResult
    }

    override fun disconnect(sessionId: PeripheralSessionId): Boolean {
        delegate.disconnectedSessions += sessionId
        disconnectFailure?.let { throw it }
        return disconnectAccepted
    }

    override fun stopAdvertising() {
        teardownStarted.countDown()
        delegate.stopAdvertising()
    }

    fun emit(event: AndroidGattEvent) = delegate.emit(event)
}
