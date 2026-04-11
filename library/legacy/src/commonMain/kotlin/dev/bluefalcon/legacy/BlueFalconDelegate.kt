package dev.bluefalcon.legacy

import dev.bluefalcon.core.*
import kotlin.js.JsName

/**
 * Legacy 2.x delegate interface for BlueFalcon callbacks.
 * Provides backward compatibility with existing 2.x applications.
 */
@JsName("BlueFalconDelegate")
interface BlueFalconDelegate {
    
    @JsName("didDiscoverDevice")
    fun didDiscoverDevice(bluetoothPeripheral: BluetoothPeripheral, advertisementData: Map<AdvertisementDataRetrievalKeys, Any>) {}
    
    @JsName("didConnect")
    fun didConnect(bluetoothPeripheral: BluetoothPeripheral) {}
    
    @JsName("didDisconnect")
    fun didDisconnect(bluetoothPeripheral: BluetoothPeripheral) {}
    
    @JsName("didDiscoverServices")
    fun didDiscoverServices(bluetoothPeripheral: BluetoothPeripheral) {}
    
    @JsName("didFailToDiscoverServices")
    fun didFailToDiscoverServices(bluetoothPeripheral: BluetoothPeripheral, status: Int) {}
    
    @JsName("didDiscoverCharacteristics")
    fun didDiscoverCharacteristics(bluetoothPeripheral: BluetoothPeripheral) {}
    
    @JsName("didCharacteristcValueChanged")
    fun didCharacteristcValueChanged(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic
    ) {}
    
    @JsName("didRssiUpdate")
    fun didRssiUpdate(bluetoothPeripheral: BluetoothPeripheral) {}
    
    @JsName("didUpdateMTU")
    fun didUpdateMTU(bluetoothPeripheral: BluetoothPeripheral, status: Int) {}
    
    @JsName("didReadDescriptor")
    fun didReadDescriptor(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristicDescriptor: BluetoothCharacteristicDescriptor
    ) {}
    
    @JsName("didWriteDescriptor")
    fun didWriteDescriptor(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristicDescriptor: BluetoothCharacteristicDescriptor
    ) {}
    
    @JsName("didWriteCharacteristic")
    fun didWriteCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        success: Boolean
    ) {}
    
    @JsName("didUpdateNotificationStateFor")
    fun didUpdateNotificationStateFor(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic
    ) {}
    
    @JsName("didOpenL2capChannel")
    fun didOpenL2capChannel(bluetoothPeripheral: BluetoothPeripheral, bluetoothSocket: BluetoothSocket?) {}
    
    @JsName("didBondStateChanged")
    fun didBondStateChanged(bluetoothPeripheral: BluetoothPeripheral, state: BlueFalconBondState) {}
}

/**
 * Advertisement data keys used in discovery callbacks
 */
enum class AdvertisementDataRetrievalKeys {
    LocalName,
    ManufacturerData,
    ServiceUUIDsKey,
    IsConnectable,
}
