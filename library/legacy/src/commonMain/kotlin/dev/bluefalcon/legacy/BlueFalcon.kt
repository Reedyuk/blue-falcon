package dev.bluefalcon.legacy

import dev.bluefalcon.core.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Legacy BlueFalcon 2.x API wrapper that provides backward compatibility
 * with existing applications while using the new 3.0 engine architecture.
 * 
 * This expect/actual class maintains the same API surface as BlueFalcon 2.x
 * but delegates to the new BlueFalconEngine underneath.
 */
expect class BlueFalcon(
    log: Logger? = PrintLnLogger,
    context: ApplicationContext,
    autoDiscoverAllServicesAndCharacteristics: Boolean = true
) {
    
    val scope: CoroutineScope
    
    /**
     * Set of delegates to receive BLE event callbacks
     */
    val delegates: MutableSet<BlueFalconDelegate>
    
    /**
     * Whether currently scanning for devices
     */
    var isScanning: Boolean
    
    /**
     * Current state of the Bluetooth manager
     */
    val managerState: StateFlow<BluetoothManagerState>
    
    /**
     * Internal mutable flow of discovered peripherals
     */
    internal val _peripherals: MutableStateFlow<Set<BluetoothPeripheral>>
    
    /**
     * Discovered peripherals as a native-friendly flow
     */
    val peripherals: NativeFlow<Set<BluetoothPeripheral>>
    
    /**
     * Connect to a Bluetooth peripheral
     */
    fun connect(bluetoothPeripheral: BluetoothPeripheral, autoConnect: Boolean = false)
    
    /**
     * Disconnect from a Bluetooth peripheral
     */
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
    
    /**
     * Request a connection priority change (Android-specific)
     */
    fun requestConnectionPriority(bluetoothPeripheral: BluetoothPeripheral, connectionPriority: ConnectionPriority)
    
    /**
     * Get the connection state of a peripheral
     */
    fun connectionState(bluetoothPeripheral: BluetoothPeripheral): BluetoothPeripheralState
    
    /**
     * Start scanning for BLE devices
     * 
     * @throws BluetoothUnknownException
     * @throws BluetoothResettingException
     * @throws BluetoothUnsupportedException
     * @throws BluetoothPermissionException
     * @throws BluetoothNotEnabledException
     */
    @Throws(
        BluetoothUnknownException::class,
        BluetoothResettingException::class,
        BluetoothUnsupportedException::class,
        BluetoothPermissionException::class,
        BluetoothNotEnabledException::class
    )
    fun scan(filters: List<ServiceFilter> = emptyList())
    
    /**
     * Clear all discovered peripherals
     */
    fun clearPeripherals()
    
    /**
     * Stop scanning for devices
     */
    fun stopScanning()
    
    /**
     * Refresh the GATT cache (Android-specific)
     */
    fun refreshGattCache(bluetoothPeripheral: BluetoothPeripheral): Boolean
    
    /**
     * Discover services on a connected peripheral
     */
    fun discoverServices(bluetoothPeripheral: BluetoothPeripheral, serviceUUIDs: List<Uuid> = emptyList())
    
    /**
     * Discover characteristics for a service
     */
    fun discoverCharacteristics(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothService: BluetoothService,
        characteristicUUIDs: List<Uuid> = emptyList()
    )
    
    /**
     * Read a characteristic value
     */
    fun readCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic
    )
    
    /**
     * Enable/disable notifications for a characteristic
     */
    fun notifyCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        notify: Boolean
    )
    
    /**
     * Enable/disable indications for a characteristic
     */
    fun indicateCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        indicate: Boolean
    )
    
    /**
     * Enable/disable both notifications and indications for a characteristic
     */
    fun notifyAndIndicateCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        enable: Boolean
    )
    
    /**
     * Write a string value to a characteristic
     */
    fun writeCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        value: String,
        writeType: Int?
    )
    
    /**
     * Write a byte array to a characteristic
     */
    fun writeCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        value: ByteArray,
        writeType: Int?
    )
    
    /**
     * Write a byte array to a characteristic without encoding
     */
    fun writeCharacteristicWithoutEncoding(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        value: ByteArray,
        writeType: Int?
    )
    
    /**
     * Read a descriptor value
     */
    fun readDescriptor(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        bluetoothCharacteristicDescriptor: BluetoothCharacteristicDescriptor
    )
    
    /**
     * Write a descriptor value
     */
    fun writeDescriptor(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristicDescriptor: BluetoothCharacteristicDescriptor,
        value: ByteArray
    )
    
    /**
     * Request MTU size change
     */
    fun changeMTU(bluetoothPeripheral: BluetoothPeripheral, mtuSize: Int)
    
    /**
     * Open an L2CAP channel
     */
    fun openL2capChannel(bluetoothPeripheral: BluetoothPeripheral, psm: Int)
    
    /**
     * Create a bond/pairing with a peripheral
     */
    fun createBond(bluetoothPeripheral: BluetoothPeripheral)
    
    /**
     * Remove a bond/pairing with a peripheral
     */
    fun removeBond(bluetoothPeripheral: BluetoothPeripheral)
    
    /**
     * Cleanup resources
     */
    fun destroy()
}

/**
 * Bluetooth manager state for backward compatibility
 */
enum class BluetoothManagerState {
    Ready, NotReady
}
