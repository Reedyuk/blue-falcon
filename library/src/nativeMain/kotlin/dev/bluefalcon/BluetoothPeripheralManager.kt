package dev.bluefalcon

import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import platform.CoreBluetooth.*
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSNumber
import platform.darwin.NSObject

class BluetoothPeripheralManager constructor(
    private val log: Logger?,
    private val blueFalcon: NativeBlueFalconEngine
) : NSObject(), CBCentralManagerDelegateProtocol {

    private val _managerState: MutableStateFlow<CBManagerState> = MutableStateFlow(CBManagerStateUnknown)
    val managerState: StateFlow<CBManagerState> = _managerState

    internal val delegate = PeripheralDelegate(log, blueFalcon)

    fun setPeripheralDelegate(peripheral: BluetoothPeripheral, unset: Boolean = false) {
        log?.debug("setPeripheralDelegate ${peripheral.uuid}")
        peripheral.device.delegate = delegate.takeUnless { unset }
    }

    override fun centralManagerDidUpdateState(central: CBCentralManager) {
        _managerState.tryEmit(central.state)
        when (central.state) {
            CBManagerStateUnknown -> log?.debug("CBManager state: unknown")
            CBManagerStateResetting -> log?.debug("CBManager state: resetting")
            CBManagerStateUnsupported -> log?.debug("CBManager state: unsupported")
            CBManagerStateUnauthorized -> log?.debug("CBManager state: unauthorized")
            CBManagerStatePoweredOff -> log?.debug("CBManager state: poweredOff")
            CBManagerStatePoweredOn -> log?.debug("CBManager state: poweredOn")
            else -> log?.debug("CBManager state: ${central.state.toInt()}")
        }
    }

    override fun centralManager(
        central: CBCentralManager,
        didDiscoverPeripheral: CBPeripheral,
        advertisementData: Map<Any?, *>,
        RSSI: NSNumber
    ) {
        if (blueFalcon.isScanning) {
            log?.debug("Discovered device ${didDiscoverPeripheral.name}:${didDiscoverPeripheral.identifier.UUIDString}")
            val device = BluetoothPeripheralImpl(didDiscoverPeripheral, rssiValue = RSSI.floatValue)
            val sharedAdvertisementData = mapNativeAdvertisementDataToShared(advertisementData)
            blueFalcon._peripherals.tryEmit(blueFalcon._peripherals.value.plus(device))
            blueFalcon.delegates.forEach {
                it.didDiscoverDevice(
                    bluetoothPeripheral = device,
                    advertisementData = sharedAdvertisementData
                )
            }
        }
    }

    override fun centralManager(central: CBCentralManager, didConnectPeripheral: CBPeripheral) {
        log?.info("Connected to ${didConnectPeripheral.name} - ${didConnectPeripheral.identifier.UUIDString}")
        val device = BluetoothPeripheralImpl(didConnectPeripheral, rssiValue = null)
        didConnectPeripheral.delegate = delegate
        blueFalcon.delegates.forEach {
            it.didConnect(device)
        }
        if (blueFalcon.autoDiscoverAllServicesAndCharacteristics) {
            didConnectPeripheral.discoverServices(null)
        }
    }

    @ObjCSignatureOverride
    override fun centralManager(central: CBCentralManager, didFailToConnectPeripheral: CBPeripheral, error: NSError?) {
        log?.error("Connection failed to ${didFailToConnectPeripheral.name} - ${didFailToConnectPeripheral.identifier.UUIDString}: ${error?.localizedDescription}")
    }

    @ObjCSignatureOverride
    override fun centralManager(
        central: CBCentralManager,
        didDisconnectPeripheral: CBPeripheral,
        error: NSError?
    ) {
        log?.info("Disconnected from ${didDisconnectPeripheral.name} - ${didDisconnectPeripheral.identifier.UUIDString}")
        val device = BluetoothPeripheralImpl(didDisconnectPeripheral, rssiValue = null)
        blueFalcon.delegates.forEach {
            it.didDisconnect(device)
        }
        didDisconnectPeripheral.delegate = null
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