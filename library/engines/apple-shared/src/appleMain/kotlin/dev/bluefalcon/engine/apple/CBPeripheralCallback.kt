package dev.bluefalcon.engine.apple

import kotlinx.cinterop.ObjCSignatureOverride
import platform.CoreBluetooth.*
import platform.Foundation.NSError
import platform.darwin.NSObject

/**
 * Callback interface for CBPeripheral delegate events
 */
interface CBPeripheralCallback {
    fun onServicesDiscovered(peripheral: CBPeripheral, error: NSError?)
    fun onCharacteristicsDiscovered(peripheral: CBPeripheral, service: CBService, error: NSError?)
    fun onCharacteristicValueUpdated(peripheral: CBPeripheral, characteristic: CBCharacteristic, error: NSError?)
    fun onCharacteristicWritten(peripheral: CBPeripheral, characteristic: CBCharacteristic, error: NSError?)
    fun onDescriptorsDiscovered(peripheral: CBPeripheral, characteristic: CBCharacteristic, error: NSError?)
    fun onNotificationStateUpdated(peripheral: CBPeripheral, characteristic: CBCharacteristic, error: NSError?)
    fun onReadyToSendWriteWithoutResponse(peripheral: CBPeripheral)
    fun onL2CAPChannelOpened(peripheral: CBPeripheral, channel: CBL2CAPChannel?, error: NSError?)
    fun onDescriptorWritten(peripheral: CBPeripheral, descriptor: CBDescriptor, error: NSError?)
}

/**
 * NSObject wrapper that implements CBPeripheralDelegateProtocol and forwards
 * events to a callback interface
 */
class CBPeripheralDelegateWrapper(
    private val callback: CBPeripheralCallback,
    private val acceptsCallback: () -> Boolean = { true },
) : NSObject(), CBPeripheralDelegateProtocol {
    
    override fun peripheral(peripheral: CBPeripheral, didDiscoverServices: NSError?) {
        if (!acceptsCallback()) return
        callback.onServicesDiscovered(peripheral, didDiscoverServices)
    }
    
    override fun peripheral(
        peripheral: CBPeripheral,
        didDiscoverCharacteristicsForService: CBService,
        error: NSError?
    ) {
        if (!acceptsCallback()) return
        callback.onCharacteristicsDiscovered(peripheral, didDiscoverCharacteristicsForService, error)
    }
    
    @ObjCSignatureOverride
    override fun peripheral(
        peripheral: CBPeripheral,
        didUpdateValueForCharacteristic: CBCharacteristic,
        error: NSError?
    ) {
        if (!acceptsCallback()) return
        callback.onCharacteristicValueUpdated(peripheral, didUpdateValueForCharacteristic, error)
    }
    
    @ObjCSignatureOverride
    override fun peripheral(
        peripheral: CBPeripheral,
        didWriteValueForCharacteristic: CBCharacteristic,
        error: NSError?
    ) {
        if (!acceptsCallback()) return
        callback.onCharacteristicWritten(peripheral, didWriteValueForCharacteristic, error)
    }
    
    @ObjCSignatureOverride
    override fun peripheral(
        peripheral: CBPeripheral,
        didDiscoverDescriptorsForCharacteristic: CBCharacteristic,
        error: NSError?
    ) {
        if (!acceptsCallback()) return
        callback.onDescriptorsDiscovered(peripheral, didDiscoverDescriptorsForCharacteristic, error)
    }
    
    @ObjCSignatureOverride
    override fun peripheral(
        peripheral: CBPeripheral,
        didUpdateNotificationStateForCharacteristic: CBCharacteristic,
        error: NSError?
    ) {
        if (!acceptsCallback()) return
        callback.onNotificationStateUpdated(peripheral, didUpdateNotificationStateForCharacteristic, error)
    }

    override fun peripheralIsReadyToSendWriteWithoutResponse(peripheral: CBPeripheral) {
        if (!acceptsCallback()) return
        callback.onReadyToSendWriteWithoutResponse(peripheral)
    }
    
    @ObjCSignatureOverride
    override fun peripheral(
        peripheral: CBPeripheral,
        didOpenL2CAPChannel: CBL2CAPChannel?,
        error: NSError?
    ) {
        if (!acceptsCallback()) return
        callback.onL2CAPChannelOpened(peripheral, didOpenL2CAPChannel, error)
    }
    
    override fun peripheral(
        peripheral: CBPeripheral,
        didWriteValueForDescriptor: CBDescriptor,
        error: NSError?
    ) {
        if (!acceptsCallback()) return
        callback.onDescriptorWritten(peripheral, didWriteValueForDescriptor, error)
    }
}
