package dev.bluefalcon.viewModels

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import dev.bluefalcon.*
import dev.bluefalcon.activities.DeviceServiceActivity
import dev.bluefalcon.adapters.DeviceServiceAdapter
import dev.bluefalcon.views.DeviceServiceActivityUI

class DeviceServiceViewModel(
    private val deviceServiceActivity: DeviceServiceActivity,
    private val device: BluetoothPeripheral,
    val service: BluetoothGattService
) : BlueFalconDelegate {

    val deviceServiceActivityUI = DeviceServiceActivityUI(this)
    val deviceServiceAdapter: DeviceServiceAdapter
    private val characteristics: List<BluetoothGattCharacteristic> get() = service.characteristics

    init {
        BlueFalconApplication.instance.blueFalcon.delegates.add(this)
        deviceServiceAdapter = DeviceServiceAdapter(createCharacteristicViewModels())
    }

    fun notifyValueChanged() {
        deviceServiceActivity.runOnUiThread {
            deviceServiceAdapter.notifyDataSetChanged()
        }
    }

    private fun createCharacteristicViewModels() = characteristics.map { characteristic -> DeviceCharacteristicViewModel(deviceServiceActivity, this, device, characteristic) }

    override fun didDiscoverDevice(bluetoothPeripheral: BluetoothPeripheral) {}

    override fun didConnect(bluetoothPeripheral: BluetoothPeripheral) {}

    override fun didDisconnect(bluetoothPeripheral: BluetoothPeripheral) {}

    override fun didDiscoverServices(bluetoothPeripheral: BluetoothPeripheral) {}

    override fun didDiscoverCharacteristics(bluetoothPeripheral: BluetoothPeripheral) {}

    override fun didCharacteristcValueChanged(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic
    ) {
        deviceServiceActivity.runOnUiThread {
            deviceServiceAdapter.viewModels
                .filter { it.characteristic.uuid == bluetoothCharacteristic.uuid }
                .forEach { viewModel ->
                    if (viewModel.characteristic.uuid == bluetoothCharacteristic.uuid) {
                        viewModel.characteristic = bluetoothCharacteristic
                    }
            }
            deviceServiceAdapter.notifyDataSetChanged()
        }
    }
}