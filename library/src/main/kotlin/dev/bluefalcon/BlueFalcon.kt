package dev.bluefalcon

import android.Manifest
import android.bluetooth.*
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
    private val mGattClientCallback = GattClientCallback()

    actual fun connect(bluetoothPeripheral: BluetoothPeripheral) {
        log("connect")
        bluetoothPeripheral.bluetoothDevice.connectGatt(context, false, mGattClientCallback)
    }

    actual fun disconnect(bluetoothPeripheral: BluetoothPeripheral) {
        log("disconnect")
        mGattClientCallback.gattForDevice(bluetoothPeripheral.bluetoothDevice)?.disconnect()
        delegates.forEach {
            it.didDisconnect(bluetoothPeripheral)
        }
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

    private fun fetchCharacteristic(
        bluetoothCharacteristic: BluetoothCharacteristic,
        gatt: BluetoothGatt): List<BluetoothCharacteristic> =
        gatt.services.flatMap { service ->
            service.characteristics.filter {
                it.uuid == bluetoothCharacteristic.uuid
            }
        }

    actual fun readCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic
    ) {
        mGattClientCallback.gattForDevice(bluetoothPeripheral.bluetoothDevice)?.let { gatt ->
            fetchCharacteristic(bluetoothCharacteristic, gatt)
                .forEach { gatt.readCharacteristic(it) }
        }
    }

    actual fun notifyCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        notify: Boolean
    ) {
        mGattClientCallback.gattForDevice(bluetoothPeripheral.bluetoothDevice)?.let { gatt ->
            fetchCharacteristic(bluetoothCharacteristic, gatt)
                .forEach { gatt.setCharacteristicNotification(it, notify) }
        }
    }

    actual fun writeCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        value: String
    ) {
        mGattClientCallback.gattForDevice(bluetoothPeripheral.bluetoothDevice)?.let { gatt ->
            fetchCharacteristic(bluetoothCharacteristic, gatt)
                .forEach {
                    it.setValue(value)
                    gatt.writeCharacteristic(it)
                }
        }
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
            result?.let { scanResult ->
                scanResult.device?.let { device ->
                    delegates.forEach {
                        it.didDiscoverDevice(BluetoothPeripheral(device))
                    }
                }
            }
        }

    }

    inner class GattClientCallback: BluetoothGattCallback() {

        private val gatts: MutableList<BluetoothGatt> = mutableListOf()

        private fun addGatt(gatt: BluetoothGatt) {
            if (gatts.firstOrNull { it.device == gatt.device } == null) {
                gatts.add(gatt)
            }
        }

        fun gattForDevice(bluetoothDevice: BluetoothDevice): BluetoothGatt? =
            gatts.firstOrNull { it.device == bluetoothDevice }

        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            super.onConnectionStateChange(gatt, status, newState)
            log("onConnectionStateChange")
            gatt?.let { bluetoothGatt ->
                bluetoothGatt.device.let {
                    addGatt(bluetoothGatt)
                    bluetoothGatt.discoverServices()
                    delegates.forEach {
                        it.didConnect(BluetoothPeripheral(bluetoothGatt.device))
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            log("onServicesDiscovered")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                return
            }
            gatt?.device?.let { bluetoothDevice ->
                gatt.services.let { services ->
                    val bluetoothPeripheral = BluetoothPeripheral(bluetoothDevice)
                    bluetoothPeripheral.services = services.toList()
                    delegates.forEach {
                        it.didDiscoverServices(bluetoothPeripheral)
                        it.didDiscoverCharacteristics(bluetoothPeripheral)
                    }
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            super.onMtuChanged(gatt, mtu, status)
            log("onMtuChanged$mtu status:$status")
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int) {
            handleCharacteristicValueChange(gatt, characteristic)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?) {
            handleCharacteristicValueChange(gatt, characteristic)
        }

        private fun handleCharacteristicValueChange(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?) {
            characteristic?.let { forcedCharacteristic ->
                gatt?.device?.let { bluetoothDevice ->
                    delegates.forEach {
                        it.didCharacteristcValueChanged(BluetoothPeripheral(bluetoothDevice), forcedCharacteristic)
                    }
                }
            }
        }
    }

}