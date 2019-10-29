package dev.bluefalcon

expect class BlueFalcon(context: ApplicationContext, serviceUUID: String?) {

    val delegates: MutableSet<BlueFalconDelegate>
    var isScanning: Boolean

    fun connect(bluetoothPeripheral: BluetoothPeripheral)
    fun disconnect(bluetoothPeripheral: BluetoothPeripheral)

    fun prepareForScan(completion: (() -> Unit))
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
        value: String
    )

    fun changeMTU(bluetoothPeripheral: BluetoothPeripheral, mtuSize: Int)

}
