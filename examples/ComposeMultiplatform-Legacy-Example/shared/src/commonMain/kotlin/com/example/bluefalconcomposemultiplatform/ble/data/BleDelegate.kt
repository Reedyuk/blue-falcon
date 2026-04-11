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
    override fun didCharacteristcValueChanged(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic
    ) {
        onDeviceEvent?.invoke(
            DeviceEvent.OnCharacteristicValueChanged(
                bluetoothPeripheral.uuid,
                bluetoothCharacteristic
            )
        )
    }

    override fun didConnect(bluetoothPeripheral: BluetoothPeripheral) {
        onDeviceEvent?.invoke(DeviceEvent.OnDeviceConnected(bluetoothPeripheral.uuid))
    }

    override fun didDisconnect(bluetoothPeripheral: BluetoothPeripheral) {
        onDeviceEvent?.invoke(DeviceEvent.OnDeviceDisconnected(bluetoothPeripheral.uuid))
    }

    override fun didDiscoverCharacteristics(bluetoothPeripheral: BluetoothPeripheral) {
        onDeviceEvent?.invoke(
            DeviceEvent.OnServicesDiscovered(bluetoothPeripheral.uuid, bluetoothPeripheral)
        )
    }

    override fun didDiscoverServices(bluetoothPeripheral: BluetoothPeripheral) {
        onDeviceEvent?.invoke(
            DeviceEvent.OnServicesDiscovered(bluetoothPeripheral.uuid, bluetoothPeripheral)
        )
    }

    override fun didReadDescriptor(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristicDescriptor: BluetoothCharacteristicDescriptor
    ) {
        onDeviceEvent?.invoke(
            DeviceEvent.OnDescriptorRead(
                bluetoothPeripheral.uuid,
                bluetoothCharacteristicDescriptor
            )
        )
    }

    override fun didRssiUpdate(bluetoothPeripheral: BluetoothPeripheral) {
        onDeviceEvent?.invoke(DeviceEvent.OnRssiUpdated(bluetoothPeripheral.uuid))
    }

    override fun didUpdateMTU(bluetoothPeripheral: BluetoothPeripheral, status: Int) {
        onDeviceEvent?.invoke(DeviceEvent.OnMtuUpdated(bluetoothPeripheral.uuid, status))
    }

    override fun didWriteCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        success: Boolean
    ) {
        onDeviceEvent?.invoke(
            DeviceEvent.OnWriteCharacteristicResult(
                bluetoothPeripheral.uuid,
                bluetoothCharacteristic,
                success
            )
        )
    }

    override fun didWriteDescriptor(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristicDescriptor: BluetoothCharacteristicDescriptor
    ) {

    }

}