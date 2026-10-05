package dev.bluefalcon.peripheral.apple

import dev.bluefalcon.core.toUuid
import dev.bluefalcon.peripheral.*
import dev.bluefalcon.peripheral.internal.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class ApplePeripheralResourcePolicyTest {
    private val id = PeripheralSessionId("peer")
    private val service = GattServiceId("180d".toUuid())
    private val characteristic = GattCharacteristicId("2a37".toUuid())
    @Test fun inactiveCommonEvictionReleasesNativeOwnersAcrossChurn() = runTest {
        val fake = FakeApplePeripheralStack()
        val retired = mutableListOf<PeripheralSessionId>()
        val stack = object : ApplePeripheralStack by fake {
            override fun retireSession(sessionId: PeripheralSessionId) { retired += sessionId }
        }
        val backend = ApplePeripheralBackend(stack, null)
        val manager = DefaultBlueFalconPeripheral(backend, coroutineContext)
        manager.start(PeripheralConfig(AdvertiseConfig(), responseDeadline = 1.seconds, inactiveSessionTimeout = 1.seconds))
        repeat(300) { n ->
            val peer = PeripheralSessionId("peer-$n")
            fake.emit(AppleGattEvent.Unsubscribed(peer, 20, characteristic))
            runCurrent(); advanceTimeBy(1001); runCurrent()
        }
        assertEquals(300, retired.size)
        assertTrue(manager.sessions.value.isEmpty())
        manager.close()
    }
    @Test fun backendRejectsNewPeerWhenNativeIdentitySlotsAreFull() = runTest {
        val stack = FakeApplePeripheralStack()
        val recording = RecordingAppleBackendSink()
        var overflow = 0
        val sink = object : PeripheralBackendEventSink by recording {
            override fun onResourceOverflow(cause: Throwable) { overflow++ }
        }
        val backend = ApplePeripheralBackend(stack, null)
        backend.start(PeripheralConfig(AdvertiseConfig()), sink)
        repeat(300) { stack.emit(AppleGattEvent.Subscribed(PeripheralSessionId("peer-$it"), 20, characteristic)) }
        assertEquals(1, overflow)
        assertTrue(recording.openedSessions.size <= 256)
        backend.close()
    }
    @Test fun reentrantWriteCallbacksHaveAnAggregateDeliveryByteBound() = runTest {
        val stack = FakeApplePeripheralStack()
        val recording = RecordingAppleBackendSink()
        var overflow = 0
        val sink = object : PeripheralBackendEventSink by recording {
            override fun onSessionOpened(token: BackendSessionToken, maximumUpdateValueLength: Int?) {
                repeat(3) { stack.emit(AppleGattEvent.CharacteristicWrite(id, 20, AppleRequestToken(it.toLong()), AppleCharacteristicWrite(service, characteristic, 0, ByteArray(512 * 1024)))) }
            }
            override fun onResourceOverflow(cause: Throwable) { overflow++ }
        }
        val backend = ApplePeripheralBackend(stack, null)
        backend.start(PeripheralConfig(AdvertiseConfig()), sink)
        stack.emit(AppleGattEvent.Subscribed(id, 20, characteristic))
        assertEquals(1, overflow)
        assertTrue(recording.requests.size <= 2)
        backend.close()
    }
    @Test fun retirementUsesExactNativeTargetAndCannotRetireReplacement() = runTest {
        val stack = FakeApplePeripheralStack()
        val recording = RecordingAppleBackendSink()
        val tokens = mutableListOf<BackendSessionToken>()
        val sink = object : PeripheralBackendEventSink by recording {
            override fun onSessionOpened(token: BackendSessionToken, maximumUpdateValueLength: Int?) { tokens += token }
        }
        class Target : AppleSessionTarget {
            var active = true
            var retirements = 0
            override fun isCurrent() = active
            override fun retire() { active = false; retirements++ }
        }
        val a = Target(); val b = Target()
        val backend = ApplePeripheralBackend(stack, null)
        backend.start(PeripheralConfig(AdvertiseConfig()), sink)
        stack.emit(AppleGattEvent.CharacteristicRead(id, 20, AppleRequestToken(1), service, characteristic, 0, target = a))
        val old = tokens.single()
        backend.retireSession(old)
        assertEquals(1, a.retirements)
        stack.emit(AppleGattEvent.CharacteristicRead(id, 20, AppleRequestToken(2), service, characteristic, 0, target = b))
        val replacement = tokens.last()
        backend.retireSession(old)
        assertTrue(replacement.isCurrent())
        assertEquals(0, b.retirements)
        recording.requests.first().responder!!.respond(GattResponseStatus.Success, null)
        assertTrue(stack.responses.isEmpty())
        backend.close()
    }

    @Test fun responseFailureRacingOwnerReplacementCannotPublishForSuccessor() = runTest {
        val fake = FakeApplePeripheralStack()
        val a = object : AppleSessionTarget { override fun isCurrent() = true; override fun retire() = Unit }
        val b = object : AppleSessionTarget { override fun isCurrent() = true; override fun retire() = Unit }
        val stack = object : ApplePeripheralStack by fake {
            override fun sendResponse(response: AppleGattResponse): Boolean {
                fake.emit(AppleGattEvent.CharacteristicRead(id, 20, AppleRequestToken(2), service, characteristic, 0, target = b))
                return false
            }
        }
        val recording = RecordingAppleBackendSink()
        val backend = ApplePeripheralBackend(stack, null)
        backend.start(PeripheralConfig(AdvertiseConfig()), recording)
        fake.emit(AppleGattEvent.CharacteristicRead(id, 20, AppleRequestToken(1), service, characteristic, 0, target = a))
        recording.requests.single().responder!!.respond(GattResponseStatus.Success, null)
        assertEquals(2, recording.requests.size)
        assertTrue(recording.platformFailures.isEmpty(), "Retired response failure published for replacement")
        backend.close()
    }

}
