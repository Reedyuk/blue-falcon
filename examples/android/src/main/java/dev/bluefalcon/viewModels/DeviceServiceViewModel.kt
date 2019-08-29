package dev.bluefalcon.viewModels

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import dev.bluefalcon.*
import dev.bluefalcon.adapters.DeviceServiceAdapter
import dev.bluefalcon.views.DeviceServiceActivityUI

class DeviceServiceViewModel(
    val device: BluetoothPeripheral,
    val service: BluetoothGattService
) : BlueFalconDelegate {

    val deviceServiceActivityUI = DeviceServiceActivityUI(this)
    val deviceServiceAdapter = DeviceServiceAdapter(this)
    val characteristics: List<BluetoothGattCharacteristic> get() = service.characteristics
    private var notify = false

    //TODO: Need to have a charactristic view model.

    init {
        BlueFalconApplication.instance.blueFalcon.delegates.add(this)
    }

    fun readCharacteristicTapped(characteristic: BluetoothGattCharacteristic) {
        BlueFalconApplication.instance.blueFalcon.readCharacteristic(
            device,
            characteristic
        )
    }

    fun notifyCharacteristicTapped(characteristic: BluetoothGattCharacteristic) {
        notify = !notify
        BlueFalconApplication.instance.blueFalcon.notifyCharacteristic(
            device,
            characteristic,
            notify
        )
    }

    fun writeCharactersticTapped(characteristic: BluetoothGattCharacteristic) {
        BlueFalconApplication.instance.blueFalcon.writeCharacteristic(
            device,
            characteristic,
            "1"
        )
    }

    override fun didDiscoverDevice(bluetoothPeripheral: BluetoothPeripheral) {}

    override fun didConnect(bluetoothPeripheral: BluetoothPeripheral) {}

    override fun didDisconnect(bluetoothPeripheral: BluetoothPeripheral) {}

    override fun didDiscoverServices(bluetoothPeripheral: BluetoothPeripheral) {}

    override fun didDiscoverCharacteristics(bluetoothPeripheral: BluetoothPeripheral) {}

    override fun didCharacteristcValueChanged(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic
    ) {
        log("didCharacteristcValueChanged ${bluetoothCharacteristic.value}")

    }
}