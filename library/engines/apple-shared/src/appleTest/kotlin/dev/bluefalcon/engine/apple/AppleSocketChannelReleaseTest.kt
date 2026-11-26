package dev.bluefalcon.engine.apple

import dev.bluefalcon.core.BluetoothCharacteristic
import dev.bluefalcon.core.BluetoothPeripheral
import dev.bluefalcon.core.BluetoothService
import dev.bluefalcon.core.L2capException
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSDate
import platform.Foundation.NSInputStream
import platform.Foundation.NSRunLoop
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.runUntilDate
import platform.Foundation.NSOutputStream
import platform.Foundation.outputStreamToMemory
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.autoreleasepool
import kotlinx.cinterop.alloc
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import platform.darwin.NSObject
import platform.objc.OBJC_ASSOCIATION_RETAIN
import platform.objc.objc_setAssociatedObject
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.ref.WeakReference
import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

/**
 * Issue #334, public stream path (`AppleL2CapSocket(channel, psm, peripheral)`): Core Bluetooth
 * closes a `CBL2CAPChannel` over the air only after the channel object is released. The engine
 * itself keeps channels on the Objective-C side (see AppleL2capChannelStoreTest). The engine's close callback captured the channel, and the socket kept that callback
 * after it ran. So any reference to a closed socket kept the channel open, and the next open on
 * the same PSM failed with "L2CAP PSM already connected".
 *
 * The device test in #334 also showed that the channel's streams keep the channel alive. So the
 * socket must drop its stream references as well.
 *
 * [FakePsm] models that Core Bluetooth rule: a new channel opens only after the previous channel
 * object was deallocated. [open] makes the fake channel's streams retain it, like the real ones.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class, NativeRuntimeApi::class, ExperimentalCoroutinesApi::class)
class AppleSocketChannelReleaseTest {
    @Test fun openWriteCloseTheSamePsmThirtyTimes() = runTest {
        val psm = FakePsm()
        val owner = AppleChannelOwner<NSObject>()
        val sockets = mutableListOf<AppleL2CapSocket>()
        var opened = 0

        repeat(30) { iteration ->
            // Like the repro in #334, the app keeps every socket it opened. The pool only drains
            // autoreleased stream factories of the test itself.
            val socket = autoreleasepool { open(psm, owner) }
            sockets += socket
            socket.write(byteArrayOf(iteration.toByte()))
            socket.close()
            collectNativeGarbage()
            opened++
        }

        assertEquals(30, opened)
        assertEquals(30, sockets.size)
        sockets.forEach { assertFalse(it.isOpen) }
    }

    @Test fun reopenFailsWhileAClosedSocketStillReferencesItsChannel() = runTest {
        val psm = FakePsm()
        val first = psm.open()
        assertFailsWith<L2capException> { psm.open() }
        first.hashCode() // keep `first` reachable up to here
    }

    @Test fun lateInstalledCallbackOnAnAlreadyClosedSocketIsAlsoDropped() {
        val socket = AppleL2CapSocket(
            NSInputStream(data = byteArrayOf(1).toData()), NSOutputStream.outputStreamToMemory(), 42, Peer,
        )
        socket.close()
        val captured = installCapturingCallback(socket)
        collectNativeGarbage()
        assertNull(captured.value, "a callback that already ran must not stay reachable from the socket")
        assertFalse(socket.isOpen)
    }

    @Test fun reopenWaitsForThisEnginesOwnCloseToSettleOnTheAir() = runTest {
        // Device measurement in #334: even with the channel released at once, Core Bluetooth
        // rejects an immediate reopen for roughly 0.3-0.4 s. This loop reopens at once.
        val closes = AppleRecentChannelCloses(clock = testScheduler.timeSource)
        val psm = ClosingPsm(rejectForMillis = 400, now = { testScheduler.currentTime })
        var opened = 0
        repeat(30) { iteration ->
            openWhileOwnCloseSettles(remaining = { closes.remaining("peer", 42) }) { psm.open() }
            opened++
            delay(300)
            psm.close()
            closes.closed("peer", 42)
            iteration.hashCode()
        }
        assertEquals(30, opened)
    }

    @Test fun rejectionWithoutARecentOwnCloseFailsAtOnceWithTheOriginalException() = runTest {
        val closes = AppleRecentChannelCloses(clock = testScheduler.timeSource)
        val original = L2capException("Failed to open L2CAP channel: L2CAP PSM already connected")
        var attempts = 0
        val thrown = assertFailsWith<L2capException> {
            openWhileOwnCloseSettles(remaining = { closes.remaining("peer", 42) }) {
                attempts++
                throw AppleL2capOpenRejected(original)
            }
        }
        assertEquals(1, attempts)
        assertEquals(original, thrown)
    }

    @Test fun retriesStopWhenTheCloseWindowEnds() = runTest {
        val closes = AppleRecentChannelCloses(window = 2.seconds, clock = testScheduler.timeSource)
        closes.closed("peer", 42)
        var attempts = 0
        assertFailsWith<L2capException> {
            openWhileOwnCloseSettles(remaining = { closes.remaining("peer", 42) }) {
                attempts++
                throw AppleL2capOpenRejected(L2capException("rejected"))
            }
        }
        assertEquals(2_000L, testScheduler.currentTime)
        assertEquals(21, attempts, "one attempt now, then one every 100 ms for 2 s")
    }

    @Test fun recentClosesArePerPeripheralAndPsm() = runTest {
        val closes = AppleRecentChannelCloses(clock = testScheduler.timeSource)
        closes.closed("peer", 42)
        assertNull(closes.remaining("peer", 43))
        assertNull(closes.remaining("other", 42))
        assertEquals(2.seconds, closes.remaining("peer", 42))
    }

    /**
     * Explicit collections plus a short main run loop turn, because Kotlin/Native
     * can hand Objective-C releases of collected objects to the main thread. Two rounds, because
     * [FakeChannel] is a Kotlin subclass: its Kotlin side becomes collectable only after the
     * streams that retain it were deallocated. A real `CBL2CAPChannel` is a pure Objective-C
     * object and is freed as soon as the streams release it.
     */
    private fun collectNativeGarbage() {
        repeat(2) {
            GC.collect()
            NSRunLoop.mainRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(0.05))
        }
    }

    /** Opens a channel on [psm] and wires it like `AppleEngine.openL2capChannel`. */
    private fun open(psm: FakePsm, owner: AppleChannelOwner<NSObject>): AppleL2CapSocket {
        val channel = psm.open()
        check(owner.retain(channel))
        val input = NSInputStream(data = byteArrayOf().toData())
        val output = NSOutputStream.outputStreamToMemory()
        // Like CBL2CAPChannel's streams, these keep the channel object alive.
        objc_setAssociatedObject(input, ChannelKey, channel, OBJC_ASSOCIATION_RETAIN)
        objc_setAssociatedObject(output, ChannelKey, channel, OBJC_ASSOCIATION_RETAIN)
        val socket = AppleL2CapSocket(input, output, 42, Peer, autoStart = false)
        socket.releaseChannelOnClose(channel, owner) { }
        socket.start()
        return socket
    }

    private fun installCapturingCallback(socket: AppleL2CapSocket): WeakReference<Any> {
        val capturedByCallback = Any()
        socket.onClosed = { capturedByCallback.hashCode() }
        return WeakReference(capturedByCallback)
    }

    private class FakeChannel : NSObject()

    private companion object {
        val ChannelKey = nativeHeap.alloc<ByteVar>().ptr
    }

    /** Core Bluetooth rejects a new open while the previous channel is still closing on the air. */
    private class ClosingPsm(private val rejectForMillis: Long, private val now: () -> Long) {
        private var closedAt: Long? = null
        private var open = false

        fun open() {
            val busy = open || closedAt?.let { now() - it < rejectForMillis } == true
            if (busy) throw AppleL2capOpenRejected(L2capException("Failed to open L2CAP channel: L2CAP PSM already connected"))
            open = true
        }

        fun close() {
            open = false
            closedAt = now()
        }
    }

    /** Core Bluetooth's rule: one channel per PSM until the previous channel object is gone. */
    private class FakePsm {
        private var previous: WeakReference<FakeChannel>? = null

        fun open(): NSObject {
            if (previous?.value != null) throw L2capException("Failed to open L2CAP channel: L2CAP PSM already connected")
            return FakeChannel().also { previous = WeakReference(it) }
        }
    }

    private object Peer : BluetoothPeripheral {
        override val name: String? = "peer"
        override val uuid = "peer"
        override val rssi: Float? = null
        override val mtuSize: Int? = null
        override val services = emptyList<BluetoothService>()
        override val characteristics = emptyList<BluetoothCharacteristic>()
    }
}
