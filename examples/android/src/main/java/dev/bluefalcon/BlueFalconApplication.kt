package dev.bluefalcon

import android.app.Application
import dev.bluefalcon.services.BluetoothService

class BlueFalconApplication: Application() {

    companion object {
        lateinit var instance: BlueFalconApplication
            private set
    }

    val bluetoothService: BluetoothService by lazy {
        BluetoothService()
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

}