package dev.bluefalcon

import platform.CoreBluetooth.*
import platform.Foundation.*
import platform.darwin.NSObject

actual class BlueFalcon actual constructor(serviceUUID: String?) {

    actual val delegates: MutableSet<BlueFalconDelegate> = mutableSetOf()

    private val centralManager: CBCentralManager
    private val bluetoothPeripheralManager = BluetoothPeripheralManager()
    private val peripheralDelegate = PeripheralDelegate()
    actual var isScanning: Boolean = false

    init {
        centralManager = CBCentralManager(bluetoothPeripheralManager, null)
    }

    actual fun connect(bluetoothPeripheral: BluetoothPeripheral) {
        centralManager.connectPeripheral(bluetoothPeripheral, null)
    }

    actual fun disconnect(bluetoothPeripheral: BluetoothPeripheral) {
        centralManager.cancelPeripheralConnection(bluetoothPeripheral)
    }

    @Throws
    actual fun scan() {
        isScanning = true
        when(centralManager.state) {
            CBManagerStateUnknown -> throw BluetoothUnknownException()
            CBManagerStateResetting -> throw BluetoothResettingException()
            CBManagerStateUnsupported -> throw BluetoothUnsupportedException()
            CBManagerStateUnauthorized -> throw BluetoothPermissionException()
            CBManagerStatePoweredOff -> throw BluetoothNotEnabledException()
            CBManagerStatePoweredOn -> centralManager.scanForPeripheralsWithServices(null, null)
        }
    }

    actual fun stopScanning() {
        isScanning = false
        centralManager.stopScan()
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

    actual fun changeMTU(bluetoothPeripheral: BluetoothPeripheral, mtuSize: Int) {
        println("Change MTU size called but not needed.")
        delegates.forEach {
            it.didUpdateMTU(bluetoothPeripheral)
        }
    }

    inner class BluetoothPeripheralManager: NSObject(), CBCentralManagerDelegateProtocol {
        override fun centralManagerDidUpdateState(central: CBCentralManager) {
            when (central.state) {
                CBManagerStateUnknown -> log("State 0 is .unknown")
                CBManagerStateResetting -> log("State 1 is .resetting")
                CBManagerStateUnsupported -> log("State 2 is .unsupported")
                CBManagerStateUnauthorized -> log("State 3 is .unauthorised")
                CBManagerStatePoweredOff -> log("State 4 is .poweredOff")
                CBManagerStatePoweredOn -> log("State 5 is .poweredOn")
                else -> log("State ${central.state.toInt()}")
            }
        }

        override fun centralManager(
            central: CBCentralManager,
            didDiscoverPeripheral: CBPeripheral,
            advertisementData: Map<Any?, *>,
            RSSI: NSNumber
        ) {
            if (isScanning) {
                log("Discovered device ${didDiscoverPeripheral.name}")
                delegates.forEach {
                    it.didDiscoverDevice(didDiscoverPeripheral)
                }
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
            }
            delegates.forEach {
                it.didDiscoverCharacteristics(peripheral)
            }
        }

        override fun peripheral(
            peripheral: CBPeripheral,
            didUpdateValueForCharacteristic: CBCharacteristic,
            error: NSError?
        ) {
            if (error != null) {
                println("Error with characteristic update ${error}")
            }
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
