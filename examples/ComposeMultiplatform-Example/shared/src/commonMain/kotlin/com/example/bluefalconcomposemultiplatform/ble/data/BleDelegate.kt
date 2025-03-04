package com.example.bluefalconcomposemultiplatform.ble.data

import dev.bluefalcon.AdvertisementDataRetrievalKeys
import dev.bluefalcon.BlueFalconDelegate
import dev.bluefalcon.BluetoothCharacteristic
import dev.bluefalcon.BluetoothCharacteristicDescriptor
import dev.bluefalcon.BluetoothPeripheral


class BleDelegate: BlueFalconDelegate {
    var writeChar: BluetoothCharacteristic? = null
    var readChar: BluetoothCharacteristic? = null

    private var onDeviceEvent: ((DeviceEvent) -> Unit)? = null
    fun setListener(onEvent: (DeviceEvent) -> Unit) {
        onDeviceEvent = onEvent
    }

    override fun didDiscoverDevice(bluetoothPeripheral: BluetoothPeripheral, advertisementData: Map<AdvertisementDataRetrievalKeys, Any>) {
        onDeviceEvent?.let {
            it(DeviceEvent.OnDeviceDiscovered(bluetoothPeripheral.uuid, bluetoothPeripheral))
        }
    }

    override fun didCharacteristcValueChanged(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic
    ) {
        bluetoothCharacteristic.value?.let { bytes ->

        }
    }

    override fun didConnect(bluetoothPeripheral: BluetoothPeripheral) {
        onDeviceEvent?.let {
            it(DeviceEvent.OnDeviceConnected(bluetoothPeripheral.uuid))
        }
    }

    override fun didDisconnect(bluetoothPeripheral: BluetoothPeripheral) {
        onDeviceEvent?.let {
            it(DeviceEvent.OnDeviceDisconnected(bluetoothPeripheral.uuid))
        }
    }

    override fun didDiscoverCharacteristics(bluetoothPeripheral: BluetoothPeripheral) {

    }

    override fun didDiscoverServices(bluetoothPeripheral: BluetoothPeripheral) {

    }

    override fun didReadDescriptor(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristicDescriptor: BluetoothCharacteristicDescriptor
    ) {

    }

    override fun didRssiUpdate(bluetoothPeripheral: BluetoothPeripheral) {
    }

    override fun didUpdateMTU(bluetoothPeripheral: BluetoothPeripheral, status: Int) {

    }

    override fun didWriteCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        success: Boolean
    ) {

    }

    override fun didWriteDescriptor(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristicDescriptor: BluetoothCharacteristicDescriptor
    ) {

    }
}