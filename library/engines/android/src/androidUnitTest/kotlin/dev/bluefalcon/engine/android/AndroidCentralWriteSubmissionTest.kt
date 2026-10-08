package dev.bluefalcon.engine.android

import dev.bluefalcon.core.CharacteristicWriteResult
import dev.bluefalcon.core.CharacteristicWriteType
import kotlinx.coroutines.Job
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidCentralWriteSubmissionTest {
    @Test fun bothModesReject513BeforeNativeEntryAndSubmit512WhenReady() {
        CharacteristicWriteType.entries.forEach { mode ->
            val f = Fixture()
            f.mtu(517)
            assertEquals(CharacteristicWriteResult.PayloadTooLarge(512), f.submit(513, mode))
            assertEquals(0, f.nativeEntries)
            assertEquals(CharacteristicWriteResult.Sent, f.submit(512, mode))
            assertEquals(1, f.nativeEntries)
            assertEquals(CharacteristicWriteResult.Backpressured, f.submit(512, mode))
            assertEquals(1, f.nativeEntries)
            f.complete()
            assertEquals(listOf<CharacteristicWriteResult>(CharacteristicWriteResult.Sent), f.completions)
        }
    }

    @Test fun shrinkBetweenInitialAdmissionAndSubmissionUsesCurrentLimit() {
        CharacteristicWriteType.entries.forEach { mode ->
            val f = Fixture()
            f.mtu(517)
            assertNull(f.state.validateWrite("peer", f.generation, mode, 512))
            f.mtu(26)
            assertEquals(CharacteristicWriteResult.PayloadTooLarge(23), f.submit(512, mode))
            assertEquals(0, f.nativeEntries)
            assertEquals(CharacteristicWriteResult.Sent, f.submit(23, mode))
            assertEquals(1, f.nativeEntries)
            f.complete()
        }
    }

    @Test fun exactNativeReplacementAndClosePreventOldEntryAndMtuAuthority() {
        val f = Fixture()
        f.mtu(517)
        val oldOwner = f.owner
        val oldGeneration = f.generation
        f.owner = Any()
        f.ownership.track("peer", f.owner)
        f.generation = f.state.onConnected("peer")
        f.mtu(517, oldOwner, oldGeneration)
        assertEquals(CharacteristicWriteResult.Disconnected, f.submit(512, owner = oldOwner, generation = oldGeneration))
        assertEquals(CharacteristicWriteResult.PayloadTooLarge(20), f.submit(512))
        assertEquals(0, f.nativeEntries)
        f.lifetime.destroy()
        f.mtu(517)
        assertEquals(CharacteristicWriteResult.Disconnected, f.submit(512))
        assertEquals(0, f.nativeEntries)
    }

    @Test fun legacyQueuedWriteRechecksSizeAtDispatchWithoutReadinessBypass() {
        val f = Fixture()
        f.mtu(517)
        assertEquals(CharacteristicWriteResult.Sent, f.submit(1))
        f.enqueueLegacy(512)
        f.mtu(26)
        f.complete()
        assertEquals(1, f.nativeEntries, "Queued value exceeded the current dispatch limit")
        assertTrue(f.gate.isIdle)
        f.mtu(517)
        f.enqueueLegacy(513)
        assertEquals(1, f.nativeEntries)
        assertTrue(f.gate.isIdle)
        f.enqueueLegacy(512)
        assertEquals(2, f.nativeEntries)
        f.complete()
    }

    private class Fixture {
        val lifetime = AndroidEngineLifecycle(Job())
        val ownership = AndroidGattOwnership<Any>()
        val state = AndroidCentralWriteState()
        var owner = Any()
        var generation = state.onConnected("peer")
        var nativeEntries = 0
        val completions = mutableListOf<CharacteristicWriteResult>()
        val gate = CentralGattOperationGate(10_000,
            CentralGattTimeoutScheduler { _, _ -> object : CentralGattTimeoutHandle { override fun cancel() = Unit } },
            onBusy = { state.onBusy("peer", generation) },
            onReady = { state.onReady("peer", generation) })
        init { ownership.track("peer", owner) }
        fun key() = CentralGattOperationKey(generation, CentralGattOperationType.WriteCharacteristic, "characteristic")
        fun mtu(mtu: Int, owner: Any = this.owner, generation: Long = this.generation) {
            ownership.withCurrent(owner) {
                if (!lifetime.isClosed) state.onMtuChanged("peer", generation, mtu, true)
            }
        }
        fun submit(size: Int, mode: CharacteristicWriteType = CharacteristicWriteType.WithResponse,
            owner: Any = this.owner, generation: Long = this.generation): CharacteristicWriteResult {
            var result: CharacteristicWriteResult = CharacteristicWriteResult.Disconnected
            ownership.withCurrent(owner) {
                if (!lifetime.isClosed) {
                    result = state.validateAndSubmitWrite("peer", generation, mode, size) {
                        if (gate.trySubmitTyped(key(), "write", { nativeEntries++; true }) {
                            completions += it.toWriteResult()
                        }) null else CharacteristicWriteResult.Backpressured
                    } ?: CharacteristicWriteResult.Sent
                }
            }
            return result
        }
        fun enqueueLegacy(size: Int) = ownership.withCurrent(owner) {
            gate.enqueueLegacy(key(), "legacy write", size) {
                state.validateAndSubmitWrite("peer", generation,
                    CharacteristicWriteType.WithResponse, size, requireReady = false) {
                    nativeEntries++
                    null
                } == null
            }
        }
        fun complete() = ownership.withCurrent(owner) { assertTrue(gate.complete(key(), 0, true)) }
    }
}
