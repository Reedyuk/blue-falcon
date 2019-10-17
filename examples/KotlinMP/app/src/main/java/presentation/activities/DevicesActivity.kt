package presentation.activities

import android.Manifest
import android.os.Bundle
import android.support.v4.app.ActivityCompat
import android.support.v7.app.AppCompatActivity
import org.jetbrains.anko.setContentView
import presentation.AppApplication
import presentation.viewmodels.DevicesViewModel
import presentation.viewmodels.DevicesViewModelOutput
import presentation.views.DevicesActivityUI

class DevicesActivity : AppCompatActivity(), DevicesViewModelOutput {

    private val viewModel = DevicesViewModel(this, AppApplication.instance.bluetoothService)
    private val devicesUI = DevicesActivityUI(viewModel)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        devicesUI.setContentView(this)
        viewModel.scan()
    }

    override fun refresh() {
        devicesUI.refresh()
    }

    override fun requiresBluetoothPermission() {
        requestLocationPermission()
    }

    private fun requestLocationPermission() {
        val permission = arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION)
        ActivityCompat.requestPermissions(this, permission, 0)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        viewModel.scan()
    }
    
}