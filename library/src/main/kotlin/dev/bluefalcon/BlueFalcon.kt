package dev.bluefalcon

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

actual class BlueFalcon(private val context: Context) {

    init {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            throw PermissionException()
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val mBluetoothScanCallBack = BluetoothScanCallBack()

    actual fun connect() {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    actual fun disconnect() {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    actual fun scan() {
        Log.i("Blue-Falcon", "BT Scan started")
        val filter = ScanFilter.Builder().build()
        val filters = listOf(filter)
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .build()
        val bluetoothScanner = bluetoothManager.adapter?.bluetoothLeScanner
        bluetoothScanner?.startScan(filters, settings, mBluetoothScanCallBack)
    }

    inner class BluetoothScanCallBack: ScanCallback() {

        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            addScanResult(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            results?.forEach { addScanResult(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e("Blue-Falcon", "Failed to scan with code "+errorCode)
        }

        private fun addScanResult(result: ScanResult?) {
            Log.i("Blue-Falcon", "Found device "+result?.device?.address)
        }

    }
}