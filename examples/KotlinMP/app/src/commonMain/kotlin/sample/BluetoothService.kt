package sample

class BluetoothService {

    private val blueFalcon = BlueFalcon(null)
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

        override fun didDiscoverDevice(bluetoothPeripheral: BluetoothPeripheral) {}

        override fun didConnect(bluetoothPeripheral: BluetoothPeripheral) {}

        override fun didDiscoverServices(bluetoothPeripheral: BluetoothPeripheral) {}

        override fun didCharacteristcValueChanged(
            bluetoothPeripheral: BluetoothPeripheral,
            bluetoothCharacteristic: BluetoothCharacteristic
        ) {}

        override fun didDisconnect(bluetoothPeripheral: BluetoothPeripheral) {}

        override fun didDiscoverCharacteristics(bluetoothPeripheral: BluetoothPeripheral) {}

        override fun didUpdateMTU(bluetoothPeripheral: BluetoothPeripheral) {}

    }
}