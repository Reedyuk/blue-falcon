package dev.bluefalcon

import kotlinx.cinterop.*
import platform.Foundation.*
import platform.posix.memcpy

fun NSData.string(): String? {
    return NSString.create(this, NSUTF8StringEncoding) as String?
}


fun NSData.toByteArray(): ByteArray {
    val data = this
    val d = memScoped { data }
    return ByteArray(d.length.toInt()).apply {
        usePinned {
            memcpy(it.addressOf(0), d.bytes, d.length)
        }
    }
}

fun ByteArray.toData(): NSData = memScoped {
    NSData.create(bytes = allocArrayOf(this@toData),
        length = this@toData.size.toULong())
}