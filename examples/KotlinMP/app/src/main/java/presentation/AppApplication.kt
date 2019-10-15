package presentation

import android.app.Application
import android.content.Context
import sample.BluetoothService

class AppApplication : Application() {

    companion object {

        lateinit var appContext: Context
        lateinit var bluetoothService = BluetoothService(BlueFalcon(appContext, null))
    }

    override fun onCreate() {
        super.onCreate()

        appContext = applicationContext
    }
}