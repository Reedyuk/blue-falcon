package dev.bluefalcon

import platform.Foundation.*

fun NSData.string(): String? {
    return NSString.create(this, NSUTF8StringEncoding) as String?
}