package dev.bluefalcon

import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.memScoped
import platform.Foundation.*
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.memScoped

fun NSData.string(): String? {
    return NSString.create(this, NSUTF8StringEncoding) as String?
}

fun ByteArray.toData(): NSData = memScoped {
    NSData.create(bytes = allocArrayOf(this@toData),
        length = this@toData.size.toULong())
}