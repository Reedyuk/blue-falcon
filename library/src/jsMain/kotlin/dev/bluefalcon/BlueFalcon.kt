package dev.bluefalcon

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BroadcastChannel
import kotlinx.coroutines.flow.Flow

actual class BlueFalcon actual constructor(context: ApplicationContext, serviceUUID: String?) {

    actual val delegates: MutableSet<BlueFalconDelegate> = mutableSetOf()
    actual var isScanning: Boolean = false

    @ExperimentalCoroutinesApi
    actual val discoveredDeviceChannel: BroadcastChannel<BluetoothPeripheral>
        get() = TODO("Not yet implemented")
    actual val discoveredDevice: Flow<BluetoothPeripheral>
        get() = TODO("Not yet implemented")

    @ExperimentalCoroutinesApi
    actual val connectedDeviceChannel: BroadcastChannel<BluetoothPeripheral>
        get() = TODO("Not yet implemented")
    actual val connectedDevice: Flow<BluetoothPeripheral>
        get() = TODO("Not yet implemented")

    actual fun connect(bluetoothPeripheral: BluetoothPeripheral) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    actual fun disconnect(bluetoothPeripheral: BluetoothPeripheral) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    actual fun stopScanning() {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    actual fun scan() {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    actual fun readCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic
    ) {
        TODO("not implemented")
    }

    actual fun writeCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        value: String,
        writeType: Int?
    ) {
        TODO("not implemented")
    }

    actual fun notifyCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        notify: Boolean
    ) {
        TODO("not implemented")
    }

    actual fun indicateCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        indicate: Boolean
    ) {
        TODO("not implemented")
    }

    actual fun notifyAndIndicateCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        enable: Boolean
    ) {
        TODO("not implemented")
    }

    actual fun readDescriptor(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        bluetoothCharacteristicDescriptor: BluetoothCharacteristicDescriptor
    ) {
        TODO("not implemented")
    }

    actual fun changeMTU(bluetoothPeripheral: BluetoothPeripheral, mtuSize: Int) {
        TODO("not implemented")
    }

}