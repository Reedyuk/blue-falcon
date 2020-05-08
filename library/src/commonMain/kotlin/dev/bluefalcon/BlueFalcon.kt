package dev.bluefalcon

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BroadcastChannel
import kotlinx.coroutines.flow.Flow

expect class BlueFalcon(context: ApplicationContext, serviceUUID: String?) {

    val delegates: MutableSet<BlueFalconDelegate>
    var isScanning: Boolean

    @ExperimentalCoroutinesApi
    val discoveredDeviceChannel: BroadcastChannel<BluetoothPeripheral>
    val discoveredDevice: Flow<BluetoothPeripheral>

    @ExperimentalCoroutinesApi
    val connectedDeviceChannel: BroadcastChannel<BluetoothPeripheral>
    val connectedDevice: Flow<BluetoothPeripheral>

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

    fun indicateCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        indicate: Boolean
    )

    fun notifyAndIndicateCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        enable: Boolean
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
