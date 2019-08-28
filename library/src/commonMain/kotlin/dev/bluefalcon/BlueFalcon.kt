package dev.bluefalcon

expect class BlueFalcon {

    val delegates: MutableList<BlueFalconDelegate>

    fun connect(bluetoothPeripheral: BluetoothPeripheral)
    fun disconnect(bluetoothPeripheral: BluetoothPeripheral)
    fun scan()

    fun readCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic
    )

    fun notifyCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        notify: Boolean
    )

    fun writeCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        value: String
    )

}
