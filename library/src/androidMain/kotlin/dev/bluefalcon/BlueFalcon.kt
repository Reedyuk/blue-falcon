package dev.bluefalcon

import android.bluetooth.*
import android.bluetooth.BluetoothAdapter.STATE_CONNECTED
import android.bluetooth.BluetoothAdapter.STATE_DISCONNECTED
import android.bluetooth.le.*
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.util.*

actual class BlueFalcon actual constructor(
    private val log: Logger?,
    private val context: ApplicationContext,
    private val autoDiscoverAllServicesAndCharacteristics: Boolean
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
    actual val managerState: StateFlow<BluetoothManagerState> = MutableStateFlow(BluetoothManagerState.Ready)

    actual fun requestConnectionPriority(
        bluetoothPeripheral: BluetoothPeripheral,
        connectionPriority: ConnectionPriority
    ) {
        log?.info("requestConnectionPriority")
        mGattClientCallback.gattsForDevice(bluetoothPeripheral.device).forEach { gatt ->
            gatt.requestConnectionPriority(connectionPriority.toNative())
        }
    }

    actual fun connectionState(bluetoothPeripheral: BluetoothPeripheral): BluetoothPeripheralState =
        when (bluetoothManager.getConnectionState(bluetoothPeripheral.device, BluetoothProfile.GATT)) {
            BluetoothProfile.STATE_CONNECTED -> BluetoothPeripheralState.Connected
            BluetoothProfile.STATE_CONNECTING -> BluetoothPeripheralState.Connecting
            BluetoothProfile.STATE_DISCONNECTED -> BluetoothPeripheralState.Disconnected
            BluetoothProfile.STATE_DISCONNECTING -> BluetoothPeripheralState.Disconnecting
            else -> BluetoothPeripheralState.Unknown
        }

    actual fun connect(bluetoothPeripheral: BluetoothPeripheral, autoConnect: Boolean) {
        log?.info("connect")
        bluetoothPeripheral.device.connectGatt(
            context,
            autoConnect,
            mGattClientCallback,
            transportMethod
        )
    }

    actual fun disconnect(bluetoothPeripheral: BluetoothPeripheral) {
        log?.info("disconnect ${bluetoothPeripheral.device.address}")
        mGattClientCallback.gattsForDevice(bluetoothPeripheral.device).forEach { gatt ->
            gatt.disconnect()
            gatt.close()
            mGattClientCallback.removeGattsForDevice(bluetoothPeripheral.device)
        }
        delegates.forEach { it.didDisconnect(bluetoothPeripheral) }
    }

    actual fun retrievePeripheral(identifier: String): BluetoothPeripheral? {
        return runCatching {
            bluetoothManager.adapter
                ?.getRemoteDevice(identifier)
                ?.let { BluetoothPeripheral(it) }
        }.onFailure { e ->
            log?.error("retrievePeripheral error: ${e.message}")
        }.getOrNull()
    }

    actual fun stopScanning() {
        isScanning = false
        bluetoothManager.adapter?.bluetoothLeScanner?.stopScan(mBluetoothScanCallBack)
    }

    actual fun scan(filters: List<ServiceFilter>) {
        log?.info("BT Scan started $filters")
        isScanning = true

        val formattedFilters: List<ScanFilter> = when {
            filters.isEmpty() -> {
                listOf(ScanFilter.Builder().build())
            }
            else -> {
                filters.map { filter ->
                    val filterBuilder = ScanFilter.Builder()
                    filter.serviceUuids.forEach {
                        filterBuilder.setServiceUuid(it)
                    }
                    filter.serviceData.forEach {
                        filterBuilder.setServiceData(it.key, it.value)
                    }
                    filterBuilder.build()
                }
            }
        }
        val settings = ScanSettings.Builder().build()
        val bluetoothScanner = bluetoothManager.adapter?.bluetoothLeScanner
        bluetoothScanner?.startScan(formattedFilters, settings, mBluetoothScanCallBack)
    }

    actual fun discoverServices(
        bluetoothPeripheral: BluetoothPeripheral,
        serviceUUIDs: List<Uuid>
    ) {
        // cant individually get services.
        mGattClientCallback.gattsForDevice(bluetoothPeripheral.device).forEach { it.discoverServices() }
    }
    actual fun discoverCharacteristics(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothService: BluetoothService,
        characteristicUUIDs: List<Uuid>
    ) {
        if (!bluetoothPeripheral.services.containsKey(bluetoothService.uuid)) {
            mGattClientCallback.gattsForDevice(bluetoothPeripheral.device).forEach { it.discoverServices() }
        }
        // no need to do anything here for android.
    }

    private fun fetchCharacteristic(
        bluetoothCharacteristic: BluetoothCharacteristic,
        gatt: BluetoothGatt
    ): Set<BluetoothCharacteristic> =
        gatt.services
            .filter { bluetoothCharacteristic.service == null || it.uuid == bluetoothCharacteristic.service?.service?.uuid }
            .flatMap { service ->
                service.characteristics.filter {
                    it.uuid == bluetoothCharacteristic.characteristic.uuid
                }.map {
                    val read = (bluetoothCharacteristic.characteristic.properties and BluetoothGattCharacteristic.PROPERTY_READ) == BluetoothGattCharacteristic.PROPERTY_READ
                    val notify = (bluetoothCharacteristic.characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) == BluetoothGattCharacteristic.PROPERTY_NOTIFY
                    val indicate = (bluetoothCharacteristic.characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) == BluetoothGattCharacteristic.PROPERTY_INDICATE

                    log?.info(
                    "${gatt.device.address} characteristic: ${bluetoothCharacteristic.characteristic.uuid} read=${read} notify=$notify indicate=$indicate")

                    BluetoothCharacteristic(it)
                }
            }.toSet()

    actual fun readCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic
    ) {
        mGattClientCallback.gattsForDevice(bluetoothPeripheral.device).forEach { gatt ->
            fetchCharacteristic(bluetoothCharacteristic, gatt)
                .forEach { gatt.readCharacteristic(it.characteristic) }
        }
    }

    private fun setCharacteristicNotification(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        enable: Boolean,
        descriptorValue: ByteArray?
    ) {
        log?.info("${bluetoothPeripheral.uuid} notifyCharacteristic setNotify for ${bluetoothCharacteristic.uuid} notify: $enable $bluetoothCharacteristic")
        mGattClientCallback.gattsForDevice(bluetoothPeripheral.device).forEach { gatt ->
            fetchCharacteristic(bluetoothCharacteristic, gatt).forEach {
                gatt.setCharacteristicNotification(it.characteristic, enable)
                descriptorValue?.let { descriptorValue ->
                    it.characteristic.descriptors.forEach { descriptor ->
                        writeDescriptor(
                            bluetoothPeripheral, descriptor, descriptorValue
                        )
                    }
                }
                val read = (bluetoothCharacteristic.characteristic.properties and BluetoothGattCharacteristic.PROPERTY_READ) == BluetoothGattCharacteristic.PROPERTY_READ
                val notify = (bluetoothCharacteristic.characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) == BluetoothGattCharacteristic.PROPERTY_NOTIFY
                val indicate = (bluetoothCharacteristic.characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) == BluetoothGattCharacteristic.PROPERTY_INDICATE

                log?.info(
                    "characteristic: ${bluetoothCharacteristic.characteristic.uuid} read=${read} notify=$notify indicate=$indicate")
            }
        }
    }

    actual fun writeDescriptor(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristicDescriptor: BluetoothCharacteristicDescriptor,
        value: ByteArray
    ) {
        log?.info("writeDescriptor ${bluetoothCharacteristicDescriptor.uuid} value $value")
        mGattClientCallback.gattsForDevice(bluetoothPeripheral.device).forEach { gatt ->
            bluetoothCharacteristicDescriptor.value = value
            gatt.writeDescriptor(bluetoothCharacteristicDescriptor)
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
        sharedWriteCharacteristic(bluetoothPeripheral, bluetoothCharacteristic, value, writeType)
    }

    actual fun writeCharacteristicWithoutEncoding(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        value: ByteArray,
        writeType: Int?
    ) {
        sharedWriteCharacteristic(bluetoothPeripheral, bluetoothCharacteristic, value, writeType)
    }

    actual fun writeCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        value: ByteArray,
        writeType: Int?
    ) {
        log?.info("writeCharacteristic ${bluetoothCharacteristic.uuid} value: $value")
        sharedWriteCharacteristic(bluetoothPeripheral, bluetoothCharacteristic, value.toString(Charset.defaultCharset()), writeType)
    }

    private fun sharedWriteCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        value: String,
        writeType: Int?
    ) {
        sharedWriteCharacteristic(
            bluetoothPeripheral,
            bluetoothCharacteristic,
            value.encodeToByteArray(),
            writeType
        )
    }

    @OptIn(ExperimentalStdlibApi::class)
    private fun sharedWriteCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        value: ByteArray,
        writeType: Int?
    ) {
        log?.info("[${bluetoothPeripheral.uuid}] Writing value {length = ${value.size}, bytes = 0x${value.toHexString()}} with response $writeType")
        mGattClientCallback.gattsForDevice(bluetoothPeripheral.device).forEach { gatt ->
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
        mGattClientCallback.gattsForDevice(bluetoothPeripheral.device)
            .forEach { it.readDescriptor(bluetoothCharacteristicDescriptor) }
        log?.debug("readDescriptor -> ${bluetoothCharacteristicDescriptor.uuid}")
    }

    actual fun changeMTU(bluetoothPeripheral: BluetoothPeripheral, mtuSize: Int) {
        log?.debug("changeMTU -> ${bluetoothPeripheral.uuid} mtuSize: $mtuSize")
        mGattClientCallback.gattsForDevice(bluetoothPeripheral.device).forEach { gatt ->
            gatt.requestMtu(mtuSize)
        }
    }

    inner class BluetoothScanCallBack : ScanCallback() {

        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            addScanResult(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            results?.forEach { addScanResult(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            log?.error("Failed to scan with code $errorCode")
        }

        private fun addScanResult(result: ScanResult?) {
            log?.debug("addScanResult $result")
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

        internal val gatts: MutableList<BluetoothGatt> = mutableListOf()

        private fun addGatt(gatt: BluetoothGatt) {
            if (gatts.firstOrNull { it.device.address == gatt.device.address } == null) {
                gatts.add(gatt)
            }
        }

        fun removeGatt(gatt: BluetoothGatt) {
            gatts.remove(gatt)
        }

        fun removeGattsForDevice(bluetoothDevice: BluetoothDevice) {
            gatts
                .filter { it.device.address == bluetoothDevice.address }
                .forEach { removeGatt(it) }
        }

        fun gattsForDevice(bluetoothDevice: BluetoothDevice): List<BluetoothGatt> =
            gatts.filter { it.device.address == bluetoothDevice.address }

        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            log?.info("onConnectionStateChange status: $status newState: $newState")
            gatt?.let { bluetoothGatt ->
                bluetoothGatt.device.let {
                    //BluetoothProfile#STATE_DISCONNECTED} or {@link BluetoothProfile#STATE_CONNECTED}
                    if (newState == STATE_CONNECTED) {
                        addGatt(bluetoothGatt)
                        if (autoDiscoverAllServicesAndCharacteristics) {
                            bluetoothGatt.readRemoteRssi()
                            bluetoothGatt.discoverServices()
                        }
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
            log?.info("onServicesDiscovered")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                return
            }
            gatt?.device?.let { bluetoothDevice ->
                gatt.services.let { services ->
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
            log?.info("onMtuChanged$mtu status:$status")
            gatt?.device?.let { bluetoothDevice ->
                val bluetoothPeripheral = BluetoothPeripheral(bluetoothDevice)
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    bluetoothPeripheral.mtuSize = mtu
                }
                delegates.forEach {
                    it.didUpdateMTU(bluetoothPeripheral, status)
                }
            }
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt?, rssi: Int, status: Int) {
            log?.info("onReadRemoteRssi $rssi")
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
            log?.info("onDescriptorRead $descriptor")
            descriptor?.let { forcedDescriptor ->
                gatt?.device?.let { bluetoothDevice ->
                    log?.debug("onDescriptorRead value ${forcedDescriptor.value}")
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
            log?.info("onDescriptorWrite ${descriptor?.uuid}")
            descriptor?.let { forcedDescriptor ->
                gatt?.device?.let { bluetoothDevice ->
                    log?.debug("${gatt.device.address} ${descriptor.uuid} onDescriptorWrite value ${forcedDescriptor.value}")
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
            log?.info("onCharacteristicWrite ${characteristic?.uuid} $gatt")
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
            log?.info("[${gatt?.device?.address}] handleCharacteristicValueChange ${characteristic?.uuid} ${characteristic?.value.contentToString()}")
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

        sharedAdvertisementData[AdvertisementDataRetrievalKeys.IsConnectable] =
            if (isConnectable) 1 else 0

        if (Build.VERSION.SDK_INT >= 33) {

            if (scanRecord.deviceName != null) {
                sharedAdvertisementData[AdvertisementDataRetrievalKeys.LocalName] =
                    scanRecord.deviceName as Any
            }

            if (scanRecord.manufacturerSpecificData != null) {
                sharedAdvertisementData[AdvertisementDataRetrievalKeys.ManufacturerData] =
                    scanRecord.manufacturerSpecificData
            }

            // Map from ParcelUuid to Strings
            if (scanRecord.serviceUuids != null) {
                sharedAdvertisementData[AdvertisementDataRetrievalKeys.ServiceUUIDsKey] =
                    scanRecord.serviceUuids.map { it.toString() }
            }

        } else {
            val advertisementBytes = scanRecord.bytes
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
        }

        return sharedAdvertisementData
    }
}
