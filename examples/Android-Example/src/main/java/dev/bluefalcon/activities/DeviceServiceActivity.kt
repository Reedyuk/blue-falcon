package dev.bluefalcon.activities

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattService
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import dev.bluefalcon.BluetoothPeripheral
import dev.bluefalcon.viewModels.DeviceServiceViewModel
import org.jetbrains.anko.setContentView

class DeviceServiceActivity : AppCompatActivity() {

    private lateinit var deviceServiceViewModel: DeviceServiceViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        deviceServiceViewModel = DeviceServiceViewModel(
            this,
            BluetoothPeripheral(
                intent.getParcelableExtra("device")!!
            ),
            intent.getParcelableExtra("service")!!
        )
        deviceServiceViewModel.deviceServiceActivityUI.setContentView(this)
    }
}