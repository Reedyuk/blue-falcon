package presentation.activities

import android.bluetooth.BluetoothGattService
import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import dev.bluefalcon.BluetoothCharacteristic
import dev.bluefalcon.BluetoothService
import org.jetbrains.anko.setContentView
import presentation.viewmodels.deviceservice.DeviceCharacteristicsViewModel
import presentation.viewmodels.deviceservice.DeviceCharacteristicsViewModelOutput
import presentation.views.DeviceServiceActivityUI

class DeviceServiceActivity: AppCompatActivity(), DeviceCharacteristicsViewModelOutput {

    private lateinit var viewModel: DeviceCharacteristicsViewModel
    private lateinit var deviceUI: DeviceServiceActivityUI

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val service = BluetoothService(intent.getParcelableExtra("service") as BluetoothGattService)
        val characteristics = service.service.characteristics.map {
            BluetoothCharacteristic(it)
        }
        viewModel = DeviceCharacteristicsViewModel(this, service, characteristics)
        deviceUI = DeviceServiceActivityUI(this, viewModel)
        deviceUI.setContentView(this)
    }

    override fun refresh() {
        deviceUI.refresh()
    }

}