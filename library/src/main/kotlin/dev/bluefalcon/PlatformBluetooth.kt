package dev.bluefalcon

import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log

actual class PlatformBluetooth actual constructor() : Bluetooth {

    private var platformContext: PlatformContext? = null
    private var bluetoothManager: BluetoothManager? = null
    private lateinit var mBluetoothScanCallBack: BluetoothScanCallBack

    constructor(platformContext: PlatformContext) : this() {
        this.platformContext = platformContext
        val context = platformContext.getContext() as Context
        this.bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    }

    override fun connect() {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun disconnect() {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun scan() {
        Log.i("Blue-Falcon", "BT Scan started")
        val filter = ScanFilter.Builder().build()
        val filters = listOf(filter)
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .build()
        val bluetoothScanner = bluetoothManager?.adapter?.bluetoothLeScanner
        mBluetoothScanCallBack = BluetoothScanCallBack()
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
            Log.e("Blue-Falcon", "Found device "+result?.device?.address)
        }

    }
}