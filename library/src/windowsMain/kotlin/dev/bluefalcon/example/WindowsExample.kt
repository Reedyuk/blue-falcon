package dev.bluefalcon.example

import dev.bluefalcon.*

/**
 * Simple Windows example demonstrating Blue Falcon usage
 */
fun main() {
    println("Blue Falcon Windows Example")
    println("===========================")
    
    // Create BlueFalcon instance
    val blueFalcon = BlueFalcon(
        log = PrintLnLogger,
        context = ApplicationContext(),
        autoDiscoverAllServicesAndCharacteristics = true
    )
    
    // Add delegate to handle Bluetooth events
    blueFalcon.delegates.add(object : BlueFalconDelegate {
        override fun didDiscoverDevice(
            bluetoothPeripheral: BluetoothPeripheral,
            advertisementData: Map<AdvertisementDataRetrievalKeys, Any>
        ) {
            println("\nDiscovered device:")
            println("  Name: ${bluetoothPeripheral.name}")
            println("  UUID: ${bluetoothPeripheral.uuid}")
            println("  RSSI: ${bluetoothPeripheral.rssi}")
            
            advertisementData[AdvertisementDataRetrievalKeys.LocalName]?.let {
                println("  Local Name: $it")
            }
        }
        
        override fun didConnect(bluetoothPeripheral: BluetoothPeripheral) {
            println("\n✓ Connected to ${bluetoothPeripheral.name}")
        }
        
        override fun didDisconnect(bluetoothPeripheral: BluetoothPeripheral) {
            println("\n✗ Disconnected from ${bluetoothPeripheral.name}")
        }
        
        override fun didDiscoverServices(bluetoothPeripheral: BluetoothPeripheral) {
            println("\nDiscovered ${bluetoothPeripheral.services.size} services:")
            bluetoothPeripheral.services.forEach { (uuid, service) ->
                println("  Service: $uuid")
                println("    Characteristics: ${service.characteristics.size}")
            }
        }
        
        override fun didDiscoverCharacteristics(bluetoothPeripheral: BluetoothPeripheral) {
            println("\n✓ Discovered characteristics")
        }
        
        override fun didCharacteristcValueChanged(
            bluetoothPeripheral: BluetoothPeripheral,
            bluetoothCharacteristic: BluetoothCharacteristic
        ) {
            println("\nCharacteristic value changed:")
            println("  UUID: ${bluetoothCharacteristic.uuid}")
            println("  Value: ${bluetoothCharacteristic.value?.contentToString()}")
        }
        
        override fun didRssiUpdate(bluetoothPeripheral: BluetoothPeripheral) {
            println("RSSI updated: ${bluetoothPeripheral.rssi}")
        }
        
        override fun didWriteCharacteristic(
            bluetoothPeripheral: BluetoothPeripheral,
            bluetoothCharacteristic: BluetoothCharacteristic,
            success: Boolean
        ) {
            val status = if (success) "✓" else "✗"
            println("$status Write characteristic: ${bluetoothCharacteristic.uuid}")
        }
        
        override fun didReadDescriptor(
            bluetoothPeripheral: BluetoothPeripheral,
            bluetoothCharacteristicDescriptor: BluetoothCharacteristicDescriptor
        ) {
            println("Read descriptor: ${bluetoothCharacteristicDescriptor.uuid}")
        }
        
        override fun didWriteDescriptor(
            bluetoothPeripheral: BluetoothPeripheral,
            bluetoothCharacteristicDescriptor: BluetoothCharacteristicDescriptor
        ) {
            println("Wrote descriptor: ${bluetoothCharacteristicDescriptor.uuid}")
        }
        
        override fun didUpdateNotificationStateFor(
            bluetoothPeripheral: BluetoothPeripheral,
            bluetoothCharacteristic: BluetoothCharacteristic
        ) {
            val state = if (bluetoothCharacteristic.isNotifying) "enabled" else "disabled"
            println("Notifications $state for: ${bluetoothCharacteristic.uuid}")
        }
        
        override fun didUpdateMTU(bluetoothPeripheral: BluetoothPeripheral, mtuSize: Int) {
            println("MTU updated to: $mtuSize bytes")
        }
    })
    
    // Check Bluetooth manager state
    if (blueFalcon.managerState.value != BluetoothManagerState.Ready) {
        println("Error: Bluetooth is not ready!")
        return
    }
    
    println("\nStarting scan for BLE devices...")
    println("Press Ctrl+C to stop\n")
    
    // Start scanning for all devices
    blueFalcon.scan()
    
    // Keep the application running
    Thread.sleep(30000) // Scan for 30 seconds
    
    // Stop scanning
    blueFalcon.stopScanning()
    println("\nScan stopped")
    
    // Example: Connect to first discovered device
    val devices = blueFalcon._peripherals.value
    if (devices.isNotEmpty()) {
        val device = devices.first()
        println("\nConnecting to ${device.name}...")
        blueFalcon.connect(device)
        
        // Wait for connection and discovery
        Thread.sleep(5000)
        
        // Example: Read a characteristic (if available)
        device.services.values.firstOrNull()?.let { service ->
            service.characteristics.firstOrNull()?.let { characteristic ->
                println("\nReading characteristic: ${characteristic.uuid}")
                blueFalcon.readCharacteristic(device, characteristic)
            }
        }
        
        Thread.sleep(2000)
        
        // Disconnect
        println("\nDisconnecting...")
        blueFalcon.disconnect(device)
        
        Thread.sleep(1000)
    }
    
    println("\nExample completed")
}
