package dev.bluefalcon

import kotlinx.cinterop.ObjCSignatureOverride
import platform.CoreBluetooth.*
import platform.Foundation.NSError
import platform.darwin.NSObject

class PeripheralDelegate constructor(
    private val log: Logger?,
    private val blueFalcon: BlueFalcon
) : NSObject(), CBPeripheralDelegateProtocol {

    override fun peripheral(peripheral: CBPeripheral, didDiscoverServices: NSError?) {
        if (didDiscoverServices != null) {
            log?.error("Error with service discovery ${didDiscoverServices}")
        } else {
            log?.info("Services discovered")
            val device = BluetoothPeripheralImpl(peripheral, rssiValue = null)
            blueFalcon.delegates.forEach {
                it.didDiscoverServices(device)
            }
            if (blueFalcon.autoDiscoverAllServicesAndCharacteristics) {
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
        log?.debug("didDiscoverCharacteristicsForService")
        val device = BluetoothPeripheralImpl(peripheral, rssiValue = null)
        blueFalcon.delegates.forEach {
            it.didDiscoverCharacteristics(device)
        }
        BluetoothService(didDiscoverCharacteristicsForService).characteristics.forEach {
            peripheral.discoverDescriptorsForCharacteristic(it.characteristic)
        }
    }

    @ObjCSignatureOverride
    override fun peripheral(
        peripheral: CBPeripheral,
        didWriteValueForCharacteristic: CBCharacteristic,
        error: NSError?
    ) {
        if (error != null) {
            log?.error("Error with characteristic write $error")
        }
        val device = BluetoothPeripheralImpl(peripheral, rssiValue = null)
        val characteristic = BluetoothCharacteristic(didWriteValueForCharacteristic)
        blueFalcon.delegates.forEach {
            it.didWriteCharacteristic(
                device,
                characteristic,
                error != null,
            )
        }
    }

    @ObjCSignatureOverride
    override fun peripheral(
        peripheral: CBPeripheral,
        didUpdateValueForCharacteristic: CBCharacteristic,
        error: NSError?
    ) {
        if (error != null) {
            log?.error("Error with characteristic update ${error}")
        }
        val device = BluetoothPeripheralImpl(peripheral, rssiValue = null)
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

        log?.debug("didWriteValueForDescriptor")
        val device = BluetoothPeripheralImpl(peripheral, rssiValue = null)
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

    @ObjCSignatureOverride
    override fun peripheral(
        peripheral: CBPeripheral,
        didDiscoverDescriptorsForCharacteristic: CBCharacteristic,
        error: NSError?
    ) {
        if (error != null) {
            log?.error("Error discovering descriptors for characteristic ${didDiscoverDescriptorsForCharacteristic.UUID}: $error")
        } else {
            log?.debug("didDiscoverDescriptorsForCharacteristic ${didDiscoverDescriptorsForCharacteristic.UUID}")
        }
        // Descriptors are discovered automatically by BlueFalcon
        // This method is required by iOS to avoid API misuse warnings
    }

    @ObjCSignatureOverride
    override fun peripheral(
        peripheral: CBPeripheral,
        didUpdateNotificationStateForCharacteristic: CBCharacteristic,
        error: NSError?
    ) {
        if (error != null) {
            log?.error("Error updating notification state for characteristic ${didUpdateNotificationStateForCharacteristic.UUID}: $error")
        } else {
            log?.debug("didUpdateNotificationStateForCharacteristic ${didUpdateNotificationStateForCharacteristic.UUID} isNotifying: ${didUpdateNotificationStateForCharacteristic.isNotifying}")
        }
        val device = BluetoothPeripheralImpl(peripheral, rssiValue = null)
        val characteristic = BluetoothCharacteristic(didUpdateNotificationStateForCharacteristic)
        blueFalcon.delegates.forEach {
            it.didUpdateNotificationStateFor(device, characteristic)
        }
    }
}