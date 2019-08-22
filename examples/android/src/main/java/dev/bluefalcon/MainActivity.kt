package dev.bluefalcon

import android.Manifest
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat

class MainActivity : AppCompatActivity() {

    //consider observable?
    private val devices: MutableList<BluetoothPeripheral> = arrayListOf()
    private val bluetoothDelegate = BluetoothDelegate()

    @RequiresApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setupBluetooth()
    }

    private fun setupBluetooth() {
        try {
            val blueFalcon = BlueFalcon(this)
            blueFalcon.delegates.add(bluetoothDelegate)
            blueFalcon.scan()
        } catch (exception: PermissionException) {
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

    inner class BluetoothDelegate: BlueFalconDelegate {

        override fun didDiscoverDevice(bluetoothPeripheral: BluetoothPeripheral) {
            devices.add(bluetoothPeripheral)
            //refresh list?
        }

        override fun didConnect(bluetoothPeripheral: BluetoothPeripheral) {
        }

        override fun didDisconnect(bluetoothPeripheral: BluetoothPeripheral) {
        }

    }
}
