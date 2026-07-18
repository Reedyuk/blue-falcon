package dev.bluefalcon.peripheral.android

import android.bluetooth.BluetoothGatt
import dev.bluefalcon.core.NoOpLogger
import dev.bluefalcon.peripheral.AdvertiseConfig
import dev.bluefalcon.peripheral.GattCharacteristicId
import dev.bluefalcon.peripheral.NotificationReadiness
import dev.bluefalcon.peripheral.PeripheralConfig
import dev.bluefalcon.peripheral.PeripheralSessionId
import dev.bluefalcon.peripheral.internal.BackendGattServerRequest
import dev.bluefalcon.peripheral.internal.PeripheralBackendEventSink
import kotlinx.coroutines.test.runTest
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.concurrent.thread

class AndroidPeripheralBackendSessionTest {
    @Test
    fun connectionMtuAndDisconnectionPublishSessionLifecycleInOrder() = runTest {
        val stack = FakeAndroidBluetoothStack()
        val sink = RecordingSessionSink()
        val backend = startedBackend(stack, sink)
        val sessionId = PeripheralSessionId("central")
        sink.onClosed = {
            stack.emit(AndroidGattEvent.MtuChanged(sessionId, mtu = 100))
        }

        stack.emit(AndroidGattEvent.Connected(sessionId))
        stack.emit(AndroidGattEvent.MtuChanged(sessionId, mtu = 185))
        stack.emit(
            AndroidGattEvent.Disconnected(
                sessionId,
                status = BluetoothGatt.GATT_SUCCESS,
            ),
        )

        assertEquals(
            listOf(
                RecordedSessionEvent.Opened(sessionId, maximumUpdateValueLength = 20),
                RecordedSessionEvent.MaximumLength(sessionId, maximumUpdateValueLength = 182),
                RecordedSessionEvent.Closed(sessionId),
            ),
            sink.events,
        )
        backend.close()
    }

    @Test
    fun duplicateConnectionIsIgnored() = runTest {
        val stack = FakeAndroidBluetoothStack()
        val sink = RecordingSessionSink()
        val backend = startedBackend(stack, sink)
        val sessionId = PeripheralSessionId("central")

        stack.emit(AndroidGattEvent.Connected(sessionId))
        stack.emit(AndroidGattEvent.Connected(sessionId))

        assertEquals(
            listOf<RecordedSessionEvent>(
                RecordedSessionEvent.Opened(sessionId, maximumUpdateValueLength = 20),
            ),
            sink.events,
        )
        backend.close()
    }

    @Test
    fun disconnectionForUnknownSessionIsIgnored() = runTest {
        val stack = FakeAndroidBluetoothStack()
        val sink = RecordingSessionSink()
        val backend = startedBackend(stack, sink)

        stack.emit(
            AndroidGattEvent.Disconnected(
                PeripheralSessionId("unknown"),
                status = BluetoothGatt.GATT_SUCCESS,
            ),
        )

        assertEquals(emptyList<RecordedSessionEvent>(), sink.events)
        backend.close()
    }

    @Test
    fun mtuBelowAttHeaderClampsMaximumUpdateValueLengthToZero() = runTest {
        val stack = FakeAndroidBluetoothStack()
        val sink = RecordingSessionSink()
        val backend = startedBackend(stack, sink)
        val sessionId = PeripheralSessionId("central")

        stack.emit(AndroidGattEvent.Connected(sessionId))
        stack.emit(AndroidGattEvent.MtuChanged(sessionId, mtu = 2))

        assertEquals(
            listOf(
                RecordedSessionEvent.Opened(sessionId, maximumUpdateValueLength = 20),
                RecordedSessionEvent.MaximumLength(sessionId, maximumUpdateValueLength = 0),
            ),
            sink.events,
        )
        backend.close()
    }

    @Test
    fun stopClearsSessionStateBeforeRestart() = runTest {
        val stack = FakeAndroidBluetoothStack()
        val sink = RecordingSessionSink()
        val backend = startedBackend(stack, sink)
        val sessionId = PeripheralSessionId("central")

        stack.emit(AndroidGattEvent.Connected(sessionId))
        backend.stop()
        backend.start(
            PeripheralConfig(advertiseConfig = AdvertiseConfig()),
            sink,
        )
        stack.emit(AndroidGattEvent.Connected(sessionId))

        assertEquals(
            listOf<RecordedSessionEvent>(
                RecordedSessionEvent.Opened(sessionId, maximumUpdateValueLength = 20),
                RecordedSessionEvent.Opened(sessionId, maximumUpdateValueLength = 20),
            ),
            sink.events,
        )
        backend.close()
    }

    @Test
    fun callbacksRemainInPlatformEventOrderAcrossThreads() = runTest {
        val stack = FakeAndroidBluetoothStack()
        val sink = RecordingSessionSink()
        val backend = startedBackend(stack, sink)
        val sessionId = PeripheralSessionId("central")
        val openedCallbackStarted = CountDownLatch(1)
        val releaseOpenedCallback = CountDownLatch(1)
        val disconnectEventStarted = CountDownLatch(1)
        val closedCallbackCompleted = CountDownLatch(1)
        sink.beforeOpened = {
            openedCallbackStarted.countDown()
            check(releaseOpenedCallback.await(5, TimeUnit.SECONDS))
        }
        sink.onClosed = { closedCallbackCompleted.countDown() }

        val connectThread = thread {
            stack.emit(AndroidGattEvent.Connected(sessionId))
        }
        assertTrue(openedCallbackStarted.await(5, TimeUnit.SECONDS))
        val disconnectThread = thread {
            disconnectEventStarted.countDown()
            stack.emit(
                AndroidGattEvent.Disconnected(
                    sessionId,
                    status = BluetoothGatt.GATT_SUCCESS,
                ),
            )
        }
        assertTrue(disconnectEventStarted.await(5, TimeUnit.SECONDS))
        val closedBeforeOpenedCompleted = closedCallbackCompleted.await(1, TimeUnit.SECONDS)
        releaseOpenedCallback.countDown()
        connectThread.join()
        disconnectThread.join()

        assertFalse(closedBeforeOpenedCompleted)
        assertEquals(
            listOf<RecordedSessionEvent>(
                RecordedSessionEvent.Opened(sessionId, maximumUpdateValueLength = 20),
                RecordedSessionEvent.Closed(sessionId),
            ),
            sink.events,
        )
        backend.close()
    }

    private suspend fun startedBackend(
        stack: FakeAndroidBluetoothStack,
        sink: PeripheralBackendEventSink,
    ): AndroidPeripheralBackend = AndroidPeripheralBackend(stack, NoOpLogger).also { backend ->
        backend.start(
            PeripheralConfig(advertiseConfig = AdvertiseConfig()),
            sink,
        )
    }
}

private sealed interface RecordedSessionEvent {
    data class Opened(
        val sessionId: PeripheralSessionId,
        val maximumUpdateValueLength: Int,
    ) : RecordedSessionEvent

    data class MaximumLength(
        val sessionId: PeripheralSessionId,
        val maximumUpdateValueLength: Int,
    ) : RecordedSessionEvent

    data class Closed(val sessionId: PeripheralSessionId) : RecordedSessionEvent
}

private class RecordingSessionSink : PeripheralBackendEventSink {
    val events = Collections.synchronizedList(mutableListOf<RecordedSessionEvent>())
    var beforeOpened: (() -> Unit)? = null
    var onClosed: (() -> Unit)? = null

    override fun onSessionOpened(
        sessionId: PeripheralSessionId,
        maximumUpdateValueLength: Int?,
    ) {
        beforeOpened?.invoke()
        events += RecordedSessionEvent.Opened(
            sessionId,
            requireNotNull(maximumUpdateValueLength),
        )
    }

    override fun onSessionClosed(sessionId: PeripheralSessionId, cause: Throwable?) {
        events += RecordedSessionEvent.Closed(sessionId)
        onClosed?.invoke()
    }

    override fun onSubscriptionsChanged(
        sessionId: PeripheralSessionId,
        subscriptions: Set<GattCharacteristicId>,
    ) = Unit

    override fun onMaximumUpdateValueLengthChanged(
        sessionId: PeripheralSessionId,
        maximumUpdateValueLength: Int?,
    ) {
        events += RecordedSessionEvent.MaximumLength(
            sessionId,
            requireNotNull(maximumUpdateValueLength),
        )
    }

    override fun onNotificationReady(readiness: NotificationReadiness) = Unit

    override fun onRequest(request: BackendGattServerRequest) = Unit

    override fun onPlatformFailure(cause: Throwable) = Unit
}
