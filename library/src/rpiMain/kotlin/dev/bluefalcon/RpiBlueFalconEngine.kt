package dev.bluefalcon

import com.welie.blessed.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class RpiBlueFalconEngine(
    private val log: Logger?,
    private val context: ApplicationContext,
    private val autoDiscoverAllServicesAndCharacteristics: Boolean
) : BlueFalconEngine {
    
    override val delegates: MutableSet<BlueFalconDelegate> = mutableSetOf()
    override var isScanning: Boolean = false

    override val scope = CoroutineScope(Dispatchers.Default)
    override val _peripherals = MutableStateFlow<Set<BluetoothPeripheral>>(emptySet())
    override val peripherals: NativeFlow<Set<BluetoothPeripheral>> = _peripherals.toNativeType(scope)
    override val managerState: StateFlow<BluetoothManagerState> = MutableStateFlow(BluetoothManagerState.Ready)

    private val bluetoothManagerCallback = object: BluetoothCentralManagerCallback() {
        override fun onDiscoveredPeripheral(peripheral: com.welie.blessed.BluetoothPeripheral, scanResult: ScanResult) {
            val device = BluetoothPeripheral(peripheral)

            val sharedAdvertisementData = mapNativeAdvertisementDataToShared(scanResult = scanResult, isConnectable = true)
            _peripherals.tryEmit(_peripherals.value.filter{ it.uuid != device.uuid }.toSet() + setOf(device))
            delegates.forEach { it.didDiscoverDevice(device, sharedAdvertisementData) }
        }
    }
    
    private val bluetoothPeripheralCallback = object: BluetoothPeripheralCallback() {
        override fun onServicesDiscovered(
            peripheral: com.welie.blessed.BluetoothPeripheral,
            services: MutableList<BluetoothGattService>
        ) {
            val device = BluetoothPeripheral(peripheral)
            device._servicesFlow.tryEmit(services.map { BluetoothService(it) })
            delegates.forEach { it.didDiscoverServices(device) }
        }

        override fun onCharacteristicUpdate(
            peripheral: com.welie.blessed.BluetoothPeripheral,
            value: ByteArray,
            characteristic: BluetoothGattCharacteristic,
            status: BluetoothCommandStatus
        ) {
            val device = BluetoothPeripheral(peripheral)
            device.services.values.forEach { service ->
                service.characteristics
                    .filter { it.name == characteristic.uuid.toString() }
                    .forEach { it.mutableVal = value }
            }
            delegates.forEach { it.didDiscoverCharacteristics(device) }
        }

        override fun onCharacteristicWrite(
            peripheral: com.welie.blessed.BluetoothPeripheral,
            value: ByteArray,
            characteristic: BluetoothGattCharacteristic,
            status: BluetoothCommandStatus
        ) {
            val device = BluetoothPeripheral(peripheral)
            val bluetoothCharacteristic = BluetoothCharacteristic(characteristic)
            delegates.forEach {
                it.didCharacteristcValueChanged(device,bluetoothCharacteristic)
            }
        }
    }
    
    private val bluetoothManager = BluetoothCentralManager(bluetoothManagerCallback)

    override fun connectionState(bluetoothPeripheral: BluetoothPeripheral): BluetoothPeripheralState =
        when (bluetoothPeripheral.device.state) {
            ConnectionState.CONNECTED -> BluetoothPeripheralState.Connected
            ConnectionState.CONNECTING -> BluetoothPeripheralState.Connecting
            ConnectionState.DISCONNECTED -> BluetoothPeripheralState.Disconnected
            ConnectionState.DISCONNECTING -> BluetoothPeripheralState.Disconnecting
            else -> BluetoothPeripheralState.Unknown
        }

    override fun connect(bluetoothPeripheral: BluetoothPeripheral, autoConnect: Boolean) {
        bluetoothManager.connectPeripheral(bluetoothPeripheral.device, bluetoothPeripheralCallback)
    }

    override fun disconnect(bluetoothPeripheral: BluetoothPeripheral) {
        bluetoothManager.cancelConnection(bluetoothPeripheral.device)
    }

    override fun retrievePeripheral(identifier: String): BluetoothPeripheral? {
        return _peripherals.value.firstOrNull { it.uuid == identifier }
    }

    override fun requestConnectionPriority(bluetoothPeripheral: BluetoothPeripheral, connectionPriority: ConnectionPriority) {
        // Not implemented for RPI
    }

    @Throws(
        BluetoothUnknownException::class,
        BluetoothResettingException::class,
        BluetoothUnsupportedException::class,
        BluetoothPermissionException::class,
        BluetoothNotEnabledException::class
    )
    override fun scan(filters: List<ServiceFilter>) {
        isScanning = true
        if(filters.isNotEmpty()) {
            val serviceUuids = filters.flatMap { it.serviceUuids }
            bluetoothManager.scanForPeripheralsWithServices(
                serviceUuids.toTypedArray()
            )
        } else {
            bluetoothManager.scanForPeripherals()
        }
    }

    override fun stopScanning() {
        isScanning = false
        bluetoothManager.stopScan()
    }

    override fun clearPeripherals() {
        _peripherals.value = emptySet()
    }

    override fun discoverServices(
        bluetoothPeripheral: BluetoothPeripheral,
        serviceUUIDs: List<Uuid>
    ) {
    }
    
    override fun discoverCharacteristics(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothService: BluetoothService,
        characteristicUUIDs: List<Uuid>
    ) {
        // no need to do anything.
    }

    override fun readCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic
    ) {
        bluetoothPeripheral.device.readCharacteristic(bluetoothCharacteristic.characteristic)
    }

    override fun notifyCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        notify: Boolean
    ) {
        bluetoothPeripheral.device.setNotify(bluetoothCharacteristic.characteristic, notify)
    }

    override fun indicateCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        indicate: Boolean
    ) {
        TODO()
    }

    override fun notifyAndIndicateCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        enable: Boolean
    ) {
        notifyCharacteristic(bluetoothPeripheral, bluetoothCharacteristic, enable)
    }

    override fun writeCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        value: String,
        writeType: Int?
    ) {
        bluetoothPeripheral.device.writeCharacteristic(bluetoothCharacteristic.characteristic, value.toByteArray(), writeType.writeType)
    }

    override fun writeCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        value: ByteArray,
        writeType: Int?
    ) {
        bluetoothPeripheral.device.writeCharacteristic(bluetoothCharacteristic.characteristic, value, writeType.writeType)
    }

    override fun writeCharacteristicWithoutEncoding(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        value: ByteArray,
        writeType: Int?
    ){
        bluetoothPeripheral.device.writeCharacteristic(bluetoothCharacteristic.characteristic, value, writeType.writeType)
    }

    private val Int?.writeType: BluetoothGattCharacteristic.WriteType
        get() = when(this) {
            0 -> BluetoothGattCharacteristic.WriteType.WITH_RESPONSE
            1 -> BluetoothGattCharacteristic.WriteType.WITHOUT_RESPONSE
            else -> BluetoothGattCharacteristic.WriteType.WITHOUT_RESPONSE
        }

    override fun readDescriptor(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        bluetoothCharacteristicDescriptor: BluetoothCharacteristicDescriptor
    ) {
        TODO()
    }

    override fun writeDescriptor(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristicDescriptor: BluetoothCharacteristicDescriptor,
        value: ByteArray
    ) {
        TODO()
    }

    override fun changeMTU(bluetoothPeripheral: BluetoothPeripheral, mtuSize: Int) {
        TODO()
    }

    fun mapNativeAdvertisementDataToShared(
        scanResult: ScanResult,
        isConnectable: Boolean
    ): Map<AdvertisementDataRetrievalKeys, Any> {
        val sharedAdvertisementData = mutableMapOf<AdvertisementDataRetrievalKeys, Any>()

        sharedAdvertisementData[AdvertisementDataRetrievalKeys.IsConnectable] =
            if (isConnectable) 1 else 0

        sharedAdvertisementData[AdvertisementDataRetrievalKeys.LocalName] = scanResult.name ?: ""
        sharedAdvertisementData[AdvertisementDataRetrievalKeys.ManufacturerData] = scanResult.manufacturerData

        val kotlinUUIDStrings = mutableListOf<String>()
        for (serviceUUID in scanResult.uuids) {
            val kotlinUUIDString = serviceUUID.toString()

            kotlinUUIDStrings.add(kotlinUUIDString)
        }
        sharedAdvertisementData[AdvertisementDataRetrievalKeys.ServiceUUIDsKey] = kotlinUUIDStrings

        return sharedAdvertisementData
    }
}
