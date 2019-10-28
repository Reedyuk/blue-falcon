package dev.bluefalcon

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelUuid
import java.util.*

actual class BlueFalcon actual constructor(
    private val context: ApplicationContext,
    private val serviceUUID: String?
) {
    actual val delegates: MutableSet<BlueFalconDelegate> = mutableSetOf()
    private val bluetoothManager: BluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val mBluetoothScanCallBack = BluetoothScanCallBack()
    private val mGattClientCallback = GattClientCallback()
    actual var isScanning: Boolean = false

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

    actual fun stopScanning() {
        isScanning = false
        bluetoothManager.adapter?.bluetoothLeScanner?.stopScan(mBluetoothScanCallBack)
    }

    actual fun scan() {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            throw BluetoothPermissionException()
        log("BT Scan started")
        isScanning = true

        val filterBuilder = ScanFilter.Builder()
        serviceUUID?.let {
            filterBuilder.setServiceUuid(ParcelUuid(UUID.fromString(it)))
        }
        val filter = filterBuilder.build()
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
                it.uuid == bluetoothCharacteristic.characteristic.uuid
            }.map {
                BluetoothCharacteristic(it)
            }
        }

    actual fun readCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic
    ) {
        mGattClientCallback.gattForDevice(bluetoothPeripheral.bluetoothDevice)?.let { gatt ->
            fetchCharacteristic(bluetoothCharacteristic, gatt)
                .forEach { gatt.readCharacteristic(it.characteristic) }
        }
    }

    actual fun notifyCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        notify: Boolean
    ) {
        mGattClientCallback.gattForDevice(bluetoothPeripheral.bluetoothDevice)?.let { gatt ->
            fetchCharacteristic(bluetoothCharacteristic, gatt)
                .forEach {
                    gatt.setCharacteristicNotification(it.characteristic, notify)
                    it.characteristic.descriptors.forEach {descriptor ->
                        descriptor.value = if (notify) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else byteArrayOf(
                            0x00,
                            0x00
                        )
                        gatt.writeDescriptor(descriptor)
                    }
                }
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
                    it.characteristic.setValue(value)
                    gatt.writeCharacteristic(it.characteristic)
                }
        }
    }

    actual fun changeMTU(bluetoothPeripheral: BluetoothPeripheral, mtuSize: Int) {
        mGattClientCallback.gattForDevice(bluetoothPeripheral.bluetoothDevice)?.requestMtu(mtuSize)
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
                    log("onServicesDiscovered -> $services")
                    val bluetoothPeripheral = BluetoothPeripheral(bluetoothDevice)
                    bluetoothPeripheral.deviceServices = services.map { BluetoothService(it) }
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
            if (status != BluetoothGatt.GATT_SUCCESS) {
                return
            }
            gatt?.device?.let { bluetoothDevice ->
                delegates.forEach {
                    it.didUpdateMTU(BluetoothPeripheral(bluetoothDevice))
                }
            }
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int) {
            handleCharacteristicValueChange(gatt, characteristic)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?) {
            handleCharacteristicValueChange(gatt, characteristic)
        }

        private fun handleCharacteristicValueChange(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?) {
            characteristic?.let { forcedCharacteristic ->
                val characteristic = BluetoothCharacteristic(forcedCharacteristic)
                gatt?.device?.let { bluetoothDevice ->
                    delegates.forEach {
                        it.didCharacteristcValueChanged(BluetoothPeripheral(bluetoothDevice), characteristic)
                    }
                }
            }
        }
    }

}