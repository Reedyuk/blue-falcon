package dev.bluefalcon

import android.content.Context

class PlatformContext(private val context: Context) : PlatformContextInterface {

    override fun getContext(): Any {
        return context
    }

}