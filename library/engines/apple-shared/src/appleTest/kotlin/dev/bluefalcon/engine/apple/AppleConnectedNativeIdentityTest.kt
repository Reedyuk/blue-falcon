package dev.bluefalcon.engine.apple

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSMutableArray
import platform.Foundation.NSHashTable
import platform.Foundation.NSPointerFunctionsObjectPointerPersonality
import platform.Foundation.NSPointerFunctionsStrongMemory
import platform.Foundation.NSUUID
import platform.darwin.NSObject
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class AppleConnectedNativeIdentityTest {
    private fun rewrap(native: NSObject): NSObject =
        NSMutableArray().apply { addObject(native) }.objectAtIndex(0uL) as NSObject

    @Test fun equalValueDifferentNativeObjectCannotCaptureOwner() {
        val native = NSUUID("00000000-0000-0000-0000-000000000001")
        val different = NSUUID("00000000-0000-0000-0000-000000000001")
        assertEquals(native, different, "Value equality must not grant native authority")
        val ownership = AppleNativeConnectionOwnership<NSObject>()
        ownership.connected("peer", native)
        assertNull(ownership.capture("peer", different))
        assertFalse(ownership.disconnected("peer", different))
    }

    @Test fun duplicateNativeWrappersCaptureExactlyOneAuthority() {
        val native = NSUUID("00000000-0000-0000-0000-000000000001")
        val epochs = ApplePeerManagerEpochs<Any>()
        val epoch = epochs.reserve("peer")
        val ownership = AppleNativeConnectionOwnership<NSObject>()
        val token = ownership.connected("peer", native, epoch)
        repeat(10) { assertSame(token, epochs.capture(epoch, ownership, rewrap(native))) }
        assertEquals(1, ownership.snapshot().size)
    }

    @Test fun retiredManagerCannotCaptureRewrappedReplacementNative() {
        val native = NSUUID("00000000-0000-0000-0000-000000000001")
        val epochs = ApplePeerManagerEpochs<Any>()
        val old = epochs.reserve("peer")
        val ownership = AppleNativeConnectionOwnership<NSObject>()
        val oldToken = ownership.connected("peer", native, old)
        ownership.beginRetirement(oldToken)
        ownership.disconnected(oldToken)
        epochs.retire(old)
        val replacementEpoch = epochs.reserve("peer")
        val replacement = ownership.connected("peer", native, replacementEpoch)
        assertNull(epochs.capture(old, ownership, rewrap(native)))
        assertSame(replacement, epochs.capture(replacementEpoch, ownership, rewrap(native)))
        assertFalse(ownership.isActive(oldToken))
    }

    @Test fun replacementTokenRejectsQueuedOldWrapperCallback() = runTest {
        val native = NSUUID("00000000-0000-0000-0000-000000000001")
        val ownership = AppleNativeConnectionOwnership<NSObject>()
        val old = ownership.connected("peer", native)
        val captured = ownership.capture("peer", rewrap(native))!!
        val dispatcher = AppleCentralCallbackDispatcher(backgroundScope)
        var oldEvents = 0
        assertTrue(dispatcher.dispatchOwned(captured, ownership, onRejected = { error("Rejected") }) { oldEvents++ })
        val replacement = ownership.connected("peer", native)
        runCurrent()
        assertEquals(0, oldEvents)
        assertFalse(ownership.disconnected(old))
        assertTrue(ownership.isActive(replacement))
        dispatcher.close()
    }

    @Test fun retirementFencesPendingDidConnectBeforeDispatchExecution() = runTest {
        val native = NSUUID("00000000-0000-0000-0000-000000000001")
        val ownership = AppleNativeConnectionOwnership<NSObject>()
        val token = ownership.connected("peer", native)
        val dispatcher = AppleCentralCallbackDispatcher(backgroundScope)
        var events = 0
        assertTrue(dispatcher.dispatchOwned(ownership.capture("peer", rewrap(native))!!, ownership,
            onRejected = { error("Rejected") }) { events++ })
        assertTrue(ownership.beginRetirement(token))
        assertNull(ownership.capture("peer", rewrap(native)))
        runCurrent()
        assertEquals(0, events)
        dispatcher.close()
    }

    @Test fun explicitTokenRetirementCannotLeakIntoReplacement() = runTest {
        val native = NSUUID("00000000-0000-0000-0000-000000000001")
        val ownership = AppleNativeConnectionOwnership<NSObject>()
        val token = ownership.connected("peer", native)
        val dispatcher = AppleCentralCallbackDispatcher(backgroundScope)
        assertTrue(ownership.beginRetirement(token)) // Lower-level token retirement, not AppleEngine.close().
        val replacement = ownership.connected("peer", native)
        var events = 0
        assertFalse(dispatcher.dispatchOwned(token, ownership, onRejected = { error("Stale callback retired replacement") }) { events++ })
        runCurrent()
        assertEquals(0, events)
        assertTrue(ownership.isActive(replacement))
        dispatcher.close()
    }

    @Test fun nativeCallbackRewrapMustReachPreinstalledCollector() = runTest {
        val native = NSUUID("00000000-0000-0000-0000-000000000001")
        val retained = NSMutableArray().apply { addObject(native) }
        val callbackNative = retained.objectAtIndex(0uL) as NSObject
        assertFalse(native === callbackNative, "Regression requires two Kotlin wrappers")
        val nativeIdentity = NSHashTable(
            options = NSPointerFunctionsStrongMemory or NSPointerFunctionsObjectPointerPersonality,
            capacity = 1uL,
        ).apply { addObject(native) }
        assertTrue(nativeIdentity.containsObject(callbackNative), "Both wrappers must refer to exactly one ObjC object")

        val epochs = ApplePeerManagerEpochs<Any>()
        val epoch = epochs.reserve("peer")
        val ownership = AppleNativeConnectionOwnership<NSObject>()
        val token = ownership.connected("peer", native, epoch)
        val dispatcher = AppleCentralCallbackDispatcher(backgroundScope)
        val updates = MutableSharedFlow<String>(extraBufferCapacity = 64)
        val observed = mutableListOf<String>()
        val collector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            updates.collect { observed += it }
        }
        // The exact capture/dispatch seam used by AppleEngine.onPeerConnected.
        epochs.capture(epoch, ownership, callbackNative)?.let { captured ->
            assertSame(token, captured)
            dispatcher.dispatchOwned(captured, ownership, onRejected = { error("Unexpected ingress rejection") }) {
                updates.tryEmit("Connected")
            }
        }
        runCurrent()
        assertEquals(listOf("Connected"), observed, "Current native didConnect must reach the preinstalled collector")
        collector.cancel()
        dispatcher.close()
    }
}
