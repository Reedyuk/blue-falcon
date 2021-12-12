package dev.bluefalcon

import platform.CoreBluetooth.*
import platform.Foundation.NSError
import platform.Foundation.NSNumber
import platform.darwin.NSObject

actual class BluetoothPeripheralManager actual constructor(
    private val blueFalcon: BlueFalcon
): NSObject(), CBCentralManagerDelegateProtocol {

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
        if (blueFalcon.isScanning) {
            log("Discovered device ${didDiscoverPeripheral.name}")
            val device = BluetoothPeripheral(didDiscoverPeripheral, rssiValue = RSSI.floatValue)
            blueFalcon.delegates.forEach {
                it.didDiscoverDevice(device)
            }
        }
    }

    override fun centralManager(central: CBCentralManager, didConnectPeripheral: CBPeripheral) {
        log("DidConnectPeripheral ${didConnectPeripheral.name}")
        val device = BluetoothPeripheral(didConnectPeripheral, rssiValue = null)
        blueFalcon.delegates.forEach {
            it.didConnect(device)
        }
        didConnectPeripheral.delegate = PeripheralDelegate(blueFalcon)
        didConnectPeripheral.discoverServices(null)
    }

    override fun centralManager(central: CBCentralManager, didDisconnectPeripheral: CBPeripheral, error: NSError?) {
        log("DidDisconnectPeripheral ${didDisconnectPeripheral.name}")
        val device = BluetoothPeripheral(didDisconnectPeripheral, rssiValue = null)
        blueFalcon.delegates.forEach {
            it.didDisconnect(device)
        }
    }

}