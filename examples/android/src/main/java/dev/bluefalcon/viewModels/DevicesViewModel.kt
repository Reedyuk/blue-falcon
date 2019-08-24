package dev.bluefalcon.viewModels

import android.Manifest
import androidx.core.app.ActivityCompat
import dev.bluefalcon.*
import dev.bluefalcon.adapters.DevicesAdapter

class DevicesViewModel(private val devicesActivity: DevicesActivity) : BlueFalconDelegate {

    val devices: MutableList<BluetoothPeripheral> = arrayListOf()
    val devicesAdapter = DevicesAdapter(this)

    fun setupBluetooth() {
        try {
            val blueFalcon = BlueFalcon(devicesActivity)
            blueFalcon.delegates.add(this)
            blueFalcon.scan()
        } catch (exception: PermissionException) {
            requestLocationPermission()
        }
    }

    private fun requestLocationPermission() {
        val permission = arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION)
        ActivityCompat.requestPermissions(devicesActivity, permission, 0)
    }

    override fun didDiscoverDevice(bluetoothPeripheral: BluetoothPeripheral) {
        if (!devices.contains(bluetoothPeripheral)) {
            devices.add(bluetoothPeripheral)
            devicesAdapter.notifyDataSetChanged()
        }
    }

    override fun didConnect(bluetoothPeripheral: BluetoothPeripheral) {}

    override fun didDisconnect(bluetoothPeripheral: BluetoothPeripheral) {}

    override fun didDiscoverServices(bluetoothPeripheral: BluetoothPeripheral) {}

    override fun didDiscoverCharacteristics(bluetoothPeripheral: BluetoothPeripheral) {}

}