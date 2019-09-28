package dev.bluefalcon.services

import dev.bluefalcon.*
import java.util.*
import android.os.Build
import androidx.annotation.RequiresApi


class BluetoothService: BlueFalconDelegate {

    private val blueFalcon = BlueFalcon(BlueFalconApplication.instance)

    private val devices: MutableList<BluetoothPeripheral> = mutableListOf()
    val detectedDeviceDelegates: MutableList<BluetoothServiceDetectedDeviceDelegate> = mutableListOf()
    val connectedDeviceDelegates: MutableMap<String, BluetoothServiceConnectedDeviceDelegate> = mutableMapOf()
    val characteristicDelegates: MutableMap<UUID, BluetoothServiceCharacteristicDelegate> = mutableMapOf()

    init {
        blueFalcon.delegates.add(this)
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

    @RequiresApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
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

    override fun didDiscoverDevice(bluetoothPeripheral: BluetoothPeripheral) {
        if (devices.firstOrNull {
            it.bluetoothDevice.address == bluetoothPeripheral.bluetoothDevice.address
        } == null) {
            devices.add(bluetoothPeripheral)
            detectedDeviceDelegates.forEach { delegate ->
                delegate.discoveredDevice(devices)
            }
        }
    }

    override fun didConnect(bluetoothPeripheral: BluetoothPeripheral) {
        connectedDeviceDelegates[bluetoothPeripheral.bluetoothDevice.address]?.connectedDevice()
    }

    override fun didDiscoverServices(bluetoothPeripheral: BluetoothPeripheral) {
        connectedDeviceDelegates[bluetoothPeripheral.bluetoothDevice.address]?.discoveredServices(bluetoothPeripheral)
    }

    override fun didCharacteristcValueChanged(bluetoothPeripheral: BluetoothPeripheral, bluetoothCharacteristic: BluetoothCharacteristic) {
        characteristicDelegates[bluetoothCharacteristic.uuid]?.characteristcValueChanged(bluetoothCharacteristic)
    }

    override fun didDisconnect(bluetoothPeripheral: BluetoothPeripheral) {}

    override fun didDiscoverCharacteristics(bluetoothPeripheral: BluetoothPeripheral) {}

}

interface BluetoothServiceDetectedDeviceDelegate {
    fun discoveredDevice(devices: List<BluetoothPeripheral>)
}

interface BluetoothServiceConnectedDeviceDelegate {
    fun connectedDevice()
    fun discoveredServices(bluetoothPeripheral: BluetoothPeripheral)
}

interface BluetoothServiceCharacteristicDelegate {
    fun characteristcValueChanged(bluetoothCharacteristic: BluetoothCharacteristic)
}
