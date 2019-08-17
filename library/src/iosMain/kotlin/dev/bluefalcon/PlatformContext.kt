package dev.bluefalcon

import platform.UIKit.UIApplicationDelegateProtocol

class PlatformContext(private val context: UIApplicationDelegateProtocol) : PlatformContextInterface {

    override fun getContext(): Any {
        return context
    }

}