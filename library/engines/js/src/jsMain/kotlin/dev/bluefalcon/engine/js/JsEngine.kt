package dev.bluefalcon.engine.js

import dev.bluefalcon.core.*
import dev.bluefalcon.engine.js.external.*
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.w3c.dom.Navigator
import kotlin.js.Promise

/**
 * JavaScript/Web Bluetooth implementation of BlueFalconEngine
 */
class JsEngine : BlueFalconEngine {
    override val scope = CoroutineScope(Dispatchers.Default)
    
    private val _peripherals = MutableStateFlow<Set<BluetoothPeripheral>>(emptySet())
    override val peripherals: StateFlow<Set<BluetoothPeripheral>> = _peripherals.asStateFlow()
    
    private val _managerState = MutableStateFlow(BluetoothManagerState.Ready)
    override val managerState: StateFlow<BluetoothManagerState> = _managerState.asStateFlow()

    private val _characteristicNotifications = MutableSharedFlow<CharacteristicNotification>(extraBufferCapacity = 64)
    override val characteristicNotifications: SharedFlow<CharacteristicNotification> = _characteristicNotifications
    
    override var isScanning: Boolean = false
        private set
    
    private val peripheralMap = mutableMapOf<String, JsBluetoothPeripheral>()
    
    private inline val Navigator.bluetooth: Bluetooth get() = asDynamic().bluetooth as Bluetooth
    
    override suspend fun scan(filters: List<ServiceFilter>) {
        isScanning = true
        
        val serviceUuids = filters.map { it.uuid.toString() }.toTypedArray()
        
        window.navigator.bluetooth.requestDevice(
            BluetoothOptions(
                acceptAllDevices = filters.isEmpty(),
                filters = if (filters.isNotEmpty()) {
                    arrayOf(BluetoothOptions.Filter.Services(serviceUuids))
                } else {
                    null
                },
                optionalServices = emptyArray()
            )
        ).then { bluetoothDevice ->
            val peripheral = peripheralMap.getOrPut(bluetoothDevice.id) {
                JsBluetoothPeripheral(bluetoothDevice)
            }
            
            _peripherals.value = _peripherals.value + peripheral
            isScanning = false
        }.catch {
            isScanning = false
        }
    }
    
    override suspend fun stopScanning() {
        isScanning = false
        // Web Bluetooth API doesn't support continuous scanning
    }
    
    override fun clearPeripherals() {
        _peripherals.value = emptySet()
        peripheralMap.clear()
    }
    
    override suspend fun connect(peripheral: BluetoothPeripheral, autoConnect: Boolean) {
        val jsPeripheral = peripheral as? JsBluetoothPeripheral
            ?: throw IllegalArgumentException("Peripheral must be a JsBluetoothPeripheral")
        
        if (jsPeripheral.device.gatt?.connected == true) {
            return
        }
        
        jsPeripheral.device.gatt?.connect()?.then { gatt ->
            // Connection successful
        }
    }
    
    override suspend fun disconnect(peripheral: BluetoothPeripheral) {
        val jsPeripheral = peripheral as? JsBluetoothPeripheral
            ?: throw IllegalArgumentException("Peripheral must be a JsBluetoothPeripheral")
        
        jsPeripheral.device.gatt?.disconnect()
    }
    
    override fun connectionState(peripheral: BluetoothPeripheral): BluetoothPeripheralState {
        val jsPeripheral = peripheral as? JsBluetoothPeripheral
            ?: return BluetoothPeripheralState.Unknown
        
        return if (jsPeripheral.device.gatt?.connected == true) {
            BluetoothPeripheralState.Connected
        } else {
            BluetoothPeripheralState.Disconnected
        }
    }
    
    override fun retrievePeripheral(identifier: String): BluetoothPeripheral? {
        // Web Bluetooth API doesn't support retrieving previously paired devices
        return peripheralMap[identifier]
    }
    
    override fun requestConnectionPriority(peripheral: BluetoothPeripheral, priority: ConnectionPriority) {
        // Not supported in Web Bluetooth API
    }
    
    override suspend fun discoverServices(peripheral: BluetoothPeripheral, serviceUUIDs: List<Uuid>) {
        val jsPeripheral = peripheral as? JsBluetoothPeripheral
            ?: throw IllegalArgumentException("Peripheral must be a JsBluetoothPeripheral")
        
        val serviceUuid = if (serviceUUIDs.isNotEmpty()) {
            serviceUUIDs.joinToString(",") { it.toString() }
        } else {
            null
        }
        
        jsPeripheral.device.gatt?.getPrimaryServices(serviceUuid)?.then { services ->
            val jsServices = services.map { JsBluetoothService(it) }
            jsPeripheral.updateServices(jsServices)
        }
    }
    
    override suspend fun discoverCharacteristics(
        peripheral: BluetoothPeripheral,
        service: BluetoothService,
        characteristicUUIDs: List<Uuid>
    ) {
        val jsService = service as? JsBluetoothService
            ?: throw IllegalArgumentException("Service must be a JsBluetoothService")
        
        val characteristicUuid = if (characteristicUUIDs.isNotEmpty()) {
            characteristicUUIDs.joinToString(",") { it.toString() }
        } else {
            null
        }
        
        jsService.service.getCharacteristics(characteristicUuid).then { characteristics ->
            val jsCharacteristics = characteristics.map { JsBluetoothCharacteristic(it, jsService) }
            jsService.updateCharacteristics(jsCharacteristics)
        }
    }
    
    override suspend fun readCharacteristic(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic
    ) {
        val jsCharacteristic = characteristic as? JsBluetoothCharacteristic
            ?: throw IllegalArgumentException("Characteristic must be a JsBluetoothCharacteristic")
        
        jsCharacteristic.characteristic.readValue()
    }
    
    override suspend fun writeCharacteristic(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        value: String,
        writeType: Int?
    ) {
        writeCharacteristic(peripheral, characteristic, value.encodeToByteArray(), writeType)
    }
    
    override suspend fun writeCharacteristic(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        value: ByteArray,
        writeType: Int?
    ) {
        val jsCharacteristic = characteristic as? JsBluetoothCharacteristic
            ?: throw IllegalArgumentException("Characteristic must be a JsBluetoothCharacteristic")
        
        jsCharacteristic.characteristic.writeValue(value)
    }
    
    override suspend fun notifyCharacteristic(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        notify: Boolean
    ) {
        val jsCharacteristic = characteristic as? JsBluetoothCharacteristic
            ?: throw IllegalArgumentException("Characteristic must be a JsBluetoothCharacteristic")
        
        if (notify) {
            jsCharacteristic.characteristic.startNotifications()
        } else {
            jsCharacteristic.characteristic.stopNotifications()
        }
    }
    
    override suspend fun indicateCharacteristic(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        indicate: Boolean
    ) {
        // Web Bluetooth API uses the same mechanism for notifications and indications
        notifyCharacteristic(peripheral, characteristic, indicate)
    }
    
    override suspend fun readDescriptor(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        descriptor: BluetoothCharacteristicDescriptor
    ) {
        throw UnsupportedOperationException("readDescriptor is not fully supported in Web Bluetooth API")
    }
    
    override suspend fun writeDescriptor(
        peripheral: BluetoothPeripheral,
        descriptor: BluetoothCharacteristicDescriptor,
        value: ByteArray
    ) {
        throw UnsupportedOperationException("writeDescriptor is not fully supported in Web Bluetooth API")
    }
    
    override suspend fun changeMTU(peripheral: BluetoothPeripheral, mtuSize: Int) {
        throw UnsupportedOperationException("changeMTU is not supported in Web Bluetooth API")
    }
    
    override fun refreshGattCache(peripheral: BluetoothPeripheral): Boolean {
        return false
    }
    
    override suspend fun openL2capChannel(peripheral: BluetoothPeripheral, psm: Int) {
        throw UnsupportedOperationException("openL2capChannel is not supported in Web Bluetooth API")
    }
    
    override suspend fun createBond(peripheral: BluetoothPeripheral) {
        throw UnsupportedOperationException("Bonding is not supported in Web Bluetooth API")
    }
    
    override suspend fun removeBond(peripheral: BluetoothPeripheral) {
        throw UnsupportedOperationException("Bonding is not supported in Web Bluetooth API")
    }
}
