package dev.bluefalcon.engine.apple

import dev.bluefalcon.core.BluetoothCharacteristic
import dev.bluefalcon.core.BluetoothPeripheral
import dev.bluefalcon.core.BluetoothService
import dev.bluefalcon.core.L2capException
import dev.bluefalcon.engine.apple.objc.BFL2capChannelStore
import dev.bluefalcon.engine.apple.objc.BFPeripheralDelegateProxy
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import platform.CoreBluetooth.CBPeripheral
import platform.CoreBluetooth.CBPeripheralDelegateProtocol
import platform.Foundation.NSError
import platform.Foundation.NSInputStream
import platform.Foundation.NSOutputStream
import platform.Foundation.NSSelectorFromString
import platform.Foundation.NSStreamEventHasBytesAvailable
import platform.Foundation.NSStreamStatusClosed
import platform.Foundation.outputStreamToMemory
import platform.darwin.NSObject
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issue #334, engine path: the channel and its streams live in `BFL2capChannelStore` and the socket
 * only has an id. `close()` must drop them on the Objective-C side at once, with no GC involved.
 */
@OptIn(ExperimentalForeignApi::class)
class AppleL2capChannelStoreTest {
    private val store = BFL2capChannelStore.shared()

    @Test fun closeRemovesTheChannelFromTheStoreAtOnce() {
        val (handle, input, output) = stored()
        val socket = AppleL2CapSocket(handle, 42, Peer, onCleanupFailure = {}, autoStart = true)
        assertTrue(store.contains(handle.id))

        socket.close()

        assertFalse(store.contains(handle.id), "the store no longer holds the channel or its streams")
        assertFalse(socket.isOpen)
        assertEquals(NSStreamStatusClosed, input.streamStatus)
        assertEquals(NSStreamStatusClosed, output.streamStatus)
        socket.close() // idempotent
    }

    @Test fun thirtyOpenWriteCloseCyclesLeaveNothingInTheStore() = runTest {
        val before = store.count()
        repeat(30) { i ->
            val (handle, _, _) = stored()
            val socket = AppleL2CapSocket(handle, 42, Peer, onCleanupFailure = {}, autoStart = true)
            socket.write(byteArrayOf(i.toByte()))
            socket.close()
            assertFalse(store.contains(handle.id))
        }
        assertEquals(before, store.count())
    }

    @Test fun readsGoThroughTheStore() = runBlocking {
        val payload = ByteArray(10_000) { (it % 251).toByte() }
        val (handle, input, _) = stored(payload)
        val socket = AppleL2CapSocket(handle, 42, Peer, onCleanupFailure = {}, autoStart = true)
        // The store's stream delegate forwards this event to the socket by id.
        input.delegate!!.stream(input, NSStreamEventHasBytesAvailable)

        val received = withContext(Dispatchers.Default) {
            withTimeout(10_000) {
                val out = ArrayList<Byte>()
                socket.incoming.first { chunk -> chunk.forEach(out::add); out.size >= payload.size }
                out.toByteArray()
            }
        }

        assertContentEquals(payload, received)
        socket.close()
    }

    @Test fun writeAfterCloseFails() = runTest {
        val (handle, _, _) = stored()
        val socket = AppleL2CapSocket(handle, 42, Peer, onCleanupFailure = {}, autoStart = true)
        socket.close()
        assertFailsWith<L2capException> { socket.write(byteArrayOf(1)) }
    }

    @Test fun startingAChannelThatIsGoneFailsAndClosesTheSocket() {
        val (handle, _, _) = stored()
        handle.close()
        val socket = AppleL2CapSocket(handle, 42, Peer, onCleanupFailure = {}, autoStart = false)
        assertFailsWith<L2capException> { socket.start() }
        assertFalse(socket.isOpen)
    }

    @Test fun proxyForwardsOtherDelegateCallsToTheKotlinDelegate() {
        val delegate = RecordingDelegate()
        val sink = AppleL2capOpenSink { _, _, _ -> }
        val proxy = BFPeripheralDelegateProxy(delegate, sink)

        assertTrue(proxy.respondsToSelector(NSSelectorFromString("peripheral:didDiscoverServices:")))
        assertTrue(proxy.respondsToSelector(NSSelectorFromString("peripheral:didOpenL2CAPChannel:error:")))
        assertFalse(proxy.respondsToSelector(NSSelectorFromString("peripheral:didModifyServices:")))
        delegate.hashCode() // the proxy holds the delegate weakly
    }

    private fun stored(payload: ByteArray = byteArrayOf()): Triple<AppleL2capHandle, NSInputStream, NSOutputStream> {
        val input = NSInputStream(data = payload.toData())
        val output = NSOutputStream.outputStreamToMemory()
        val id = store.addOwner(NSObject(), input, output)
        return Triple(AppleL2capHandle(id), input, output)
    }

    private class RecordingDelegate : NSObject(), CBPeripheralDelegateProtocol {
        override fun peripheral(peripheral: CBPeripheral, didDiscoverServices: NSError?) = Unit
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
