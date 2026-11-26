package dev.bluefalcon.engine.apple

import dev.bluefalcon.engine.apple.objc.BFL2capChannelStore
import dev.bluefalcon.engine.apple.objc.BFL2capOpenSinkProtocol
import platform.CoreBluetooth.CBL2CAPChannel
import platform.CoreBluetooth.CBPeripheral
import platform.Foundation.NSError
import platform.darwin.NSObject

/**
 * An L2CAP channel kept on the Objective-C side, in `BFL2capChannelStore` (#334).
 *
 * Core Bluetooth closes a `CBL2CAPChannel` over the air when the channel object is deallocated.
 * Kotlin/Native releases an Objective-C object only when its GC frees the Kotlin reference, so a
 * channel that reaches Kotlin can stay open long after `close()`. The engine therefore never holds
 * the channel or its streams: `BFPeripheralDelegateProxy` stores them and hands Kotlin this id,
 * and [close] drops them, so ARC deallocates the channel at once.
 */
internal class AppleL2capHandle(val id: Long) {
    init {
        require(id > 0) { "Invalid L2CAP channel id $id" }
    }

    fun close() = BFL2capChannelStore.shared().closeChannel(id)

    override fun toString(): String = "AppleL2capHandle($id)"

    companion object {
        /**
         * Stores a channel that already reached Kotlin, from the public
         * [CBPeripheralCallback.onL2CAPChannelOpened]. Only the engine's own proxy path keeps the
         * channel out of Kotlin entirely.
         */
        fun adopt(channel: CBL2CAPChannel): AppleL2capHandle? {
            val input = channel.inputStream ?: return null
            val output = channel.outputStream ?: return null
            return AppleL2capHandle(BFL2capChannelStore.shared().addOwner(channel, input, output))
        }
    }
}

/** Receives opens from `BFPeripheralDelegateProxy`. The channel itself stays in the store. */
internal class AppleL2capOpenSink(
    private val onOpened: (peripheral: CBPeripheral, channel: AppleL2capHandle?, error: NSError?) -> Unit,
) : NSObject(), BFL2capOpenSinkProtocol {
    override fun bfPeripheral(
        peripheral: CBPeripheral,
        didOpenL2capChannelId: Long,
        psm: UShort,
        error: NSError?,
    ) {
        onOpened(peripheral, if (didOpenL2capChannelId > 0) AppleL2capHandle(didOpenL2capChannelId) else null, error)
    }
}
