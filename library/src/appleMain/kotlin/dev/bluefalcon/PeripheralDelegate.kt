package dev.bluefalcon

import platform.CoreBluetooth.*
import platform.Foundation.NSError
import platform.Foundation.NSNumber
import platform.darwin.NSObject

class PeripheralDelegate constructor(
    private val log: Logger?,
    private val blueFalcon: BlueFalcon
) : NSObject(), CBPeripheralDelegateProtocol {

    override fun peripheral(peripheral: CBPeripheral, didDiscoverServices: NSError?) {
        if (didDiscoverServices != null) {
            log?.error("Error with service discovery ${didDiscoverServices}")
        } else {
            log?.info("didDiscoverServices")
            val device = BluetoothPeripheral(peripheral, rssiValue = null)
            blueFalcon.delegates.forEach {
                it.didDiscoverServices(device)
            }
            if(blueFalcon.autoDiscoverAllServicesAndCharacteristics) {
                peripheral.services
                    ?.mapNotNull { it as? CBService }
                    ?.forEach {
                        peripheral.discoverCharacteristics(null, it)
                    }
            }
        }
    }

    override fun peripheral(
        peripheral: CBPeripheral,
        didDiscoverCharacteristicsForService: CBService,
        error: NSError?
    ) {
        if (error != null) {
            log?.error("Error with characteristic discovery ${didDiscoverCharacteristicsForService}")
        }
        log?.info("didDiscoverCharacteristicsForService")
        val device = BluetoothPeripheral(peripheral, rssiValue = null)
        blueFalcon.delegates.forEach {
            it.didDiscoverCharacteristics(device)
        }
        BluetoothService(didDiscoverCharacteristicsForService).characteristics.forEach {
            peripheral.discoverDescriptorsForCharacteristic(it.characteristic)
        }
    }

    override fun peripheral(
        peripheral: CBPeripheral,
        didUpdateValueForCharacteristic: CBCharacteristic,
        error: NSError?
    ) {
        if (error != null) {
            log?.error("Error with characteristic update ${error}")
        }
        log?.info("didUpdateValueForCharacteristic")
        val device = BluetoothPeripheral(peripheral, rssiValue = null)
        val characteristic = BluetoothCharacteristic(didUpdateValueForCharacteristic)
        blueFalcon.delegates.forEach {
            it.didCharacteristcValueChanged(
                device,
                characteristic
            )
        }
    }
    override fun peripheral(
        peripheral: CBPeripheral,
        didWriteValueForDescriptor: CBDescriptor,
        error: NSError?
    ) {
        if (error != null) {
            log?.error("Error during characteristic write $error")
        }

        log?.info("didWriteValueForCharacteristic")
        val device = BluetoothPeripheral(peripheral, rssiValue = null)
        didWriteValueForDescriptor.characteristic?.let { characteristic ->
            val characteristic = BluetoothCharacteristic(characteristic)
            blueFalcon.delegates.forEach {
                it.didWriteCharacteristic(
                    device,
                    characteristic,
                    error == null
                )
            }
        }
    }
}