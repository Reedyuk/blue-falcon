package dev.bluefalcon

import android.content.Context

//fun BlueFalcon(context: Context) = BlueFalcon(PlatformBluetooth(PlatformContext(context)))

class PlatformContext(private val context: Context) : PlatformContextInterface {

    override fun getContext(): Any {
        return context
    }

}