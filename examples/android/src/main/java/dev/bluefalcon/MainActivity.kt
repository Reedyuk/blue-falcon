package dev.bluefalcon

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    @RequiresApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        hasPermissions()

        BlueFalcon.init(PlatformBluetooth(PlatformContext(this)))
        BlueFalcon.scan()
    }

    private fun requestBluetoothPermission() {
        val enableBTIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
        this.startActivityForResult(enableBTIntent, 2)
        applicationContext.startActivity(enableBTIntent)
        Log.d("Morpheus", "Requested bt permission")
    }

    private fun requestLocationPermission() {
        val permission = arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
        ActivityCompat.requestPermissions(this, permission, 0)
    }

    private fun hasPermissions(): Boolean {
//        if (!bluetoothAdapter.isEnabled) {
//            requestBluetoothPermission(activity)
//            return false
//        } else
        if (!hasLocationPermission()) {
            requestLocationPermission()
            return false
        }
        return true
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
}
