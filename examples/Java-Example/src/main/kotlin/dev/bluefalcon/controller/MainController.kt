package dev.bluefalcon.controller

import dev.bluefalcon.*
import tornadofx.*

class MainController: Controller(), BlueFalconDelegate {
    val title="BlueFalcon"
    val minWidth=600.px
    val minHeight=480.px

    init {
        val blueFalcon = BlueFalcon(ApplicationContext(), null)
        blueFalcon.delegates.add(this)
        blueFalcon.scan()
    }

    override fun didCharacteristcValueChanged(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic
    ) {
        TODO("Not yet implemented")
    }

    override fun didConnect(bluetoothPeripheral: BluetoothPeripheral) {
        TODO("Not yet implemented")
    }

    override fun didDisconnect(bluetoothPeripheral: BluetoothPeripheral) {
        TODO("Not yet implemented")
    }

    override fun didDiscoverCharacteristics(bluetoothPeripheral: BluetoothPeripheral) {
        TODO("Not yet implemented")
    }

    override fun didDiscoverDevice(bluetoothPeripheral: BluetoothPeripheral) {
        TODO("Not yet implemented")
    }

    override fun didDiscoverServices(bluetoothPeripheral: BluetoothPeripheral) {
        TODO("Not yet implemented")
    }

    override fun didReadDescriptor(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristicDescriptor: BluetoothCharacteristicDescriptor
    ) {
        TODO("Not yet implemented")
    }

    override fun didRssiUpdate(bluetoothPeripheral: BluetoothPeripheral) {
        TODO("Not yet implemented")
    }

    override fun didUpdateMTU(bluetoothPeripheral: BluetoothPeripheral) {
        TODO("Not yet implemented")
    }
}
