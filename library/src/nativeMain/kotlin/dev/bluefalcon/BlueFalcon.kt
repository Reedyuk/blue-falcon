package dev.bluefalcon

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import platform.CoreBluetooth.*
import platform.Foundation.*

actual class BlueFalcon actual constructor(
    private val log: Logger?,
    private val context: ApplicationContext,
    internal val autoDiscoverAllServicesAndCharacteristics: Boolean
) {
    actual val scope = CoroutineScope(Dispatchers.Default)
    actual val delegates: MutableSet<BlueFalconDelegate> = mutableSetOf()

    private val bluetoothPeripheralManager = BluetoothPeripheralManager(log, this)

    actual val managerState: StateFlow<BluetoothManagerState> = bluetoothPeripheralManager.managerState.map {
        when (it) {
            CBManagerStatePoweredOn -> BluetoothManagerState.Ready
            else -> BluetoothManagerState.NotReady
        }
    }.stateIn(scope, SharingStarted.Eagerly, BluetoothManagerState.NotReady)

    private val centralManager: CBCentralManager = CBCentralManager(bluetoothPeripheralManager, null)

    actual var isScanning: Boolean = false
    internal actual val _peripherals = MutableStateFlow<Set<BluetoothPeripheral>>(emptySet())
    actual val peripherals: NativeFlow<Set<BluetoothPeripheral>> = _peripherals.toNativeType(scope)

    actual fun connect(bluetoothPeripheral: BluetoothPeripheral, autoConnect: Boolean) {
        //auto connect is ignored due to not needing it in iOS
        centralManager.connectPeripheral(bluetoothPeripheral.bluetoothDevice, null)
    }

    actual fun disconnect(bluetoothPeripheral: BluetoothPeripheral) {
        log?.info("disconnect ${bluetoothPeripheral.uuid}")
        centralManager.cancelPeripheralConnection(bluetoothPeripheral.bluetoothDevice)
    }

    @Throws(
        BluetoothUnknownException::class,
        BluetoothResettingException::class,
        BluetoothUnsupportedException::class,
        BluetoothPermissionException::class,
        BluetoothNotEnabledException::class
    )
    actual fun scan(filters: ServiceFilter?) {
        isScanning = true
        when (centralManager.state) {
            CBManagerStateUnknown -> throw BluetoothUnknownException("Authorization state: ${centralManager.authorization()}")
            CBManagerStateResetting -> throw BluetoothResettingException()
            CBManagerStateUnsupported -> throw BluetoothUnsupportedException()
            CBManagerStateUnauthorized -> throw BluetoothPermissionException()
            CBManagerStatePoweredOff -> throw BluetoothNotEnabledException()
            CBManagerStatePoweredOn -> {
                if (filters != null) {
                    centralManager.scanForPeripheralsWithServices(
                        filters.serviceUuids,
                        mapOf(CBCentralManagerScanOptionAllowDuplicatesKey to true)
                    )
                } else {
                    centralManager.scanForPeripheralsWithServices(null, mapOf(CBCentralManagerScanOptionAllowDuplicatesKey to true) )
                }
            }
        }
    }

    actual fun stopScanning() {
        isScanning = false
        centralManager.stopScan()
    }

    actual fun discoverServices(
        bluetoothPeripheral: BluetoothPeripheral,
        serviceUUIDs: List<Uuid>
    ) {
        log?.info("discoverServices ${bluetoothPeripheral.uuid} services: $serviceUUIDs")
        bluetoothPeripheral.bluetoothDevice.discoverServices(
            serviceUUIDs.map { CBUUID.UUIDWithString(it.toString()) }
        )
    }
    actual fun discoverCharacteristics(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothService: BluetoothService,
        characteristicUUIDs: List<Uuid>
    ) {
        log?.info("discoverCharacteristics ${bluetoothPeripheral.uuid} services: ${bluetoothService.uuid} chars: $characteristicUUIDs ${bluetoothPeripheral.bluetoothDevice.delegate}")
        bluetoothPeripheral.bluetoothDevice.discoverCharacteristics(
            characteristicUUIDs.map { CBUUID.UUIDWithString(it.toString()) }, bluetoothService.service
        )
    }

    actual fun readCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic
    ) {
        bluetoothPeripheral.bluetoothDevice.readValueForCharacteristic(bluetoothCharacteristic.characteristic)
    }

    actual fun notifyCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        notify: Boolean
    ) {
        bluetoothPeripheralManager.setPeripheralDelegate(bluetoothPeripheral)
        log?.info("notifyCharacteristic setNotify for ${bluetoothCharacteristic.uuid} notify: $notify")
        bluetoothPeripheral.bluetoothDevice.setNotifyValue(notify, bluetoothCharacteristic.characteristic)
    }

    actual fun indicateCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        indicate: Boolean
    ) {
        notifyCharacteristic(bluetoothPeripheral, bluetoothCharacteristic, indicate)
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
        sharedWriteCharacteristic(
            bluetoothPeripheral,
            bluetoothCharacteristic,
            NSString.create(string = value),
            writeType
        )
    }

    actual fun writeCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        value: ByteArray,
        writeType: Int?
    ) {
        sharedWriteCharacteristic(
            bluetoothPeripheral,
            bluetoothCharacteristic,
            NSString.create(string = value.decodeToString()),
            writeType
        )
    }

    actual fun writeCharacteristicWithoutEncoding(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        value: ByteArray,
        writeType: Int?
    ) {
        sharedWriteCharacteristic(
            bluetoothPeripheral,
            bluetoothCharacteristic,
            value.toData(),
            writeType
        )
    }

    private fun sharedWriteCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        value: NSString,
        writeType: Int?
    ) {
        value.dataUsingEncoding(NSUTF8StringEncoding)?.let {
            sharedWriteCharacteristic(
                bluetoothPeripheral,
                bluetoothCharacteristic,
                it,
                writeType
            )
        }
    }

    private fun sharedWriteCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        value: NSData,
        writeType: Int?
    ) {
        log?.info("Writing value $value with response $writeType")
        bluetoothPeripheral.bluetoothDevice.writeValue(
            value,
            bluetoothCharacteristic.characteristic,
            when (writeType) {
                1 -> CBCharacteristicWriteWithoutResponse
                else -> CBCharacteristicWriteWithResponse
            }
        )
    }

    actual fun readDescriptor(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        bluetoothCharacteristicDescriptor: BluetoothCharacteristicDescriptor
    ) {
        bluetoothPeripheral.bluetoothDevice.discoverDescriptorsForCharacteristic(bluetoothCharacteristic.characteristic)
    }

    actual fun writeDescriptor(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristicDescriptor: BluetoothCharacteristicDescriptor,
        value: ByteArray
    ) {
        bluetoothPeripheral.bluetoothDevice.writeValue(data = value.toData(), bluetoothCharacteristicDescriptor)
    }

    actual fun changeMTU(bluetoothPeripheral: BluetoothPeripheral, mtuSize: Int) {
        var mtu = bluetoothPeripheral.bluetoothDevice.maximumWriteValueLengthForType(CBCharacteristicWriteWithResponse)
        log?.debug("Change MTU size called but not needed: ${mtuSize}")
        val btPeripheral = bluetoothPeripheral
        btPeripheral.mtuSize = mtu.toInt()
        delegates.forEach {
            it.didUpdateMTU(btPeripheral)
        }
    }
}