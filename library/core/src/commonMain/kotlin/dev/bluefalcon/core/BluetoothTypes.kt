package dev.bluefalcon.core

import kotlinx.coroutines.flow.SharedFlow

/**
 * Represents a Bluetooth Low Energy peripheral device
 */
interface BluetoothPeripheral {
    /**
     * Device name, may be null if not advertised
     */
    val name: String?
    
    /**
     * Platform-specific unique identifier
     * - Android: MAC address (e.g., "00:11:22:33:44:55")
     * - iOS/Native: UUID string
     */
    val uuid: String
    
    /**
     * Received Signal Strength Indicator in dBm
     */
    val rssi: Float?
    
    /**
     * Maximum Transmission Unit size in bytes
     */
    val mtuSize: Int?
    
    /**
     * Discovered services on this peripheral
     */
    val services: List<BluetoothService>
    
    /**
     * All discovered characteristics across all services
     */
    val characteristics: List<BluetoothCharacteristic>

    /**
     * Manufacturer-specific advertisement data, keyed by company ID (little-endian 16-bit value).
     * Populated from scan results; empty when not advertised or not yet scanned.
     *
     * - Android: sourced from `ScanRecord.manufacturerSpecificData` (API 33+) or parsed from raw bytes
     * - iOS/macOS: sourced from `kCBAdvDataManufacturerData` (NSData → company ID + payload)
     */
    val manufacturerData: Map<Int, ByteArray>
        get() = emptyMap()
}

/**
 * Represents a GATT service on a BLE peripheral
 */
interface BluetoothService {
    /**
     * Service UUID
     */
    val uuid: Uuid
    
    /**
     * Human-readable service name (if known)
     */
    val name: String?
    
    /**
     * Characteristics belonging to this service
     */
    val characteristics: List<BluetoothCharacteristic>
}

/**
 * Represents a GATT characteristic within a service
 */
interface BluetoothCharacteristic {
    /**
     * Characteristic UUID
     */
    val uuid: Uuid
    
    /**
     * Human-readable characteristic name (if known)
     */
    val name: String?
    
    /**
     * Current value of the characteristic
     */
    val value: ByteArray?

    /**
     * Stream of subscribed notification/indication payloads.
     */
    val notifications: SharedFlow<ByteArray>
    
    /**
     * Descriptors for this characteristic
     */
    val descriptors: List<BluetoothCharacteristicDescriptor>
    
    /**
     * Whether notifications are currently enabled
     */
    val isNotifying: Boolean
    
    /**
     * The service this characteristic belongs to
     */
    val service: BluetoothService?
}

/**
 * Represents a GATT characteristic descriptor
 */
interface BluetoothCharacteristicDescriptor {
    /**
     * Descriptor UUID
     */
    val uuid: Uuid
    
    /**
     * Descriptor value
     */
    val value: ByteArray?
    
    /**
     * The characteristic this descriptor belongs to
     */
    val characteristic: BluetoothCharacteristic?
}
