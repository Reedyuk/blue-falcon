package dev.bluefalcon

import AdvertisementDataRetrievalKeys
import com.welie.blessed.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.*

actual class BlueFalcon actual constructor(context: ApplicationContext, private val serviceUUID: String?) {
    actual val delegates: MutableSet<BlueFalconDelegate> = mutableSetOf()
    actual var isScanning: Boolean = false

    actual val scope = CoroutineScope(Dispatchers.Default)
    internal actual val _peripherals = MutableStateFlow<Set<BluetoothPeripheral>>(emptySet())
    actual val peripherals: NativeFlow<Set<BluetoothPeripheral>> = _peripherals.toNativeType(scope)

    private val bluetoothManagerCallback = object: BluetoothCentralManagerCallback() {
        override fun onDiscoveredPeripheral(peripheral: com.welie.blessed.BluetoothPeripheral, scanResult: ScanResult) {
            val device = BluetoothPeripheral(peripheral)

            val sharedAdvertisementData = mapNativeAdvertisementDataToShared(scanResult = scanResult, isConnectable = true)
            _peripherals.tryEmit(_peripherals.value + setOf(device))
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
            device.services.forEach { service ->
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

    actual fun connect(bluetoothPeripheral: BluetoothPeripheral, autoConnect: Boolean) {
        bluetoothManager.connectPeripheral(bluetoothPeripheral.bluetoothDevice, bluetoothPeripheralCallback)
    }

    actual fun disconnect(bluetoothPeripheral: BluetoothPeripheral) {
        bluetoothManager.cancelConnection(bluetoothPeripheral.bluetoothDevice)
    }

    @Throws(
        BluetoothUnknownException::class,
        BluetoothResettingException::class,
        BluetoothUnsupportedException::class,
        BluetoothPermissionException::class,
        BluetoothNotEnabledException::class
    )
    actual fun scan(uuid : String?) {
        isScanning = true
        if(serviceUUID != null) {
            bluetoothManager.scanForPeripheralsWithServices(arrayOf(UUID.fromString(serviceUUID)))
        } else {
            bluetoothManager.scanForPeripherals()
        }
    }

    actual fun stopScanning() {
        isScanning = false
        bluetoothManager.stopScan()
    }

    actual fun readCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic
    ) {
        bluetoothPeripheral.bluetoothDevice.readCharacteristic(bluetoothCharacteristic.characteristic)
    }

    actual fun notifyCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        notify: Boolean
    ) {
        bluetoothPeripheral.bluetoothDevice.setNotify(bluetoothCharacteristic.characteristic, notify)
    }

    actual fun indicateCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        indicate: Boolean
    ) {
        TODO()
    }

    actual fun notifyAndIndicateCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        enable: Boolean
    ) {
        notifyCharacteristic(bluetoothPeripheral, bluetoothCharacteristic, enable)
    }

    actual fun writeCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        value: String,
        writeType: Int?
    ) {
        bluetoothPeripheral.bluetoothDevice.writeCharacteristic(bluetoothCharacteristic.characteristic, value.toByteArray(), writeType.writeType)
    }

    actual fun writeCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        value: ByteArray,
        writeType: Int?
    ) {
        bluetoothPeripheral.bluetoothDevice.writeCharacteristic(bluetoothCharacteristic.characteristic, value, writeType.writeType)
    }

    actual fun writeCharacteristicWithoutEncoding(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        value: ByteArray,
        writeType: Int?
    ){
        bluetoothPeripheral.bluetoothDevice.writeCharacteristic(bluetoothCharacteristic.characteristic, value, writeType.writeType)
    }

    private val Int?.writeType: BluetoothGattCharacteristic.WriteType
        get() = when(this) {
            0 -> BluetoothGattCharacteristic.WriteType.WITH_RESPONSE
            1 -> BluetoothGattCharacteristic.WriteType.WITHOUT_RESPONSE
            else -> BluetoothGattCharacteristic.WriteType.WITHOUT_RESPONSE
        }

    actual fun readDescriptor(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        bluetoothCharacteristicDescriptor: BluetoothCharacteristicDescriptor
    ) {
        TODO()
    }

    actual fun changeMTU(bluetoothPeripheral: BluetoothPeripheral, mtuSize: Int) {
        TODO()
    }

    //Helper
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