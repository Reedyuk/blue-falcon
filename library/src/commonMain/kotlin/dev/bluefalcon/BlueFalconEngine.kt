package dev.bluefalcon

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * BlueFalconEngine defines the contract for platform-specific Bluetooth LE implementations.
 * 
 * This interface follows the Ktor engine pattern, allowing anyone to create custom engines
 * for different platforms while maintaining a consistent API.
 * 
 * To create a custom engine:
 * 1. Implement this interface with platform-specific Bluetooth logic
 * 2. Pass your engine instance to BlueFalcon constructor
 * 3. All BLE operations will be delegated to your engine implementation
 */
interface BlueFalconEngine {
    
    val scope: CoroutineScope
    
    val delegates: MutableSet<BlueFalconDelegate>
    var isScanning: Boolean
    
    val managerState: StateFlow<BluetoothManagerState>
    
    val _peripherals: MutableStateFlow<Set<BluetoothPeripheral>>
    val peripherals: NativeFlow<Set<BluetoothPeripheral>>
    
    fun connect(bluetoothPeripheral: BluetoothPeripheral, autoConnect: Boolean = false)
    fun disconnect(bluetoothPeripheral: BluetoothPeripheral)
    
    /**
     * Retrieves a peripheral by its platform-specific identifier.
     *
     * @param identifier The platform-specific identifier:
     *   - **Android**: MAC address format (e.g., "00:11:22:33:44:55")
     *   - **iOS/Native**: UUID format (e.g., "XXXXXXXX-XXXX-XXXX-XXXX-XXXXXXXXXXXX")
     * @return The BluetoothPeripheral if found, null otherwise
     */
    fun retrievePeripheral(identifier: String): BluetoothPeripheral?
    
    fun requestConnectionPriority(bluetoothPeripheral: BluetoothPeripheral, connectionPriority: ConnectionPriority)
    
    fun connectionState(bluetoothPeripheral: BluetoothPeripheral): BluetoothPeripheralState
    
    @Throws(
        BluetoothUnknownException::class,
        BluetoothResettingException::class,
        BluetoothUnsupportedException::class,
        BluetoothPermissionException::class,
        BluetoothNotEnabledException::class
    )
    fun scan(filters: List<ServiceFilter> = emptyList())
    
    fun clearPeripherals()
    
    fun stopScanning()
    
    fun discoverServices(bluetoothPeripheral: BluetoothPeripheral, serviceUUIDs: List<Uuid> = emptyList())
    
    fun discoverCharacteristics(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothService: BluetoothService,
        characteristicUUIDs: List<Uuid> = emptyList()
    )
    
    fun readCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic
    )
    
    fun notifyCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        notify: Boolean
    )
    
    fun indicateCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        indicate: Boolean
    )
    
    fun notifyAndIndicateCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        enable: Boolean
    )
    
    fun writeCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        value: String,
        writeType: Int?
    )
    
    fun writeCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        value: ByteArray,
        writeType: Int?
    )
    
    fun writeCharacteristicWithoutEncoding(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        value: ByteArray,
        writeType: Int?
    )
    
    fun readDescriptor(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        bluetoothCharacteristicDescriptor: BluetoothCharacteristicDescriptor
    )
    
    fun writeDescriptor(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristicDescriptor: BluetoothCharacteristicDescriptor,
        value: ByteArray
    )
    
    fun changeMTU(bluetoothPeripheral: BluetoothPeripheral, mtuSize: Int)
}
