package sample

import dev.bluefalcon.BlueFalcon
import dev.bluefalcon.BlueFalconDelegate
import dev.bluefalcon.BluetoothCharacteristic
import dev.bluefalcon.BluetoothPeripheral

class BluetoothService(private val blueFalcon: BlueFalcon) {

    private val bluetoothDelegate = BluetoothDelegate()

    init {
        blueFalcon.delegates.add(bluetoothDelegate)
    }

    fun scan() {
        blueFalcon.scan()
    }

    fun connect(bluetoothPeripheral: BluetoothPeripheral) {
        blueFalcon.connect(bluetoothPeripheral)
    }

    fun disconnect(bluetoothPeripheral: BluetoothPeripheral) {
        blueFalcon.disconnect(bluetoothPeripheral)
    }

    fun readCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic
    ) {
        blueFalcon.readCharacteristic(bluetoothPeripheral, bluetoothCharacteristic)
    }

    fun notifyCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        notify: Boolean
    ) {
        blueFalcon.notifyCharacteristic(bluetoothPeripheral, bluetoothCharacteristic, notify)
    }

    fun writeCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        value: String
    ) {
        blueFalcon.writeCharacteristic(bluetoothPeripheral, bluetoothCharacteristic, value)
    }

    internal class BluetoothDelegate: BlueFalconDelegate {

        override fun didDiscoverDevice(bluetoothPeripheral: BluetoothPeripheral) {
            println("didDiscoverDevice")
        }

        override fun didConnect(bluetoothPeripheral: BluetoothPeripheral) {
            println("didConnect")
        }

        override fun didDiscoverServices(bluetoothPeripheral: BluetoothPeripheral) {
            println("didDiscoverServices")
        }

        override fun didCharacteristcValueChanged(
            bluetoothPeripheral: BluetoothPeripheral,
            bluetoothCharacteristic: BluetoothCharacteristic
        ) {
            println("didCharacteristcValueChanged")
        }

        override fun didDisconnect(bluetoothPeripheral: BluetoothPeripheral) {
            println("didDisconnect")
        }

        override fun didDiscoverCharacteristics(bluetoothPeripheral: BluetoothPeripheral) {
            println("didDiscoverCharacteristics")
        }

        override fun didUpdateMTU(bluetoothPeripheral: BluetoothPeripheral) {
            println("didUpdateMTU")
        }

    }
}