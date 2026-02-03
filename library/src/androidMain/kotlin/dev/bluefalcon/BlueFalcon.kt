package dev.bluefalcon

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

actual class BlueFalcon actual constructor(
    private val log: Logger?,
    private val context: ApplicationContext,
    private val autoDiscoverAllServicesAndCharacteristics: Boolean
) {
    
    // Engine-based constructor - allows custom engine injection
    constructor(
        engine: BlueFalconEngine
    ) : this(null, ApplicationContext(), true) {
        this.engine = engine
    }
    
    private var engine: BlueFalconEngine = createDefaultBlueFalconEngine(log, context, autoDiscoverAllServicesAndCharacteristics)
    
    actual val delegates: MutableSet<BlueFalconDelegate>
        get() = engine.delegates
    
    actual var isScanning: Boolean
        get() = engine.isScanning
        set(value) { engine.isScanning = value }

    actual val scope: CoroutineScope
        get() = engine.scope
    
    internal actual val _peripherals: MutableStateFlow<Set<BluetoothPeripheral>>
        get() = engine._peripherals
    
    actual val peripherals: NativeFlow<Set<BluetoothPeripheral>>
        get() = engine.peripherals
    
    actual val managerState: StateFlow<BluetoothManagerState>
        get() = engine.managerState

    
    actual fun requestConnectionPriority(
        bluetoothPeripheral: BluetoothPeripheral,
        connectionPriority: ConnectionPriority
    ) = engine.requestConnectionPriority(bluetoothPeripheral, connectionPriority)

    actual fun connectionState(bluetoothPeripheral: BluetoothPeripheral): BluetoothPeripheralState =
        engine.connectionState(bluetoothPeripheral)

    actual fun connect(bluetoothPeripheral: BluetoothPeripheral, autoConnect: Boolean) =
        engine.connect(bluetoothPeripheral, autoConnect)

    actual fun disconnect(bluetoothPeripheral: BluetoothPeripheral) =
        engine.disconnect(bluetoothPeripheral)

    actual fun retrievePeripheral(identifier: String): BluetoothPeripheral? =
        engine.retrievePeripheral(identifier)

    actual fun stopScanning() = engine.stopScanning()

    actual fun clearPeripherals() = engine.clearPeripherals()

    actual fun scan(filters: List<ServiceFilter>) = engine.scan(filters)

    actual fun discoverServices(
        bluetoothPeripheral: BluetoothPeripheral,
        serviceUUIDs: List<Uuid>
    ) = engine.discoverServices(bluetoothPeripheral, serviceUUIDs)
    
    actual fun discoverCharacteristics(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothService: BluetoothService,
        characteristicUUIDs: List<Uuid>
    ) = engine.discoverCharacteristics(bluetoothPeripheral, bluetoothService, characteristicUUIDs)

    actual fun readCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic
    ) = engine.readCharacteristic(bluetoothPeripheral, bluetoothCharacteristic)

    actual fun notifyCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        notify: Boolean
    ) = engine.notifyCharacteristic(bluetoothPeripheral, bluetoothCharacteristic, notify)

    actual fun indicateCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        indicate: Boolean
    ) = engine.indicateCharacteristic(bluetoothPeripheral, bluetoothCharacteristic, indicate)

    actual fun notifyAndIndicateCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        enable: Boolean
    ) = engine.notifyAndIndicateCharacteristic(bluetoothPeripheral, bluetoothCharacteristic, enable)

    actual fun writeCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        value: String,
        writeType: Int?
    ) = engine.writeCharacteristic(bluetoothPeripheral, bluetoothCharacteristic, value, writeType)

    actual fun writeCharacteristicWithoutEncoding(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        value: ByteArray,
        writeType: Int?
    ) = engine.writeCharacteristicWithoutEncoding(bluetoothPeripheral, bluetoothCharacteristic, value, writeType)

    actual fun writeCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        value: ByteArray,
        writeType: Int?
    ) = engine.writeCharacteristic(bluetoothPeripheral, bluetoothCharacteristic, value, writeType)

    actual fun readDescriptor(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        bluetoothCharacteristicDescriptor: BluetoothCharacteristicDescriptor
    ) = engine.readDescriptor(bluetoothPeripheral, bluetoothCharacteristic, bluetoothCharacteristicDescriptor)
    
    actual fun writeDescriptor(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristicDescriptor: BluetoothCharacteristicDescriptor,
        value: ByteArray
    ) = engine.writeDescriptor(bluetoothPeripheral, bluetoothCharacteristicDescriptor, value)

    actual fun changeMTU(bluetoothPeripheral: BluetoothPeripheral, mtuSize: Int) =
        engine.changeMTU(bluetoothPeripheral, mtuSize)
}
