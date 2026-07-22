package dev.bluefalcon.peripheral

import dev.bluefalcon.core.toUuid
import dev.bluefalcon.peripheral.fake.FakePeripheralBackend
import dev.bluefalcon.peripheral.internal.DefaultBlueFalconPeripheral
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class PeripheralSessionContractTest {

    @Test
    fun sessionOpenAndDuplicateOpenPublishOneStableSession() = runTest {
        val (backend, peripheral) = startedPeripheral()

        backend.openSession(SessionId, maximumUpdateValueLength = 20)
        runCurrent()
        val session = peripheral.sessions.value.single()

        backend.openSession(SessionId, maximumUpdateValueLength = 42)
        runCurrent()

        assertEquals(1, peripheral.sessions.value.size)
        assertSame(session, peripheral.sessions.value.single())
        assertEquals(42, session.maximumUpdateValueLength.value)
    }

    @Test
    fun subscriptionAndMtuUpdatesAreScopedAndCopiedAtCallbackBoundary() = runTest {
        val (backend, peripheral) = startedPeripheral()
        backend.openSession(SessionId)
        backend.openSession(OtherSessionId)
        runCurrent()
        val session = peripheral.sessions.value.single { it.id == SessionId }
        val other = peripheral.sessions.value.single { it.id == OtherSessionId }
        val mutableSubscriptions = mutableSetOf(CharacteristicId)

        backend.updateSubscriptions(SessionId, mutableSubscriptions)
        mutableSubscriptions.clear()
        backend.updateMaximumValueLength(SessionId, 61)
        runCurrent()

        assertEquals(setOf(CharacteristicId), session.subscriptions.value)
        assertEquals(61, session.maximumUpdateValueLength.value)
        assertTrue(other.subscriptions.value.isEmpty())
        assertEquals(null, other.maximumUpdateValueLength.value)
    }

    @Test
    fun notifyCopiesValueAndTargetsOwningSession() = runTest {
        val (backend, peripheral) = startedPeripheral()
        backend.openSession(SessionId)
        runCurrent()
        val session = peripheral.sessions.value.single()
        val value = byteArrayOf(1, 2, 3)
        backend.mutateNotificationInput = true

        val result = session.notify(
            characteristic = CharacteristicId,
            value = value,
            mode = NotificationMode.Indication,
        )

        assertEquals(NotificationResult.Sent, result)
        assertContentEquals(byteArrayOf(1, 2, 3), value)
        assertEquals(SessionId, backend.lastNotificationSessionId)
        assertEquals(CharacteristicId, backend.lastNotificationCharacteristic)
        assertContentEquals(byteArrayOf(1, 2, 3), backend.lastNotificationValue)
        assertEquals(NotificationMode.Indication, backend.lastNotificationMode)
    }

    @Test
    fun disconnectTargetsOwningSession() = runTest {
        val (backend, peripheral) = startedPeripheral()
        backend.openSession(SessionId)
        runCurrent()
        val session = peripheral.sessions.value.single()

        assertEquals(DisconnectResult.Disconnected, session.disconnect())
        assertEquals(listOf(SessionId), backend.disconnectSessionIds)
    }

    @Test
    fun readinessReachesManagerAndMatchingSession() = runTest {
        val (backend, peripheral) = startedPeripheral()
        backend.openSession(SessionId)
        runCurrent()
        val session = peripheral.sessions.value.single()
        val managerReadiness = async(UnconfinedTestDispatcher(testScheduler)) {
            peripheral.notificationReadiness.first()
        }
        val sessionReadiness = async(UnconfinedTestDispatcher(testScheduler)) {
            session.notificationReady.first()
        }

        backend.signalNotificationReady(NotificationReadiness.Session(SessionId))
        runCurrent()

        assertEquals(NotificationReadiness.Session(SessionId), managerReadiness.await())
        assertEquals(Unit, sessionReadiness.await())
    }

    @Test
    fun managerReadinessReachesEveryActiveSession() = runTest {
        val (backend, peripheral) = startedPeripheral()
        backend.openSession(SessionId)
        backend.openSession(OtherSessionId)
        runCurrent()
        val sessions = peripheral.sessions.value.associateBy { it.id }
        val firstReadiness = async(UnconfinedTestDispatcher(testScheduler)) {
            withTimeoutOrNull(1.milliseconds) {
                sessions.getValue(SessionId).notificationReady.first()
            }
        }
        val secondReadiness = async(UnconfinedTestDispatcher(testScheduler)) {
            withTimeoutOrNull(1.milliseconds) {
                sessions.getValue(OtherSessionId).notificationReady.first()
            }
        }

        backend.signalNotificationReady(NotificationReadiness.Manager)
        runCurrent()

        assertEquals(Unit, firstReadiness.await())
        assertEquals(Unit, secondReadiness.await())
    }

    @Test
    fun sessionCloseRemovesSessionAndRejectsLaterOperations() = runTest {
        val (backend, peripheral) = startedPeripheral()
        backend.openSession(SessionId)
        runCurrent()
        val session = peripheral.sessions.value.single()

        backend.closeSession(SessionId)
        runCurrent()

        assertTrue(peripheral.sessions.value.isEmpty())
        assertEquals(SessionState.Closed, session.state.value)
        assertEquals(
            NotificationResult.Disconnected,
            session.notify(CharacteristicId, byteArrayOf(1)),
        )
        assertEquals(DisconnectResult.AlreadyDisconnected, session.disconnect())
        assertEquals(null, backend.lastNotificationSessionId)
        assertTrue(backend.disconnectSessionIds.isEmpty())
    }

    @Test
    fun firstSubscriptionCreatesSessionAndInactivityWaitsForFinalUnsubscribe() = runTest {
        val backend = FakePeripheralBackend(
            capabilities = PeripheralCapabilities.Unsupported.copy(
                localGattServer = true,
                connectionLifecycleVisibility = false,
            ),
        )
        val peripheral = DefaultBlueFalconPeripheral(backend, coroutineContext)
        peripheral.start(
            PeripheralConfig(
                advertiseConfig = AdvertiseConfig(),
                inactiveSessionTimeout = 100.milliseconds,
            ),
        )

        backend.updateSubscriptions(SessionId, setOf(CharacteristicId))
        runCurrent()
        val session = peripheral.sessions.value.single()

        advanceTimeBy(500)
        runCurrent()
        assertEquals(SessionState.Active, session.state.value)

        backend.updateSubscriptions(SessionId, emptySet())
        runCurrent()
        advanceTimeBy(99)
        runCurrent()
        assertEquals(SessionState.Active, session.state.value)

        advanceTimeBy(1)
        runCurrent()
        assertTrue(peripheral.sessions.value.isEmpty())
        assertEquals(SessionState.Closed, session.state.value)
    }

    @Test
    fun stopCanReachBackendWhileNotificationIsSuspended() = runTest {
        val (backend, peripheral) = startedPeripheral()
        backend.openSession(SessionId)
        runCurrent()
        val session = peripheral.sessions.value.single()
        backend.blockNotificationsUntilStop()
        val notification = async(UnconfinedTestDispatcher(testScheduler)) {
            withTimeoutOrNull(100.milliseconds) {
                session.notify(CharacteristicId, byteArrayOf(1))
            }
        }
        runCurrent()

        val stop = async(UnconfinedTestDispatcher(testScheduler)) { peripheral.stop() }
        runCurrent()

        assertEquals(1, backend.stopCalls)
        stop.await()
        assertEquals(NotificationResult.Disconnected, notification.await())
        assertEquals(SessionState.Closed, session.state.value)
    }

    private suspend fun kotlinx.coroutines.test.TestScope.startedPeripheral():
        Pair<FakePeripheralBackend, DefaultBlueFalconPeripheral> {
        val backend = FakePeripheralBackend()
        val peripheral = DefaultBlueFalconPeripheral(backend, coroutineContext)
        peripheral.start(PeripheralConfig(AdvertiseConfig()))
        return backend to peripheral
    }

    private companion object {
        val SessionId = PeripheralSessionId("central-1")
        val OtherSessionId = PeripheralSessionId("central-2")
        val CharacteristicId = GattCharacteristicId(
            "00002a37-0000-1000-8000-00805f9b34fb".toUuid(),
        )
    }
}
