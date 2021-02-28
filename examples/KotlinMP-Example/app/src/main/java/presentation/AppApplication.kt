package presentation

import android.app.Application
import dev.bluefalcon.BlueFalcon
import sample.BluetoothService

class AppApplication : Application() {

    companion object {
        lateinit var instance: AppApplication
            private set
    }

    val bluetoothService by lazy {
        BluetoothService(BlueFalcon(instance, null))
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }
}