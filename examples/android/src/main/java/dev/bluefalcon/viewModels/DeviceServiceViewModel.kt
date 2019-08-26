package dev.bluefalcon.viewModels

import android.bluetooth.BluetoothGattService
import dev.bluefalcon.BlueFalconApplication
import dev.bluefalcon.BlueFalconDelegate
import dev.bluefalcon.BluetoothPeripheral
import dev.bluefalcon.activities.DeviceServiceActivity
import dev.bluefalcon.adapters.DeviceServiceAdapter
import dev.bluefalcon.views.DeviceServiceActivityUI

class DeviceServiceViewModel(
    val deviceServiceActivity: DeviceServiceActivity,
    val service: BluetoothGattService,
    val bluetoothPeripheral: BluetoothPeripheral
) : BlueFalconDelegate {

    val deviceServiceActivityUI = DeviceServiceActivityUI(this)
    val deviceServiceAdapter = DeviceServiceAdapter(this)

    init {
        BlueFalconApplication.instance.blueFalcon.delegates.add(this)
    }

    override fun didDiscoverDevice(bluetoothPeripheral: BluetoothPeripheral) {}

    override fun didConnect(bluetoothPeripheral: BluetoothPeripheral) {}

    override fun didDisconnect(bluetoothPeripheral: BluetoothPeripheral) {}

    override fun didDiscoverServices(bluetoothPeripheral: BluetoothPeripheral) {}

    override fun didDiscoverCharacteristics(bluetoothPeripheral: BluetoothPeripheral) {}
}