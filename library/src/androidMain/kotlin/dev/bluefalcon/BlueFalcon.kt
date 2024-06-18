package dev.bluefalcon

import AdvertisementDataRetrievalKeys
import android.Manifest
import android.bluetooth.*
import android.bluetooth.BluetoothAdapter.STATE_CONNECTED
import android.bluetooth.BluetoothAdapter.STATE_DISCONNECTED
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.ByteBuffer
import java.util.*

actual class BlueFalcon actual constructor(
    private val context: ApplicationContext,
    private val serviceUUID: String?
) {
    actual val delegates: MutableSet<BlueFalconDelegate> = mutableSetOf()
    private val bluetoothManager: BluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val mBluetoothScanCallBack = BluetoothScanCallBack()
    private val mGattClientCallback = GattClientCallback()
    var transportMethod: Int = BluetoothDevice.TRANSPORT_AUTO
    actual var isScanning: Boolean = false

    actual val scope = CoroutineScope(Dispatchers.Default)
    internal actual val _peripherals = MutableStateFlow<Set<BluetoothPeripheral>>(emptySet())
    actual val peripherals: NativeFlow<Set<BluetoothPeripheral>> = _peripherals.toNativeType(scope)

    actual fun connect(bluetoothPeripheral: BluetoothPeripheral, autoConnect: Boolean) {
        log("connect")
        bluetoothPeripheral.bluetoothDevice.connectGatt(
            context,
            autoConnect,
            mGattClientCallback,
            transportMethod
        )
    }

    actual fun disconnect(bluetoothPeripheral: BluetoothPeripheral) {
        log("disconnect")
        val gatt = mGattClientCallback.gattForDevice(bluetoothPeripheral.bluetoothDevice)
        gatt?.apply {
            disconnect()
            close()
        }

        if (gatt != null) {
            mGattClientCallback.removeGatt(gatt)
        }
        delegates.forEach { it.didDisconnect(bluetoothPeripheral) }
    }

    actual fun stopScanning() {
        isScanning = false
        bluetoothManager.adapter?.bluetoothLeScanner?.stopScan(mBluetoothScanCallBack)
    }

    actual fun scan(uuid :String?) {
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
        gatt: BluetoothGatt
    ): List<BluetoothCharacteristic> =
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

    private fun setCharacteristicNotification(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        enable: Boolean,
        descriptorValue: ByteArray
    ) {
        mGattClientCallback.gattForDevice(bluetoothPeripheral.bluetoothDevice)?.let { gatt ->
            fetchCharacteristic(bluetoothCharacteristic, gatt)
                .forEach {
                    gatt.setCharacteristicNotification(it.characteristic, enable)
                    it.characteristic.descriptors.forEach { descriptor ->
                        descriptor.value = descriptorValue
                        gatt.writeDescriptor(descriptor)
                    }
                }
        }
    }

    actual fun notifyCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        notify: Boolean
    ) {
        setCharacteristicNotification(
            bluetoothPeripheral,
            bluetoothCharacteristic,
            notify,
            if (notify)
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            else
                BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
        )
    }

    actual fun indicateCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        indicate: Boolean
    ) {
        setCharacteristicNotification(
            bluetoothPeripheral,
            bluetoothCharacteristic,
            indicate,
            if (indicate)
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            else
                BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
        )
    }

    actual fun notifyAndIndicateCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        enable: Boolean
    ) {
        setCharacteristicNotification(
            bluetoothPeripheral,
            bluetoothCharacteristic,
            enable,
            if (enable)
                byteArrayOf(
                    0x03,
                    0x00
                )
            else
                BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
        )
    }

    actual fun writeCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        value: String,
        writeType: Int?
    ) {
        mGattClientCallback.gattForDevice(bluetoothPeripheral.bluetoothDevice)?.let { gatt ->
            fetchCharacteristic(bluetoothCharacteristic, gatt)
                .forEach {
                    writeType?.let { writeType ->
                        it.characteristic.writeType = writeType
                    }
                    it.characteristic.setValue(value)
                    gatt.writeCharacteristic(it.characteristic)
                }
        }
    }

    actual fun writeCharacteristicWithoutEncoding(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        value: ByteArray,
        writeType: Int?
    ) {
        writeCharacteristic(bluetoothPeripheral, bluetoothCharacteristic, value, writeType)
    }

    actual fun writeCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        value: ByteArray,
        writeType: Int?
    ) {
        mGattClientCallback.gattForDevice(bluetoothPeripheral.bluetoothDevice)?.let { gatt ->
            fetchCharacteristic(bluetoothCharacteristic, gatt)
                .forEach {
                    writeType?.let { writeType ->
                        it.characteristic.writeType = writeType
                    }
                    it.characteristic.setValue(value)
                    gatt.writeCharacteristic(it.characteristic)
                }
        }
    }

    actual fun readDescriptor(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        bluetoothCharacteristicDescriptor: BluetoothCharacteristicDescriptor
    ) {
        mGattClientCallback.gattForDevice(bluetoothPeripheral.bluetoothDevice)
            ?.readDescriptor(bluetoothCharacteristicDescriptor)
        log("readDescriptor -> ${bluetoothCharacteristicDescriptor.uuid}")
    }

    actual fun changeMTU(bluetoothPeripheral: BluetoothPeripheral, mtuSize: Int) {
        mGattClientCallback.gattForDevice(bluetoothPeripheral.bluetoothDevice)?.requestMtu(mtuSize)
    }

    inner class BluetoothScanCallBack : ScanCallback() {

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
                    val isConnectable = if (Build.VERSION.SDK_INT >= 26) {
                        scanResult.isConnectable
                    } else {
                        false
                    }

                    val sharedAdvertisementData =
                        mapNativeAdvertisementDataToShared(
                            receivedScanRecord = scanResult.scanRecord,
                            isConnectable = isConnectable
                        )

                    val bluetoothPeripheral = BluetoothPeripheral(device)
                    bluetoothPeripheral.rssi = scanResult.rssi.toFloat()

                    _peripherals.tryEmit(_peripherals.value + setOf(bluetoothPeripheral))
                    delegates.forEach {
                        it.didDiscoverDevice(bluetoothPeripheral, sharedAdvertisementData)
                    }
                }
            }
        }

    }

    inner class GattClientCallback : BluetoothGattCallback() {

        private val gatts: MutableList<BluetoothGatt> = mutableListOf()

        private fun addGatt(gatt: BluetoothGatt) {
            if (gatts.firstOrNull { it.device == gatt.device } == null) {
                gatts.add(gatt)
            }
        }

        fun removeGatt(gatt: BluetoothGatt) {
            gatts.remove(gatt)
        }

        fun gattForDevice(bluetoothDevice: BluetoothDevice): BluetoothGatt? =
            gatts.firstOrNull { it.device == bluetoothDevice }

        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            super.onConnectionStateChange(gatt, status, newState)
            log("onConnectionStateChange")
            gatt?.let { bluetoothGatt ->
                bluetoothGatt.device.let {
                    //BluetoothProfile#STATE_DISCONNECTED} or {@link BluetoothProfile#STATE_CONNECTED}
                    if (newState == STATE_CONNECTED) {
                        addGatt(bluetoothGatt)
                        bluetoothGatt.readRemoteRssi()
                        bluetoothGatt.discoverServices()
                        delegates.forEach {
                            it.didConnect(BluetoothPeripheral(bluetoothGatt.device))
                        }
                    } else if (newState == STATE_DISCONNECTED) {
                        removeGatt(bluetoothGatt)
                        bluetoothGatt.close()
                        delegates.forEach {
                            it.didDisconnect(BluetoothPeripheral(bluetoothGatt.device))
                        }
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
                    bluetoothPeripheral._servicesFlow.tryEmit(services.map {  BluetoothService(it) })
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

        override fun onReadRemoteRssi(gatt: BluetoothGatt?, rssi: Int, status: Int) {
            log("onReadRemoteRssi $rssi")
            gatt?.device?.let { bluetoothDevice ->
                val bluetoothPeripheral = BluetoothPeripheral(bluetoothDevice)
                bluetoothPeripheral.rssi = rssi.toFloat()
                delegates.forEach {
                    it.didRssiUpdate(
                        bluetoothPeripheral
                    )
                }
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            handleCharacteristicValueChange(gatt, characteristic)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?
        ) {
            handleCharacteristicValueChange(gatt, characteristic)
        }

        override fun onDescriptorRead(
            gatt: BluetoothGatt?,
            descriptor: BluetoothGattDescriptor?,
            status: Int
        ) {
            log("onDescriptorRead $descriptor")
            descriptor?.let { forcedDescriptor ->
                gatt?.device?.let { bluetoothDevice ->
                    log("onDescriptorRead value ${forcedDescriptor.value}")
                    delegates.forEach {
                        it.didReadDescriptor(
                            BluetoothPeripheral(bluetoothDevice),
                            forcedDescriptor
                        )
                    }
                }
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt?,
            descriptor: BluetoothGattDescriptor?,
            status: Int
        ) {
            log("onDescriptorWrite $descriptor")
            descriptor?.let { forcedDescriptor ->
                gatt?.device?.let { bluetoothDevice ->
                    log("onDescriptorWrite value ${forcedDescriptor.value}")
                    delegates.forEach {
                        it.didWriteDescriptor(
                            BluetoothPeripheral(bluetoothDevice),
                            forcedDescriptor
                        )
                    }
                }
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            characteristic?.let { forcedCharacteristic ->
                val characteristic = BluetoothCharacteristic(forcedCharacteristic)
                gatt?.device?.let { bluetoothDevice ->
                    delegates.forEach {
                        it.didWriteCharacteristic(
                            BluetoothPeripheral(bluetoothDevice),
                            characteristic,
                            status == BluetoothGatt.GATT_SUCCESS
                        )
                    }
                }
            }
        }

        private fun handleCharacteristicValueChange(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?
        ) {
            characteristic?.let { forcedCharacteristic ->
                val characteristic = BluetoothCharacteristic(forcedCharacteristic)
                gatt?.device?.let { bluetoothDevice ->
                    delegates.forEach {
                        it.didCharacteristcValueChanged(
                            BluetoothPeripheral(bluetoothDevice),
                            characteristic
                        )
                    }
                }
            }
        }
    }

    //Helper
    fun mapNativeAdvertisementDataToShared(
        receivedScanRecord: ScanRecord?,
        isConnectable: Boolean
    ): Map<AdvertisementDataRetrievalKeys, Any> {
        val sharedAdvertisementData = mutableMapOf<AdvertisementDataRetrievalKeys, Any>()
        val scanRecord = receivedScanRecord ?: return sharedAdvertisementData
        val advertisementBytes = scanRecord.bytes

        sharedAdvertisementData[AdvertisementDataRetrievalKeys.IsConnectable] =
            if (isConnectable) 1 else 0

        var index = 0
        while (index < advertisementBytes.size) {
            val length = advertisementBytes[index].toUByte().toInt()
            index += 1

            //Zero value indicates that we are done with the record now
            if (length == 0) break

            val type = advertisementBytes[index].toUByte().toInt()

            //if the type is zero, then we are pass the significant section of the data,
            // and we are  done
            if (type == 0) break

            if (index + length > advertisementBytes.size - 1) {
                // we  pass the significant section of the data,
                // and we are done
                break
            }

            //Keys according to:
            // https://github.com/zephyrproject-rtos/zephyr/blob/1e02dd0dc1958fed957c6962ad4213c556639188/include/zephyr/bluetooth/gap.h#L29
            val value = advertisementBytes.copyOfRange(index + 1, index + length)
            when (type) {
                0x09 -> {
                    sharedAdvertisementData[AdvertisementDataRetrievalKeys.LocalName] =
                        String(value)

                }
                0xff -> {
                    sharedAdvertisementData[AdvertisementDataRetrievalKeys.ManufacturerData] =
                        value

                }
                0x07 -> {
                    val uuidAsStringList = mutableListOf<String>()
                    value.reverse() //Because service uuids are in reversed order

                    var uuidIndex = 0
                    val uuidLength = 16 //One UUID has the size of 128bit which are 16 bits.
                    while (uuidIndex + uuidLength <= value.size) {
                        val uuidAsBytes = value.copyOfRange(uuidIndex, uuidIndex + uuidLength)
                        val byteBuffer = ByteBuffer.wrap(uuidAsBytes)
                        val high = byteBuffer.long
                        val low = byteBuffer.long
                        val uuid = UUID(high, low)

                        uuidAsStringList.add(uuid.toString())
                        uuidIndex += uuidLength
                    }

                    sharedAdvertisementData[AdvertisementDataRetrievalKeys.ServiceUUIDsKey] =
                        uuidAsStringList
                }
            }

            index += length
        }
        return sharedAdvertisementData
    }
}
