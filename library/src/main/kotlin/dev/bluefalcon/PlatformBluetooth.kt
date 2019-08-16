package dev.bluefalcon

import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.content.Context

actual class PlatformBluetooth : Bluetooth {
    override fun connect() {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun disconnect() {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun scan() {
        println("Scan")
        /*val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.let {
            bluetoothAdapter = it.adapter
        }
        val filter = ScanFilter.Builder().build()
        val filters = listOf(filter)
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .build()
        val bluetoothScanner = bluetoothAdapter?.bluetoothLeScanner
        mBluetoothScanCallBack = BluetoothScanCallBack()
        bluetoothScanner?.startScan(filters, settings, mBluetoothScanCallBack)*/
    }
}