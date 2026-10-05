package dev.bluefalcon.peripheral.android

import dev.bluefalcon.core.NoOpLogger
import dev.bluefalcon.core.toUuid
import dev.bluefalcon.peripheral.*
import dev.bluefalcon.peripheral.internal.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class AndroidPeripheralResourcePolicyTest {
    private val id = PeripheralSessionId("peer")
    private val service = GattServiceId("180d".toUuid())
    private val characteristic = GattCharacteristicId("2a37".toUuid())
    @Test fun reentrantNativeRequestsCannotAccumulateUnboundedDeliveryClosures() = runTest {
        val stack = FakeAndroidBluetoothStack()
        val recording = RecordingBackendSink()
        var overflow = 0
        val sink = object : PeripheralBackendEventSink by recording {
            override fun onSessionOpened(token: BackendSessionToken, maximumUpdateValueLength: Int?) {
                repeat(10_000) { stack.emit(AndroidGattEvent.CharacteristicRead(id, it, service, characteristic, 0)) }
            }
            override fun onResourceOverflow(cause: Throwable) { overflow++ }
        }
        val backend = AndroidPeripheralBackend(stack, NoOpLogger)
        backend.start(PeripheralConfig(AdvertiseConfig()), sink)
        stack.emit(AndroidGattEvent.Connected(id))
        assertEquals(1, overflow)
        assertTrue(recording.requests.size <= 256)
        backend.close()
    }
    @Test fun stalledDeliveryPayloadHasAnAggregateByteBound() = runTest {
        val stack = FakeAndroidBluetoothStack()
        val recording = RecordingBackendSink()
        var overflow = 0
        val sink = object : PeripheralBackendEventSink by recording {
            override fun onSessionOpened(token: BackendSessionToken, maximumUpdateValueLength: Int?) {
                repeat(3) { stack.emit(AndroidGattEvent.CharacteristicWrite(id, it, service, characteristic, 0, false, true, ByteArray(512 * 1024))) }
            }
            override fun onResourceOverflow(cause: Throwable) { overflow++ }
        }
        val backend = AndroidPeripheralBackend(stack, NoOpLogger)
        backend.start(PeripheralConfig(AdvertiseConfig()), sink)
        stack.emit(AndroidGattEvent.Connected(id))
        assertEquals(1, overflow)
        assertTrue(recording.requests.size <= 2)
        backend.close()
    }
    @Test fun invalidPreparedCccdFragmentsAreRejectedBeforeRetentionOrPublicRequest() = runTest {
        val stack = FakeAndroidBluetoothStack()
        val sink = RecordingBackendSink()
        val backend = AndroidPeripheralBackend(stack, NoOpLogger)
        backend.start(PeripheralConfig(AdvertiseConfig()), sink)
        stack.emit(AndroidGattEvent.Connected(id))
        repeat(1000) { stack.emit(AndroidGattEvent.DescriptorWrite(id, it, service, characteristic, GattDescriptorId("2902".toUuid()), it + 2, true, true, ByteArray(64))) }
        assertTrue(sink.requests.isEmpty())
        assertEquals(1000, stack.responses.size)
        assertTrue(stack.responses.all { it.status == GattResponseStatus.InvalidOffset })
        backend.close()
    }
    @Test fun exactSessionRetirementRevokesResponderAndPreservesReplacement() = runTest {
        val stack = FakeAndroidBluetoothStack()
        val recording = RecordingBackendSink()
        val tokens = mutableListOf<BackendSessionToken>()
        val sink = object : PeripheralBackendEventSink by recording {
            override fun onSessionOpened(token: BackendSessionToken, maximumUpdateValueLength: Int?) { tokens += token }
        }
        val backend = AndroidPeripheralBackend(stack, NoOpLogger)
        backend.start(PeripheralConfig(AdvertiseConfig()), sink)
        stack.emit(AndroidGattEvent.Connected(id))
        stack.emit(AndroidGattEvent.CharacteristicRead(id, 1, service, characteristic, 0))
        val old = tokens.single()
        backend.retireSession(old)
        assertFalse(old.isCurrent())
        stack.emit(AndroidGattEvent.Connected(id))
        val replacement = tokens.last()
        assertNotSame(old, replacement)
        backend.retireSession(old)
        assertTrue(replacement.isCurrent())
        recording.requests.single().responder!!.respond(GattResponseStatus.Success, null)
        assertTrue(stack.responses.isEmpty())
        backend.close()
    }

    @Test fun nativePayloadAdmissionPrecedesCopiesAndIncludesActiveCallback() {
        val admission = AndroidPayloadAdmission(PeripheralRequestAdmission(maximumItems = 1, maximumBytes = 8))
        var copies = 0
        var rejected = 0
        admission.dispatch(8, { rejected++ }) {
            copies++
            admission.dispatch(1, { rejected++ }) { copies++ }
        }
        assertEquals(1, copies)
        assertEquals(1, rejected)
        admission.dispatch(9, { rejected++ }) { copies++ }
        assertEquals(1, copies)
        assertEquals(2, rejected)
        admission.dispatch(8, { rejected++ }) { copies++ }
        assertEquals(2, copies)
    }

    @Test fun notificationCompletionChurnCannotQueueUnboundedCanceledWatchdogs() = runTest {
        class StalledDispatcher : kotlinx.coroutines.CoroutineDispatcher() {
            val queued = java.util.ArrayDeque<Runnable>()
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { queued.addLast(block) }
            fun drain() { while (queued.isNotEmpty()) queued.removeFirst().run() }
        }
        val dispatcher = StalledDispatcher()
        val stack = FakeAndroidBluetoothStack()
        val backend = AndroidPeripheralBackend(stack, NoOpLogger, watchdogDispatcher = dispatcher)
        val sink = RecordingBackendSink()
        backend.start(PeripheralConfig(AdvertiseConfig(services = listOf(GattServiceConfig(service.uuid.toString(), listOf(GattCharacteristicConfig(characteristic.uuid.toString(), setOf(CharacteristicProperty.NOTIFY))))))), sink)
        stack.emit(AndroidGattEvent.Connected(id))
        stack.emit(AndroidGattEvent.DescriptorWrite(id, 8, service, characteristic, GattDescriptorId("2902".toUuid()), 0, false, false, byteArrayOf(1, 0)))
        var rejected = 0
        repeat(10_000) {
            when (backend.notify(id, characteristic, byteArrayOf(1), NotificationMode.Notification)) {
                NotificationResult.Sent -> stack.emit(AndroidGattEvent.NotificationSent(id, 0))
                is NotificationResult.Failed -> rejected++
                else -> fail("Unexpected notification result")
            }
        }
        assertTrue(dispatcher.queued.size <= 256, "Canceled watchdog jobs escaped owner admission")
        assertTrue(rejected >= 9744)
        dispatcher.drain()
        assertEquals(NotificationResult.Sent, backend.notify(id, characteristic, byteArrayOf(2), NotificationMode.Notification))
        backend.close(); dispatcher.drain()
    }

}
