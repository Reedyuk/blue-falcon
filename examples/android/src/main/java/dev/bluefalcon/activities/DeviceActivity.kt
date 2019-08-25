package dev.bluefalcon.activities

import android.bluetooth.BluetoothDevice
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import dev.bluefalcon.BluetoothPeripheral
import dev.bluefalcon.viewModels.DeviceViewModel
import org.jetbrains.anko.setContentView

class DeviceActivity : AppCompatActivity() {

    private lateinit var deviceViewModel: DeviceViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        deviceViewModel = DeviceViewModel(
            BluetoothPeripheral(intent.getParcelableExtra("device") as BluetoothDevice)
        )
        deviceViewModel.deviceActivityUI.setContentView(this)
    }
}