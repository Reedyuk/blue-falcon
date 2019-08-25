package dev.bluefalcon.viewModels

import android.bluetooth.BluetoothGattService
import dev.bluefalcon.BlueFalconApplication
import dev.bluefalcon.BlueFalconDelegate
import dev.bluefalcon.BluetoothPeripheral
import dev.bluefalcon.adapters.DeviceAdapter
import dev.bluefalcon.views.DeviceActivityUI

class DeviceViewModel(
    val bluetoothPeripheral: BluetoothPeripheral
) : BlueFalconDelegate {

    val deviceActivityUI = DeviceActivityUI(this)
    val services: List<BluetoothGattService> get() = bluetoothPeripheral.services
    val deviceAdapter = DeviceAdapter(this)
    var isConnected = false

    init {
        //need to move bluefalcon to a singleton?
        BlueFalconApplication.instance.blueFalcon.delegates.add(this)
        BlueFalconApplication.instance.blueFalcon.connect(bluetoothPeripheral)
    }

    override fun didDiscoverDevice(bluetoothPeripheral: BluetoothPeripheral) {}

    override fun didConnect(bluetoothPeripheral: BluetoothPeripheral) {
        println("Connected")
        isConnected = true
    }

    override fun didDisconnect(bluetoothPeripheral: BluetoothPeripheral) {}

    override fun didDiscoverServices(bluetoothPeripheral: BluetoothPeripheral) {
        //we need to get the bluetooth gatt?
        print("Services in view model ${bluetoothPeripheral.services}")
    }

    override fun didDiscoverCharacteristics(bluetoothPeripheral: BluetoothPeripheral) {}
}