package dev.bluefalcon

import AdvertisementDataRetrievalKeys
import platform.CoreBluetooth.*
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSNumber
import platform.darwin.NSObject

actual class BluetoothPeripheralManager actual constructor(
    private val blueFalcon: BlueFalcon
) : NSObject(), CBCentralManagerDelegateProtocol {

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

            val sharedAdvertisementData = mapNativeAdvertisementDataToShared(advertisementData)
            blueFalcon.delegates.forEach {
                it.didDiscoverDevice(
                    bluetoothPeripheral = device,
                    advertisementData = sharedAdvertisementData
                )
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

    override fun centralManager(
        central: CBCentralManager,
        didDisconnectPeripheral: CBPeripheral,
        error: NSError?
    ) {
        log("DidDisconnectPeripheral ${didDisconnectPeripheral.name}")
        val device = BluetoothPeripheral(didDisconnectPeripheral, rssiValue = null)
        blueFalcon.delegates.forEach {
            it.didDisconnect(device)
        }
    }

    //Helper
    fun mapNativeAdvertisementDataToShared(advertisementData: Map<Any?, *>): Map<AdvertisementDataRetrievalKeys, Any> {
        val sharedAdvertisementData = mutableMapOf<AdvertisementDataRetrievalKeys, Any>()

        for (entry in advertisementData.entries) {
            if (entry.key !is String) {
                //Must be string regarding to documentation:
                //https://developer.apple.com/documentation/corebluetooth/cbcentralmanagerdelegate/1518937-centralmanager

                continue
            }
            val key = entry.key as String
            val value = entry.value ?: continue

            val mappedKey = when (key) {
                "kCBAdvDataIsConnectable" -> AdvertisementDataRetrievalKeys.IsConnectable
                "kCBAdvDataLocalName" -> AdvertisementDataRetrievalKeys.LocalName
                "kCBAdvDataManufacturerData" -> AdvertisementDataRetrievalKeys.ManufacturerData
                "kCBAdvDataServiceUUIDs" -> AdvertisementDataRetrievalKeys.ServiceUUIDsKey
                else -> continue
            }

            if (mappedKey == AdvertisementDataRetrievalKeys.ServiceUUIDsKey) {
                val serviceUUIDs = value as MutableList<CBUUID>
                val kotlinUUIDStrings = mutableListOf<String>()
                for (serviceUUID in serviceUUIDs) {
                    val kotlinUUIDString = serviceUUID.UUIDString

                    kotlinUUIDStrings.add(kotlinUUIDString)
                }
                sharedAdvertisementData[mappedKey] = kotlinUUIDStrings
            } else if (mappedKey == AdvertisementDataRetrievalKeys.ManufacturerData) {
                val data = value as NSData

                sharedAdvertisementData[mappedKey] = data.toByteArray()
            } else {
                sharedAdvertisementData[mappedKey] = value
            }
        }

        return sharedAdvertisementData
    }
}