package dev.bluefalcon.engine.apple

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import platform.CoreBluetooth.*
import kotlinx.cinterop.autoreleasepool
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class AppleNotificationStorageTest {
    private fun native(id: Int) = CBMutableCharacteristic(
        CBUUID.UUIDWithString("00000000-0000-0000-0000-${id.toString().padStart(12, '0')}"),
        CBCharacteristicPropertyNotify, null, CBAttributePermissionsReadable,
    )
    @Test fun liveKeyAdmissionFailsExplicitlyAtCapacity() {
        val store = AppleNotificationFlowStore(maximumKeys = 2)
        val natives = List(3, ::native)
        store.flowFor(natives[0]); store.flowFor(natives[1])
        assertFailsWith<IllegalStateException> { store.flowFor(natives[2]) }
        assertEquals(2, store.retainedKeyCount())
        assertSame(store.flowFor(natives[0]), store.flowFor(natives[0]))
    }
    @OptIn(kotlin.experimental.ExperimentalNativeApi::class, kotlin.native.runtime.NativeRuntimeApi::class)
    @Test fun unreachableNativeKeysAreRetiredDuringChurn() {
        val store = AppleNotificationFlowStore()
        fun batch() = autoreleasepool { repeat(100) { store.flowFor(native(it)) }; kotlin.native.runtime.GC.collect() }
        repeat(20) { batch(); kotlin.native.runtime.GC.collect() }
        kotlin.native.runtime.GC.collect()
        assertEquals(0, store.retainedKeyCount())
    }
    @Test fun oversizedPayloadDoesNotEnterAStalledFlow() = runTest {
        val store = AppleNotificationFlowStore(maximumPayloadBytes = 2)
        val characteristic = native(1)
        val received = mutableListOf<ByteArray>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            store.flowFor(characteristic).collect { received += it; awaitCancellation() }
        }
        assertTrue(store.emit(characteristic, byteArrayOf(1)))
        assertFalse(store.emit(characteristic, byteArrayOf(1, 2, 3)))
        assertEquals(1, received.size)
        assertEquals(1, store.status.rejectedValues)
    }

    @Test fun aStalledCollectorHasFiniteItemStorageAndImmutablePayloads() = runTest {
        val store = AppleNotificationFlowStore()
        val characteristic = native(1)
        val release = CompletableDeferred<Unit>()
        val received = mutableListOf<ByteArray>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            store.flowFor(characteristic).collect { received += it; if (received.size == 1) release.await() }
        }
        val original = byteArrayOf(7)
        assertTrue(store.emit(characteristic, original))
        original[0] = 9
        repeat(64) { assertTrue(store.emit(characteristic, byteArrayOf(2))) }
        assertFalse(store.emit(characteristic, byteArrayOf(3)))
        assertEquals(1, store.status.rejectedValues)
        assertContentEquals(byteArrayOf(7), received.first())
        release.complete(Unit); runCurrent()
        assertEquals(65, received.size)
    }

    @Test fun aCollectorCanReenterStorageWithoutHoldingTheNativeLock() = runTest {
        val store = AppleNotificationFlowStore()
        val characteristic = native(1)
        var received = false
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            store.flowFor(characteristic).collect {
                assertEquals(1, store.status.retainedKeys)
                assertSame(store.flowFor(characteristic), store.flowFor(characteristic))
                received = true
            }
        }
        assertTrue(store.emit(characteristic, byteArrayOf(1)))
        assertTrue(received)
    }

    @OptIn(kotlin.experimental.ExperimentalNativeApi::class, kotlin.native.runtime.NativeRuntimeApi::class)
    @Test fun nativeRetentionOutsideKotlinKeepsTheSameFlow() {
        val store = AppleNotificationFlowStore()
        val externalOwner = platform.Foundation.NSMutableArray()
        val flow = run {
            val characteristic = native(1)
            externalOwner.addObject(characteristic)
            store.flowFor(characteristic)
        }
        kotlin.native.runtime.GC.collect()
        val native = externalOwner.objectAtIndex(0uL) as CBCharacteristic
        assertSame(flow, store.flowFor(native))
    }

    @Test fun emitWithoutAnApplicationFlowRetainsNoNativeKey() {
        val store = AppleNotificationFlowStore()
        repeat(1_000) { assertTrue(store.emit(native(it), byteArrayOf(1))) }
        assertEquals(0, store.retainedKeyCount())
    }
}
