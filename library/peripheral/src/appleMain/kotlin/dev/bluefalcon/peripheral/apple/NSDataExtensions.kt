package dev.bluefalcon.peripheral.apple

import kotlinx.cinterop.*
import platform.Foundation.*
import platform.posix.memcpy

@OptIn(ExperimentalForeignApi::class)
fun NSData.toByteArray(): ByteArray {
    val data = this
    val d = memScoped { data }
    val length = d.length.toInt()
    if (length == 0) return ByteArray(0)

    return ByteArray(length).apply {
        usePinned {
            memcpy(it.addressOf(0), d.bytes, d.length)
        }
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
fun ByteArray.toData(): NSData = memScoped {
    NSData.create(bytes = allocArrayOf(this@toData),
        length = this@toData.size.toULong())
}
