package dev.bluefalcon.peripheral

import dev.bluefalcon.core.toUuid
import dev.bluefalcon.peripheral.fake.FakePeripheralBackend
import dev.bluefalcon.peripheral.internal.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class PeripheralResourcePolicyTest {
    private val id = PeripheralSessionId("peer")
    private val service = GattServiceId("180d".toUuid())
    private val characteristic = GattCharacteristicId("2a37".toUuid())

    @Test fun pendingRemoteResponsesHaveAnAdmissionBoundBeforeDeadlineJobs() = runTest {
        val backend = FakePeripheralBackend()
        val manager = DefaultBlueFalconPeripheral(backend, coroutineContext, requestCapacity = 512)
        manager.start(PeripheralConfig(AdvertiseConfig(), responseDeadline = 60.seconds))
        backend.openSession(id); runCurrent()
        val responses = List(300) { backend.emitCharacteristicRead(id, service, characteristic) }
        runCurrent()
        assertTrue(responses.count { it.responses.isNotEmpty() } >= 44, "Remote pending responses bypassed bounded admission")
        manager.close()
    }

    @Test fun payloadAdmissionIncludesPublicQueuedCopiesAfterDeadlineCompletion() = runTest {
        val backend = FakePeripheralBackend()
        val manager = DefaultBlueFalconPeripheral(backend, coroutineContext, requestCapacity = 16)
        manager.start(PeripheralConfig(AdvertiseConfig(), responseDeadline = 1.seconds))
        backend.openSession(id); runCurrent()
        backend.emitCharacteristicWrite(id, service, characteristic, ByteArray(512 * 1024), true)
        backend.emitCharacteristicWrite(id, service, characteristic, ByteArray(512 * 1024), true)
        runCurrent()
        advanceTimeBy(1001); runCurrent()
        val rejected = requireNotNull(backend.emitCharacteristicWrite(id, service, characteristic, byteArrayOf(1), true))
        assertEquals(GattResponseStatus.UnlikelyError, rejected.responses.single().status)
        manager.stop()
        manager.start(PeripheralConfig(AdvertiseConfig()))
        backend.openSession(id); runCurrent()
        val accepted = backend.emitCharacteristicRead(id, service, characteristic)
        runCurrent()
        assertTrue(accepted.responses.isEmpty(), "Stopped public queue retained admission in next run")
        manager.close()
    }

    @Test fun inactiveCommonSessionRetiresExactBackendOwner() = runTest {
        val fake = FakePeripheralBackend()
        val retired = mutableListOf<BackendSessionToken>()
        val backend = object : PeripheralBackend by fake {
            override val capabilities = fake.capabilities.copy(connectionLifecycleVisibility = false)
            override suspend fun retireSession(token: BackendSessionToken) { retired += token; token.retire() }
        }
        val manager = DefaultBlueFalconPeripheral(backend, coroutineContext)
        manager.start(PeripheralConfig(AdvertiseConfig(), inactiveSessionTimeout = 1.seconds))
        fake.openSession(id); runCurrent()
        advanceTimeBy(1001); runCurrent()
        assertTrue(manager.sessions.value.isEmpty())
        assertEquals(1, retired.size, "Inactivity evicted only common session, retaining backend owner")
        manager.close()
    }

    @Test fun livePeerAdmissionIsBoundedEvenWhenCommonEventQueueKeepsDraining() = runTest {
        val backend = FakePeripheralBackend()
        val manager = DefaultBlueFalconPeripheral(backend, coroutineContext)
        manager.start(PeripheralConfig(AdvertiseConfig()))
        repeat(257) { backend.openSession(PeripheralSessionId("peer-$it")); runCurrent() }
        assertIs<PeripheralManagerState.Failed>(manager.state.value)
        assertTrue(manager.sessions.value.isEmpty())
        manager.close()
    }
}
