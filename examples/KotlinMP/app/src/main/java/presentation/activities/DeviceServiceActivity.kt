package presentation.activities

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattService
import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import dev.bluefalcon.BluetoothCharacteristic
import dev.bluefalcon.BluetoothPeripheral
import dev.bluefalcon.BluetoothService
import org.jetbrains.anko.setContentView
import presentation.AppApplication
import presentation.viewmodels.deviceservice.DeviceCharacteristicViewModelOutput
import presentation.viewmodels.deviceservice.DeviceCharacteristicsViewModel
import presentation.views.DeviceServiceActivityUI

class DeviceServiceActivity: AppCompatActivity(), DeviceCharacteristicViewModelOutput {
    private lateinit var viewModel: DeviceCharacteristicsViewModel
    private lateinit var deviceUI: DeviceServiceActivityUI

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val service = BluetoothService(intent.getParcelableExtra("service") as BluetoothGattService)
        val device = BluetoothPeripheral(intent.getParcelableExtra("device") as BluetoothDevice)
        val characteristics = service.service.characteristics.map {
            BluetoothCharacteristic(it)
        }
        viewModel = DeviceCharacteristicsViewModel(
            AppApplication.instance.bluetoothService,
            device,
            service,
            characteristics
        )
        deviceUI = DeviceServiceActivityUI(this, viewModel)
        deviceUI.setContentView(this)
    }

    override fun refresh() {
        deviceUI.refresh()
    }

}