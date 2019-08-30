package dev.bluefalcon

import platform.CoreBluetooth.*
import platform.Foundation.*
import platform.darwin.NSObject

actual class BlueFalcon {

    actual val delegates: MutableList<BlueFalconDelegate> = arrayListOf()

    private val centralManager: CBCentralManager
    private val bluetoothPeripheralManager = BluetoothPeripheralManager()
    private val peripheralDelegate = PeripheralDelegate()

    init {
        centralManager = CBCentralManager(bluetoothPeripheralManager, null)
    }

    actual fun connect(bluetoothPeripheral: BluetoothPeripheral) {
        centralManager.connectPeripheral(bluetoothPeripheral, null)
    }

    actual fun disconnect(bluetoothPeripheral: BluetoothPeripheral) {
        centralManager.cancelPeripheralConnection(bluetoothPeripheral)
    }

    actual fun scan() {
        centralManager.scanForPeripheralsWithServices(null, null)
    }

    actual fun readCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic
    ) {
        bluetoothPeripheral.readValueForCharacteristic(bluetoothCharacteristic)
    }

    actual fun notifyCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        notify: Boolean
    ) {
        bluetoothPeripheral.setNotifyValue(notify, bluetoothCharacteristic)
    }

    actual fun writeCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        value: String
    ) {
        val formattedString = NSString.create(string = value)
        formattedString.dataUsingEncoding(NSUTF8StringEncoding)?.let {
            bluetoothPeripheral.writeValue(
                it,
                bluetoothCharacteristic,
                CBCharacteristicWriteWithResponse
            )
        }
    }

    inner class BluetoothPeripheralManager: NSObject(), CBCentralManagerDelegateProtocol {
        override fun centralManagerDidUpdateState(central: CBCentralManager) {
            when (central.state.toInt()) {
                0 -> log("State 0 is .unknown")
                1 -> log("State 1 is .resetting")
                2 -> log("State 2 is .unsupported")
                3 -> log("State 3 is .unauthorised")
                4 -> log("State 4 is .poweredOff")
                5 -> log("State 5 is .poweredOn")
                else -> log("State ${central.state.toInt()}")
            }
        }

        override fun centralManager(
            central: CBCentralManager,
            didDiscoverPeripheral: CBPeripheral,
            advertisementData: Map<Any?, *>,
            RSSI: NSNumber
        ) {
            log("Discovered device ${didDiscoverPeripheral.name}")
            delegates.forEach {
                it.didDiscoverDevice(didDiscoverPeripheral)
            }
        }

        override fun centralManager(central: CBCentralManager, didConnectPeripheral: CBPeripheral) {
            log("DidConnectPeripheral ${didConnectPeripheral.name}")
            delegates.forEach {
                it.didConnect(didConnectPeripheral)
            }
            didConnectPeripheral.delegate = peripheralDelegate
            didConnectPeripheral.discoverServices(null)
        }

        override fun centralManager(central: CBCentralManager, didDisconnectPeripheral: CBPeripheral, error: NSError?) {
            log("DidDisconnectPeripheral ${didDisconnectPeripheral.name}")
            delegates.forEach {
                it.didDisconnect(didDisconnectPeripheral)
            }
        }

    }

    inner class PeripheralDelegate: NSObject(), CBPeripheralDelegateProtocol {

        override fun peripheral(
            peripheral: CBPeripheral,
            didDiscoverServices: NSError?
        ) {
            if (didDiscoverServices != null) {
                println("Error with service discovery ${didDiscoverServices}")
            } else {
                delegates.forEach {
                    it.didDiscoverServices(peripheral)
                }
                peripheral.services
                    ?.mapNotNull { it as? CBService }
                    ?.forEach {
                        peripheral.discoverCharacteristics(null, it)
                    }
            }
        }

        override fun peripheral(
            peripheral: CBPeripheral,
            didDiscoverCharacteristicsForService: CBService,
            error: NSError?
        ) {
            if (error != null) {
                println("Error with characteristic discovery ${didDiscoverCharacteristicsForService}")
            } else {
                delegates.forEach {
                    it.didDiscoverCharacteristics(peripheral)
                }
            }
        }

        override fun peripheral(
            peripheral: CBPeripheral,
            didUpdateValueForCharacteristic: CBCharacteristic,
            error: NSError?
        ) {
            println("didUpdateValueForCharacteristic")
            delegates.forEach {
                it.didCharacteristcValueChanged(
                    peripheral,
                    didUpdateValueForCharacteristic
                )
            }
        }
    }

}
