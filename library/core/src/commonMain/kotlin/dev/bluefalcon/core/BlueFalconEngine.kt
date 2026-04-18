package dev.bluefalcon.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Core engine interface that all platform engines must implement.
 * This defines the contract for BLE operations across all platforms.
 */
interface BlueFalconEngine {
    /**
     * Coroutine scope for engine operations
     */
    val scope: CoroutineScope
    
    /**
     * Currently discovered peripherals
     */
    val peripherals: StateFlow<Set<BluetoothPeripheral>>
    
    /**
     * Current Bluetooth manager state
     */
    val managerState: StateFlow<BluetoothManagerState>

    /**
     * Characteristic notification/indication events emitted by platform callbacks.
     */
    val characteristicNotifications: SharedFlow<CharacteristicNotification>
    
    /**
     * Check if currently scanning
     */
    val isScanning: Boolean
    
    /**
     * Start scanning for BLE devices
     * @param filters Optional service UUID filters
     */
    suspend fun scan(filters: List<ServiceFilter> = emptyList())
    
    /**
     * Stop scanning for devices
     */
    suspend fun stopScanning()
    
    /**
     * Clear all discovered peripherals from cache
     */
    fun clearPeripherals()
    
    /**
     * Connect to a peripheral
     * @param peripheral The peripheral to connect to
     * @param autoConnect Whether to automatically reconnect on disconnection
     */
    suspend fun connect(peripheral: BluetoothPeripheral, autoConnect: Boolean = false)
    
    /**
     * Disconnect from a peripheral
     * @param peripheral The peripheral to disconnect from
     */
    suspend fun disconnect(peripheral: BluetoothPeripheral)
    
    /**
     * Get the current connection state of a peripheral
     */
    fun connectionState(peripheral: BluetoothPeripheral): BluetoothPeripheralState
    
    /**
     * Retrieve a peripheral by its platform-specific identifier
     * @param identifier MAC address (Android) or UUID (iOS/Native)
     */
    fun retrievePeripheral(identifier: String): BluetoothPeripheral?
    
    /**
     * Request a connection priority change (Android-specific, no-op on other platforms)
     */
    fun requestConnectionPriority(peripheral: BluetoothPeripheral, priority: ConnectionPriority)
    
    /**
     * Discover services on a connected peripheral
     * @param peripheral The peripheral to discover services on
     * @param serviceUUIDs Optional list of specific service UUIDs to discover
     */
    suspend fun discoverServices(peripheral: BluetoothPeripheral, serviceUUIDs: List<Uuid> = emptyList())
    
    /**
     * Discover characteristics for a service
     * @param peripheral The peripheral
     * @param service The service to discover characteristics for
     * @param characteristicUUIDs Optional list of specific characteristic UUIDs
     */
    suspend fun discoverCharacteristics(
        peripheral: BluetoothPeripheral,
        service: BluetoothService,
        characteristicUUIDs: List<Uuid> = emptyList()
    )
    
    /**
     * Read a characteristic value
     */
    suspend fun readCharacteristic(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic
    )
    
    /**
     * Write a string value to a characteristic
     */
    suspend fun writeCharacteristic(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        value: String,
        writeType: Int? = null
    )
    
    /**
     * Write a byte array to a characteristic
     */
    suspend fun writeCharacteristic(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        value: ByteArray,
        writeType: Int? = null
    )
    
    /**
     * Enable/disable notifications for a characteristic
     */
    suspend fun notifyCharacteristic(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        notify: Boolean
    )
    
    /**
     * Enable/disable indications for a characteristic
     */
    suspend fun indicateCharacteristic(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        indicate: Boolean
    )
    
    /**
     * Read a descriptor value
     */
    suspend fun readDescriptor(
        peripheral: BluetoothPeripheral,
        characteristic: BluetoothCharacteristic,
        descriptor: BluetoothCharacteristicDescriptor
    )
    
    /**
     * Write a descriptor value
     */
    suspend fun writeDescriptor(
        peripheral: BluetoothPeripheral,
        descriptor: BluetoothCharacteristicDescriptor,
        value: ByteArray
    )
    
    /**
     * Request MTU size change
     */
    suspend fun changeMTU(peripheral: BluetoothPeripheral, mtuSize: Int)
    
    /**
     * Refresh GATT cache (Android-specific)
     */
    fun refreshGattCache(peripheral: BluetoothPeripheral): Boolean
    
    /**
     * Open an L2CAP channel
     */
    suspend fun openL2capChannel(peripheral: BluetoothPeripheral, psm: Int)
    
    /**
     * Create a bond/pairing with a peripheral
     */
    suspend fun createBond(peripheral: BluetoothPeripheral)
    
    /**
     * Remove a bond/pairing with a peripheral
     */
    suspend fun removeBond(peripheral: BluetoothPeripheral)
}

data class CharacteristicNotification(
    val peripheral: BluetoothPeripheral,
    val characteristic: BluetoothCharacteristic,
    val value: ByteArray
)
