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
