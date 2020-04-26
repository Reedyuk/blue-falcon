package dev.bluefalcon

expect class BlueFalcon(context: ApplicationContext, serviceUUID: String?) {

    val delegates: MutableSet<BlueFalconDelegate>
    var isScanning: Boolean

    fun connect(bluetoothPeripheral: BluetoothPeripheral)
    fun disconnect(bluetoothPeripheral: BluetoothPeripheral)

    fun scan()
    fun stopScanning()

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
        value: String,
        writeType: Int?
    )

    fun readDescriptor(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        bluetoothCharacteristicDescriptor: BluetoothCharacteristicDescriptor
    )

    fun changeMTU(bluetoothPeripheral: BluetoothPeripheral, mtuSize: Int)

}
