package dev.bluefalcon.viewModels

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import dev.bluefalcon.BlueFalconApplication
import dev.bluefalcon.BlueFalconDelegate
import dev.bluefalcon.BluetoothCharacteristic
import dev.bluefalcon.BluetoothPeripheral
import dev.bluefalcon.adapters.DeviceServiceAdapter
import dev.bluefalcon.views.DeviceServiceActivityUI

class DeviceServiceViewModel(
    val service: BluetoothGattService
) : BlueFalconDelegate {

    val deviceServiceActivityUI = DeviceServiceActivityUI(this)
    val deviceServiceAdapter = DeviceServiceAdapter(this)
    val characteristics: List<BluetoothGattCharacteristic> get() = service.characteristics

    init {
        BlueFalconApplication.instance.blueFalcon.delegates.add(this)
    }

    override fun didDiscoverDevice(bluetoothPeripheral: BluetoothPeripheral) {}

    override fun didConnect(bluetoothPeripheral: BluetoothPeripheral) {}

    override fun didDisconnect(bluetoothPeripheral: BluetoothPeripheral) {}

    override fun didDiscoverServices(bluetoothPeripheral: BluetoothPeripheral) {}

    override fun didDiscoverCharacteristics(bluetoothPeripheral: BluetoothPeripheral) {}

    override fun didCharacteristcValueChanged(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic
    ) {}
}