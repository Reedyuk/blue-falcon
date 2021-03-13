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
        println("didCharacteristcValueChanged")
    }

    override fun didConnect(bluetoothPeripheral: BluetoothPeripheral) {
        println("didConnect ${bluetoothPeripheral.name}")
    }

    override fun didDisconnect(bluetoothPeripheral: BluetoothPeripheral) {
        println("didDisconnect ${bluetoothPeripheral.name}")
    }

    override fun didDiscoverCharacteristics(bluetoothPeripheral: BluetoothPeripheral) {
        println("didDiscoverCharacteristics ${bluetoothPeripheral.name}")
    }

    override fun didDiscoverDevice(bluetoothPeripheral: BluetoothPeripheral) {
        println("didDiscoverDevice ${bluetoothPeripheral.name}")
    }

    override fun didDiscoverServices(bluetoothPeripheral: BluetoothPeripheral) {
        println("didDiscoverServices ${bluetoothPeripheral.name}")
    }

    override fun didReadDescriptor(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristicDescriptor: BluetoothCharacteristicDescriptor
    ) {
        println("didReadDescriptor ${bluetoothPeripheral.name}")
    }

    override fun didRssiUpdate(bluetoothPeripheral: BluetoothPeripheral) {
        println("didRssiUpdate ${bluetoothPeripheral.name}")
    }

    override fun didUpdateMTU(bluetoothPeripheral: BluetoothPeripheral) {
        println("didUpdateMTU ${bluetoothPeripheral.name}")
    }
}
