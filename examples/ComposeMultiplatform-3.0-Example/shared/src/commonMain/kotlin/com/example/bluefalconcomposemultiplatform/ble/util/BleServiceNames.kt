package com.example.bluefalconcomposemultiplatform.ble.util

/**
 * Mapping of standard BLE service UUIDs to human-readable names
 * Based on Bluetooth SIG assigned numbers
 */
object BleServiceNames {
    private val serviceNames = mapOf(
        // Generic Services
        "00001800-0000-1000-8000-00805f9b34fb" to "Generic Access",
        "00001801-0000-1000-8000-00805f9b34fb" to "Generic Attribute",
        "00001802-0000-1000-8000-00805f9b34fb" to "Immediate Alert",
        "00001803-0000-1000-8000-00805f9b34fb" to "Link Loss",
        "00001804-0000-1000-8000-00805f9b34fb" to "Tx Power",
        
        // Device Information
        "0000180a-0000-1000-8000-00805f9b34fb" to "Device Information",
        "0000180f-0000-1000-8000-00805f9b34fb" to "Battery Service",
        
        // Health Services
        "00001808-0000-1000-8000-00805f9b34fb" to "Glucose",
        "00001809-0000-1000-8000-00805f9b34fb" to "Health Thermometer",
        "0000180d-0000-1000-8000-00805f9b34fb" to "Heart Rate",
        "00001810-0000-1000-8000-00805f9b34fb" to "Blood Pressure",
        "00001811-0000-1000-8000-00805f9b34fb" to "Alert Notification Service",
        "00001812-0000-1000-8000-00805f9b34fb" to "Human Interface Device",
        "00001818-0000-1000-8000-00805f9b34fb" to "Cycling Power",
        "00001816-0000-1000-8000-00805f9b34fb" to "Cycling Speed and Cadence",
        "00001814-0000-1000-8000-00805f9b34fb" to "Running Speed and Cadence",
        "0000181c-0000-1000-8000-00805f9b34fb" to "User Data",
        "0000181d-0000-1000-8000-00805f9b34fb" to "Weight Scale",
        
        // Location & Navigation
        "00001819-0000-1000-8000-00805f9b34fb" to "Location and Navigation",
        
        // Automation & Control
        "0000181e-0000-1000-8000-00805f9b34fb" to "Bond Management",
        "00001805-0000-1000-8000-00805f9b34fb" to "Current Time Service",
        "00001807-0000-1000-8000-00805f9b34fb" to "Next DST Change Service",
        "00001806-0000-1000-8000-00805f9b34fb" to "Reference Time Update Service",
        
        // Phone & Media
        "0000180e-0000-1000-8000-00805f9b34fb" to "Phone Alert Status Service",
        
        // Environmental
        "0000181a-0000-1000-8000-00805f9b34fb" to "Environmental Sensing",
        
        // Other common services
        "6e400001-b5a3-f393-e0a9-e50e24dcca9e" to "Nordic UART Service",
        "0000fee0-0000-1000-8000-00805f9b34fb" to "Xiaomi Service",
        "0000fee7-0000-1000-8000-00805f9b34fb" to "Tile Service",
        
        // Nordic MCUmgr SMP (Firmware OTA)
        "8d53dc1d-1db7-4cd3-868b-8a527460aa84" to "Nordic SMP (FOTA)"
    )
    
    private val characteristicNames = mapOf(
        // Generic Access
        "00002a00-0000-1000-8000-00805f9b34fb" to "Device Name",
        "00002a01-0000-1000-8000-00805f9b34fb" to "Appearance",
        "00002a04-0000-1000-8000-00805f9b34fb" to "Peripheral Preferred Connection Parameters",
        
        // Device Information
        "00002a23-0000-1000-8000-00805f9b34fb" to "System ID",
        "00002a24-0000-1000-8000-00805f9b34fb" to "Model Number",
        "00002a25-0000-1000-8000-00805f9b34fb" to "Serial Number",
        "00002a26-0000-1000-8000-00805f9b34fb" to "Firmware Revision",
        "00002a27-0000-1000-8000-00805f9b34fb" to "Hardware Revision",
        "00002a28-0000-1000-8000-00805f9b34fb" to "Software Revision",
        "00002a29-0000-1000-8000-00805f9b34fb" to "Manufacturer Name",
        "00002a2a-0000-1000-8000-00805f9b34fb" to "IEEE Regulatory Certification",
        "00002a50-0000-1000-8000-00805f9b34fb" to "PnP ID",
        
        // Battery
        "00002a19-0000-1000-8000-00805f9b34fb" to "Battery Level",
        
        // Heart Rate
        "00002a37-0000-1000-8000-00805f9b34fb" to "Heart Rate Measurement",
        "00002a38-0000-1000-8000-00805f9b34fb" to "Body Sensor Location",
        "00002a39-0000-1000-8000-00805f9b34fb" to "Heart Rate Control Point",
        
        // Temperature
        "00002a1c-0000-1000-8000-00805f9b34fb" to "Temperature Measurement",
        "00002a1d-0000-1000-8000-00805f9b34fb" to "Temperature Type",
        
        // Nordic UART
        "6e400002-b5a3-f393-e0a9-e50e24dcca9e" to "UART TX",
        "6e400003-b5a3-f393-e0a9-e50e24dcca9e" to "UART RX",
        
        // Nordic MCUmgr SMP (Firmware OTA)
        "da2e7828-fbce-4e01-ae9e-261174997c48" to "SMP Characteristic"
    )
    
    /**
     * Get human-readable name for a service UUID
     * @param uuid The service UUID (case-insensitive)
     * @return Human-readable name or null if not found
     */
    fun getServiceName(uuid: String): String? {
        return serviceNames[uuid.lowercase()]
    }
    
    /**
     * Get human-readable name for a characteristic UUID
     * @param uuid The characteristic UUID (case-insensitive)
     * @return Human-readable name or null if not found
     */
    fun getCharacteristicName(uuid: String): String? {
        return characteristicNames[uuid.lowercase()]
    }
}
