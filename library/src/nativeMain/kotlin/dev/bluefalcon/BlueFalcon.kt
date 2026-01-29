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

    actual fun requestConnectionPriority(
        bluetoothPeripheral: BluetoothPeripheral,
        connectionPriority: ConnectionPriority
    ) { }

    actual fun connectionState(bluetoothPeripheral: BluetoothPeripheral): BluetoothPeripheralState =
        when (bluetoothPeripheral.device.state) {
            CBPeripheralStateConnected -> BluetoothPeripheralState.Connected
            CBPeripheralStateConnecting -> BluetoothPeripheralState.Connecting
            CBPeripheralStateDisconnected -> BluetoothPeripheralState.Disconnected
            CBPeripheralStateDisconnecting -> BluetoothPeripheralState.Disconnecting
            else -> BluetoothPeripheralState.Unknown
        }

    actual fun connect(bluetoothPeripheral: BluetoothPeripheral, autoConnect: Boolean) {
        //auto connect is ignored due to not needing it in iOS
        log?.debug("connect ${bluetoothPeripheral.uuid} state: ${bluetoothPeripheral.device.state}")
        if (bluetoothPeripheral.device.state == CBPeripheralStateConnected) {
            // IF you decide to pass in a BluetoothPeripheral that was generated from another CBManager, then we need to retrieve and regenerate it using our CBManager.
            val replacementDevice = centralManager.retrievePeripheralsWithIdentifiers(
                listOf(bluetoothPeripheral.device.identifier)
            ).firstOrNull() as? CBPeripheral
            if (replacementDevice != null) {
                if (replacementDevice.state == CBPeripheralStateDisconnected || replacementDevice.state == CBPeripheralStateDisconnecting) {
                    log?.debug("connect: Device is disconnected, connecting to replacement device")
                    centralManager.connectPeripheral(replacementDevice, null)
                } else {
                    log?.debug("connect: Replacement device is connected, using it")
                    bluetoothPeripheralManager.centralManager(centralManager, didConnectPeripheral = replacementDevice)
                }
            } else {
                log?.debug("connect: Device is connected, using it")
                bluetoothPeripheralManager.centralManager(centralManager, didConnectPeripheral = bluetoothPeripheral.device)
            }
        } else {
            centralManager.connectPeripheral(bluetoothPeripheral.device, null)
        }
    }

    actual fun disconnect(bluetoothPeripheral: BluetoothPeripheral) {
        log?.debug("disconnect ${bluetoothPeripheral.uuid}")
        centralManager.cancelPeripheralConnection(bluetoothPeripheral.device)
    }

    actual fun retrievePeripheral(identifier: String): BluetoothPeripheral? {
        return runCatching {
            centralManager
                .retrievePeripheralsWithIdentifiers(listOf(NSUUID(identifier)))
                .filterIsInstance<CBPeripheral>()
                .firstOrNull()
                ?.let { BluetoothPeripheralImpl(it, it.RSSI?.floatValue) }
        }.onFailure { e ->
            log?.error("retrievePeripheral error: ${e.message}")
        }.getOrNull()
    }

    @Throws(
        BluetoothUnknownException::class,
        BluetoothResettingException::class,
        BluetoothUnsupportedException::class,
        BluetoothPermissionException::class,
        BluetoothNotEnabledException::class
    )
    actual fun scan(filters: List<ServiceFilter>) {
        log?.info("Scan started with filters: $filters")
        isScanning = true
        when (centralManager.state) {
            CBManagerStateUnknown -> throw BluetoothUnknownException("Authorization state: ${centralManager.authorization()}")
            CBManagerStateResetting -> throw BluetoothResettingException()
            CBManagerStateUnsupported -> throw BluetoothUnsupportedException()
            CBManagerStateUnauthorized -> throw BluetoothPermissionException()
            CBManagerStatePoweredOff -> throw BluetoothNotEnabledException()
            CBManagerStatePoweredOn -> {
                when {
                    filters.isEmpty() -> {
                        centralManager.scanForPeripheralsWithServices(null, mapOf(CBCentralManagerScanOptionAllowDuplicatesKey to true) )
                    }
                    else -> {
                        centralManager.scanForPeripheralsWithServices(
                            filters.flatMap { it.serviceUuids },
                            mapOf(CBCentralManagerScanOptionAllowDuplicatesKey to true)
                        )
                    }
                }
            }
        }
    }

    actual fun stopScanning() {
        log?.info("Scan stopped")
        isScanning = false
        centralManager.stopScan()
    }

    actual fun clearPeripherals() {
        _peripherals.value = emptySet()
    }

    actual fun discoverServices(
        bluetoothPeripheral: BluetoothPeripheral,
        serviceUUIDs: List<Uuid>
    ) {
        log?.debug("discoverServices ${bluetoothPeripheral.uuid} services: $serviceUUIDs")
        bluetoothPeripheral.device.discoverServices(
            serviceUUIDs.map { CBUUID.UUIDWithString(it.toString()) }
        )
    }
    actual fun discoverCharacteristics(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothService: BluetoothService,
        characteristicUUIDs: List<Uuid>
    ) {
        log?.debug("discoverCharacteristics ${bluetoothPeripheral.uuid} service: ${bluetoothService.uuid} chars: $characteristicUUIDs")
        bluetoothPeripheral.device.discoverCharacteristics(
            characteristicUUIDs.map { CBUUID.UUIDWithString(it.toString()) }, bluetoothService.service
        )
    }

    actual fun readCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic
    ) {
        bluetoothPeripheral.device.readValueForCharacteristic(bluetoothCharacteristic.characteristic)
    }

    actual fun notifyCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        notify: Boolean
    ) {
        bluetoothPeripheralManager.setPeripheralDelegate(bluetoothPeripheral)
        log?.debug("notifyCharacteristic ${bluetoothCharacteristic.uuid} notify: $notify")
        bluetoothPeripheral.device.setNotifyValue(notify, bluetoothCharacteristic.characteristic)
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
        log?.debug("Writing value (${value.length} bytes) with writeType: $writeType")
        bluetoothPeripheral.device.writeValue(
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
        bluetoothPeripheral.device.discoverDescriptorsForCharacteristic(bluetoothCharacteristic.characteristic)
    }

    actual fun writeDescriptor(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristicDescriptor: BluetoothCharacteristicDescriptor,
        value: ByteArray
    ) {
        bluetoothPeripheral.device.writeValue(data = value.toData(), bluetoothCharacteristicDescriptor)
    }

    actual fun changeMTU(bluetoothPeripheral: BluetoothPeripheral, mtuSize: Int) {
        val mtu = bluetoothPeripheral.device.maximumWriteValueLengthForType(CBCharacteristicWriteWithResponse)
        log?.debug("Change MTU size called but not needed for darwin platforms: $mtuSize:$mtu")
        bluetoothPeripheral.mtuSize = mtu.toInt()
        delegates.forEach {
            it.didUpdateMTU(bluetoothPeripheral, 1)
        }
    }
}