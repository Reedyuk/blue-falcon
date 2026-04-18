package dev.bluefalcon.engine.apple

import dev.bluefalcon.core.*
import kotlinx.cinterop.BetaInteropApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.CoreBluetooth.*
import platform.Foundation.*

/**
 * Shared Apple implementation of BlueFalconEngine for iOS and macOS
 * Uses CoreBluetooth framework
 */
@OptIn(BetaInteropApi::class)
class AppleEngine : BlueFalconEngine, CBCentralManagerCallback, CBPeripheralCallback {
    
    override val scope = CoroutineScope(Dispatchers.Default)
    
    private val _peripherals = MutableStateFlow<Set<BluetoothPeripheral>>(emptySet())
    override val peripherals: StateFlow<Set<BluetoothPeripheral>> = _peripherals.asStateFlow()
    
    private val _managerState = MutableStateFlow(BluetoothManagerState.NotReady)
    override val managerState: StateFlow<BluetoothManagerState> = _managerState.asStateFlow()

    private val _characteristicNotifications = MutableSharedFlow<CharacteristicNotification>(extraBufferCapacity = 64)
    override val characteristicNotifications: SharedFlow<CharacteristicNotification> = _characteristicNotifications
    
    override var isScanning: Boolean = false
        private set
    
    // CoreBluetooth manager
    private val bluetoothManager = BluetoothPeripheralManager(this)
    private val centralManager: CBCentralManager
        get() = bluetoothManager.centralManager
    
    // Peripheral delegate for handling peripheral events
    private val peripheralDelegate = CBPeripheralDelegateWrapper(this)
    
    // Map to track connected peripherals
    private val connectedPeripherals = mutableMapOf<String, AppleBluetoothPeripheral>()
    
    override suspend fun scan(filters: List<ServiceFilter>) {
        isScanning = true
        
        when (centralManager.state) {
            CBManagerStateUnknown -> throw BluetoothUnknownException("Authorization state: ${centralManager.authorization()}")
            CBManagerStateResetting -> throw BluetoothResettingException()
            CBManagerStateUnsupported -> throw BluetoothUnsupportedException()
            CBManagerStateUnauthorized -> throw BluetoothPermissionException()
            CBManagerStatePoweredOff -> throw BluetoothNotEnabledException()
            CBManagerStatePoweredOn -> {
                val serviceUUIDs = if (filters.isEmpty()) {
                    null
                } else {
                    filters.map { CBUUID.UUIDWithString(it.uuid.toString()) }
                }
                
                centralManager.scanForPeripheralsWithServices(
                    serviceUUIDs,
                    mapOf(CBCentralManagerScanOptionAllowDuplicatesKey to true)
                )
            }
        }
    }
    
    override suspend fun stopScanning() {
        isScanning = false
        centralManager.stopScan()
    }
    
    override fun clearPeripherals() {
        _peripherals.value = emptySet()
    }
    
    override suspend fun connect(peripheral: BluetoothPeripheral, autoConnect: Boolean) {
        val applePeripheral = peripheral as? AppleBluetoothPeripheral
            ?: throw IllegalArgumentException("Peripheral must be an AppleBluetoothPeripheral")
        
        val cbPeripheral = applePeripheral.cbPeripheral
        
        // If already connected, trigger connection callback
        if (cbPeripheral.state == CBPeripheralStateConnected) {
            val replacementDevice = centralManager.retrievePeripheralsWithIdentifiers(
                listOf(cbPeripheral.identifier)
            ).firstOrNull() as? CBPeripheral
            
            if (replacementDevice != null) {
                if (replacementDevice.state == CBPeripheralStateDisconnected || 
                    replacementDevice.state == CBPeripheralStateDisconnecting) {
                    centralManager.connectPeripheral(replacementDevice, null)
                } else {
                    onPeripheralConnected(replacementDevice)
                }
            } else {
                onPeripheralConnected(cbPeripheral)
            }
        } else {
            centralManager.connectPeripheral(cbPeripheral, null)
        }
    }
    
    override suspend fun disconnect(peripheral: BluetoothPeripheral) {
        val applePeripheral = peripheral as? AppleBluetoothPeripheral
            ?: throw IllegalArgumentException("Peripheral must be an AppleBluetoothPeripheral")
        
        centralManager.cancelPeripheralConnection(applePeripheral.cbPeripheral)
        connectedPeripherals.remove(peripheral.uuid)
    }
    
    override fun connectionState(peripheral: BluetoothPeripheral): BluetoothPeripheralState {
        val applePeripheral = peripheral as? AppleBluetoothPeripheral
            ?: return BluetoothPeripheralState.Unknown
        
        return when (applePeripheral.cbPeripheral.state) {
            CBPeripheralStateConnected -> BluetoothPeripheralState.Connected
            CBPeripheralStateConnecting -> BluetoothPeripheralState.Connecting
            CBPeripheralStateDisconnected -> BluetoothPeripheralState.Disconnected
            CBPeripheralStateDisconnecting -> BluetoothPeripheralState.Disconnecting
            else -> BluetoothPeripheralState.Unknown
        }
    }
    
    override fun retrievePeripheral(identifier: String): BluetoothPeripheral? {
        return runCatching {
            centralManager
                .retrievePeripheralsWithIdentifiers(listOf(NSUUID(identifier)))
                .filterIsInstance<CBPeripheral>()
                .firstOrNull()
                ?.let { AppleBluetoothPeripheral(it, null) }
        }.getOrNull()
    }
    
    override fun requestConnectionPriority(peripheral: BluetoothPeripheral, priority: ConnectionPriority) {
        // No-op on Apple platforms
    }
    
    override suspend fun discoverServices(peripheral: BluetoothPeripheral, serviceUUIDs: List<Uuid>) {
        val applePeripheral = peripheral as? AppleBluetoothPeripheral
            ?: throw IllegalArgumentException("Peripheral must be an AppleBluetoothPeripheral")
        
        val uuids = if (serviceUUIDs.isEmpty()) {
            null
        } else {
            serviceUUIDs.map { CBUUID.UUIDWithString(it.toString()) }
        }
        
        applePeripheral.cbPeripheral.discoverServices(uuids)
    }
    
    override suspend fun discoverCharacteristics(
        peripheral: BluetoothPeripheral,
        service: BluetoothService,
        characteristicUUIDs: List<Uuid>
    ) {
        val applePeripheral = peripheral as? AppleBluetoothPeripheral
            ?: throw IllegalArgumentException("Peripheral must be an AppleBluetoothPeripheral")
        
        val appleService = service as? AppleBluetoothService
            ?: throw IllegalArgumentException("Service must be an AppleBluetoothService")
        
        val uuids = if (characteristicUUIDs.isEmpty()) {
            null
        } else {
            characteristicUUIDs.map { CBUUID.UUIDWithString(it.toString()) }
        }
        
        applePeripheral.cbPeripheral.discoverCharacteristics(uuids, appleService.cbService)
    }
    
    override suspend fun readCharacteristic(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic
    ) {
        val applePeripheral = peripheral as? AppleBluetoothPeripheral
            ?: throw IllegalArgumentException("Peripheral must be an AppleBluetoothPeripheral")
        
        val appleCharacteristic = characteristic as? AppleBluetoothCharacteristic
            ?: throw IllegalArgumentException("Characteristic must be an AppleBluetoothCharacteristic")
        
        applePeripheral.cbPeripheral.readValueForCharacteristic(appleCharacteristic.cbCharacteristic)
    }
    
    override suspend fun writeCharacteristic(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        value: String,
        writeType: Int?
    ) {
        val data = NSString.create(string = value).dataUsingEncoding(NSUTF8StringEncoding)
            ?: throw IllegalArgumentException("Failed to encode string to data")
        
        writeCharacteristicData(peripheral, characteristic, data, writeType)
    }
    
    override suspend fun writeCharacteristic(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        value: ByteArray,
        writeType: Int?
    ) {
        val data = value.toData()
        writeCharacteristicData(peripheral, characteristic, data, writeType)
    }
    
    private fun writeCharacteristicData(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        data: NSData,
        writeType: Int?
    ) {
        val applePeripheral = peripheral as? AppleBluetoothPeripheral
            ?: throw IllegalArgumentException("Peripheral must be an AppleBluetoothPeripheral")
        
        val appleCharacteristic = characteristic as? AppleBluetoothCharacteristic
            ?: throw IllegalArgumentException("Characteristic must be an AppleBluetoothCharacteristic")
        
        val cbWriteType = when (writeType) {
            1 -> CBCharacteristicWriteWithoutResponse
            else -> CBCharacteristicWriteWithResponse
        }
        
        applePeripheral.cbPeripheral.writeValue(
            data,
            appleCharacteristic.cbCharacteristic,
            cbWriteType
        )
    }
    
    override suspend fun notifyCharacteristic(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        notify: Boolean
    ) {
        val applePeripheral = peripheral as? AppleBluetoothPeripheral
            ?: throw IllegalArgumentException("Peripheral must be an AppleBluetoothPeripheral")
        
        val appleCharacteristic = characteristic as? AppleBluetoothCharacteristic
            ?: throw IllegalArgumentException("Characteristic must be an AppleBluetoothCharacteristic")
        
        applePeripheral.cbPeripheral.delegate = peripheralDelegate
        applePeripheral.cbPeripheral.setNotifyValue(notify, appleCharacteristic.cbCharacteristic)
    }
    
    override suspend fun indicateCharacteristic(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        indicate: Boolean
    ) {
        // On Apple platforms, notifications and indications use the same API
        notifyCharacteristic(peripheral, characteristic, indicate)
    }
    
    override suspend fun readDescriptor(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        descriptor: BluetoothCharacteristicDescriptor
    ) {
        val applePeripheral = peripheral as? AppleBluetoothPeripheral
            ?: throw IllegalArgumentException("Peripheral must be an AppleBluetoothPeripheral")
        
        val appleCharacteristic = characteristic as? AppleBluetoothCharacteristic
            ?: throw IllegalArgumentException("Characteristic must be an AppleBluetoothCharacteristic")
        
        // Discover descriptors first
        applePeripheral.cbPeripheral.discoverDescriptorsForCharacteristic(appleCharacteristic.cbCharacteristic)
    }
    
    override suspend fun writeDescriptor(
        peripheral: BluetoothPeripheral,
        descriptor: BluetoothCharacteristicDescriptor,
        value: ByteArray
    ) {
        val applePeripheral = peripheral as? AppleBluetoothPeripheral
            ?: throw IllegalArgumentException("Peripheral must be an AppleBluetoothPeripheral")
        
        val appleDescriptor = descriptor as? AppleBluetoothCharacteristicDescriptor
            ?: throw IllegalArgumentException("Descriptor must be an AppleBluetoothCharacteristicDescriptor")
        
        applePeripheral.cbPeripheral.writeValue(
            data = value.toData(),
            forDescriptor = appleDescriptor.cbDescriptor
        )
    }
    
    override suspend fun changeMTU(peripheral: BluetoothPeripheral, mtuSize: Int) {
        val applePeripheral = peripheral as? AppleBluetoothPeripheral
            ?: throw IllegalArgumentException("Peripheral must be an AppleBluetoothPeripheral")
        
        // Get the actual MTU size from the peripheral
        val actualMtu = applePeripheral.cbPeripheral.maximumWriteValueLengthForType(
            CBCharacteristicWriteWithResponse
        ).toInt()
        
        applePeripheral.mtuSize = actualMtu
    }
    
    override fun refreshGattCache(peripheral: BluetoothPeripheral): Boolean {
        // Not supported on Apple platforms
        return false
    }
    
    override suspend fun openL2capChannel(peripheral: BluetoothPeripheral, psm: Int) {
        val applePeripheral = peripheral as? AppleBluetoothPeripheral
            ?: throw IllegalArgumentException("Peripheral must be an AppleBluetoothPeripheral")
        
        applePeripheral.cbPeripheral.delegate = peripheralDelegate
        applePeripheral.cbPeripheral.openL2CAPChannel(psm.toUShort())
    }
    
    override suspend fun createBond(peripheral: BluetoothPeripheral) {
        // Not required on Apple platforms - bonding is handled automatically
    }
    
    override suspend fun removeBond(peripheral: BluetoothPeripheral) {
        // Not supported on Apple platforms - must be done through system settings
    }
    
    // CBCentralManagerCallback implementation
    
    override fun onStateUpdated(state: CBManagerState) {
        _managerState.value = when (state) {
            CBManagerStatePoweredOn -> BluetoothManagerState.Ready
            else -> BluetoothManagerState.NotReady
        }
    }
    
    override fun onPeripheralDiscovered(
        peripheral: CBPeripheral,
        advertisementData: Map<Any?, *>,
        rssi: NSNumber
    ) {
        if (isScanning) {
            val device = AppleBluetoothPeripheral(peripheral, rssi.floatValue)
            _peripherals.value = _peripherals.value + device
        }
    }
    
    override fun onPeripheralConnected(peripheral: CBPeripheral) {
        peripheral.delegate = peripheralDelegate
        val device = AppleBluetoothPeripheral(peripheral, null)
        connectedPeripherals[device.uuid] = device
    }
    
    override fun onPeripheralDisconnected(peripheral: CBPeripheral, error: NSError?) {
        val uuid = peripheral.identifier.UUIDString
        connectedPeripherals.remove(uuid)
        peripheral.delegate = null
    }
    
    override fun onPeripheralConnectionFailed(peripheral: CBPeripheral, error: NSError?) {
        // Connection failed - could expose this through a callback if needed
    }
    
    // CBPeripheralCallback implementation
    
    override fun onServicesDiscovered(peripheral: CBPeripheral, error: NSError?) {
        // Services discovered - automatically handled through peripheral.services property
    }
    
    override fun onCharacteristicsDiscovered(peripheral: CBPeripheral, service: CBService, error: NSError?) {
        // Characteristics discovered - automatically handled through service.characteristics property
        
        // Discover descriptors for all characteristics
        service.characteristics?.mapNotNull { it as? CBCharacteristic }?.forEach { characteristic ->
            peripheral.discoverDescriptorsForCharacteristic(characteristic)
        }
    }
    
    override fun onCharacteristicValueUpdated(
        peripheral: CBPeripheral,
        characteristic: CBCharacteristic,
        error: NSError?
    ) {
        val bluetoothPeripheral =
            connectedPeripherals[peripheral.identifier.UUIDString] ?: AppleBluetoothPeripheral(peripheral, null)
        val bluetoothCharacteristic = AppleBluetoothCharacteristic(
            cbCharacteristic = characteristic,
            service = characteristic.service?.let { AppleBluetoothService(it) }
        )
        val value = bluetoothCharacteristic.value ?: return
        bluetoothCharacteristic.emitNotification(value)
        _characteristicNotifications.tryEmit(
            CharacteristicNotification(
                peripheral = bluetoothPeripheral,
                characteristic = bluetoothCharacteristic,
                value = value
            )
        )
    }
    
    override fun onCharacteristicWritten(
        peripheral: CBPeripheral,
        characteristic: CBCharacteristic,
        error: NSError?
    ) {
        // Characteristic written - could expose this through a callback if needed
    }
    
    override fun onDescriptorsDiscovered(
        peripheral: CBPeripheral,
        characteristic: CBCharacteristic,
        error: NSError?
    ) {
        // Descriptors discovered - automatically handled through characteristic.descriptors property
    }
    
    override fun onNotificationStateUpdated(
        peripheral: CBPeripheral,
        characteristic: CBCharacteristic,
        error: NSError?
    ) {
        // Notification state updated - automatically handled through characteristic.isNotifying property
    }
    
    override fun onL2CAPChannelOpened(peripheral: CBPeripheral, channel: CBL2CAPChannel?, error: NSError?) {
        // L2CAP channel opened - could expose this through a callback if needed
    }
    
    override fun onDescriptorWritten(peripheral: CBPeripheral, descriptor: CBDescriptor, error: NSError?) {
        // Descriptor written - could expose this through a callback if needed
    }
}
