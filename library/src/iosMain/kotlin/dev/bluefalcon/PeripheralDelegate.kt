package dev.bluefalcon

import platform.CoreBluetooth.*
import platform.Foundation.NSError
import platform.darwin.NSObject

actual class PeripheralDelegate actual constructor(
    private val blueFalcon: BlueFalcon
) : NSObject(), CBPeripheralDelegateProtocol {

    override fun peripheral(
        peripheral: CBPeripheral,
        didDiscoverServices: NSError?
    ) {
        if (didDiscoverServices != null) {
            println("Error with service discovery ${didDiscoverServices}")
        } else {
            val device = BluetoothPeripheral(peripheral, rssiValue = null)
            blueFalcon.delegates.forEach {
                it.didDiscoverServices(device)
            }
            peripheral.services
                ?.mapNotNull { it as? CBService }
                ?.forEach {
                    peripheral.discoverCharacteristics(null, it)
                }
        }
    }

    override fun peripheral(
        peripheral: CBPeripheral,
        didDiscoverCharacteristicsForService: CBService,
        error: NSError?
    ) {
        if (error != null) {
            println("Error with characteristic discovery ${didDiscoverCharacteristicsForService}")
        }
        val device = BluetoothPeripheral(peripheral, rssiValue = null)
        blueFalcon.delegates.forEach {
            it.didDiscoverCharacteristics(device)
        }
        BluetoothService(didDiscoverCharacteristicsForService).characteristics.forEach {
            peripheral.discoverDescriptorsForCharacteristic(it.characteristic)
        }
    }

    @Suppress("CONFLICTING_OVERLOADS")
    override fun peripheral(
        peripheral: CBPeripheral,
        didUpdateValueForCharacteristic: CBCharacteristic,
        error: NSError?
    ) {
        if (error != null) {
            println("Error with characteristic update ${error}")
        }
        println("didUpdateValueForCharacteristic")
        val device = BluetoothPeripheral(peripheral, rssiValue = null)
        val characteristic = BluetoothCharacteristic(didUpdateValueForCharacteristic)
        blueFalcon.delegates.forEach {
            it.didCharacteristcValueChanged(
                device,
                characteristic
            )
        }
    }

    override fun peripheral(peripheral: CBPeripheral, didUpdateValueForDescriptor: CBDescriptor, error: NSError?) {
        println("didUpdateValueForDescriptor ${didUpdateValueForDescriptor.value}")
    }

    @Suppress("CONFLICTING_OVERLOADS")
    override fun peripheral(peripheral: CBPeripheral, didDiscoverDescriptorsForCharacteristic: CBCharacteristic, error: NSError?) {
        println("didDiscoverDescriptorsForCharacteristic")
    }

    @Suppress("CONFLICTING_OVERLOADS")
    override fun peripheral(peripheral: CBPeripheral, didWriteValueForCharacteristic: CBCharacteristic, error: NSError?) {
        if (error != null) {
            println("Error during characteristic write $error")
        }

        println("didWriteValueForCharacteristic")
        val device = BluetoothPeripheral(peripheral, rssiValue = null)
        val characteristic = BluetoothCharacteristic(didWriteValueForCharacteristic)
        blueFalcon.delegates.forEach {
            it.didWriteCharacteristic(
                device,
                characteristic,
                error == null
            )
        }
    }
}