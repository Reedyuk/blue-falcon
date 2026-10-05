package dev.bluefalcon.peripheral.android

import dev.bluefalcon.core.NoOpLogger
import dev.bluefalcon.core.toUuid
import dev.bluefalcon.peripheral.*
import dev.bluefalcon.peripheral.internal.DefaultBlueFalconPeripheral
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidPeripheralOwnershipRaceTest {
    @Test fun shutdownResponsePausedAcrossRestartCannotResolveReplacementNativeServer() = runTest {
        val fake = FakeAndroidBluetoothStack()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        lateinit var old: Target
        old = Target {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            old.current
        }
        var launched = false
        val stack = object : AndroidBluetoothStack by fake {
            override fun stopAdvertising() {
                if (!launched) {
                    launched = true
                    Thread {
                        try { fake.listeners.first().onEvent(read(old)) }
                        finally { finished.countDown() }
                    }.start()
                    assertTrue(entered.await(5, TimeUnit.SECONDS))
                }
                fake.stopAdvertising()
            }
            override fun closeGattServer() {
                old.current = false
                fake.closeGattServer()
            }
            override fun sendResponse(response: AndroidGattResponse): Boolean {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                return fake.sendResponse(response)
            }
        }
        val backend = AndroidPeripheralBackend(stack, NoOpLogger)
        try {
            backend.start(PeripheralConfig(AdvertiseConfig()), RecordingBackendSink())
            fake.emit(AndroidGattEvent.Connected(Id, old))
            backend.stop()
            backend.start(PeripheralConfig(AdvertiseConfig()), RecordingBackendSink())
            fake.emit(AndroidGattEvent.Connected(Id, Target { true }))
            release.countDown()
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            assertTrue(fake.responses.isEmpty(), "Shutdown rejection dynamically used replacement server")
        } finally {
            release.countDown()
            finished.await(5, TimeUnit.SECONDS)
            backend.close()
        }
    }

    @Test fun responderFailureQueuedBeforeSameIdReconnectCannotPublishIntoReplacement() = runTest {
        val fake = FakeAndroidBluetoothStack()
        val manager = DefaultBlueFalconPeripheral(AndroidPeripheralBackend(fake, NoOpLogger), coroutineContext)
        val events = mutableListOf<PeripheralEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { manager.events.collect { events += it } }
        try {
            manager.start(PeripheralConfig(AdvertiseConfig()))
            val first = Target { false }
            fake.emit(AndroidGattEvent.Connected(Id, first))
            runCurrent()
            val pending = async(UnconfinedTestDispatcher(testScheduler)) { manager.requests.first() }
            fake.emit(read(first))
            runCurrent()
            requireNotNull(pending.await().response).respond(GattResponseStatus.Success)
            // Native reconnect occurs before the common event consumer can drain failure A.
            first.current = false
            fake.emit(AndroidGattEvent.Disconnected(Id, 0, first))
            fake.emit(AndroidGattEvent.Connected(Id, Target { true }))
            runCurrent()
            assertEquals(1, manager.sessions.value.size)
            assertTrue(events.none { it is PeripheralEvent.PlatformFailure }, "Retired responder failure entered replacement run")
        } finally { manager.close() }
    }

    private class Target(private val response: (AndroidGattResponse) -> Boolean) : AndroidSessionTarget {
        @Volatile var current = true
        override fun isCurrent() = current
        override fun sendResponse(response: AndroidGattResponse) = this.response(response)
        override fun notify(request: AndroidNotificationRequest) = AndroidNotificationStartResult.Accepted
        override fun disconnect() = true
    }
    private fun read(target: AndroidSessionTarget) = AndroidGattEvent.CharacteristicRead(
        Id, 7, GattServiceId("180d".toUuid()), GattCharacteristicId("2a37".toUuid()), 0, target,
    )
    private companion object { val Id = PeripheralSessionId("same") }
}
