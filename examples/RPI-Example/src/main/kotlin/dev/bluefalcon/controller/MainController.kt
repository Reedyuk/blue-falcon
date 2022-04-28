package dev.bluefalcon.controller

import dev.bluefalcon.*
import javafx.collections.FXCollections
import javafx.collections.ObservableList
import tornadofx.*

class MainController: Controller(), BlueFalconDelegate {
    val title="BlueFalcon"
    val minWidth=600.px
    val minHeight=480.px

    val devices: ObservableList<BluetoothPeripheral> = FXCollections.observableArrayList()

    private val blueFalcon = BlueFalcon(ApplicationContext(), null)

    init {
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
        devices.remove(bluetoothPeripheral)
    }

    override fun didDiscoverCharacteristics(bluetoothPeripheral: BluetoothPeripheral) {
        println("didDiscoverCharacteristics ${bluetoothPeripheral.name}")
        bluetoothPeripheral.deviceServices.forEach { service ->
            service.characteristics.forEach {
                blueFalcon.readCharacteristic(bluetoothPeripheral, it)
            }
        }
    }

    override fun didDiscoverDevice(bluetoothPeripheral: BluetoothPeripheral, advertisementData: [AdvertisementDataRetrievalKeys : Any]) {
        println("didDiscoverDevice ${bluetoothPeripheral.name}")
        devices.add(bluetoothPeripheral)
        blueFalcon.connect(bluetoothPeripheral)
    }

    override fun didDiscoverServices(bluetoothPeripheral: BluetoothPeripheral) {
        println("didDiscoverServices ${bluetoothPeripheral.name}:${bluetoothPeripheral.deviceServices.size}}")
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
