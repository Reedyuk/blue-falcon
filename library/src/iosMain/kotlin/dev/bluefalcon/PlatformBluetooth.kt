package dev.bluefalcon

import dev.bluefalcon.Bluetooth
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCentralManagerDelegateProtocol
import platform.CoreBluetooth.CBPeripheral
import platform.Foundation.NSError
import platform.Foundation.NSNumber
import platform.darwin.NSObject

actual class PlatformBluetooth : Bluetooth {

    private val centralManager: CBCentralManager
    private val bluetoothPeripheralManager = BluetoothPeripheralManager()

    init {
        centralManager = CBCentralManager(bluetoothPeripheralManager, null)
    }

    override fun connect() {
        //centralManager.connectPeripheral()
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun disconnect() {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun scan() {
        centralManager.scanForPeripheralsWithServices(null, null)
    }

    inner class BluetoothPeripheralManager: NSObject(), CBCentralManagerDelegateProtocol {
        override fun centralManagerDidUpdateState(central: CBCentralManager) {
            when (central.state.toInt()) {
                0 -> print("State 0 is .unknown?")
                1 -> print("State 1 is .resetting?")
                2 -> print("State 2 is .unsupported?")
                3 -> print("State 3 is .unauthorised?")
                4 -> print("State 4 is .poweredOff?")
                5 -> {
                    print("State 5 is .poweredOn?")
                    scan()
                }
                else -> print("State "+central.state.toInt()+" is else?")
            }
        }

        override fun centralManager(
            central: CBCentralManager,
            didDiscoverPeripheral: CBPeripheral,
            advertisementData: Map<Any?, *>,
            RSSI: NSNumber
        ) {
            print("Discovered device "+didDiscoverPeripheral.name)
        }

        override fun centralManager(central: CBCentralManager, didConnectPeripheral: CBPeripheral) {
        }

        override fun centralManager(central: CBCentralManager, didDisconnectPeripheral: CBPeripheral, error: NSError?) {
            print("Disconnected device " + didDisconnectPeripheral.name)
        }

    }
}