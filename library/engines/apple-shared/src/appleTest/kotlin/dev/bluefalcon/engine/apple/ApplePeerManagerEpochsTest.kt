package dev.bluefalcon.engine.apple

import kotlin.test.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import platform.Foundation.NSMutableArray
import platform.darwin.NSObject

class ApplePeerManagerEpochsTest {
    @Test fun nativeCloseFailureStillRunsTerminalCleanupAndReleasesAdmission() {
        val owners = ApplePeerManagerEpochs<Any>()
        val old = owners.reserve("a")
        var cleaned = false
        assertFailsWith<IllegalStateException> {
            owners.retireAfterClose(old, close = {
                assertFalse(owners.isCurrent(old))
                assertFailsWith<IllegalStateException> { owners.reserve("a") }
                throw IllegalStateException("native close failed")
            }, cleanup = {
                assertTrue(owners.finishRetirement(old))
                cleaned = true
            })
        }
        assertTrue(cleaned)
        assertTrue(old.terminated.isCompleted)
        assertTrue(owners.isCurrent(owners.reserve("a")))
    }
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun sameUuidAdmissionWaitsForNativeCloseAndTerminalCleanup() = runTest {
        for (forced in listOf(false, true)) {
            val owners = ApplePeerManagerEpochs<Any>()
            val ownership = AppleNativeConnectionOwnership<NSObject>()
            val native = NSObject()
            val old = owners.reserve("a")
            val token = ownership.connected("a", native, old)
            val independent = owners.reserve("b")
            val closeEntered = CompletableDeferred<Unit>()
            val releaseClose = CompletableDeferred<Unit>()
            val retirement = launch {
                assertTrue(owners.beginRetirement(old))
                assertTrue(ownership.beginRetirement(token))
                if (forced) owners.quarantineNative(native)
                // A native manager close that has begun, but has not finished clearing delegates.
                closeEntered.complete(Unit)
                releaseClose.await()
                assertTrue(ownership.disconnected(token))
                assertTrue(owners.finishRetirement(old))
                token.terminated.complete(Unit)
            }
            closeEntered.await()
            val reconnect = async { token.terminated.await(); owners.reserve("a") }
            runCurrent()
            assertFalse(owners.isCurrent(old), "Ingress must reject a retiring manager immediately")
            assertSame(token, ownership.current("a"), "Ownership remains reserved until native close ends")
            assertNull(ownership.capture("a", native), "Retiring native callbacks lose authority immediately")
            assertFailsWith<IllegalStateException> { owners.reserve("a") }
            assertFalse(reconnect.isCompleted, "Reconnect must not install a replacement delegate during old close")
            assertTrue(owners.isCurrent(independent))
            releaseClose.complete(Unit)
            retirement.join()
            assertTrue(owners.isCurrent(reconnect.await()))
            assertEquals(!forced, owners.nativeAllowed(native))
        }
    }

    @Test fun liveNativeQuarantineNeverEvictsAnAmbiguousHandleAtCapacity() {
        val owners = ApplePeerManagerEpochs<Any>()
        val independent = owners.reserve("independent")
        val natives = List(257) { NSObject() }
        natives.take(256).forEach(owners::quarantineNative)
        assertFalse(owners.nativeAllowed(natives.first()))
        assertTrue(owners.nativeAllowed(natives.last()))
        owners.quarantineNative(natives.last())
        assertFalse(owners.nativeAllowed(natives.first()))
        assertFalse(owners.nativeAllowed(natives.last()))
        assertFalse(owners.nativeAllowed(NSObject()), "Exhausted native provenance storage fails closed")
        assertTrue(owners.isCurrent(independent), "Existing independent manager remains owned")
    }
    @OptIn(kotlin.experimental.ExperimentalNativeApi::class, kotlin.native.runtime.NativeRuntimeApi::class)
    @Test fun quarantineSurvivesNativeObjectRetainedOutsideKotlin() {
        val owners = ApplePeerManagerEpochs<Any>()
        fun retainedNative(): NSMutableArray {
            val native = NSObject()
            owners.quarantineNative(native)
            return NSMutableArray().apply { addObject(native) }
        }
        val externalOwner = retainedNative()
        kotlin.native.runtime.GC.collect()
        assertFalse(owners.nativeAllowed(externalOwner.objectAtIndex(0uL) as NSObject))
    }
    @Test fun forcedRetirementRejectsNativeReuseButNormalDrainAllowsIt() {
        val owners = ApplePeerManagerEpochs<Any>()
        val native = NSObject()
        val normal = owners.reserve("peer")
        owners.retire(normal)
        assertTrue(owners.nativeAllowed(native), "Drained terminal permits normal same-native reuse")
        owners.quarantineNative(native)
        assertFalse(owners.nativeAllowed(native), "Forced retirement cannot prove native callback provenance")
        assertTrue(owners.nativeAllowed(NSObject()), "Fresh manager native handle remains admissible")
    }

    @Test fun retiredManagerCannotRecaptureSameNativePeripheral() {
        val owners = ApplePeerManagerEpochs<Any>()
        val native = Any()
        val old = owners.reserve("peer")
        old.manager = native
        assertTrue(owners.retire(old))
        val replacement = owners.reserve("peer")
        replacement.manager = native
        assertFalse(owners.isCurrent(old))
        assertTrue(owners.isCurrent(replacement))
        assertFalse(owners.retire(old))
        assertSame(replacement, owners.current("peer"))
    }
    @Test fun missingTerminalRetiresOnlyExactManagerAndAllowsSameUuidRecovery() {
        val owners = ApplePeerManagerEpochs<Any>()
        val ownership = AppleNativeConnectionOwnership<Any>()
        val native = Any()
        val oldEpoch = owners.reserve("a")
        val oldToken = ownership.connected("a", native, oldEpoch)
        val independent = owners.reserve("b")
        val callbacks = mutableListOf<() -> Unit>()
        val timers = AppleTerminalWatchdogs<AppleNativeConnectionToken<Any>>({ action -> callbacks += action; Job() })
        var closed = 0
        timers.start(oldToken, { owners.isCurrent(oldEpoch) && ownership.isActive(oldToken) }, {
            assertTrue(owners.retire(oldEpoch))
            assertTrue(ownership.disconnected(oldToken))
            oldToken.terminated.complete(Unit)
            closed++
        })
        callbacks.single()()
        assertTrue(oldToken.terminated.isCompleted)
        assertFalse(timers.isPending(oldToken))
        val replacementEpoch = owners.reserve("a")
        val replacementToken = ownership.connected("a", native, replacementEpoch)
        assertNull(owners.capture(oldEpoch, ownership, native), "Old terminal may not recapture same native replacement")
        callbacks.single()() // A cancelled or duplicate already-running timer.
        assertEquals(1, closed)
        assertTrue(ownership.isActive(replacementToken))
        assertSame(replacementToken, owners.capture(replacementEpoch, ownership, native))
        assertTrue(owners.isCurrent(independent))
    }
    @Test fun cancelledTerminalTimerCannotRetireReplacement() {
        val callbacks = mutableListOf<() -> Unit>()
        val timers = AppleTerminalWatchdogs<Any>({ action -> callbacks += action; Job() })
        val owner = Any()
        var retired = 0
        timers.start(owner, { true }, { retired++ })
        timers.complete(owner)
        timers.start(owner, { true }, { retired++ })
        callbacks.first()()
        assertEquals(0, retired)
        assertTrue(timers.isPending(owner))
        callbacks.last()()
        assertEquals(1, retired)
    }
    @Test fun stuckPeerDoesNotAffectIndependentManager() {
        val owners = ApplePeerManagerEpochs<Any>()
        val stuck = owners.reserve("a")
        val independent = owners.reserve("b")
        owners.retire(stuck)
        assertTrue(owners.isCurrent(independent))
        repeat(20) { owners.retire(owners.reserve("a")) }
        assertTrue(owners.isCurrent(independent))
    }
    @Test fun admissionIsBoundedAndRetirementRestoresCapacity() {
        val owners = ApplePeerManagerEpochs<Any>(1)
        val first = owners.reserve("a")
        assertFailsWith<IllegalStateException> { owners.reserve("b") }
        owners.retire(first)
        assertTrue(owners.isCurrent(owners.reserve("b")))
    }
}
