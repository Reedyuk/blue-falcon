package dev.bluefalcon

import android.app.Application

class BlueFalconApplication: Application() {

    companion object {
        lateinit var instance: BlueFalconApplication
            private set
    }

    val blueFalcon: BlueFalcon by lazy {
        BlueFalcon(this)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

}