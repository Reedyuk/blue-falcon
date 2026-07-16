package dev.bluefalcon.engine.js

import dev.bluefalcon.core.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Web Bluetooth implementation of [BlueFalconEngine].
 *
 * The orchestration here is target-agnostic; all browser interop is delegated to
 * [WebBluetoothApi], which has separate `actual` implementations for the `js` and
 * `wasmJs` targets. The class name is retained for backwards compatibility with
 * existing Kotlin/JS consumers.
 */
class JsEngine : BlueFalconEngine {
    override val scope = CoroutineScope(Dispatchers.Default)

    private val api = WebBluetoothApi()

    private val _peripherals = MutableStateFlow<Set<BluetoothPeripheral>>(emptySet())
    override val peripherals: StateFlow<Set<BluetoothPeripheral>> = _peripherals.asStateFlow()

    private val _managerState = MutableStateFlow(BluetoothManagerState.Ready)
    override val managerState: StateFlow<BluetoothManagerState> = _managerState.asStateFlow()

    private val _characteristicNotifications = MutableSharedFlow<CharacteristicNotification>(extraBufferCapacity = 64)
    override val characteristicNotifications: SharedFlow<CharacteristicNotification> = _characteristicNotifications

    private val _connectionStateUpdates = MutableSharedFlow<ConnectionStateUpdate>(extraBufferCapacity = 64)
    override val connectionStateUpdates: SharedFlow<ConnectionStateUpdate> = _connectionStateUpdates

    override var isScanning: Boolean = false
        private set

    private val peripheralMap = mutableMapOf<String, JsBluetoothPeripheral>()

    /**
     * Prompts the user to pick a device via the Web Bluetooth chooser and adds the
     * selection to [peripherals].
     *
     * Web Bluetooth has no continuous scan: each call opens the browser's device
     * chooser and resolves with a single device. Dismissing the chooser without
     * selecting (or no device matching) is treated as a no-op — no peripheral is added
     * and no exception is thrown. Genuine failures (Web Bluetooth unavailable, blocked
     * by permissions policy, etc.) still propagate to the caller.
     *
     * The filter UUIDs are declared as optional services so they're accessible after
     * connecting; the chooser itself is not filtered, because Web Bluetooth can't match
     * services a device doesn't advertise.
     */
    override suspend fun scan(filters: List<ServiceFilter>) {
        isScanning = true
        try {
            val serviceUuids = filters.map { it.uuid.toString() }
            // Null means the user dismissed the chooser — leave peripherals untouched.
            val device = api.requestDevice(serviceUuids) ?: return
            val peripheral = peripheralMap.getOrPut(device.id) {
                JsBluetoothPeripheral(device)
            }
            _peripherals.value = _peripherals.value + peripheral
        } finally {
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

        if (jsPeripheral.device.isConnected) {
            return
        }

        // Web Bluetooth's connect() is a Promise that resolves only when the GATT connection
        // is established. As a suspend function, it returns after the connection is confirmed.
        jsPeripheral.device.connect()
        _connectionStateUpdates.tryEmit(ConnectionStateUpdate(peripheral, BluetoothPeripheralState.Connected))
    }

    override suspend fun disconnect(peripheral: BluetoothPeripheral) {
        val jsPeripheral = peripheral as? JsBluetoothPeripheral
            ?: throw IllegalArgumentException("Peripheral must be a JsBluetoothPeripheral")

        // Web Bluetooth's disconnect() resolves synchronously on the JS side.
        jsPeripheral.device.disconnect()
        _connectionStateUpdates.tryEmit(ConnectionStateUpdate(peripheral, BluetoothPeripheralState.Disconnected))
    }

    override fun connectionState(peripheral: BluetoothPeripheral): BluetoothPeripheralState {
        val jsPeripheral = peripheral as? JsBluetoothPeripheral
            ?: return BluetoothPeripheralState.Unknown

        return if (jsPeripheral.device.isConnected) {
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

        val services = jsPeripheral.device.getPrimaryServices(serviceUUIDs.map { it.toString() })
        jsPeripheral.updateServices(services.map { JsBluetoothService(it) })
    }

    override suspend fun discoverCharacteristics(
        peripheral: BluetoothPeripheral,
        service: BluetoothService,
        characteristicUUIDs: List<Uuid>
    ) {
        val jsService = service as? JsBluetoothService
            ?: throw IllegalArgumentException("Service must be a JsBluetoothService")

        val characteristics = jsService.service.getCharacteristics(characteristicUUIDs.map { it.toString() })
        jsService.updateCharacteristics(characteristics.map { JsBluetoothCharacteristic(it, jsService) })
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
            jsCharacteristic.characteristic.startNotifications { value ->
                jsCharacteristic.emitNotification(value)
                _characteristicNotifications.tryEmit(
                    CharacteristicNotification(
                        peripheral = peripheral,
                        characteristic = jsCharacteristic,
                        value = value
                    )
                )
            }
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

    override suspend fun openL2capChannel(
        peripheral: BluetoothPeripheral,
        psm: Int,
        secure: Boolean
    ): BluetoothSocket {
        throw UnsupportedOperationException("openL2capChannel is not supported in Web Bluetooth API")
    }

    override suspend fun createBond(peripheral: BluetoothPeripheral) {
        throw UnsupportedOperationException("Bonding is not supported in Web Bluetooth API")
    }

    override suspend fun removeBond(peripheral: BluetoothPeripheral) {
        throw UnsupportedOperationException("Bonding is not supported in Web Bluetooth API")
    }
}