package presentation.activities

import android.bluetooth.BluetoothDevice
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.util.Log
import dev.bluefalcon.BluetoothPeripheral
import org.jetbrains.anko.setContentView
import presentation.AppApplication
import presentation.viewmodels.device.DeviceViewModel
import presentation.viewmodels.device.DeviceViewModelOutput
import presentation.views.DeviceActivityUI

class DeviceActivity : AppCompatActivity(), DeviceViewModelOutput {

    private lateinit var viewModel: DeviceViewModel
    private lateinit var deviceUI: DeviceActivityUI

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val bluetoothDevice = intent.getParcelableExtra("device") as BluetoothDevice?

        if(bluetoothDevice != null) {
            val device = BluetoothPeripheral(bluetoothDevice)
            viewModel = DeviceViewModel(this, device, AppApplication.instance.bluetoothService)
            deviceUI = DeviceActivityUI(this, viewModel)
            deviceUI.setContentView(this)
        } else {
            Log.e("DeviceActivity", "bluetoothDevice is null")
        }
    }

    override fun refresh() {
        deviceUI.refresh()
    }

}