package dev.bluefalcon

import kotlin.js.JsName

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
}