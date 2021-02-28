package sample

import dev.bluefalcon.*

expect fun BluetoothService.scan()

class BluetoothService(private val blueFalcon: BlueFalcon) {

    private val bluetoothDelegate = BluetoothDelegate()

    init {
        blueFalcon.delegates.add(bluetoothDelegate)
    }

    fun addDevicesDelegate(devicesDelegate: DevicesDelegate) {
        bluetoothDelegate.deviceDelegate = devicesDelegate
    }

    fun addDeviceConnectDelegate(deviceConnectDelegate: DeviceConnectDelegate) {
        bluetoothDelegate.deviceConnectDelegate = deviceConnectDelegate
    }

    fun addDeviceCharacteristicDelegate(deviceCharacteristicDelegate: DeviceCharacteristicDelegate) {
        bluetoothDelegate.deviceCharacteristicDelegate = deviceCharacteristicDelegate
    }

    internal fun performScan() {
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
        bluetoothCharacteristic.descriptors.forEach { descriptor ->
            blueFalcon.readDescriptor(
                bluetoothPeripheral,
                bluetoothCharacteristic,
                descriptor
            )
        }
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
        blueFalcon.writeCharacteristic(bluetoothPeripheral, bluetoothCharacteristic, value, null)
    }

    internal class BluetoothDelegate: BlueFalconDelegate {

        private val devices: MutableList<BluetoothPeripheral> = mutableListOf()
        var deviceDelegate: DevicesDelegate? = null
        var deviceConnectDelegate: DeviceConnectDelegate? = null
        var deviceCharacteristicDelegate: DeviceCharacteristicDelegate? = null

        override fun didDiscoverDevice(bluetoothPeripheral: BluetoothPeripheral) {
            println("didDiscoverDevice")
            if (devices.firstOrNull { device -> device.uuid == bluetoothPeripheral.uuid } == null) {
                devices.add(bluetoothPeripheral)
                deviceDelegate?.didDiscoverDevice(bluetoothPeripheral)
            }
        }

        override fun didConnect(bluetoothPeripheral: BluetoothPeripheral) {
            println("didConnect")
            deviceConnectDelegate?.didDeviceConnect(bluetoothPeripheral)
        }

        override fun didDiscoverServices(bluetoothPeripheral: BluetoothPeripheral) {
            println("didDiscoverServices")
            deviceConnectDelegate?.didDiscoverServices(bluetoothPeripheral)
        }

        override fun didReadDescriptor(
            bluetoothPeripheral: BluetoothPeripheral,
            bluetoothCharacteristicDescriptor: BluetoothCharacteristicDescriptor
        ) {
            println("read descriptor ${bluetoothCharacteristicDescriptor}")
        }

        override fun didCharacteristcValueChanged(
            bluetoothPeripheral: BluetoothPeripheral,
            bluetoothCharacteristic: BluetoothCharacteristic
        ) {
            println("didCharacteristcValueChanged ${bluetoothCharacteristic.value}")
            bluetoothCharacteristic.value?.let {
                deviceCharacteristicDelegate?.didCharacteristcValueChanged(it.toString())
            }
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

        override fun didRssiUpdate(bluetoothPeripheral: BluetoothPeripheral) {
            println("didRssiUpdate")
            deviceConnectDelegate?.didRssiChange(bluetoothPeripheral)
        }

    }
}

interface DevicesDelegate {
    fun didDiscoverDevice(bluetoothPeripheral: BluetoothPeripheral)
}

interface DeviceConnectDelegate {
    fun didDeviceConnect(bluetoothPeripheral: BluetoothPeripheral)
    fun didDiscoverServices(bluetoothPeripheral: BluetoothPeripheral)
    fun didRssiChange(bluetoothPeripheral: BluetoothPeripheral)
}

interface DeviceCharacteristicDelegate {
    fun didCharacteristcValueChanged(value: String)
}
