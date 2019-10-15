package presentation.activities

import android.Manifest
import android.os.Bundle
import android.support.v4.app.ActivityCompat
import android.support.v7.app.AppCompatActivity
import android.widget.TextView
import dev.bluefalcon.BluetoothPermissionException
import presentation.AppApplication
import presentation.viewmodels.DevicesViewModel
import presentation.viewmodels.DevicesViewModelOutput
import sample.*

class DevicesActivity : AppCompatActivity(), DevicesViewModelOutput {

    private val viewModel = DevicesViewModel(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Sample().checkMe()
        setContentView(R.layout.activity_main)
        findViewById<TextView>(R.id.main_text).text = hello()
        AppApplication.instance.bluetoothService.addDevicesDelegate(viewModel)
        setupBluetooth()
    }

    override fun refresh() {
        print("Bluetooth Device "+viewModel.devices[viewModel.devices.size-1].bluetoothDevice.address)
    }

    fun setupBluetooth() {
        try {
            AppApplication.instance.bluetoothService.scan()
        } catch (exception: BluetoothPermissionException) {
            requestLocationPermission()
        }
    }

    private fun requestLocationPermission() {
        val permission = arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION)
        ActivityCompat.requestPermissions(this, permission, 0)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        setupBluetooth()
    }
    
}