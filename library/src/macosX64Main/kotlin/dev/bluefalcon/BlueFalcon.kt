package dev.bluefalcon

import platform.CoreBluetooth.*
import platform.Foundation.*
import platform.darwin.NSObject

actual class BlueFalcon actual constructor(
    private val context: ApplicationContext,
    private val serviceUUID: String?
) {
    actual val delegates: MutableSet<BlueFalconDelegate> = mutableSetOf()

    private val centralManager: CBCentralManager
    private val bluetoothPeripheralManager = BluetoothPeripheralManager()
    private val peripheralDelegate = PeripheralDelegate()
    actual var isScanning: Boolean = false

    init {
        centralManager = CBCentralManager(bluetoothPeripheralManager, null)
    }

    actual fun connect(bluetoothPeripheral: BluetoothPeripheral, autoConnect: Boolean) {
        //auto connect is ignored due to not needing it in macOS
        centralManager.connectPeripheral(bluetoothPeripheral.bluetoothDevice, null)
    }

    actual fun disconnect(bluetoothPeripheral: BluetoothPeripheral) {
        centralManager.cancelPeripheralConnection(bluetoothPeripheral.bluetoothDevice)
    }

//    @Throws
    actual fun scan() {
        isScanning = true
        when(centralManager.state) {
            CBManagerStateUnknown -> throw BluetoothUnknownException()
            CBManagerStateResetting -> throw BluetoothResettingException()
            CBManagerStateUnsupported -> throw BluetoothUnsupportedException()
            CBManagerStateUnauthorized -> throw BluetoothPermissionException()
            CBManagerStatePoweredOff -> throw BluetoothNotEnabledException()
            CBManagerStatePoweredOn -> {
                if (serviceUUID != null) {
                    centralManager.scanForPeripheralsWithServices(listOf(serviceUUID), null)
                } else {
                    centralManager.scanForPeripheralsWithServices(null, null)
                }
            }
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
        bluetoothPeripheral.bluetoothDevice.readValueForCharacteristic(bluetoothCharacteristic.characteristic)
    }

    actual fun notifyCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        notify: Boolean
    ) {
        bluetoothPeripheral.bluetoothDevice.setNotifyValue(notify, bluetoothCharacteristic.characteristic)
    }

    actual fun indicateCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        indicate: Boolean
    ) {
        notifyCharacteristic(bluetoothPeripheral, bluetoothCharacteristic, indicate)
    }

    actual fun notifyAndIndicateCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        enable: Boolean
    ) {
        notifyCharacteristic(bluetoothPeripheral, bluetoothCharacteristic, enable)
    }

    actual fun writeCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        value: String,
        writeType: Int?
    ) {
        sharedWriteCharacteristic(
            bluetoothPeripheral,
            bluetoothCharacteristic,
            NSString.create(string = value),
            writeType
        )
    }

    actual fun writeCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        value: ByteArray,
        writeType: Int?
    ){
        sharedWriteCharacteristic(
            bluetoothPeripheral,
            bluetoothCharacteristic,
            NSString.create(string = value.decodeToString()),
            writeType
        )
    }

    private fun sharedWriteCharacteristic(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        value: NSString,
        writeType: Int?
    ) {
        value.dataUsingEncoding(NSUTF8StringEncoding)?.let {
            bluetoothPeripheral.bluetoothDevice.writeValue(
                it,
                bluetoothCharacteristic.characteristic,
                when (writeType) {
                    1 -> CBCharacteristicWriteWithoutResponse
                    else -> CBCharacteristicWriteWithResponse
                }
            )
        }
    }

    actual fun readDescriptor(
        bluetoothPeripheral: BluetoothPeripheral,
        bluetoothCharacteristic: BluetoothCharacteristic,
        bluetoothCharacteristicDescriptor: BluetoothCharacteristicDescriptor
    ) {
        bluetoothPeripheral.bluetoothDevice.discoverDescriptorsForCharacteristic(bluetoothCharacteristic.characteristic)
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
                val device = BluetoothPeripheral(didDiscoverPeripheral, rssiValue = RSSI.floatValue)
                delegates.forEach {
                    it.didDiscoverDevice(device)
                }
            }
        }

        override fun centralManager(central: CBCentralManager, didConnectPeripheral: CBPeripheral) {
            log("DidConnectPeripheral ${didConnectPeripheral.name}")
            val device = BluetoothPeripheral(didConnectPeripheral, rssiValue = null)
            delegates.forEach {
                it.didConnect(device)
            }
            didConnectPeripheral.delegate = peripheralDelegate
            didConnectPeripheral.discoverServices(null)
        }

        override fun centralManager(central: CBCentralManager, didDisconnectPeripheral: CBPeripheral, error: NSError?) {
            log("DidDisconnectPeripheral ${didDisconnectPeripheral.name}")
            val device = BluetoothPeripheral(didDisconnectPeripheral, rssiValue = null)
            delegates.forEach {
                it.didDisconnect(device)
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
                val device = BluetoothPeripheral(peripheral, rssiValue = null)
                delegates.forEach {
                    it.didDiscoverServices(device)
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
            val device = BluetoothPeripheral(peripheral, rssiValue = null)
            delegates.forEach {
                it.didDiscoverCharacteristics(device)
            }
            BluetoothService(didDiscoverCharacteristicsForService).characteristics.forEach {
                peripheral.discoverDescriptorsForCharacteristic(it.characteristic)
            }
        }

        @Suppress("CONFLICTING_OVERLOADS")
        override fun peripheral(
            peripheral: CBPeripheral,
            didUpdateValueForCharacteristic: CBCharacteristic,
            error: NSError?
        ) {
            if (error != null) {
                println("Error with characteristic update ${error}")
            }
            println("didUpdateValueForCharacteristic")
            val device = BluetoothPeripheral(peripheral, rssiValue = null)
            val characteristic = BluetoothCharacteristic(didUpdateValueForCharacteristic)
            delegates.forEach {
                it.didCharacteristcValueChanged(
                    device,
                    characteristic
                )
            }
        }

        override fun peripheral(peripheral: CBPeripheral, didUpdateValueForDescriptor: CBDescriptor, error: NSError?) {
            println("didUpdateValueForDescriptor ${didUpdateValueForDescriptor.value}")
        }

        @Suppress("CONFLICTING_OVERLOADS")
        override fun peripheral(peripheral: CBPeripheral, didDiscoverDescriptorsForCharacteristic: CBCharacteristic, error: NSError?) {
            println("didDiscoverDescriptorsForCharacteristic")
        }

    }

}
