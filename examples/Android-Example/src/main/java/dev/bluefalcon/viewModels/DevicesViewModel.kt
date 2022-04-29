package dev.bluefalcon.viewModels

import android.Manifest
import androidx.core.app.ActivityCompat
import dev.bluefalcon.*
import dev.bluefalcon.activities.DevicesActivity
import dev.bluefalcon.adapters.DevicesAdapter
import dev.bluefalcon.services.BluetoothServiceDetectedDeviceDelegate
import dev.bluefalcon.views.DevicesActivityUI

class DevicesViewModel(private val devicesActivity: DevicesActivity): BluetoothServiceDetectedDeviceDelegate {

    var devices: List<BluetoothPeripheral> = emptyList()
    val devicesAdapter = DevicesAdapter(emptyList())
    val devicesActivityUI = DevicesActivityUI(this)

    fun setupBluetooth() {
        try {
            BlueFalconApplication.instance.bluetoothService.scan()
        } catch (exception: BluetoothPermissionException) {
            requestLocationPermission()
        }
    }

    fun addDelegate() {
        BlueFalconApplication.instance.bluetoothService.detectedDeviceDelegates.add(this)
    }

    fun removeDelegate() {
        BlueFalconApplication.instance.bluetoothService.detectedDeviceDelegates.remove(this)
    }

    private fun requestLocationPermission() {
        val permission = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        ActivityCompat.requestPermissions(devicesActivity, permission, 0)
    }

    override fun discoveredDevice(devices: List<BluetoothPeripheral>) {
        devicesActivity.runOnUiThread {
            this.devices = devices
            devicesAdapter.devices = devices
            devicesAdapter.notifyDataSetChanged()
        }
    }

}