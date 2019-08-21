package dev.bluefalcon

import android.Manifest
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager

actual class BlueFalcon(private val context: Context) {

    init {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            throw PermissionException()
    }

    actual val delegates: MutableList<BlueFalconDelegate> = arrayListOf()
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val mBluetoothScanCallBack = BluetoothScanCallBack()

    actual fun connect(bluetoothPeripheral: BluetoothPeripheral) {
        log("connect")
        bluetoothPeripheral.connectGatt(context, false, GattClientCallback())
    }

    actual fun disconnect(bluetoothPeripheral: BluetoothPeripheral) {
        log("disconnect")
        //no need to disconnect in Android.
    }

    actual fun scan() {
        log("BT Scan started")
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
            log("Failed to scan with code $errorCode")
        }

        private fun addScanResult(result: ScanResult?) {
            log("Found device ${result?.device?.address}")
            result?.let { scanResult ->
                scanResult.device?.let { device ->
                    delegates.forEach {
                        it.didConnect(device)
                    }
                }
            }
        }

    }

    inner class GattClientCallback: BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            super.onConnectionStateChange(gatt, status, newState)
            log("onConnectionStateChange")
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            log("onServicesDiscovered")
        }

        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            super.onMtuChanged(gatt, mtu, status)
            log("onMtuChanged$mtu status:$status")
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int) {
            log("onCharacteristicChanged")
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?) {
            log("onCharacteristicChanged")
        }
    }
}