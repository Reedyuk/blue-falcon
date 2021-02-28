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
) {

    val deviceServiceActivityUI = DeviceServiceActivityUI(this)
    val deviceServiceAdapter: DeviceServiceAdapter
    private val characteristics: List<BluetoothGattCharacteristic> get() = service.characteristics

    init {
        deviceServiceAdapter = DeviceServiceAdapter(createCharacteristicViewModels())
    }

    fun notifyValueChanged() {
        deviceServiceActivity.runOnUiThread {
            deviceServiceAdapter.notifyDataSetChanged()
        }
    }

    private fun createCharacteristicViewModels() = characteristics.map { characteristic ->
        DeviceCharacteristicViewModel(
            deviceServiceActivity,
            this,
            device,
            BluetoothCharacteristic(characteristic)
        )
    }
}